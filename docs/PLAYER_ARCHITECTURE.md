# Hz Player — Player Architecture

> Media3 ExoPlayer and a standalone native FFmpeg player behind the `IPlayerEngine`
> contract, rendered through `PlayerSurface`.
> Verified against code at `8ffb763` — drift check: `git diff 8ffb763..HEAD -- app/src`.

---

## Core Architecture

```
┌───────────────────────────────────────────────────────────┐
│  MediaPlaybackService (Media3 MediaSessionService)         │
│  • MediaSession built from engine.getMedia3Player()        │
│  • re-points the session when the engine swaps its player   │
│  • drives the system notification + lock-screen controls    │
└───────────────────────────┬───────────────────────────────┘
                            │ Media3 Player (via IPlayerEngine)
┌───────────────────────────▼───────────────────────────────┐
│  Active engine — Map<EngineType, IPlayerEngine>            │
│  • EXO_PLAYER / FFMPEG → ExoPlayerEngine                   │
│    (FFMPEG = same engine, FFmpeg renderers preferred)      │
│  • NATIVE_FFMPEG → FfmpegNativeEngine (libffplayer.so)     │
│  • all rendering via createRenderView / updateRenderView    │
└───────────────────────────┬───────────────────────────────┘
                            │ IPlayerEngine calls
┌───────────────────────────▼───────────────────────────────┐
│  PlayerRepositoryImpl                                      │
│  • Map<EngineType, IPlayerEngine> (Hilt @IntoMap)          │
│  • activeEngine resolved from UserPreferencesRepository    │
│  • delegates every call; exposes playbackStateInfo Flow    │
│  • polls network traffic for remote URIs                   │
└───────────────────────────┬───────────────────────────────┘
                            │ Flow / callbacks
┌───────────────────────────▼───────────────────────────────┐
│  PlayerViewModel → StateFlow<PlayerUiState>                │
│  • maps PlayerStateInfo → PlayerUiState                    │
│  • position lives in a separate StateFlow (see below)      │
└───────────────────────────┬───────────────────────────────┘
                            │ collectAsStateWithLifecycle
┌───────────────────────────▼───────────────────────────────┐
│  VideoPlayerScreen / AudioPlayerScreen + PlayerSurface     │
│  • PlayerSurface renders; imports no Media3 type           │
│  • PlayerControlsOverlay, gestures, sheets, dialogs        │
└───────────────────────────────────────────────────────────┘
```

---

## MediaPlayerHolder (Singleton)

Owns the single `ExoPlayer` (backs both `EXO_PLAYER` and `FFMPEG`):

- `DefaultTrackSelector` with **tunneling disabled** (4K HDR HEVC tunneled seek stall).
- `DefaultLoadControl` with larger buffers (50 s / 90 s) for smoother remote streaming.
- `HzRenderersFactory` — one `RenderersFactory` for ASS renderers + audio delay +
  equalizer processors, with a `preferFfmpeg` flag that indexes the FFmpeg extension
  decoders before MediaCodec (`FFMPEG`) or keeps platform-first order (`EXO_PLAYER`).
- `AudioDelaySink` wrapping the default audio sink for A/V sync offset.
- `playbackState: StateFlow<PlayerStateInfo>`.
- `onPlayerError` routes through `PlaybackErrorMapper` → redacted `(kind, message)`.
- Debug stats via `ExoDebugStatsHelper` (FPS, decoder labels, HDR, SoC).

---

## Engine seam — `PlayerSurface`

Presentation renders video through one composable; the render-seam methods live on
`IPlayerEngine`, so adding an engine requires **zero** changes here.

```kotlin
@Composable
fun PlayerSurface(engine: IPlayerEngine, uiState: PlayerUiState, …) {
    key(engine.engineType) {                       // rebuild the surface on engine swap
        AndroidView(
            factory = { engine.createRenderView(it, uiState.useSurfaceView) },
            update = { engine.updateRenderView(it, RenderViewConfig(uiState.aspectRatioMode)) },
        )
    }
}
```

Lifecycle (pause on `ON_STOP`, resume on `ON_RESUME`) lives in `VideoPlayerScreen`,
calling `engine.onRenderViewPaused(view)` / `onRenderViewResumed(view)` directly.

