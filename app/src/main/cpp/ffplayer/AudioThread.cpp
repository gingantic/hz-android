#include "FfmpegPlayerContext.h"
#include "AudioFilterGraph.h"

// ─── Audio Decode Thread ─────────────────────────────────────────────────────

void audioDecodeThreadFunc(FfmpegPlayerContext* ctx) {
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
                // This fallback is only for a stream that has not presented
                // any video yet. Once playback has rendered a frame, a later
                // video underrun must keep audio paused until video recovers.
                if (std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - buffStart).count() > 2500 &&
                    ctx->totalRenderedFrames.load(std::memory_order_relaxed) == 0) {
                    LOGW("Initial buffering timeout in audio thread; releasing audio");
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
                ctx->outChannels = nativeOutputChannelCount(inCh);
                ctx->updateAudioOutputFormat(ctx->outSampleRate, ctx->outChannels);

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
