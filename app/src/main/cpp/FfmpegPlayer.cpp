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
#include <string>
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
    jobject const bridge_ = nullptr;
    int64_t pos_ = 0;
    mutable int64_t cachedSize_ = -1;
    jmethodID readAtMid_ = nullptr;
    jmethodID getSizeMid_ = nullptr;
    bool ok_ = true;

public:
    JniFile(JNIEnv* env, jobject bridge)
        : bridge_(env ? env->NewGlobalRef(bridge) : nullptr) {
        if (!env || !bridge) {
            ok_ = false;
            return;
        }
        jclass cls = env->GetObjectClass(bridge);
        if (env->ExceptionCheck()) { env->ExceptionClear(); ok_ = false; }
        if (cls) {
            readAtMid_ = env->GetMethodID(cls, "readAt", "(J[BI)I");
            if (env->ExceptionCheck()) { env->ExceptionClear(); ok_ = false; }
            getSizeMid_ = env->GetMethodID(cls, "getSize", "()J");
            if (env->ExceptionCheck()) { env->ExceptionClear(); ok_ = false; }
            env->DeleteLocalRef(cls);
        } else {
            ok_ = false;
        }
    }

    ~JniFile() override {
        if (bridge_) {
            JNIEnv* env = getJniEnv();
            if (env) {
                env->DeleteGlobalRef(bridge_);
            }
        }
    }

    int64_t read(uint8_t* buf, int64_t size) override {
        if (!ok_ || !bridge_) return -1;
        JNIEnv* env = getJniEnv();
        if (!env) return -1;

        jsize requiredSize = static_cast<jsize>(size);
        jbyteArray localBuf = env->NewByteArray(requiredSize);
        if (!localBuf) return -1;

        jint n = env->CallIntMethod(bridge_, readAtMid_, pos_, localBuf, requiredSize);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(localBuf);
            return -1;
        }

        if (n > 0) {
            env->GetByteArrayRegion(localBuf, 0, n, reinterpret_cast<jbyte*>(buf));
            pos_ += n;
        }
        env->DeleteLocalRef(localBuf);

        return n < 0 ? 0 : static_cast<int64_t>(n);
    }

    int64_t seek(int64_t offset, int whence) override {
        switch (whence) {
            case SEEK_SET: pos_ = offset; break;
            case SEEK_CUR: pos_ += offset; break;
            case SEEK_END: pos_ = size() + offset; break;
            default:       return -1;
        }
        return pos_;
    }

    int64_t size() override {
        if (!ok_ || !bridge_) return 0;
        if (cachedSize_ < 0) {
            JNIEnv* env = getJniEnv();
            if (!env) return 0;
            cachedSize_ = env->CallLongMethod(bridge_, getSizeMid_);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                cachedSize_ = 0;
            }
        }
        return cachedSize_;
    }

    bool ok() const override { return ok_ && bridge_ != nullptr; }
};

struct PlayerIOBridge {
    RandomAccessFile* file = nullptr;
    std::atomic<bool> abortRequested{false};
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
    jmethodID midOnAudioFlush = nullptr;
    jmethodID midOnVideoSizeChanged = nullptr;
    jmethodID midOnStateChanged = nullptr;
    jmethodID midOnError = nullptr;
    jmethodID midOnPositionUpdate = nullptr;

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

    // Audio Stream
    int audioStreamIdx = -1;
    AVCodecContext* audioCodecCtx = nullptr;
    AVRational audioTimeBase{1, 1000};
    SwrContext* swrCtx = nullptr;
    int outSampleRate = 48000;
    int outChannels = 2;
    AVChannelLayout outChLayout{};

    // Surface / Window
    std::mutex windowMutex;
    ANativeWindow* nativeWindow = nullptr;

    // Threads & Control
    std::thread playbackThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> isPaused{true};
    std::atomic<bool> isStopped{false};
    std::atomic<int64_t> seekTargetMs{-1};
    std::atomic<float> playbackSpeed{1.0f};
    std::atomic<int64_t> currentPositionMs{0};

    // AV Clock (Master clock = Audio, or Monotonic if no audio)
    std::atomic<int64_t> masterAudioPtsUs{0};
    std::chrono::steady_clock::time_point masterAudioWallTime{};
    std::mutex clockMutex;

    std::mutex controlMutex;
    std::condition_variable controlCv;

    FfmpegPlayerContext(JNIEnv* env, jobject obj) {
        kotlinPlayerRef = env->NewGlobalRef(obj);
        jclass cls = env->GetObjectClass(obj);
        kotlinPlayerClass = static_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);

