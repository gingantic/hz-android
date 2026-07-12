# Hz Player — Player Architecture

> Media3 ExoPlayer behind the `IPlayerEngine` contract, rendered through `PlayerSurface`.
> Last refreshed: 2026-07-11. Supersedes the earlier "ExoPlayer wired directly into
> every layer" design — see `docs/ENGINE_MODULARITY.md` for the refactor rationale.

---

## Core Architecture

```
┌───────────────────────────────────────────────────────────┐
│                    MediaPlaybackService                    │
│                  (Media3 MediaSessionService)              │
│  • builds MediaSession from engine.getMedia3Player()  │
│  • Media3 MediaNotificationService → system controls       │
└───────────────────────────┬───────────────────────────────┘
                            │ Media3 Player (via IPlayerEngine)
┌───────────────────────────▼───────────────────────────────┐
│                    ExoPlayerEngine                         │
│  • implements IPlayerEngine (the only playback contract)   │
│  • owns rendering: createRenderView / updateRenderView     │
│  • wraps MediaPlayerHolder.player                          │
└───────────────────────────┬───────────────────────────────┘
                            │ IPlayerEngine calls
┌───────────────────────────▼───────────────────────────────┐
│                    PlayerRepositoryImpl                    │
│  • Map<EngineType, IPlayerEngine> (Hilt @IntoMap)          │
│  • activeEngine resolved from UserPreferencesRepository     │
│  • delegates every call; exposes playbackStateInfo Flow    │
│  • network traffic polling for remote URIs                 │
└───────────────────────────┬───────────────────────────────┘
                            │ Flow / callbacks
┌───────────────────────────▼───────────────────────────────┐
│                    PlayerViewModel                         │
│  • maps PlayerStateInfo → PlayerUiState                    │
│  • exposes StateFlow<PlayerUiState> (activeEngineType etc) │
└───────────────────────────┬───────────────────────────────┘
                            │ collectAsStateWithLifecycle
┌───────────────────────────▼───────────────────────────────┐
│   VideoPlayerScreen / AudioPlayerScreen + PlayerSurface    │
│  • PlayerSurface(engine) renders; no Media3 import         │
│  • PlayerControlsOverlay, gestures, sheets, dialogs        │
└───────────────────────────────────────────────────────────┘
```

---

## MediaPlayerHolder (Singleton)

Owns the single `ExoPlayer`. Configured for this app:
- `DefaultTrackSelector` with **tunneling disabled** (4K HDR HEVC tunneled seek stall).
- `DefaultLoadControl` with larger buffers (50s/90s) for smoother remote streaming.
- `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_ON` (FFmpeg extension decoders).
- Exposes `PlayerStateInfo` via `playbackState: StateFlow`.
- `onPlayerError` routes through `PlaybackErrorMapper` → redacted `(kind, message)`.

```kotlin
@Singleton
class MediaPlayerHolder @Inject constructor(@ApplicationContext context: Context) {
    var player: ExoPlayer = buildPlayer()   // single instance, private set
    val playbackState: StateFlow<PlayerStateInfo>
}
```

---

## Engine seam — `PlayerSurface`

Presentation renders video through one composable; it switches on `engine.engineType`
and asks the concrete engine for its render view. Screens never import `PlayerView`.

```kotlin
@Composable
fun PlayerSurface(engine: IPlayerEngine, uiState: PlayerUiState, modifier: Modifier, onRenderView: (View?) -> Unit) {
    key(engine.engineType) {
        AndroidView(
            factory = { ctx ->
                when (engine.engineType) {
                    EngineType.EXO_PLAYER ->
                        (engine as ExoPlayerEngine).createRenderView(ctx, uiState.useSurfaceView)
                    // EngineType.VLC / MPV → their engine.createRenderView(ctx)
                }.also(onRenderView)
            },
            update = { view -> /* engine.updateRenderView(view, RenderViewConfig(...)) */ },
        )
    }
}
```

