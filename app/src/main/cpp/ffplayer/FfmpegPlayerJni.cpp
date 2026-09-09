#include "FfmpegPlayerContext.h"
#include "JniFileIO.h"

#include <cmath>
#include <cstdlib>

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
    std::unique_lock<std::recursive_mutex> mediaLock(ctx->mediaOperationMutex);
    ctx->closeMedia();

    // The context is stopped while the previous session is torn down, but it
    // must be live before avformat_open_input/find_stream_info. The interrupt
    // callback is also used by direct URL protocols, not only custom IO.
    ctx->isRunning.store(false);
    ctx->isStopped.store(false);
    ctx->isPaused.store(true);
    ctx->errorNotified.store(false);

    struct PendingSubtitleHeader {
        int streamIndex;
        std::vector<jbyte> data;
        std::string title;
    };
    struct PendingFontAttachment {
        std::string name;
        std::vector<jbyte> data;
    };
    std::vector<PendingSubtitleHeader> pendingSubtitleHeaders;
    std::vector<PendingFontAttachment> pendingFontAttachments;
    std::vector<std::string> pendingErrors;
    bool pendingVideoSize = false;
    int pendingVideoWidth = 0;
    int pendingVideoHeight = 0;
    int pendingVideoRotation = 0;
    int pendingVideoSarNum = 1;
    int pendingVideoSarDen = 1;
    int pendingAudioSessionId = -1;
    // stopPlayback aborts the previous session; clear that state before
    // opening either custom-IO or direct-URL input.
    ctx->ioBridge.abortRequested.store(false);

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
    std::vector<std::string> audioTrackNames;

    for (unsigned i = 0; i < ctx->fmtCtx->nb_streams; i++) {
        AVStream* st = ctx->fmtCtx->streams[i];
        AVCodecParameters* codecpar = st->codecpar;
        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO && ctx->videoStreamIdx < 0) {
            ctx->videoStreamIdx = static_cast<int>(i);
        } else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (ctx->audioStreamIdx < 0) {
                ctx->audioStreamIdx = static_cast<int>(i);
            }
            AVDictionaryEntry* lang = av_dict_get(st->metadata, "language", nullptr, 0);
            AVDictionaryEntry* title = av_dict_get(st->metadata, "title", nullptr, 0);
            std::string name = "Audio Track " + std::to_string(audioTrackNames.size() + 1);
            if (title && title->value) {
                name = title->value;
            } else if (lang && lang->value) {
                name = std::string("Audio (") + lang->value + ")";
            }
            audioTrackNames.push_back(name);
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

                PendingSubtitleHeader pendingHeader;
                pendingHeader.streamIndex = static_cast<int>(i);
                pendingHeader.title = std::move(subTitle);
                if (headerSize > 0 && codecpar->extradata) {
                    pendingHeader.data.assign(
                        reinterpret_cast<const jbyte*>(codecpar->extradata),
                        reinterpret_cast<const jbyte*>(codecpar->extradata) + headerSize);
                }
                pendingSubtitleHeaders.push_back(std::move(pendingHeader));
            }
        } else if (codecpar->codec_type == AVMEDIA_TYPE_ATTACHMENT) {
            if (codecpar->extradata && codecpar->extradata_size > 0 && ctx->midOnFontAttachment) {
                AVDictionaryEntry* nameEntry = av_dict_get(st->metadata, "filename", nullptr, 0);
                PendingFontAttachment pendingFont;
                pendingFont.name = (nameEntry && nameEntry->value) ? nameEntry->value : "font.ttf";
                pendingFont.data.assign(
                    reinterpret_cast<const jbyte*>(codecpar->extradata),
                    reinterpret_cast<const jbyte*>(codecpar->extradata) + codecpar->extradata_size);
                pendingFontAttachments.push_back(std::move(pendingFont));
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
                    pendingVideoSize = true;
                    pendingVideoWidth = ctx->videoWidth;
                    pendingVideoHeight = ctx->videoHeight;
                    pendingVideoRotation = ctx->videoRotation;
                    pendingVideoSarNum = ctx->videoSarNum;
                    pendingVideoSarDen = ctx->videoSarDen;
                }
                LOGI("Video decoder initialized: %s (%dx%d, threads=%d)",
                     vCodec->name, ctx->videoWidth, ctx->videoHeight, ctx->videoCodecCtx->thread_count);
            } else {
                LOGE("avcodec_open2 failed for video decoder %s", vCodec->name);
                pendingErrors.emplace_back("Failed to initialize video decoder");
            }
            av_dict_free(&codecOpts);
        } else {
            LOGE("No video decoder found for codec ID %d", vst->codecpar->codec_id);
            pendingErrors.emplace_back("Unsupported video codec");
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
                ctx->outChannels = nativeOutputChannelCount(inChannels);
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
                    pendingAudioSessionId = sid;
                }
                ctx->nativeEqualizer.init(ctx->outSampleRate, ctx->outChannels);
                LOGI("Audio decoder initialized: %s (sampleRate=%d, ch=%d, sessionId=%d)", aCodec->name, ctx->outSampleRate, ctx->outChannels, sid);
            }
        }
    }

    ctx->publishMetadataSnapshot(audioTrackNames);
    ctx->isRunning.store(true);
    ctx->isStopped.store(false);
    ctx->isPaused.store(false);
    ctx->triggerAudioRampIn(80);

    // Start video and audio decode threads, then demux thread
    ctx->videoThread = std::thread(videoDecodeThreadFunc, ctx);
    ctx->audioThread = std::thread(audioDecodeThreadFunc, ctx);
    ctx->demuxThread = std::thread(demuxThreadFunc, ctx, startPositionMs);

    // No media-operation lock is held while invoking Java callbacks. All data
    // originating from the just-opened format is copied into pending values.
    mediaLock.unlock();

    for (const auto& error : pendingErrors) {
        ctx->notifyError(env, error.c_str());
    }
    for (const auto& header : pendingSubtitleHeaders) {
        jbyteArray jHeader = env->NewByteArray(static_cast<jsize>(header.data.size()));
        if (!jHeader) continue;
        if (!header.data.empty()) {
            env->SetByteArrayRegion(jHeader, 0, static_cast<jsize>(header.data.size()), header.data.data());
        }
        jstring jTitle = env->NewStringUTF(header.title.c_str());
        if (jTitle) {
            env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnSubtitleHeader,
                                static_cast<jint>(header.streamIndex), jHeader, jTitle);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(jTitle);
        }
        env->DeleteLocalRef(jHeader);
    }
    for (const auto& font : pendingFontAttachments) {
        jstring jName = env->NewStringUTF(font.name.c_str());
        jbyteArray jFont = env->NewByteArray(static_cast<jsize>(font.data.size()));
        if (jName && jFont) {
            env->SetByteArrayRegion(jFont, 0, static_cast<jsize>(font.data.size()), font.data.data());
            env->CallVoidMethod(ctx->kotlinPlayerRef, ctx->midOnFontAttachment, jName, jFont);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (jName) env->DeleteLocalRef(jName);
        if (jFont) env->DeleteLocalRef(jFont);
    }
    if (pendingVideoSize) {
        ctx->notifyVideoSize(env, pendingVideoWidth, pendingVideoHeight,
                             pendingVideoRotation, pendingVideoSarNum, pendingVideoSarDen);
    }
    if (pendingAudioSessionId > 0) {
        ctx->notifyAudioSessionId(env, pendingAudioSessionId);
    }
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
    return ctx ? ctx->getMetadataDurationMs() : 0;
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
    int64_t durationMs = ctx->getMetadataDurationMs();
    if (durationMs > 0 && clockMs > durationMs) clockMs = durationMs;
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
    if (!ctx) return nullptr;

    // Copy labels while holding only the short metadata lock. Do not traverse
    // fmtCtx here: closeMedia may replace it during a concurrent teardown.
    auto metadata = ctx->getMetadataSnapshot();
    const auto& names = metadata.audioTrackNames;

    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strCls, nullptr);
    if (!arr) {
        env->DeleteLocalRef(strCls);
        return nullptr;
    }
    for (size_t i = 0; i < names.size(); i++) {
        jstring jstr = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), jstr);
        if (jstr) env->DeleteLocalRef(jstr);
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

