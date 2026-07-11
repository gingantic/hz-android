package com.rhnxdev.hzplayer.presentation.player.components

import android.view.View
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine

/** Engine-specific surface pause — routed through the render seam. */
fun pauseRenderView(engine: IPlayerEngine, view: View?) {
    view ?: return
    engine.onRenderViewPaused(view)
}

/** Engine-specific surface resume — routed through the render seam. */
fun resumeRenderView(engine: IPlayerEngine, view: View?) {
    view ?: return
    engine.onRenderViewResumed(view)
}
