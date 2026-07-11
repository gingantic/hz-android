
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
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureState
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureCallbacks
import com.rhnxdev.hzplayer.presentation.player.components.playerGestures
import com.rhnxdev.hzplayer.presentation.player.components.SpeedSelectionDialog
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

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onMinimize: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val orientationMode by viewModel.orientationMode.collectAsStateWithLifecycle()
    val floatingEnabled by viewModel.backgroundPlay.collectAsStateWithLifecycle()
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

    // Seek + slide + hold-speed cue state, written by the gesture loop and read by
    // the indicators / LaunchedEffects below.
    val gestureState = remember { PlayerGestureState() }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSubtitleBrowser by remember { mutableStateOf(false) }
    var showSubtitleStyleDialog by remember { mutableStateOf(false) }
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showUnlockOverlay by remember { mutableStateOf(false) }
    var hudInteractionTick by remember { mutableLongStateOf(0L) }
    var holdGainedMs by remember { mutableLongStateOf(0L) }
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
        )
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

    // Auto-hide the indicators after the last gesture. Use a tick so re-showing
    // while already visible (e.g. double-tap twice) re-arms the timer instead of
    // leaving the pill stuck.
    LaunchedEffect(gestureState.seekShowTick) {
        if (gestureState.seekShowTick > 0 && !gestureState.isDragSeeking) {
            delay(1200)
            gestureState.seekVisible = false
            gestureState.seekDelta = 0L
        }
    }

    LaunchedEffect(gestureState.slideShowCount) {
        if (gestureState.slideVisible) {
            delay(1000)
            gestureState.slideVisible = false
        }
    }

    // Accumulate real time while hold-to-speed is engaged. 2x → 1 extra
    // second per real second (2x - 1 = gained ratio).
    LaunchedEffect(gestureState.isHoldSpeeding) {
        if (!gestureState.isHoldSpeeding) {
            holdGainedMs = 0L
            return@LaunchedEffect
        }
        while (gestureState.isHoldSpeeding) {
            delay(250)
            holdGainedMs += (250 * (2f - 1)).toLong()
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
                        onMinimize = if (floatingEnabled) onMinimize else null,
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
                    visible = gestureState.isHoldSpeeding,
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
                    isDragSeeking = gestureState.isDragSeeking,
                    seekDelta = gestureState.seekDelta,
                    seekVisible = gestureState.seekVisible,
                    isSeekForward = gestureState.isSeekForward,
                )

                // Slide indicator (brightness / volume)
                SlideIndicator(
                    value = gestureState.slideValue,
                    type = gestureState.slideType,
                    visible = gestureState.slideVisible,
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

                // Resume confirmation prompt (shown when resume mode = ASK and a saved
                // position exists for the opened media).
                uiState.pendingResume?.let { pending ->
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
}