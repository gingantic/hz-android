# Hz Player — Player Architecture

> Media3 ExoPlayer integration with MVVM for the Hz Player.

---

## Core Architecture

```
┌───────────────────────────────────────────────────────────┐
│                    MediaPlaybackService                    │
│                  (Media3 MediaSessionService)              │
│                                                           │
│  ┌─────────────────┐     ┌───────────────────────────┐    │
│  │   MediaSession   │────▶│      ExoPlayer            │    │
│  │  (Media3)        │     │  (single JVM singleton)    │    │
│  └─────────────────┘     └───────────┬───────────────┘    │
│                                      │                    │
│  ┌────────────────────────────────────▼───────────────┐   │
│  │           Notification Manager                      │   │
│  │  (Media3 MediaNotificationService integration)      │   │
│  └────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
                              │
                              │ Binder / Bundle
                              ▼
┌───────────────────────────────────────────────────────────┐
│                    PlayerRepositoryImpl                     │
│                                                           │
│  • Holds reference to ExoPlayer                           │
│  • Exposes StateFlow<PlayerState>                         │
│  • Proxy for player operations (play/pause/seek/tracks)   │
└───────────────────────────────────────────────────────────┘
                              │
                              │ Flow / suspend
                              ▼
┌───────────────────────────────────────────────────────────┐
│                    PlayerViewModel                         │
│                                                           │
│  • Maps PlayerState → PlayerUiState                       │
│  • Exposes StateFlow<PlayerUiState>                       │
│  • Binds player state to screen lifecycle                 │
└───────────────────────────────────────────────────────────┘
                              │
                              │ collectAsStateWithLifecycle
                              ▼
┌───────────────────────────────────────────────────────────┐
│                    VideoPlayerScreen                       │
│                                                           │
│  • AndroidView(TextureView) for video output              │
│  • PlayerControlsOverlay composable                       │
│  • Gesture handlers                                       │
└───────────────────────────────────────────────────────────┘
```

---

## MediaPlayerHolder (Singleton)

```kotlin
@Singleton
class MediaPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .setRenderersFactory(DefaultRenderersFactory(context))
        .build()

    private val _playbackState = MutableStateFlow(PlayerState.IDLE)
    val playbackState: StateFlow<PlayerState> = _playbackState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = when (state) {
                    Player.STATE_IDLE -> PlayerState.IDLE
                    Player.STATE_BUFFERING -> PlayerState.BUFFERING
                    Player.STATE_READY -> PlayerState.READY
                    Player.STATE_ENDED -> PlayerState.ENDED
                    else -> PlayerState.IDLE
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Update position tracking
            }
        })
    }
}
```

---

## MediaPlaybackService

```kotlin
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playerHolder: MediaPlayerHolder

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, playerHolder.player)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = playerHolder.player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            controllerCompat.unregisterCallback(callback)
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
```

---

## PlayerState

```kotlin
enum class PlayerState { IDLE, BUFFERING, READY, ENDED }

data class PlayerStateInfo(
    val state: PlayerState = PlayerState.IDLE,
    val isPlaying: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val subtitleTracks: List<Media3TrackInfo> = emptyList(),
    val audioTracks: List<Media3TrackInfo> = emptyList(),
    val selectedSubtitleTrackId: Int = -1,
    val selectedAudioTrackId: Int = -1,
)
```

---

## PlayerViewModel

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playerRepository.playbackStateInfo.collect { info ->
                _uiState.update { state ->
                    state.copy(
                        isPlaying = info.isPlaying,
                        isLoading = info.state == PlayerState.BUFFERING,
                        currentPosition = info.currentPosition,
                        duration = info.duration,
                        bufferedPercentage = if (info.duration > 0)
                            (info.bufferedPosition * 100 / info.duration).toInt()
                        else 0,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = when (info.repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.NONE
                        },
                        subtitleTracks = info.subtitleTracks,
                        audioTracks = info.audioTracks,
                        selectedSubtitleTrack = info.selectedSubtitleTrackId,
                        selectedAudioTrack = info.selectedAudioTrackId,
                    )
                }
            }
        }
    }

    fun onPlayPause() = playerRepository.togglePlayPause()
    fun onSeekTo(positionMs: Long) = playerRepository.seekTo(positionMs)
    fun onSkipForward(ms: Long) = playerRepository.skipForward(ms)
    fun onSkipBackward(ms: Long) = playerRepository.skipBackward(ms)
    fun onSetSpeed(speed: Float) = playerRepository.setSpeed(speed)
    fun onToggleShuffle() = playerRepository.toggleShuffle()
    fun onCycleRepeatMode() = playerRepository.cycleRepeatMode()
    fun onSelectSubtitleTrack(trackId: Int) = playerRepository.selectSubtitleTrack(trackId)
    fun onSelectAudioTrack(trackId: Int) = playerRepository.selectAudioTrack(trackId)
}
```

---

## VideoPlayerScreen — Gesture Handling

```kotlin
@Composable
fun VideoPlayerScreen(
    uiState: PlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showControls by remember { mutableStateOf(true) }
    var controlsJob by remember { mutableStateOf<Job?>(null) }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls) {
        if (showControls) {
            controlsJob?.cancel()
            controlsJob = coroutineScope.launch {
                delay(3000)
                showControls = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Video surface
        AndroidView(
            factory = { context ->
                TextureView(context).apply { /* surfaceTextureListener */ }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch gesture handler
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            // Seek 10s back/forward based on tap x position
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        // Left half: brightness, Right half: volume
                    }
                }
        )

        // Controls overlay (fade animation)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControlsOverlay(
                uiState = uiState,
                onPlayPause = onPlayPause,
                onSeekTo = onSeekTo,
                onSkipForward = onSkipForward,
                onSkipBackward = onSkipBackward,
                onBack = onBack,
                onSpeedClick = { showSpeedSelector = true },
                onSubtitleClick = { showSubtitleSheet = true },
                onAudioTrackClick = { showAudioTrackSheet = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

---

## ExoPlayer Integration Points

| VLC Feature | Media3 Equivalent |
|---|---|
| `MediaPlayer.play()` | `ExoPlayer.play()` |
| `MediaPlayer.pause()` | `ExoPlayer.pause()` |
| `MediaPlayer.time` | `ExoPlayer.currentPosition` |
| `MediaPlayer.length` | `ExoPlayer.duration` |
| `MediaPlayer.setRate()` | `ExoPlayer.setPlaybackSpeed()` |
| `MediaPlayer.setEqualizer()` | `ExoPlayer.audioSessionId` + AudioEffect |
| Subtitles: `IVLCVout.setSubtitle()` | `ExoPlayer.setTrackSelectionParameters()` |
| Audio tracks: `MediaPlayer.getAudioTracks()` | `ExoPlayer.getCurrentTracks().getGroups()` |
| Chapters: `MediaPlayer.getChapterCount()` | `ExoPlayer.timeline.getPeriod()` |
| ABRepeat: Custom VLC feature | Not natively supported (manual impl) |
