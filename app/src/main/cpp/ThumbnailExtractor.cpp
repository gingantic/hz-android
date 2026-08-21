#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/codec_desc.h>
#include <libavutil/avutil.h>
#include <libavutil/pixdesc.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

#include <cstdio>
#include <cstdlib>
#include <vector>
#include <cstring>
#include <chrono>
#include <algorithm>
#include <cinttypes>
#include <string>

#define LOG_TAG "HzPlayer/Thumb"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// === ponytail: this exists ===
// RandomAccessFile interface mirrors testffmpeg/src/FileIO.h exactly.
// JniFile replaces LocalFile by bridging via JNI into Kotlin RandomAccessBridge.

class RandomAccessFile {
public:
    virtual ~RandomAccessFile() = default;
    virtual int64_t read(uint8_t* buf, int64_t size) = 0;
    virtual int64_t seek(int64_t offset, int whence) = 0;
    virtual int64_t size() = 0;
    virtual bool ok() const = 0;
};

// ─── JNI bridge to Kotlin RandomAccessBridge ─────────────────────────

class JniFile : public RandomAccessFile {
    JNIEnv* const env_;
    jobject const bridge_;
    int64_t pos_ = 0;
    mutable int64_t cachedSize_ = -1;
    jmethodID readAtMid_;
    jmethodID getSizeMid_;
    jbyteArray jbuf_ = nullptr;
    jsize jbufCapacity_ = 0;
    bool ok_ = true;

public:
    JniFile(JNIEnv* e, jobject bridge)
        : env_(e), bridge_(e->NewGlobalRef(bridge)) {
        jclass cls = env_->GetObjectClass(bridge_);
        if (env_->ExceptionCheck()) { env_->ExceptionClear(); ok_ = false; }
        readAtMid_ = env_->GetMethodID(cls, "readAt", "(J[BI)I");
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            ok_ = false;
        }
        getSizeMid_ = env_->GetMethodID(cls, "getSize", "()J");
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            ok_ = false;
        }
        if (cls) env_->DeleteLocalRef(cls);
    }

    ~JniFile() override {
        if (jbuf_) {
            env_->DeleteGlobalRef(jbuf_);
            jbuf_ = nullptr;
        }
        if (bridge_) env_->DeleteGlobalRef(bridge_);
    }

    int64_t read(uint8_t* buf, int64_t size) override {
        if (!ok_) return -1;
        jsize requiredSize = static_cast<jsize>(size);
        if (!jbuf_ || jbufCapacity_ < requiredSize) {
            if (jbuf_) {
                env_->DeleteGlobalRef(jbuf_);
                jbuf_ = nullptr;
            }
            jbyteArray localBuf = env_->NewByteArray(requiredSize);
            if (!localBuf) return -1;
            jbuf_ = static_cast<jbyteArray>(env_->NewGlobalRef(localBuf));
            env_->DeleteLocalRef(localBuf);
            jbufCapacity_ = requiredSize;
        }

        jint n = env_->CallIntMethod(bridge_, readAtMid_, pos_, jbuf_,
                                     requiredSize);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            return -1;
        }

        if (n > 0) {
            env_->GetByteArrayRegion(jbuf_, 0, n,
                                     reinterpret_cast<jbyte*>(buf));
            pos_ += n;
        }

        // -1 means EOF/error in Kotlin bridge — map to 0 for FFmpeg (0 = EOF)
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
        if (!ok_) return 0;
        if (cachedSize_ < 0) {
            cachedSize_ = env_->CallLongMethod(bridge_, getSizeMid_);
            if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
                cachedSize_ = 0;
            }
        }
        return cachedSize_;
    }

    bool ok() const override { return ok_ && bridge_ != nullptr; }
};

// ─── I/O instrumentation ────────────────────────────────────────────

struct IOStats {
    int64_t readCalls = 0;
    int64_t seekCalls = 0;
    int64_t totalBytes = 0;
    int64_t currentPos = 0;
};

static int g_avioBufSize = 1024 * 1024; // 1 MB

struct IOBridge {
    RandomAccessFile* file;
    IOStats* stats;
    // Probe budget: during avformat_find_stream_info, cap *additional* I/O
    // (relative to probeBaseBytes) so pathological files don't scan forever.
    int64_t probeLimit    = 0;
    int64_t probeBaseBytes = 0; // totalBytes snapshot when probing started
    bool    probing       = false;
    // Hard deadline: abort I/O after this point.  Returns AVERROR_EXIT (not
    // EOF) so FFmpeg fails immediately instead of retrying endlessly.
    std::chrono::steady_clock::time_point deadline{};
    bool hasDeadline  = false;
    bool deadlineHit  = false; // log only once
};