`NATIVE_FFMPEG` implements both hooks as no-ops — its pause is `viewModel.pause()`
(`pauseClock()` + `NativeAudioSink.pause()`). While paused with no surface
(backgrounded), three guards keep the pipeline from racing ahead of the clock:
`VideoThread` arms the `needSeekFrame` fast path only when a window exists and only
renders through it when `hasSurface`; `surfaceChanged` is peeked, not consumed, so
the main loop still re-inits the hardware decoder on the recreated surface; and
`DemuxThread` stops reading while paused (the queues' push soft-limits would
otherwise let a stalled consumer run to EOF). Without these, frames decoded while
backgrounded anchored the master clock per frame, racing the position to EOF while
audio stayed put (~1–2 min ahead on resume).

On the return path, a surface-refresh frame (the one-shot fast path armed by the
recreated surface) presents on the new surface but must not re-anchor the clock —
otherwise the video's decode lead yanks the audio clock on every return. A frame
more than 600 ms ahead of the clock is a normal decode lead: the pacing loop holds
it until its presentation time and only re-aligns when the clock is frozen
(`isBuffering`) or the gap is a discontinuity (> 5 s). Frames from an older seek
generation skip A/V correction entirely — `publishSeekRequest()` already
re-anchored the clock to the target, so the in-flight pre-seek frame would
otherwise log a bogus `Large A/V desync` and briefly snap the clock back.

Returning while still paused does not resume playback, and the recreated surface
starts black until something renders on it. Three wakes present a still without
resuming: the software render wait re-renders its held frame through the GL
renderer (no codec involvement); the hardware pacing gate drops its held codec
buffer and returns to the main loop, which re-binds the codec with no buffer held
(the only proven `AMediaCodec_setOutputSurface` pattern) and pulls a fresh output
from the decoder — it decodes ahead, so no new packets are needed; and the
main-loop paused wait wakes so the same pull happens when nothing was held. The
still is a refresh frame: no clock or position anchor. The rebind drop is
one-shot (`surfaceRebindPending`) — without it the drain would discard every
output it dequeues until the main loop re-binds.

---

## MediaPlaybackService

`MediaPlaybackService.kt` builds the session inline — no separate provider class:

```kotlin
val player = playerRepository.activeEngine.getMedia3Player()
mediaSession = player?.let { MediaSession.Builder(this, it).setSessionActivity(pi).build() }
playerRepository.activeEngine.setOnPlayerReplacedListener { mediaSession?.setPlayer(it) }
```

A `MediaSession` is only built when the active engine returns a non-null
`getMedia3Player()` (ExoPlayer today). A non-Media3 backend returns `null` and opts
out — which is why `NATIVE_FFMPEG` has no lock-screen controls.

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
    /** True iff the current MediaItem declares a `drmConfiguration` (Widevine L1 path, etc.). */
    val drmSessionActive: Boolean = false,
)
```

Two things are deliberately **not** here: track lists (subtitle/audio) — cached by
`PlayerTrackCache` and refreshed on READY to avoid per-tick re-queries — and chapters,
which `PlayerViewModel` probes separately into `PlayerUiState.chapters`.

---

## PlayerViewModel (engine-agnostic)

Split into focused controllers so each class stays small:

| Controller | Responsibility |
|---|---|
| `PlayerViewModel` | Orchestrates controllers, maps `PlayerStateInfo` → `PlayerUiState`, sleep timer, A-B repeat loop, chapter probing |
| `PlayerPositionController` | 250 ms position tick (`StateFlow<Long>`), seek bookkeeping + clamping, periodic resume-save |
| `PlayerTrackCache` | Caches subtitle/audio track lists; refreshes on READY |
| `PlayerPlaylistController` | Video playlist + audio queue management |
| `PlayerDebugController` | Debug stats polling |

Position is a **separate** `StateFlow<Long>` (`vm.position`), not part of
`PlayerUiState`, so the 250 ms tick only recomposes the seek bar.

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val resumeRepository: ResumeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // init: collect playerRepository.playbackStateInfo → _uiState.update { copy(...) }
    // onPlayPause / onSeekTo / onSkipForward|Back / onSetSpeed / onToggleShuffle /
    // onCycleRepeatMode / onSelectSubtitleTrack / onSelectAudioTrack / retry / clearError
    // — all delegate to playerRepository → active engine.
}
```

