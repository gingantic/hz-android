package com.rhnxdev.hzplayer.presentation.player

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.player.RenderViewConfig

/**
 * Renders the active engine's native view via the engine-agnostic seam. The
 * surface is keyed by [IPlayerEngine.engineType] so switching engines rebuilds it.
 * Adding a new engine needs no change here — it implements the render seam.
 */
@Composable
fun PlayerSurface(
    engine: IPlayerEngine,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    onRenderView: (View?) -> Unit,
) {
    key(engine.engineType) {
        AndroidView(
            factory = { ctx ->
                val view = engine.createRenderView(ctx, uiState.useSurfaceView)
                onRenderView(view)
                view
            },
            update = { view ->
                engine.updateRenderView(
                    view,
                    RenderViewConfig(
                        aspectRatioMode = uiState.aspectRatioMode,
                        subtitleStyle = uiState.subtitleStyle,
                    ),
                )
            },
            modifier = modifier,
        )
    }
}
