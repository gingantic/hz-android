package com.rhnxdev.hzplayer.core.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Share of finger travel that moves the content at the start of a pull; it fades to 0 at the cap. */
private const val DRAG_RESISTANCE = 0.85f

/** Spinner stays at least this long, so a refresh that ends in a few ms is still seen. */
private const val MIN_SPIN_MS = 700L

/** How long to wait for the owner to report `isRefreshing = true` after [onRefresh] runs. */
private const val START_GRACE_MS = 300L

private val TriggerDistance = 64.dp
private val MaxDistance = 150.dp
private val IndicatorSize = 40.dp

/**
 * Pull-to-refresh container: the content follows the finger and the spinner rides in the gap.
 *
 * Replaces the stock `PullToRefreshBox`, which moves only the indicator and mirrors the owner's
 * `isLoading` flag. That flag also flips for background loads (tab focus, folder open), which
 * flashed a spinner nobody asked for. Here the spinner belongs to the gesture: it shows only after
 * the user pulls past the trigger distance, stays until the owner reports done (never shorter than
 * [MIN_SPIN_MS]), then the content eases back.
 *
 * @param isRefreshing — owner's loading flag; read to know when a user-started refresh is done
 * @param onRefresh — called once when the user releases past the trigger distance
 * @param enabled — false ignores the gesture (it also stops once a refresh is under way)
 */
@Composable
fun HzPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentIsRefreshing by rememberUpdatedState(isRefreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentEnabled by rememberUpdatedState(enabled)

    val state = remember(density) {
        PullRefreshState(
            scope = scope,
            triggerPx = with(density) { TriggerDistance.toPx() },
            maxPx = with(density) { MaxDistance.toPx() },
            isEnabled = { currentEnabled },
            isOwnerRefreshing = { currentIsRefreshing },
            refresh = { currentOnRefresh() },
            tick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        )
    }

    // Without a touch gesture (TalkBack, switch access) refresh is still reachable.
    val refreshLabel = stringResource(R.string.refresh)
    Box(
        modifier = modifier
            .clipToBounds()
            .nestedScroll(state.connection)
            .semantics {
                customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true })
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = state.offset },
        ) { content() }
        PullIndicator(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Gesture and animation state for [HzPullToRefreshBox].
 *
 * Only a user drag moves [offset]; the owner's flag is never mirrored into it.
 *
 * @param triggerPx — pull distance that arms a refresh; also where the content rests while it runs
 * @param maxPx — asymptote of the rubber band, the content never travels past it
 */
@Stable
internal class PullRefreshState(
    private val scope: CoroutineScope,
    val triggerPx: Float,
    private val maxPx: Float,
    private val isEnabled: () -> Boolean,
    private val isOwnerRefreshing: () -> Boolean,
    private val refresh: () -> Unit,
    private val tick: () -> Unit,
) {
    /** Content displacement in px. Read it in layout/draw lambdas only. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** True from release-past-trigger until the content is back at rest. */
    var refreshing by mutableStateOf(false)
        private set

    /** 0 at rest, 1 at the trigger distance, above 1 while overpulling. */
    val progress: Float get() = offset / triggerPx

    private var armed = false
    private var settleJob: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Finger moving up while the content is pulled down: give the pull back first.
            if (!canDrag(source) || available.y >= 0f || offset <= 0f) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Reaching here means the list is at its top and still has leftover downward drag.
            if (!canDrag(source) || available.y <= 0f) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (refreshing || offset <= 0f) return Velocity.Zero
            release()
            return Velocity(0f, available.y) // the list must not fling while the content settles
        }
    }

    // Fling and side-effect scrolls must not start a pull, or a fling landing on the top would.
    private fun canDrag(source: NestedScrollSource) =
        isEnabled() && !refreshing && source == NestedScrollSource.UserInput

    /** Moves the content by [dy] of finger travel through the rubber band; returns the finger px used. */
    private fun drag(dy: Float): Float {
        settleJob?.cancel() // a new touch takes over from a half-finished return animation
        settleJob = null
        val step = dy * DRAG_RESISTANCE * (1f - offset / maxPx)
        val target = offset + step
        val used = if (target < 0f) dy * (offset / -step) else dy
        offset = target.coerceAtLeast(0f)

        val nowArmed = offset >= triggerPx
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) tick()
        }
        return used
    }

    private fun release() {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (offset >= triggerPx) {
                refreshing = true
                val startedAt = SystemClock.elapsedRealtime()
                refresh()
                moveTo(triggerPx, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
                awaitOwnerDone()
                delay((MIN_SPIN_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
                moveTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
                refreshing = false
            } else {
                moveTo(0f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
            }
            armed = false
        }
    }

    /**
     * Waits for the owner's flag to go true then false. It may never go true (a refresh that
     * finishes between frames, or an owner that ignores the call), so the wait for true is capped.
     */
    private suspend fun awaitOwnerDone() {
        withTimeoutOrNull(START_GRACE_MS) { snapshotFlow(isOwnerRefreshing).first { it } }
        snapshotFlow(isOwnerRefreshing).first { !it }
    }

    private suspend fun moveTo(target: Float, spec: AnimationSpec<Float>) {
        animate(initialValue = offset, targetValue = target, animationSpec = spec) { value, _ ->
            offset = value
        }
    }
}

/** Round spinner that sits in the gap above the content and turns into a spinner on release. */
@Composable
private fun PullIndicator(state: PullRefreshState, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    // Keeps turning through the return animation so the spinner never jumps back to angle 0.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(state.refreshing) {
        if (state.refreshing) {
            while (true) spin.animateTo(spin.value + 360f, tween(durationMillis = 900, easing = LinearEasing))
        } else {
            spin.snapTo(0f)
        }
    }

    Surface(
        modifier = modifier
            .size(IndicatorSize)
            .graphicsLayer {
                // Invisible until the gap can hold the whole circle, so a short pull never shows a
                // spinner sliced by the container's top edge.
                alpha = ((state.offset - size.height * 0.6f) / (size.height * 0.4f)).coerceIn(0f, 1f)
                val scale = 0.6f + 0.4f * state.progress.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
                translationY = state.offset / 2f - size.height / 2f // centred in the gap
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            // At full progress the arc already matches the spinner's, so release changes nothing.
            val p = state.progress.coerceIn(0f, 1f)
            val stroke = 3.dp.toPx()
            drawArc(
                color = color,
                startAngle = -90f + 270f * p + spin.value,
                sweepAngle = 40f + 230f * p,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun HzPullToRefreshBoxPreview() {
    HzPlayerTheme {
        HzPullToRefreshBox(isRefreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items((1..12).toList()) { ListItem(headlineContent = { Text("Item $it") }) }
            }
        }
    }
}