---

## Seek clamping (shared across engines)

`domain/player/IPlayerEngine.kt` owns the one clamp every engine uses:

```kotlin
const val SEEK_END_MARGIN_MS = 1_000L          // stay 1 s short of the end
fun clampSeekPosition(positionMs: Long, durationMs: Long): Long
```

- Never negative.
- With a known duration: kept `SEEK_END_MARGIN_MS` short of the end. Landing exactly
  on the final timestamp makes the demuxer read past EOF (`EOFException` on containers
  without a tail index — e.g. MKV without Cues).
- With an unknown duration: only the negative guard applies. It must **not** fall back
  to `Long.MAX_VALUE`, which maps to a byte offset past the file end and errors out.

Call sites: `ExoPlayerEngine.seekTo` (+ resume-seek on prepare, `skipToNext/Previous`),
`FfmpegNativeEngine.seekTo`, and `PlayerPositionController.clampSeekTarget()` — which
prefers the live engine duration and falls back to the UI-state duration, so a
transient engine `0` doesn't disable the clamp. `skipForward/Backward` just offset from
the current position and let `seekTo` clamp. `JumpToTimeDialog` clamps the typed
HH:MM:SS to the media duration before jumping.

---

## Seek transactions (native engine)

`NATIVE_FFMPEG` publishes a seek as one transaction and tags every queue flush with that
transaction's generation, so a frame decoded before the flush can never consume or clear
the new target.

- `publishSeekRequest(targetMs, mode, scrub)` (`cpp/ffplayer/FfmpegPlayerContext.h`) bumps
  `seekVersion` and records `SeekRequest{generation, targetMs, mode, scrub}` together with
  the coupled state (buffering, drift reset, `seekTargetMs`, reported position, master
  clock) under `seekRequestMutex`. The demux thread consumes it via `takeSeekRequest()`, so
  the target and the generation can never belong to different seeks.
- The demux thread then sets `videoSeekTargetPtsUs` / `audioSeekTargetPtsUs`, clears both
  queues and pushes a generation-tagged flush marker — `pushFlush(req.generation)`, where
  the argument is mandatory. `nativeSelectAudioTrack` tags its audio-queue flush with the
  current `seekVersion` for the same reason: an untagged flush can discard a pending
  seek's marker and leave the audio thread dropping frames until the next seek.
- Each decode thread keeps a local `flushGeneration`, seeded from `seekVersion` at thread
  start. The seed is load-bearing — `nativeOpen` and the `initialSeekMs == 0` demux path
  set startup targets with no generation bump and no marker. It advances only when the
  thread pops a tagged marker, and a pending target is ignored while
  `flushGeneration != seekVersion`, i.e. while the frame in hand predates the flush. The
  gate covers accurate seeks and scrubs on both the software and the AMediaCodec video
  path; the audio thread applies it in `processAudioFrame` (a scrub queues no audio).

Kotlin mirrors the same rule (`FfmpegNativeEngine`, `PlayerPositionController`):
`pendingSeekTargetMs` is an `AtomicLong` cleared only by a `compareAndSet` that still
matches the target it was read with, so a callback racing a newer `seekTo()` cannot drop
that seek's filter; `hasSeekLanded()` requires the position to have reached the target
from the seek's direction (500 ms tolerance), so a pre-seek position cannot satisfy a
backward seek; and `accumulatedSeekTarget` is held for `SEEK_SETTLE_MS` (800 ms) after the
last seek or scrub, so the 250 ms tick keeps reporting the requested position until the
engine lands on it.

---

## VideoPlayerScreen — Gestures

Gestures are applied with the `Modifier.playerGestures(...)` modifier
(`PlayerGestures.kt`) and are **mutually exclusive** — one per touch sequence:

- **Single tap**: toggle the controls overlay (auto-hide ~3 s).
- **Double tap left/right**: seek ∓10 s (`SeekIndicator` / `DragSeekIndicator`).
- **Swipe left half**: brightness. **Swipe right half**: volume.
- **Swipe up/down**: dismiss player (portrait). **Pinch / aspect button**: zoom-to-fit
  or fill (`AspectRatioMode`).
