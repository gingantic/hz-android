package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Fling speed (px/s) above which a released bar snaps in the fling direction. */
private const val SNAP_VELOCITY = 300f

/**
 * Scroll-linked visibility shared by the top and bottom bars of the main tabs.
 *
 * One fraction drives both bars so they move together. The nested-scroll connection
 * consumes the first [travelPx] of scroll, so the bars follow the finger instead of the
 * list racing them, and snaps to fully shown or hidden when the gesture ends.
 *
 * @param travelPx — scroll distance that takes the bars from shown to hidden
 */
@Stable
class HideOnScrollState internal constructor(private val travelPx: Float) {

    /** 0f = bars fully shown, 1f = fully hidden. Read it in layout/draw only. */
    var hiddenFraction by mutableFloatStateOf(0f)
        private set

    private var holds = 0

    internal fun hold() {
        holds++
    }

    internal fun release() {
        holds = (holds - 1).coerceAtLeast(0)
    }

    /** Animates the bars back to fully shown. */
    suspend fun show() = settle(0f)

    private suspend fun settle(target: Float) {
        if (hiddenFraction == target) return
        animate(
            initialValue = hiddenFraction,
            targetValue = target,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        ) { value, _ -> hiddenFraction = value }
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (holds > 0 || available.y == 0f) return Offset.Zero
            val previous = hiddenFraction
            hiddenFraction = (previous - available.y / travelPx).coerceIn(0f, 1f)
            return Offset(0f, (previous - hiddenFraction) * travelPx)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val fraction = hiddenFraction
            if (holds == 0 && fraction > 0f && fraction < 1f) {
                val velocity = consumed.y + available.y
                settle(
                    when {
                        velocity > SNAP_VELOCITY -> 0f
                        velocity < -SNAP_VELOCITY -> 1f
                        else -> if (fraction > 0.5f) 1f else 0f
                    },
                )
            }
            return Velocity.Zero
        }
    }
}

/** Creates the [HideOnScrollState] for a screen that hosts the bars. */
@Composable
fun rememberHideOnScrollState(travel: Dp = 64.dp): HideOnScrollState {
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    return remember(travelPx) { HideOnScrollState(travelPx) }
}

/** Null outside the main tabs (previews, detail screens), where bars never hide. */
val LocalHideOnScrollState = compositionLocalOf<HideOnScrollState?> { null }

/**
 * Keeps the bars visible while [hold] is true (search field open, items selected).
 */
@Composable
fun HoldBarsVisible(hold: Boolean) {
    val state = LocalHideOnScrollState.current ?: return
    DisposableEffect(state, hold) {
        if (hold) state.hold()
        onDispose { if (hold) state.release() }
    }
    LaunchedEffect(state, hold) { if (hold) state.show() }
}

/**
 * Slides a top bar up and out of the layout as [state] hides.
 *
 * The height shrinks with the bar, so content below moves up with it. Clipping keeps
 * the bar from drawing over the status-bar area while it leaves.
 *
 * @param state — null leaves the bar alone (previews, screens outside the main tabs)
 */
fun Modifier.hideTopOnScroll(state: HideOnScrollState?): Modifier {
    if (state == null) return this
    // clipToBounds must come first so it clips to the shrunk bounds, not the full bar.
    return clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * (1f - state.hiddenFraction)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }
}

/**
 * Slides a bottom bar down and out of the layout as [state] hides.
 *
 * The height shrinks with the bar, so content above grows into the freed space.
 */
fun Modifier.hideBottomOnScroll(state: HideOnScrollState): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * (1f - state.hiddenFraction)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
