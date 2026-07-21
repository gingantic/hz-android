# Hz Player — Player Architecture

> Media3 ExoPlayer behind the `IPlayerEngine` contract, rendered through `PlayerSurface`.
> Last refreshed: 2026-07-21. Reflects the libass subtitle pipeline, position controller
> split, audio queue, floating video player, and resume-mode support.

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

Presentation renders video through one composable. The render seam methods
(`createRenderView`, `updateRenderView`, `onRenderViewPaused/Resumed`) live directly
on `IPlayerEngine` — no typed casts or `when` branches needed. Adding a new engine
requires **zero** changes to `PlayerSurface`.

```kotlin
@Composable
fun PlayerSurface(engine: IPlayerEngine, uiState: PlayerUiState, modifier: Modifier, onRenderView: (View?) -> Unit) {
    key(engine.engineType) {
        AndroidView(
            factory = { ctx ->
                val view = engine.createRenderView(ctx, uiState.useSurfaceView)
                onRenderView(view)
                view
            },
            update = { view ->
                engine.updateRenderView(view, RenderViewConfig(uiState.aspectRatioMode))
            },
        )
    }
}
```

Lifecycle (brightness/volume pause on `ON_STOP`, resume on `ON_RESUME`) lives in
`VideoPlayerScreen` and calls `engine.onRenderViewPaused(view)` /
`engine.onRenderViewResumed(view)` directly through the interface.

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
enum class PlayerState { IDLE, BUFFERING, READY, ENDED, ERROR }

data class PlayerStateInfo(
    val state: PlayerState = IDLE,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val errorMessage: String? = null,
    val errorKind: PlaybackErrorKind? = null,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentUri: String? = null,
    val drmSessionActive: Boolean = false,
)
```

Track lists (subtitle/audio) are **not** in `PlayerStateInfo` — they are cached by
`PlayerTrackCache` and refreshed on READY to avoid per-tick re-queries.

---

## PlayerViewModel (engine-agnostic)

The ViewModel is split into focused controllers to keep each class small:

| Controller | Responsibility |
|---|---|
| `PlayerViewModel` | Orchestrates controllers, maps `PlayerStateInfo` → `PlayerUiState` |
| `PlayerPositionController` | 250ms position tick (`StateFlow<Long>`), periodic resume-save |
| `PlayerTrackCache` | Caches subtitle/audio track lists; refreshes on READY |
| `PlayerPlaylistController` | Video playlist + audio queue management |
| `PlayerDebugController` | Debug stats polling |

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val resumeRepository: ResumeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Position is a SEPARATE StateFlow so the 250ms tick only recomposes the seekbar
    val position: StateFlow<Long>  // from PlayerPositionController

    init {
        viewModelScope.launch {
            playerRepository.playbackStateInfo.collect { info ->
                _uiState.update { state ->
                    state.copy(
                        isPlaying = info.isPlaying,
                        isLoading = info.state == PlayerState.BUFFERING,
                        duration = info.duration,
                        bufferedPercentage = ...,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = info.repeatMode,
                        errorMessage = info.errorMessage,
                        errorKind = info.errorKind,
                        currentTitle = info.currentTitle,
                        currentArtist = info.currentArtist,
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

Gestures are handled by the extracted `PlayerGestures` composable:

- **Single tap**: toggle controls overlay (auto-hide ~3s).
- **Double tap left/right**: seek ∓10s (with `SeekIndicator`/`DragSeekIndicator`).
- **Swipe left half**: brightness. **Swipe right half**: volume.
- **Swipe up/down**: dismiss player (portrait).
- **Pinch / aspect button**: zoom-to-fit / fill (`AspectRatioMode`).
- Lock pill (`UnlockPill`) disables gestures; swipe to unlock.
- `GestureCueIndicators` provides visual feedback for all gesture types.

---

## Subtitle pipeline (libass)

All subtitle rendering goes through native libass for pixel-perfect ASS/SSA output:

1. **Embedded tracks**: `AssExtractorsFactory` + `AssMatroskaExtractor` intercept
   subtitle samples in ExoPlayer's extractor chain → `AssTrackOutput` buffers them.
2. **External files**: `NeighborSubtitleDiscoverer` auto-detects sibling `.srt/.ass`
   files (local + SMB); external ASS loads via `IPlayerEngine.loadExternalAss(uri)`.
3. **SRT/VTT**: `SubtitleConverters` converts to ASS on-the-fly for unified rendering.
4. **Rendering**: `AssHandler` feeds data to libass via `AssDirectBridge` (JNI) →
   renders a bitmap at each frame time → displayed on `SubtitleOverlayView`.
5. **Compose**: `AssSubtitleOverlay` wraps the overlay view in an `AndroidView`.

Subtitle track names are resolved to languages + country flags via
`SubtitleLanguageResolver` and displayed with `FlagIcon` in the selection dialogs.

---

## Floating Video Player

`FloatingVideoPlayer` provides a draggable, resizable PiP-style overlay that stays
on top when the user navigates away from the full-screen player. Includes play/pause,
close, fullscreen-return buttons, and a progress indicator.

---

## Audio Queue

`AudioQueueSheet` shows the current "now playing" list for audio playback. The queue
is managed by `PlayerPlaylistController` and exposed via `PlayerUiState.audioQueue` /
`audioQueueIndex`. Users can tap a queue item to jump to it.

---

## ExoPlayer Integration Points

| Feature | Media3 Implementation |
|---|---|
| `play()/pause()` | `ExoPlayer.play()/pause()` |
| `time/length` | `currentPosition` / `duration` |
| `setRate()` | `setPlaybackSpeed()` |
| Subtitles (embedded) | `AssExtractorsFactory` intercepts → libass pipeline |
| Subtitles (external) | `addExternalSubtitle(uri)` / `loadExternalAss(uri)` |
| Audio tracks | `getCurrentTracks().getGroups()` |
| Decoder mode | `setDecoderMode()` → rebuilds renderers (SW/HW) |
| DRM | `drmSessionActive` flag → TextureView for secure decode |
| ABRepeat | manual (not native) |

Subtitle timing offset is exposed via `IPlayerEngine.setSubtitleDelay/getSubtitleDelay`.
