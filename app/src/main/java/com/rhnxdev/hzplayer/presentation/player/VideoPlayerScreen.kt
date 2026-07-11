
package com.rhnxdev.hzplayer.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.rhnxdev.hzplayer.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.presentation.player.components.PlaybackErrorOverlay
import com.rhnxdev.hzplayer.presentation.player.components.SeekIndicators
import com.rhnxdev.hzplayer.presentation.player.components.UnlockPill
import com.rhnxdev.hzplayer.presentation.player.components.pauseRenderView
import com.rhnxdev.hzplayer.presentation.player.components.resumeRenderView
import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.presentation.player.components.DebugOverlay
import com.rhnxdev.hzplayer.presentation.player.components.PlayerControlsOverlay
import com.rhnxdev.hzplayer.presentation.player.components.AudioSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SlideIndicator
import com.rhnxdev.hzplayer.presentation.player.components.SlideType
import com.rhnxdev.hzplayer.presentation.player.components.SpeedSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleOverlay
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.PlaylistDrawer
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSearchDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleStylingDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleFileBrowserBottomSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

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

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val orientationMode by viewModel.orientationMode.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = view.context
    val activity = remember(view) { context as? android.app.Activity }
    val window = remember(activity) { activity?.window }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Apply the user's orientation preference on enter (AUTO / portrait / landscape).
    LaunchedEffect(activity, orientationMode) {
        activity?.let { viewModel.applyOrientationMode(it, orientationMode) }
    }

    BackHandler { onBack() }

    // --- Seek indicator local state ---
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekVisible by remember { mutableStateOf(false) }
    var seekShowTick by remember { mutableLongStateOf(0L) }
    var isDragSeeking by remember { mutableStateOf(false) }
    var isSeekForward by remember { mutableStateOf(true) }

    // --- Slide indicator local state (brightness / volume) ---
    var slideVisible by remember { mutableStateOf(false) }
    var slideType by remember { mutableStateOf(SlideType.BRIGHTNESS) }
    var slideValue by remember { mutableStateOf(0f) }
    var slideShowCount by remember { mutableStateOf(0L) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSubtitleBrowser by remember { mutableStateOf(false) }
    var showSubtitleStyleDialog by remember { mutableStateOf(false) }
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showUnlockOverlay by remember { mutableStateOf(false) }
    var hudInteractionTick by remember { mutableLongStateOf(0L) }
    var isHoldSpeeding by remember { mutableStateOf(false) }
    var holdGainedMs by remember { mutableLongStateOf(0L) }
    val renderViewRef = remember { mutableStateOf<View?>(null) }
    val gestureTimerScope = rememberCoroutineScope()

    // Auto-hide the indicators after the last gesture. Use a tick so re-showing
    // while already visible (e.g. double-tap twice) re-arms the timer instead of
    // leaving the pill stuck.
    LaunchedEffect(seekShowTick) {
        if (seekShowTick > 0 && !isDragSeeking) {
            delay(1200)
            seekVisible = false
            seekDelta = 0L
        }
    }

    LaunchedEffect(slideShowCount) {
        if (slideVisible) {
            delay(1000)
            slideVisible = false
        }
    }

    // Accumulate real time while hold-to-speed is engaged. 2x → 1 extra
    // second per real second (HOLD_SPEED_MULTIPLIER - 1 = gained ratio).
    LaunchedEffect(isHoldSpeeding) {
        if (!isHoldSpeeding) {
            holdGainedMs = 0L
            return@LaunchedEffect
        }
        while (isHoldSpeeding) {
            delay(250)
            holdGainedMs += (250 * (HOLD_SPEED_MULTIPLIER - 1)).toLong()
        }
    }

    // Surface lifecycle: pause/cleanup on background, reconnect on resume.
    //
    // IMPORTANT: We hook ON_STOP / ON_START instead of ON_PAUSE / ON_RESUME so that
    // brief window-focus losses (notification shade, permission dialog, switching apps
    // for just a second) do NOT pause the player or destroy the surface. ON_PAUSE fires
    // on *any* focus change; ON_STOP only fires when the app is truly fully backgrounded.
    //
    // Both engines use TextureView whose SurfaceTexture persists across onStop()
    // (onSurfaceTextureDestroyed returns false), so there is NO surface rebuild on resume.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // App is truly fully backgrounded — pause playback now.
                    pauseRenderView(viewModel.getActiveEngine(), renderViewRef.value)
                    viewModel.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // App is returning from background — onResume below handles reconnect.
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    resumeRenderView(viewModel.getActiveEngine(), renderViewRef.value)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Reset color mode on leave
            if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                activity.window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    // ----- Immersive fullscreen -----
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        if (window == null) return@DisposableEffect onDispose {}

        val originalNavColor = window.navigationBarColor
        val originalStatusColor = window.statusBarColor
        val originalCutoutMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else {
            0
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.Black.toArgb()
        window.statusBarColor = Color.Black.toArgb()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }

        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = originalCutoutMode
                window.attributes = attrs
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.navigationBarColor = originalNavColor
            window.statusBarColor = originalStatusColor
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, view).show(
                WindowInsetsCompat.Type.systemBars()
            )
            val activity = view.context as? android.app.Activity
            if (activity != null) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Sync system bars with controls visibility
    DisposableEffect(uiState.showControls) {
        val window = (view.context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)

        if (uiState.showControls) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {}
    }

    // Auto-hide controls after 3 seconds (only if not locked), reset on HUD interaction
    LaunchedEffect(uiState.showControls, uiState.playerLocked, hudInteractionTick) {
        if (uiState.showControls && !uiState.playerLocked) {
            delay(3000)
            viewModel.onHideControls()
        }
    }

    // ── Determine which video surface to render ────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video surface — engine-agnostic. PlayerSurface picks the right native
        // view for the active engine (ExoPlayer → PlayerView; future engines →
        // their own SurfaceView/TextureView). No Media3 type leaks into this screen.
        PlayerSurface(
            engine = viewModel.getActiveEngine(),
            uiState = uiState,
            modifier = Modifier.fillMaxSize(),
            onRenderView = { renderViewRef.value = it },
        )

        // (built-in subtitle rendering used — see factory block above)
        // SubtitleOverlay removed: refine custom subtitle overlay later
        // SubtitleOverlay(
        //     cues = uiState.subtitleCueTexts,
        //     style = uiState.subtitleStyle,
        //     modifier = Modifier.fillMaxSize(),
        // )

        // Gesture overlay
        Box(
            modifier = if (!uiState.playerLocked) Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                        val isControlsVisible = viewModel.uiState.value.showControls
                        val inControlsZone = isControlsVisible &&
                            (start.y < topBarHeight || start.y > size.height - bottomBarHeight)

                        val isLeftSideEdge = start.x < size.width * 0.3f
                        val isRightSideEdge = start.x > size.width * 0.7f
                        val isMiddleArea = !isLeftSideEdge && !isRightSideEdge

                        var initialBrightness = 0f
                        var maxVolume = 0
                        var currentVolume = 0
                        if (isLeftSideEdge) {
                            val w = window
                            initialBrightness = if (w != null) {
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
                            slideValue = initialBrightness
                        } else if (isRightSideEdge) {
                            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            slideValue = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f
                        }

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
                            holdTimer = gestureTimerScope.launch {
                                delay(HOLD_SPEED_THRESHOLD_MS)
                                if (dominantDirection == null) {
                                    holdTriggered = true
                                    holdActive = true
                                    isHoldSpeeding = true
                                    prevSpeed = viewModel.uiState.value.playbackSpeed
                                    // ponytail: hold-to-speed implies watching — resume if paused
                                    if (!viewModel.uiState.value.isPlaying) viewModel.resume()
                                    viewModel.onSetSpeed(HOLD_SPEED_MULTIPLIER)
                                    // Ramp +0.2 every 10s until cap.
                                    var speed = HOLD_SPEED_MULTIPLIER
                                    rampTimer = gestureTimerScope.launch {
                                        while (true) {
                                            delay(HOLD_RAMP_INTERVAL_MS)
                                            if (speed >= HOLD_SPEED_CAP) break
                                            speed = (speed + HOLD_RAMP_STEP).coerceAtMost(HOLD_SPEED_CAP)
                                            viewModel.onSetSpeed(speed)
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
                                        val canSeek = viewModel.uiState.value.duration > 0
                                        dominantDirection = if (canSeek) DragDirection.SEEK else null
                                    }
                                }
                                if (dominantDirection != null && !dragStarted) {
                                    dragStarted = true
                                    viewModel.onHideControls()
                                }
                                when (dominantDirection) {
                                    DragDirection.ADJUST -> {
                                        if (!isMiddleArea) {
                                            val deltaNormal = -dragAccumulated.y / size.height.coerceAtLeast(1)
                                            val base = if (isLeftSideEdge) initialBrightness
                                            else (if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f)
                                            val clamped = (base + deltaNormal).coerceIn(0f, 1f)
                                            slideValue = clamped
                                            slideType = if (isLeftSideEdge) SlideType.BRIGHTNESS else SlideType.VOLUME
                                            slideVisible = true
                                            slideShowCount++
                                            if (isLeftSideEdge) {
                                                window?.attributes = window?.attributes?.apply { screenBrightness = clamped }
                                            } else {
                                                val vol = (clamped * maxVolume).toInt().coerceIn(0, maxVolume)
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                                            }
                                        }
                                    }
                                    DragDirection.SEEK -> {
                                        val canSeek = viewModel.uiState.value.duration > 0
                                        if (!canSeek) { dominantDirection = null; continue }
                                        isDragSeeking = true
                                        val durationMs = viewModel.uiState.value.duration
                                        val widthPx = size.width.coerceAtLeast(1).toFloat()
                                        val maxSwipeMs = (300_000L * viewModel.uiState.value.seekSensitivity).toLong()
                                        val ratio = dragAccumulated.x / widthPx
                                        val rawDelta = (ratio * maxSwipeMs).toLong()
                                        isSeekForward = rawDelta >= 0
                                        seekDelta = rawDelta.coerceIn(
                                            -viewModel.position.value,
                                            (durationMs - viewModel.position.value).coerceAtLeast(0L)
                                        )
                                        seekVisible = true
                                        seekShowTick++
                                        seekConsumed = true
                                    }
                                    null -> {}
                                }
                            }
                        } finally {
                            holdTimer?.cancel()
                            rampTimer?.cancel()
                            if (holdActive) viewModel.onSetSpeed(prevSpeed)
                            isHoldSpeeding = false
                            if (seekConsumed && seekDelta != 0L) viewModel.onSeekBy(seekDelta)
                            isDragSeeking = false
                            seekDelta = 0L
                            if (!holdTriggered && dominantDirection == null && !inControlsZone) {
                                // Pure tap → double-tap seek / single-tap toggle
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime <= DOUBLE_TAP_MS) {
                                    pendingSingleTap?.cancel()
                                    lastTapTime = 0L
                                    when {
                                        dir < 0 -> {
                                            isDragSeeking = false; isSeekForward = false
                                            viewModel.onSeekBy(-TAP_SEEK_MS)
                                            seekDelta = -TAP_SEEK_MS; seekVisible = true; seekShowTick++
                                        }
                                        dir > 0 -> {
                                            isDragSeeking = false; isSeekForward = true
                                            viewModel.onSeekBy(TAP_SEEK_MS)
                                            seekDelta = TAP_SEEK_MS; seekVisible = true; seekShowTick++
                                        }
                                        else -> viewModel.onPlayPause()
                                    }
                                } else {
                                    lastTapTime = now
                                    pendingSingleTap = gestureTimerScope.launch {
                                        delay(DOUBLE_TAP_MS)
                                        if (lastTapTime != 0L) {
                                            viewModel.onToggleControls()
                                            lastTapTime = 0L
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            else Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { showUnlockOverlay = true }
            },
        ) {
            // Controls overlay (hidden when locked)
            if (uiState.showControls && !uiState.playerLocked) {
                PlayerControlsOverlay(
                    uiState = uiState,
                    positionFlow = viewModel.position,
                    title = uiState.currentTitle,
                    onBack = onBack,
                    onPlayPause = viewModel::onPlayPause,
                    onSeekTo = viewModel::onSeekTo,
                    onSkipForward = viewModel::onSkipForward,
                    onSkipBackward = viewModel::onSkipBackward,
                    onSpeedClick = { showSpeedDialog = true },
                    onAudioClick = { showAudioDialog = true },
                    onSubtitleClick = { showSubtitleDialog = true },
                    onLockClick = { viewModel.onToggleLock() },
                    onAspectRatioClick = { viewModel.onAspectRatioChange(uiState.aspectRatioMode.next()) },
                    onOrientationClick = {
                        val act = view.context as? android.app.Activity
                        if (act != null) viewModel.onToggleOrientation(act)
                    },
                    onPlaylistClick = { viewModel.onTogglePlaylistDrawer() },
                    onDebugClick = if (uiState.debugMode) { { viewModel.onToggleDebugOverlay() } } else null,
                    onSkipToNext = if (uiState.videoPlaylist.isNotEmpty()) {
                        { viewModel.onPlaylistNext() }
                    } else null,
                    onSkipToPrevious = if (uiState.videoPlaylist.isNotEmpty()) {
                        { viewModel.onPlaylistPrevious() }
                    } else null,
                    onInteract = { hudInteractionTick++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Unlock pill — positioned at the bottom, no semi-transparent background
            if (uiState.playerLocked && showUnlockOverlay) {
                UnlockPill(
                    onUnlock = {
                        viewModel.onToggleLock()
                        showUnlockOverlay = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                )
            }

            // Auto-dismiss unlock overlay after 5s
            val showUnlock = showUnlockOverlay
            LaunchedEffect(showUnlock) {
                if (showUnlock) {
                    delay(5000)
                    showUnlockOverlay = false
                }
            }

            if (showSubtitleDialog) {
                SubtitleSelectionDialog(
                    subtitleTracks = uiState.subtitleTracks,
                    selectedTrackIndex = uiState.selectedSubtitleTrack,
                    onTrackSelected = viewModel::selectSubtitleTrack,
                    onDismiss = { showSubtitleDialog = false },
                    onAddExternalSubtitleClick = {
                        showSubtitleDialog = false
                        showSubtitleBrowser = true
                    },
                    subtitleDelayMs = uiState.subtitleDelayMs,
                    onSubtitleDelayChange = viewModel::onSubtitleDelayChange,
                    onStyleClick = {
                        showSubtitleDialog = false
                        showSubtitleStyleDialog = true
                    },
                    onSearchOnlineClick = {
                        showSubtitleDialog = false
                        showSubtitleSearchDialog = true
                    },
                )
            }

            if (showAudioDialog) {
                AudioSelectionDialog(
                    audioTracks = uiState.audioTracks,
                    selectedTrackIndex = uiState.selectedAudioTrack,
                    onTrackSelected = viewModel::selectAudioTrack,
                    onDismiss = { showAudioDialog = false },
                )
            }

            if (showSubtitleBrowser) {
                SubtitleFileBrowserBottomSheet(
                    videoUri = uiState.currentPlaybackUri,
                    onDismiss = { showSubtitleBrowser = false },
                    onSubtitleSelected = { uri, name ->
                        showSubtitleBrowser = false
                        viewModel.addExternalSubtitle(uri, name)
                    }
                )
            }

            if (showSubtitleStyleDialog) {
                SubtitleStylingDialog(
                    currentStyle = uiState.subtitleStyle,
                    onStyleChange = { style ->
                        viewModel.onSubtitleStyleChange(style)
                    },
                    onDismiss = { showSubtitleStyleDialog = false },
                )
            }

            if (showSubtitleSearchDialog) {
                SubtitleSearchDialog(
                    onDismiss = { showSubtitleSearchDialog = false },
                    onSubtitleDownloaded = { uri ->
                        showSubtitleSearchDialog = false
                        viewModel.addExternalSubtitle(uri)
                    },
                )
            }

            if (showSpeedDialog) {
                SpeedSelectionDialog(
                    currentSpeed = uiState.playbackSpeed,
                    onSpeedSelected = viewModel::onSetSpeed,
                    onDismiss = { showSpeedDialog = false },
                )
            }

            // Hold-to-speed visual cue (above HUD, below slide/seek indicators)
            AnimatedVisibility(
                visible = isHoldSpeeding,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Hold · ${"%.1f".format(uiState.playbackSpeed)}x  +${formatDuration(holdGainedMs)}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }

            SeekIndicators(
                positionFlow = viewModel.position,
                duration = uiState.duration,
                isDragSeeking = isDragSeeking,
                seekDelta = seekDelta,
                seekVisible = seekVisible,
                isSeekForward = isSeekForward,
            )

            // Slide indicator (brightness / volume)
            SlideIndicator(
                value = slideValue,
                type = slideType,
                visible = slideVisible,
                modifier = Modifier.fillMaxSize(),
            )

            // Playlist drawer (right side overlay)
            if (uiState.showPlaylistDrawer && uiState.videoPlaylist.isNotEmpty()) {
                PlaylistDrawer(
                    playlist = uiState.videoPlaylist,
                    currentIndex = uiState.currentPlaylistIndex,
                    onSelect = viewModel::onPlaylistSelect,
                    onDismiss = viewModel::onTogglePlaylistDrawer,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Debug overlay (stats for nerds) — floating top-right
            if (uiState.debugOverlayVisible) {
                DebugOverlay(
                    stats = uiState.debugStats,
                    onDismiss = { viewModel.onToggleDebugOverlay() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Buffering loader
            if (uiState.isLoading && uiState.errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

                        PlaybackErrorOverlay(
                errorMessage = uiState.errorMessage,
                errorKind = uiState.errorKind,
                onBack = onBack,
                onRetry = viewModel::retry,
                onDismiss = viewModel::clearError,
            )
        }
    }
}