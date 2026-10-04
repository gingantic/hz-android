#pragma once
#include "FfmpegPlayerContext.h"

// Numbers mirror app/.../domain/player/NativeErrorMapper.kt (NativeErrorClass).
enum NativeErrorClass {
    ERR_NONE = 0,
    ERR_NETWORK = 1,
    ERR_TIMEOUT = 2,
    ERR_AUTH = 3,
    ERR_NOT_FOUND = 4,
    ERR_SERVER = 5,
    ERR_FORMAT = 6,
    ERR_UNKNOWN = 8
};

/** Maps an FFmpeg AVERROR to a NativeErrorClass. */
int classifyAvError(int e);

/**
 * Fills ctx->ioDefaults and `opts` with the web options (whitelist, TLS trust, timeouts,
 * reconnect policy). `opts` additionally gets the HLS-only options that must not be
 * forwarded to child opens. An empty caFile means "no bundle": https is then not whitelisted.
 */
void applyWebOptions(FfmpegPlayerContext* ctx, const std::string& caFile, AVDictionary** opts);

/**
 * AVFormatContext.io_open replacement: applies ctx->ioDefaults to every playlist, segment,
 * key and dref open so TLS trust survives http->https hops. Tolerates NULL options/opaque.
 */
int player_io_open(AVFormatContext* s, AVIOContext** pb, const char* url, int flags,
                   AVDictionary** options);

/** Routes FFmpeg WARNING+ logs to logcat (tag "FFmpeg") in debug builds only. */
void installFfmpegLogBridge();
