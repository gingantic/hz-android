#pragma once
#include "PlayerCommon.h"
#include "JniFileIO.h"
#include "PacketQueue.h"
#include "NativeAudioSink.h"
#include "NativeEqualizer.h"

// ─── Player Context ─────────────────────────────────────────────────────────

struct FfmpegPlayerContext {
    jobject kotlinPlayerRef = nullptr;
    jclass kotlinPlayerClass = nullptr;

    NativeAudioSink nativeAudioSink;
    NativeEqualizer nativeEqualizer;

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

    // Serializes operations that own or traverse FFmpeg media structures. Ordinary
    // metadata queries use metadataSnapshot instead and never take this lock.
    std::recursive_mutex mediaOperationMutex;

    struct MetadataSnapshot {
        int64_t durationMs = 0;
        int videoWidth = 0;
        int videoHeight = 0;
        int videoRotation = 0;
        int videoSarNum = 1;
        int videoSarDen = 1;
        std::string videoCodecName;
        std::string audioCodecName;
        std::string audioLanguage;
        float sourceFps = 0.0f;
        int64_t videoBitrate = 0;
        int64_t audioBitrate = 0;
        int outSampleRate = 48000;
        int outChannels = 2;
        std::vector<std::string> audioTrackNames;
    };

    mutable std::mutex metadataMutex;
    MetadataSnapshot metadataSnapshot;
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
    std::atomic<bool> errorNotified{false};

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

    MetadataSnapshot getMetadataSnapshot() const {
        std::lock_guard<std::mutex> lock(metadataMutex);
        return metadataSnapshot;
    }

    int64_t getMetadataDurationMs() const {
        std::lock_guard<std::mutex> lock(metadataMutex);
        return metadataSnapshot.durationMs;
    }

    void resetMetadataSnapshot() {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot = MetadataSnapshot{};
    }

    // Called once after open completes and before decode threads start, so the
    // direct fields are stable while this complete snapshot is copied.
    void publishMetadataSnapshot(const std::vector<std::string>& audioTrackNames) {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot.durationMs = durationMs;
        metadataSnapshot.videoWidth = videoWidth;
        metadataSnapshot.videoHeight = videoHeight;
        metadataSnapshot.videoRotation = videoRotation;
        metadataSnapshot.videoSarNum = videoSarNum;
        metadataSnapshot.videoSarDen = videoSarDen;
        metadataSnapshot.videoCodecName = videoCodecName;
        metadataSnapshot.audioCodecName = audioCodecName;
        metadataSnapshot.audioLanguage = audioLanguage;
        metadataSnapshot.sourceFps = sourceFps;
        metadataSnapshot.videoBitrate = videoBitrate;
        metadataSnapshot.audioBitrate = audioBitrate;
        metadataSnapshot.outSampleRate = outSampleRate;
        metadataSnapshot.outChannels = outChannels;
        metadataSnapshot.audioTrackNames = audioTrackNames;
    }

    void updateVideoDimensions(int width, int height) {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot.videoWidth = width;
        metadataSnapshot.videoHeight = height;
    }

    void setVideoDimensions(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        updateVideoDimensions(width, height);
    }

    void updateVideoCodecName(const std::string& name) {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot.videoCodecName = name;
    }

    void setVideoCodecName(const std::string& name) {
        videoCodecName = name;
        updateVideoCodecName(name);
    }

    void updateAudioOutputFormat(int sampleRate, int channels) {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot.outSampleRate = sampleRate;
        metadataSnapshot.outChannels = channels;
    }

    void updateAudioSelectionMetadata(const std::string& codecName,
                                      const std::string& language,
                                      int64_t bitrate,
                                      int sampleRate,
                                      int channels) {
        std::lock_guard<std::mutex> lock(metadataMutex);
        metadataSnapshot.audioCodecName = codecName;
        metadataSnapshot.audioLanguage = language;
        metadataSnapshot.audioBitrate = bitrate;
        metadataSnapshot.outSampleRate = sampleRate;
        metadataSnapshot.outChannels = channels;
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

        bool attached = false;
        JNIEnv* env = getJniEnv(&attached);
        if (env) {
            if (kotlinPlayerRef) env->DeleteGlobalRef(kotlinPlayerRef);
            if (kotlinPlayerClass) env->DeleteGlobalRef(kotlinPlayerClass);
        }
        if (attached && g_jvm) {
            g_jvm->DetachCurrentThread();
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

    // Terminal failures must stop all workers and publish an explicit error.
    // This is intentionally separate from stopPlayback(): demux calls it from
    // its own thread, so it must never join itself.
    void failPlayback(JNIEnv* env, const char* msg) {
        isRunning.store(false);
        isStopped.store(true);
        isPaused.store(true);
        isBuffering.store(false);
        ioBridge.abortRequested.store(true);
        if (jniFile) jniFile->abortRead();
        nativeAudioSink.pause();
        nativeAudioSink.flush();
        videoQueue.abort();
        audioQueue.abort();
        controlCv.notify_all();

        if (!errorNotified.exchange(true)) {
            notifyError(env, msg);
            notifyState(env, STATE_ERROR);
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
        // The FFmpeg interrupt flag cannot preempt a synchronous JNI read.
        // Ask the Java source to cancel before joining callback-owning threads.
        if (jniFile) jniFile->abortRead();
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
        std::lock_guard<std::recursive_mutex> mediaLock(mediaOperationMutex);
        resetMetadataSnapshot();
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
        durationMs = 0;
        videoStreamIdx = -1;
        videoWidth = 0;
        videoHeight = 0;
        videoTimeBase = AVRational{1, 1000};
        videoRotation = 0;
        videoSarNum = 1;
        videoSarDen = 1;
        audioStreamIdx = -1;
        audioTimeBase = AVRational{1, 1000};
        outSampleRate = 48000;
        outChannels = 2;
        videoCodecName.clear();
        audioCodecName.clear();
        audioLanguage.clear();
        sourceFps = 0.0f;
        videoBitrate = 0;
        audioBitrate = 0;
        subtitleStreamIndices.clear();
        selectedSubtitleStreamIdx = -1;
        lastWindowWidth = -1;
        lastWindowHeight = -1;
        demuxEof.store(false);
        videoFinished.store(false);
        audioFinished.store(false);
        endNotified.store(false);
        errorNotified.store(false);
        videoSeekTargetPtsUs.store(-1);
        audioSeekTargetPtsUs.store(-1);
        isRunning.store(false);
        isStopped.store(true);
        isPaused.store(true);
    }
};

// Thread entry points (definitions in their own translation units)
void videoDecodeThreadFunc(FfmpegPlayerContext* ctx);
void audioDecodeThreadFunc(FfmpegPlayerContext* ctx);
void demuxThreadFunc(FfmpegPlayerContext* ctx, int64_t initialSeekMs);

// Demuxer interrupt callback (definition in FfmpegPlayerContext.cpp)
int player_interrupt_callback(void* opaque);
