#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/pixdesc.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

#include <cstdio>
#include <vector>
#include <cstring>
#include <chrono>
#include <algorithm>
#include <cinttypes>
#include <string>

#define LOG_TAG "ThumbIO"
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

public:
    JniFile(JNIEnv* e, jobject bridge)
        : env_(e), bridge_(e->NewGlobalRef(bridge)) {
        jclass cls = env_->GetObjectClass(bridge_);
        readAtMid_ = env_->GetMethodID(cls, "readAt", "(J[BI)I");
        getSizeMid_ = env_->GetMethodID(cls, "getSize", "()J");
    }

    ~JniFile() override {
        env_->DeleteGlobalRef(bridge_);
    }

    int64_t read(uint8_t* buf, int64_t size) override {
        jbyteArray jbuf = env_->NewByteArray(static_cast<jsize>(size));
        if (!jbuf) return -1;

        jint n = env_->CallIntMethod(bridge_, readAtMid_, pos_, jbuf,
                                     static_cast<jint>(size));
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            env_->DeleteLocalRef(jbuf);
            return -1;
        }

        if (n > 0) {
            env_->GetByteArrayRegion(jbuf, 0, n,
                                     reinterpret_cast<jbyte*>(buf));
            pos_ += n;
        }
        env_->DeleteLocalRef(jbuf);

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
        if (cachedSize_ < 0) {
            cachedSize_ = env_->CallLongMethod(bridge_, getSizeMid_);
            if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
                cachedSize_ = 0;
            }
        }
        return cachedSize_;
    }

    bool ok() const override { return bridge_ != nullptr; }
};

// ─── I/O instrumentation ────────────────────────────────────────────

struct IOStats {
    int64_t readCalls = 0;
    int64_t seekCalls = 0;
    int64_t totalBytes = 0;
    int64_t largestRead = 0;
    int64_t currentPos = 0;

    struct Chunk { int64_t start, end; };
    std::vector<Chunk> chunks;
};

static int g_avioBufSize = 256 * 1024; // 256 KB

struct IOBridge {
    RandomAccessFile* file;
    IOStats* stats;
};

static int io_read(void* opaque, uint8_t* buf, int bufSize) {
    auto* io = static_cast<IOBridge*>(opaque);
    int64_t offset = io->stats->currentPos;
    int64_t n = io->file->read(buf, bufSize);
    if (n > 0) {
        io->stats->readCalls++;
        io->stats->totalBytes += n;
        io->stats->largestRead = std::max(io->stats->largestRead, n);
        io->stats->chunks.push_back({offset, offset + n - 1});
        io->stats->currentPos += n;
        LOGD("READ  offset=%" PRId64 " size=%" PRId64, offset, n);
    }
    return static_cast<int>(n);
}

static int64_t io_seek(void* opaque, int64_t offset, int whence) {
    auto* io = static_cast<IOBridge*>(opaque);
    if (whence == AVSEEK_SIZE) {
        return io->file->size();
    }
    io->stats->seekCalls++;
    LOGD("SEEK  offset=%" PRId64 " whence=%d", offset, whence);
    int64_t newPos = io->file->seek(offset, whence);
    if (newPos >= 0) io->stats->currentPos = newPos;
    return newPos;
}

// ─── RGBA ───────────────────────────────────────────────────────────

static std::vector<uint8_t> frameToRgba(AVFrame* frame, int& outW, int& outH) {
    int w = frame->width;
    int h = frame->height;

    int numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGBA, w, h, 1);
    std::vector<uint8_t> rgba(numBytes);

    AVFrame* tmp = av_frame_alloc();
    av_image_fill_arrays(tmp->data, tmp->linesize, rgba.data(),
                         AV_PIX_FMT_RGBA, w, h, 1);
    tmp->width  = w;
    tmp->height = h;
    tmp->format = AV_PIX_FMT_RGBA;

    SwsContext* sws = sws_getContext(w, h,
        static_cast<AVPixelFormat>(frame->format),
        w, h, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!sws) {
        LOGE("sws_getContext failed");
        av_frame_free(&tmp);
        return {};
    }
    sws_scale(sws, frame->data, frame->linesize, 0, h,
              tmp->data, tmp->linesize);
    sws_freeContext(sws);
    av_frame_free(&tmp);
    outW = w;
    outH = h;
    return rgba;
}

