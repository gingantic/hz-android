#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <aaudio/AAudio.h>

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libavutil/imgutils.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
}

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <vector>
#include <deque>
#include <string>
#include <unordered_set>
#include <algorithm>
#include <cstring>
#include <cinttypes>

#define LOG_TAG "FfmpegPlayerNative"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

static JavaVM* g_jvm = nullptr;

static JNIEnv* getJniEnv() {
    if (!g_jvm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

// ─── RandomAccessFile / JniFile for custom AVIO ──────────────────────────────

class RandomAccessFile {
public:
    virtual ~RandomAccessFile() = default;
    virtual int64_t read(uint8_t* buf, int64_t size) = 0;
    virtual int64_t seek(int64_t offset, int whence) = 0;
    virtual int64_t size() = 0;
    virtual bool ok() const = 0;
};

class JniFile : public RandomAccessFile {
private:
    jobject bridge_ = nullptr;
    jmethodID midReadAt_ = nullptr;
    jmethodID midGetSize_ = nullptr;
    jbyteArray bufferArray_ = nullptr;
    int bufferCap_ = 0;
    int64_t pos_ = 0;
    mutable int64_t cachedSize_ = -1;
    bool ok_ = false;

public:
    JniFile(JNIEnv* env, jobject bridge) {
        if (!bridge) return;
        bridge_ = env->NewGlobalRef(bridge);
        jclass cls = env->GetObjectClass(bridge);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return; }

        midReadAt_ = env->GetMethodID(cls, "readAt", "(J[BI)I");
        if (env->ExceptionCheck()) env->ExceptionClear();

        midGetSize_ = env->GetMethodID(cls, "getSize", "()J");
        if (env->ExceptionCheck()) env->ExceptionClear();

        env->DeleteLocalRef(cls);

        if (midReadAt_ && midGetSize_) {
            ok_ = true;
        } else {
            LOGE("JniFile: missing readAt/getSize methods on bridge object");
        }
    }

    ~JniFile() override {
        JNIEnv* env = getJniEnv();
        if (env) {
            if (bufferArray_) env->DeleteGlobalRef(bufferArray_);
            if (bridge_) {
                jclass cls = env->GetObjectClass(bridge_);
                if (!env->ExceptionCheck() && cls) {
                    jmethodID midClose = env->GetMethodID(cls, "close", "()V");
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    } else if (midClose) {
                        env->CallVoidMethod(bridge_, midClose);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                    env->DeleteLocalRef(cls);
                } else {
                    env->ExceptionClear();
                }
                env->DeleteGlobalRef(bridge_);
            }
        }
    }

    int64_t read(uint8_t* buf, int64_t size) override {
        if (!ok_ || size <= 0) return 0;
        JNIEnv* env = getJniEnv();
        if (!env) return AVERROR_EXIT;

        int chunkSize = static_cast<int>(std::min<int64_t>(size, 256 * 1024));
        if (!bufferArray_ || bufferCap_ < chunkSize) {
            if (bufferArray_) env->DeleteGlobalRef(bufferArray_);
            jbyteArray localArr = env->NewByteArray(chunkSize);
            if (!localArr) return AVERROR_EXIT;
            bufferArray_ = static_cast<jbyteArray>(env->NewGlobalRef(localArr));
            env->DeleteLocalRef(localArr);
            bufferCap_ = chunkSize;
        }

        jint readBytes = env->CallIntMethod(bridge_, midReadAt_, static_cast<jlong>(pos_), bufferArray_, chunkSize);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return AVERROR_EXIT;
        }
        if (readBytes < 0) return AVERROR_EOF;
        if (readBytes > 0) {
            env->GetByteArrayRegion(bufferArray_, 0, readBytes, reinterpret_cast<jbyte*>(buf));
            pos_ += readBytes;
        }
        return readBytes;
    }

    int64_t seek(int64_t offset, int whence) override {
        if (!ok_) return AVERROR_EXIT;
        switch (whence) {
            case SEEK_SET: pos_ = offset; break;
            case SEEK_CUR: pos_ += offset; break;
            case SEEK_END: {
                int64_t s = size();
                if (s >= 0) pos_ = s + offset;
                else return AVERROR_EXIT;
                break;
            }
            case AVSEEK_SIZE:
                return size();
            default:
                return AVERROR_EXIT;
        }
        return pos_;
    }

    int64_t size() override {
        if (!ok_) return -1;
        if (cachedSize_ >= 0) return cachedSize_;
        JNIEnv* env = getJniEnv();
        if (!env) return -1;
        jlong s = env->CallLongMethod(bridge_, midGetSize_);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }
        if (s >= 0) {
            cachedSize_ = static_cast<int64_t>(s);
        }
        return cachedSize_;
    }

    bool ok() const override { return ok_ && bridge_ != nullptr; }
};

struct PlayerIOBridge {
    RandomAccessFile* file = nullptr;
    std::atomic<bool> abortRequested{false};
};

// ─── Dynamic Multi-Dimensional Packet Queue ───────────────────────────────────

struct PacketQueue {
    struct Item {
        AVPacket* pkt = nullptr;
        int64_t ptsUs = -1;
        bool isFlush = false;
        bool isEof = false;
    };

    std::deque<Item> items;
    std::mutex mtx;
    std::condition_variable notEmpty;
    std::condition_variable notFull;
    std::atomic<bool> aborted{false};

    // Multi-dimensional queue limits
    size_t minPackets = 15;
    size_t maxPackets = 150;
    size_t maxBytes = 32 * 1024 * 1024; // 32 MB default
    int64_t maxDurationUs = 2000000LL;   // 2.0 seconds default

    // Running metrics
    size_t totalBytes = 0;
    int64_t durationUs = 0;
    int64_t firstPtsUs = -1;
    int64_t lastPtsUs = -1;

    void setLimits(size_t minPkts, size_t maxPkts, size_t maxB, int64_t maxDurUs) {
        std::lock_guard<std::mutex> lk(mtx);
        minPackets = minPkts;
        maxPackets = maxPkts;
        maxBytes = maxB;
        maxDurationUs = maxDurUs;
    }

    bool isFullLocked() const {
        if (items.size() < minPackets) return false;
        if (items.size() >= maxPackets) return true;
        if (totalBytes >= maxBytes) return true;
        if (maxDurationUs > 0 && durationUs >= maxDurationUs) return true;
        return false;
    }

    void recalculateStatsLocked() {
        totalBytes = 0;
        firstPtsUs = -1;
        lastPtsUs = -1;
        durationUs = 0;
        for (const auto& it : items) {
            if (it.pkt) {
                totalBytes += static_cast<size_t>(it.pkt->size);
            }
            if (it.ptsUs >= 0) {
                if (firstPtsUs < 0) firstPtsUs = it.ptsUs;
                lastPtsUs = it.ptsUs;
            }
        }
        if (firstPtsUs >= 0 && lastPtsUs >= firstPtsUs) {
            durationUs = lastPtsUs - firstPtsUs;
        }
    }

    void push(AVPacket* pkt, AVRational timeBase = {1, 1000}) {
        if (!pkt) return;
        int64_t ptsUs = (pkt->pts != AV_NOPTS_VALUE)
            ? av_rescale_q(pkt->pts, timeBase, AV_TIME_BASE_Q)
            : ((pkt->dts != AV_NOPTS_VALUE)
                ? av_rescale_q(pkt->dts, timeBase, AV_TIME_BASE_Q)
                : -1);

        std::unique_lock<std::mutex> lk(mtx);
        notFull.wait_for(lk, std::chrono::milliseconds(20), [&] {
            return aborted.load() || !isFullLocked();
        });
        if (aborted.load()) {
            av_packet_free(&pkt);
            return;
        }

        totalBytes += static_cast<size_t>(pkt->size);
        if (ptsUs >= 0) {
            if (firstPtsUs < 0) firstPtsUs = ptsUs;
            lastPtsUs = ptsUs;
            if (lastPtsUs >= firstPtsUs) {
                durationUs = lastPtsUs - firstPtsUs;
            }
        }

        items.push_back({pkt, ptsUs, false, false});
        notEmpty.notify_one();
    }

    void pushFlush() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, -1, true, false});
        notEmpty.notify_one();
    }

    void pushEof() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, -1, false, true});
        notEmpty.notify_one();
    }

    bool empty() {
        std::lock_guard<std::mutex> lk(mtx);
        return items.empty();
    }

    size_t size() {
        std::lock_guard<std::mutex> lk(mtx);
        return items.size();
    }

    size_t getBytes() {
        std::lock_guard<std::mutex> lk(mtx);
        return totalBytes;
    }

    int64_t getDurationUs() {
        std::lock_guard<std::mutex> lk(mtx);
        return durationUs;
    }

    bool pop(Item& out, int timeoutMs = 50) {
        std::unique_lock<std::mutex> lk(mtx);
        if (!notEmpty.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] {
            return aborted.load() || !items.empty();
        })) {
            return false;
        }
        if (items.empty()) return false;
        out = items.front();
        items.pop_front();
        if (out.pkt) {
            if (totalBytes >= static_cast<size_t>(out.pkt->size)) {
                totalBytes -= static_cast<size_t>(out.pkt->size);
            } else {
                totalBytes = 0;
            }
        }
        // Incremental stats update — avoids O(N) full queue scan on every dequeue.
        // totalBytes is already decremented above; just advance firstPtsUs from the new front.
        if (!items.empty()) {
            firstPtsUs = -1;
            for (const auto& it : items) {
                if (it.ptsUs >= 0) { firstPtsUs = it.ptsUs; break; }
            }
            if (firstPtsUs >= 0 && lastPtsUs >= firstPtsUs) {
                durationUs = lastPtsUs - firstPtsUs;
            } else {
                durationUs = 0;
            }
        } else {
            firstPtsUs = -1; lastPtsUs = -1; durationUs = 0;
        }
        notFull.notify_one();
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lk(mtx);
        for (auto& it : items) {
            if (it.pkt) av_packet_free(&it.pkt);
        }
        items.clear();
        totalBytes = 0;
        durationUs = 0;
        firstPtsUs = -1;
        lastPtsUs = -1;
        notFull.notify_all();
        notEmpty.notify_all();
    }

    void abort() {
        aborted.store(true);
        notEmpty.notify_all();
        notFull.notify_all();
    }

    void reset() {
        clear();
        aborted.store(false);
    }
};

static inline int64_t getMonotonicTimeMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static int player_io_read(void* opaque, uint8_t* buf, int bufSize) {
    auto* io = static_cast<PlayerIOBridge*>(opaque);
    if (!io || io->abortRequested.load()) {
        return AVERROR_EXIT;
    }
    int64_t n = io->file->read(buf, bufSize);
    return static_cast<int>(n);
}

static int64_t player_io_seek(void* opaque, int64_t offset, int whence) {
    auto* io = static_cast<PlayerIOBridge*>(opaque);
    if (!io || io->abortRequested.load()) {
        return AVERROR_EXIT;
    }
    if (whence == AVSEEK_SIZE) {
        return io->file->size();
    }
    return io->file->seek(offset, whence);
}

// ─── Native Audio Sink (AAudio C++ Engine) ───────────────────────────────────

class NativeAudioSink {
private:
    AAudioStream* stream = nullptr;
    int sampleRate = 48000;
    int channelCount = 2;
    aaudio_format_t activeFormat = AAUDIO_FORMAT_PCM_FLOAT;
    std::atomic<bool> isPlaying{false};
    std::atomic<int64_t> totalFramesWritten{0};
    std::atomic<int64_t> headPositionOffset{0};
    std::mutex streamMutex;

    // Continuous timeline tracking for acoustic PTS
    std::atomic<int64_t> basePtsUs{0};
    std::atomic<int64_t> baseFramePosition{0};
    std::atomic<bool> hasBasePts{false};
    int64_t expectedNextPtsUs = 0;

public:
    NativeAudioSink() = default;
    ~NativeAudioSink() { release(); }

    bool isReady() const { return stream != nullptr; }
    aaudio_format_t getFormat() const { return activeFormat; }

    bool init(int inSampleRate, int inChannels) {
        std::lock_guard<std::mutex> lk(streamMutex);
        if (stream && sampleRate == inSampleRate && channelCount == inChannels) {
            return true;
        }

        releaseLocked();

        sampleRate = (inSampleRate > 0) ? inSampleRate : 48000;
        channelCount = (inChannels > 0) ? inChannels : 2;
        totalFramesWritten.store(0);
        headPositionOffset.store(0);
        hasBasePts.store(false);
        expectedNextPtsUs = 0;

        auto tryOpen = [&](aaudio_format_t fmt) -> bool {
            AAudioStreamBuilder* builder = nullptr;
            aaudio_result_t res = AAudio_createStreamBuilder(&builder);
            if (res != AAUDIO_OK || !builder) return false;

            AAudioStreamBuilder_setFormat(builder, fmt);
            AAudioStreamBuilder_setChannelCount(builder, channelCount);
            AAudioStreamBuilder_setSampleRate(builder, sampleRate);
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
            AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MOVIE);
            AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);

            res = AAudioStreamBuilder_openStream(builder, &stream);
            AAudioStreamBuilder_delete(builder);

            if (res == AAUDIO_OK && stream) {
                activeFormat = AAudioStream_getFormat(stream);
                return true;
            }
            stream = nullptr;
            return false;
        };

        // Try high-dynamic-range PCM Float32 first, fallback to PCM S16
        if (!tryOpen(AAUDIO_FORMAT_PCM_FLOAT)) {
            LOGI("NativeAudioSink: AAudio PCM_FLOAT unavailable, falling back to PCM_I16");
            if (!tryOpen(AAUDIO_FORMAT_PCM_I16)) {
                LOGE("NativeAudioSink: Failed to open AAudio stream in both Float32 and Int16 formats");
                sessionId = 0;
                return false;
            }
        }

        sessionId = static_cast<int>(AAudioStream_getSessionId(stream));
        int32_t burst = AAudioStream_getFramesPerBurst(stream);
        // 6 bursts: extra headroom prevents underruns from GC/scheduler jitter without
        // meaningfully increasing latency vs. the previous 4-burst setting.
        AAudioStream_setBufferSizeInFrames(stream, burst * 6);

        if (isPlaying.load()) {
            AAudioStream_requestStart(stream);
        }

        LOGI("NativeAudioSink: Successfully initialized AAudio stream (rate=%d, channels=%d, burst=%d, format=%s, sessionId=%d)",
             sampleRate, channelCount, burst, (activeFormat == AAUDIO_FORMAT_PCM_FLOAT ? "FLOAT32" : "INT16"), sessionId);
        return true;
    }

    int getSessionId() const { return sessionId; }

    void play() {
        isPlaying.store(true);
        std::lock_guard<std::mutex> lk(streamMutex);
        if (stream) {
            aaudio_stream_state_t state = AAudioStream_getState(stream);
            if (state != AAUDIO_STREAM_STATE_STARTING && state != AAUDIO_STREAM_STATE_STARTED) {
                AAudioStream_requestStart(stream);
            }
        }
    }

    void pause() {
        isPlaying.store(false);
        std::lock_guard<std::mutex> lk(streamMutex);
        if (stream) {
            aaudio_stream_state_t state = AAudioStream_getState(stream);
            if (state != AAUDIO_STREAM_STATE_PAUSING && state != AAUDIO_STREAM_STATE_PAUSED) {
                AAudioStream_requestPause(stream);
            }
        }
    }

    void flush() {
        std::lock_guard<std::mutex> lk(streamMutex);
        if (stream) {
            AAudioStream_requestFlush(stream);
            totalFramesWritten.store(0);
            headPositionOffset.store(0);
            hasBasePts.store(false);
            expectedNextPtsUs = 0;
        }
    }

    void release() {
        std::lock_guard<std::mutex> lk(streamMutex);
        releaseLocked();
    }

    int32_t write(const float* pcm, int32_t numFrames, int64_t framePtsUs, int64_t timeoutNanoseconds = 50000000LL) {
        if (!pcm || numFrames <= 0) return 0;
        std::lock_guard<std::mutex> lk(streamMutex);
        if (!stream) return 0;

        aaudio_stream_state_t state = AAudioStream_getState(stream);
        if (isPlaying.load() && state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING) {
            AAudioStream_requestStart(stream);
        }

        aaudio_result_t result = AAudioStream_write(stream, pcm, numFrames, timeoutNanoseconds);
        if (result > 0) {
            totalFramesWritten.fetch_add(result, std::memory_order_relaxed);
            return result;
        } else if (result < 0) {
            LOGW("NativeAudioSink: AAudioStream_write (float) returned error: %d", result);
        }
        return 0;
    }

    int32_t write(const int16_t* pcm, int32_t numFrames, int64_t framePtsUs, int64_t timeoutNanoseconds = 50000000LL) {
        if (!pcm || numFrames <= 0) return 0;
        std::lock_guard<std::mutex> lk(streamMutex);
        if (!stream) return 0;

        aaudio_stream_state_t state = AAudioStream_getState(stream);
        if (isPlaying.load() && state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING) {
            AAudioStream_requestStart(stream);
        }

        aaudio_result_t result = AAudioStream_write(stream, pcm, numFrames, timeoutNanoseconds);
        if (result > 0) {
            totalFramesWritten.fetch_add(result, std::memory_order_relaxed);
            return result;
        } else if (result < 0) {
            LOGW("NativeAudioSink: AAudioStream_write (int16) returned error: %d", result);
        }
        return 0;
    }

    int64_t getAcousticPlaybackTimestampUs(int64_t latestPtsUs) {
        std::lock_guard<std::mutex> lk(streamMutex);
        if (!stream || sampleRate <= 0) return latestPtsUs;

        int64_t writtenFrames = AAudioStream_getFramesWritten(stream);
        int64_t readFrames = 0;
        int64_t timeNanoseconds = 0;
        aaudio_result_t res = AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &readFrames, &timeNanoseconds);
        if (res != AAUDIO_OK || readFrames < 0) {
            readFrames = AAudioStream_getFramesRead(stream);
        }

        int64_t pendingFrames = writtenFrames - readFrames;
        if (pendingFrames < 0) pendingFrames = 0;
        int64_t maxPending = static_cast<int64_t>(sampleRate) / 2;
        if (pendingFrames > maxPending) pendingFrames = maxPending;

        int64_t latencyUs = (pendingFrames * 1000000LL) / sampleRate;

        if (timeNanoseconds > 0) {
            int64_t nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            int64_t elapsedSinceTimestampUs = (nowNs - timeNanoseconds) / 1000LL;
            if (elapsedSinceTimestampUs > 0 && elapsedSinceTimestampUs < 50000) {
                latencyUs = std::max<int64_t>(0, latencyUs - elapsedSinceTimestampUs);
            }
        }

        int64_t acousticPtsUs = latestPtsUs - latencyUs;
        return (acousticPtsUs >= 0) ? acousticPtsUs : 0;
    }

private:
    int sessionId = 0;

    void releaseLocked() {
        if (stream) {
            AAudioStream_close(stream);
            stream = nullptr;
        }
        sessionId = 0;
        totalFramesWritten.store(0);
        headPositionOffset.store(0);
        hasBasePts.store(false);
        expectedNextPtsUs = 0;
    }
};

// ─── Native 10-Band Graphic Equalizer DSP (RBJ Biquad Filter Chain) ──────────

struct NativeEqualizer {
    static constexpr int BAND_COUNT = 10;
    static constexpr float CENTER_FREQUENCIES[BAND_COUNT] = {
        31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };
    static constexpr float Q = 1.41421356f; // 1-octave bandwidth

    struct Coeffs {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        bool isFlat = true;
    };

    struct FilterState {
        float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
    };

    std::atomic<bool> enabled{false};
    std::mutex mutex;
    int currentSampleRate = 48000;
    int currentChannels = 2;
    int gainsMb[BAND_COUNT] = {0};
    Coeffs coeffs[BAND_COUNT];
    std::vector<std::vector<FilterState>> channelStates; // [channel][band]

    NativeEqualizer() {
        channelStates.resize(2, std::vector<FilterState>(BAND_COUNT));
    }

    void init(int sampleRate, int channels) {
        std::lock_guard<std::mutex> lock(mutex);
        currentSampleRate = (sampleRate > 0) ? sampleRate : 48000;
        currentChannels = (channels > 0) ? channels : 2;
        channelStates.resize(currentChannels, std::vector<FilterState>(BAND_COUNT));
        for (auto& ch : channelStates) {
            for (auto& st : ch) {
                st = FilterState{};
            }
        }
        recalculateCoefficientsLocked();
    }

