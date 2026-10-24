#include "NetworkIo.h"

#include <cerrno>
#include <cinttypes>
#include <cstdarg>
#include <cstring>
#include <strings.h>

extern "C" {
#include <libavutil/base64.h>
}
#include <libavutil/intreadwrite.h>

#include "HlsMasterTrim.h"

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

namespace {

bool isHttpUrl(const char* url) {
    return url && (strncasecmp(url, "http://", 7) == 0 ||
                   strncasecmp(url, "https://", 8) == 0);
}

/** Host component only, no userinfo/port/brackets. Empty when the URL has none. */
std::string hostOf(const char* url) {
    char proto[16] = {};
    char auth[8] = {};
    char host[256] = {};
    char path[8] = {};
    int port = -1;
    av_url_split(proto, sizeof(proto), auth, sizeof(auth), host, sizeof(host),
                 &port, path, sizeof(path), url);
    return host;
}

bool hasQuery(const char* url) {
    const char* query = strchr(url, '?');
    if (!query) return false;
    const char* fragment = strchr(url, '#');
    return !fragment || query < fragment;
}

/** Query including the leading '?', excluding any fragment. Empty for a bare '?'. */
std::string queryOf(const std::string& url) {
    const size_t query = url.find('?');
    if (query == std::string::npos) return {};
    const size_t fragment = url.find('#', query);
    const size_t end = fragment == std::string::npos ? url.size() : fragment;
    return end > query + 1 ? url.substr(query, end - query) : std::string();
}

} // namespace

void setPrimaryUrl(FfmpegPlayerContext* ctx, const std::string& url) {
    if (!ctx) return;
    ctx->primaryHost = hostOf(url.c_str());
    ctx->primaryQuery = queryOf(url);
    ctx->primaryUrl = url;
    ctx->trimMasterPending.store(true);
    // Never log primaryQuery: it is the stream's signed token.
    LOGI("Web source host=%s signed_query=%s", ctx->primaryHost.c_str(),
         ctx->primaryQuery.empty() ? "no" : "yes");
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
    add("reconnect_on_network_error", "1");
    add("reconnect_on_http_error", "408,429,5xx");
    // http.c backs off 0/1/3/7 s and gives up once the next delay exceeds this (~11 s total).
    add("reconnect_delay_max", "8");
    // reconnect_streamed is intentionally off (FFmpeg default): without a Content-Length,
    // http.c cannot tell a finished chunked body from a dropped connection, so it reconnects
    // on every clean EOF and, against a Range-ignoring server (Cloudflare), loops until EIO.

    for (const auto& kv : ctx->ioDefaults) {
        av_dict_set(opts, kv.first.c_str(), kv.second.c_str(), 0);
    }
    // Open-dict only: HLS accepts segments with any extension, and retries a failed segment.
    av_dict_set(opts, "extension_picky", "0", 0);
    av_dict_set(opts, "seg_max_retry", "3", 0);
}

