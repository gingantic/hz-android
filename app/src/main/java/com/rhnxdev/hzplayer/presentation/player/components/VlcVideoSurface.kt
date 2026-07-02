package com.rhnxdev.hzplayer.presentation.player.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import com.rhnxdev.hzplayer.data.datasource.player.VlcEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Compose video surface for the libVLC engine.
 *
 * This component creates a [SurfaceView] and manages the
 * [IVLCVout][org.videolan.libvlc.IVLCVout] three-phase lifecycle:
 *
 * 1. **set** — `setVideoView(surfaceView)` via [VlcEngine.setSurfaceView]
 * 2. **attach** — `attachViews()` via [VlcEngine.setSurfaceView]
 * 3. **detach** — `detachViews()` via [VlcEngine.removeSurfaceView]
 *
 * Playback is automatically deferred until the surface is ready.
 *
 * @param engine The active [VlcEngine]
 * @param modifier Compose modifier
 */
@Composable
fun VlcVideoSurface(
    engine: VlcEngine,
    modifier: Modifier = Modifier,
) {
    // ── Track video dimensions for aspect-ratio-preserving layout ──
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    // ── Revision counter bumped on each surfaceCreated (increments on rotation)
    // Used as a LaunchedEffect key so the size-polling loop restarts after
    // the surface is destroyed and recreated during an orientation change.
    var surfaceRevision by remember { mutableIntStateOf(0) }

    // ── Surface lifecycle — attach/detach on create/destroy ──────
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setSurfaceView(this@apply)
                            // Query the video size after surface is attached.
                            // If still 0×0 (buffering), the polling loop will pick it up.
                            val size = engine.getVideoSize()
                            videoWidth = size.first
                            videoHeight = size.second
                            // Bump revision so LaunchedEffect restarts the poll loop
                            // after this surface was recreated (e.g. after rotation).
                            surfaceRevision++
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            engine.setWindowSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // Reset dimensions so the aspect-ratio modifier falls back
                            // to fillMaxSize while the new surface is being set up after
                            // a rotation. Without this, the old portrait geometry is used
                            // until the poll loop happens to re-run.
                            videoWidth = 0
                            videoHeight = 0
                            engine.removeSurfaceView()
                        }
                    })
                }
            },
            modifier = Modifier.videoAspectRatio(videoWidth, videoHeight),
        )
    }

    // ── Poll video dimensions while playing (VLC may report size late) ─
    // Keyed on surfaceRevision so this loop restarts after every surface
    // recreation (rotation), ensuring fresh dimensions are fetched even
    // when isLoading is already true (e.g. mid-buffering network stream).
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
 * A modifier that preserves the video's aspect ratio within
 * the available space, letterboxing as needed.
 */
internal fun Modifier.videoAspectRatio(
    videoWidth: Int,
    videoHeight: Int,
): Modifier = this.then(
    layout { measurable, constraints ->
        if (videoWidth <= 0 || videoHeight <= 0) {
            // No video dimensions yet — fill available space
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        } else {
            val aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val constrainedWidth = constraints.maxWidth
            val constrainedHeight = constraints.maxHeight

            val fittedWidth: Int
            val fittedHeight: Int

            if (constrainedWidth.toFloat() / constrainedHeight > aspectRatio) {
                // Container is wider than video — height constrained
                fittedHeight = constrainedHeight
                fittedWidth = (constrainedHeight * aspectRatio).roundToInt()
                    .coerceAtMost(constrainedWidth)
            } else {
                // Container is taller than video — width constrained
                fittedWidth = constrainedWidth
                fittedHeight = (constrainedWidth / aspectRatio).roundToInt()
                    .coerceAtMost(constrainedHeight)
            }

            val placeable = measurable.measure(constraints)
            layout(fittedWidth, fittedHeight) {
                placeable.placeRelative(0, 0)
            }
        }
    },
)