        midOnAudioInit = env->GetMethodID(kotlinPlayerClass, "onAudioInit", "(II)V");
        midOnAudioData = env->GetMethodID(kotlinPlayerClass, "onAudioData", "([BI)I");
        midOnAudioFlush = env->GetMethodID(kotlinPlayerClass, "onAudioFlush", "()V");
        midOnVideoSizeChanged = env->GetMethodID(kotlinPlayerClass, "onVideoSizeChanged", "(II)V");
        midOnStateChanged = env->GetMethodID(kotlinPlayerClass, "onStateChanged", "(I)V");
        midOnError = env->GetMethodID(kotlinPlayerClass, "onError", "(Ljava/lang/String;)V");
        midOnPositionUpdate = env->GetMethodID(kotlinPlayerClass, "onPositionUpdate", "(JJ)V");

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
        if (audioStreamIdx >= 0) {
            auto now = std::chrono::steady_clock::now();
            int64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - masterAudioWallTime).count();
            float speed = playbackSpeed.load();
            if (speed <= 0.0f) speed = 1.0f;
            return masterAudioPtsUs.load() + static_cast<int64_t>(elapsedUs * speed);
        } else {
            auto now = std::chrono::steady_clock::now();
            int64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - masterAudioWallTime).count();
            float speed = playbackSpeed.load();
            if (speed <= 0.0f) speed = 1.0f;
            return masterAudioPtsUs.load() + static_cast<int64_t>(elapsedUs * speed);
        }
    }

    void setMasterClockUs(int64_t ptsUs) {
        std::lock_guard<std::mutex> lock(clockMutex);
        masterAudioPtsUs.store(ptsUs);
        masterAudioWallTime = std::chrono::steady_clock::now();
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
        if (surface) {
            nativeWindow = ANativeWindow_fromSurface(env, surface);
            LOGD("setSurface: acquired ANativeWindow %p", nativeWindow);
        }
    }

    void stopPlayback() {
        isRunning.store(false);
        isStopped.store(true);
        ioBridge.abortRequested.store(true);
        controlCv.notify_all();
        if (playbackThread.joinable()) {
            playbackThread.join();
        }
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
    }
};

// ─── Playback Thread Function ────────────────────────────────────────────────

