#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

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
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libavutil/imgutils.h>
#include <libavutil/channel_layout.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
}

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

// ─── Thread-Safe Packet Queue ────────────────────────────────────────────────

struct PacketQueue {
    struct Item {
        AVPacket* pkt = nullptr;
        bool isFlush = false;
        bool isEof = false;
    };

    std::deque<Item> items;
    std::mutex mtx;
    std::condition_variable notEmpty;
    std::condition_variable notFull;
    std::atomic<bool> aborted{false};
    size_t maxPackets = 60;

    void push(AVPacket* pkt) {
        std::unique_lock<std::mutex> lk(mtx);
        notFull.wait(lk, [&] {
            return aborted.load() || items.size() < maxPackets;
        });
        if (aborted.load()) {
            if (pkt) av_packet_free(&pkt);
            return;
        }
        items.push_back({pkt, false, false});
        notEmpty.notify_one();
    }

    void pushFlush() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, true, false});
        notEmpty.notify_one();
    }

    void pushEof() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, false, true});
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
        notFull.notify_one();
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lk(mtx);
        for (auto& it : items) {
            if (it.pkt) av_packet_free(&it.pkt);
        }
        items.clear();
        notFull.notify_all();
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

    jmethodID midOnAudioInit = nullptr;
    jmethodID midOnAudioData = nullptr;
    jmethodID midGetAudioLatencyUs = nullptr;
    jmethodID midOnAudioFlush = nullptr;
    jmethodID midOnVideoSizeChanged = nullptr;
    jmethodID midOnStateChanged = nullptr;
    jmethodID midOnError = nullptr;
    jmethodID midOnPositionUpdate = nullptr;
    jmethodID midOnSubtitleHeader = nullptr;
    jmethodID midOnSubtitleData = nullptr;
    jmethodID midOnFontAttachment = nullptr;

    // IO / Demuxer
    JniFile* jniFile = nullptr;
    PlayerIOBridge ioBridge;
    AVIOContext* avioCtx = nullptr;
    uint8_t* avioBuf = nullptr;
    static constexpr int AVIO_BUF_SIZE = 1024 * 1024; // 1 MB buffer

    AVFormatContext* fmtCtx = nullptr;
    int64_t durationMs = 0;

    // Video Stream
    int videoStreamIdx = -1;
    AVCodecContext* videoCodecCtx = nullptr;
    AVRational videoTimeBase{1, 1000};
    int videoWidth = 0;
    int videoHeight = 0;
    SwsContext* swsCtx = nullptr;
    int lastWindowWidth = -1;
    int lastWindowHeight = -1;

    // Audio Stream
    int audioStreamIdx = -1;
    AVCodecContext* audioCodecCtx = nullptr;
    AVRational audioTimeBase{1, 1000};
    SwrContext* swrCtx = nullptr;
    int outSampleRate = 48000;
    int outChannels = 2;
    AVChannelLayout outChLayout{};

    // Subtitle Streams
    std::vector<int> subtitleStreamIndices;

    // Surface / Window
    std::mutex windowMutex;
    ANativeWindow* nativeWindow = nullptr;

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
    std::atomic<int64_t> seekTargetMs{-1};
    std::atomic<float> playbackSpeed{1.0f};
    std::atomic<int64_t> currentPositionMs{0};

    // Stream termination & EOF tracking
    std::atomic<bool> demuxEof{false};
    std::atomic<bool> videoFinished{false};
    std::atomic<bool> audioFinished{false};
    std::atomic<bool> endNotified{false};

    // AV Clock (Master clock = Audio, or Monotonic if no audio)
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
        midOnVideoSizeChanged = env->GetMethodID(kotlinPlayerClass, "onVideoSizeChanged", "(II)V");
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
        midOnFontAttachment = env->GetMethodID(kotlinPlayerClass, "onFontAttachment", "(Ljava/lang/String;[B)V");
        if (env->ExceptionCheck()) env->ExceptionClear();

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
        if (isPaused.load()) {
            return masterAudioPtsUs.load();
        }
        auto now = std::chrono::steady_clock::now();
        int64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - masterAudioWallTime).count();
        float speed = playbackSpeed.load();
        if (speed <= 0.0f) speed = 1.0f;
        return masterAudioPtsUs.load() + static_cast<int64_t>(elapsedUs * speed);
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

    void notifyVideoSize(JNIEnv* env, int w, int h) {
        if (kotlinPlayerRef && midOnVideoSizeChanged) {
            env->CallVoidMethod(kotlinPlayerRef, midOnVideoSizeChanged, w, h);
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
    }

    void stopPlayback() {
        isRunning.store(false);
        isStopped.store(true);
        demuxEof.store(false);
        videoFinished.store(false);
        audioFinished.store(false);
        endNotified.store(false);
        ioBridge.abortRequested.store(true);
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
        if (audioCodecCtx) {
            avcodec_free_context(&audioCodecCtx);
            audioCodecCtx = nullptr;
        }
        if (swrCtx) {
            swr_free(&swrCtx);
            swrCtx = nullptr;
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
        videoStreamIdx = -1;
        audioStreamIdx = -1;
        subtitleStreamIndices.clear();
        lastWindowWidth = -1;
        lastWindowHeight = -1;
        demuxEof.store(false);
        videoFinished.store(false);
        audioFinished.store(false);
        endNotified.store(false);
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

    auto renderFrame = [&](AVFrame* f, bool isSeekFrame) {
        if (f->width > 0 && f->height > 0 && (f->width != ctx->videoWidth || f->height != ctx->videoHeight)) {
            ctx->videoWidth = f->width;
            ctx->videoHeight = f->height;
            ctx->notifyVideoSize(env, f->width, f->height);
        }

        int64_t ptsUs = (f->best_effort_timestamp != AV_NOPTS_VALUE)
            ? av_rescale_q(f->best_effort_timestamp, ctx->videoTimeBase, AV_TIME_BASE_Q)
            : (f->pts != AV_NOPTS_VALUE
                ? av_rescale_q(f->pts, ctx->videoTimeBase, AV_TIME_BASE_Q)
                : ctx->getMasterClockUs());

        ctx->currentPositionMs.store(ptsUs / 1000);

        // ── A/V Sync (skip waiting if rendering the single frame after a seek while paused) ──
        if (!isSeekFrame) {
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

                if (ctx->audioStreamIdx >= 0) {
                    // Desync recovery: if drift > 500ms, resync master clock to video PTS
                    if (diffUs < -500000 || diffUs > 5000000) {
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
                if (waitUs > 15000) waitUs = 15000;

                std::unique_lock<std::mutex> lk(ctx->controlMutex);
                ctx->controlCv.wait_for(lk, std::chrono::microseconds(waitUs), [&] {
                    return ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                           ctx->seekTargetMs.load() >= 0;
                });
            }
        }

        if (!ctx->isRunning.load() || ctx->isStopped.load() || ctx->seekTargetMs.load() >= 0) {
            return;
        }

        // ── Render ───────────────────────────────────────────────────
        std::lock_guard<std::mutex> lock(ctx->windowMutex);
        if (ctx->nativeWindow && f->width > 0 && f->height > 0) {
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
                ANativeWindow_unlockAndPost(ctx->nativeWindow);
            }
        }
    };

    while (ctx->isRunning.load() && !ctx->isStopped.load()) {
        if (ctx->isPaused.load() && !needSeekFrame && ctx->seekTargetMs.load() < 0) {
            std::unique_lock<std::mutex> lk(ctx->controlMutex);
            ctx->controlCv.wait(lk, [&] {
                return !ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                       ctx->seekTargetMs.load() >= 0;
            });
            if (!ctx->isRunning.load() || ctx->isStopped.load()) break;
        }

        if (!ctx->videoQueue.pop(item, 50)) continue;

        if (item.isFlush) {
            if (ctx->videoCodecCtx) avcodec_flush_buffers(ctx->videoCodecCtx);
            av_frame_unref(vFrame);
            ctx->videoFinished.store(false);
            needSeekFrame = true;
            continue;
        }

        if (item.isEof) {
            LOGI("videoDecodeThread received EOF, draining decoder");
            if (ctx->videoCodecCtx) {
                avcodec_send_packet(ctx->videoCodecCtx, nullptr);
                while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
                    renderFrame(vFrame, needSeekFrame);
                    needSeekFrame = false;
                    av_frame_unref(vFrame);
                }
            }
            ctx->videoFinished.store(true);
            ctx->checkPlaybackFinished(env);
            continue;
        }

        if (!ctx->videoCodecCtx || !item.pkt) {
            av_packet_free(&item.pkt);
            continue;
        }

        int sendRet = avcodec_send_packet(ctx->videoCodecCtx, item.pkt);
        if (sendRet == AVERROR(EAGAIN)) {
            while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
                renderFrame(vFrame, needSeekFrame);
                needSeekFrame = false;
                av_frame_unref(vFrame);
            }
            sendRet = avcodec_send_packet(ctx->videoCodecCtx, item.pkt);
        }
        av_packet_free(&item.pkt);

        while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
            renderFrame(vFrame, needSeekFrame);
            needSeekFrame = false;
            av_frame_unref(vFrame);
        }
    }

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
    PacketQueue::Item item{};
    uint8_t* audioOutBuf = nullptr;
    int audioOutBufSize = 0;
    jbyteArray jAudioByteArray = nullptr;
    int jAudioByteCap = 0;

    auto processAudioFrame = [&](AVFrame* f) {
        int64_t ptsUs = (f->best_effort_timestamp != AV_NOPTS_VALUE)
            ? av_rescale_q(f->best_effort_timestamp, ctx->audioTimeBase, AV_TIME_BASE_Q)
            : (f->pts != AV_NOPTS_VALUE
                ? av_rescale_q(f->pts, ctx->audioTimeBase, AV_TIME_BASE_Q)
                : ctx->getMasterClockUs());

        if (ctx->videoStreamIdx < 0) {
            ctx->currentPositionMs.store(ptsUs / 1000);
        }

        if (ctx->swrCtx) {
            int outSamples = swr_get_out_samples(ctx->swrCtx, f->nb_samples);
            int reqSize = outSamples * ctx->outChannels * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
            if (reqSize > audioOutBufSize) {
                av_freep(&audioOutBuf);
                audioOutBuf = static_cast<uint8_t*>(av_malloc(reqSize + 1024));
                audioOutBufSize = reqSize + 1024;
            }
            uint8_t* outPtr = audioOutBuf;
            int conv = swr_convert(ctx->swrCtx, &outPtr, outSamples,
                                   const_cast<const uint8_t**>(f->data),
                                   f->nb_samples);
            if (conv > 0) {
                int pcmBytes = conv * ctx->outChannels * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
                if (!jAudioByteArray || jAudioByteCap < pcmBytes) {
                    if (jAudioByteArray) env->DeleteLocalRef(jAudioByteArray);
                    jAudioByteArray = env->NewByteArray(pcmBytes);
                    jAudioByteCap = pcmBytes;
                }
                if (jAudioByteArray) {
                    env->SetByteArrayRegion(jAudioByteArray, 0, pcmBytes,
                                            reinterpret_cast<jbyte*>(audioOutBuf));
                    env->CallIntMethod(ctx->kotlinPlayerRef, ctx->midOnAudioData,
                                       jAudioByteArray, pcmBytes);
                    if (env->ExceptionCheck()) env->ExceptionClear();

                    int64_t latencyUs = 0;
                    if (ctx->midGetAudioLatencyUs) {
                        latencyUs = env->CallLongMethod(ctx->kotlinPlayerRef, ctx->midGetAudioLatencyUs);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                    int64_t acousticPtsUs = ptsUs - latencyUs;
                    if (acousticPtsUs < 0) acousticPtsUs = 0;
                    ctx->setMasterClockUs(acousticPtsUs);
                }
            }
        } else {
            ctx->setMasterClockUs(ptsUs);
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
            if (ctx->audioCodecCtx) avcodec_flush_buffers(ctx->audioCodecCtx);
            av_frame_unref(aFrame);
            ctx->audioFinished.store(false);
            continue;
        }

        if (item.isEof) {
            LOGI("audioDecodeThread received EOF, draining decoder");
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

        if (!ctx->audioCodecCtx || !item.pkt) {
            av_packet_free(&item.pkt);
            continue;
        }

        int sendRet = avcodec_send_packet(ctx->audioCodecCtx, item.pkt);
        av_packet_free(&item.pkt);
        if (sendRet < 0 && sendRet != AVERROR(EAGAIN)) continue;

        while (avcodec_receive_frame(ctx->audioCodecCtx, aFrame) == 0) {
            processAudioFrame(aFrame);
            av_frame_unref(aFrame);
        }
    }

    if (jAudioByteArray) env->DeleteLocalRef(jAudioByteArray);
    av_freep(&audioOutBuf);
    av_frame_free(&aFrame);

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

    ctx->notifyState(env, STATE_READY);
    ctx->setMasterClockUs(0);
    if (initialSeekMs > 0) {
        ctx->seekTargetMs.store(initialSeekMs);
    } else {
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
            LOGI("Seeking to %" PRId64 " ms", target);
            ctx->demuxEof.store(false);
            ctx->videoFinished.store(false);
            ctx->audioFinished.store(false);
            ctx->endNotified.store(false);
            ctx->videoQueue.clear();
            ctx->videoQueue.pushFlush();
            ctx->audioQueue.clear();
            ctx->audioQueue.pushFlush();

            int streamIdx = ctx->videoStreamIdx >= 0 ? ctx->videoStreamIdx : ctx->audioStreamIdx;
            AVRational tb = ctx->videoStreamIdx >= 0 ? ctx->videoTimeBase : ctx->audioTimeBase;
            int64_t seekTs = av_rescale_q(target * 1000, AV_TIME_BASE_Q, tb);
            av_seek_frame(ctx->fmtCtx, streamIdx, seekTs, AVSEEK_FLAG_BACKWARD);
            if (ctx->fmtCtx->pb) {
                ctx->fmtCtx->pb->eof_reached = 0;
                ctx->fmtCtx->pb->error = 0;
            }

            ctx->setMasterClockUs(target * 1000);
            ctx->currentPositionMs.store(target);
            ctx->notifyAudioFlush(env);
            ctx->notifyPosition(env, target, ctx->durationMs);
            ctx->controlCv.notify_all();
        }

        if (ctx->isPaused.load()) {
            std::unique_lock<std::mutex> lk(ctx->controlMutex);
            if (ctx->videoQueue.size() >= 15 || ctx->audioQueue.size() >= 15) {
                ctx->controlCv.wait(lk, [&] {
                    return !ctx->isPaused.load() || !ctx->isRunning.load() || ctx->isStopped.load() ||
                           ctx->seekTargetMs.load() >= 0;
                });
                if (!ctx->isRunning.load() || ctx->isStopped.load()) break;
                if (ctx->seekTargetMs.load() >= 0) continue;
            }
        }

        int ret = av_read_frame(ctx->fmtCtx, pkt);
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
            } else if (ret == AVERROR_EXIT) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        if (pkt->stream_index == ctx->videoStreamIdx && ctx->videoCodecCtx) {
            AVPacket* vpkt = av_packet_alloc();
            if (vpkt && av_packet_ref(vpkt, pkt) == 0) {
                ctx->videoQueue.push(vpkt);
            } else {
                av_packet_free(&vpkt);
            }
        } else if (pkt->stream_index == ctx->audioStreamIdx && ctx->audioCodecCtx) {
            AVPacket* apkt = av_packet_alloc();
            if (apkt && av_packet_ref(apkt, pkt) == 0) {
                ctx->audioQueue.push(apkt);
            } else {
                av_packet_free(&apkt);
            }
        } else {
            // Forward subtitle packets to Kotlin
            for (int subIdx : ctx->subtitleStreamIndices) {
                if (pkt->stream_index == subIdx && ctx->midOnSubtitleData && pkt->data && pkt->size > 0) {
                    AVRational tb = ctx->fmtCtx->streams[subIdx]->time_base;
                    int64_t ptsUs = (pkt->pts != AV_NOPTS_VALUE)
                        ? av_rescale_q(pkt->pts, tb, AV_TIME_BASE_Q)
                        : ((pkt->dts != AV_NOPTS_VALUE)
                            ? av_rescale_q(pkt->dts, tb, AV_TIME_BASE_Q)
                            : 0);
                    /* Convert packet duration to microseconds so Kotlin/AssHandler can use it
                     * directly instead of trying to parse it from the raw MKV block body. */
                    int64_t durUs = (pkt->duration > 0)
                        ? av_rescale_q(pkt->duration, tb, AV_TIME_BASE_Q)
                        : 3000000LL; /* 3 s fallback for packets without duration */
                    jbyteArray jSubData = env->NewByteArray(pkt->size);
                    if (jSubData) {
                        env->SetByteArrayRegion(jSubData, 0, pkt->size, reinterpret_cast<jbyte*>(pkt->data));
                        env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnSubtitleData,
                                            static_cast<jint>(subIdx), static_cast<jlong>(ptsUs),
                                            static_cast<jlong>(durUs), jSubData);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        env->DeleteLocalRef(jSubData);
                    }
                    break;
                }
            }
        }
        av_packet_unref(pkt);

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPosNotify).count() >= 200) {
            lastPosNotify = now;
            ctx->notifyPosition(env, ctx->currentPositionMs.load(), ctx->durationMs);
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

JNI_FUNC(jboolean, nativeOpen, jlong handle, jobject bridgeObj, jstring urlStr, jobject surfaceObj, jlong startPositionMs) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return JNI_FALSE;

    ctx->closeMedia();

    const char* urlChars = urlStr ? env->GetStringUTFChars(urlStr, nullptr) : nullptr;
    std::string url = urlChars ? urlChars : "";
    if (urlChars) env->ReleaseStringUTFChars(urlStr, urlChars);

    ctx->fmtCtx = avformat_alloc_context();
    if (!ctx->fmtCtx) {
        LOGE("Failed to allocate format context");
        return JNI_FALSE;
    }

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
    av_dict_set(&opts, "analyzeduration", "5000000", 0);
    av_dict_set(&opts, "probesize", "5242880", 0);
    av_dict_set(&opts, "genpts", "1", 0);
    av_dict_set(&opts, "fflags", "+genpts+discardcorrupt+fastseek", 0);

    const char* openPath = bridgeObj ? "" : url.c_str();
    int openRet = avformat_open_input(&ctx->fmtCtx, openPath, nullptr, &opts);
    av_dict_free(&opts);

    if (openRet < 0) {
        LOGE("avformat_open_input failed with code %d", openRet);
        ctx->closeMedia();
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(ctx->fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        ctx->closeMedia();
        return JNI_FALSE;
    }

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
            if (ctx->midOnSubtitleHeader) {
                int headerSize = (codecpar->extradata && codecpar->extradata_size > 0) ? codecpar->extradata_size : 0;
                jbyteArray jHeader = env->NewByteArray(headerSize);
                if (jHeader) {
                    if (headerSize > 0 && codecpar->extradata) {
                        env->SetByteArrayRegion(jHeader, 0, headerSize,
                                                reinterpret_cast<jbyte*>(codecpar->extradata));
                    }
                    AVDictionaryEntry* titleEntry = av_dict_get(st->metadata, "title", nullptr, 0);
                    if (!titleEntry) titleEntry = av_dict_get(st->metadata, "language", nullptr, 0);
                    jstring jTitle = env->NewStringUTF(titleEntry ? titleEntry->value : "Subtitle");
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
            if (hwThreads < 1) hwThreads = 1;
            ctx->videoCodecCtx->thread_count = static_cast<int>(hwThreads);
            ctx->videoCodecCtx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
            ctx->videoCodecCtx->flags |= AV_CODEC_FLAG_OUTPUT_CORRUPT;
            ctx->videoCodecCtx->flags2 |= AV_CODEC_FLAG2_FAST;
            if (avcodec_open2(ctx->videoCodecCtx, vCodec, nullptr) == 0) {
                ctx->videoWidth = ctx->videoCodecCtx->width;
                ctx->videoHeight = ctx->videoCodecCtx->height;
                if (ctx->videoWidth > 0 && ctx->videoHeight > 0) {
                    ctx->notifyVideoSize(env, ctx->videoWidth, ctx->videoHeight);
                }
                LOGI("Video decoder initialized: %s (%dx%d, threads=%d)",
                     vCodec->name, ctx->videoWidth, ctx->videoHeight, ctx->videoCodecCtx->thread_count);
            } else {
                LOGE("avcodec_open2 failed for video decoder %s", vCodec->name);
                ctx->notifyError(env, "Failed to initialize video decoder");
            }
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
                ctx->outChannels = 2;

                swr_alloc_set_opts2(
                    &ctx->swrCtx,
                    &ctx->outChLayout,
                    AV_SAMPLE_FMT_S16,
                    ctx->outSampleRate,
                    &ctx->audioCodecCtx->ch_layout,
                    ctx->audioCodecCtx->sample_fmt,
                    ctx->audioCodecCtx->sample_rate,
                    0, nullptr);

                if (ctx->swrCtx && swr_init(ctx->swrCtx) >= 0) {
                    if (ctx->midOnAudioInit) {
                        env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnAudioInit, ctx->outSampleRate, ctx->outChannels);
                    }
                    LOGI("Audio decoder initialized: %s (sampleRate=%d, ch=%d)", aCodec->name, ctx->outSampleRate, ctx->outChannels);
                }
            }
        }
    }

    ctx->isRunning.store(true);
    ctx->isStopped.store(false);
    ctx->isPaused.store(false);

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
        ctx->resumeClock();
        ctx->controlCv.notify_all();
    }
}

JNI_FUNC(void, nativePause, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->pauseClock();
        ctx->controlCv.notify_all();
    }
}

JNI_FUNC(void, nativeSeek, jlong handle, jlong posMs) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->seekTargetMs.store(posMs);
        ctx->controlCv.notify_all();
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
    return ctx ? ctx->currentPositionMs.load() : 0;
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

