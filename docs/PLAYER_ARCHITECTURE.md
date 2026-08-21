# Hz Player — Player Architecture

> Media3 ExoPlayer and a standalone native FFmpeg player behind the `IPlayerEngine`
> contract, rendered through `PlayerSurface`.
> Last refreshed: 2026-08-22. Reflects three selectable engines, the 10-band
> equalizer, the libass subtitle pipeline (zero-flicker), position controller
> split, audio queue, floating video player, resume-mode support, sleep timer,
> chapters, A-B repeat, audio delay, and play-as-audio mode.

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
│        Active engine (Map<EngineType, IPlayerEngine>)      │
│  • EXO_PLAYER / FFMPEG → ExoPlayerEngine                   │
│    (FFMPEG = same engine, FFmpeg renderers preferred)      │
│  • NATIVE_FFMPEG → FfmpegNativeEngine                      │
│    (standalone libffplayer.so, no ExoPlayer)               │
│  • all rendering via createRenderView/updateRenderView     │
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

Owns the single `ExoPlayer` (backs both `EXO_PLAYER` and `FFMPEG` engine types).
Configured for this app:
- `DefaultTrackSelector` with **tunneling disabled** (4K HDR HEVC tunneled seek stall).
- `DefaultLoadControl` with larger buffers (50s/90s) for smoother remote streaming.
- `HzRenderersFactory` (single `RenderersFactory`): ASS renderers + audio delay +
  equalizer processors, with a `preferFfmpeg` flag that indexes the FFmpeg extension
  decoders before MediaCodec (`EngineType.FFMPEG`) or keeps platform-first order
  (`EXO_PLAYER`).
- `AudioDelaySink` wrapping the default audio sink for A/V sync offset.
- Chapter probing via native FFmpeg demuxer on READY → `List<ChapterInfo>`.
- Exposes `PlayerStateInfo` via `playbackState: StateFlow`.
- `onPlayerError` routes through `PlaybackErrorMapper` → redacted `(kind, message)`.
- Debug stats extracted via `ExoDebugStatsHelper` (FPS, decoder labels, HDR, SoC).

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
    val chapters: List<ChapterInfo> = emptyList(),  // container chapters from FFmpeg
)
```

Track lists (subtitle/audio) are **not** in `PlayerStateInfo` — they are cached by
`PlayerTrackCache` and refreshed on READY to avoid per-tick re-queries.

---

## PlayerViewModel (engine-agnostic)

The ViewModel is split into focused controllers to keep each class small:

| Controller | Responsibility |
|---|---|
| `PlayerViewModel` | Orchestrates controllers, maps `PlayerStateInfo` → `PlayerUiState`, sleep timer, A-B repeat loop |
| `PlayerPositionController` | 250ms position tick (`StateFlow<Long>`), periodic resume-save, A-B loop boundary check |
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

## Sleep Timer

`SleepTimerDialog` offers fixed presets (15/30/45/60/90/120 min), "end of video" mode
(-1), and Off. The timer is managed by `PlayerViewModel`:
- Countdown displayed via `sleepTimerRemainingFlow` (1 Hz `StateFlow<Long>`).
- "End of video" auto-stops when `PlayerState.ENDED` is reached.
- On expiry, playback pauses and the timer resets.

---

## A-B Repeat Loop

Cycled via `PlayerMoreOptionsSheet.onCycleAbRepeat`:
1. First tap → `abLoopStartMs = currentPosition` (point A set).
2. Second tap → `abLoopEndMs = currentPosition` (loop active).
3. Third tap → both cleared (loop off).

`PlayerPositionController` monitors the 250ms tick: when `position >= abLoopEndMs`,
it calls `seekTo(abLoopStartMs)` automatically.

---

## Chapter Navigation

Chapters are probed from the media container (MKV chapters, MP4 chpl, OGG chapters)
via the native FFmpeg demuxer when the player reaches READY state. The resulting
`List<ChapterInfo>` (startMs, endMs, title) flows through `PlayerStateInfo` →
`PlayerUiState.chapters`. `ChapterSelectionDialog` shows the list with the current
chapter highlighted; tapping a row seeks to its `startMs`.

---

## Audio Delay (A/V Sync Offset)

`AudioDelaySink` (a `ForwardingAudioSink`) shifts the audio clock reported to
ExoPlayer. Since ExoPlayer syncs video to the audio renderer's clock, offsetting
`getCurrentPositionUs` shifts A/V sync without touching the audio pipeline.
- Positive delay → audio heard later (video renders ahead).
- Controlled via `IPlayerEngine.setAudioDelay(delayMs)`.
- Persisted in `PlayerUiState.audioDelayMs`.

---

## Engine Selection (three engines)

Picked in Settings; persisted via `UserPreferencesRepository.activeEngine`:

| Engine | Backing | Use case |
|---|---|---|
| `EXO_PLAYER` | Media3 ExoPlayer, platform decoders first | Default; best battery + format coverage |
| `FFMPEG` | Same ExoPlayer pipeline, FFmpeg software renderers preferred (`HzRenderersFactory.preferFfmpeg`) | Formats the platform decoder mishandles |
| `NATIVE_FFMPEG` | Standalone native player (`FfmpegNativeEngine` + `cpp/FfmpegPlayer.cpp`, `libffplayer.so`) | Instant seeking on local/networked media; AMediaCodec HW decode w/ libdav1d fallback |

Switching stops current playback and rebuilds the render surface
(`PlayerSurface` keys on `engineType`). The native engine opts out of the
system MediaSession (`getMedia3Player() = null`).

See `docs/FFMPEG_NATIVE_AUDIT_AND_ROADMAP.md` for the native player deep-dive.

---

## 10-Band Equalizer

Implemented once, exposed through the engine contract:
- `ExoPlayerEngine`: `TenBandEqualizerProcessor` in the `AudioProcessor` chain +
  `EqualizerController` (bass boost, loudness enhancement).
- `FfmpegNativeEngine`: same `EqualizerController`, attached to the native
  player's AudioTrack session id.
- UI: `EqualizerSheet` — per-band sliders, device presets, bass boost, loudness;
  state flows as `StateFlow<EqualizerInfo>` and persists via `EqualizerSettings`.

---

## Play-as-Audio Mode

From the video player, users can tap "Play as audio" (`PlayerMoreOptionsSheet`)
to hide the video surface and continue playback as audio-only. The ViewModel sets
`isVideo=false` + `playingVideoAsAudio=true`; the engine keeps playing. The mini
player bar takes over the UI. Users can return to the video surface via the
floating player or navigating back to the full-screen player.

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
| FFmpeg preference | `setFfmpegPreferred(true)` → renderer reorder (`EngineType.FFMPEG`) |
| Equalizer | `TenBandEqualizerProcessor` + `EqualizerController` in the processor chain |
| DRM | `drmSessionActive` flag → TextureView for secure decode |
| ABRepeat | manual (not native) — `PlayerPositionController` loops A→B |
| Sleep timer | manual — `PlayerViewModel` countdown + auto-pause |
| Chapters | probed via native FFmpeg demuxer → `List<ChapterInfo>` |
| Audio delay | `AudioDelaySink` (ForwardingAudioSink clock shift) |

Subtitle timing offset is exposed via `IPlayerEngine.setSubtitleDelay/getSubtitleDelay`.
