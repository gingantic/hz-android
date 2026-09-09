#pragma once
#include "PlayerCommon.h"

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