static void playbackThreadFunc(FfmpegPlayerContext* ctx, int64_t initialSeekMs) {
    LOGI("playbackThreadFunc started");
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach playback thread to JVM");
        return;
    }

    ctx->notifyState(env, STATE_READY);
    if (initialSeekMs > 0) {
        ctx->seekTargetMs.store(initialSeekMs);
    }

    AVPacket* pkt = av_packet_alloc();
    AVFrame* vFrame = av_frame_alloc();
    AVFrame* aFrame = av_frame_alloc();

    uint8_t* audioOutBuf = nullptr;
    int audioOutBufSize = 0;
    jbyteArray jAudioByteArray = nullptr;
    int jAudioByteCap = 0;

    auto lastPosNotifyTime = std::chrono::steady_clock::now();

    while (ctx->isRunning.load() && !ctx->isStopped.load()) {
        if (ctx->isPaused.load()) {
            std::unique_lock<std::mutex> lock(ctx->controlMutex);
            ctx->controlCv.wait(lock, [&]() {
                return !ctx->isPaused.load() || !ctx->isRunning.load() || ctx->seekTargetMs.load() >= 0;
            });
            if (!ctx->isRunning.load()) break;
        }

        int64_t targetSeek = ctx->seekTargetMs.exchange(-1);
        if (targetSeek >= 0) {
            LOGI("Executing instant seek to %" PRId64 " ms", targetSeek);
            int64_t seekTs = av_rescale_q(targetSeek * 1000, AV_TIME_BASE_Q,
                                          ctx->videoStreamIdx >= 0 ? ctx->videoTimeBase : ctx->audioTimeBase);
            int streamIdx = ctx->videoStreamIdx >= 0 ? ctx->videoStreamIdx : ctx->audioStreamIdx;
            av_seek_frame(ctx->fmtCtx, streamIdx, seekTs, AVSEEK_FLAG_BACKWARD);

            if (ctx->videoCodecCtx) avcodec_flush_buffers(ctx->videoCodecCtx);
            if (ctx->audioCodecCtx) avcodec_flush_buffers(ctx->audioCodecCtx);

            ctx->notifyAudioFlush(env);
            ctx->setMasterClockUs(targetSeek * 1000);
            ctx->currentPositionMs.store(targetSeek);
            ctx->notifyPosition(env, targetSeek, ctx->durationMs);
        }

        int ret = av_read_frame(ctx->fmtCtx, pkt);
        if (ret < 0) {
            if (ret == AVERROR_EOF || avio_feof(ctx->fmtCtx->pb)) {
                LOGI("Demuxer reached EOF");
                ctx->notifyState(env, STATE_ENDED);
                ctx->isPaused.store(true);
                continue;
            } else if (ret == AVERROR_EXIT) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        // ── Process Video Packet ──
        if (pkt->stream_index == ctx->videoStreamIdx && ctx->videoCodecCtx) {
            int sendRet = avcodec_send_packet(ctx->videoCodecCtx, pkt);
            if (sendRet == 0 || sendRet == AVERROR(EAGAIN)) {
                while (avcodec_receive_frame(ctx->videoCodecCtx, vFrame) == 0) {
                    int64_t ptsUs = (vFrame->best_effort_timestamp != AV_NOPTS_VALUE)
                        ? av_rescale_q(vFrame->best_effort_timestamp, ctx->videoTimeBase, AV_TIME_BASE_Q)
                        : (vFrame->pts != AV_NOPTS_VALUE
                            ? av_rescale_q(vFrame->pts, ctx->videoTimeBase, AV_TIME_BASE_Q)
                            : ctx->getMasterClockUs());

                    int64_t curPosMs = ptsUs / 1000;
                    ctx->currentPositionMs.store(curPosMs);

                    int64_t clockUs = ctx->getMasterClockUs();
                    int64_t diffUs = ptsUs - clockUs;
                    float speed = ctx->playbackSpeed.load();

                    if (diffUs > 4000) {
                        int64_t waitUs = static_cast<int64_t>(diffUs / (speed > 0 ? speed : 1.0f));
                        if (waitUs > 500000) waitUs = 500000;
                        std::this_thread::sleep_for(std::chrono::microseconds(waitUs));
                    }

                    if (ctx->audioStreamIdx < 0) {
                        ctx->setMasterClockUs(ptsUs);
                    }

                    // Render Frame to Surface
                    {
                        std::lock_guard<std::mutex> lock(ctx->windowMutex);
                        if (ctx->nativeWindow && vFrame->width > 0 && vFrame->height > 0) {
                            ANativeWindow_setBuffersGeometry(
                                ctx->nativeWindow, vFrame->width, vFrame->height, WINDOW_FORMAT_RGBA_8888);
                            ANativeWindow_Buffer windowBuffer;
                            if (ANativeWindow_lock(ctx->nativeWindow, &windowBuffer, nullptr) == 0) {
                                ctx->swsCtx = sws_getCachedContext(
                                    ctx->swsCtx, vFrame->width, vFrame->height,
                                    static_cast<AVPixelFormat>(vFrame->format),
                                    windowBuffer.width, windowBuffer.height,
                                    AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);

                                if (ctx->swsCtx) {
                                    uint8_t* dstPlanes[4] = {static_cast<uint8_t*>(windowBuffer.bits), nullptr, nullptr, nullptr};
                                    int dstStrides[4] = {windowBuffer.stride * 4, 0, 0, 0};
                                    sws_scale(ctx->swsCtx, vFrame->data, vFrame->linesize, 0, vFrame->height, dstPlanes, dstStrides);
                                }
                                ANativeWindow_unlockAndPost(ctx->nativeWindow);
                            }
                        }
                    }
                    av_frame_unref(vFrame);
                }
            }
        }
        // ── Process Audio Packet ──
        else if (pkt->stream_index == ctx->audioStreamIdx && ctx->audioCodecCtx) {
            int sendRet = avcodec_send_packet(ctx->audioCodecCtx, pkt);
            if (sendRet == 0 || sendRet == AVERROR(EAGAIN)) {
                while (avcodec_receive_frame(ctx->audioCodecCtx, aFrame) == 0) {
                    int64_t ptsUs = (aFrame->best_effort_timestamp != AV_NOPTS_VALUE)
                        ? av_rescale_q(aFrame->best_effort_timestamp, ctx->audioTimeBase, AV_TIME_BASE_Q)
                        : (aFrame->pts != AV_NOPTS_VALUE
                            ? av_rescale_q(aFrame->pts, ctx->audioTimeBase, AV_TIME_BASE_Q)
                            : ctx->getMasterClockUs());

                    ctx->setMasterClockUs(ptsUs);

                    if (ctx->videoStreamIdx < 0) {
                        ctx->currentPositionMs.store(ptsUs / 1000);
                    }

                    if (ctx->swrCtx) {
                        int outSamples = swr_get_out_samples(ctx->swrCtx, aFrame->nb_samples);
                        int requiredSize = outSamples * ctx->outChannels * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);

                        if (requiredSize > audioOutBufSize) {
                            av_freep(&audioOutBuf);
                            audioOutBuf = static_cast<uint8_t*>(av_malloc(requiredSize + 1024));
                            audioOutBufSize = requiredSize + 1024;
                        }

                        uint8_t* outPtr = audioOutBuf;
                        int convertedSamples = swr_convert(
                            ctx->swrCtx, &outPtr, outSamples,
                            const_cast<const uint8_t**>(aFrame->data), aFrame->nb_samples);

                        if (convertedSamples > 0) {
                            int pcmBytes = convertedSamples * ctx->outChannels * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
                            if (!jAudioByteArray || jAudioByteCap < pcmBytes) {
                                if (jAudioByteArray) env->DeleteLocalRef(jAudioByteArray);
                                jAudioByteArray = env->NewByteArray(pcmBytes);
                                jAudioByteCap = pcmBytes;
                            }
                            if (jAudioByteArray) {
                                env->SetByteArrayRegion(jAudioByteArray, 0, pcmBytes, reinterpret_cast<jbyte*>(audioOutBuf));
                                env->CallIntMethod(ctx->kotlinPlayerRef, ctx->midOnAudioData, jAudioByteArray, pcmBytes);
                                if (env->ExceptionCheck()) env->ExceptionClear();
                            }
                        }
                    }
                    av_frame_unref(aFrame);
                }
            }
        }

        av_packet_unref(pkt);

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPosNotifyTime).count() >= 200) {
            lastPosNotifyTime = now;
            ctx->notifyPosition(env, ctx->currentPositionMs.load(), ctx->durationMs);
        }
    }

    if (jAudioByteArray) {
        env->DeleteLocalRef(jAudioByteArray);
    }
    if (audioOutBuf) {
        av_freep(&audioOutBuf);
    }

    av_frame_free(&vFrame);
    av_frame_free(&aFrame);
    av_packet_free(&pkt);

    g_jvm->DetachCurrentThread();
    LOGI("playbackThreadFunc finished");
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
    av_dict_set(&opts, "buffer_size", "1048576", 0);
    av_dict_set(&opts, "analyzeduration", "2000000", 0);
    av_dict_set(&opts, "probesize", "2097152", 0);

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

    // Find streams
    ctx->videoStreamIdx = -1;
    ctx->audioStreamIdx = -1;

    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        AVCodecParameters* codecpar = ctx->fmtCtx->streams[i]->codecpar;
        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO && ctx->videoStreamIdx < 0) {
            ctx->videoStreamIdx = static_cast<int>(i);
        } else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO && ctx->audioStreamIdx < 0) {
            ctx->audioStreamIdx = static_cast<int>(i);
        }
    }

    // Init Video Decoder
    if (ctx->videoStreamIdx >= 0) {
        AVStream* vst = ctx->fmtCtx->streams[ctx->videoStreamIdx];
        ctx->videoTimeBase = vst->time_base;
        const AVCodec* vCodec = avcodec_find_decoder(vst->codecpar->codec_id);
        if (vCodec) {
            ctx->videoCodecCtx = avcodec_alloc_context3(vCodec);
            avcodec_parameters_to_context(ctx->videoCodecCtx, vst->codecpar);
            ctx->videoCodecCtx->thread_count = 0;
            if (avcodec_open2(ctx->videoCodecCtx, vCodec, nullptr) == 0) {
                ctx->videoWidth = ctx->videoCodecCtx->width;
                ctx->videoHeight = ctx->videoCodecCtx->height;
                ctx->notifyVideoSize(env, ctx->videoWidth, ctx->videoHeight);
                LOGI("Video decoder initialized: %s (%dx%d)", vCodec->name, ctx->videoWidth, ctx->videoHeight);
            }
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
            ctx->audioCodecCtx->thread_count = 0;
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

    if (surfaceObj) {
        ctx->setSurface(env, surfaceObj);
    }

    ctx->isRunning.store(true);
    ctx->isStopped.store(false);
    ctx->isPaused.store(false);

    ctx->playbackThread = std::thread(playbackThreadFunc, ctx, startPositionMs);
    return JNI_TRUE;
}

JNI_FUNC(void, nativeSetSurface, jlong handle, jobject surface) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) ctx->setSurface(env, surface);
}

JNI_FUNC(void, nativePlay, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->isPaused.store(false);
        ctx->controlCv.notify_all();
    }
}

JNI_FUNC(void, nativePause, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (ctx) {
        ctx->isPaused.store(true);
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