- **Hold**: temporary speed-up (ramps 2x→4x).
- Lock pill (`UnlockPill`) disables gestures; `GestureCueIndicators` gives visual feedback.

---

## Subtitle pipeline (libass)

All subtitle rendering goes through native libass for pixel-perfect ASS/SSA output:

1. **Embedded tracks** — `AssExtractorsFactory` + `AssMatroskaExtractor` intercept
   subtitle samples in the extractor chain → `AssTrackOutput` buffers them.
2. **External files** — `NeighborSubtitleDiscoverer` auto-detects sibling `.srt/.ass`
   (local + SMB); external ASS loads via `IPlayerEngine.loadExternalAss(uri)`.
3. **SRT/VTT** — `SubtitleConverters` converts to ASS on the fly for unified rendering.
4. **Rendering** — `AssHandler` feeds libass via `AssDirectBridge` (JNI), renders a
   bitmap per frame time → `SubtitleOverlayView`.
5. **Compose** — `AssSubtitleOverlay` wraps the overlay view in an `AndroidView`.

Track names resolve to languages + country flags via `SubtitleLanguageResolver`
(`FlagIcon` in the selection dialogs).

---

## Feature notes

| Feature | Behaviour |
|---|---|
| **Floating video player** | `FloatingVideoPlayer` — draggable, resizable PiP-style overlay that stays on top after leaving the full-screen player; play/pause, close, fullscreen-return, progress |
| **Audio queue** | `AudioQueueSheet` shows the now-playing list from `PlayerPlaylistController` via `PlayerUiState.audioQueue` / `audioQueueIndex`; tap to jump |
| **Sleep timer** | `SleepTimerDialog` — presets 15/30/45/60/90/120 min, end-of-video (−1), off; 1 Hz countdown via `PlayerViewModel.sleepTimerRemainingMs`; on expiry playback pauses and the timer resets |
| **A-B repeat** | `PlayerViewModel.onCycleAbRepeat()`: tap 1 sets A, tap 2 sets B (must be > A + 500 ms) and starts the loop, tap 3 clears. `startAbRepeatLoop()` collects the position flow and seeks back to A whenever it reaches B |
| **Chapters** | `PlayerViewModel.loadChaptersIfNeeded()` probes once per URI on IO via `MediaInfoProbe.probeChapters` (native FFmpeg demuxer: MKV chapters, MP4 chpl, OGG chapters) → `PlayerUiState.chapters`; `ChapterSelectionDialog` highlights the current chapter and seeks to `startMs` |
| **Audio delay** | `AudioDelaySink` (`ForwardingAudioSink`) shifts the audio clock; since ExoPlayer syncs video to it, A/V sync moves without touching the audio pipeline. Positive = audio heard later. Set via `IPlayerEngine.setAudioDelay`; persisted in `PlayerUiState.audioDelayMs` |
| **Play-as-audio** | From the video player, "Play as audio" hides the video surface and keeps playing; ViewModel sets `isVideo=false` + `playingVideoAsAudio=true`, `MiniPlayerBar` takes over, and the floating player or back-navigation restores video |

Subtitle timing offset is exposed via `IPlayerEngine.setSubtitleDelay/getSubtitleDelay`.

---

## Engine Selection (three engines)

Picked in Settings, persisted via `UserPreferencesRepository.activeEngine`:

| Engine | Backing | Use case |
|---|---|---|
| `EXO_PLAYER` | Media3 ExoPlayer, platform decoders first | Default; best battery + format coverage |
| `FFMPEG` | Same ExoPlayer pipeline, FFmpeg software renderers preferred (`HzRenderersFactory.preferFfmpeg`) | Formats the platform decoder mishandles |
| `NATIVE_FFMPEG` | Standalone native player (`FfmpegNativeEngine` + `cpp/ffplayer/`, `libffplayer.so`) | Instant seeking on local/networked media; AMediaCodec HW decode with libdav1d fallback |

Switching stops current playback and rebuilds the render surface (`PlayerSurface`
keys on `engineType`). The native engine opts out of the system MediaSession.