static int io_read(void* opaque, uint8_t* buf, int bufSize) {
    auto* io = static_cast<IOBridge*>(opaque);
    // Enforce hard deadline — return AVERROR_EXIT so FFmpeg aborts the
    // operation immediately (returning 0/EOF causes demuxer retries → spam).
    if (io->hasDeadline &&
        std::chrono::steady_clock::now() > io->deadline) {
        if (!io->deadlineHit) {
            io->deadlineHit = true;
            LOGD("I/O deadline exceeded, aborting extraction");
        }
        return AVERROR_EXIT;
    }
    // Enforce probe budget — cap *additional* bytes read since probing began.
    if (io->probing && io->probeLimit > 0 &&
        (io->stats->totalBytes - io->probeBaseBytes) >= io->probeLimit) {
        return AVERROR_EOF;
    }
    int64_t n = io->file->read(buf, bufSize);
    if (n > 0) {
        io->stats->readCalls++;
        io->stats->totalBytes += n;
        io->stats->currentPos += n;
    }
    return static_cast<int>(n);
}

static int64_t io_seek(void* opaque, int64_t offset, int whence) {
    auto* io = static_cast<IOBridge*>(opaque);
    if (whence == AVSEEK_SIZE) {
        return io->file->size();
    }
    io->stats->seekCalls++;
    int64_t newPos = io->file->seek(offset, whence);
    if (newPos >= 0) io->stats->currentPos = newPos;
    return newPos;
}

// ─── RGBA ───────────────────────────────────────────────────────────

static std::vector<uint8_t> frameToRgba(AVFrame* frame, int dstW, int dstH, int& outW, int& outH) {
    int w = frame->width;
    int h = frame->height;
    AVPixelFormat srcFmt = static_cast<AVPixelFormat>(frame->format);

    if (srcFmt == AV_PIX_FMT_NONE) {
        LOGE("frameToRgba: AV_PIX_FMT_NONE, can't convert");
        return {};
    }

    int numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGBA, dstW, dstH, 1);
    std::vector<uint8_t> rgba(numBytes);

    AVFrame* tmp = av_frame_alloc();
    av_image_fill_arrays(tmp->data, tmp->linesize, rgba.data(),
                         AV_PIX_FMT_RGBA, dstW, dstH, 1);
    tmp->width  = dstW;
    tmp->height = dstH;
    tmp->format = AV_PIX_FMT_RGBA;

    SwsContext* sws = sws_getContext(w, h, srcFmt,
        dstW, dstH, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!sws) {
        // Try AVFrame re-write trick: some formats swscale doesn't handle directly
        LOGE("sws_getContext failed for fmt=%s, trying raw copy fallback",
             av_get_pix_fmt_name(srcFmt));
        // If frame data is already RGBA-like (e.g. some hw surfaces)
        if (frame->linesize[0] > 0 && frame->data[0] && w == dstW && h == dstH) {
            int rowBytes = std::min(frame->linesize[0], w * 4);
            for (int y = 0; y < h && y < frame->height; y++) {
                memcpy(rgba.data() + y * w * 4,
                       frame->data[0] + y * frame->linesize[0], rowBytes);
            }
            av_frame_free(&tmp);
            outW = w;
            outH = h;
            return rgba;
        }
        av_frame_free(&tmp);
        return {};
    }
    sws_scale(sws, frame->data, frame->linesize, 0, h,
              tmp->data, tmp->linesize);
    sws_freeContext(sws);
    av_frame_free(&tmp);
    outW = dstW;
    outH = dstH;
    return rgba;
}

