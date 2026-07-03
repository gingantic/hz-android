package com.rhnxdev.hzplayer.presentation.player.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.rhnxdev.hzplayer.data.datasource.player.VlcEngine
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Compose video surface for the libVLC engine.
 *
 * Uses a [TextureView] instead of a [SurfaceView] so that we can return
 * `false` from [TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed],
 * which instructs Android to **keep the SurfaceTexture alive** in GPU memory
 * even after the activity stops.  This eliminates the black flash that occurs
 * when the user briefly switches away and returns:
 *
 * - SurfaceView: OS destroys the surface on every onStop → VLC goes black.
 * - TextureView + return false: texture stays alive → VLC keeps its last frame
 *   rendered → no black, instant resume.
 *
 * VLC lifecycle:
 * 1. **set+attach** — [VlcEngine.setTextureView] on [onSurfaceTextureAvailable]
 * 2. **soft-detach** — [VlcEngine.onSurfaceDestroyed] on [onSurfaceTextureDestroyed]
 *    (does NOT call detachViews — keeps the vout alive)
 * 3. **hard-detach** — [VlcEngine.removeSurfaceView] from lifecycle/release only
 */
@Composable
fun VlcVideoSurface(
    engine: VlcEngine,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.AUTO,
    modifier: Modifier = Modifier,
) {
    // ── Track video dimensions for aspect-ratio-preserving layout ──
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    // Revision counter bumped each time the texture becomes available.
    // Restarts the dimension-polling loop after rotation or first attach.
    var surfaceRevision by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {

                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            // Attach VLC to the fresh texture.
                            engine.setTextureView(this@apply)
                            val size = engine.getVideoSize()
                            videoWidth = size.first
                            videoHeight = size.second
                            // Bump so the polling LaunchedEffect restarts.
                            surfaceRevision++
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            engine.setWindowSize(width, height)
                        }

                        /**
                         * Returning **false** here is the key to zero-flash background:
                         * Android will NOT release the SurfaceTexture, so VLC's vout
                         * keeps its last decoded frame in GPU memory.  When the app
                         * returns, the frame is already there — no black, no re-init.
                         *
                         * We still call [VlcEngine.onSurfaceDestroyed] to update the
                         * engine's state flags (surfacesAttached / pendingPlay) so
                         * resume logic is correct.
                         */
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            videoWidth = 0
                            videoHeight = 0
                            engine.onSurfaceDestroyed()
                            // false = keep the SurfaceTexture alive (no black flash)
                            return false
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            // Called on every decoded frame — nothing needed here.
                        }
                    }

                    // Update VLC's window size whenever the layout changes (e.g. orientation changes).
                    // On layout changes, the view is measured and positioned with new screen constraints.
                    addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        val w = right - left
                        val h = bottom - top
                        if (w > 0 && h > 0) {
                            engine.setWindowSize(w, h)
                        }
                    }
                }
            },
            modifier = Modifier.videoAspectRatio(videoWidth, videoHeight, aspectRatioMode),
        )
    }

    // ── Apply forced aspect ratio to VLC engine (16:9 / 4:3) ──
    LaunchedEffect(aspectRatioMode) {
        when (aspectRatioMode) {
            AspectRatioMode.RATIO_16_9 -> engine.setAspectRatio("16:9")
            AspectRatioMode.RATIO_4_3  -> engine.setAspectRatio("4:3")
            else                       -> engine.setAspectRatio(null)
        }
    }

    // ── Poll video dimensions while playing (VLC may report size late) ──
    LaunchedEffect(surfaceRevision) {
        while (true) {
            delay(500)
            val size = engine.getVideoSize()
            if (size.first > 0 && size.second > 0 &&
                (size.first != videoWidth || size.second != videoHeight)
            ) {
                videoWidth = size.first
                videoHeight = size.second
            }
        }
    }
}

/**
 * A modifier that scales the video surface according to the chosen [AspectRatioMode].
 *
 * The child is measured with tight [targetW]×[targetH] constraints so the
 * `TextureView` renders at exactly the desired size.  Overflow is clipped by
 * the parent [Box]'s `clipToBounds` — giving a centre-crop effect.
 */
internal fun Modifier.videoAspectRatio(
    videoWidth: Int,
    videoHeight: Int,
    mode: AspectRatioMode = AspectRatioMode.AUTO,
): Modifier = this.then(
    layout { measurable, constraints ->
        if (videoWidth <= 0 || videoHeight <= 0) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        } else {
            val videoAR = videoWidth.toFloat() / videoHeight.toFloat()
            val screenW = constraints.maxWidth
            val screenH = constraints.maxHeight

            val effectiveAR = when (mode) {
                AspectRatioMode.RATIO_16_9 -> 16f / 9f
                AspectRatioMode.RATIO_4_3  -> 4f / 3f
                else                       -> videoAR
            }

            val targetW: Int
            val targetH: Int

            when (mode) {
                AspectRatioMode.AUTO,
                AspectRatioMode.RATIO_16_9,
                AspectRatioMode.RATIO_4_3 -> {
                    if (screenW.toFloat() / screenH > effectiveAR) {
                        targetH = screenH
                        targetW = (screenH * effectiveAR).roundToInt()
                            .coerceAtMost(screenW)
                    } else {
                        targetW = screenW
                        targetH = (screenW / effectiveAR).roundToInt()
                            .coerceAtMost(screenH)
                    }
                }
            }

            val tightConstraints = Constraints.fixed(targetW, targetH)
            val placeable = measurable.measure(tightConstraints)
            layout(targetW, targetH) {
                placeable.placeRelative(0, 0)
            }
        }
    },
)
