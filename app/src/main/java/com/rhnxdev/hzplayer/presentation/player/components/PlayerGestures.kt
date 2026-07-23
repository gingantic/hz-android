package com.rhnxdev.hzplayer.presentation.player.components

import android.media.AudioManager
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Duration in ms for double-tap fixed seeks (left/right thirds). */
private const val TAP_SEEK_MS = 10_000L

/** Dominant drag direction used to disambiguate vertical (brightness/volume) from horizontal (seek). */
private enum class DragDirection { SEEK, ADJUST }

/** Playback speed once hold-to-speed engages (base for the ramp below). */
private const val HOLD_SPEED_MULTIPLIER = 2f

/** Hold-to-speed ramp: +HOLD_RAMP_STEP every HOLD_RAMP_INTERVAL_MS of holding. */
private const val HOLD_RAMP_STEP = 0.2f
private const val HOLD_RAMP_INTERVAL_MS = 10_000L
// ponytail: cap keeps audio pitch sane; raise if users want faster.
private const val HOLD_SPEED_CAP = 4f

/** Hold this long in a third before 2x engages (shorter = tap/double-tap). */
private const val HOLD_SPEED_THRESHOLD_MS = 1000L

/** Ignore finger jitter below this many px before deciding drag direction. */
private const val DRAG_DEAD_ZONE_PX = 24f

/** Any movement beyond this many px immediately cancels the hold-to-speed timer
 *  (prevents 2x from triggering when the user moves their finger). */
private const val HOLD_JITTER_PX = 8f

/**
 * Mutable UI-cue state written by [playerGestures] and read by the seek / slide /
 * hold-speed indicators in the player screen. Owned via `remember` in the screen.
 */
class PlayerGestureState {
    // Seek indicator
    var seekDelta by mutableLongStateOf(0L)
    var seekVisible by mutableStateOf(false)
    var seekShowTick by mutableLongStateOf(0L)
    var isDragSeeking by mutableStateOf(false)
    var isSeekForward by mutableStateOf(true)

    // Slide indicator (brightness / volume)
    var slideVisible by mutableStateOf(false)
    var slideType by mutableStateOf(SlideType.BRIGHTNESS)
    var slideValue by mutableStateOf(0f)
    var slideShowCount by mutableLongStateOf(0L)

    // Hold-to-speed
    var isHoldSpeeding by mutableStateOf(false)
    /** Current hold-to-speed multiplier (ramps 2x→4x); drives the gain cue. */
    var holdSpeed by mutableFloatStateOf(1f)
}

/**
 * The viewModel hooks the gesture loop calls, plus value snapshots it reads.
 * Kept as a plain holder so [playerGestures] never sees the ViewModel directly.
 */
class PlayerGestureCallbacks(
    val onSetSpeed: (Float) -> Unit,
    val resume: () -> Unit,
    val onHideControls: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onToggleControls: () -> Unit,
    val onPlayPause: () -> Unit,
    /** Snapshot of the current player UI state (viewModel.uiState.value). */
    val uiState: () -> PlayerUiState,
    /** Current playback position in ms (viewModel.position.value). */
    val position: () -> Long,
    /**
     * Called when a brightness or volume slide gesture ends so the final value
     * can be persisted. [type] is [SlideType.BRIGHTNESS] or [SlideType.VOLUME];
     * [value] is normalised 0.0–1.0.
     */
    val onSlideDone: (type: SlideType, value: Float) -> Unit = { _, _ -> },
)

/**
 * The player's single touch-gesture loop: hold-to-speed, horizontal seek scrub,
 * vertical brightness/volume, and single/double-tap.
 *
 * ponytail: extracted verbatim from VideoPlayerScreen — one gesture loop so
 * hold-to-speed survives finger drag (two stacked pointerInput blocks stole the
 * stream and cancelled the hold). Body preserved exactly; only references were
 * rebound to [state] / [callbacks] / [scope].
 */
