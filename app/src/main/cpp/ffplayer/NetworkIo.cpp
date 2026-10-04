#include "NetworkIo.h"

#include <cerrno>
#include <cstdarg>

int classifyAvError(int e) {
    switch (e) {
        case AVERROR_HTTP_UNAUTHORIZED:
        case AVERROR_HTTP_FORBIDDEN:
            return ERR_AUTH;
        case AVERROR_HTTP_NOT_FOUND:
            return ERR_NOT_FOUND;
        case AVERROR_HTTP_BAD_REQUEST:
        case AVERROR_HTTP_TOO_MANY_REQUESTS:
        case AVERROR_HTTP_OTHER_4XX:
        case AVERROR_HTTP_SERVER_ERROR:
            return ERR_SERVER;
        case AVERROR_EXIT:
            return ERR_TIMEOUT;
        case AVERROR_INVALIDDATA:
        case AVERROR_DEMUXER_NOT_FOUND:
        case AVERROR_PROTOCOL_NOT_FOUND:
        case AVERROR_STREAM_NOT_FOUND:
        case AVERROR_DECODER_NOT_FOUND:
            return ERR_FORMAT;
        default:
            break;
    }
    if (e == AVERROR(ETIMEDOUT)) return ERR_TIMEOUT;
    if (e == AVERROR(ENOENT)) return ERR_NOT_FOUND;
    if (e == AVERROR(EACCES)) return ERR_AUTH;
    // Remaining errno values (incl. TLS/cert failures that surface as EIO) are network errors.
    if (e < 0 && e > -1000) return ERR_NETWORK;
    return ERR_UNKNOWN;
}

void applyWebOptions(FfmpegPlayerContext* ctx, const std::string& caFile, AVDictionary** opts) {
    ctx->ioDefaults.clear();
    auto add = [&](const char* k, const char* v) { ctx->ioDefaults.emplace_back(k, v); };

    add("protocol_whitelist", caFile.empty() ? "http,tcp,crypto,data" : "http,https,tcp,tls,crypto,data");
    if (!caFile.empty()) {
        add("tls_verify", "1");
        add("ca_file", caFile.c_str());
    }
    add("rw_timeout", "15000000");
    add("timeout", "15000000");
    add("reconnect", "1");
    add("reconnect_streamed", "1");
    add("reconnect_on_network_error", "1");
    add("reconnect_on_http_error", "408,429,5xx");
    // http.c backs off 0/1/3/7 s and gives up once the next delay exceeds this (~11 s total).
    add("reconnect_delay_max", "8");

    for (const auto& kv : ctx->ioDefaults) {
        av_dict_set(opts, kv.first.c_str(), kv.second.c_str(), 0);
    }
    // Open-dict only: HLS accepts segments with any extension, and retries a failed segment.
    av_dict_set(opts, "extension_picky", "0", 0);
    av_dict_set(opts, "seg_max_retry", "3", 0);
}

int player_io_open(AVFormatContext* s, AVIOContext** pb, const char* url, int flags,
                   AVDictionary** options) {
    auto* ctx = static_cast<FfmpegPlayerContext*>(s->opaque);
    // mov.c opens dref files with options == NULL, so fall back to a local dictionary.
    AVDictionary* local = nullptr;
    AVDictionary** opts = options ? options : &local;

    if (ctx) {
        ctx->lastIoTimeMs.store(getMonotonicTimeMs());
        // ioDefaults is the only carrier of protocol_whitelist: av_opt_set_dict2 consumes it
        // out of the open dictionary before io_open is reached.
        for (const auto& kv : ctx->ioDefaults) {
            av_dict_set(opts, kv.first.c_str(), kv.second.c_str(), AV_DICT_DONT_OVERWRITE);
        }
    }

    int ret = avio_open2(pb, url, flags, &s->interrupt_callback, opts);
    av_dict_free(&local);
    if (ctx) ctx->lastIoTimeMs.store(getMonotonicTimeMs());
    return ret;
}

#ifndef NDEBUG
static void ffmpegLogCallback(void* ptr, int level, const char* fmt, va_list vl) {
    if (level > AV_LOG_WARNING) return;
    static thread_local int printPrefix = 1;
    char line[1024];
    av_log_format_line2(ptr, level, fmt, vl, line, sizeof(line), &printPrefix);
    __android_log_write(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "FFmpeg", line);
}
#endif

void installFfmpegLogBridge() {
#ifndef NDEBUG
    av_log_set_callback(ffmpegLogCallback);
#endif
}