// ─── JNI: native entry point ────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_rhnxdev_hzplayer_core_thumbnail_NativeThumbnailExtractor_nativeExtract(
    JNIEnv* env, jclass /*clazz*/, jobject bridge,
    jfloat positionPercent, jint maxWidth, jboolean fastMode) {

    if (!bridge) { LOGE("bridge is null"); return nullptr; }

    JniFile file(env, bridge);
    if (!file.ok()) { LOGE("JniFile init failed"); return nullptr; }

    IOStats stats;
    IOBridge ioBridge{&file, &stats};

    // In fast mode (network/SMB), use a 1 MB AVIO buffer.  SMB has ~50 ms
    // round-trip latency per read; a larger buffer means fewer round-trips
    // for sequential header/moov parsing (15 MB MKV attachments: 15 reads
    // instead of 60, saving ~2.5 s).  Memory cost is negligible.
    const int avioBufSize = fastMode ? (1024 * 1024) : g_avioBufSize;
    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(avioBufSize));
    if (!ioBuf) { LOGE("av_malloc failed"); return nullptr; }
    AVIOContext* avio = avio_alloc_context(ioBuf, avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);

    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;

    if (fastMode) {
        // Network-optimised probing: MP4/MKV expose streams from the header
        // alone; 256 KB is generous. MPEG-TS may need a bit more but 256 KB
        // still catches the first video PES in the vast majority of streams.
        fmtCtx->probesize = 256 * 1024;          // 256 KB
        fmtCtx->max_analyze_duration = 500000;   // 500 ms
    } else {
        // Local files: generous budget so MPEG-TS streams are detected.
        fmtCtx->probesize = 2 * 1024 * 1024;     // 2 MB
        fmtCtx->max_analyze_duration = 2000000;  // 2s analysis max
    }
    fmtCtx->fps_probe_size = 1; // Don't decode 20 frames just to estimate frame rate

    auto t0 = std::chrono::steady_clock::now();

    // Hard deadline: abort I/O after this point so the thread returns
    // gracefully (Coil would cancel the coroutine, but JNI calls can't be
    // interrupted — the native code must self-terminate).  Applies to local
    // extraction too: with a broken seek index or garbage timestamps the
    // decode loop would otherwise chew through the entire file.  Hitting the
    // deadline still yields a thumbnail — the loop exits with the best frame
    // decoded so far (the keyframe just before the target).
    ioBridge.deadline    = t0 + std::chrono::seconds(fastMode ? 15 : 20);
    ioBridge.hasDeadline = true;

    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "ignore_chapters", "1", 0);
    av_dict_set(&opts, "ignore_editlist", "1", 0);
    av_dict_set(&opts, "enable_drefs", "0", 0);

    int openRet = avformat_open_input(&fmtCtx, "", nullptr, &opts);
    av_dict_free(&opts);

    if (openRet != 0) {
        LOGE("avformat_open_input via AVIO failed");
        // avformat_open_input already frees fmtCtx AND fmtCtx->pb (our avio)
        // on failure — no manual cleanup needed (doing so would double-free).
        return nullptr;
    }
    // Probe budget for fast mode: cap *additional* I/O during find_stream_info.
    // probeBaseBytes snapshots current total so files that already read 15 MB
    // during open_input (MKV attachments) still get a full 4 MB probe window.
    static constexpr int64_t FAST_PROBE_LIMIT = 4 * 1024 * 1024; // 4 MB
    ioBridge.probeBaseBytes = stats.totalBytes;
    ioBridge.probing        = fastMode;
    ioBridge.probeLimit     = fastMode ? FAST_PROBE_LIMIT : 0;

    int siRet = avformat_find_stream_info(fmtCtx, nullptr);
    ioBridge.probing = false; // lift the budget for the decode phase

    if (siRet < 0) {
        if (fastMode && fmtCtx->nb_streams > 0) {
            // Budget exhausted but header detected streams — proceed with
            // header-only codec params (MKV Tracks / MP4 stbl provide these).
            LOGD("fastMode: find_stream_info truncated (budget), using header info");
        } else {
            LOGE("avformat_find_stream_info failed");
            avformat_close_input(&fmtCtx); // frees avio + ioBuf
            return nullptr;
        }
    }

    int si = -1;
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++)
        if (fmtCtx->streams[i] && fmtCtx->streams[i]->codecpar && fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            si = static_cast<int>(i); break;
        }
    if (si == -1) {
        LOGE("no video stream found");
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    // ── Seek to target timestamp ──
    AVStream* st = fmtCtx->streams[si];
    int64_t durationUs = fmtCtx->duration; // container-level, AV_TIME_BASE
    int64_t targetUs = static_cast<int64_t>(durationUs *
                                            static_cast<double>(positionPercent));
    int64_t targetTs = av_rescale_q(targetUs, AV_TIME_BASE_Q, st->time_base);

    bool seekOk = false;
    if (av_seek_frame(fmtCtx, si, targetTs, AVSEEK_FLAG_BACKWARD) >= 0) {
        seekOk = true;
    } else if (fastMode) {
        // find_stream_info was truncated → no seek index.
        // Try byte-based seek (demuxer may support AVSEEK_FLAG_BYTE), then
        // fall back to raw AVIO seek.  The demuxer will resync on the next
        // av_read_frame by scanning for a valid cluster/frame header.
        int64_t fileSize = file.size();
        int64_t estimatedPos = static_cast<int64_t>(
            fileSize * static_cast<double>(positionPercent) * 0.95);
        LOGD("fastMode: av_seek_frame failed, byte-seeking to %" PRId64, estimatedPos);
        if (av_seek_frame(fmtCtx, -1, estimatedPos, AVSEEK_FLAG_BYTE) < 0) {
            avio_seek(fmtCtx->pb, estimatedPos, SEEK_SET);
        }
        seekOk = true;
    }
    if (!seekOk) {
        LOGE("av_seek_frame failed");
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    // ── Init decoder ──
    const AVCodec* codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) { LOGE("no decoder found"); avformat_close_input(&fmtCtx); return nullptr; }

    AVCodecContext* dec = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(dec, st->codecpar);
    if (avcodec_open2(dec, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        avcodec_free_context(&dec);
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    avcodec_flush_buffers(dec);

    AVPacket* pkt = av_packet_alloc();
    AVFrame*  frame = av_frame_alloc();
    AVFrame*  foundFrame = nullptr;
    int64_t   bestDelta = INT64_MAX;
    bool      pastTarget = false;

    while (!pastTarget && av_read_frame(fmtCtx, pkt) >= 0) {
        if (pkt->stream_index != si) { av_packet_unref(pkt); continue; }
        int sendRet = avcodec_send_packet(dec, pkt);
        av_packet_unref(pkt);
        // A non-EAGAIN send error is fatal (corrupt packet / decoder stuck) —
        // stop instead of re-entering av_read_frame and busy-looping to EOF.
        if (sendRet < 0) {
            if (sendRet != AVERROR(EAGAIN)) break;
            continue;
        }
        while (true) {
            int ret = avcodec_receive_frame(dec, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;

            if (fastMode) {
                // Network-fast: accept the very first decoded frame.
                // We already seeked to the target keyframe; decoding more
                // just burns network bytes for a marginally better pick.
                foundFrame = av_frame_clone(frame);
                av_frame_unref(frame);
                break;
            }

            if (frame->pts != AV_NOPTS_VALUE) {
                // Pick the frame closest to the target timestamp (not just the
                // first one at/after it), so a late keyframe still lands well.
                int64_t delta = llabs(frame->pts - targetTs);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    av_frame_free(&foundFrame);
                    foundFrame = av_frame_clone(frame);
                }
                // Frames are in presentation order; once we pass the target,
                // every later frame is further away — stop decoding.  This
                // must also terminate the OUTER av_read_frame loop (via
                // pastTarget): breaking only the inner receive loop left the
                // outer one demuxing + decoding the entire remainder of the
                // file — minutes of CPU on long videos — while foundFrame
                // was already final.
                if (frame->pts > targetTs) { pastTarget = true; break; }
            } else if (!foundFrame) {
                // No PTS available: fall back to the first decoded frame
                // (supersedes the previous NOPTS-only branch).
                foundFrame = av_frame_clone(frame);
            }
            av_frame_unref(frame);
        }
        if (foundFrame && (fastMode || bestDelta == 0)) break;
    }

    av_frame_free(&frame);
    av_packet_free(&pkt);
    avcodec_free_context(&dec);

    jobject resultBitmap = nullptr;

    if (foundFrame) {
        int dstW = foundFrame->width;
        int dstH = foundFrame->height;

        // Scale if wider than maxWidth
        if (maxWidth > 0 && dstW > maxWidth) {
            dstH = static_cast<int>((static_cast<int64_t>(dstH) * maxWidth) / dstW);
            dstW = maxWidth;
            if (dstH < 1) dstH = 1;
        }

        // Convert to RGBA (may also scale via sws)
        // If dimensions changed, sws_scale will handle the resize
        int outW, outH;
        std::vector<uint8_t> rgba = frameToRgba(foundFrame, dstW, dstH, outW, outH);

        if (!rgba.empty() && outW == dstW && outH == dstH) {
            // Create Bitmap via JNI — guard every lookup so a mangled symbol
            // (R8) or OOM can't deref a null method/class and segfault.
            jclass bmpCls = env->FindClass("android/graphics/Bitmap");
            if (!bmpCls) {
                LOGE("FindClass Bitmap failed");
                env->ExceptionClear();
            } else {
                jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
                if (!cfgCls) {
                    LOGE("FindClass Bitmap$Config failed");
                    env->ExceptionClear();
                } else {
                    jmethodID valueOf = env->GetStaticMethodID(cfgCls, "valueOf",
                        "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
                    if (!valueOf) {
                        LOGE("GetStaticMethodID valueOf failed");
                        env->ExceptionClear();
                    } else {
                        jstring cfgName = env->NewStringUTF("ARGB_8888");
                        if (!cfgName) {
                            LOGE("NewStringUTF failed");
                            env->ExceptionClear();
                        } else {
                            jobject cfg = env->CallStaticObjectMethod(cfgCls, valueOf, cfgName);
                            env->DeleteLocalRef(cfgName);
                            if (env->ExceptionCheck()) {
                                LOGE("valueOf threw");
                                env->ExceptionClear();
                                cfg = nullptr;
                            }
                            if (cfg) {
                                jmethodID createBmp = env->GetStaticMethodID(bmpCls, "createBitmap",
                                    "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
                                if (!createBmp) {
                                    LOGE("GetStaticMethodID createBitmap failed");
                                    env->ExceptionClear();
                                } else {
                                    jobject bitmap = env->CallStaticObjectMethod(bmpCls, createBmp,
                                                                                 outW, outH, cfg);
                                    if (env->ExceptionCheck()) {
                                        LOGE("createBitmap threw");
                                        env->ExceptionClear();
                                        bitmap = nullptr;
                                    }
                                    if (bitmap) {
                                        // Copy RGBA pixels — use the *actual* decoded
                                        // dimensions (outW/outH), not the requested dstW/dstH,
                                        // so the raw-copy fallback (which reports source dims)
                                        // can't read out of bounds.
                                        AndroidBitmapInfo info;
                                        if (AndroidBitmap_getInfo(env, bitmap, &info) == 0) {
                                            void* pixels = nullptr;
                                            if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                                                uint8_t* src = rgba.data();
                                                uint8_t* dst = static_cast<uint8_t*>(pixels);
                                                size_t rowBytes = static_cast<size_t>(outW) * 4;
                                                for (int y = 0; y < outH; y++) {
                                                    memcpy(dst, src, rowBytes);
                                                    src += rowBytes;
                                                    dst += info.stride;
                                                }
                                                AndroidBitmap_unlockPixels(env, bitmap);
                                            }
                                        }
                                        resultBitmap = bitmap;
                                    }
                                }
                            }
                        }
                    }
                    env->DeleteLocalRef(cfgCls);
                }
                env->DeleteLocalRef(bmpCls);
            }
        }
        av_frame_free(&foundFrame);
    } else {
        LOGD("no frame found at %.0f%%", positionPercent * 100.0);
    }

    // ── Summary ──
    auto t1 = std::chrono::steady_clock::now();
    int elapsedMs = static_cast<int>(
        std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());
    LOGD("Thumbnail %s in %d ms (%.1f MB read, %" PRId64 " reads, %" PRId64 " seeks)",
         resultBitmap ? "ok" : "FAILED", elapsedMs,
         stats.totalBytes / (1024.0 * 1024.0),
         stats.readCalls, stats.seekCalls);

    avformat_close_input(&fmtCtx); // frees avio + ioBuf

    return resultBitmap;
}

// ─── Embedded ASS/SSA subtitle extraction ───────────────────────────
// Demuxes the container and reassembles a complete .ass document (header from
// the stream's extradata + all Dialogue events) so libass can render it with
// full styling. ExoPlayer's built-in parser drops override tags, so we go to
// ffmpeg directly.
// ponytail: full-file demux scan — subtitle packets are interleaved throughout
// the container, so there's no cheaper way to collect them all. Upgrade path:
// incremental/progressive feeding into libass if load latency matters.

static void formatAssTime(char* buf, size_t bufSize, int64_t cs) {
    if (cs < 0) cs = 0;
    int h = static_cast<int>(cs / 360000);
    int m = static_cast<int>((cs / 6000) % 60);
    int s = static_cast<int>((cs / 100) % 60);
    int c = static_cast<int>(cs % 100);
    snprintf(buf, bufSize, "%d:%02d:%02d.%02d", h, m, s, c);
}

static void appendDialogue(std::string& out, AVStream* st, AVPacket* pkt) {
    std::string data(reinterpret_cast<const char*>(pkt->data),
                     static_cast<size_t>(pkt->size));
    while (!data.empty() && (data.back() == '\n' || data.back() == '\r'))
        data.pop_back();

    int64_t pts = (pkt->pts != AV_NOPTS_VALUE) ? pkt->pts
                : (pkt->dts != AV_NOPTS_VALUE ? pkt->dts : 0);
    int64_t startCs = av_rescale_q(pts, st->time_base, AVRational{1, 100});
    int64_t durCs = pkt->duration > 0
        ? av_rescale_q(pkt->duration, st->time_base, AVRational{1, 100}) : 0;
    char startBuf[32], endBuf[32];
    formatAssTime(startBuf, sizeof(startBuf), startCs);
    formatAssTime(endBuf, sizeof(endBuf), startCs + durCs);

    // libavcodec ass packet format:
    //   ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
    // Text (field 9) may contain commas, so split only the first 8.
    std::vector<std::string> f;
    size_t pos = 0;
    for (int i = 0; i < 8 && pos != std::string::npos; i++) {
        size_t comma = data.find(',', pos);
        if (comma == std::string::npos) { f.push_back(data.substr(pos)); pos = std::string::npos; }
        else { f.push_back(data.substr(pos, comma - pos)); pos = comma + 1; }
    }
    std::string text = (pos == std::string::npos) ? std::string() : data.substr(pos);

    out += "Dialogue: ";
    if (f.size() >= 8) {
        // f[0]=ReadOrder (dropped), f[1]=Layer, f[2]=Style, f[3]=Name,
        // f[4]=MarginL, f[5]=MarginR, f[6]=MarginV, f[7]=Effect
        out += f[1]; out += ',';
        out += startBuf; out += ',';
        out += endBuf; out += ',';
        out += f[2]; out += ','; out += f[3]; out += ',';
        out += f[4]; out += ','; out += f[5]; out += ','; out += f[6]; out += ',';
        out += f[7]; out += ',';
        out += text;
    } else {
        // Unknown layout — emit with default fields so libass still shows the line.
        out += "0,"; out += startBuf; out += ','; out += endBuf;
        out += ",Default,,0,0,0,,";
        out += data;
    }
    out += '\n';
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_subtitle_AssStreamExtractor_nativeExtractAss(
    JNIEnv* env, jclass /*clazz*/, jobject bridge, jint assOrdinal) {

    if (!bridge) { LOGE("ass: bridge is null"); return nullptr; }

    JniFile file(env, bridge);
    if (!file.ok()) { LOGE("ass: JniFile init failed"); return nullptr; }

    IOStats stats;
    IOBridge ioBridge{&file, &stats};

    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(g_avioBufSize));
    if (!ioBuf) { LOGE("ass: av_malloc failed"); return nullptr; }
    AVIOContext* avio = avio_alloc_context(ioBuf, g_avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);

    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;
    fmtCtx->probesize = 5 * 1024 * 1024;
    fmtCtx->max_analyze_duration = 5000000;

    if (avformat_open_input(&fmtCtx, "", nullptr, nullptr) != 0) {
        LOGE("ass: avformat_open_input failed");
        return nullptr;
    }
    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        LOGE("ass: find_stream_info failed");
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    // Find the assOrdinal-th ASS/SSA subtitle stream (container order).
    int target = -1, seen = 0;
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++) {
        if (!fmtCtx->streams[i] || !fmtCtx->streams[i]->codecpar) continue;
        AVCodecID id = fmtCtx->streams[i]->codecpar->codec_id;
        if (id == AV_CODEC_ID_ASS || id == AV_CODEC_ID_SSA) {
            if (seen == assOrdinal) { target = static_cast<int>(i); break; }
            seen++;
        }
    }
    if (target < 0) {
        LOGD("ass: no ASS stream at ordinal %d", assOrdinal);
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    AVStream* st = fmtCtx->streams[target];
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++)
        fmtCtx->streams[i]->discard =
            (static_cast<int>(i) == target) ? AVDISCARD_DEFAULT : AVDISCARD_ALL;

    std::string out;
    if (st->codecpar->extradata && st->codecpar->extradata_size > 0) {
        out.append(reinterpret_cast<const char*>(st->codecpar->extradata),
                   static_cast<size_t>(st->codecpar->extradata_size));
        if (out.empty() || out.back() != '\n') out.push_back('\n');
    } else {
        out += "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n"
               "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
               "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, "
               "ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, "
               "MarginL, MarginR, MarginV, Encoding\n"
               "Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,"
               "0,0,0,0,100,100,0,0,1,2,1,2,10,10,10,1\n\n[Events]\n"
               "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, "
               "Effect, Text\n";
    }

    AVPacket* pkt = av_packet_alloc();
    while (av_read_frame(fmtCtx, pkt) >= 0) {
        if (pkt->stream_index == target && pkt->data && pkt->size > 0)
            appendDialogue(out, st, pkt);
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    avformat_close_input(&fmtCtx);

    LOGD("ass: extracted %zu bytes for ordinal %d", out.size(), assOrdinal);
    return env->NewStringUTF(out.c_str());
}

// ─── Embedded attachment font extraction ────────────────────────────
// Pulls muxed font files (MKV attachments) so libass can render styles with
// their intended typefaces instead of a fallback. Returns a flat Object[] of
// [String name, byte[] data, ...] pairs, or null if none/failure.

static bool hasFontExtension(const char* name) {
    if (!name) return false;
    size_t n = strlen(name);
    static const char* exts[] = {".ttf", ".otf", ".ttc", ".otc", ".pfb"};
    for (const char* e : exts) {
        size_t el = strlen(e);
        if (n >= el) {
            bool match = true;
            for (size_t i = 0; i < el; i++) {
                char a = name[n - el + i];
                if (a >= 'A' && a <= 'Z') a = static_cast<char>(a - 'A' + 'a');
                if (a != e[i]) { match = false; break; }
            }
            if (match) return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_subtitle_AssStreamExtractor_nativeExtractFonts(
    JNIEnv* env, jclass /*clazz*/, jobject bridge) {

    if (!bridge) return nullptr;
    JniFile file(env, bridge);
    if (!file.ok()) return nullptr;

    IOStats stats;
    IOBridge ioBridge{&file, &stats};
    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(g_avioBufSize));
    if (!ioBuf) return nullptr;
    AVIOContext* avio = avio_alloc_context(ioBuf, g_avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);
    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;
    fmtCtx->probesize = 5 * 1024 * 1024;
    fmtCtx->max_analyze_duration = 5000000;

    if (avformat_open_input(&fmtCtx, "", nullptr, nullptr) != 0) {
        LOGE("fonts: avformat_open_input failed");
        return nullptr;
    }
    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    std::vector<int> fontStreams;
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++) {
        AVStream* st = fmtCtx->streams[i];
        if (!st || !st->codecpar) continue;
        if (st->codecpar->codec_type != AVMEDIA_TYPE_ATTACHMENT) continue;
        if (!st->codecpar->extradata || st->codecpar->extradata_size <= 0) continue;
        AVDictionaryEntry* mt = av_dict_get(st->metadata, "mimetype", nullptr, 0);
        AVDictionaryEntry* fn = av_dict_get(st->metadata, "filename", nullptr, 0);
        bool isFont = (fn && hasFontExtension(fn->value)) ||
                      (mt && (strstr(mt->value, "font") || strstr(mt->value, "truetype") ||
                              strstr(mt->value, "opentype") || strstr(mt->value, "sfnt")));
        if (isFont) fontStreams.push_back(static_cast<int>(i));
    }

    if (fontStreams.empty()) { avformat_close_input(&fmtCtx); return nullptr; }

    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(fontStreams.size() * 2), objCls, nullptr);
    env->DeleteLocalRef(objCls);
    if (!arr) { avformat_close_input(&fmtCtx); return nullptr; }

    for (size_t k = 0; k < fontStreams.size(); k++) {
        AVStream* st = fmtCtx->streams[fontStreams[k]];
        AVDictionaryEntry* fn = av_dict_get(st->metadata, "filename", nullptr, 0);
        const char* name = fn ? fn->value : "font";

        jstring jn = env->NewStringUTF(name);
        env->SetObjectArrayElement(arr, static_cast<jsize>(k * 2), jn);
        env->DeleteLocalRef(jn);

        jsize sz = static_cast<jsize>(st->codecpar->extradata_size);
        jbyteArray jb = env->NewByteArray(sz);
        env->SetByteArrayRegion(jb, 0, sz,
                                reinterpret_cast<const jbyte*>(st->codecpar->extradata));
        env->SetObjectArrayElement(arr, static_cast<jsize>(k * 2 + 1), jb);
        env->DeleteLocalRef(jb);
    }

    LOGD("ass: extracted %zu embedded fonts", fontStreams.size());
    avformat_close_input(&fmtCtx);
    return arr;
}

// ─── Media info probe (codec / container metadata) ─────────────────
// Opens the source just far enough to read container + stream headers and
// returns a flat String[] of key/value pairs describing the codecs. The
// Kotlin side converts it to a Map. Keys (only present when known):
//   format, format_long, bitrate,
//   video_codec, video_codec_long, video_profile, video_resolution,
//   video_bitrate, video_fps, video_pix_fmt,
//   audio_codec, audio_codec_long, audio_bitrate, audio_sample_rate,
//   audio_channels

static void probePut(std::vector<std::string>& kv, const char* key,
                     const std::string& value) {
    if (value.empty()) return;
    kv.emplace_back(key);
    kv.push_back(value);
}

static std::string probeCodecName(const AVCodecParameters* par) {
    if (!par) return "";
    const AVCodecDescriptor* desc = avcodec_descriptor_get(par->codec_id);
    if (desc && desc->name) return desc->name;
    return "";
}

static std::string probeCodecLongName(const AVCodecParameters* par) {
    if (!par) return "";
    const AVCodecDescriptor* desc = avcodec_descriptor_get(par->codec_id);
    if (desc && desc->long_name) return desc->long_name;
    return "";
}

// ─── Chapter probe ──────────────────────────────────────────────────
// Reads container-level chapter markers (MKV chapters, MP4 chpl, …) and
// returns a flat String[] of [startMs, endMs, title] triples in playback
// order, or null when the source has no chapters / can't be parsed.

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_core_thumbnail_NativeThumbnailExtractor_nativeProbeChapters(
    JNIEnv* env, jclass /*clazz*/, jobject bridge) {

    if (!bridge) { LOGE("chapters: bridge is null"); return nullptr; }
    JniFile file(env, bridge);
    if (!file.ok()) { LOGE("chapters: JniFile init failed"); return nullptr; }

    IOStats stats;
    IOBridge ioBridge{&file, &stats};
    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(g_avioBufSize));
    if (!ioBuf) return nullptr;
    AVIOContext* avio = avio_alloc_context(ioBuf, g_avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);
    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;
    // Chapters live in the container header — no stream analysis needed.
    fmtCtx->probesize = 1024 * 1024;
    fmtCtx->max_analyze_duration = 500000;

    if (avformat_open_input(&fmtCtx, "", nullptr, nullptr) != 0) {
        LOGE("chapters: avformat_open_input failed");
        return nullptr;
    }

    unsigned nb = fmtCtx->nb_chapters;
    if (nb == 0 || !fmtCtx->chapters) {
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    std::vector<std::string> kv;
    kv.reserve(nb * 3);
    for (unsigned i = 0; i < nb; i++) {
        AVChapter* ch = fmtCtx->chapters[i];
        if (!ch) continue;
        int64_t startMs = av_rescale_q(ch->start, ch->time_base, AVRational{1, 1000});
        int64_t endMs   = av_rescale_q(ch->end,   ch->time_base, AVRational{1, 1000});
        AVDictionaryEntry* t = av_dict_get(ch->metadata, "title", nullptr, 0);
        kv.push_back(std::to_string(startMs));
        kv.push_back(std::to_string(endMs));
        kv.emplace_back(t && t->value ? t->value : "");
    }

    avformat_close_input(&fmtCtx);

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(kv.size()),
                                           strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;

    for (size_t i = 0; i < kv.size(); i++) {
        jstring js = env->NewStringUTF(kv[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }

    LOGD("chapters: %u found", nb);
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_core_thumbnail_NativeThumbnailExtractor_nativeProbeMediaInfo(
    JNIEnv* env, jclass /*clazz*/, jobject bridge) {

    if (!bridge) { LOGE("probe: bridge is null"); return nullptr; }
    JniFile file(env, bridge);
    if (!file.ok()) { LOGE("probe: JniFile init failed"); return nullptr; }

    IOStats stats;
    IOBridge ioBridge{&file, &stats};
    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(g_avioBufSize));
    if (!ioBuf) return nullptr;
    AVIOContext* avio = avio_alloc_context(ioBuf, g_avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);
    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;
    fmtCtx->probesize = 5 * 1024 * 1024;
    fmtCtx->max_analyze_duration = 5000000;

    if (avformat_open_input(&fmtCtx, "", nullptr, nullptr) != 0) {
        LOGE("probe: avformat_open_input failed");
        return nullptr;
    }
    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        avformat_close_input(&fmtCtx);
        LOGE("probe: find_stream_info failed");
        return nullptr;
    }

    std::vector<std::string> kv;

    // ── Container ──
    if (fmtCtx->iformat && fmtCtx->iformat->name)
        probePut(kv, "format", fmtCtx->iformat->name);
    if (fmtCtx->iformat && fmtCtx->iformat->long_name)
        probePut(kv, "format_long", fmtCtx->iformat->long_name);
    if (fmtCtx->bit_rate > 0)
        probePut(kv, "bitrate", std::to_string(fmtCtx->bit_rate));

    // Stream counts
    int videoTrackCount = 0, audioTrackCount = 0, subTrackCount = 0;
    for (unsigned int i = 0; i < fmtCtx->nb_streams; i++) {
        if (!fmtCtx->streams[i] || !fmtCtx->streams[i]->codecpar) continue;
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) videoTrackCount++;
        else if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) audioTrackCount++;
        else if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) subTrackCount++;
    }
    if (videoTrackCount > 0) probePut(kv, "video_tracks", std::to_string(videoTrackCount));
    if (audioTrackCount > 0) probePut(kv, "audio_tracks", std::to_string(audioTrackCount));
    if (subTrackCount > 0) probePut(kv, "subtitle_tracks", std::to_string(subTrackCount));

    // ── First video stream ──
    int vIdx = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1,
                                   nullptr, 0);
    if (vIdx >= 0 && fmtCtx->streams[vIdx] && fmtCtx->streams[vIdx]->codecpar) {
        AVStream* st = fmtCtx->streams[vIdx];
        const AVCodecParameters* par = st->codecpar;
        probePut(kv, "video_codec", probeCodecName(par));
        probePut(kv, "video_codec_long", probeCodecLongName(par));

        const AVCodec* dec = avcodec_find_decoder(par->codec_id);
        if (dec && par->profile != AV_PROFILE_UNKNOWN) {
            const char* prof = av_get_profile_name(dec, par->profile);
            if (prof) probePut(kv, "video_profile", prof);
        }

        if (par->width > 0 && par->height > 0) {
            probePut(kv, "video_resolution",
                     std::to_string(par->width) + "x" + std::to_string(par->height));
        }
        if (par->bit_rate > 0)
            probePut(kv, "video_bitrate", std::to_string(par->bit_rate));

        AVRational fr = av_guess_frame_rate(fmtCtx, st, nullptr);
        if (fr.den > 0 && fr.num > 0) {
            double fps = av_q2d(fr);
            char buf[32];
            snprintf(buf, sizeof(buf), "%.2f", fps);
            probePut(kv, "video_fps", buf);
        }
        if (par->format != AV_PIX_FMT_NONE) {
            const char* pf = av_get_pix_fmt_name(
                static_cast<AVPixelFormat>(par->format));
            if (pf) probePut(kv, "video_pix_fmt", pf);

            const AVPixFmtDescriptor* desc = av_pix_fmt_desc_get(
                static_cast<AVPixelFormat>(par->format));
            if (desc && desc->comp[0].depth > 0) {
                probePut(kv, "video_bit_depth", std::to_string(desc->comp[0].depth) + "-bit");
            }
        }
        if (par->color_primaries == AVCOL_PRI_BT2020 || par->color_trc == AVCOL_TRC_SMPTE2084 || par->color_trc == AVCOL_TRC_ARIB_STD_B67) {
            probePut(kv, "video_hdr", (par->color_trc == AVCOL_TRC_ARIB_STD_B67) ? "HLG" : "HDR10");
        }
    }

    // ── First audio stream ──
    int aIdx = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1,
                                   nullptr, 0);
    if (aIdx >= 0 && fmtCtx->streams[aIdx] && fmtCtx->streams[aIdx]->codecpar) {
        const AVCodecParameters* par = fmtCtx->streams[aIdx]->codecpar;
        probePut(kv, "audio_codec", probeCodecName(par));
        probePut(kv, "audio_codec_long", probeCodecLongName(par));
        if (par->bit_rate > 0)
            probePut(kv, "audio_bitrate", std::to_string(par->bit_rate));
        if (par->sample_rate > 0)
            probePut(kv, "audio_sample_rate", std::to_string(par->sample_rate));
        if (par->ch_layout.nb_channels > 0) {
            probePut(kv, "audio_channels",
                     std::to_string(par->ch_layout.nb_channels));
            char layoutBuf[64] = {0};
            av_channel_layout_describe(&par->ch_layout, layoutBuf, sizeof(layoutBuf));
            if (layoutBuf[0] != '\0') {
                probePut(kv, "audio_layout", layoutBuf);
            }
        }
        if (fmtCtx->streams[aIdx]->metadata) {
            AVDictionaryEntry* aLang = av_dict_get(fmtCtx->streams[aIdx]->metadata, "language", nullptr, 0);
            if (aLang && aLang->value) {
                probePut(kv, "audio_language", aLang->value);
            }
        }
    }

    avformat_close_input(&fmtCtx);

    if (kv.empty()) return nullptr;

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(kv.size()),
                                           strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;

    for (size_t i = 0; i < kv.size(); i++) {
        jstring js = env->NewStringUTF(kv[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }

    LOGD("probe: %zu key/value pairs", kv.size() / 2);
    return arr;
}