    void setGains(bool isEnabled, const int* inGainsMb, int count) {
        std::lock_guard<std::mutex> lock(mutex);
        enabled.store(isEnabled);
        if (inGainsMb && count > 0) {
            for (int i = 0; i < BAND_COUNT && i < count; i++) {
                gainsMb[i] = std::clamp(inGainsMb[i], -1500, 1500); // ±15 dB
            }
        }
        recalculateCoefficientsLocked();
    }

    void recalculateCoefficientsLocked() {
        float Fs = static_cast<float>(currentSampleRate);
        for (int i = 0; i < BAND_COUNT; i++) {
            int gainMb = gainsMb[i];
            if (gainMb == 0) {
                coeffs[i] = Coeffs{};
                continue;
            }

            float f0 = CENTER_FREQUENCIES[i];
            if (f0 >= Fs * 0.49f) {
                coeffs[i] = Coeffs{};
                continue;
            }

            float A = std::pow(10.0f, static_cast<float>(gainMb) / 4000.0f);
            float w0 = 2.0f * 3.14159265358979323846f * f0 / Fs;
            float cosW = std::cos(w0);
            float sinW = std::sin(w0);
            float alpha = sinW / (2.0f * Q);

            float b0 = 1.0f + alpha * A;
            float b1 = -2.0f * cosW;
            float b2 = 1.0f - alpha * A;
            float a0 = 1.0f + alpha / A;
            float a1 = -2.0f * cosW;
            float a2 = 1.0f - alpha / A;

            float invA0 = 1.0f / a0;
            coeffs[i].b0 = b0 * invA0;
            coeffs[i].b1 = b1 * invA0;
            coeffs[i].b2 = b2 * invA0;
            coeffs[i].a1 = a1 * invA0;
            coeffs[i].a2 = a2 * invA0;
            coeffs[i].isFlat = false;
        }
    }

    void process(float* pcm, int numFrames, int channels) {
        if (!enabled.load() || !pcm || numFrames <= 0 || channels <= 0) return;
        std::lock_guard<std::mutex> lock(mutex);

        if (channelStates.size() < static_cast<size_t>(channels)) {
            channelStates.resize(channels, std::vector<FilterState>(BAND_COUNT));
        }

        bool hasActiveBand = false;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (!coeffs[i].isFlat) {
                hasActiveBand = true;
                break;
            }
        }
        if (!hasActiveBand) return;

        for (int f = 0; f < numFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int idx = f * channels + ch;
                float sample = pcm[idx];

                auto& states = channelStates[ch];
                for (int b = 0; b < BAND_COUNT; b++) {
                    const auto& c = coeffs[b];
                    if (c.isFlat) continue;

                    auto& st = states[b];
                    float y = c.b0 * sample + c.b1 * st.x1 + c.b2 * st.x2 - c.a1 * st.y1 - c.a2 * st.y2;
                    st.x2 = st.x1;
                    st.x1 = sample;
                    st.y2 = st.y1;
                    st.y1 = y;
                    sample = y;
                }

                pcm[idx] = sample;
            }
        }
    }

    void process(int16_t* pcm, int numFrames, int channels) {
        if (!enabled.load() || !pcm || numFrames <= 0 || channels <= 0) return;
        std::lock_guard<std::mutex> lock(mutex);

        if (channelStates.size() < static_cast<size_t>(channels)) {
            channelStates.resize(channels, std::vector<FilterState>(BAND_COUNT));
        }

        bool hasActiveBand = false;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (!coeffs[i].isFlat) {
                hasActiveBand = true;
                break;
            }
        }
        if (!hasActiveBand) return;

        for (int f = 0; f < numFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int idx = f * channels + ch;
                float sample = static_cast<float>(pcm[idx]);

                auto& states = channelStates[ch];
                for (int b = 0; b < BAND_COUNT; b++) {
                    const auto& c = coeffs[b];
                    if (c.isFlat) continue;

                    auto& st = states[b];
                    float y = c.b0 * sample + c.b1 * st.x1 + c.b2 * st.x2 - c.a1 * st.y1 - c.a2 * st.y2;
                    st.x2 = st.x1;
                    st.x1 = sample;
                    st.y2 = st.y1;
                    st.y1 = y;
                    sample = y;
                }

                if (sample > 32767.0f) sample = 32767.0f;
                else if (sample < -32768.0f) sample = -32768.0f;
                pcm[idx] = static_cast<int16_t>(sample);
            }
        }
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto& ch : channelStates) {
            for (auto& st : ch) {
                st = FilterState{};
            }
        }
    }
};

// ─── Playback States (matching Kotlin PlayerState) ───────────────────────────
enum NativePlayerState {
    STATE_IDLE = 0,
    STATE_BUFFERING = 1,
    STATE_READY = 2,
    STATE_ENDED = 3,
    STATE_ERROR = 4
};

// ─── Player Context ─────────────────────────────────────────────────────────

struct FfmpegPlayerContext {
    jobject kotlinPlayerRef = nullptr;
    jclass kotlinPlayerClass = nullptr;

    NativeAudioSink nativeAudioSink;
    NativeEqualizer nativeEqualizer;

    jmethodID midOnAudioInit = nullptr;
    jmethodID midOnAudioData = nullptr;
    jmethodID midGetAudioLatencyUs = nullptr;
    jmethodID midOnAudioFlush = nullptr;
    jmethodID midOnAudioSessionId = nullptr;
    jmethodID midOnVideoSizeChanged = nullptr;
    jmethodID midOnStateChanged = nullptr;
    jmethodID midOnError = nullptr;
    jmethodID midOnPositionUpdate = nullptr;
    jmethodID midOnSubtitleHeader = nullptr;
    jmethodID midOnSubtitleData = nullptr;
    jmethodID midOnBitmapSubtitle = nullptr;
    jmethodID midOnFontAttachment = nullptr;
    jmethodID midOnFrameRendered = nullptr;

    // IO / Demuxer
    JniFile* jniFile = nullptr;
    PlayerIOBridge ioBridge;
    AVIOContext* avioCtx = nullptr;
    uint8_t* avioBuf = nullptr;
    static constexpr int AVIO_BUF_SIZE = 256 * 1024; // 256 KB buffer (optimal for random access & archive seek probing)
    std::atomic<int64_t> lastIoTimeMs{0};
    std::atomic<int64_t> ioTimeoutMs{15000}; // 15 seconds watchdog for network / IO stalls

    AVFormatContext* fmtCtx = nullptr;
    int64_t durationMs = 0;

    // Video Stream
    int videoStreamIdx = -1;
    AVCodecContext* videoCodecCtx = nullptr;
    AVCodecParameters* videoCodecPar = nullptr;
    AVRational videoTimeBase{1, 1000};
    int videoWidth = 0;
    int videoHeight = 0;
    int videoRotation = 0;
    int videoSarNum = 1;
    int videoSarDen = 1;
    SwsContext* swsCtx = nullptr;
    int lastWindowWidth = -1;
    int lastWindowHeight = -1;
    std::atomic<bool> useHardware{true};
    std::atomic<bool> forceSdr{false};

    // Audio Stream
    int audioStreamIdx = -1;
    AVCodecContext* audioCodecCtx = nullptr;
    AVRational audioTimeBase{1, 1000};
    int outSampleRate = 48000;
    int outChannels = 2;
    AVChannelLayout outChLayout{};
    std::mutex audioCodecMutex;

    // Subtitle Streams
    std::vector<int> subtitleStreamIndices;
    int selectedSubtitleStreamIdx = -1;
    AVCodecContext* subtitleCodecCtx = nullptr;
    std::mutex subtitleMutex;

    // Surface / Window
    std::mutex windowMutex;
    ANativeWindow* nativeWindow = nullptr;
    std::atomic<bool> surfaceChanged{false};

    // Queues
    PacketQueue videoQueue;
    PacketQueue audioQueue;

    // Threads & Control
    std::thread demuxThread;
    std::thread audioThread;
    std::thread videoThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> isPaused{true};
    std::atomic<bool> isStopped{false};
    std::atomic<bool> isScrubbing{false};
    std::atomic<bool> fastSeek{false};
    std::atomic<int64_t> seekTargetMs{-1};
    std::atomic<int64_t> videoSeekTargetPtsUs{-1};
    std::atomic<int64_t> audioSeekTargetPtsUs{-1};
    std::atomic<float> playbackSpeed{1.0f};
    std::atomic<int64_t> currentPositionMs{0};
    // Monotonically incremented on every seek. Used as a stale-frame guard so that
    // frames decoded before a seek are not rendered after the flush fires.
    std::atomic<int64_t> seekVersion{0};

    // Stream termination & EOF tracking
    std::atomic<bool> demuxEof{false};
    std::atomic<bool> videoFinished{false};
    std::atomic<bool> audioFinished{false};
    std::atomic<bool> endNotified{false};

    // Telemetry & Debug Stats
    std::string videoCodecName;
    std::string audioCodecName;
    std::string audioLanguage;
    float sourceFps = 0.0f;
    int64_t videoBitrate = 0;
    int64_t audioBitrate = 0;
    std::atomic<int64_t> totalRenderedFrames{0};
    std::atomic<int64_t> totalDroppedFrames{0};

    // A/V Sync & Dynamic Clock Adjustment
    std::atomic<int64_t> audioDelayUs{0};
    std::atomic<int64_t> lastVideoPtsUs{0};
    std::atomic<int64_t> lastAudioDriftUs{0};

    // Audio DSP state (Smooth Ramp-In & Soft-Knee Limiting in native C++)
    std::atomic<int> rampInRemainingFrames{0};
    std::atomic<int> totalRampFrames{0};
    std::vector<jint> subtitlePixelBuf;

    void triggerAudioRampIn(int durationMs = 80) {
        int sr = outSampleRate > 0 ? outSampleRate : 48000;
        int frames = (sr * durationMs) / 1000;
        if (frames < 1) frames = 1;
        totalRampFrames.store(frames);
        rampInRemainingFrames.store(frames);
    }

    // AV Clock (Master clock = Audio, or Monotonic if no audio)
    std::atomic<bool> isBuffering{true};
    std::atomic<int64_t> masterAudioPtsUs{0};
    std::chrono::steady_clock::time_point masterAudioWallTime{std::chrono::steady_clock::now()};
    std::mutex clockMutex;

    std::mutex controlMutex;
    std::condition_variable controlCv;

    void checkPlaybackFinished(JNIEnv* env) {
        bool vDone = (videoStreamIdx < 0) || videoFinished.load();
        bool aDone = (audioStreamIdx < 0) || audioFinished.load();
        if (vDone && aDone && isRunning.load() && !isStopped.load()) {
            if (!endNotified.exchange(true)) {
                LOGI("Playback finished: all streams completed rendering");
                isPaused.store(true);
                notifyState(env, STATE_ENDED);
            }
        }
    }

    FfmpegPlayerContext(JNIEnv* env, jobject obj) {
        kotlinPlayerRef = env->NewGlobalRef(obj);
        jclass cls = env->GetObjectClass(obj);
        kotlinPlayerClass = static_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);

