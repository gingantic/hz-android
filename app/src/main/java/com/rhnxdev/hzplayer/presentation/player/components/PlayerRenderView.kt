package com.rhnxdev.hzplayer.presentation.player.components

import android.view.View
import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine

/** Engine-specific surface pause — calls into the concrete engine via the seam. */
fun pauseRenderView(engine: IPlayerEngine, view: View?) {
    view ?: return
    when (engine.engineType) {
        EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine).onRenderViewPaused(view)
    }
}

/** Engine-specific surface resume — calls into the concrete engine via the seam. */
fun resumeRenderView(engine: IPlayerEngine, view: View?) {
    view ?: return
    when (engine.engineType) {
        EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine).onRenderViewResumed(view)
    }
}
