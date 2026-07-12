package com.rhnxdev.hzplayer.domain.player

import com.rhnxdev.hzplayer.domain.model.AspectRatioMode

/**
 * Immutable config handed to an engine when (re)configuring its video surface.
 * Keeps the presentation layer free of engine-specific resize APIs — the engine
 * reads these domain types and applies them to its own view. Subtitle styling is
 * applied through the engine's own subtitle pipeline, not this config.
 */
data class RenderViewConfig(
    val aspectRatioMode: AspectRatioMode,
)