        midOnAudioInit = env->GetMethodID(kotlinPlayerClass, "onAudioInit", "(II)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnAudioData = env->GetMethodID(kotlinPlayerClass, "onAudioData", "([BI)I");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midGetAudioLatencyUs = env->GetMethodID(kotlinPlayerClass, "getAudioLatencyUs", "()J");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnAudioFlush = env->GetMethodID(kotlinPlayerClass, "onAudioFlush", "()V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnAudioSessionId = env->GetMethodID(kotlinPlayerClass, "onAudioSessionId", "(I)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnVideoSizeChanged = env->GetMethodID(kotlinPlayerClass, "onVideoSizeChanged", "(IIIII)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnStateChanged = env->GetMethodID(kotlinPlayerClass, "onStateChanged", "(I)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnError = env->GetMethodID(kotlinPlayerClass, "onError", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnPositionUpdate = env->GetMethodID(kotlinPlayerClass, "onPositionUpdate", "(JJ)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnSubtitleHeader = env->GetMethodID(kotlinPlayerClass, "onSubtitleHeader", "(I[BLjava/lang/String;)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnSubtitleData = env->GetMethodID(kotlinPlayerClass, "onSubtitleData", "(IJJ[B)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnBitmapSubtitle = env->GetMethodID(kotlinPlayerClass, "onBitmapSubtitle", "(IJJIIII[III)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnFontAttachment = env->GetMethodID(kotlinPlayerClass, "onFontAttachment", "(Ljava/lang/String;[B)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        midOnFrameRendered = env->GetMethodID(kotlinPlayerClass, "onFrameRendered", "(J)V");
        if (env->ExceptionCheck()) env->ExceptionClear();

        videoQueue.setLimits(15, 150, 32 * 1024 * 1024, 2000000LL); // 15-150 pkts, 32MB, 2.0s
        audioQueue.setLimits(25, 300, 8 * 1024 * 1024, 3000000LL);  // 25-300 pkts, 8MB, 3.0s

        av_channel_layout_default(&outChLayout, 2);
    }

    ~FfmpegPlayerContext() {
        stopPlayback();
        closeMedia();

        JNIEnv* env = nullptr;
        if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            if (kotlinPlayerRef) env->DeleteGlobalRef(kotlinPlayerRef);
            if (kotlinPlayerClass) env->DeleteGlobalRef(kotlinPlayerClass);
        }
        av_channel_layout_uninit(&outChLayout);
    }

    int64_t getMasterClockUs() {
        std::lock_guard<std::mutex> lock(clockMutex);
        int64_t basePts = masterAudioPtsUs.load();
        int64_t delay = audioDelayUs.load();
        if (isPaused.load() || isBuffering.load()) {
            return std::max<int64_t>(0, basePts + delay);
        }
        auto now = std::chrono::steady_clock::now();
        int64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - masterAudioWallTime).count();
        float speed = playbackSpeed.load();
        if (speed <= 0.0f) speed = 1.0f;
        int64_t current = basePts + static_cast<int64_t>(elapsedUs * speed) + delay;
        return std::max<int64_t>(0, current);
    }

    void setMasterClockUs(int64_t ptsUs) {
        std::lock_guard<std::mutex> lock(clockMutex);
        masterAudioPtsUs.store(ptsUs);
        masterAudioWallTime = std::chrono::steady_clock::now();
    }

    void pauseClock() {
        std::lock_guard<std::mutex> lock(clockMutex);
        if (!isPaused.load()) {
            auto now = std::chrono::steady_clock::now();
            int64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - masterAudioWallTime).count();
            float speed = playbackSpeed.load();
            if (speed <= 0.0f) speed = 1.0f;
            masterAudioPtsUs.store(masterAudioPtsUs.load() + static_cast<int64_t>(elapsedUs * speed));
            isPaused.store(true);
        }
    }

    void resumeClock() {
        std::lock_guard<std::mutex> lock(clockMutex);
        masterAudioWallTime = std::chrono::steady_clock::now();
        isPaused.store(false);
    }

    void notifyState(JNIEnv* env, int state) {
        if (kotlinPlayerRef && midOnStateChanged) {
            env->CallVoidMethod(kotlinPlayerRef, midOnStateChanged, state);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void notifyError(JNIEnv* env, const char* msg) {
        if (kotlinPlayerRef && midOnError) {
            jstring jmsg = env->NewStringUTF(msg ? msg : "Playback error");
            env->CallVoidMethod(kotlinPlayerRef, midOnError, jmsg);
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jmsg) env->DeleteLocalRef(jmsg);
        }
    }

    void notifyVideoSize(JNIEnv* env, int w, int h, int rot, int sarNum, int sarDen) {
        if (kotlinPlayerRef && midOnVideoSizeChanged) {
            env->CallVoidMethod(kotlinPlayerRef, midOnVideoSizeChanged, w, h, rot, sarNum, sarDen);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void notifyPosition(JNIEnv* env, int64_t posMs, int64_t durMs) {
        if (kotlinPlayerRef && midOnPositionUpdate) {
            env->CallVoidMethod(kotlinPlayerRef, midOnPositionUpdate, posMs, durMs);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void notifyAudioFlush(JNIEnv* env) {
        if (kotlinPlayerRef && midOnAudioFlush) {
            env->CallVoidMethod(kotlinPlayerRef, midOnAudioFlush);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void notifyAudioSessionId(JNIEnv* env, int sessionId) {
        if (kotlinPlayerRef && midOnAudioSessionId && sessionId > 0) {
            env->CallVoidMethod(kotlinPlayerRef, midOnAudioSessionId, static_cast<jint>(sessionId));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void notifyFrameRendered(JNIEnv* env, int64_t ptsUs) {
        if (kotlinPlayerRef && midOnFrameRendered) {
            env->CallVoidMethod(kotlinPlayerRef, midOnFrameRendered, static_cast<jlong>(ptsUs));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    void setSurface(JNIEnv* env, jobject surface) {
        std::lock_guard<std::mutex> lock(windowMutex);
        if (nativeWindow) {
            ANativeWindow_release(nativeWindow);
            nativeWindow = nullptr;
        }
        lastWindowWidth = -1;
        lastWindowHeight = -1;
        if (surface) {
            nativeWindow = ANativeWindow_fromSurface(env, surface);
            LOGD("setSurface: acquired ANativeWindow %p", nativeWindow);
        }
        surfaceChanged.store(true);
        controlCv.notify_all();
    }

    void stopPlayback() {
        isRunning.store(false);
        isStopped.store(true);
        demuxEof.store(false);
        videoFinished.store(false);
        audioFinished.store(false);
        endNotified.store(false);
        videoSeekTargetPtsUs.store(-1);
        audioSeekTargetPtsUs.store(-1);
        ioBridge.abortRequested.store(true);
        nativeAudioSink.pause();
        nativeAudioSink.flush();
        videoQueue.abort();
        audioQueue.abort();
        controlCv.notify_all();
        if (demuxThread.joinable()) demuxThread.join();
        if (audioThread.joinable()) audioThread.join();
        if (videoThread.joinable()) videoThread.join();
        videoQueue.reset();
        audioQueue.reset();
    }

    void closeMedia() {
        nativeAudioSink.release();
        {
            std::lock_guard<std::mutex> lock(windowMutex);
            if (nativeWindow) {
                ANativeWindow_release(nativeWindow);
                nativeWindow = nullptr;
            }
            if (swsCtx) {
                sws_freeContext(swsCtx);
                swsCtx = nullptr;
            }
        }
        if (videoCodecCtx) {
            avcodec_free_context(&videoCodecCtx);
            videoCodecCtx = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(audioCodecMutex);
            if (audioCodecCtx) {
                avcodec_free_context(&audioCodecCtx);
                audioCodecCtx = nullptr;
            }
        }
        if (videoCodecPar) {
            avcodec_parameters_free(&videoCodecPar);
            videoCodecPar = nullptr;
        }
        if (swsCtx) {
            sws_freeContext(swsCtx);
            swsCtx = nullptr;
        }
        if (fmtCtx) {
            avformat_close_input(&fmtCtx);
            fmtCtx = nullptr;
        }
        if (avioCtx) {
            avioCtx = nullptr;
            avioBuf = nullptr;
        }
        if (jniFile) {
            delete jniFile;
            jniFile = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(subtitleMutex);
            if (subtitleCodecCtx) {
                avcodec_free_context(&subtitleCodecCtx);
                subtitleCodecCtx = nullptr;
            }
        }
        videoStreamIdx = -1;
        videoRotation = 0;
        videoSarNum = 1;
        videoSarDen = 1;
        audioStreamIdx = -1;
        subtitleStreamIndices.clear();
        selectedSubtitleStreamIdx = -1;
        lastWindowWidth = -1;
        lastWindowHeight = -1;
        demuxEof.store(false);
        videoFinished.store(false);
        audioFinished.store(false);
        endNotified.store(false);
        videoSeekTargetPtsUs.store(-1);
        audioSeekTargetPtsUs.store(-1);
    }
};

static int player_interrupt_callback(void* opaque) {
    auto* ctx = static_cast<FfmpegPlayerContext*>(opaque);
    if (!ctx) return 0;
    if (ctx->ioBridge.abortRequested.load() || ctx->isStopped.load()) {
        return 1;
    }
    int64_t timeout = ctx->ioTimeoutMs.load();
    if (timeout > 0) {
        int64_t lastIo = ctx->lastIoTimeMs.load();
        if (lastIo > 0) {
            int64_t now = getMonotonicTimeMs();
            if ((now - lastIo) > timeout) {
                LOGW("player_interrupt_callback: IO stalled for %" PRId64 " ms > %" PRId64 " ms timeout. Interrupting demuxer.",
                     (now - lastIo), timeout);
                return 1;
            }
        }
    }
    return 0;
}

// ─── Hardware Video Decoder (AMediaCodec Zero-Copy) ─────────────────────────

struct HwVideoDecoder {
    AMediaCodec* codec = nullptr;
    AVBSFContext* bsfCtx = nullptr;
    std::atomic<bool> isConfigured{false};
    std::string codecName;
    int width = 0;
    int height = 0;

    bool init(AVCodecParameters* par, ANativeWindow* window, bool forceSdr = false, int rotationDegrees = 0) {
        release();
        if (!par || !window) return false;

        const char* mime = nullptr;
        const char* bsfName = nullptr;
        if (par->codec_id == AV_CODEC_ID_H264) {
            mime = "video/avc";
            bsfName = "h264_mp4toannexb";
        } else if (par->codec_id == AV_CODEC_ID_HEVC) {
            mime = "video/hevc";
            bsfName = "hevc_mp4toannexb";
        } else if (par->codec_id == AV_CODEC_ID_VP9) {
            mime = "video/x-vnd.on2.vp9";
        } else if (par->codec_id == AV_CODEC_ID_VP8) {
            mime = "video/x-vnd.on2.vp8";
        } else if (par->codec_id == AV_CODEC_ID_AV1) {
            mime = "video/av01";
            bsfName = nullptr; // Demuxer emits complete OBU temporal units; raw frames fed directly to AMediaCodec
        }

        if (!mime) {
            LOGI("HwVideoDecoder: Codec ID %d has no hardware MediaCodec mapping", par->codec_id);
            return false;
        }

        width = par->width;
        height = par->height;
        if (width <= 0 || height <= 0) {
            LOGW("HwVideoDecoder: Invalid dimensions %dx%d", width, height);
            return false;
        }

        codec = AMediaCodec_createDecoderByType(mime);
        if (!codec) {
            LOGW("HwVideoDecoder: Failed to create AMediaCodec for %s", mime);
            return false;
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
        if (rotationDegrees != 0) {
            AMediaFormat_setInt32(format, "rotation-degrees", rotationDegrees);
        }

        // Codec Specific Data
        if (par->extradata && par->extradata_size > 0) {
            if (par->codec_id == AV_CODEC_ID_AV1 || par->codec_id == AV_CODEC_ID_VP9 || !bsfName) {
                AMediaFormat_setBuffer(format, "csd-0", par->extradata, par->extradata_size);
            }
        }

        if (forceSdr) {
            // Force SDR Tone-Mapping (BT.709 with standard gamma transfer curve)
            AMediaFormat_setInt32(format, "color-standard", 1); // COLOR_STANDARD_BT709
            AMediaFormat_setInt32(format, "color-transfer", 3); // COLOR_TRANSFER_SDR_VIDEO
            AMediaFormat_setInt32(format, "color-range", 2);    // COLOR_RANGE_LIMITED
            AMediaFormat_setInt32(format, "hdr-to-sdr-tonemapping", 1);
            AMediaFormat_setInt32(format, "enable-hdr-to-sdr-tonemapping", 1);
            LOGI("HwVideoDecoder: Enabled hardware HDR-to-SDR Tone-Mapping (BT.709)");
        } else {
            // Color & HDR Metadata (only explicitly set when defined to let decoder parse in-band metadata)
            if (par->color_primaries == AVCOL_PRI_BT2020) {
                AMediaFormat_setInt32(format, "color-standard", 6); // BT.2020
            } else if (par->color_primaries == AVCOL_PRI_BT709) {
                AMediaFormat_setInt32(format, "color-standard", 1); // BT.709
            }

            if (par->color_trc == AVCOL_TRC_SMPTE2084) {
                AMediaFormat_setInt32(format, "color-transfer", 6); // ST.2084 (HDR10)
            } else if (par->color_trc == AVCOL_TRC_ARIB_STD_B67) {
                AMediaFormat_setInt32(format, "color-transfer", 7); // HLG
            } else if (par->color_trc == AVCOL_TRC_BT709) {
                AMediaFormat_setInt32(format, "color-transfer", 3); // SDR
            }

            if (par->color_range == AVCOL_RANGE_JPEG) {
                AMediaFormat_setInt32(format, "color-range", 1); // Full
            } else if (par->color_range == AVCOL_RANGE_MPEG) {
                AMediaFormat_setInt32(format, "color-range", 2); // Limited
            }
        }

        media_status_t status = AMediaCodec_configure(codec, format, window, nullptr, 0);
        AMediaFormat_delete(format);

        if (status != AMEDIA_OK) {
            LOGW("HwVideoDecoder: AMediaCodec_configure failed (%d)", status);
            AMediaCodec_delete(codec);
            codec = nullptr;
            return false;
        }

        if (AMediaCodec_start(codec) != AMEDIA_OK) {
            LOGW("HwVideoDecoder: AMediaCodec_start failed");
            AMediaCodec_delete(codec);
            codec = nullptr;
            return false;
        }

        if (bsfName) {
            const AVBitStreamFilter* bsf = av_bsf_get_by_name(bsfName);
            if (bsf) {
                if (av_bsf_alloc(bsf, &bsfCtx) == 0) {
                    avcodec_parameters_copy(bsfCtx->par_in, par);
                    if (av_bsf_init(bsfCtx) < 0) {
                        av_bsf_free(&bsfCtx);
                        bsfCtx = nullptr;
                    }
                }
            }
        }

        codecName = std::string("MediaCodec (") + mime + ")";
        isConfigured.store(true);
        LOGI("HwVideoDecoder: Initialized %s for %dx%d (Zero-Copy Surface)", mime, width, height);
        return true;
    }

    bool setOutputSurface(ANativeWindow* window) {
        if (!codec || !isConfigured.load() || !window) return false;
        media_status_t status = AMediaCodec_setOutputSurface(codec, window);
        if (status == AMEDIA_OK) {
            LOGI("HwVideoDecoder: AMediaCodec_setOutputSurface succeeded");
            return true;
        }
        LOGW("HwVideoDecoder: AMediaCodec_setOutputSurface returned %d", status);
        return false;
    }

    void flush() {
        if (codec && isConfigured.load()) {
            AMediaCodec_flush(codec);
        }
        if (bsfCtx) {
            av_bsf_flush(bsfCtx);
        }
    }

    void release() {
        isConfigured.store(false);
        if (codec) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        if (bsfCtx) {
            av_bsf_free(&bsfCtx);
            bsfCtx = nullptr;
        }
    }
};

// ─── Audio Filtergraph (Tempo / Pitch Scaling) ──────────────────────────────

struct AudioFilterGraph {
    AVFilterGraph* graph = nullptr;
    AVFilterContext* srcCtx = nullptr;
    AVFilterContext* sinkCtx = nullptr;
    float currentSpeed = 1.0f;
    int sampleRate = 0;
    int channels = 0;
    AVSampleFormat sampleFmt = AV_SAMPLE_FMT_NONE;

    void release() {
        if (graph) {
            avfilter_graph_free(&graph);
            graph = nullptr;
            srcCtx = nullptr;
            sinkCtx = nullptr;
        }
    }

    bool init(int inSampleRate, int inChannels, const AVChannelLayout* inLayout, AVSampleFormat inSampleFmt, float speed) {
        release();

        if (speed <= 0.0f) speed = 1.0f;
        currentSpeed = speed;
        sampleRate = inSampleRate;
        channels = inChannels;
        sampleFmt = inSampleFmt;

        if (std::abs(speed - 1.0f) < 0.005f) {
            return true;
        }

        graph = avfilter_graph_alloc();
        if (!graph) return false;

        const AVFilter* abuffer = avfilter_get_by_name("abuffer");
        const AVFilter* abuffersink = avfilter_get_by_name("abuffersink");
        if (!abuffer || !abuffersink) {
            release();
            return false;
        }

        char chLayoutStr[64] = {0};
        if (inLayout) {
            av_channel_layout_describe(inLayout, chLayoutStr, sizeof(chLayoutStr));
        }
        if (chLayoutStr[0] == '\0') {
            if (inChannels == 1) snprintf(chLayoutStr, sizeof(chLayoutStr), "mono");
            else if (inChannels == 6) snprintf(chLayoutStr, sizeof(chLayoutStr), "5.1");
            else if (inChannels == 8) snprintf(chLayoutStr, sizeof(chLayoutStr), "7.1");
            else snprintf(chLayoutStr, sizeof(chLayoutStr), "stereo");
        }

        char args[256];
        snprintf(args, sizeof(args),
                 "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                 inSampleRate, inSampleRate, av_get_sample_fmt_name(inSampleFmt), chLayoutStr);

        int ret = avfilter_graph_create_filter(&srcCtx, abuffer, "in", args, nullptr, graph);
        if (ret < 0) {
            release();
            return false;
        }

        ret = avfilter_graph_create_filter(&sinkCtx, abuffersink, "out", nullptr, nullptr, graph);
        if (ret < 0) {
            release();
            return false;
        }

        // Build chained atempo filter (atempo accepts [0.5, 2.0] per stage)
        std::string filterStr = "";
        float tempSpeed = speed;
        while (tempSpeed > 2.0f) {
            filterStr += "atempo=2.0,";
            tempSpeed /= 2.0f;
        }
        while (tempSpeed < 0.5f) {
            filterStr += "atempo=0.5,";
            tempSpeed /= 0.5f;
        }
        char tempoBuf[32];
        snprintf(tempoBuf, sizeof(tempoBuf), "atempo=%.4f", tempSpeed);
        filterStr += tempoBuf;

        AVFilterInOut* outputs = avfilter_inout_alloc();
        AVFilterInOut* inputs  = avfilter_inout_alloc();
        if (!outputs || !inputs) {
            if (outputs) avfilter_inout_free(&outputs);
            if (inputs) avfilter_inout_free(&inputs);
            release();
            return false;
        }

        outputs->name       = av_strdup("in");
        outputs->filter_ctx = srcCtx;
        outputs->pad_idx    = 0;
        outputs->next       = nullptr;

        inputs->name        = av_strdup("out");
        inputs->filter_ctx  = sinkCtx;
        inputs->pad_idx     = 0;
        inputs->next        = nullptr;

        ret = avfilter_graph_parse_ptr(graph, filterStr.c_str(), &inputs, &outputs, nullptr);
        avfilter_inout_free(&inputs);
        avfilter_inout_free(&outputs);

        if (ret < 0) {
            release();
            return false;
        }

        ret = avfilter_graph_config(graph, nullptr);
        if (ret < 0) {
            release();
            return false;
        }

        LOGI("AudioFilterGraph: Initialized for speed %.2f (%s)", speed, filterStr.c_str());
        return true;
    }
};

// ─── OpenGL ES Shader-Based Video Renderer ───────────────────────────────────

class GlVideoRenderer {
private:
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLContext eglContext = EGL_NO_CONTEXT;
    ANativeWindow* boundWindow = nullptr;

    GLuint program = 0;
    GLuint texY = 0;
    GLuint texU = 0;
    GLuint texV = 0;
    int texWidth = 0;
    int texHeight = 0;
    int texIs10Bit = -1;

    GLint locTexY = -1;
    GLint locTexU = -1;
    GLint locTexV = -1;
    GLint locIs10Bit = -1;
    GLint locColorStd = -1;
    GLint locHdrTransfer = -1;
    GLint locForceSdr = -1;

    static GLuint compileShader(GLenum type, const char* src) {
        GLuint shader = glCreateShader(type);
        if (!shader) return 0;
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen > 0) {
                std::vector<char> infoLog(infoLen);
                glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
                LOGE("GlVideoRenderer: Shader compilation failed: %s", infoLog.data());
            }
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    void initShaders() {
        const char* vShaderSrc =
            "#version 300 es\n"
            "layout(location = 0) in vec4 a_position;\n"
            "layout(location = 1) in vec2 a_texCoord;\n"
            "out vec2 v_texCoord;\n"
            "void main() {\n"
            "    gl_Position = a_position;\n"
            "    v_texCoord = a_texCoord;\n"
            "}\n";

        const char* fShaderSrc =
            "#version 300 es\n"
            "precision highp float;\n"
            "precision highp int;\n"
            "\n"
            "in vec2 v_texCoord;\n"
            "out vec4 fragColor;\n"
            "\n"
            "uniform sampler2D u_texY;\n"
            "uniform sampler2D u_texU;\n"
            "uniform sampler2D u_texV;\n"
            "\n"
            "uniform int u_is10Bit;\n"
            "uniform int u_colorStd;\n"
            "uniform int u_hdrTransfer;\n"
            "uniform int u_forceSdr;\n"
            "\n"
            "vec3 hableCurve(vec3 x) {\n"
            "    const float A = 0.15;\n"
            "    const float B = 0.50;\n"
            "    const float C = 0.10;\n"
            "    const float D = 0.20;\n"
            "    const float E = 0.02;\n"
            "    const float F = 0.30;\n"
            "    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - (E / F);\n"
            "}\n"
            "\n"
            "vec3 pqToLinear(vec3 N) {\n"
            "    const float m1 = 2610.0 / 16384.0;\n"
            "    const float m2 = (2523.0 / 4096.0) * 128.0;\n"
            "    const float c1 = 3424.0 / 4096.0;\n"
            "    const float c2 = (2413.0 / 4096.0) * 32.0;\n"
            "    const float c3 = (2392.0 / 4096.0) * 32.0;\n"
            "    vec3 N_inv_m2 = pow(clamp(N, 0.0, 1.0), vec3(1.0 / m2));\n"
            "    vec3 num = max(N_inv_m2 - c1, vec3(0.0));\n"
            "    vec3 den = c2 - c3 * N_inv_m2;\n"
            "    return pow(max(num / den, vec3(0.0)), vec3(1.0 / m1));\n"
            "}\n"
            "\n"
            "vec3 hlgToLinear(vec3 N) {\n"
            "    vec3 L;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float n = clamp(N[i], 0.0, 1.0);\n"
            "        if (n <= 0.5) {\n"
            "            L[i] = (n * n) / 3.0;\n"
            "        } else {\n"
            "            L[i] = (exp((n - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;\n"
            "        }\n"
            "    }\n"
            "    return L;\n"
            "}\n"
            "\n"
            "void main() {\n"
            "    float y, u, v;\n"
            "    if (u_is10Bit == 1) {\n"
            "        vec4 py = texture(u_texY, v_texCoord);\n"
            "        vec4 pu = texture(u_texU, v_texCoord);\n"
            "        vec4 pv = texture(u_texV, v_texCoord);\n"
            "        y = (py.r + py.a * 256.0 * (255.0 / 256.0)) * (255.0 / 1023.0);\n"
            "        u = (pu.r + pu.a * 256.0 * (255.0 / 256.0)) * (255.0 / 1023.0);\n"
            "        v = (pv.r + pv.a * 256.0 * (255.0 / 256.0)) * (255.0 / 1023.0);\n"
            "    } else {\n"
            "        y = texture(u_texY, v_texCoord).r;\n"
            "        u = texture(u_texU, v_texCoord).r;\n"
            "        v = texture(u_texV, v_texCoord).r;\n"
            "    }\n"
            "\n"
            "    y = clamp((y - (16.0 / 255.0)) * (255.0 / (235.0 - 16.0)), 0.0, 1.0);\n"
            "    u = u - 0.5;\n"
            "    v = v - 0.5;\n"
            "\n"
            "    vec3 rgb;\n"
            "    if (u_colorStd == 2) {\n"
            "        rgb.r = y + 1.47460 * v;\n"
            "        rgb.g = y - 0.16455 * u - 0.57135 * v;\n"
            "        rgb.b = y + 1.88140 * u;\n"
            "    } else if (u_colorStd == 1) {\n"
            "        rgb.r = y + 1.57480 * v;\n"
            "        rgb.g = y - 0.18732 * u - 0.46812 * v;\n"
            "        rgb.b = y + 1.85560 * u;\n"
            "    } else {\n"
            "        rgb.r = y + 1.40200 * v;\n"
            "        rgb.g = y - 0.34414 * u - 0.71414 * v;\n"
            "        rgb.b = y + 1.77200 * u;\n"
            "    }\n"
            "    rgb = clamp(rgb, 0.0, 1.0);\n"
            "\n"
            "    if (u_hdrTransfer == 1 || u_hdrTransfer == 2 || u_forceSdr == 1) {\n"
            "        vec3 lin;\n"
            "        if (u_hdrTransfer == 2) {\n"
            "            lin = hlgToLinear(rgb) * 3.8;\n"
            "        } else {\n"
            "            lin = pqToLinear(rgb) * 14.0;\n"
            "        }\n"
            "        if (u_colorStd == 2) {\n"
            "            mat3 to709 = mat3(\n"
            "                1.6605, -0.1246, -0.0182,\n"
            "               -0.5876,  1.1329, -0.1006,\n"
            "               -0.0728, -0.0083,  1.1187\n"
            "            );\n"
            "            lin = max(vec3(0.0), to709 * lin);\n"
            "        }\n"
            "        float whitePoint = 1.0 / (hableCurve(vec3(11.2)).x);\n"
            "        vec3 mapped = hableCurve(lin * 2.2) * whitePoint;\n"
            "        mapped = clamp(mapped, 0.0, 1.0);\n"
            "        rgb = pow(mapped, vec3(1.0 / 2.2));\n"
            "    }\n"
            "    fragColor = vec4(rgb, 1.0);\n"
            "}\n";

        GLuint vs = compileShader(GL_VERTEX_SHADER, vShaderSrc);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, fShaderSrc);
        if (!vs || !fs) return;

        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        glDeleteShader(vs);
        glDeleteShader(fs);

        locTexY = glGetUniformLocation(program, "u_texY");
        locTexU = glGetUniformLocation(program, "u_texU");
        locTexV = glGetUniformLocation(program, "u_texV");
        locIs10Bit = glGetUniformLocation(program, "u_is10Bit");
        locColorStd = glGetUniformLocation(program, "u_colorStd");
        locHdrTransfer = glGetUniformLocation(program, "u_hdrTransfer");
        locForceSdr = glGetUniformLocation(program, "u_forceSdr");
    }

    void initTextures() {
        if (!texY) {
            GLuint texs[3];
            glGenTextures(3, texs);
            texY = texs[0];
            texU = texs[1];
            texV = texs[2];
            for (int i = 0; i < 3; i++) {
                glBindTexture(GL_TEXTURE_2D, texs[i]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }
        }
    }

public:
    GlVideoRenderer() = default;
    ~GlVideoRenderer() { release(); }

    bool isReady() const { return eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT; }

    bool init(ANativeWindow* window, bool forceRecreate = false) {
        if (!window) return false;
        if (!forceRecreate && boundWindow == window && isReady()) return true;
        release();

        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL_NO_DISPLAY) {
            LOGE("GlVideoRenderer: eglGetDisplay failed");
            return false;
        }

        EGLint major = 0, minor = 0;
        if (!eglInitialize(eglDisplay, &major, &minor)) {
            LOGE("GlVideoRenderer: eglInitialize failed");
            return false;
        }

        const EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs = 0;
        if (!eglChooseConfig(eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs <= 0) {
            const EGLint attribs2[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_BLUE_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_RED_SIZE, 8,
                EGL_NONE
            };
            if (!eglChooseConfig(eglDisplay, attribs2, &config, 1, &numConfigs) || numConfigs <= 0) {
                LOGE("GlVideoRenderer: eglChooseConfig failed");
                return false;
            }
        }

        const EGLint ctxAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        if (eglContext == EGL_NO_CONTEXT) {
            const EGLint ctxAttribs2[] = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL_NONE
            };
            eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
        if (eglContext == EGL_NO_CONTEXT) {
            LOGE("GlVideoRenderer: eglCreateContext failed");
            return false;
        }

        eglSurface = eglCreateWindowSurface(eglDisplay, config, window, nullptr);
        if (eglSurface == EGL_NO_SURFACE) {
            LOGE("GlVideoRenderer: eglCreateWindowSurface failed");
            return false;
        }

        if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            LOGE("GlVideoRenderer: eglMakeCurrent failed");
            return false;
        }

        boundWindow = window;
        initShaders();
        initTextures();
        LOGI("GlVideoRenderer: Initialized OpenGL ES 3.0 video renderer for window %p", window);
        return true;
    }

    void release() {
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (program) {
                glDeleteProgram(program);
                program = 0;
            }
            if (texY) {
                GLuint texs[3] = {texY, texU, texV};
                glDeleteTextures(3, texs);
                texY = texU = texV = 0;
            }
            if (eglSurface != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL_NO_SURFACE;
            }
            if (eglContext != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL_NO_CONTEXT;
            }
            eglTerminate(eglDisplay);
            eglDisplay = EGL_NO_DISPLAY;
        }
        boundWindow = nullptr;
        texWidth = 0;
        texHeight = 0;
        texIs10Bit = -1;
    }

    bool render(AVFrame* f, bool forceSdr, int rotation = 0) {
        if (!f || !f->data[0] || !isReady() || !program) return false;
        if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false;

        bool is10Bit = (f->format == AV_PIX_FMT_YUV420P10LE ||
                        f->format == AV_PIX_FMT_YUV420P10BE ||
                        f->format == AV_PIX_FMT_YUV422P10LE ||
                        f->format == AV_PIX_FMT_YUV444P10LE ||
                        f->format == AV_PIX_FMT_YUV420P12LE);

        int w = f->width;
        int h = f->height;
        int uvW = (f->format == AV_PIX_FMT_YUV444P10LE || f->format == AV_PIX_FMT_YUV444P) ? w : (w / 2);
        int uvH = (f->format == AV_PIX_FMT_YUV422P10LE || f->format == AV_PIX_FMT_YUV422P || f->format == AV_PIX_FMT_YUV444P10LE || f->format == AV_PIX_FMT_YUV444P) ? h : (h / 2);

        glUseProgram(program);

        // Upload Y
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texY);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[0] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, w, h, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[0]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[0]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[0]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, w, h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[0]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[0]);
            }
        }

        // Upload U
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texU);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[1] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, uvW, uvH, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[1]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[1]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[1]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvW, uvH, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[1]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[1]);
            }
        }

        // Upload V
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texV);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[2] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, uvW, uvH, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[2]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[2]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[2]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvW, uvH, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[2]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[2]);
            }
        }

        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        texWidth = w;
        texHeight = h;
        texIs10Bit = is10Bit ? 1 : 0;

        int colorStd = 0;
        if (f->color_primaries == AVCOL_PRI_BT2020) colorStd = 2;
        else if (f->color_primaries == AVCOL_PRI_BT709 || w >= 1280 || h >= 720) colorStd = 1;

        int hdrTransfer = 0;
        if (f->color_trc == AVCOL_TRC_SMPTE2084) hdrTransfer = 1;
        else if (f->color_trc == AVCOL_TRC_ARIB_STD_B67) hdrTransfer = 2;

        glUniform1i(locTexY, 0);
        glUniform1i(locTexU, 1);
        glUniform1i(locTexV, 2);
        glUniform1i(locIs10Bit, is10Bit ? 1 : 0);
        glUniform1i(locColorStd, colorStd);
        glUniform1i(locHdrTransfer, hdrTransfer);
        glUniform1i(locForceSdr, forceSdr ? 1 : 0);

        EGLint surfW = 0, surfH = 0;
        eglQuerySurface(eglDisplay, eglSurface, EGL_WIDTH, &surfW);
        eglQuerySurface(eglDisplay, eglSurface, EGL_HEIGHT, &surfH);
        glViewport(0, 0, surfW > 0 ? surfW : w, surfH > 0 ? surfH : h);

        float u0 = 0.0f, v0 = 0.0f;
        float u1 = 0.0f, v1 = 1.0f;
        float u2 = 1.0f, v2 = 0.0f;
        float u3 = 1.0f, v3 = 1.0f;

        if (rotation == 90) {
            u0 = 0.0f; v0 = 1.0f;
            u1 = 1.0f; v1 = 1.0f;
            u2 = 0.0f; v2 = 0.0f;
            u3 = 1.0f; v3 = 0.0f;
        } else if (rotation == 180) {
            u0 = 1.0f; v0 = 1.0f;
            u1 = 1.0f; v1 = 0.0f;
            u2 = 0.0f; v2 = 1.0f;
            u3 = 0.0f; v3 = 0.0f;
        } else if (rotation == 270) {
            u0 = 1.0f; v0 = 0.0f;
            u1 = 0.0f; v1 = 0.0f;
            u2 = 1.0f; v2 = 1.0f;
            u3 = 0.0f; v3 = 1.0f;
        }

        const float vertices[] = {
            -1.0f,  1.0f,  u0, v0,
            -1.0f, -1.0f,  u1, v1,
             1.0f,  1.0f,  u2, v2,
             1.0f, -1.0f,  u3, v3
        };
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices + 2);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);

        eglSwapBuffers(eglDisplay, eglSurface);
        return true;
    }
};

// ─── HDR Tone-Mapping Processor (Fallback CPU blit path) ─────────────────────

class HdrToneMapper {
private:
    float pqToLinear[1024];
    float hlgToLinear[1024];
    uint8_t linearToSdr[4096];
    bool initialized = false;