Lifecycle (brightness/volume pause on `ON_STOP`, resume on `ON_RESUME`) lives in
`VideoPlayerScreen` and calls engine-specific `onRenderViewPaused/Resumed` through the
typed cast — those methods are **not** on `IPlayerEngine`.

---

---

## MediaPlaybackService

Built inline in `MediaPlaybackService.kt` — the service calls `engine.getMedia3Player()`
on the active engine and wraps it in a `Media3 MediaSession`. No separate provider class.

```kotlin
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {
    @Inject lateinit var playerRepository: PlayerRepository
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = playerRepository.activeEngine
        val player = engine.getMedia3Player()
        mediaSession = player?.let {
            MediaSession.Builder(this, it).setSessionActivity(pendingIntent).build()
        }
        // Re-point session when the engine swaps its player (decoder rebuild).
        engine.setOnPlayerReplacedListener { newPlayer ->
            mediaSession?.setPlayer(newPlayer)
        }
    }
    override fun onGetSession(c: ControllerInfo) = mediaSession
    override fun onDestroy() {
        mediaSession?.release().also { mediaSession = null }
        playerRepository.release()
        super.onDestroy()
    }
}
```

A `MediaSession` is only built when the active engine's `getMedia3Player()` returns
non-null (ExoPlayer today). A future non-Media3 backend returns `null` and opts out.

---


## PlayerState

```kotlin
enum class PlayerState { IDLE, BUFFERING, READY, ENDED }

data class PlayerStateInfo(
    val state: PlayerState = IDLE,
    val isPlaying: Boolean = false,
    val currentUri: String? = null,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val subtitleTracks: List<String> = emptyList(),
    val audioTracks: List<String> = emptyList(),
    val selectedSubtitleTrack: Int = -1,
    val selectedAudioTrack: Int = -1,
    val errorKind: PlaybackErrorKind? = null,
    val errorMessage: String? = null,
)
```

---

## PlayerViewModel (engine-agnostic)

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
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
                            (info.bufferedPosition * 100 / info.duration).toInt() else 0,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = info.repeatMode,
                        subtitleTracks = info.subtitleTracks,
                        audioTracks = info.audioTracks,
                        selectedSubtitleTrack = info.selectedSubtitleTrack,
                        selectedAudioTrack = info.selectedAudioTrack,
                        errorMessage = info.errorMessage,
                        errorKind = info.errorKind,
                        activeEngineType = playerRepository.activeEngine.engineType,
                    )
                }
            }
        }
    }
    // onPlayPause / onSeekTo / onSkipForward/Back / onSetSpeed / onToggleShuffle /
    // onCycleRepeatMode / onSelectSubtitleTrack / onSelectAudioTrack / retry / clearError
    // — all delegate to playerRepository (which delegates to the active engine).
}
```

---

## VideoPlayerScreen — Gestures

- **Single tap**: toggle controls overlay (auto-hide ~3s).
- **Double tap left/right**: seek ∓10s (with `SeekIndicator`/`DragSeekIndicator`).
- **Swipe left half**: brightness. **Swipe right half**: volume.
- **Swipe up/down**: dismiss player (portrait).
- **Pinch / aspect button**: zoom-to-fit / fill (`AspectRatioMode`).
- Lock pill (`UnlockPill`) disables gestures; swipe to unlock.

---

## ExoPlayer Integration Points

| VLC Feature | Media3 Equivalent |
|---|---|
| `MediaPlayer.play()/pause()` | `ExoPlayer.play()/pause()` |
| `MediaPlayer.time/length` | `currentPosition` / `duration` |
| `setRate()` | `setPlaybackSpeed()` |
| Subtitles `setSubtitle()` | `setTrackSelectionParameters()` + external `.addExternalSubtitle(uri)` |
| Audio tracks `getAudioTracks()` | `getCurrentTracks().getGroups()` |
| Chapters | `timeline.getPeriod()` |
| ABRepeat | manual (not native) |

Subtitle timing offset is exposed via `IPlayerEngine.setSubtitleDelay/getSubtitleDelay`
and styled through `SubtitleStyle` + `SubtitleStylingDialog`.