fun Modifier.playerGestures(
    state: PlayerGestureState,
    callbacks: PlayerGestureCallbacks,
    audioManager: AudioManager,
    window: Window?,
    view: View,
    scope: CoroutineScope,
): Modifier = this.pointerInput(Unit) {
    // ponytail: single gesture loop so hold-to-speed survives finger drag
    // (two stacked pointerInput blocks stole the stream and cancelled the hold)
    var lastTapTime = 0L
    var pendingSingleTap: Job? = null
    val DOUBLE_TAP_MS = 300L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val start = down.position
        val downTime = down.uptimeMillis
        val third = size.width / 3f
        val dir = when {
            start.x < third -> -1
            start.x > third * 2 -> 1
            else -> 0
        }
        val topBarHeight = 80.dp.toPx()
        val bottomBarHeight = 160.dp.toPx()
        val isControlsVisible = callbacks.uiState().showControls
        val inControlsZone = isControlsVisible &&
            (start.y < topBarHeight || start.y > size.height - bottomBarHeight)

        val isLeftSideEdge = start.x < size.width * 0.3f
        val isRightSideEdge = start.x > size.width * 0.7f
        val isMiddleArea = !isLeftSideEdge && !isRightSideEdge

        // ponytail: reset seek cue state at gesture start so previous gesture's
        // stale "seeking" flag doesn't leak into a new tap/drag. Slide cues stay
        // neutral until the ADJUST direction commits (lazy init in the drag handler).
        state.slideVisible = false
        state.seekVisible = false

        var adjustInitialized = false
        var lazyBrightness = 0f
        var lazyMaxVol = 0
        var lazyCurrVol = 0

        var dragAccumulated = Offset.Zero
        var dominantDirection: DragDirection? = null
        var holdActive = false
        var holdTriggered = false
        var prevSpeed = 1f
        var seekConsumed = false
        var dragStarted = false

        // ponytail: 2x arms only after 1s still-hold. Finger idle sends no
        // events, so a timer (not the loop) drives the delay. Drag cancels it.
        var holdTimer: Job? = null
        var rampTimer: Job? = null
        if (dir != 0 && !inControlsZone) {
            holdTimer = scope.launch {
                delay(HOLD_SPEED_THRESHOLD_MS)
                if (dominantDirection == null) {
                    holdTriggered = true
                    holdActive = true
                    state.isHoldSpeeding = true
                    state.holdSpeed = HOLD_SPEED_MULTIPLIER
                    prevSpeed = callbacks.uiState().playbackSpeed
                    // ponytail: hold-to-speed implies watching — resume if paused
                    if (!callbacks.uiState().isPlaying) callbacks.resume()
                    callbacks.onSetSpeed(HOLD_SPEED_MULTIPLIER)
                    // Ramp +0.2 every 10s until cap.
                    var speed = HOLD_SPEED_MULTIPLIER
                    rampTimer = scope.launch {
                        while (true) {
                            delay(HOLD_RAMP_INTERVAL_MS)
                            if (speed >= HOLD_SPEED_CAP) break
                            speed = (speed + HOLD_RAMP_STEP).coerceAtMost(HOLD_SPEED_CAP)
                            state.holdSpeed = speed
                            callbacks.onSetSpeed(speed)
                        }
                    }
                }
            }
        }

        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.id == down.id && it.changedToUpIgnoreConsumed() }) break
                val change = event.changes.firstOrNull { it.id == down.id && it.pressed }
                    ?: continue
                if (inControlsZone) continue
                // Net displacement from touch-start: finger jitter cancels out.
                dragAccumulated = change.position - start
                // Cancel the hold timer as soon as the finger moves more than
                // HOLD_JITTER_PX — this prevents 2x speed from firing when the
                // user moves their finger, even before the drag dead-zone is hit.
                val totalMovement = kotlin.math.sqrt(
                    dragAccumulated.x * dragAccumulated.x +
                    dragAccumulated.y * dragAccumulated.y
                )
                if (totalMovement > HOLD_JITTER_PX && holdTimer?.isActive == true) {
                    holdTimer?.cancel()
                }
                // While hold-to-speed is engaged, suppress ALL gesture processing.
                // Moving the finger during 2x hold should do nothing — no seek,
                // no brightness/volume adjust. Speed restores on finger lift (finally).
                if (holdActive) continue
                if (dominantDirection == null) {
                    val v = kotlin.math.abs(dragAccumulated.y)
                    val h = kotlin.math.abs(dragAccumulated.x)
                    if (v > DRAG_DEAD_ZONE_PX && v > h + DRAG_DEAD_ZONE_PX) {
                        dominantDirection = DragDirection.ADJUST
                    } else if (h > DRAG_DEAD_ZONE_PX && h > v + DRAG_DEAD_ZONE_PX) {
                        val canSeek = callbacks.uiState().duration > 0
                        dominantDirection = if (canSeek) DragDirection.SEEK else null
                    }
                }
                if (dominantDirection != null && !dragStarted) {
                    dragStarted = true
                    callbacks.onHideControls()
                }
                when (dominantDirection) {
                    DragDirection.ADJUST -> {
                        if (!isMiddleArea) {
                            // ponytail: lazy-init brightness/volume values only on the first
                            // ADJUST event, not at finger-down — a plain tap shouldn't read
                            // Settings.System or touch the audio stack at all.
                            if (!adjustInitialized) {
                                adjustInitialized = true
                                val w = window
                                lazyBrightness = if (w != null && isLeftSideEdge) {
                                    val b = w.attributes.screenBrightness
                                    if (b >= 0f) b else {
                                        try {
                                            Settings.System.getInt(
                                                view.context.contentResolver,
                                                Settings.System.SCREEN_BRIGHTNESS
                                            ) / 255f
                                        } catch (_: Exception) { 0.5f }
                                    }
                                } else 0.5f
                                lazyMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                lazyCurrVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            }
                            val deltaNormal = -dragAccumulated.y / size.height.coerceAtLeast(1)
                            val base = if (isLeftSideEdge) lazyBrightness
                            else (if (lazyMaxVol > 0) lazyCurrVol.toFloat() / lazyMaxVol else 0f)
                            val clamped = (base + deltaNormal).coerceIn(0f, 1f)
                            state.slideValue = clamped
                            state.slideType = if (isLeftSideEdge) SlideType.BRIGHTNESS else SlideType.VOLUME
                            state.slideVisible = true
                            state.slideShowCount++
                            if (isLeftSideEdge) {
                                window?.attributes = window?.attributes?.apply { screenBrightness = clamped }
                            } else {
                                val vol = (clamped * lazyMaxVol).toInt().coerceIn(0, lazyMaxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            }
                        }
                    }
                    DragDirection.SEEK -> {
                        val canSeek = callbacks.uiState().duration > 0
                        if (!canSeek) { dominantDirection = null; continue }
                        state.isDragSeeking = true
                        val durationMs = callbacks.uiState().duration
                        val widthPx = size.width.coerceAtLeast(1).toFloat()
                        val maxSwipeMs = (300_000L * callbacks.uiState().seekSensitivity).toLong()
                        val ratio = dragAccumulated.x / widthPx
                        val rawDelta = (ratio * maxSwipeMs).toLong()
                        state.isSeekForward = rawDelta >= 0
                        state.seekDelta = rawDelta.coerceIn(
                            -callbacks.position(),
                            (durationMs - callbacks.position()).coerceAtLeast(0L)
                        )
                        state.seekVisible = true
                        state.seekShowTick++
                        seekConsumed = true
                    }
                    null -> {}
                }
            }
        } finally {
            holdTimer?.cancel()
            rampTimer?.cancel()
            if (holdActive) callbacks.onSetSpeed(prevSpeed)
            state.isHoldSpeeding = false
            state.holdSpeed = 1f
            if (seekConsumed && state.seekDelta != 0L) callbacks.onSeekBy(state.seekDelta)
            state.isDragSeeking = false
            state.seekDelta = 0L
            // Persist volume/brightness when a slide gesture ends.
            if (adjustInitialized && dominantDirection == DragDirection.ADJUST) {
                callbacks.onSlideDone(state.slideType, state.slideValue)
            }
            if (!holdTriggered && dominantDirection == null && !inControlsZone) {
                // Pure tap → double-tap seek / single-tap toggle
                val now = System.currentTimeMillis()
                if (now - lastTapTime <= DOUBLE_TAP_MS) {
                    pendingSingleTap?.cancel()
                    lastTapTime = 0L
                    when {
                        dir < 0 -> {
                            state.isDragSeeking = false; state.isSeekForward = false
                            callbacks.onSeekBy(-TAP_SEEK_MS)
                            state.seekDelta = -TAP_SEEK_MS; state.seekVisible = true; state.seekShowTick++
                        }
                        dir > 0 -> {
                            state.isDragSeeking = false; state.isSeekForward = true
                            callbacks.onSeekBy(TAP_SEEK_MS)
                            state.seekDelta = TAP_SEEK_MS; state.seekVisible = true; state.seekShowTick++
                        }
                        else -> callbacks.onPlayPause()
                    }
                } else {
                    lastTapTime = now
                    pendingSingleTap = scope.launch {
                        delay(DOUBLE_TAP_MS)
                        if (lastTapTime != 0L) {
                            callbacks.onToggleControls()
                            lastTapTime = 0L
                        }
                    }
                }
            }
        }
    }
}