    // Persistent worker thread for parallel row processing without per-frame thread spawning
    std::thread workerThread;
    std::mutex workerMtx;
    std::condition_variable workerCv;
    std::condition_variable doneCv;
    bool workerRunning = false;
    bool workerTaskReady = false;
    bool workerTaskDone = true;

    // Task parameters for worker thread
    const uint16_t* taskSrc = nullptr;
    uint8_t* taskDst = nullptr;
    int taskWidth = 0;
    int taskStartY = 0;
    int taskEndY = 0;
    int taskSrcStrideBytes = 0;
    int taskDstStrideBytes = 0;
    bool taskIsHlg = false;

    static inline float hableCurve(float x) {
        const float A = 0.15f;
        const float B = 0.50f;
        const float C = 0.10f;
        const float D = 0.20f;
        const float E = 0.02f;
        const float F = 0.30f;
        return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - (E / F);
    }

    void processRowRange(const uint16_t* src, uint8_t* dst, int width, int startY, int endY,
                         int srcStrideBytes, int dstStrideBytes, bool isHlg) {
        const float* eotf = isHlg ? hlgToLinear : pqToLinear;

        // BT.2020 -> BT.709 Gamut Transformation Matrix
        const float c00 =  1.6605f, c01 = -0.5876f, c02 = -0.0728f;
        const float c10 = -0.1246f, c11 =  1.1329f, c12 = -0.0083f;
        const float c20 = -0.0182f, c21 = -0.1006f, c22 =  1.1187f;

        const float exposure = isHlg ? 3.8f : 14.0f;

        for (int y = startY; y < endY; y++) {
            const uint16_t* rowSrc = reinterpret_cast<const uint16_t*>(reinterpret_cast<const uint8_t*>(src) + y * srcStrideBytes);
            uint8_t* rowDst = dst + y * dstStrideBytes;

            for (int x = 0; x < width; x++) {
                int rIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 0] >> 6), 0, 1023);
                int gIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 1] >> 6), 0, 1023);
                int bIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 2] >> 6), 0, 1023);

                float rLin = eotf[rIdx];
                float gLin = eotf[gIdx];
                float bLin = eotf[bIdx];

                // BT.2020 -> BT.709 with exposure scaling
                float r709 = std::max(0.0f, c00 * rLin + c01 * gLin + c02 * bLin) * exposure;
                float g709 = std::max(0.0f, c10 * rLin + c11 * gLin + c12 * bLin) * exposure;
                float b709 = std::max(0.0f, c20 * rLin + c21 * gLin + c22 * bLin) * exposure;

                int rLutIdx = std::clamp(static_cast<int>((r709 * 0.25f) * 4095.0f), 0, 4095);
                int gLutIdx = std::clamp(static_cast<int>((g709 * 0.25f) * 4095.0f), 0, 4095);
                int bLutIdx = std::clamp(static_cast<int>((b709 * 0.25f) * 4095.0f), 0, 4095);

                rowDst[x * 4 + 0] = linearToSdr[rLutIdx];
                rowDst[x * 4 + 1] = linearToSdr[gLutIdx];
                rowDst[x * 4 + 2] = linearToSdr[bLutIdx];
                rowDst[x * 4 + 3] = 255;
            }
        }
    }

    void workerLoop() {
        while (true) {
            std::unique_lock<std::mutex> lk(workerMtx);
            workerCv.wait(lk, [this] { return !workerRunning || workerTaskReady; });
            if (!workerRunning) break;

            processRowRange(taskSrc, taskDst, taskWidth, taskStartY, taskEndY,
                            taskSrcStrideBytes, taskDstStrideBytes, taskIsHlg);

            workerTaskReady = false;
            workerTaskDone = true;
            doneCv.notify_one();
        }
    }

