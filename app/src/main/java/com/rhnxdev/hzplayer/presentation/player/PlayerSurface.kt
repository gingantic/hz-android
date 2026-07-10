package com.rhnxdev.hzplayer.presentation.player

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.player.RenderViewConfig

/**
 * The ONLY place that knows about concrete engine rendering views. It picks the
 * right native view for [engine.engineType] and wires lifecycle/config updates.
 *
 * To support a new engine: add a `when` branch here calling that engine's
 * `createRenderView` / `updateRenderView` (see docs/ENGINE_MODULARITY.md).
 */
@Composable
fun PlayerSurface(
    engine: IPlayerEngine,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    onRenderView: (View?) -> Unit,
) {
    val engineType = engine.engineType
    key(engineType) {
        AndroidView(
            factory = { ctx ->
                val view = when (engineType) {
                    EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine)
                        .createRenderView(ctx, uiState.useSurfaceView)
                    // EngineType.VLC -> (engine as VlcEngine).createRenderView(ctx)
                    // EngineType.MPV -> (engine as MpvEngine).createRenderView(ctx)
                }
                onRenderView(view)
                view
            },
            update = { view ->
                when (engineType) {
                    EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine).updateRenderView(
                        view,
                        RenderViewConfig(
                            aspectRatioMode = uiState.aspectRatioMode,
                            subtitleStyle = uiState.subtitleStyle,
                            hdrEnabled = uiState.hdrEnabled,
                        ),
                    )
                    // other branches delegate to their engine's updateRenderView
                }
            },
            modifier = modifier,
        )
    }
}
