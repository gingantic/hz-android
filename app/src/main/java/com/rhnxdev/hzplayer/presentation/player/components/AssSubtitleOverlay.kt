package com.rhnxdev.hzplayer.presentation.player.components

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler

/**
 * Hosts the libass-driven ASS/SSA overlay [android.view.View] above the video
 * surface. All rendering/state lives in [AssHandler] (fed by ExoPlayer's
 * extraction pipeline); this composable only needs to place the view.
 */
@Composable
fun AssSubtitleOverlay(
    assHandler: AssHandler,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { assHandler.view },
        modifier = modifier,
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
}