public:
    HdrToneMapper() = default;

    ~HdrToneMapper() {
        {
            std::lock_guard<std::mutex> lk(workerMtx);
            workerRunning = false;
            workerCv.notify_all();
        }
        if (workerThread.joinable()) {
            workerThread.join();
        }
    }

    void init() {
        if (initialized) return;

        // 1. PQ (SMPTE ST 2084) EOTF LUT (maps 10-bit non-linear code [0..1023] to normalized scene luminance)
        const double m1 = 2610.0 / 16384.0;
        const double m2 = (2523.0 / 4096.0) * 128.0;
        const double c1 = 3424.0 / 4096.0;
        const double c2 = (2413.0 / 4096.0) * 32.0;
        const double c3 = (2392.0 / 4096.0) * 32.0;

        for (int i = 0; i < 1024; i++) {
            double N = static_cast<double>(i) / 1023.0;
            double N_inv_m2 = std::pow(N, 1.0 / m2);
            double num = std::max(N_inv_m2 - c1, 0.0);
            double den = c2 - c3 * N_inv_m2;
            double L = (den > 0.0 && num > 0.0) ? std::pow(num / den, 1.0 / m1) : 0.0;
            pqToLinear[i] = static_cast<float>(L);
        }

        // 2. HLG (ARIB STD-B67) EOTF LUT
        for (int i = 0; i < 1024; i++) {
            double N = static_cast<double>(i) / 1023.0;
            double L;
            if (N <= 0.5) {
                L = (N * N) / 3.0;
            } else {
                L = (std::exp((N - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
            }
            hlgToLinear[i] = static_cast<float>(L);
        }

        // 3. Linear-to-SDR LUT (Hable Filmic Tone-Curve + sRGB Gamma ~2.2)
        const float whitePoint = hableCurve(11.2f);
        for (int i = 0; i < 4096; i++) {
            float lin = (static_cast<float>(i) / 4095.0f) * 4.0f;
            float mapped = hableCurve(lin * 2.2f) / whitePoint;
            mapped = std::clamp(mapped, 0.0f, 1.0f);
            float sdr = std::pow(mapped, 1.0f / 2.2f);
            int val = static_cast<int>(std::round(sdr * 255.0f));
            linearToSdr[i] = static_cast<uint8_t>(std::clamp(val, 0, 255));
        }

        workerRunning = true;
        workerThread = std::thread(&HdrToneMapper::workerLoop, this);
        initialized = true;
    }

    void toneMapRgba64ToRgba8(const uint16_t* src, uint8_t* dst, int width, int height, int srcStrideBytes, int dstStrideBytes, bool isHlg) {
        init();

        if (height >= 480 && workerRunning) {
            int mid = height / 2;
            {
                std::lock_guard<std::mutex> lk(workerMtx);
                taskSrc = src;
                taskDst = dst;
                taskWidth = width;
                taskStartY = 0;
                taskEndY = mid;
                taskSrcStrideBytes = srcStrideBytes;
                taskDstStrideBytes = dstStrideBytes;
                taskIsHlg = isHlg;
                workerTaskDone = false;
                workerTaskReady = true;
                workerCv.notify_one();
            }

            // Process lower half on calling thread
            processRowRange(src, dst, width, mid, height, srcStrideBytes, dstStrideBytes, isHlg);

            // Wait for worker thread to complete upper half
            std::unique_lock<std::mutex> lk(workerMtx);
            doneCv.wait(lk, [this] { return workerTaskDone; });
        } else {
            processRowRange(src, dst, width, 0, height, srcStrideBytes, dstStrideBytes, isHlg);
        }
    }
};

// ─── Video Decode + Render Thread ────────────────────────────────────────────

static void videoDecodeThreadFunc(FfmpegPlayerContext* ctx) {
    LOGI("videoDecodeThread started");
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach video thread to JVM");
        return;
    }

    AVFrame* vFrame = av_frame_alloc();
    PacketQueue::Item item{};
    bool needSeekFrame = false;

    HdrToneMapper toneMapper;
    std::vector<uint16_t> hdrBuffer;

    bool allowHw = ctx->useHardware.load();

    HwVideoDecoder hwDecoder;
    if (allowHw && ctx->videoCodecPar) {
        std::lock_guard<std::mutex> lock(ctx->windowMutex);
        if (ctx->nativeWindow) {
            if (hwDecoder.init(ctx->videoCodecPar, ctx->nativeWindow, ctx->forceSdr.load(), ctx->videoRotation)) {
                ctx->videoCodecName = hwDecoder.codecName;
            }
        }
    }

    GlVideoRenderer glRenderer;
    bool glRendererInitialized = false;

    auto renderFrame = [&](AVFrame* f, bool isSeekFrame) -> bool {
        if (f->width > 0 && f->height > 0 && (f->width != ctx->videoWidth || f->height != ctx->videoHeight)) {
            ctx->videoWidth = f->width;
            ctx->videoHeight = f->height;
            ctx->notifyVideoSize(env, f->width, f->height, ctx->videoRotation, ctx->videoSarNum, ctx->videoSarDen);
        }

        int64_t ptsUs = (f->best_effort_timestamp != AV_NOPTS_VALUE)
            ? av_rescale_q(f->best_effort_timestamp, ctx->videoTimeBase, AV_TIME_BASE_Q)
            : (f->pts != AV_NOPTS_VALUE
                ? av_rescale_q(f->pts, ctx->videoTimeBase, AV_TIME_BASE_Q)
                : ctx->getMasterClockUs());
        // Capture the seek generation at the moment this frame entered the render pipeline.
        int64_t mySeekVersion = ctx->seekVersion.load(std::memory_order_acquire);

        int64_t targetPts = ctx->videoSeekTargetPtsUs.load();
        if (targetPts >= 0) {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            if (ptsUs < targetPts - (frameDurUs / 2)) {
                // Drop all preroll frames before seek target
                return false;
            }
            ctx->videoSeekTargetPtsUs.store(-1);
            isSeekFrame = true;
        } else if (ctx->isScrubbing.load() || isSeekFrame) {
            isSeekFrame = true;
            ctx->setMasterClockUs(ptsUs);
            ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
        }

        ctx->lastVideoPtsUs.store(ptsUs);
        ctx->currentPositionMs.store(ptsUs / 1000);

        // ── A/V Sync (skip waiting if rendering the single frame after a seek) ──
        if (!isSeekFrame) {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            int64_t lateDropThresholdUs = std::max<int64_t>(80000, frameDurUs * 2);

            while (ctx->isRunning.load() && !ctx->isStopped.load()) {
                if (ctx->isPaused.load() || ctx->isScrubbing.load()) {
                    std::unique_lock<std::mutex> lk(ctx->controlMutex);
                    ctx->controlCv.wait(lk, [&] {
                        return (!ctx->isPaused.load() && !ctx->isScrubbing.load()) || !ctx->isRunning.load() || ctx->isStopped.load() ||
                               ctx->seekTargetMs.load() >= 0;
                    });
                    if (!ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0) {
                        break;
                    }
                }

                int64_t clockUs = ctx->getMasterClockUs();
                int64_t diffUs  = ptsUs - clockUs;
                ctx->lastAudioDriftUs.store(diffUs);

                if (ctx->audioStreamIdx >= 0) {
                    // Desync recovery: if video is more than 2 frames late compared to audio clock,
                    // drop this video frame to catch up smoothly
                    if (diffUs < -lateDropThresholdUs && !isSeekFrame) {
                        if (diffUs < -500000) {
                            LOGW("Video far behind audio clock (diff: %" PRId64 " us); re-aligning master clock", diffUs);
                            ctx->setMasterClockUs(ptsUs);
                            break;
                        }
                        ctx->totalDroppedFrames.fetch_add(1, std::memory_order_relaxed);
                        LOGD("Dropping late video frame (diff: %" PRId64 " us, threshold: %" PRId64 " us)", diffUs, lateDropThresholdUs);
                        return false;
                    }
                    // Only for extreme desync (> 600ms) after seek/underrun do we re-align the master clock
                    if (diffUs > 600000 && !isSeekFrame) {
                        LOGW("Large A/V desync detected (diff: %" PRId64 " us); re-aligning master clock", diffUs);
                        ctx->setMasterClockUs(ptsUs);
                        break;
                    }
                } else {
                    if (diffUs < -500000 || diffUs > 5000000) {
                        ctx->setMasterClockUs(ptsUs);
                        break;
                    }
                    if (diffUs <= 1000) {
                        ctx->setMasterClockUs(ptsUs);
                        break;
                    }
                }

                if (diffUs <= 1000) {
                    break;
                }

                float speed = ctx->playbackSpeed.load();
                if (speed <= 0.0f) speed = 1.0f;
                int64_t waitUs = static_cast<int64_t>(diffUs / speed);
                // Cap at half a frame period so we stay reactive at high frame rates
                // (e.g. 60fps = 16.7ms, so cap = ~8ms instead of the old flat 40ms).
                int64_t maxWaitUs = std::max<int64_t>(4000, frameDurUs / 2);
                if (waitUs > maxWaitUs) waitUs = maxWaitUs;

                std::unique_lock<std::mutex> lk(ctx->controlMutex);
                ctx->controlCv.wait_for(lk, std::chrono::microseconds(waitUs), [&] {
                    return ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                           ctx->seekTargetMs.load() >= 0;
                });
            }
        }

        if (!ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0) {
            return false;
        }
        // Stale-frame guard: if a newer seek fired during A/V sync wait, discard this frame.
        if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
            return false;
        }

        // ── Render ───────────────────────────────────────────────────
        std::lock_guard<std::mutex> lock(ctx->windowMutex);
        if (ctx->nativeWindow && f->width > 0 && f->height > 0) {
            bool sChanged = ctx->surfaceChanged.exchange(false);
            if (!glRendererInitialized || sChanged) {
                glRendererInitialized = glRenderer.init(ctx->nativeWindow, sChanged);
            }
            if (glRendererInitialized && glRenderer.render(f, ctx->forceSdr.load(), ctx->videoRotation)) {
                ctx->totalRenderedFrames.fetch_add(1, std::memory_order_relaxed);
                ctx->notifyFrameRendered(env, ptsUs);
                if (ctx->isBuffering.exchange(false) || isSeekFrame) {
                    ctx->setMasterClockUs(ptsUs);
                    ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
                    ctx->notifyState(env, STATE_READY);
                    ctx->controlCv.notify_all();
                }
                return true;
            } else {
                // Fallback to CPU blit if OpenGL ES is not available
                if (f->width  != ctx->lastWindowWidth ||
                    f->height != ctx->lastWindowHeight) {
                    ANativeWindow_setBuffersGeometry(ctx->nativeWindow,
                        f->width, f->height, WINDOW_FORMAT_RGBA_8888);
                    ctx->lastWindowWidth  = f->width;
                    ctx->lastWindowHeight = f->height;
                    if (ctx->swsCtx) { sws_freeContext(ctx->swsCtx); ctx->swsCtx = nullptr; }
                }
                ANativeWindow_Buffer wb;
                if (ANativeWindow_lock(ctx->nativeWindow, &wb, nullptr) == 0) {
                    bool isHdr = (f->color_trc == AVCOL_TRC_SMPTE2084 ||
                                  f->color_trc == AVCOL_TRC_ARIB_STD_B67 ||
                                  f->color_primaries == AVCOL_PRI_BT2020 ||
                                  f->format == AV_PIX_FMT_YUV420P10LE ||
                                  f->format == AV_PIX_FMT_YUV420P10BE ||
                                  f->format == AV_PIX_FMT_YUV422P10LE ||
                                  f->format == AV_PIX_FMT_YUV444P10LE ||
                                  f->format == AV_PIX_FMT_YUV420P12LE ||
                                  ctx->forceSdr.load());

                    if (isHdr) {
                        bool isHlg = (f->color_trc == AVCOL_TRC_ARIB_STD_B67);
                        size_t requiredWords = static_cast<size_t>(f->width) * f->height * 4;
                        if (hdrBuffer.size() < requiredWords) {
                            hdrBuffer.resize(requiredWords);
                        }
                        ctx->swsCtx = sws_getCachedContext(
                            ctx->swsCtx, f->width, f->height,
                            static_cast<AVPixelFormat>(f->format),
                            f->width, f->height,
                            AV_PIX_FMT_RGBA64LE, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
                        if (ctx->swsCtx) {
                            uint8_t* dst[4] = {reinterpret_cast<uint8_t*>(hdrBuffer.data()), nullptr, nullptr, nullptr};
                            int dstStride[4] = {f->width * 8, 0, 0, 0};
                            sws_scale(ctx->swsCtx, f->data, f->linesize, 0, f->height, dst, dstStride);
                            toneMapper.toneMapRgba64ToRgba8(hdrBuffer.data(), static_cast<uint8_t*>(wb.bits),
                                                            f->width, f->height, f->width * 8, wb.stride * 4, isHlg);
                        }
                    } else {
                        ctx->swsCtx = sws_getCachedContext(
                            ctx->swsCtx, f->width, f->height,
                            static_cast<AVPixelFormat>(f->format),
                            f->width, f->height,
                            AV_PIX_FMT_RGBA, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
                        if (ctx->swsCtx) {
                            uint8_t* dst[4]  = {static_cast<uint8_t*>(wb.bits), nullptr, nullptr, nullptr};
                            int dstStride[4] = {wb.stride * 4, 0, 0, 0};
                            sws_scale(ctx->swsCtx, f->data, f->linesize, 0, f->height, dst, dstStride);
                        }
                    }
                    ANativeWindow_unlockAndPost(ctx->nativeWindow);
                    ctx->totalRenderedFrames.fetch_add(1, std::memory_order_relaxed);
                    ctx->notifyFrameRendered(env, ptsUs);
                    if (ctx->isBuffering.exchange(false)) {
                        ctx->setMasterClockUs(ptsUs);
                        ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
                        ctx->notifyState(env, STATE_READY);
                        ctx->controlCv.notify_all();
                    } else if (isSeekFrame) {
                        ctx->notifyState(env, STATE_READY);
                    }
                    return true;
                }
            }
        }
        return false;
    };

    auto renderHwFrame = [&](int64_t ptsUs, ssize_t outIdx, bool isSeekFrame) -> bool {
        // Capture seek generation so we can discard stale frames after a rapid seek.
        int64_t mySeekVersion = ctx->seekVersion.load(std::memory_order_acquire);
        int64_t targetPts = ctx->videoSeekTargetPtsUs.load();
        if (targetPts >= 0) {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            if (ptsUs < targetPts - (frameDurUs / 2)) {
                // Drop all preroll frames before seek target without rendering
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }
            ctx->videoSeekTargetPtsUs.store(-1);
            isSeekFrame = true;
        } else if (ctx->isScrubbing.load() || isSeekFrame) {
            isSeekFrame = true;
            ctx->setMasterClockUs(ptsUs);
            ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
        }

        ctx->lastVideoPtsUs.store(ptsUs);
        ctx->currentPositionMs.store(ptsUs / 1000);

        // Stale-frame guard: a newer seek arrived before this frame could be scheduled.
        if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
            AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
            return false;
        }
        if (isSeekFrame || ctx->isScrubbing.load()) {
            AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, true);
        } else {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            int64_t lateDropThresholdUs = std::max<int64_t>(80000, frameDurUs * 2);

            // VSYNC-aligned presentation scheduling via AMediaCodec_releaseOutputBufferAtTime
            while (ctx->isRunning.load() && !ctx->isStopped.load()) {
                if (ctx->isPaused.load()) {
                    std::unique_lock<std::mutex> lk(ctx->controlMutex);
                    ctx->controlCv.wait(lk, [&] {
                        return !ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                               ctx->seekTargetMs.load() >= 0;
                    });
                    if (!ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0) {
                        break;
                    }
                }

                int64_t clockUs = ctx->getMasterClockUs();
                int64_t diffUs  = ptsUs - clockUs;
                ctx->lastAudioDriftUs.store(diffUs);

                if (ctx->audioStreamIdx >= 0) {
                    if (diffUs < -lateDropThresholdUs) {
                        if (diffUs < -500000) {
                            LOGW("Large A/V desync detected in HW decode (diff: %" PRId64 " us); re-aligning master clock", diffUs);
                            ctx->setMasterClockUs(ptsUs);
                            break;
                        }
                        ctx->totalDroppedFrames.fetch_add(1, std::memory_order_relaxed);
                        AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                        return false;
                    }
                    if (diffUs > 600000) {
                        LOGW("Large A/V desync detected in HW decode (diff: %" PRId64 " us); re-aligning master clock", diffUs);
                        ctx->setMasterClockUs(ptsUs);
                        break;
                    }
                } else {
                    if (diffUs < -500000 || diffUs > 5000000) {
                        ctx->setMasterClockUs(ptsUs);
                        break;
                    }
                    if (diffUs <= 30000) {
                        break;
                    }
                }

                float speed = ctx->playbackSpeed.load();
                if (speed <= 0.0f) speed = 1.0f;
                // Render ahead window: scale by playback speed
                int64_t renderAheadUs = static_cast<int64_t>(30000.0f / std::max(1.0f, speed));
                if (diffUs <= renderAheadUs) {
                    break;
                }

                int64_t waitUs = static_cast<int64_t>((diffUs - renderAheadUs) / speed);
                int64_t maxWaitUs = std::max<int64_t>(4000, frameDurUs / 2);
                if (waitUs > maxWaitUs) waitUs = maxWaitUs;

                std::unique_lock<std::mutex> lk(ctx->controlMutex);
                ctx->controlCv.wait_for(lk, std::chrono::microseconds(waitUs), [&] {
                    return ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                           ctx->seekTargetMs.load() >= 0;
                });
            }

            if (!ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0) {
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }
            // Stale-frame guard: discard if a newer seek fired during A/V sync wait.
            if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }

            int64_t clockUs = ctx->getMasterClockUs();
            int64_t diffUs  = ptsUs - clockUs;
            float speed = ctx->playbackSpeed.load();
            if (speed <= 0.0f) speed = 1.0f;

            int64_t nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            int64_t renderTimestampNs = nowNs + static_cast<int64_t>((diffUs * 1000LL) / speed);

            AMediaCodec_releaseOutputBufferAtTime(hwDecoder.codec, outIdx, renderTimestampNs);
        }

        ctx->totalRenderedFrames.fetch_add(1, std::memory_order_relaxed);
        ctx->notifyFrameRendered(env, ptsUs);
        if (ctx->isBuffering.exchange(false) || isSeekFrame) {
            ctx->setMasterClockUs(ptsUs);
            ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
            ctx->notifyState(env, STATE_READY);
            ctx->controlCv.notify_all();
        }
        return true;
    };

    auto drainHwFrames = [&]() {
        AMediaCodecBufferInfo info;
        while (ctx->isRunning.load() && !ctx->isStopped.load() && ctx->seekTargetMs.load() < 0) {
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(hwDecoder.codec, &info, 0);
            if (outIdx >= 0) {
                int64_t ptsUs = info.presentationTimeUs;
                bool rendered = renderHwFrame(ptsUs, outIdx, needSeekFrame);
                if (rendered) needSeekFrame = false;
            } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* fmt = AMediaCodec_getOutputFormat(hwDecoder.codec);
                if (fmt) {
                    int32_t w = 0, h = 0;
                    if (AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w) &&
                        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h)) {
                        ctx->videoWidth = w;
                        ctx->videoHeight = h;
                        ctx->notifyVideoSize(env, w, h, ctx->videoRotation, ctx->videoSarNum, ctx->videoSarDen);
                    }
                    AMediaFormat_delete(fmt);
                }
            } else {
                break;
            }
        }
    };

    auto feedHwPacket = [&](AVPacket* p) {
        int64_t ptsUs = (p->pts != AV_NOPTS_VALUE)
            ? av_rescale_q(p->pts, ctx->videoTimeBase, AV_TIME_BASE_Q)
            : ((p->dts != AV_NOPTS_VALUE)
                ? av_rescale_q(p->dts, ctx->videoTimeBase, AV_TIME_BASE_Q)
                : 0);
        int maxRetries = 50;
        while (maxRetries-- > 0 && ctx->isRunning.load() && !ctx->isStopped.load() && ctx->seekTargetMs.load() < 0) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(hwDecoder.codec, 5000);
            if (inIdx >= 0) {
                size_t inBufSize = 0;
                uint8_t* inBuf = AMediaCodec_getInputBuffer(hwDecoder.codec, inIdx, &inBufSize);
                if (inBuf && p->size <= inBufSize) {
                    memcpy(inBuf, p->data, p->size);
                    AMediaCodec_queueInputBuffer(hwDecoder.codec, inIdx, 0, p->size, ptsUs, 0);
                }
                break;
            }
            drainHwFrames();
        }
    };

    // Decoupled pre-decoded frame pool for smooth presentation timing.
    // 6 frames absorbs decode jitter from I-frames / keyframes without starving the renderer.
    std::deque<AVFrame*> decodedFrames;
    const size_t maxDecodedFrames = 6;

    auto drainOneDecodedFrame = [&](bool isSeek) -> bool {
        if (decodedFrames.empty()) return false;
        AVFrame* front = decodedFrames.front();
        decodedFrames.pop_front();
        bool rendered = renderFrame(front, isSeek);
        av_frame_free(&front);
        return rendered;
    };

    auto drainAllDecodedFrames = [&](bool isSeek) {
        while (!decodedFrames.empty() && ctx->isRunning.load() && !ctx->isStopped.load()) {
            drainOneDecodedFrame(isSeek);
        }
    };

    auto queueDecodedFrame = [&](AVFrame* src, bool isSeek) {
        if (isSeek || needSeekFrame || ctx->isScrubbing.load() || ctx->videoSeekTargetPtsUs.load() >= 0) {
            bool rendered = renderFrame(src, needSeekFrame);
            if (rendered) needSeekFrame = false;
            return;
        }

        AVFrame* clone = av_frame_clone(src);
        if (clone) {
            decodedFrames.push_back(clone);
            while (decodedFrames.size() >= maxDecodedFrames && ctx->isRunning.load() && !ctx->isStopped.load()) {
                drainOneDecodedFrame(false);
            }
        } else {
            renderFrame(src, false);
        }
    };

    while (ctx->isRunning.load() && !ctx->isStopped.load()) {
        if (ctx->surfaceChanged.exchange(false)) {
            std::lock_guard<std::mutex> lock(ctx->windowMutex);
            glRenderer.release();
            glRendererInitialized = false;
            bool allowHwNow = ctx->useHardware.load();
            if (hwDecoder.isConfigured.load()) {
                if (ctx->nativeWindow && allowHwNow) {
                    if (!hwDecoder.setOutputSurface(ctx->nativeWindow)) {
                        LOGI("Re-initializing HwVideoDecoder for new surface");
                        hwDecoder.init(ctx->videoCodecPar, ctx->nativeWindow, ctx->forceSdr.load(), ctx->videoRotation);
                    }
                } else if (!allowHwNow) {
                    hwDecoder.release();
                }
            } else if (allowHwNow && ctx->videoCodecPar && ctx->nativeWindow) {
                if (hwDecoder.init(ctx->videoCodecPar, ctx->nativeWindow, ctx->forceSdr.load(), ctx->videoRotation)) {
                    ctx->videoCodecName = hwDecoder.codecName;
                }
            }
            needSeekFrame = true;
        }

        if (ctx->isPaused.load() && !ctx->isScrubbing.load() && !needSeekFrame && ctx->seekTargetMs.load() < 0 && ctx->videoQueue.empty() && decodedFrames.empty()) {
            std::unique_lock<std::mutex> lk(ctx->controlMutex);
            ctx->controlCv.wait(lk, [&] {
                return !ctx->isPaused.load() || ctx->isScrubbing.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                       ctx->seekTargetMs.load() >= 0 || !ctx->videoQueue.empty();
            });
            if (!ctx->isRunning.load() || ctx->isStopped.load()) break;
        }

        if (!ctx->videoQueue.pop(item, 5)) {
            if (!decodedFrames.empty()) {
                drainOneDecodedFrame(false);
            }
            continue;
        }

        if (item.isFlush) {
            if (hwDecoder.isConfigured.load()) {
                hwDecoder.flush();
                // Drain and discard any output buffers that were queued before the flush.
                // Without this, stale pre-seek frames can appear momentarily at the new position.
                AMediaCodecBufferInfo flushInfo;
                ssize_t flushIdx;
                while ((flushIdx = AMediaCodec_dequeueOutputBuffer(hwDecoder.codec, &flushInfo, 0)) >= 0) {
                    AMediaCodec_releaseOutputBuffer(hwDecoder.codec, flushIdx, false);
                }
            } else if (ctx->videoCodecCtx) {
                avcodec_flush_buffers(ctx->videoCodecCtx);
                ctx->videoCodecCtx->skip_frame = AVDISCARD_DEFAULT;
            }
            for (auto* f : decodedFrames) {
                if (f) av_frame_free(&f);
            }
            decodedFrames.clear();
            av_frame_unref(vFrame);
            ctx->videoFinished.store(false);
            needSeekFrame = true;
            continue;
        }

        if (item.isEof) {
            LOGI("videoDecodeThread received EOF, draining decoder");
            if (hwDecoder.isConfigured.load()) {
                ssize_t inIdx = AMediaCodec_dequeueInputBuffer(hwDecoder.codec, 5000);
                if (inIdx >= 0) {
                    AMediaCodec_queueInputBuffer(hwDecoder.codec, inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                }
                AMediaCodecBufferInfo info;
                while (true) {
                    ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(hwDecoder.codec, &info, 5000);
                    if (outIdx >= 0) {
                        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                            AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                            break;
                        }
                        int64_t ptsUs = info.presentationTimeUs;
                        bool rendered = renderHwFrame(ptsUs, outIdx, needSeekFrame);
                        if (rendered) needSeekFrame = false;
                    } else {
                        break;
                    }
                }
            } else if (ctx->videoCodecCtx) {
                avcodec_send_packet(ctx->videoCodecCtx, nullptr);
                while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
                    queueDecodedFrame(vFrame, needSeekFrame);
                    av_frame_unref(vFrame);
                }
                drainAllDecodedFrames(false);
            }
            needSeekFrame = false;
            ctx->videoFinished.store(true);
            ctx->checkPlaybackFinished(env);
            continue;
        }

        if (hwDecoder.isConfigured.load()) {
            if (item.pkt) {
                if (hwDecoder.bsfCtx) {
                    AVPacket* bsfPkt = av_packet_alloc();
                    int bsfRet = av_bsf_send_packet(hwDecoder.bsfCtx, item.pkt);
                    if (bsfRet == 0) {
                        while (av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
                            feedHwPacket(bsfPkt);
                            av_packet_unref(bsfPkt);
                        }
                    } else if (bsfRet == AVERROR(EAGAIN)) {
                        while (av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
                            feedHwPacket(bsfPkt);
                            av_packet_unref(bsfPkt);
                        }
                        if (av_bsf_send_packet(hwDecoder.bsfCtx, item.pkt) == 0) {
                            while (av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
                                feedHwPacket(bsfPkt);
                                av_packet_unref(bsfPkt);
                            }
                        }
                    }
                    av_packet_free(&bsfPkt);
                } else {
                    feedHwPacket(item.pkt);
                }
                av_packet_free(&item.pkt);
            }
            drainHwFrames();
            continue;
        }

        if (!ctx->videoCodecCtx || !item.pkt) {
            av_packet_free(&item.pkt);
            continue;
        }

        // QoS / Preroll: Skip B-frames during accurate seek preroll to decode at maximum speed
        if (ctx->videoCodecCtx) {
            int64_t targetPts = ctx->videoSeekTargetPtsUs.load();
            if (targetPts >= 0) {
                if (ctx->videoCodecCtx->skip_frame != AVDISCARD_NONREF) {
                    ctx->videoCodecCtx->skip_frame = AVDISCARD_NONREF;
                }
            } else if (ctx->audioStreamIdx >= 0 && !ctx->isScrubbing.load() && !needSeekFrame) {
                int64_t drift = ctx->lastAudioDriftUs.load();
                // Scale thresholds by playback speed: at 2x the audio clock runs twice as fast,
                // so a nominal -80ms drift is expected — don't aggressively skip B-frames.
                float qosSpeed = ctx->playbackSpeed.load();
                if (qosSpeed < 1.0f) qosSpeed = 1.0f;
                int64_t skipThresholdUs    = static_cast<int64_t>(80000.0f  * qosSpeed);
                int64_t recoverThresholdUs = static_cast<int64_t>(-20000.0f * qosSpeed);
                if (drift < -skipThresholdUs) {
                    if (ctx->videoCodecCtx->skip_frame != AVDISCARD_NONREF) {
                        ctx->videoCodecCtx->skip_frame = AVDISCARD_NONREF;
                        LOGD("QoS: Enabling AVDISCARD_NONREF (drift: %" PRId64 " us, speed: %.2f)", drift, qosSpeed);
                    }
                } else if (drift >= recoverThresholdUs) {
                    if (ctx->videoCodecCtx->skip_frame != AVDISCARD_DEFAULT) {
                        ctx->videoCodecCtx->skip_frame = AVDISCARD_DEFAULT;
                    }
                }
            } else {
                if (ctx->videoCodecCtx->skip_frame != AVDISCARD_DEFAULT) {
                    ctx->videoCodecCtx->skip_frame = AVDISCARD_DEFAULT;
                }
            }
        }

        int sendRet = avcodec_send_packet(ctx->videoCodecCtx, item.pkt);
        if (sendRet == AVERROR(EAGAIN)) {
            while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
                queueDecodedFrame(vFrame, needSeekFrame);
                av_frame_unref(vFrame);
            }
            sendRet = avcodec_send_packet(ctx->videoCodecCtx, item.pkt);
        }
        av_packet_free(&item.pkt);

        while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
            queueDecodedFrame(vFrame, needSeekFrame);
            av_frame_unref(vFrame);
        }
    }

    for (auto* f : decodedFrames) {
        if (f) av_frame_free(&f);
    }
    decodedFrames.clear();

    glRenderer.release();
    hwDecoder.release();
    av_frame_free(&vFrame);
    g_jvm->DetachCurrentThread();
    LOGI("videoDecodeThread finished");
}

// ─── Audio Decode Thread ─────────────────────────────────────────────────────