Cold start: `_activeEngineType` holds the `EXO_PLAYER` default until the persisted
preference has been read (DataStore is async). `playVideo`/`playUri`/`playAudio`/playlists
wait for that first emission (`PlayerRepositoryImpl.engineReady`), and only the newest play
dispatches — a newer play or `stop()` supersedes a pending one. Without this, a cold-start
play started on the default engine while the collector later flipped the UI to an idle
engine (black video, `0:00`). `startPlaybackService()` runs inside the same block so the
MediaSession wraps the settled engine.

`NATIVE_FFMPEG` plays web `http(s)` and HLS (VOD + live). It does **not** play DASH: a
`.mpd` URL fails fast with `player_error_dash_native`.

### Web playback on the native engine

- **Headers:** `sanitizeHttpRequestHeaders` (`core/util/HttpHeaderSanitizer.kt`) applies the
  same rules as `MediaPlayerHolder.setHttpRequestHeaders`, plus RFC 7230 token / no-CR-LF
  checks. `nativeOpen` drops any header containing a line break again.
- **TLS trust:** FFmpeg's mbedTLS loads no system store and has no `ca_path`.
  `NativeTlsCaBundle` exports the `system:` roots to `cacheDir/tls/cacert.pem`, skipping
  certificates mbedTLS cannot parse (it rejects the whole file otherwise). The file is rebuilt
  when it is missing, older than 7 days, or its PEM markers are gone (a truncated bundle would
  break every https open until it aged out). The path is passed
  for every `http`/`https` URL and set as `ca_file` + `tls_verify=1` on the open dictionary
  and, through `player_io_open` (`cpp/ffplayer/NetworkIo.cpp`), on every playlist, segment
  and key open. So `http -> https` redirects and `http` playlists with `https` segments
  verify. `tls_verify` is never disabled; an `https` URL with no bundle fails closed.
- **HLS:** `extension_picky=0` (segments with any extension), `seg_max_retry=3`, probe window
  3 s / 2 MiB for web sources, reconnect delay capped at 8 s (about 11 s of retries).
- **Seekability:** live HLS and range-less HTTP are marked not seekable. `publishSeekRequest`
  is the single gate (covers seek, scrub, EOF restart, resume seek); `seekTo` and
  `setScrubbing(true)` also return early. Seeks still go through `clampSeekPosition`.
  On such streams `nativePlay` does not re-seek; after real starvation it sets
  `forceNextFrame` instead.
- **Errors:** native code classifies the AVERROR (`classifyAvError`); Kotlin reads it through
  the lock-free `nativeGetLastErrorClass` and maps it with `NativeErrorMapper`. Local and SMB
  sources keep their raw messages.
- **Logs:** debug builds route FFmpeg WARNING+ to logcat tag `FFmpeg`.

---

## 10-Band Equalizer

Implemented once, exposed through the engine contract:

- `EqualizerController` is the single EQ state store for the app; both engines'
  mutators write through it.
- `ExoPlayerEngine` — `TenBandEqualizerProcessor` in the `AudioProcessor` chain +
  `EqualizerController` (bass boost, loudness) on the Exo audio session.
- `FfmpegNativeEngine` — mirrors the controller state into its native EQ and pushes its
  AudioTrack session id through `MediaPlayerHolder.setAudioSessionId`, so the same
  platform effects attach to whichever engine is playing.
- UI — `EqualizerSheet`: per-band sliders, device presets, bass boost, loudness; state
  flows as `StateFlow<EqualizerInfo>` and persists via `EqualizerSettings`.

---

## ExoPlayer Integration Points

| Feature | Media3 implementation |
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
| Seek clamping | `clampSeekPosition()` + `SEEK_END_MARGIN_MS` (shared, engine-agnostic) |
| A-B repeat | manual — `PlayerViewModel.startAbRepeatLoop()` loops A→B off the position flow |
| Sleep timer | manual — `PlayerViewModel` countdown + auto-pause |
| Chapters | `MediaInfoProbe.probeChapters()` (native FFmpeg demuxer) → `PlayerUiState.chapters` |
| Audio delay | `AudioDelaySink` (ForwardingAudioSink clock shift) |
