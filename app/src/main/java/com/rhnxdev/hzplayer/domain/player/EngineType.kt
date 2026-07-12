package com.rhnxdev.hzplayer.domain.player

/**
 * Supported playback engine. Currently a single value — ExoPlayer — but the type
 * is load-bearing: it tags the active engine in UI/debug and keys the surface
 * rebuild in [com.rhnxdev.hzplayer.presentation.player.PlayerSurface], and the
 * `Map<EngineType, IPlayerEngine>` multibinding (see [di.PlayerEngineModule]) lets
 * the Settings engine selector add a backend without touching the call sites.
 */
enum class EngineType {
    EXO_PLAYER,
}
