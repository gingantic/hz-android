package com.rhnxdev.hzplayer.domain.player

/**
 * Supported playback engine. The type is load-bearing: it tags the active
 * engine in UI/debug and keys the surface rebuild in
 * [com.rhnxdev.hzplayer.presentation.player.PlayerSurface], and the
 * `Map<EngineType, IPlayerEngine>` multibinding (see [di.PlayerEngineModule]) lets
 * the Settings engine selector add a backend without touching the call sites.
 */
enum class EngineType {
    EXO_PLAYER,

    /**
     * Same ExoPlayer pipeline, but the FFmpeg software renderers are indexed
     * before the MediaCodec ones (Media3 extension-prefer semantics), forcing
     * every FFmpeg-supported codec through software decode. MediaCodec stays
     * as fallback for formats FFmpeg doesn't claim.
     */
    FFMPEG,
}
