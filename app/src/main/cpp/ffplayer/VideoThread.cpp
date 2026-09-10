#include "FfmpegPlayerContext.h"
#include "HwVideoDecoder.h"
#include "GlVideoRenderer.h"
#include "HdrToneMapper.h"

// ─── Video Decode + Render Thread ────────────────────────────────────────────

void videoDecodeThreadFunc(FfmpegPlayerContext* ctx) {
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
        std::unique_lock<std::mutex> lock(ctx->windowMutex);
        if (!ctx->nativeWindow && ctx->isRunning.load() && !ctx->isStopped.load()) {
            ctx->controlCv.wait_for(lock, std::chrono::milliseconds(200), [&] {
                return ctx->nativeWindow != nullptr || !ctx->isRunning.load() || ctx->isStopped.load();
            });
        }
        if (ctx->nativeWindow) {
            if (hwDecoder.init(ctx->videoCodecPar, ctx->nativeWindow, ctx->forceSdr.load(), ctx->videoRotation)) {
                ctx->setVideoCodecName(hwDecoder.codecName);
            }
        }
    }

    GlVideoRenderer glRenderer;
    bool glRendererInitialized = false;

    auto renderFrame = [&](AVFrame* f, bool isSeekFrame) -> bool {
        if (f->width > 0 && f->height > 0 && (f->width != ctx->videoWidth || f->height != ctx->videoHeight)) {
            ctx->setVideoDimensions(f->width, f->height);
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
        bool anchorClockForSeek = false;
        if (targetPts >= 0) {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            if (ptsUs < targetPts - (frameDurUs / 2)) {
                // Drop all preroll frames before seek target
                return false;
            }
            // Consume only the target that this seek generation requested.
            // A newer seek must never be cleared by an older in-flight frame.
            int64_t expectedTargetPts = targetPts;
            if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion ||
                !ctx->videoSeekTargetPtsUs.compare_exchange_strong(
                    expectedTargetPts, -1, std::memory_order_acq_rel)) {
                return false;
            }
            isSeekFrame = true;
        } else if (ctx->isScrubbing.load() || isSeekFrame) {
            isSeekFrame = true;
            anchorClockForSeek = true;
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

                if (ctx->audioStreamIdx >= 0 && ctx->audioCodecCtx != nullptr) {
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
        if (anchorClockForSeek) {
            // Do not publish a scrubbing/seek position until the frame is
            // known to belong to the current seek generation.
            if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
                return false;
            }
            ctx->setMasterClockUs(ptsUs);
            ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
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
        bool anchorClockForSeek = false;
        if (targetPts >= 0) {
            int64_t frameDurUs = (ctx->sourceFps > 0) ? static_cast<int64_t>(1000000.0f / ctx->sourceFps) : 33333;
            if (ptsUs < targetPts - (frameDurUs / 2)) {
                // Drop all preroll frames before seek target without rendering
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }
            // Consume only the target that this seek generation requested.
            // A newer seek must never be cleared by an older in-flight frame.
            int64_t expectedTargetPts = targetPts;
            if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion ||
                !ctx->videoSeekTargetPtsUs.compare_exchange_strong(
                    expectedTargetPts, -1, std::memory_order_acq_rel)) {
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }
            isSeekFrame = true;
        } else if (ctx->isScrubbing.load() || isSeekFrame) {
            isSeekFrame = true;
            anchorClockForSeek = true;
        }

        ctx->lastVideoPtsUs.store(ptsUs);
        ctx->currentPositionMs.store(ptsUs / 1000);

        // Stale-frame guard: a newer seek arrived before this frame could be scheduled.
        if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
            AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
            return false;
        }
        if (anchorClockForSeek) {
            // Do not publish a scrubbing/seek position until the frame is
            // known to belong to the current seek generation.
            if (ctx->seekVersion.load(std::memory_order_acquire) != mySeekVersion) {
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, false);
                return false;
            }
            ctx->setMasterClockUs(ptsUs);
            ctx->notifyPosition(env, ptsUs / 1000, ctx->durationMs);
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

                if (ctx->audioStreamIdx >= 0 && ctx->audioCodecCtx != nullptr) {
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

            if (renderTimestampNs <= nowNs) {
                AMediaCodec_releaseOutputBuffer(hwDecoder.codec, outIdx, true);
            } else {
                AMediaCodec_releaseOutputBufferAtTime(hwDecoder.codec, outIdx, renderTimestampNs);
            }
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

    int hwDecodeConsecutiveFailures = 0;
    bool fallbackToSoftwareRequested = false;

    auto drainHwFrames = [&]() {
        if (!hwDecoder.codec || !hwDecoder.isConfigured.load()) return;
        AMediaCodecBufferInfo info;
        while (ctx->isRunning.load() && !ctx->isStopped.load() && ctx->seekTargetMs.load() < 0) {
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(hwDecoder.codec, &info, 0);
            if (outIdx >= 0) {
                hwDecodeConsecutiveFailures = 0;
                int64_t ptsUs = info.presentationTimeUs;
                bool rendered = renderHwFrame(ptsUs, outIdx, needSeekFrame);
                if (rendered) needSeekFrame = false;
            } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* fmt = AMediaCodec_getOutputFormat(hwDecoder.codec);
                if (fmt) {
                    int32_t w = 0, h = 0;
                    if (AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w) &&
                        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h)) {
                        ctx->setVideoDimensions(w, h);
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
        if (!p || !hwDecoder.codec || !hwDecoder.isConfigured.load()) return;
        if (ctx->seekTargetMs.load() >= 0) return;

        int64_t ptsUs = (p->pts != AV_NOPTS_VALUE)
            ? av_rescale_q(p->pts, ctx->videoTimeBase, AV_TIME_BASE_Q)
            : ((p->dts != AV_NOPTS_VALUE)
                ? av_rescale_q(p->dts, ctx->videoTimeBase, AV_TIME_BASE_Q)
                : 0);
        int maxRetries = 50;
        bool queued = false;
        while (maxRetries-- > 0 && ctx->isRunning.load() && !ctx->isStopped.load() && ctx->seekTargetMs.load() < 0) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(hwDecoder.codec, 5000);
            if (inIdx >= 0) {
                size_t inBufSize = 0;
                uint8_t* inBuf = AMediaCodec_getInputBuffer(hwDecoder.codec, inIdx, &inBufSize);
                if (inBuf && p->size <= inBufSize) {
                    memcpy(inBuf, p->data, p->size);
                    AMediaCodec_queueInputBuffer(hwDecoder.codec, inIdx, 0, p->size, ptsUs, 0);
                    queued = true;
                    hwDecodeConsecutiveFailures = 0;
                }
                break;
            } else if (inIdx < -1) {
                LOGW("HwVideoDecoder: AMediaCodec_dequeueInputBuffer fatal error %zd", inIdx);
                hwDecodeConsecutiveFailures += 5;
                break;
            }
            drainHwFrames();
        }
        if (!queued && ctx->seekTargetMs.load() < 0 && !ctx->isPaused.load()) {
            hwDecodeConsecutiveFailures++;
            if (hwDecodeConsecutiveFailures >= 30) {
                fallbackToSoftwareRequested = true;
            }
        }
    };

    // Decoupled pre-decoded frame pool for smooth presentation timing.
    // 6 frames absorbs decode jitter from I-frames / keyframes without starving the renderer.
    std::deque<AVFrame*> decodedFrames;
    const size_t maxDecodedFrames = 6;

    // A blocked demux/SMB read leaves both the packet queue and decoded-frame
    // queue empty. Do not report a single 5 ms scheduling gap as buffering, but
    // surface a sustained post-startup underrun to the Kotlin player state.
    const auto videoStarvationThreshold = std::chrono::milliseconds(200);
    bool videoStarvationPending = false;
    std::chrono::steady_clock::time_point videoStarvationStarted;

    auto resetVideoStarvation = [&]() {
        videoStarvationPending = false;
    };

    auto updateVideoStarvation = [&]() {
        // Startup and seek buffering are reported by the demux thread. This
        // detector is only for an underrun after at least one frame was shown.
        if (ctx->totalRenderedFrames.load(std::memory_order_relaxed) == 0 ||
            !ctx->isRunning.load() || ctx->isStopped.load() ||
            ctx->isPaused.load() || ctx->isScrubbing.load() ||
            ctx->seekTargetMs.load() >= 0 || needSeekFrame ||
            ctx->videoSeekTargetPtsUs.load() >= 0 ||
            ctx->demuxEof.load() || ctx->videoFinished.load()) {
            resetVideoStarvation();
            return;
        }

        auto now = std::chrono::steady_clock::now();
        if (!videoStarvationPending) {
            videoStarvationPending = true;
            videoStarvationStarted = now;
            return;
        }
        if (now - videoStarvationStarted < videoStarvationThreshold) return;

        if (!ctx->isBuffering.exchange(true)) {
            LOGW("Video pipeline starved; entering buffering state");
            ctx->notifyState(env, STATE_BUFFERING);
            ctx->controlCv.notify_all();
        }
    };

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
                        LOGI("HwVideoDecoder: setOutputSurface failed, falling back to software decoder");
                        hwDecoder.release();
                        if (ctx->videoCodecName.rfind("MediaCodec", 0) == 0) {
                            ctx->setVideoCodecName(ctx->videoCodecCtx ? ctx->videoCodecCtx->codec->name : "Software");
                        }
                    }
                } else {
                    hwDecoder.release();
                    if (ctx->videoCodecName.rfind("MediaCodec", 0) == 0) {
                        ctx->setVideoCodecName(ctx->videoCodecCtx ? ctx->videoCodecCtx->codec->name : "Software");
                    }
                }
            } else if (ctx->nativeWindow && allowHwNow && ctx->videoCodecPar) {
                // Surface was recreated (e.g. ActivityInfo.COLOR_MODE_HDR switch).
                // Re-initialize hardware decoder on the new surface.
                if (hwDecoder.init(ctx->videoCodecPar, ctx->nativeWindow, ctx->forceSdr.load(), ctx->videoRotation)) {
                    ctx->setVideoCodecName(hwDecoder.codecName);
                    LOGI("HwVideoDecoder: Re-initialized hardware decoder on recreated surface");
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
                resetVideoStarvation();
                drainOneDecodedFrame(false);
            } else {
                updateVideoStarvation();
            }
            continue;
        }
        resetVideoStarvation();

        if (item.isFlush) {
            hwDecodeConsecutiveFailures = 0;
            fallbackToSoftwareRequested = false;
            if (hwDecoder.isConfigured.load()) {
                if (hwDecoder.flush()) {
                    // Drain and discard any output buffers that were queued before the flush.
                    // Without this, stale pre-seek frames can appear momentarily at the new position.
                    AMediaCodecBufferInfo flushInfo;
                    ssize_t flushIdx;
                    while ((flushIdx = AMediaCodec_dequeueOutputBuffer(hwDecoder.codec, &flushInfo, 0)) >= 0) {
                        AMediaCodec_releaseOutputBuffer(hwDecoder.codec, flushIdx, false);
                    }
                } else {
                    // A failed MediaCodec restart must not leave the video
                    // thread feeding a dead hardware decoder indefinitely.
                    LOGW("HwVideoDecoder: Seek flush failed; falling back to software decoder");
                    hwDecoder.release();
                    if (ctx->videoCodecCtx) {
                        avcodec_flush_buffers(ctx->videoCodecCtx);
                        ctx->videoCodecCtx->skip_frame = AVDISCARD_DEFAULT;
                    }
                    if (ctx->videoCodecName.rfind("MediaCodec", 0) == 0) {
                        ctx->setVideoCodecName(ctx->videoCodecCtx ? ctx->videoCodecCtx->codec->name : "Software");
                    }
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
                        while (!fallbackToSoftwareRequested && hwDecoder.bsfCtx && av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
                            feedHwPacket(bsfPkt);
                            av_packet_unref(bsfPkt);
                        }
                    } else if (bsfRet == AVERROR(EAGAIN)) {
                        while (!fallbackToSoftwareRequested && hwDecoder.bsfCtx && av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
                            feedHwPacket(bsfPkt);
                            av_packet_unref(bsfPkt);
                        }
                        if (!fallbackToSoftwareRequested && hwDecoder.bsfCtx && av_bsf_send_packet(hwDecoder.bsfCtx, item.pkt) == 0) {
                            while (!fallbackToSoftwareRequested && hwDecoder.bsfCtx && av_bsf_receive_packet(hwDecoder.bsfCtx, bsfPkt) == 0) {
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

            if (fallbackToSoftwareRequested) {
                LOGW("HwVideoDecoder: Too many dequeue/feed failures, safely falling back to software decoder");
                hwDecoder.release();
                if (ctx->videoCodecCtx) {
                    avcodec_flush_buffers(ctx->videoCodecCtx);
                }
                if (ctx->videoCodecName.rfind("MediaCodec", 0) == 0) {
                    ctx->setVideoCodecName(ctx->videoCodecCtx ? ctx->videoCodecCtx->codec->name : "Software");
                }
                fallbackToSoftwareRequested = false;
                hwDecodeConsecutiveFailures = 0;
            }
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
