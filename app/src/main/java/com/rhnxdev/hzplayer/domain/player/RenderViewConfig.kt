package com.rhnxdev.hzplayer.domain.player

import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle

/**
 * Immutable config handed to an engine when (re)configuring its video surface.
 * Keeps the presentation layer free of engine-specific resize / style APIs —
 * the engine reads these domain types and applies them to its own view.
 */
data class RenderViewConfig(
    val aspectRatioMode: AspectRatioMode,
    val subtitleStyle: SubtitleStyle,
)