JNI_FUNC(jint, nativeGetVideoWidth, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->getMetadataSnapshot().videoWidth : 0;
}

JNI_FUNC(jint, nativeGetVideoHeight, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->getMetadataSnapshot().videoHeight : 0;
}

JNI_FUNC(jboolean, nativeSelectAudioTrack, jlong handle, jint targetTrackIndex) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return JNI_FALSE;

    std::lock_guard<std::recursive_mutex> mediaLock(ctx->mediaOperationMutex);
    if (!ctx->fmtCtx || ctx->isStopped.load()) return JNI_FALSE;

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
    int newChannels = nativeOutputChannelCount(inChannels);
    AVDictionaryEntry* langEntry = av_dict_get(ast->metadata, "language", nullptr, 0);
    std::string newAudioLanguage = langEntry && langEntry->value ? langEntry->value : "";
    int64_t newAudioBitrate = (newCodecCtx->bit_rate > 0) ? newCodecCtx->bit_rate : ast->codecpar->bit_rate;

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
        ctx->audioCodecName = aCodec->name;
        ctx->audioLanguage = newAudioLanguage;
        ctx->audioBitrate = newAudioBitrate;
    }
    ctx->updateAudioSelectionMetadata(aCodec->name, newAudioLanguage, newAudioBitrate,
                                      newSampleRate, newChannels);

    // Flush audio queue so audio thread reconfigures resampler & sink smoothly without restarting demuxer
    ctx->audioQueue.clear();
    ctx->audioQueue.pushFlush();
    ctx->triggerAudioRampIn(60);

    LOGI("Successfully switched to audio track %d (stream %d, %s, %d Hz) without seeking demuxer",
         targetTrackIndex, targetStreamIdx, aCodec->name, newSampleRate);
    return JNI_TRUE;
}