static void audioDecodeThreadFunc(FfmpegPlayerContext* ctx) {
    LOGI("audioDecodeThread started");
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach audio thread to JVM");
        return;
    }

    AVFrame* aFrame = av_frame_alloc();
    AVFrame* filterFrame = av_frame_alloc();
    AudioFilterGraph audioFilter;
    float lastFilterSpeed = 1.0f;

    SwrContext* localSwrCtx = nullptr;
    AVChannelLayout lastInChLayout{};
    AVSampleFormat lastInFmt = AV_SAMPLE_FMT_NONE;
    int lastInSampleRate = 0;

    ctx->nativeAudioSink.init(ctx->outSampleRate, ctx->outChannels);
    int initialSid = ctx->nativeAudioSink.getSessionId();
    if (initialSid > 0) {
        ctx->notifyAudioSessionId(env, initialSid);
    }
    ctx->nativeEqualizer.init(ctx->outSampleRate, ctx->outChannels);

    PacketQueue::Item item{};
    uint8_t* audioOutBuf = nullptr;
    int audioOutBufSize = 0;
    int64_t smoothedDriftUs = 0;
    int compensationActive = 0;

    auto processAudioFrame = [&](AVFrame* f) {
        int64_t ptsUs = (f->best_effort_timestamp != AV_NOPTS_VALUE)
            ? av_rescale_q(f->best_effort_timestamp, ctx->audioTimeBase, AV_TIME_BASE_Q)
            : (f->pts != AV_NOPTS_VALUE
                ? av_rescale_q(f->pts, ctx->audioTimeBase, AV_TIME_BASE_Q)
                : ctx->getMasterClockUs());

        int64_t targetPts = ctx->audioSeekTargetPtsUs.load();
        if (targetPts >= 0) {
            int64_t frameDurUs = (f->nb_samples > 0 && f->sample_rate > 0)
                ? (static_cast<int64_t>(f->nb_samples) * 1000000LL / f->sample_rate)
                : 0;
            if (ptsUs + frameDurUs < targetPts) {
                // Preroll: drop audio before seek target
                return;
            }
            ctx->audioSeekTargetPtsUs.store(-1);
        }

        if (ctx->videoStreamIdx < 0) {
            ctx->currentPositionMs.store(ptsUs / 1000);
        }

        if (ctx->videoStreamIdx >= 0 && ctx->nativeWindow != nullptr) {
            auto buffStart = std::chrono::steady_clock::now();
            while (ctx->isBuffering.load() && ctx->isRunning.load() && !ctx->isStopped.load() &&
                   !ctx->videoFinished.load() && ctx->nativeWindow != nullptr) {
                if (std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - buffStart).count() > 2500) {
                    LOGW("Buffering timeout in audio thread; releasing audio");
                    if (ctx->isBuffering.exchange(false)) {
                        ctx->setMasterClockUs(ptsUs);
                        ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
                        ctx->notifyState(env, STATE_READY);
                        ctx->controlCv.notify_all();
                    }
                    break;
                }
                std::unique_lock<std::mutex> lk(ctx->controlMutex);
                ctx->controlCv.wait_for(lk, std::chrono::milliseconds(10), [&] {
                    return !ctx->isBuffering.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                           ctx->videoFinished.load() || ctx->nativeWindow == nullptr || ctx->seekTargetMs.load() >= 0;
                });
                if (ctx->seekTargetMs.load() >= 0 || !ctx->isRunning.load() || ctx->isStopped.load()) break;
            }
        }

        if (ctx->videoStreamIdx < 0) {
            if (ctx->isBuffering.exchange(false)) {
                ctx->setMasterClockUs(ptsUs);
                ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
                ctx->notifyState(env, STATE_READY);
                ctx->controlCv.notify_all();
            }
        }

        float currentSpd = ctx->playbackSpeed.load();
        if (std::abs(currentSpd - lastFilterSpeed) > 0.005f ||
            (currentSpd != 1.0f && !audioFilter.graph)) {
            if (ctx->audioCodecCtx) {
                bool updatedDynamic = false;
                // Prefer dynamic atempo rate update over a full filtergraph rebuild (~10-15ms gap).
                // avfilter_graph_send_command works when transitioning between two non-1.0x speeds.
                if (audioFilter.graph &&
                    std::abs(currentSpd - 1.0f)    > 0.005f &&
                    std::abs(lastFilterSpeed - 1.0f) > 0.005f) {
                    float clampedSpd = std::max(0.5f, std::min(2.0f, currentSpd));
                    char tempoStr[32];
                    snprintf(tempoStr, sizeof(tempoStr), "%.4f", clampedSpd);
                    if (avfilter_graph_send_command(audioFilter.graph, "atempo", "tempo",
                                                    tempoStr, nullptr, 0, 0) >= 0) {
                        audioFilter.currentSpeed = currentSpd;
                        updatedDynamic = true;
                    }
                }
                if (!updatedDynamic) {
                    audioFilter.init(ctx->audioCodecCtx->sample_rate,
                                     ctx->audioCodecCtx->ch_layout.nb_channels,
                                     &ctx->audioCodecCtx->ch_layout,
                                     ctx->audioCodecCtx->sample_fmt,
                                     currentSpd);
                }
                lastFilterSpeed = currentSpd;
            }
        }

        auto convertAndSend = [&](AVFrame* frameToRender) {
            if (!frameToRender || frameToRender->nb_samples <= 0 || !frameToRender->data[0]) {
                return;
            }

            bool needReinitSwr = (localSwrCtx == nullptr) ||
                                 (frameToRender->sample_rate != lastInSampleRate) ||
                                 (frameToRender->format != lastInFmt) ||
                                 (av_channel_layout_compare(&frameToRender->ch_layout, &lastInChLayout) != 0);

            bool useFloat = (ctx->nativeAudioSink.getFormat() == AAUDIO_FORMAT_PCM_FLOAT);
            AVSampleFormat targetSampleFmt = useFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;

            if (needReinitSwr) {
                if (localSwrCtx) {
                    swr_free(&localSwrCtx);
                    localSwrCtx = nullptr;
                }
                av_channel_layout_uninit(&lastInChLayout);
                av_channel_layout_copy(&lastInChLayout, &frameToRender->ch_layout);
                lastInFmt = static_cast<AVSampleFormat>(frameToRender->format);
                lastInSampleRate = frameToRender->sample_rate;

                ctx->outSampleRate = (frameToRender->sample_rate > 0) ? frameToRender->sample_rate : 48000;
                int inCh = frameToRender->ch_layout.nb_channels > 0 ? frameToRender->ch_layout.nb_channels : 2;
                ctx->outChannels = (inCh == 1) ? 1 : ((inCh == 6) ? 6 : ((inCh == 8) ? 8 : 2));

                av_channel_layout_uninit(&ctx->outChLayout);
                av_channel_layout_default(&ctx->outChLayout, ctx->outChannels);

                ctx->nativeAudioSink.init(ctx->outSampleRate, ctx->outChannels);
                useFloat = (ctx->nativeAudioSink.getFormat() == AAUDIO_FORMAT_PCM_FLOAT);
                targetSampleFmt = useFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;

                swr_alloc_set_opts2(
                    &localSwrCtx,
                    &ctx->outChLayout,
                    targetSampleFmt,
                    ctx->outSampleRate,
                    &frameToRender->ch_layout,
                    static_cast<AVSampleFormat>(frameToRender->format),
                    frameToRender->sample_rate,
                    0, nullptr
                );

                if (localSwrCtx) {
                    // Configure ITU-R BS.775-1 surround downmix matrix coefficients for clear dialog
                    av_opt_set_double(localSwrCtx, "clev", 0.7071067811865476, 0); // Center -3dB (1/sqrt(2))
                    av_opt_set_double(localSwrCtx, "slev", 0.7071067811865476, 0); // Surround -3dB (1/sqrt(2))
                    av_opt_set_double(localSwrCtx, "rematrix_volume", 1.0, 0);
                }

                if (localSwrCtx && swr_init(localSwrCtx) >= 0) {
                    int sid = ctx->nativeAudioSink.getSessionId();
                    if (sid > 0) {
                        ctx->notifyAudioSessionId(env, sid);
                    }
                    ctx->nativeEqualizer.init(ctx->outSampleRate, ctx->outChannels);
                    compensationActive = 0;
                    LOGI("Audio resampler dynamically initialized: in(%d Hz, fmt %d, %d ch) -> out(%d Hz, %s, %d ch)",
                         frameToRender->sample_rate, frameToRender->format, inCh, ctx->outSampleRate, (useFloat ? "FLT" : "S16"), ctx->outChannels);
                } else {
                    LOGE("Failed to initialize dynamic audio resampler!");
                }
            }

            if (localSwrCtx) {
                // Dynamic clock drift compensation via libswresample
                if (ctx->videoStreamIdx >= 0 && !ctx->isBuffering.load() && !ctx->isPaused.load() && ctx->outSampleRate > 0) {
                    int64_t currentDrift = ctx->lastAudioDriftUs.load();
                    smoothedDriftUs = (smoothedDriftUs * 15 + currentDrift) / 16;

                    if (smoothedDriftUs > 20000 && smoothedDriftUs < 400000) {
                        // Video is ahead: speed up audio by generating fewer samples (shrink)
                        int delta = -static_cast<int>((ctx->outSampleRate * 2) / 1000);
                        if (compensationActive != -1) {
                            swr_set_compensation(localSwrCtx, delta, ctx->outSampleRate);
                            compensationActive = -1;
                        }
                    } else if (smoothedDriftUs < -20000 && smoothedDriftUs > -400000) {
                        // Video is behind: slow down audio slightly
                        int delta = static_cast<int>((ctx->outSampleRate * 2) / 1000);
                        if (compensationActive != 1) {
                            swr_set_compensation(localSwrCtx, delta, ctx->outSampleRate);
                            compensationActive = 1;
                        }
                    } else if (std::abs(smoothedDriftUs) <= 10000) {
                        if (compensationActive != 0) {
                            swr_set_compensation(localSwrCtx, 0, 0);
                            compensationActive = 0;
                        }
                    }
                }

                int outSamples = swr_get_out_samples(localSwrCtx, frameToRender->nb_samples);
                if (outSamples <= 0) return;
                int bytesPerSample = av_get_bytes_per_sample(targetSampleFmt);
                int reqSize = outSamples * ctx->outChannels * bytesPerSample;
                if (reqSize > audioOutBufSize) {
                    av_freep(&audioOutBuf);
                    audioOutBuf = static_cast<uint8_t*>(av_malloc(reqSize + 1024));
                    audioOutBufSize = reqSize + 1024;
                }
                uint8_t* outPtr = audioOutBuf;
                int conv = swr_convert(localSwrCtx, &outPtr, outSamples,
                                       const_cast<const uint8_t**>(frameToRender->data),
                                       frameToRender->nb_samples);
                if (conv > 0) {
                    int totalSamples = conv * ctx->outChannels;
                    int channels = ctx->outChannels;
                    // Snapshot the seek version before writing to AAudio.
                    // If a seek fires during the blocking write(), we must not update
                    // the master clock with the old pre-seek acoustic PTS.
                    int64_t capturedSeekVer = ctx->seekVersion.load(std::memory_order_acquire);

                    if (useFloat) {
                        float* floatSamples = reinterpret_cast<float*>(audioOutBuf);

                        // 1. Native Equalizer DSP in 32-bit Float
                        ctx->nativeEqualizer.process(floatSamples, conv, channels);

                        // 2. Smooth S-curve Volume Ramp-In
                        int remaining = ctx->rampInRemainingFrames.load();
                        if (remaining > 0) {
                            int totalRamp = ctx->totalRampFrames.load();
                            if (totalRamp < 1) totalRamp = 1;
                            for (int i = 0; i < totalSamples; i += channels) {
                                if (remaining > 0) {
                                    float progress = 1.0f - (static_cast<float>(remaining) / static_cast<float>(totalRamp));
                                    float smoothFactor = 0.5f * (1.0f - std::cos(progress * 3.14159265358979323846f));
                                    for (int ch = 0; ch < channels && (i + ch) < totalSamples; ch++) {
                                        floatSamples[i + ch] = floatSamples[i + ch] * smoothFactor;
                                    }
                                    remaining--;
                                }
                            }
                            ctx->rampInRemainingFrames.store(remaining);
                        }

                        // 3. Soft-Knee Peak Limiting (prevent digital clipping in float domain)
                        for (int i = 0; i < totalSamples; i++) {
                            float s = floatSamples[i];
                            if (s > 0.95f) {
                                s = 0.95f + (s - 0.95f) * 0.25f;
                                if (s > 1.0f) s = 1.0f;
                            } else if (s < -0.95f) {
                                s = -0.95f + (s + 0.95f) * 0.25f;
                                if (s < -1.0f) s = -1.0f;
                            }
                            floatSamples[i] = s;
                        }

                        // Direct write Float32 to AAudio Sink
                        ctx->nativeAudioSink.write(floatSamples, conv, ptsUs);
                    } else {
                        int16_t* pcmSamples = reinterpret_cast<int16_t*>(audioOutBuf);

                        // 1. Native Equalizer DSP in 16-bit PCM
                        ctx->nativeEqualizer.process(pcmSamples, conv, channels);

                        // 2. Smooth S-curve Volume Ramp-In
                        int remaining = ctx->rampInRemainingFrames.load();
                        if (remaining > 0) {
                            int totalRamp = ctx->totalRampFrames.load();
                            if (totalRamp < 1) totalRamp = 1;
                            for (int i = 0; i < totalSamples; i += channels) {
                                if (remaining > 0) {
                                    float progress = 1.0f - (static_cast<float>(remaining) / static_cast<float>(totalRamp));
                                    float smoothFactor = 0.5f * (1.0f - std::cos(progress * 3.14159265358979323846f));
                                    for (int ch = 0; ch < channels && (i + ch) < totalSamples; ch++) {
                                        pcmSamples[i + ch] = static_cast<int16_t>(pcmSamples[i + ch] * smoothFactor);
                                    }
                                    remaining--;
                                }
                            }
                            ctx->rampInRemainingFrames.store(remaining);
                        }

                        // 3. Soft-Knee Peak Limiting (prevent digital clipping in S16 domain)
                        for (int i = 0; i < totalSamples; i++) {
                            int32_t s = pcmSamples[i];
                            if (s > 30000) {
                                s = 30000 + static_cast<int32_t>((s - 30000) * 0.25f);
                                if (s > 32767) s = 32767;
                            } else if (s < -30000) {
                                s = -30000 + static_cast<int32_t>((s + 30000) * 0.25f);
                                if (s < -32768) s = -32768;
                            }
                            pcmSamples[i] = static_cast<int16_t>(s);
                        }

                        // Direct write S16 to AAudio Sink
                        ctx->nativeAudioSink.write(pcmSamples, conv, ptsUs);
                    }

                    int64_t frameEndPtsUs = ptsUs + (static_cast<int64_t>(conv) * 1000000LL) / (ctx->outSampleRate > 0 ? ctx->outSampleRate : 48000);
                    int64_t acousticPtsUs = ctx->nativeAudioSink.getAcousticPlaybackTimestampUs(frameEndPtsUs);
                    // Always anchor master clock to the real acoustic output of the audio DAC
                    if (ctx->seekVersion.load(std::memory_order_acquire) == capturedSeekVer) {
                        ctx->setMasterClockUs(acousticPtsUs);
                    }
                }
            } else {
                ctx->setMasterClockUs(ptsUs);
            }
        };

        if (audioFilter.graph && audioFilter.srcCtx && audioFilter.sinkCtx) {
            if (av_buffersrc_add_frame_flags(audioFilter.srcCtx, f, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
                while (av_buffersink_get_frame(audioFilter.sinkCtx, filterFrame) >= 0) {
                    convertAndSend(filterFrame);
                    av_frame_unref(filterFrame);
                }
            } else {
                convertAndSend(f);
            }
        } else {
            convertAndSend(f);
        }
    };

    while (ctx->isRunning.load() && !ctx->isStopped.load()) {
        if (ctx->isPaused.load() && ctx->seekTargetMs.load() < 0) {
            std::unique_lock<std::mutex> lk(ctx->controlMutex);
            ctx->controlCv.wait(lk, [&] {
                return !ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                       ctx->seekTargetMs.load() >= 0;
            });
            if (!ctx->isRunning.load() || ctx->isStopped.load()) break;
        }

        if (!ctx->audioQueue.pop(item, 50)) continue;

        if (item.isFlush) {
            std::lock_guard<std::mutex> lk(ctx->audioCodecMutex);
            if (ctx->audioCodecCtx) avcodec_flush_buffers(ctx->audioCodecCtx);
            audioFilter.release();
            ctx->nativeAudioSink.flush();
            ctx->nativeEqualizer.reset();
            smoothedDriftUs = 0;
            compensationActive = 0;
            lastFilterSpeed = 1.0f;
            av_frame_unref(aFrame);
            av_frame_unref(filterFrame);
            ctx->audioFinished.store(false);
            continue;
        }

        if (item.isEof) {
            LOGI("audioDecodeThread received EOF, draining decoder");
            std::lock_guard<std::mutex> lk(ctx->audioCodecMutex);
            if (ctx->audioCodecCtx) {
                avcodec_send_packet(ctx->audioCodecCtx, nullptr);
                while (avcodec_receive_frame(ctx->audioCodecCtx, aFrame) == 0) {
                    processAudioFrame(aFrame);
                    av_frame_unref(aFrame);
                }
            }
            ctx->audioFinished.store(true);
            ctx->checkPlaybackFinished(env);
            continue;
        }

        std::lock_guard<std::mutex> lk(ctx->audioCodecMutex);
        if (!ctx->audioCodecCtx || !item.pkt) {
            av_packet_free(&item.pkt);
            continue;
        }

        int sendRet = avcodec_send_packet(ctx->audioCodecCtx, item.pkt);
        if (sendRet == AVERROR(EAGAIN)) {
            while (avcodec_receive_frame(ctx->audioCodecCtx, aFrame) == 0) {
                processAudioFrame(aFrame);
                av_frame_unref(aFrame);
            }
            sendRet = avcodec_send_packet(ctx->audioCodecCtx, item.pkt);
        }
        av_packet_free(&item.pkt);

        while (avcodec_receive_frame(ctx->audioCodecCtx, aFrame) == 0) {
            processAudioFrame(aFrame);
            av_frame_unref(aFrame);
        }
    }

    audioFilter.release();
    if (localSwrCtx) {
        swr_free(&localSwrCtx);
        localSwrCtx = nullptr;
    }
    av_channel_layout_uninit(&lastInChLayout);
    ctx->nativeAudioSink.release();
    av_freep(&audioOutBuf);
    av_frame_free(&aFrame);
    av_frame_free(&filterFrame);

    g_jvm->DetachCurrentThread();
    LOGI("audioDecodeThread finished");
}

// ─── Demux Thread ────────────────────────────────────────────────────────────