namespace {

/**
 * Skips a PNG header glued in front of a TS segment (hotlink-filter workaround on
 * some CDNs). mpegts re-syncs on the prefix but latches onto stray 0x47 bytes, so
 * the PMT can be missed until after find_stream_info and the video stream is lost.
 * Returns the number of bytes skipped, or 0 when the stream is left untouched.
 */
int stripLeadingPng(AVIOContext* pb) {
    static const uint8_t kPngSignature[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    uint8_t head[8];
    if (avio_read(pb, head, sizeof(head)) != static_cast<int>(sizeof(head)) ||
        memcmp(head, kPngSignature, sizeof(head)) != 0) {
        avio_seek(pb, 0, SEEK_SET);
        return 0;
    }

    int64_t skipped = sizeof(head);
    for (int chunk = 0; chunk < 64; ++chunk) {
        uint8_t hdr[8];
        if (avio_read(pb, hdr, sizeof(hdr)) != static_cast<int>(sizeof(hdr))) break;
        skipped += sizeof(hdr);
        const uint32_t len = AV_RB32(hdr);
        if (len > (1u << 20)) break;
        if (memcmp(hdr + 4, "IEND", 4) == 0) {
            if (avio_skip(pb, 4) < 0) break;  // IEND CRC
            skipped += 4;
            uint8_t sync = 0;
            if (avio_read(pb, &sync, 1) == 1 && sync == 0x47) {
                avio_seek(pb, -1, SEEK_CUR);   // leave the first TS packet unread
#ifndef NDEBUG
                LOGD("Skipped %" PRId64 "-byte PNG prefix before TS", skipped);
#endif
                return static_cast<int>(skipped);
            }
            break;
        }
        if (avio_skip(pb, static_cast<int64_t>(len) + 4) < 0) break;
        skipped += static_cast<int64_t>(len) + 4;
    }

    avio_seek(pb, 0, SEEK_SET);
    return 0;
}

} // namespace

namespace {

/**
 * Replaces the just-opened primary playlist with a one-variant copy when it is an HLS master.
 *
 * hls.c probes every variant during avformat_open_input; see HlsMasterTrim.h. The trimmed text
 * is served through a `data:` URL, which keeps the AVIOContext backed by a URLContext so hls.c
 * does not disable its persistent-connection handling ("custom io_open" gate). Relative URIs
 * still resolve against the original URL because avformat keeps it as the context URL.
 *
 * Anything that is not a clearly-detected master is handed back untouched, byte for byte.
 *
 * @param s — format context owning the open
 * @param ctx — player context (ioDefaults carries whitelist, headers and timeouts)
 * @param pb — in: the opened playlist; out: the playlist to use
 * @param url — URL that was opened
 * @return 0, or the AVERROR of a failed re-open of [url] (then *pb is null)
 */
int trimPrimaryMaster(AVFormatContext* s, FfmpegPlayerContext* ctx, AVIOContext** pb,
                      const char* url) {
    constexpr int kPeekBytes = 8192;                 // fits the 32 KiB avio buffer: seek-back is free
    constexpr size_t kMaxMasterBytes = 1u << 20;

    auto reopen = [&]() -> int {
        avio_closep(pb);
        AVDictionary* o = nullptr;
        for (const auto& kv : ctx->ioDefaults) av_dict_set(&o, kv.first.c_str(), kv.second.c_str(), 0);
        const int r = avio_open2(pb, url, AVIO_FLAG_READ, &s->interrupt_callback, &o);
        av_dict_free(&o);
        return r;
    };

    uint8_t head[kPeekBytes];
    const int got = avio_read(*pb, head, sizeof(head));
    if (got <= 0) return reopen();  // let the normal open path surface the real error
    if (!hls_trim::looksLikeMaster(reinterpret_cast<const char*>(head), static_cast<size_t>(got))) {
        return avio_seek(*pb, 0, SEEK_SET) < 0 ? reopen() : 0;
    }

    std::string text(reinterpret_cast<const char*>(head), static_cast<size_t>(got));
    uint8_t chunk[16384];
    bool complete = false;
    while (text.size() <= kMaxMasterBytes) {
        const int n = avio_read(*pb, chunk, sizeof(chunk));
        if (n == AVERROR_EOF) { complete = true; break; }
        if (n < 0) break;
        text.append(reinterpret_cast<const char*>(chunk), static_cast<size_t>(n));
    }
    if (!complete || text.size() > kMaxMasterBytes) return reopen();

    int variants = 0;
    const std::string trimmed = hls_trim::trimMasterToOneVariant(text, &variants);
    const std::string& body = trimmed.empty() ? text : trimmed;

    std::string b64(AV_BASE64_SIZE(body.size()), '\0');
    if (!av_base64_encode(&b64[0], static_cast<int>(b64.size()),
                          reinterpret_cast<const uint8_t*>(body.data()), static_cast<int>(body.size()))) {
        return reopen();
    }
    b64.resize(strlen(b64.c_str()));
    const std::string dataUrl = "data:application/vnd.apple.mpegurl;base64," + b64;

    AVDictionary* o = nullptr;
    for (const auto& kv : ctx->ioDefaults) av_dict_set(&o, kv.first.c_str(), kv.second.c_str(), 0);
    AVIOContext* mem = nullptr;
    const int r = avio_open2(&mem, dataUrl.c_str(), AVIO_FLAG_READ, &s->interrupt_callback, &o);
    av_dict_free(&o);
    if (r < 0) return reopen();

    avio_closep(pb);
    *pb = mem;
    if (!trimmed.empty()) LOGI("HLS master: probing 1 of %d variants", variants);
    return 0;
}

} // namespace

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

    // HLS resolves relative children against the playlist URL; RFC 3986 drops the
    // playlist's query, so signed variants/segments/keys would 403. Re-attach it for
    // same-host http(s) children, mirroring ExoPlayer's ProtocolRoutingDataSource.
    const char* requestUrl = url;
    std::string enriched;
    if (ctx && isHttpUrl(url) && !ctx->primaryQuery.empty() && !ctx->primaryHost.empty() &&
        !hasQuery(url) &&
        strcasecmp(hostOf(url).c_str(), ctx->primaryHost.c_str()) == 0) {
        enriched = url;
        const size_t fragment = enriched.find('#');
        if (fragment == std::string::npos) {
            enriched += ctx->primaryQuery;
        } else {
            enriched.insert(fragment, ctx->primaryQuery);
        }
        requestUrl = enriched.c_str();
    }

    int ret = avio_open2(pb, requestUrl, flags, &s->interrupt_callback, opts);
    av_dict_free(&local);
    if (ret >= 0 && ctx && ctx->isWebSource) {
        stripLeadingPng(*pb);
        // One-shot: only the first open of the user's URL can be a master; later opens of the
        // same URL are playlist reloads and must go straight through.
        if (ctx->trimMasterPending.load() && ctx->primaryUrl == url &&
            ctx->trimMasterPending.exchange(false)) {
            ret = trimPrimaryMaster(s, ctx, pb, url);
        }
    }
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
