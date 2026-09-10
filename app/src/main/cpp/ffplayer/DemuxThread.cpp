#include "FfmpegPlayerContext.h"

// ─── Demux Thread ────────────────────────────────────────────────────────────

void demuxThreadFunc(FfmpegPlayerContext* ctx, int64_t initialSeekMs) {
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
        }
        if (ctx->audioStreamIdx >= 0 && ctx->audioCodecCtx) {
            ctx->audioSeekTargetPtsUs.store(0);
        } else {
            ctx->audioSeekTargetPtsUs.store(-1);
        }
        int initialSeekRet = av_seek_frame(ctx->fmtCtx, -1, 0, AVSEEK_FLAG_BACKWARD);
        if (initialSeekRet < 0) {
            LOGW("Initial seek to start failed (ret=%d); continuing from the demuxer's current position", initialSeekRet);
        }
        if (ctx->fmtCtx->pb) {
            ctx->fmtCtx->pb->eof_reached = 0;
            ctx->fmtCtx->pb->error = 0;
        }
    }

    AVPacket* pkt = av_packet_alloc();
    auto lastPosNotify = std::chrono::steady_clock::now();
    int64_t readErrorSinceMs = -1;

    auto reportDemuxFailure = [&](const char* prefix, int ret) {
        char errorText[AV_ERROR_MAX_STRING_SIZE] = {};
        av_strerror(ret, errorText, sizeof(errorText));
        std::string message = std::string(prefix) + ": " +
            (errorText[0] ? errorText : "unknown FFmpeg error");
        LOGE("%s (ret=%d)", message.c_str(), ret);
        ctx->failPlayback(env, message.c_str());
    };

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
                if (ctx->audioStreamIdx >= 0 && ctx->audioCodecCtx) {
                    ctx->audioSeekTargetPtsUs.store(target * 1000);
                } else {
                    ctx->audioSeekTargetPtsUs.store(-1);
                }
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
            if (seekRet < 0) {
                reportDemuxFailure("Seek failed", static_cast<int>(seekRet));
                break;
            }
            if (ctx->fmtCtx->pb) {
                ctx->fmtCtx->pb->eof_reached = 0;
                ctx->fmtCtx->pb->error = 0;
            }

            if (!scrubbing) {
                ctx->triggerAudioRampIn(40);
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

        int64_t readStartedMs = getMonotonicTimeMs();
        ctx->lastIoTimeMs.store(readStartedMs);
        int ret = av_read_frame(ctx->fmtCtx, pkt);
        int64_t readFinishedMs = getMonotonicTimeMs();
        ctx->lastIoTimeMs.store(readFinishedMs);
        if (ret < 0) {
            if (ret == AVERROR_EOF) {
                readErrorSinceMs = -1;
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
            }

            bool stopping = ctx->ioBridge.abortRequested.load() ||
                            ctx->isStopped.load() ||
                            !ctx->isRunning.load();
            if (ret == AVERROR_EXIT || stopping) {
                if (!stopping && ret == AVERROR_EXIT) {
                    reportDemuxFailure("Demuxer I/O aborted unexpectedly", ret);
                }
                break;
            }

            if (readErrorSinceMs < 0) {
                readErrorSinceMs = readFinishedMs;
            }
            int64_t errorAgeMs = readFinishedMs - readErrorSinceMs;
            if (ctx->ioTimeoutMs.load() > 0 && errorAgeMs >= ctx->ioTimeoutMs.load()) {
                reportDemuxFailure("Demuxer read failed", ret);
                break;
            }

            LOGW("Demuxer read returned %d; retrying for %" PRId64 " ms", ret, errorAgeMs);
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        readErrorSinceMs = -1;

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
            } else if (ctx->videoSeekTargetPtsUs.load() >= 0 && ctx->totalRenderedFrames.load() == 0) {
                curMs = ctx->videoSeekTargetPtsUs.load() / 1000;
            } else if (ctx->audioSeekTargetPtsUs.load() >= 0 && ctx->audioStreamIdx >= 0 && ctx->audioCodecCtx) {
                curMs = ctx->audioSeekTargetPtsUs.load() / 1000;
            } else {
                curMs = ctx->getMasterClockUs() / 1000;
                if (curMs <= 0 && ctx->totalRenderedFrames.load() > 0) {
                    curMs = ctx->currentPositionMs.load();
                    if (curMs <= 0) {
                        curMs = ctx->lastVideoPtsUs.load() / 1000;
                    }
                }
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