// ─── JNI: native entry point ────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_rhnxdev_hzplayer_core_thumbnail_NativeThumbnailExtractor_extractThumbnail(
    JNIEnv* env, jclass /*clazz*/, jobject bridge,
    jfloat positionPercent, jint maxWidth) {

    if (!bridge) { LOGE("bridge is null"); return nullptr; }

    JniFile file(env, bridge);
    if (!file.ok()) { LOGE("JniFile init failed"); return nullptr; }

    IOStats stats;
    IOBridge ioBridge{&file, &stats};

    uint8_t* ioBuf = static_cast<uint8_t*>(av_malloc(g_avioBufSize));
    if (!ioBuf) { LOGE("av_malloc failed"); return nullptr; }
    AVIOContext* avio = avio_alloc_context(ioBuf, g_avioBufSize, 0,
                                           &ioBridge, io_read, nullptr, io_seek);

    AVFormatContext* fmtCtx = avformat_alloc_context();
    fmtCtx->pb = avio;

    auto t0 = std::chrono::steady_clock::now();

    if (avformat_open_input(&fmtCtx, "", nullptr, nullptr) != 0) {
        LOGE("avformat_open_input via AVIO failed");
        // avformat_open_input error: fmtCtx and pb need manual cleanup
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        avformat_free_context(fmtCtx);
        return nullptr;
    }
    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        avformat_close_input(&fmtCtx); // frees avio + ioBuf
        return nullptr;
    }

    int si = -1;
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++)
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            si = static_cast<int>(i); break;
        }
    if (si == -1) {
        LOGE("no video stream found");
        avformat_close_input(&fmtCtx);
        return nullptr;
    }

    // ── Seek to target timestamp ──
    AVStream* st = fmtCtx->streams[si];
    int64_t targetUs = static_cast<int64_t>(fmtCtx->duration *
                                            static_cast<double>(positionPercent));
    int64_t targetTs = av_rescale_q(targetUs, AV_TIME_BASE_Q, st->time_base);

    if (av_seek_frame(fmtCtx, si, targetTs, AVSEEK_FLAG_BACKWARD) < 0) {
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
    int found = 0;

    while (av_read_frame(fmtCtx, pkt) >= 0 && !found) {
        if (pkt->stream_index != si) { av_packet_unref(pkt); continue; }
        if (avcodec_send_packet(dec, pkt) < 0) { av_packet_unref(pkt); continue; }
        while (true) {
            int ret = avcodec_receive_frame(dec, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;

            if (frame->pts >= targetTs) {
                // We got our frame — transfer ownership
                foundFrame = av_frame_clone(frame);
                if (foundFrame) found = 1;
            }
            av_frame_unref(frame);
        }
        av_packet_unref(pkt);
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
        std::vector<uint8_t> rgba = frameToRgba(foundFrame, outW, outH);

        if (!rgba.empty() && outW == dstW && outH == dstH) {
            // Create Bitmap via JNI
            jclass bmpCls = env->FindClass("android/graphics/Bitmap");
            jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
            if (bmpCls && cfgCls) {
                jmethodID valueOf = env->GetStaticMethodID(cfgCls, "valueOf",
                    "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
                jstring cfgName = env->NewStringUTF("ARGB_8888");
                jobject cfg = env->CallStaticObjectMethod(cfgCls, valueOf, cfgName);
                env->DeleteLocalRef(cfgName);

                jmethodID createBmp = env->GetStaticMethodID(bmpCls, "createBitmap",
                    "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
                jobject bitmap = env->CallStaticObjectMethod(bmpCls, createBmp,
                                                             dstW, dstH, cfg);

                if (bitmap) {
                    // Copy RGBA pixels
                    AndroidBitmapInfo info;
                    if (AndroidBitmap_getInfo(env, bitmap, &info) == 0) {
                        void* pixels = nullptr;
                        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                            uint8_t* src = rgba.data();
                            uint8_t* dst = static_cast<uint8_t*>(pixels);
                            size_t rowBytes = static_cast<size_t>(dstW) * 4;
                            for (int y = 0; y < dstH; y++) {
                                memcpy(dst, src, rowBytes);
                                src += rowBytes;
                                dst += info.stride;
                            }
                            AndroidBitmap_unlockPixels(env, bitmap);
                        }
                    }
                    // Create a global ref so the Bitmap survives return
                    resultBitmap = env->NewGlobalRef(bitmap);
                    env->DeleteLocalRef(bitmap);
                }
            }
        }
        av_frame_free(&foundFrame);
    } else {
        LOGD("no frame found at %.0f%%", positionPercent * 100.0);
    }

    // ── I/O statistics ──
    auto t1 = std::chrono::steady_clock::now();
    int elapsedMs = static_cast<int>(
        std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());

    std::sort(stats.chunks.begin(), stats.chunks.end(),
              [](auto& a, auto& b) { return a.start < b.start; });
    std::vector<IOStats::Chunk> merged;
    for (auto& r : stats.chunks) {
        if (merged.empty()) {
            merged.push_back(r);
        } else {
            auto& last = merged.back();
            if (r.start <= last.end + 1) {
                last.end = std::max(last.end, r.end);
            } else {
                merged.push_back(r);
            }
        }
    }
    int64_t uniqueBytes = 0;
    for (auto& r : merged) uniqueBytes += (r.end - r.start + 1);

    LOGD("===== Thumbnail I/O Statistics =====");
    LOGD("Duration: %d ms", elapsedMs);
    LOGD("AVIO buffer: %d", g_avioBufSize);
    LOGD("Read calls: %" PRId64, stats.readCalls);
    LOGD("Seek calls: %" PRId64, stats.seekCalls);
    LOGD("Total bytes read: %.2f MB", stats.totalBytes / (1024.0 * 1024.0));
    LOGD("Unique bytes: %.2f MB", uniqueBytes / (1024.0 * 1024.0));
    LOGD("Largest read: %" PRId64, stats.largestRead);
    LOGD("Average read: %" PRId64 " bytes",
         stats.readCalls > 0 ? stats.totalBytes / stats.readCalls : 0);

    double overlap = stats.totalBytes > 0
        ? (1.0 - static_cast<double>(uniqueBytes) / stats.totalBytes) * 100.0
        : 0.0;
    LOGD("Overlap: %.1f %%", overlap);
    LOGD("Regions accessed:");
    for (auto& r : merged) {
        double mbStart = r.start / (1024.0 * 1024.0);
        double mbEnd   = r.end   / (1024.0 * 1024.0);
        double sizeMB  = (r.end - r.start + 1) / (1024.0 * 1024.0);
        LOGD("  %.2f MB - %.2f MB (%.2f MB)", mbStart, mbEnd, sizeMB);
    }
    LOGD("Total unique regions: %zu", merged.size());
    LOGD("====================================");
    LOGD("Thumbnail generated: %s", resultBitmap ? "yes" : "no");

    avformat_close_input(&fmtCtx); // frees avio + ioBuf

    return resultBitmap;
}