static void demuxThreadFunc(FfmpegPlayerContext* ctx, int64_t initialSeekMs) {
    LOGI("demuxThread started");
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach demux thread to JVM");
        return;
    }

    ctx->isBuffering.store(true);
    ctx->notifyState(env, STATE_BUFFERING);

    if (initialSeekMs > 0) {
        ctx->setMasterClockUs(initialSeekMs * 1000);
        ctx->currentPositionMs.store(initialSeekMs);
        ctx->seekTargetMs.store(initialSeekMs);
    } else {
        ctx->setMasterClockUs(0);
        ctx->currentPositionMs.store(0);
        if (ctx->videoStreamIdx >= 0) {
            ctx->videoSeekTargetPtsUs.store(0);
            ctx->audioSeekTargetPtsUs.store(0);
        }
        av_seek_frame(ctx->fmtCtx, -1, 0, AVSEEK_FLAG_BACKWARD);
        if (ctx->fmtCtx->pb) {
            ctx->fmtCtx->pb->eof_reached = 0;
            ctx->fmtCtx->pb->error = 0;
        }
    }

    AVPacket* pkt = av_packet_alloc();
    auto lastPosNotify = std::chrono::steady_clock::now();

    while (ctx->isRunning.load() && !ctx->isStopped.load()) {
        int64_t target = ctx->seekTargetMs.exchange(-1);
        if (target >= 0) {
            bool scrubbing = ctx->isScrubbing.load();
            bool isFast = ctx->fastSeek.load() || scrubbing;
            LOGI("Seeking to %" PRId64 " ms (scrubbing=%d, fastSeek=%d)", target, scrubbing ? 1 : 0, isFast ? 1 : 0);
            ctx->setMasterClockUs(target * 1000);
            ctx->currentPositionMs.store(target);
            if (isFast) {
                ctx->videoSeekTargetPtsUs.store(-1);
                ctx->audioSeekTargetPtsUs.store(-1);
                ctx->isBuffering.store(true);
                ctx->notifyState(env, STATE_BUFFERING);
            } else {
                ctx->videoSeekTargetPtsUs.store(target * 1000);
                ctx->audioSeekTargetPtsUs.store(target * 1000);
                ctx->isBuffering.store(true);
                ctx->notifyState(env, STATE_BUFFERING);
            }
            ctx->demuxEof.store(false);
            ctx->videoFinished.store(false);
            ctx->audioFinished.store(false);
            ctx->endNotified.store(false);
            ctx->lastAudioDriftUs.store(0);
            ctx->videoQueue.clear();
            ctx->videoQueue.pushFlush();
            ctx->audioQueue.clear();
            ctx->audioQueue.pushFlush();
            ctx->nativeAudioSink.flush();

            int64_t targetUs = target * 1000;
            int seekRet = -1;
            if (target <= 0) {
                seekRet = av_seek_frame(ctx->fmtCtx, -1, 0, AVSEEK_FLAG_BACKWARD);
            } else {
                seekRet = av_seek_frame(ctx->fmtCtx, -1, targetUs, AVSEEK_FLAG_BACKWARD);
                if (seekRet < 0 && ctx->videoStreamIdx >= 0) {
                    int64_t vSeekTs = av_rescale_q(targetUs, AV_TIME_BASE_Q, ctx->videoTimeBase);
                    seekRet = av_seek_frame(ctx->fmtCtx, ctx->videoStreamIdx, vSeekTs, AVSEEK_FLAG_BACKWARD);
                }
            }
            if (ctx->fmtCtx->pb) {
                ctx->fmtCtx->pb->eof_reached = 0;
                ctx->fmtCtx->pb->error = 0;
            }

            if (!scrubbing) {
                ctx->triggerAudioRampIn(40);
                ctx->notifyAudioFlush(env);
            }
            ctx->notifyPosition(env, target, ctx->durationMs);
            ctx->controlCv.notify_all();
        }

        if (ctx->isScrubbing.load() && ctx->seekTargetMs.load() < 0 && ctx->videoQueue.size() >= 2) {
            std::unique_lock<std::mutex> lk(ctx->controlMutex);
            ctx->controlCv.wait_for(lk, std::chrono::milliseconds(20), [&] {
                return !ctx->isScrubbing.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                       ctx->seekTargetMs.load() >= 0;
            });
            if (!ctx->isRunning.load() || ctx->isStopped.load()) break;
        }

        ctx->lastIoTimeMs.store(getMonotonicTimeMs());
        int ret = av_read_frame(ctx->fmtCtx, pkt);
        ctx->lastIoTimeMs.store(getMonotonicTimeMs());
        if (ret < 0) {
            if (ret == AVERROR_EOF) {
                if (!ctx->demuxEof.exchange(true)) {
                    LOGI("Demuxer reached EOF, sending EOF to queues");
                    if (ctx->videoStreamIdx >= 0) ctx->videoQueue.pushEof();
                    if (ctx->audioStreamIdx >= 0) ctx->audioQueue.pushEof();
                    if (ctx->videoStreamIdx < 0 && ctx->audioStreamIdx < 0) {
                        ctx->checkPlaybackFinished(env);
                    }
                }
                std::unique_lock<std::mutex> lk(ctx->controlMutex);
                ctx->controlCv.wait_for(lk, std::chrono::milliseconds(100), [&] {
                    return !ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0;
                });
                continue;
            } else if (ret == AVERROR_EXIT || ctx->ioBridge.abortRequested.load() || ctx->isStopped.load()) {
                break;
            }
            if (ctx->ioTimeoutMs.load() > 0 && ctx->lastIoTimeMs.load() > 0) {
                int64_t elapsed = getMonotonicTimeMs() - ctx->lastIoTimeMs.load();
                if (elapsed >= ctx->ioTimeoutMs.load()) {
                    LOGE("Demuxer read timed out after %" PRId64 " ms", elapsed);
                    ctx->notifyError(env, "Network read timed out");
                    break;
                }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        if (pkt->stream_index == ctx->videoStreamIdx && (ctx->videoCodecCtx || ctx->videoCodecPar)) {
            AVPacket* vpkt = av_packet_alloc();
            if (vpkt) {
                av_packet_move_ref(vpkt, pkt);
                ctx->videoQueue.push(vpkt, ctx->videoTimeBase);
            }
        } else if (pkt->stream_index == ctx->audioStreamIdx && ctx->audioCodecCtx) {
            if (!ctx->isScrubbing.load()) {
                AVPacket* apkt = av_packet_alloc();
                if (apkt) {
                    av_packet_move_ref(apkt, pkt);
                    ctx->audioQueue.push(apkt, ctx->audioTimeBase);
                }
            }
        } else {
            // Forward or decode subtitle packets
            for (int subIdx : ctx->subtitleStreamIndices) {
                if (pkt->stream_index == subIdx && pkt->data && pkt->size > 0) {
                    if (ctx->selectedSubtitleStreamIdx >= 0 && subIdx != ctx->selectedSubtitleStreamIdx) {
                        break;
                    }

                    AVStream* st = ctx->fmtCtx->streams[subIdx];
                    AVRational tb = st->time_base;
                    int64_t ptsUs = (pkt->pts != AV_NOPTS_VALUE)
                        ? av_rescale_q(pkt->pts, tb, AV_TIME_BASE_Q)
                        : ((pkt->dts != AV_NOPTS_VALUE)
                            ? av_rescale_q(pkt->dts, tb, AV_TIME_BASE_Q)
                            : 0);
                    int64_t durUs = (pkt->duration > 0)
                        ? av_rescale_q(pkt->duration, tb, AV_TIME_BASE_Q)
                        : 3000000LL;

                    std::lock_guard<std::mutex> subLock(ctx->subtitleMutex);
                    if (ctx->subtitleCodecCtx && ctx->midOnBitmapSubtitle) {
                        AVSubtitle sub;
                        memset(&sub, 0, sizeof(sub));
                        int gotSub = 0;
                        int decRet = avcodec_decode_subtitle2(ctx->subtitleCodecCtx, &sub, &gotSub, pkt);
                        if (decRet >= 0 && gotSub > 0) {
                            int canvasW = ctx->videoWidth > 0 ? ctx->videoWidth : 1920;
                            int canvasH = ctx->videoHeight > 0 ? ctx->videoHeight : 1080;
                            int64_t startPtsUs = ptsUs + static_cast<int64_t>(sub.start_display_time) * 1000LL;
                            int64_t endPtsUs = (sub.end_display_time > 0 && sub.end_display_time > sub.start_display_time)
                                ? (ptsUs + static_cast<int64_t>(sub.end_display_time) * 1000LL)
                                : (startPtsUs + durUs);

                            if (sub.num_rects == 0) {
                                env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnBitmapSubtitle,
                                                    static_cast<jint>(subIdx), static_cast<jlong>(startPtsUs),
                                                    static_cast<jlong>(endPtsUs), 0, 0, 0, 0, nullptr,
                                                    static_cast<jint>(canvasW), static_cast<jint>(canvasH));
                                if (env->ExceptionCheck()) env->ExceptionClear();
                            } else {
                                for (unsigned r = 0; r < sub.num_rects; r++) {
                                    AVSubtitleRect* rect = sub.rects[r];
                                    if (!rect || rect->w <= 0 || rect->h <= 0) continue;

                                    if (rect->type == SUBTITLE_BITMAP && rect->data[0]) {
                                        int pixelCount = rect->w * rect->h;
                                        if (ctx->subtitlePixelBuf.size() < static_cast<size_t>(pixelCount)) {
                                            ctx->subtitlePixelBuf.resize(pixelCount);
                                        }
                                        uint32_t* palette = reinterpret_cast<uint32_t*>(rect->data[1]);

                                        for (int y = 0; y < rect->h; y++) {
                                            uint8_t* rowSrc = rect->data[0] + y * rect->linesize[0];
                                            jint* rowDst = ctx->subtitlePixelBuf.data() + y * rect->w;
                                            for (int x = 0; x < rect->w; x++) {
                                                uint8_t colorIdx = rowSrc[x];
                                                rowDst[x] = palette ? static_cast<jint>(palette[colorIdx]) : 0;
                                            }
                                        }

                                        jintArray jPixelArray = env->NewIntArray(pixelCount);
                                        if (jPixelArray) {
                                            env->SetIntArrayRegion(jPixelArray, 0, pixelCount, ctx->subtitlePixelBuf.data());
                                            env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnBitmapSubtitle,
                                                                static_cast<jint>(subIdx), static_cast<jlong>(startPtsUs),
                                                                static_cast<jlong>(endPtsUs), static_cast<jint>(rect->x),
                                                                static_cast<jint>(rect->y), static_cast<jint>(rect->w),
                                                                static_cast<jint>(rect->h), jPixelArray,
                                                                static_cast<jint>(canvasW), static_cast<jint>(canvasH));
                                            if (env->ExceptionCheck()) env->ExceptionClear();
                                            env->DeleteLocalRef(jPixelArray);
                                        }
                                    }
                                }
                            }
                            avsubtitle_free(&sub);
                        }
                    } else if (ctx->midOnSubtitleData) {
                        jbyteArray jSubData = env->NewByteArray(pkt->size);
                        if (jSubData) {
                            env->SetByteArrayRegion(jSubData, 0, pkt->size, reinterpret_cast<jbyte*>(pkt->data));
                            env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnSubtitleData,
                                                static_cast<jint>(subIdx), static_cast<jlong>(ptsUs),
                                                static_cast<jlong>(durUs), jSubData);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                            env->DeleteLocalRef(jSubData);
                        }
                    }
                    break;
                }
            }
        }
        av_packet_unref(pkt);

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPosNotify).count() >= 200) {
            lastPosNotify = now;
            int64_t curMs;
            if (ctx->seekTargetMs.load() >= 0) {
                curMs = ctx->seekTargetMs.load();
            } else if (ctx->videoSeekTargetPtsUs.load() >= 0) {
                curMs = ctx->videoSeekTargetPtsUs.load() / 1000;
            } else if (ctx->audioSeekTargetPtsUs.load() >= 0) {
                curMs = ctx->audioSeekTargetPtsUs.load() / 1000;
            } else {
                curMs = ctx->getMasterClockUs() / 1000;
                if (curMs < 0) curMs = 0;
                if (ctx->durationMs > 0 && curMs > ctx->durationMs) curMs = ctx->durationMs;
            }
            ctx->currentPositionMs.store(curMs);
            ctx->notifyPosition(env, curMs, ctx->durationMs);
        }
    }

    av_packet_free(&pkt);
    g_jvm->DetachCurrentThread();
    LOGI("demuxThread finished");
}

// ─── JNI Exports ─────────────────────────────────────────────────────────────

#define JNI_FUNC(RETURN_TYPE, NAME, ...) \
    extern "C" JNIEXPORT RETURN_TYPE JNICALL \
    Java_com_rhnxdev_hzplayer_data_datasource_player_ffmpeg_FfmpegNativePlayer_##NAME( \
        JNIEnv* env, jobject thiz, ##__VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNI_FUNC(jlong, nativeCreate) {
    auto* ctx = new FfmpegPlayerContext(env, thiz);
    return reinterpret_cast<jlong>(ctx);
}

JNI_FUNC(jboolean, nativeOpen, jlong handle, jobject bridgeObj, jstring urlStr, jobject surfaceObj, jlong startPositionMs, jobjectArray headersArr) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return JNI_FALSE;

    ctx->stopPlayback();
    ctx->closeMedia();

    ctx->isBuffering.store(true);
    ctx->totalRenderedFrames.store(0);
    ctx->totalDroppedFrames.store(0);
    ctx->videoSeekTargetPtsUs.store(startPositionMs > 0 ? startPositionMs * 1000 : 0);
    ctx->audioSeekTargetPtsUs.store(startPositionMs > 0 ? startPositionMs * 1000 : 0);
    ctx->setMasterClockUs(startPositionMs > 0 ? startPositionMs * 1000 : 0);
    ctx->currentPositionMs.store(startPositionMs > 0 ? startPositionMs : 0);
    ctx->videoRotation = 0;
    ctx->videoSarNum = 1;
    ctx->videoSarDen = 1;
    ctx->videoCodecName.clear();
    ctx->audioCodecName.clear();
    ctx->audioLanguage.clear();
    ctx->sourceFps = 0.0f;
    ctx->videoBitrate = 0;
    ctx->audioBitrate = 0;

    const char* urlChars = urlStr ? env->GetStringUTFChars(urlStr, nullptr) : nullptr;
    std::string url = urlChars ? urlChars : "";
    if (urlChars) env->ReleaseStringUTFChars(urlStr, urlChars);

    std::string customHeaders;
    std::string userAgent;
    if (headersArr) {
        jsize len = env->GetArrayLength(headersArr);
        for (jsize i = 0; i + 1 < len; i += 2) {
            auto* kStr = static_cast<jstring>(env->GetObjectArrayElement(headersArr, i));
            auto* vStr = static_cast<jstring>(env->GetObjectArrayElement(headersArr, i + 1));
            if (kStr && vStr) {
                const char* kChars = env->GetStringUTFChars(kStr, nullptr);
                const char* vChars = env->GetStringUTFChars(vStr, nullptr);
                if (kChars && vChars) {
                    if (strcasecmp(kChars, "User-Agent") == 0) {
                        userAgent = vChars;
                    } else {
                        customHeaders += std::string(kChars) + ": " + std::string(vChars) + "\r\n";
                    }
                }
                if (kChars) env->ReleaseStringUTFChars(kStr, kChars);
                if (vChars) env->ReleaseStringUTFChars(vStr, vChars);
            }
            if (kStr) env->DeleteLocalRef(kStr);
            if (vStr) env->DeleteLocalRef(vStr);
        }
    }

    ctx->fmtCtx = avformat_alloc_context();
    if (!ctx->fmtCtx) {
        LOGE("Failed to allocate format context");
        return JNI_FALSE;
    }

    ctx->lastIoTimeMs.store(getMonotonicTimeMs());
    ctx->fmtCtx->interrupt_callback.callback = player_interrupt_callback;
    ctx->fmtCtx->interrupt_callback.opaque = ctx;

    if (bridgeObj) {
        ctx->jniFile = new JniFile(env, bridgeObj);
        if (!ctx->jniFile->ok()) {
            LOGE("JniFile init failed");
            ctx->closeMedia();
            return JNI_FALSE;
        }
        ctx->ioBridge.file = ctx->jniFile;
        ctx->ioBridge.abortRequested.store(false);

        ctx->avioBuf = static_cast<uint8_t*>(av_malloc(FfmpegPlayerContext::AVIO_BUF_SIZE));
        ctx->avioCtx = avio_alloc_context(
            ctx->avioBuf, FfmpegPlayerContext::AVIO_BUF_SIZE, 0,
            &ctx->ioBridge, player_io_read, nullptr, player_io_seek);

        if (!ctx->avioCtx) {
            LOGE("Failed to allocate AVIOContext");
            ctx->closeMedia();
            return JNI_FALSE;
        }
        ctx->fmtCtx->pb = ctx->avioCtx;
    }

    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "buffer_size", "2097152", 0);
    av_dict_set(&opts, "analyzeduration", "500000", 0);
    av_dict_set(&opts, "probesize", "524288", 0);
    av_dict_set(&opts, "genpts", "1", 0);
    av_dict_set(&opts, "fflags", "+genpts+discardcorrupt+fastseek", 0);

    bool isNetworkStream = (!url.empty() && (url.rfind("http://", 0) == 0 || url.rfind("https://", 0) == 0 || url.rfind("rtsp://", 0) == 0));
    if (isNetworkStream) {
        av_dict_set(&opts, "flags", "low_delay", 0);
        av_dict_set(&opts, "rw_timeout", "15000000", 0); // 15 seconds in microseconds
        av_dict_set(&opts, "timeout", "15000000", 0);    // 15 seconds in microseconds
        av_dict_set(&opts, "reconnect", "1", 0);
        av_dict_set(&opts, "reconnect_streamed", "1", 0);
        av_dict_set(&opts, "reconnect_delay_max", "5", 0);
    }

    if (!customHeaders.empty()) {
        av_dict_set(&opts, "headers", customHeaders.c_str(), 0);
        LOGI("Configured custom HTTP headers (%zu bytes)", customHeaders.size());
    }
    if (!userAgent.empty()) {
        av_dict_set(&opts, "user_agent", userAgent.c_str(), 0);
        LOGI("Configured custom User-Agent: %s", userAgent.c_str());
    }

    const char* openPath = bridgeObj ? "" : url.c_str();
    ctx->lastIoTimeMs.store(getMonotonicTimeMs());
    int openRet = avformat_open_input(&ctx->fmtCtx, openPath, nullptr, &opts);
    av_dict_free(&opts);
    ctx->lastIoTimeMs.store(getMonotonicTimeMs());

    if (openRet < 0) {
        LOGE("avformat_open_input failed with code %d", openRet);
        ctx->closeMedia();
        return JNI_FALSE;
    }

    ctx->fmtCtx->flags |= AVFMT_FLAG_FAST_SEEK;
    if (isNetworkStream) {
        ctx->fmtCtx->flags |= AVFMT_FLAG_NOBUFFER;
    }
    ctx->fmtCtx->max_analyze_duration = 500000;
    ctx->fmtCtx->probesize = 524288;

    ctx->lastIoTimeMs.store(getMonotonicTimeMs());
    if (avformat_find_stream_info(ctx->fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        ctx->closeMedia();
        return JNI_FALSE;
    }
    ctx->lastIoTimeMs.store(getMonotonicTimeMs());

    ctx->durationMs = (ctx->fmtCtx->duration > 0) ? (ctx->fmtCtx->duration / 1000) : 0;

    ctx->videoStreamIdx = -1;
    ctx->audioStreamIdx = -1;
    ctx->subtitleStreamIndices.clear();

    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        AVStream* st = ctx->fmtCtx->streams[i];
        AVCodecParameters* codecpar = st->codecpar;
        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO && ctx->videoStreamIdx < 0) {
            ctx->videoStreamIdx = static_cast<int>(i);
        } else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO && ctx->audioStreamIdx < 0) {
            ctx->audioStreamIdx = static_cast<int>(i);
        } else if (codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            ctx->subtitleStreamIndices.push_back(static_cast<int>(i));
            if (ctx->selectedSubtitleStreamIdx < 0) {
                ctx->selectedSubtitleStreamIdx = static_cast<int>(i);
                AVCodecID cid = codecpar->codec_id;
                if (cid == AV_CODEC_ID_HDMV_PGS_SUBTITLE || cid == AV_CODEC_ID_DVD_SUBTITLE ||
                    cid == AV_CODEC_ID_DVB_SUBTITLE || cid == AV_CODEC_ID_XSUB) {
                    const AVCodec* sCodec = avcodec_find_decoder(cid);
                    if (sCodec) {
                        ctx->subtitleCodecCtx = avcodec_alloc_context3(sCodec);
                        avcodec_parameters_to_context(ctx->subtitleCodecCtx, codecpar);
                        avcodec_open2(ctx->subtitleCodecCtx, sCodec, nullptr);
                        LOGI("Auto-selected bitmap subtitle track %d (%s)", static_cast<int>(i), sCodec->name);
                    }
                }
            }
            if (ctx->midOnSubtitleHeader) {
                int headerSize = (codecpar->extradata && codecpar->extradata_size > 0) ? codecpar->extradata_size : 0;
                jbyteArray jHeader = env->NewByteArray(headerSize);
                if (jHeader) {
                    if (headerSize > 0 && codecpar->extradata) {
                        env->SetByteArrayRegion(jHeader, 0, headerSize,
                                                reinterpret_cast<jbyte*>(codecpar->extradata));
                    }
                    AVDictionaryEntry* titleEntry = av_dict_get(st->metadata, "title", nullptr, 0);
                    AVDictionaryEntry* langEntry = av_dict_get(st->metadata, "language", nullptr, 0);
                    std::string subTitle = "Subtitle";
                    if (titleEntry && titleEntry->value && strlen(titleEntry->value) > 0) {
                        subTitle = titleEntry->value;
                        if (langEntry && langEntry->value && strlen(langEntry->value) > 0) {
                            subTitle += " [" + std::string(langEntry->value) + "]";
                        }
                    } else if (langEntry && langEntry->value && strlen(langEntry->value) > 0) {
                        subTitle = std::string("Subtitle (") + langEntry->value + ")";
                    }
                    jstring jTitle = env->NewStringUTF(subTitle.c_str());
                    env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnSubtitleHeader,
                                        static_cast<jint>(i), jHeader, jTitle);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    env->DeleteLocalRef(jTitle);
                    env->DeleteLocalRef(jHeader);
                }
            }
        } else if (codecpar->codec_type == AVMEDIA_TYPE_ATTACHMENT) {
            if (codecpar->extradata && codecpar->extradata_size > 0 && ctx->midOnFontAttachment) {
                AVDictionaryEntry* nameEntry = av_dict_get(st->metadata, "filename", nullptr, 0);
                const char* fontName = nameEntry ? nameEntry->value : "font.ttf";
                jstring jName = env->NewStringUTF(fontName);
                jbyteArray jFont = env->NewByteArray(codecpar->extradata_size);
                if (jFont && jName) {
                    env->SetByteArrayRegion(jFont, 0, codecpar->extradata_size,
                                            reinterpret_cast<jbyte*>(codecpar->extradata));
                    env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnFontAttachment, jName, jFont);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
                if (jName) env->DeleteLocalRef(jName);
                if (jFont) env->DeleteLocalRef(jFont);
            }
        }
    }

    if (surfaceObj) {
        ctx->setSurface(env, surfaceObj);
    }

    // Init Video Decoder with multithreading and error resilience
    if (ctx->videoStreamIdx >= 0) {
        AVStream* vst = ctx->fmtCtx->streams[ctx->videoStreamIdx];
        ctx->videoTimeBase = vst->time_base;

        // Parse SAR (Sample Aspect Ratio)
        ctx->videoSarNum = 1;
        ctx->videoSarDen = 1;
        if (vst->sample_aspect_ratio.num > 0 && vst->sample_aspect_ratio.den > 0) {
            ctx->videoSarNum = vst->sample_aspect_ratio.num;
            ctx->videoSarDen = vst->sample_aspect_ratio.den;
        } else if (vst->codecpar->sample_aspect_ratio.num > 0 && vst->codecpar->sample_aspect_ratio.den > 0) {
            ctx->videoSarNum = vst->codecpar->sample_aspect_ratio.num;
            ctx->videoSarDen = vst->codecpar->sample_aspect_ratio.den;
        }

        // Parse Rotation / Display Matrix
        ctx->videoRotation = 0;
        const AVPacketSideData* sd = av_packet_side_data_get(
            vst->codecpar->coded_side_data,
            vst->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX
        );
        if (sd && sd->data && sd->size >= sizeof(int32_t) * 9) {
            const int32_t* displayMatrix = reinterpret_cast<const int32_t*>(sd->data);
            double rot = -av_display_rotation_get(displayMatrix);
            if (!std::isnan(rot)) {
                int iRot = static_cast<int>(std::round(rot)) % 360;
                if (iRot < 0) iRot += 360;
                ctx->videoRotation = iRot;
            }
        } else {
            AVDictionaryEntry* rotEntry = av_dict_get(vst->metadata, "rotate", nullptr, 0);
            if (!rotEntry) rotEntry = av_dict_get(ctx->fmtCtx->metadata, "rotate", nullptr, 0);
            if (rotEntry && rotEntry->value) {
                int iRot = atoi(rotEntry->value) % 360;
                if (iRot < 0) iRot += 360;
                ctx->videoRotation = iRot;
            }
        }
        LOGI("Video parsed: rotation=%d deg, SAR=%d:%d", ctx->videoRotation, ctx->videoSarNum, ctx->videoSarDen);

        ctx->videoCodecPar = avcodec_parameters_alloc();
        if (ctx->videoCodecPar) {
            avcodec_parameters_copy(ctx->videoCodecPar, vst->codecpar);
        }
        const AVCodec* vCodec = nullptr;
        if (vst->codecpar->codec_id == AV_CODEC_ID_AV1) {
            vCodec = avcodec_find_decoder_by_name("libdav1d");
        }
        if (!vCodec) {
            vCodec = avcodec_find_decoder(vst->codecpar->codec_id);
        }
        if (vCodec) {
            ctx->videoCodecCtx = avcodec_alloc_context3(vCodec);
            avcodec_parameters_to_context(ctx->videoCodecCtx, vst->codecpar);
            unsigned int hwThreads = std::thread::hardware_concurrency();
            if (hwThreads > 8) hwThreads = 8;
            if (hwThreads < 2) hwThreads = 2;
            ctx->videoCodecCtx->thread_count = static_cast<int>(hwThreads);
            ctx->videoCodecCtx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
            ctx->videoCodecCtx->flags |= AV_CODEC_FLAG_OUTPUT_CORRUPT;
            ctx->videoCodecCtx->flags2 |= AV_CODEC_FLAG2_FAST;

            AVDictionary* codecOpts = nullptr;
            av_dict_set(&codecOpts, "threads", "auto", 0);
            av_dict_set(&codecOpts, "framedrop", "1", 0);
            av_dict_set_int(&codecOpts, "tile_threads", hwThreads >= 4 ? 4 : hwThreads, 0);
            av_dict_set_int(&codecOpts, "frame_threads", hwThreads >= 4 ? 4 : hwThreads, 0);
            av_dict_set_int(&codecOpts, "low_delay", 1, 0);

            if (avcodec_open2(ctx->videoCodecCtx, vCodec, &codecOpts) == 0) {
                ctx->videoWidth = ctx->videoCodecCtx->width;
                ctx->videoHeight = ctx->videoCodecCtx->height;
                ctx->videoCodecName = vCodec->name;
                if (vst->r_frame_rate.den > 0 && vst->r_frame_rate.num > 0) {
                    ctx->sourceFps = static_cast<float>(av_q2d(vst->r_frame_rate));
                } else if (vst->avg_frame_rate.den > 0 && vst->avg_frame_rate.num > 0) {
                    ctx->sourceFps = static_cast<float>(av_q2d(vst->avg_frame_rate));
                }
                ctx->videoBitrate = (ctx->videoCodecCtx && ctx->videoCodecCtx->bit_rate > 0)
                    ? ctx->videoCodecCtx->bit_rate
                    : vst->codecpar->bit_rate;
                if (ctx->videoWidth > 0 && ctx->videoHeight > 0) {
                    ctx->notifyVideoSize(env, ctx->videoWidth, ctx->videoHeight, ctx->videoRotation, ctx->videoSarNum, ctx->videoSarDen);
                }
                LOGI("Video decoder initialized: %s (%dx%d, threads=%d)",
                     vCodec->name, ctx->videoWidth, ctx->videoHeight, ctx->videoCodecCtx->thread_count);
            } else {
                LOGE("avcodec_open2 failed for video decoder %s", vCodec->name);
                ctx->notifyError(env, "Failed to initialize video decoder");
            }
            av_dict_free(&codecOpts);
        } else {
            LOGE("No video decoder found for codec ID %d", vst->codecpar->codec_id);
            ctx->notifyError(env, "Unsupported video codec");
        }
    }

    // Init Audio Decoder
    if (ctx->audioStreamIdx >= 0) {
        AVStream* ast = ctx->fmtCtx->streams[ctx->audioStreamIdx];
        ctx->audioTimeBase = ast->time_base;
        const AVCodec* aCodec = avcodec_find_decoder(ast->codecpar->codec_id);
        if (aCodec) {
            ctx->audioCodecCtx = avcodec_alloc_context3(aCodec);
            avcodec_parameters_to_context(ctx->audioCodecCtx, ast->codecpar);
            ctx->audioCodecCtx->thread_count = 2;
            ctx->audioCodecCtx->flags |= AV_CODEC_FLAG_OUTPUT_CORRUPT;
            if (avcodec_open2(ctx->audioCodecCtx, aCodec, nullptr) == 0) {
                ctx->outSampleRate = ctx->audioCodecCtx->sample_rate > 0 ? ctx->audioCodecCtx->sample_rate : 48000;
                int inChannels = ctx->audioCodecCtx->ch_layout.nb_channels;
                if (inChannels == 1) {
                    ctx->outChannels = 1;
                } else if (inChannels == 6) {
                    ctx->outChannels = 6;
                } else if (inChannels == 8) {
                    ctx->outChannels = 8;
                } else {
                    ctx->outChannels = 2;
                }
                ctx->audioCodecName = aCodec->name;
                AVDictionaryEntry* langEntry = av_dict_get(ast->metadata, "language", nullptr, 0);
                ctx->audioLanguage = langEntry ? langEntry->value : "";
                ctx->audioBitrate = (ctx->audioCodecCtx && ctx->audioCodecCtx->bit_rate > 0)
                    ? ctx->audioCodecCtx->bit_rate
                    : ast->codecpar->bit_rate;

                av_channel_layout_uninit(&ctx->outChLayout);
                av_channel_layout_default(&ctx->outChLayout, ctx->outChannels);
                ctx->nativeAudioSink.init(ctx->outSampleRate, ctx->outChannels);
                int sid = ctx->nativeAudioSink.getSessionId();
                if (sid > 0) {
                    ctx->notifyAudioSessionId(env, sid);
                }
                ctx->nativeEqualizer.init(ctx->outSampleRate, ctx->outChannels);
                LOGI("Audio decoder initialized: %s (sampleRate=%d, ch=%d, sessionId=%d)", aCodec->name, ctx->outSampleRate, ctx->outChannels, sid);
            }
        }
    }

    ctx->isRunning.store(true);
    ctx->isStopped.store(false);
    ctx->isPaused.store(false);
    ctx->triggerAudioRampIn(80);

    // Start video and audio decode threads, then demux thread
    ctx->videoThread = std::thread(videoDecodeThreadFunc, ctx);
    ctx->audioThread = std::thread(audioDecodeThreadFunc, ctx);
    ctx->demuxThread = std::thread(demuxThreadFunc, ctx, startPositionMs);
    return JNI_TRUE;
}

