
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import com.rhnxdev.hzplayer.presentation.player.components.GestureCueIndicators
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureState
import com.rhnxdev.hzplayer.presentation.player.components.UnlockPill
import com.rhnxdev.hzplayer.presentation.player.components.AssSubtitleOverlay
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
import com.rhnxdev.hzplayer.presentation.player.components.SlideType
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureCallbacks
import com.rhnxdev.hzplayer.presentation.player.components.playerGestures
import com.rhnxdev.hzplayer.presentation.player.components.SpeedSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.PlayerMoreOptionsSheet
import com.rhnxdev.hzplayer.presentation.player.components.EqualizerSheet
import com.rhnxdev.hzplayer.presentation.player.components.SleepTimerDialog
import com.rhnxdev.hzplayer.presentation.player.components.JumpToTimeDialog
import com.rhnxdev.hzplayer.presentation.player.components.ChapterSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.PlaylistDrawer
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSearchDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleFileBrowserBottomSheet
import com.rhnxdev.hzplayer.presentation.player.components.AssSubtitleOverlay
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    assHandler: AssHandler,
    onBack: () -> Unit,
    onMinimize: () -> Unit = {},
    onPlayAsAudio: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val orientationMode by viewModel.orientationMode.collectAsStateWithLifecycle()
    val floatingEnabled by viewModel.backgroundPlay.collectAsStateWithLifecycle()
    val lastVolume by viewModel.lastVolume.collectAsStateWithLifecycle()
    val lastBrightness by viewModel.lastBrightness.collectAsStateWithLifecycle()
    val saveVolumeBrightnessState by viewModel.saveVolumeBrightnessState.collectAsStateWithLifecycle()
    val disableHdr by viewModel.disableHdr.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = view.context
    val activity = remember(view) { context as? android.app.Activity }
    val window = remember(activity) { activity?.window }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Apply the user's orientation preference on enter (AUTO / portrait / landscape).
    LaunchedEffect(activity, orientationMode) {
        activity?.let { viewModel.applyOrientationMode(it, orientationMode) }
    }

    // Restore last-saved volume and brightness once per screen session if saving volume/brightness state is enabled.
    var hasRestoredVolume by remember { mutableStateOf(false) }
    var hasRestoredBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(lastVolume, saveVolumeBrightnessState) {
        if (!hasRestoredVolume && saveVolumeBrightnessState && lastVolume >= 0f) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val vol = (lastVolume * maxVol).toInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            hasRestoredVolume = true
        }
    }

    LaunchedEffect(lastBrightness, saveVolumeBrightnessState) {
        if (!hasRestoredBrightness && saveVolumeBrightnessState && lastBrightness >= 0f) {
            window?.attributes = window?.attributes?.apply { screenBrightness = lastBrightness }
            hasRestoredBrightness = true
        }
    }

    var isExiting by remember { mutableStateOf(false) }
    val handleBack = remember { { isExiting = true } }

    BackHandler {
        handleBack()
    }

    // When exiting, let one frame render the black overlay then navigate back.
    LaunchedEffect(isExiting) {
        if (isExiting) {
            // yield to let the black overlay paint over the video frame
            kotlinx.coroutines.yield()
            onBack()
        }
    }

    // Seek + slide + hold-speed cue state, written by the gesture loop and read by
    // the indicators / LaunchedEffects below.
    val gestureState = remember { PlayerGestureState() }

    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSubtitleBrowser by remember { mutableStateOf(false) }
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var showChapterDialog by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showUnlockOverlay by remember { mutableStateOf(false) }
    var hudInteractionTick by remember { mutableLongStateOf(0L) }
    val gestureCallbacks = remember(viewModel) {
        PlayerGestureCallbacks(
            onSetSpeed = viewModel::onSetSpeed,
            resume = viewModel::resume,
            onHideControls = viewModel::onHideControls,
            onSeekBy = viewModel::onSeekBy,
            onToggleControls = viewModel::onToggleControls,
            onPlayPause = viewModel::onPlayPause,
            uiState = { viewModel.uiState.value },
            position = { viewModel.position.value },
            onSlideDone = { type, value ->
                when (type) {
                    SlideType.VOLUME -> viewModel.saveLastVolume(value)
                    SlideType.BRIGHTNESS -> viewModel.saveLastBrightness(value)
                }
            },
        )
    }
    val onPlayPause = remember(viewModel) { viewModel::onPlayPause }
    val onSeekTo = remember(viewModel) { viewModel::onSeekTo }
    val onSkipForward = remember(viewModel) { viewModel::onSkipForward }
    val onSkipBackward = remember(viewModel) { viewModel::onSkipBackward }
    val onSpeedClick = remember { { showSpeedDialog = true } }
    val onAudioClick = remember { { showAudioDialog = true } }
    val onSubtitleClick = remember(viewModel) {
        {
            viewModel.refreshSubtitleTracks()
            showSubtitleDialog = true
        }
    }
    val onLockClick = remember(viewModel) { viewModel::onToggleLock }
    val onAspectRatioClick = remember(viewModel, uiState.aspectRatioMode) {
        { viewModel.onAspectRatioChange(uiState.aspectRatioMode.next()) }
    }
    val onOrientationClick = remember(viewModel, view) {
        {
            val act = view.context as? android.app.Activity
            if (act != null) viewModel.onToggleOrientation(act)
        }
    }
    val onPlaylistClick = remember(viewModel) { viewModel::onTogglePlaylistDrawer }
    val onMoreClick = remember { { showMoreSheet = true } }
    val onCycleAbRepeat = remember(viewModel) { viewModel::onCycleAbRepeat }
    val onInteract: () -> Unit = remember { { hudInteractionTick++; Unit } }
    val onMinimizeCallback = remember(floatingEnabled, onMinimize) {
        if (floatingEnabled) onMinimize else null
    }
    val onDebugClick = remember(viewModel, uiState.debugMode) {
        if (uiState.debugMode) { { viewModel.onToggleDebugOverlay() } } else null
    }
    val onSkipToNext: (() -> Unit)? = remember(viewModel, uiState.videoPlaylist.isEmpty()) {
        if (uiState.videoPlaylist.isNotEmpty()) {
            { viewModel.onPlaylistNext(); Unit }
        } else null
    }
    val onSkipToPrevious: (() -> Unit)? = remember(viewModel, uiState.videoPlaylist.isEmpty()) {
        if (uiState.videoPlaylist.isNotEmpty()) {
            { viewModel.onPlaylistPrevious(); Unit }
        } else null
    }
    val onUnlock = remember(viewModel) {
        {
            viewModel.onToggleLock()
            showUnlockOverlay = false
        }
    }
    val renderViewRef = remember { mutableStateOf<View?>(null) }
    val gestureTimerScope = rememberCoroutineScope()

    var isInPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }

    DisposableEffect(activity) {
        val compAct = activity as? androidx.activity.ComponentActivity
        if (compAct != null) {
            val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                isInPip = info.isInPictureInPictureMode
            }
            compAct.addOnPictureInPictureModeChangedListener(listener)
            onDispose {
                compAct.removeOnPictureInPictureModeChangedListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    // ── Brightness consistency across PiP ─────────────────────────────────
    // The player's brightness is a *window override*; in PiP that override
    // would keep forcing the whole screen's brightness from a tiny window.
    // Drop it on PiP entry and re-apply the exact value when expanding back.
    var pipSavedBrightness by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(isInPip) {
        val w = window ?: return@LaunchedEffect
        if (isInPip) {
            pipSavedBrightness = w.attributes.screenBrightness
            w.attributes = w.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } else if (pipSavedBrightness >= 0f) {
            w.attributes = w.attributes.apply { screenBrightness = pipSavedBrightness }
            pipSavedBrightness = -1f
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
                    // Don't pause when entering system PiP — it owns the video
                    // surface and pausing here would freeze the PiP window.
                    if (activity?.isInPictureInPictureMode == true) return@LifecycleEventObserver
                    // Don't pause when minimizing to the in-app floating window —
                    // the engine is a singleton and must keep running so the mini
                    // player can take over the surface.
                    if (viewModel.isMinimizing) return@LifecycleEventObserver
                    // App is truly fully backgrounded — pause playback and suspend
                    // the position / save tick so we don't write to DB in background.
                    pauseRenderView(viewModel.getActiveEngine(), renderViewRef.value)
                    viewModel.pause()
                    viewModel.onAppBackground()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // App is returning from background — onResume below handles reconnect.
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // Clear a stale minimize flag from a previous minimize /
                    // play-as-audio hand-off so backgrounding pauses again.
                    viewModel.isMinimizing = false
                    resumeRenderView(viewModel.getActiveEngine(), renderViewRef.value)
                    viewModel.onAppForeground()
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

    // ── HDR color-mode management ─────────────────────────────────────────
    // When HDR content renders via SurfaceView the display compositor switches
    // to HDR/wide-gamut mode automatically.  On some devices the display does
    // NOT revert when the HDR surface stops presenting (video ended, player
    // idle, or playlist transition to SDR).  The app's custom theme color then
    // appears washed-out / wrong because the UI is still composited in HDR.
    //
    // Fix: explicitly reset window.colorMode to DEFAULT whenever the player is
    // no longer actively rendering video (ENDED / IDLE / ERROR), and set
    // COLOR_MODE_HDR when the video surface is active so the system knows to
    // keep the HDR pipeline active (helps devices that need the explicit hint).
    val videoSurfaceActive = uiState.isVideoSurfaceActive
    LaunchedEffect(videoSurfaceActive, disableHdr) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return@LaunchedEffect
        val w = activity?.window ?: return@LaunchedEffect
        if (videoSurfaceActive && !disableHdr) {
            // Video surface is presenting frames in HDR mode — allow the system to use HDR
            // if the SurfaceView content is HDR.
            w.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        } else {
            // Revert / force SDR mode so the display compositor remains in standard dynamic range.
            w.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
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
            // Drop the player's brightness override so the rest of the app (and
            // the next player session) starts from system brightness again.
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
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

    // Sync system bars with controls visibility (also hide when locked)
    DisposableEffect(uiState.showControls, uiState.playerLocked) {
        val window = (view.context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)

        if (uiState.showControls && !uiState.playerLocked) {
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

        // Hide ExoPlayer's built-in SubtitleView when ASS renderer takes over.
        // Without this, both layers render simultaneously → double subtitle.
        val exoEngine = viewModel.getActiveEngine() as? com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
        DisposableEffect(assHandler, exoEngine) {
            val handler = assHandler
            handler.onAssTrackSelected = {
                exoEngine?.setExoSubtitleViewVisible(false)
            }
            // If already initialized (e.g. screen rotation), hide immediately
            if (handler.initialized) {
                exoEngine?.setExoSubtitleViewVisible(false)
            }
            onDispose {
                handler.onAssTrackSelected = null
                exoEngine?.setExoSubtitleViewVisible(true)
            }
        }

        // libass ASS/SSA overlay — drawn above the video, below the controls.
        AssSubtitleOverlay(
            assHandler = assHandler,
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture overlay
        if (!isInPip) {
            Box(
                modifier = if (!uiState.playerLocked) Modifier
                    .fillMaxSize()
                    .playerGestures(
                        state = gestureState,
                        callbacks = gestureCallbacks,
                        audioManager = audioManager,
                        window = window,
                        view = view,
                        scope = gestureTimerScope,
                    )
                else Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { showUnlockOverlay = true }
                },
            ) {
                // Controls overlay (hidden when locked)
                if (uiState.showControls && !uiState.playerLocked) {
                    key("player_controls") {
                        PlayerControlsOverlay(
                            uiState = uiState,
                            positionFlow = viewModel.position,
                            networkTrafficFlow = viewModel.networkTraffic,
                            title = uiState.currentTitle,
                            onBack = handleBack,
                            onPlayPause = onPlayPause,
                            onSeekTo = onSeekTo,
                            onScrubStart = viewModel::onScrubStart,
                            onScrub = viewModel::onScrub,
                            onScrubEnd = viewModel::onScrubEnd,
                            onSkipForward = onSkipForward,
                            onSkipBackward = onSkipBackward,
                            onSpeedClick = onSpeedClick,
                            onAudioClick = onAudioClick,
                            onSubtitleClick = onSubtitleClick,
                            onLockClick = onLockClick,
                            onAspectRatioClick = onAspectRatioClick,
                            onOrientationClick = onOrientationClick,
                            onPlaylistClick = onPlaylistClick,
                            onMoreClick = onMoreClick,
                            onMinimize = onMinimizeCallback,
                            onDebugClick = onDebugClick,
                            onSkipToNext = onSkipToNext,
                            onSkipToPrevious = onSkipToPrevious,
                            onInteract = onInteract,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Unlock pill — positioned at the bottom, no semi-transparent background
                if (uiState.playerLocked && showUnlockOverlay) {
                    key("unlock_pill") {
                        UnlockPill(
                            onUnlock = onUnlock,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 80.dp),
                        )
                    }
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
                    key("subtitle_dialog") {
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
                            onSearchOnlineClick = {
                                showSubtitleDialog = false
                                showSubtitleSearchDialog = true
                            },
                        )
                    }
                }

                if (showAudioDialog) {
                    key("audio_dialog") {
                        AudioSelectionDialog(
                            audioTracks = uiState.audioTracks,
                            selectedTrackIndex = uiState.selectedAudioTrack,
                            onTrackSelected = viewModel::selectAudioTrack,
                            onDismiss = { showAudioDialog = false },
                            audioDelayMs = uiState.audioDelayMs,
                            onAudioDelayChange = viewModel::onAudioDelayChange,
                        )
                    }
                }

                if (showSubtitleBrowser) {
                    key("subtitle_browser") {
                        SubtitleFileBrowserBottomSheet(
                            videoUri = uiState.currentPlaybackUri,
                            onDismiss = { showSubtitleBrowser = false },
                            onSubtitleSelected = { uri, name ->
                                showSubtitleBrowser = false
                                viewModel.addExternalSubtitle(uri, name)
                            }
                        )
                    }
                }

                if (showSubtitleSearchDialog) {
                    key("subtitle_search") {
                        SubtitleSearchDialog(
                            onDismiss = { showSubtitleSearchDialog = false },
                            onSubtitleDownloaded = { uri ->
                                showSubtitleSearchDialog = false
                                viewModel.addExternalSubtitle(uri)
                            },
                        )
                    }
                }

                if (showSpeedDialog) {
                    key("speed_dialog") {
                        SpeedSelectionDialog(
                            currentSpeed = uiState.playbackSpeed,
                            onSpeedSelected = viewModel::onSetSpeed,
                            onDismiss = { showSpeedDialog = false },
                        )
                    }
                }

                if (showMoreSheet) {
                    key("more_options_sheet") {
                        val equalizerInfo by viewModel.equalizerState.collectAsStateWithLifecycle()
                        PlayerMoreOptionsSheet(
                            repeatMode = uiState.repeatMode,
                            sleepTimerRemainingFlow = viewModel.sleepTimerRemainingMs,
                            chapterCount = uiState.chapters.size,
                            onSleepTimerClick = {
                                showMoreSheet = false
                                showSleepDialog = true
                            },
                            onJumpToClick = {
                                showMoreSheet = false
                                showJumpDialog = true
                            },
                            onChaptersClick = {
                                showMoreSheet = false
                                showChapterDialog = true
                            },
                            onCycleRepeat = viewModel::onCycleRepeatMode,
                            abLoopStartMs = uiState.abLoopStartMs,
                            abLoopEndMs = uiState.abLoopEndMs,
                            onCycleAbRepeat = onCycleAbRepeat,
                            equalizerEnabled = equalizerInfo.enabled,
                            onEqualizerClick = {
                                showMoreSheet = false
                                showEqualizerSheet = true
                            },
                            onPlayAsAudio = {
                                showMoreSheet = false
                                viewModel.onPlayAsAudio()
                                onPlayAsAudio()
                            },
                            onDismiss = { showMoreSheet = false },
                        )
                    }
                }

                if (showSleepDialog) {
                    key("sleep_timer_dialog") {
                        SleepTimerDialog(
                            sleepTimerRemainingFlow = viewModel.sleepTimerRemainingMs,
                            onSetTimer = viewModel::onSetSleepTimer,
                            onDismiss = { showSleepDialog = false },
                        )
                    }
                }

                if (showJumpDialog) {
                    key("jump_to_dialog") {
                        JumpToTimeDialog(
                            durationMs = uiState.duration,
                            onJump = viewModel::onSeekTo,
                            onDismiss = { showJumpDialog = false },
                        )
                    }
                }

                if (showChapterDialog && uiState.chapters.isNotEmpty()) {
                    key("chapter_dialog") {
                        ChapterSelectionDialog(
                            chapters = uiState.chapters,
                            positionFlow = viewModel.position,
                            onChapterSelected = { viewModel.onSeekTo(it.startMs) },
                            onDismiss = { showChapterDialog = false },
                        )
                    }
                }

                if (showEqualizerSheet) {
                    key("equalizer_sheet") {
                        EqualizerSheet(
                            stateFlow = viewModel.equalizerState,
                            onEnabledChange = viewModel::onEqualizerEnabledChange,
                            onBandChange = viewModel::onEqualizerBandChange,
                            onPresetSelect = viewModel::onEqualizerPresetSelect,
                            onReset = viewModel::onEqualizerReset,
                            onBassBoostChange = viewModel::onBassBoostChange,
                            onLoudnessChange = viewModel::onLoudnessGainChange,
                            onDismiss = { showEqualizerSheet = false },
                        )
                    }
                }

                // Gesture cues: seek / slide / hold-to-speed. Reads gestureState
                // internally so per-pointer-move updates recompose only this leaf.
                GestureCueIndicators(
                    state = gestureState,
                    positionFlow = viewModel.position,
                    duration = uiState.duration,
                    modifier = Modifier.fillMaxSize(),
                )

                // Playlist drawer (right side overlay)
                if (uiState.showPlaylistDrawer && uiState.videoPlaylist.isNotEmpty()) {
                    key("playlist_drawer") {
                        PlaylistDrawer(
                            playlist = uiState.videoPlaylist,
                            currentIndex = uiState.currentPlaylistIndex,
                            onSelect = viewModel::onPlaylistSelect,
                            onDismiss = viewModel::onTogglePlaylistDrawer,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Debug overlay (stats for nerds) — floating top-right
                if (uiState.debugOverlayVisible) {
                    key("debug_overlay") {
                        val debugStats by viewModel.debugStats.collectAsStateWithLifecycle()
                        DebugOverlay(
                            stats = debugStats,
                            onDismiss = { viewModel.onToggleDebugOverlay() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Buffering loader
                if (uiState.isLoading && uiState.errorMessage == null) {
                    key("buffering_loader") {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                key("error_overlay") {
                    PlaybackErrorOverlay(
                        errorMessage = uiState.errorMessage,
                        errorKind = uiState.errorKind,
                        onBack = handleBack,
                        onRetry = viewModel::retry,
                        onDismiss = viewModel::clearError,
                    )
                }

                // Resume confirmation prompt (shown when resume mode = ASK and a saved
                // position exists for the opened media).
                uiState.pendingResume?.let { pending ->
                    key("resume_dialog") {
                        AlertDialog(
                            onDismissRequest = viewModel::dismissResume,
                            title = { Text(stringResource(R.string.resume_dialog_title)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.resume_dialog_body,
                                        pending.title,
                                        com.rhnxdev.hzplayer.core.util.formatDuration(pending.resumePositionMs),
                                    ),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = viewModel::confirmResume) {
                                    Text(stringResource(R.string.resume_dialog_resume))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = viewModel::dismissResume) {
                                    Text(stringResource(R.string.resume_dialog_start_over))
                                }
                            },
                        )
                    }
                }
            }
        }

        if (isExiting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}