JNI_FUNC(jint, nativeGetVideoRotation, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->getMetadataSnapshot().videoRotation : 0;
}

JNI_FUNC(jint, nativeGetSarNum, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->getMetadataSnapshot().videoSarNum : 1;
}

JNI_FUNC(jint, nativeGetSarDen, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    return ctx ? ctx->getMetadataSnapshot().videoSarDen : 1;
}

JNI_FUNC(jobjectArray, nativeGetDebugInfo, jlong handle) {
    auto* ctx = reinterpret_cast<FfmpegPlayerContext*>(handle);
    if (!ctx) return nullptr;

    // Copy all strings and scalar metadata before crossing into JNI. This keeps
    // Java array construction independent of open/close and worker mutations.
    auto metadata = ctx->getMetadataSnapshot();

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray result = env->NewObjectArray(11, strClass, nullptr);
    if (!result) {
        env->DeleteLocalRef(strClass);
        return nullptr;
    }

    auto setStr = [&](int index, const std::string& val) {
        jstring js = env->NewStringUTF(val.c_str());
        env->SetObjectArrayElement(result, index, js);
        if (js) env->DeleteLocalRef(js);
    };

    setStr(0, metadata.videoCodecName);
    std::string resStr = (metadata.videoWidth > 0 && metadata.videoHeight > 0)
        ? (std::to_string(metadata.videoWidth) + "x" + std::to_string(metadata.videoHeight))
        : "";
    setStr(1, resStr);
    setStr(2, std::to_string(metadata.sourceFps));
    setStr(3, std::to_string(metadata.videoBitrate));
    setStr(4, metadata.audioCodecName);
    setStr(5, std::to_string(metadata.outSampleRate));
    setStr(6, std::to_string(metadata.outChannels));
    setStr(7, metadata.audioLanguage);
    setStr(8, std::to_string(metadata.audioBitrate));
    setStr(9, std::to_string(ctx->totalRenderedFrames.load()));
    setStr(10, std::to_string(ctx->totalDroppedFrames.load()));

    env->DeleteLocalRef(strClass);
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
    if (!ctx) return JNI_FALSE;

    std::lock_guard<std::recursive_mutex> mediaLock(ctx->mediaOperationMutex);
    if (!ctx->fmtCtx || ctx->isStopped.load()) return JNI_FALSE;

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