JNI_FUNC(void, nativeSetSurface, jlong handle, jobject surface) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) ctx->setSurface(env, surface);
}

JNI_FUNC(void, nativePlay, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        if (ctx->demuxEof.load() && ctx->videoFinished.load() && ctx->audioFinished.load()) {
            ctx->seekTargetMs.store(0);
        }
        ctx->triggerAudioRampIn(50);
        ctx->resumeClock();
        ctx->nativeAudioSink.play();
        ctx->controlCv.notify_all();
    }
}

JNI_FUNC(void, nativePause, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->pauseClock();
        ctx->nativeAudioSink.pause();
        ctx->controlCv.notify_all();
    }
}

JNI_FUNC(void, nativeSeek, jlong handle, jlong posMs) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        // Increment seek version FIRST so that any frame currently mid-render
        // or mid-write in audio sees the new version and discards itself.
        ctx->seekVersion.fetch_add(1, std::memory_order_release);
        ctx->isBuffering.store(true);
        ctx->lastAudioDriftUs.store(0);
        ctx->seekTargetMs.store(posMs);
        ctx->currentPositionMs.store(posMs);
        ctx->setMasterClockUs(posMs * 1000);
        ctx->controlCv.notify_all();
        ctx->videoQueue.notFull.notify_all();
        ctx->audioQueue.notFull.notify_all();
        ctx->videoQueue.notEmpty.notify_all();
        ctx->audioQueue.notEmpty.notify_all();
    }
}

JNI_FUNC(void, nativeStop, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->stopPlayback();
    }
}

JNI_FUNC(void, nativeRelease, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        delete ctx;
    }
}

JNI_FUNC(jlong, nativeGetDuration, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->durationMs : 0;
}

JNI_FUNC(jlong, nativeGetPosition, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return 0;
    if (ctx->seekTargetMs.load() >= 0) {
        return ctx->seekTargetMs.load();
    }
    if (ctx->videoSeekTargetPtsUs.load() >= 0) {
        return ctx->videoSeekTargetPtsUs.load() / 1000;
    }
    if (ctx->audioSeekTargetPtsUs.load() >= 0) {
        return ctx->audioSeekTargetPtsUs.load() / 1000;
    }
    int64_t clockMs = ctx->getMasterClockUs() / 1000;
    if (clockMs < 0) clockMs = 0;
    if (ctx->durationMs > 0 && clockMs > ctx->durationMs) clockMs = ctx->durationMs;
    return clockMs;
}

JNI_FUNC(jboolean, nativeIsPlaying, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return (ctx && ctx->isRunning.load() && !ctx->isPaused.load()) ? JNI_TRUE : JNI_FALSE;
}

JNI_FUNC(void, nativeSetSpeed, jlong handle, jfloat speed) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->playbackSpeed.store(speed);
    }
}

JNI_FUNC(void, nativeSetFastSeek, jlong handle, jboolean enabled) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->fastSeek.store(enabled == JNI_TRUE);
    }
}

JNI_FUNC(jobjectArray, nativeGetAudioTracks, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx || !ctx->fmtCtx) return nullptr;

    std::vector<std::string> names;
    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        AVStream* st = ctx->fmtCtx->streams[i];
        if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            AVDictionaryEntry* lang = av_dict_get(st->metadata, "language", nullptr, 0);
            AVDictionaryEntry* title = av_dict_get(st->metadata, "title", nullptr, 0);
            std::string name = "Audio Track " + std::to_string(names.size() + 1);
            if (title && title->value) {
                name = title->value;
            } else if (lang && lang->value) {
                name = std::string("Audio (") + lang->value + ")";
            }
            names.push_back(name);
        }
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strCls, nullptr);
    for (size_t i = 0; i < names.size(); i++) {
        jstring jstr = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), jstr);
        env->DeleteLocalRef(jstr);
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

JNI_FUNC(jint, nativeGetVideoWidth, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->videoWidth : 0;
}

JNI_FUNC(jint, nativeGetVideoHeight, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->videoHeight : 0;
}

JNI_FUNC(jboolean, nativeSelectAudioTrack, jlong handle, jint targetTrackIndex) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx || !ctx->fmtCtx) return JNI_FALSE;

    int audioCount = 0;
    int targetStreamIdx = -1;
    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        if (ctx->fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (audioCount == targetTrackIndex) {
                targetStreamIdx = static_cast<int>(i);
                break;
            }
            audioCount++;
        }
    }

    if (targetStreamIdx < 0 || targetStreamIdx == ctx->audioStreamIdx) {
        return JNI_FALSE;
    }

    AVStream* ast = ctx->fmtCtx->streams[targetStreamIdx];
    const AVCodec* aCodec = avcodec_find_decoder(ast->codecpar->codec_id);
    if (!aCodec) {
        LOGE("No decoder found for selected audio track %d (codec ID %d)", targetTrackIndex, ast->codecpar->codec_id);
        return JNI_FALSE;
    }

    AVCodecContext* newCodecCtx = avcodec_alloc_context3(aCodec);
    if (!newCodecCtx) return JNI_FALSE;
    avcodec_parameters_to_context(newCodecCtx, ast->codecpar);
    newCodecCtx->thread_count = 2;
    newCodecCtx->flags |= AV_CODEC_FLAG_OUTPUT_CORRUPT;

    if (avcodec_open2(newCodecCtx, aCodec, nullptr) < 0) {
        LOGE("Failed to open codec for selected audio track %d", targetTrackIndex);
        avcodec_free_context(&newCodecCtx);
        return JNI_FALSE;
    }

    int newSampleRate = newCodecCtx->sample_rate > 0 ? newCodecCtx->sample_rate : 48000;
    int inChannels = newCodecCtx->ch_layout.nb_channels;
    int newChannels = 2;
    if (inChannels == 1) newChannels = 1;
    else if (inChannels == 6) newChannels = 6;
    else if (inChannels == 8) newChannels = 8;
    else newChannels = 2;

    AVChannelLayout targetLayout{};
    av_channel_layout_default(&targetLayout, newChannels);

    // Safely swap contexts under mutex
    {
        std::lock_guard<std::mutex> lock(ctx->audioCodecMutex);
        if (ctx->audioCodecCtx) {
            avcodec_free_context(&ctx->audioCodecCtx);
        }

        ctx->audioCodecCtx = newCodecCtx;
        av_channel_layout_uninit(&ctx->outChLayout);
        ctx->outChLayout = targetLayout;
        ctx->audioStreamIdx = targetStreamIdx;
        ctx->audioTimeBase = ast->time_base;
        ctx->outSampleRate = newSampleRate;
        ctx->outChannels = newChannels;
    }

    // Flush audio queue so audio thread reconfigures resampler & sink smoothly without restarting demuxer
    ctx->audioQueue.clear();
    ctx->audioQueue.pushFlush();
    ctx->triggerAudioRampIn(60);

    LOGI("Successfully switched to audio track %d (stream %d, %s, %d Hz) without seeking demuxer",
         targetTrackIndex, targetStreamIdx, aCodec->name, ctx->outSampleRate);
    return JNI_TRUE;
}

JNI_FUNC(jint, nativeGetVideoRotation, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->videoRotation : 0;
}

JNI_FUNC(jint, nativeGetSarNum, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->videoSarNum : 1;
}

JNI_FUNC(jint, nativeGetSarDen, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->videoSarDen : 1;
}

JNI_FUNC(jobjectArray, nativeGetDebugInfo, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return nullptr;

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray result = env->NewObjectArray(11, strClass, nullptr);
    if (!result) return nullptr;

    auto setStr = [&](int index, const std::string& val) {
        jstring js = env->NewStringUTF(val.c_str());
        env->SetObjectArrayElement(result, index, js);
        if (js) env->DeleteLocalRef(js);
    };

    setStr(0, ctx->videoCodecName);
    std::string resStr = (ctx->videoWidth > 0 && ctx->videoHeight > 0)
        ? (std::to_string(ctx->videoWidth) + "x" + std::to_string(ctx->videoHeight))
        : "";
    setStr(1, resStr);
    setStr(2, std::to_string(ctx->sourceFps));
    setStr(3, std::to_string(ctx->videoBitrate));
    setStr(4, ctx->audioCodecName);
    setStr(5, std::to_string(ctx->outSampleRate));
    setStr(6, std::to_string(ctx->outChannels));
    setStr(7, ctx->audioLanguage);
    setStr(8, std::to_string(ctx->audioBitrate));
    setStr(9, std::to_string(ctx->totalRenderedFrames.load()));
    setStr(10, std::to_string(ctx->totalDroppedFrames.load()));

    return result;
}

JNI_FUNC(void, nativeSetHardwareAcceleration, jlong handle, jboolean enabled) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->useHardware.store(enabled == JNI_TRUE);
    }
}

JNI_FUNC(void, nativeSetScrubbing, jlong handle, jboolean isScrubbing) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->isScrubbing.store(isScrubbing == JNI_TRUE);
        if (isScrubbing == JNI_TRUE) {
            ctx->audioQueue.clear();
            ctx->audioQueue.pushFlush();
        }
    }
}

JNI_FUNC(jboolean, nativeSelectSubtitleTrack, jlong handle, jint targetTrackIndex) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx || !ctx->fmtCtx) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->subtitleMutex);
    if (ctx->subtitleCodecCtx) {
        avcodec_free_context(&ctx->subtitleCodecCtx);
        ctx->subtitleCodecCtx = nullptr;
    }

    if (targetTrackIndex < 0) {
        ctx->selectedSubtitleStreamIdx = -1;
        LOGI("Disabled subtitles");
        return JNI_TRUE;
    }

    int subCount = 0;
    int targetStreamIdx = -1;
    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        if (ctx->fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            if (subCount == targetTrackIndex) {
                targetStreamIdx = static_cast<int>(i);
                break;
            }
            subCount++;
        }
    }

    if (targetStreamIdx < 0) {
        return JNI_FALSE;
    }

    ctx->selectedSubtitleStreamIdx = targetStreamIdx;
    AVStream* st = ctx->fmtCtx->streams[targetStreamIdx];
    AVCodecID cid = st->codecpar->codec_id;
    if (cid == AV_CODEC_ID_HDMV_PGS_SUBTITLE || cid == AV_CODEC_ID_DVD_SUBTITLE ||
        cid == AV_CODEC_ID_DVB_SUBTITLE || cid == AV_CODEC_ID_XSUB) {
        const AVCodec* sCodec = avcodec_find_decoder(cid);
        if (sCodec) {
            ctx->subtitleCodecCtx = avcodec_alloc_context3(sCodec);
            avcodec_parameters_to_context(ctx->subtitleCodecCtx, st->codecpar);
            avcodec_open2(ctx->subtitleCodecCtx, sCodec, nullptr);
            LOGI("Selected bitmap subtitle track %d (%s)", targetTrackIndex, sCodec->name);
        }
    } else {
        LOGI("Selected text subtitle track %d (stream %d)", targetTrackIndex, targetStreamIdx);
    }

    return JNI_TRUE;
}

JNI_FUNC(void, nativeSetForceSdr, jlong handle, jboolean forceSdr) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->forceSdr.store(forceSdr == JNI_TRUE);
        LOGI("Native Force SDR set to: %d", forceSdr == JNI_TRUE ? 1 : 0);
    }
}

JNI_FUNC(void, nativeSetAudioDelay, jlong handle, jlong delayMs) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->audioDelayUs.store(delayMs * 1000LL);
        LOGI("Native Audio Delay set to: %" PRId64 " ms", delayMs);
    }
}

JNI_FUNC(void, nativeSetEqualizer, jlong handle, jboolean enabled, jintArray gainsMbArr) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return;

    int gains[NativeEqualizer::BAND_COUNT] = {0};
    int count = 0;
    if (gainsMbArr) {
        jsize len = env->GetArrayLength(gainsMbArr);
        count = std::min<int>(static_cast<int>(len), NativeEqualizer::BAND_COUNT);
        jint* elements = env->GetIntArrayElements(gainsMbArr, nullptr);
        if (elements) {
            for (int i = 0; i < count; i++) {
                gains[i] = elements[i];
            }
            env->ReleaseIntArrayElements(gainsMbArr, elements, JNI_ABORT);
        }
    }
    ctx->nativeEqualizer.setGains(enabled == JNI_TRUE, gains, count);
}

JNI_FUNC(jint, nativeGetAudioSessionId, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->nativeAudioSink.getSessionId() : 0;
}


