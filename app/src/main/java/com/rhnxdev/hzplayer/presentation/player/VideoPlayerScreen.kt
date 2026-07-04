package com.rhnxdev.hzplayer.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import android.widget.Toast
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.presentation.player.components.PlayerControlsOverlay
import com.rhnxdev.hzplayer.presentation.player.components.SeekIndicator
import com.rhnxdev.hzplayer.presentation.player.components.DragSeekIndicator
import com.rhnxdev.hzplayer.presentation.player.components.AudioSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SlideIndicator
import com.rhnxdev.hzplayer.presentation.player.components.SlideType
import com.rhnxdev.hzplayer.presentation.player.components.SpeedSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleOverlay
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSelectionDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleSearchDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleStylingDialog
import com.rhnxdev.hzplayer.presentation.player.components.SubtitleFileBrowserBottomSheet
import kotlinx.coroutines.delay

/** Duration in ms for double-tap fixed seeks (left/right thirds). */
private const val TAP_SEEK_MS = 10_000L

/** Dominant drag direction used to disambiguate vertical (brightness/volume) from horizontal (seek). */
private enum class DragDirection { SEEK, ADJUST }

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = view.context
    val activity = remember(view) { context as? android.app.Activity }
    val window = remember(activity) { activity?.window }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    BackHandler { onBack() }

    // --- Seek indicator local state ---
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekVisible by remember { mutableStateOf(false) }
    var isDragSeeking by remember { mutableStateOf(false) }

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
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    // Bug 5: whether seeking (drag-to-seek) is allowed — disabled for live/unknown streams
    val canSeek = uiState.duration > 0

    // Auto-hide the indicators after the last gesture
    LaunchedEffect(seekVisible, isDragSeeking) {
        if (seekVisible && !isDragSeeking) {
            delay(1200)
            seekVisible = false
        }
    }

    LaunchedEffect(slideShowCount) {
        if (slideVisible) {
            delay(1000)
            slideVisible = false
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
                    playerViewRef.value?.onPause()
                    viewModel.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // App is returning from background — PlayerView.onResume() below handles reconnect.
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // Reconnect ExoPlayer's PlayerView; VLC handled via TextureView callbacks.
                    playerViewRef.value?.onResume()
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
        // Video surface — ExoPlayer PlayerView (inflated dynamically with TextureView or SurfaceView surface type)
        android.util.Log.d("VideoPlayerScreen", "Rendering path: EXO_PLAYER useSurfaceView=${uiState.useSurfaceView}")
        key(uiState.useSurfaceView) {
            AndroidView(
                factory = { ctx ->
                    // Inflate from XML so the correct `app:surface_type` takes effect.
                    // PlayerView surface type is fixed at construction time — there is no
                    // programmatic setter.  TextureView keeps its last decoded frame in
                    // GPU memory through onStop(), eliminating the black flash when the
                    // user briefly switches apps and returns.
                    // SurfaceView bypasses UI composition and provides direct 10-bit HDR rendering.
                    val layoutRes = if (uiState.useSurfaceView) {
                        com.rhnxdev.hzplayer.R.layout.view_exo_player_surface
                    } else {
                        com.rhnxdev.hzplayer.R.layout.view_exo_player
                    }
                    val playerView = android.view.LayoutInflater.from(ctx)
                        .inflate(layoutRes, null, false)
                        as PlayerView
                    playerView.player = viewModel.getExoPlayer()
                    playerView.useController = false
                    // Use built-in subtitle rendering with transparent background
                    val subtitleView = playerView.subtitleView
                    if (subtitleView != null) {
                        // For HDR content, SurfaceView renders at 10-bit luminance.
                        // SDR white text appears dim against HDR video. Use a
                        // semi-transparent black background + thick outline to
                        // ensure legibility regardless of HDR peak brightness.
                        subtitleView.setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                0xFFFFFFFF.toInt(), // foregroundColor — pure white
                                0xCC000000.toInt(), // backgroundColor — solid dark (75% black)
                                0x00000000,         // windowColor — transparent
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                0xFF000000.toInt(), // edgeColor — black
                                null // typeface
                            )
                        )
                    }

                    // Force direct hardware composer secure composition to maintain 10-bit HDR colors
                    if (uiState.useSurfaceView) {
                        val surfaceView = playerView.videoSurfaceView
                        if (surfaceView is android.view.SurfaceView) {
                            surfaceView.setSecure(true)
                        }
                    }

                    playerViewRef.value = playerView
                    playerView
                },
                update = { playerView ->
                    playerView.player = viewModel.getExoPlayer()
                    // Map our AspectRatioMode to Media3 PlayerView resize modes.
                    // AUTO → fit the video within the container, preserving its original ratio.
                    // 16:9 → zoom to fill the container, cropping to 16:9 rectangle.
                    // 4:3  → stretch to fill the container (distorts if source is 16:9).
                    when (uiState.aspectRatioMode) {
                        AspectRatioMode.AUTO -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRatioMode.RATIO_16_9 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioMode.RATIO_4_3 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

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
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (uiState.showControls) {
                                val topBarHeight = 80.dp.toPx()
                                val bottomBarHeight = 160.dp.toPx()
                                if (offset.y < topBarHeight || offset.y > size.height - bottomBarHeight) {
                                    return@detectTapGestures
                                }
                            }
                            val third = size.width / 3f
                            when {
                                offset.x < third -> {
                                    isDragSeeking = false
                                    viewModel.onSeekBy(-TAP_SEEK_MS)
                                    seekDelta = -TAP_SEEK_MS
                                    seekVisible = true
                                }
                                offset.x > third * 2 -> {
                                    isDragSeeking = false
                                    viewModel.onSeekBy(TAP_SEEK_MS)
                                    seekDelta = TAP_SEEK_MS
                                    seekVisible = true
                                }
                                else -> viewModel.onPlayPause()
                            }
                        },
                        onTap = { _ -> viewModel.onToggleControls() },
                    )
                }
                .pointerInput(Unit) {
                    // Unified drag: vertical → brightness/volume, horizontal → seek
                    var dragAccumulated = 0f
                    var offsetAccumulated = Offset.Zero
                    var dominantDirection: DragDirection? = null // null=undecided, SEEK, ADJUST
                    var isLeftSideEdge = false
                    var isRightSideEdge = false
                    var isMiddleArea = false
                    var ignoreGesture = false
                    var initialBrightness = 0f

                    // Volume helpers captured once per gesture
                    var maxVolume = 0
                    var currentVolume = 0

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            ignoreGesture = false
                            if (uiState.showControls) {
                                val topBarHeight = 80.dp.toPx()
                                val bottomBarHeight = 160.dp.toPx()
                                if (startOffset.y < topBarHeight || startOffset.y > size.height - bottomBarHeight) {
                                    ignoreGesture = true
                                    return@detectDragGestures
                                }
                            }

                            // Hide controls for an immersive gesture adjustment
                            viewModel.onHideControls()

                            isDragSeeking = false
                            seekDelta = 0L
                            dragAccumulated = 0f
                            offsetAccumulated = Offset.Zero
                            dominantDirection = null

                            val isLeft = startOffset.x < size.width * 0.3f
                            val isRight = startOffset.x > size.width * 0.7f
                            val isMiddle = !isLeft && !isRight

                            isLeftSideEdge = isLeft
                            isRightSideEdge = isRight
                            isMiddleArea = isMiddle

                            if (isLeft) {
                                // Initialize brightness from window
                                val w = window
                                if (w != null) {
                                    val b = w.attributes.screenBrightness
                                    initialBrightness = if (b >= 0f) b else {
                                        // Auto-brightness → read system value to switch to manual
                                        try {
                                            Settings.System.getInt(
                                                view.context.contentResolver,
                                                Settings.System.SCREEN_BRIGHTNESS
                                            ) / 255f
                                        } catch (_: Exception) { 0.5f }
                                    }
                                } else {
                                    initialBrightness = 0.5f
                                }
                                slideValue = initialBrightness
                            } else if (isRight) {
                                // Initialize volume
                                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                slideValue = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (ignoreGesture) return@detectDragGestures
                            change.consume()
                            offsetAccumulated += dragAmount

                            if (dominantDirection == null) {
                                val v = kotlin.math.abs(offsetAccumulated.y)
                                val h = kotlin.math.abs(offsetAccumulated.x)
                                if (v > h + 20f) {
                                    dominantDirection = DragDirection.ADJUST
                                } else if (h > v + 20f) {
                                    // Block horizontal seek for live/unknown duration streams
                                    dominantDirection = if (canSeek) DragDirection.SEEK else null
                                }
                            }

                            when (dominantDirection) {
                                DragDirection.ADJUST -> {
                                    if (!isMiddleArea) {
                                        val deltaNormal = -offsetAccumulated.y / size.height.coerceAtLeast(1)
                                        val newVal = (if (isLeftSideEdge) initialBrightness else (if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f)) + deltaNormal
                                        val clamped = newVal.coerceIn(0f, 1f)
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
                                    if (!canSeek) return@detectDragGestures
                                    isDragSeeking = true
                                    val durationMs = uiState.duration
                                    // Linear seek: full-width swipe = 300s * sensitivity
                                    // sensitivity 1.0 = 5 minutes, 3.0 = 15 minutes, 0.2 = 1 minute per full swipe
                                    val widthPx = size.width.coerceAtLeast(1).toFloat()
                                    val maxSwipeMs = (300_000L * uiState.seekSensitivity).toLong()
                                    val ratio = offsetAccumulated.x / widthPx
                                    val rawDelta = (ratio * maxSwipeMs).toLong()
                                    seekDelta = rawDelta.coerceIn(
                                        -uiState.currentPosition,
                                        (durationMs - uiState.currentPosition).coerceAtLeast(0L)
                                    )
                                    seekVisible = true
                                }
                                null -> {}
                            }
                        },
                        onDragEnd = {
                            if (ignoreGesture) return@detectDragGestures
                            if (dominantDirection == DragDirection.SEEK && seekDelta != 0L) {
                                viewModel.onSeekBy(seekDelta)
                            }
                            isDragSeeking = false
                            seekDelta = 0L
                        },
                        onDragCancel = {
                            seekVisible = false; seekDelta = 0L
                            slideVisible = false
                            isDragSeeking = false
                        },
                    )
                }
            else Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { showUnlockOverlay = true }
            },
        ) {
            // Controls overlay (hidden when locked)
            if (uiState.showControls && !uiState.playerLocked) {
                PlayerControlsOverlay(
                    uiState = uiState,
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
                    onInteract = { hudInteractionTick++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Unlock overlay — shown on tap when locked
            if (uiState.playerLocked && showUnlockOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) {
                            viewModel.onToggleLock()
                            showUnlockOverlay = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Tap to unlock",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
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

            // Seek indicator (positioned on the correct side automatically for double tap,
            // or central progress indicator for drag seeks)
            if (isDragSeeking) {
                DragSeekIndicator(
                    deltaMs = seekDelta,
                    currentPositionMs = uiState.currentPosition,
                    durationMs = uiState.duration,
                    visible = seekVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SeekIndicator(
                    deltaMs = seekDelta,
                    currentPositionMs = uiState.currentPosition,
                    visible = seekVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Slide indicator (brightness / volume)
            SlideIndicator(
                value = slideValue,
                type = slideType,
                visible = slideVisible,
                modifier = Modifier.fillMaxSize(),
            )

            // Buffering loader
            if (uiState.isLoading && uiState.errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Error overlay (network timeout, disconnected, etc.)
            val errorMsg = uiState.errorMessage
            if (errorMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { /* consume clicks */ },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⚠",
                            fontSize = 36.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMsg,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
