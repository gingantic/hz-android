package com.rhnxdev.hzplayer.domain.player

/**
 * Supported playback engine backends.
 * User can switch between them in Settings when one engine
 * doesn't support a particular format or feature.
 */
enum class EngineType {
    /** Media3 ExoPlayer — primary/default engine, best for common formats */
    EXO_PLAYER,

    /** libVLC — alternative engine, best for exotic formats and network streams */
    VLC,
}
