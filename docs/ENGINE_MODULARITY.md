# Engine Modularity — Multi-Backend Playback Architecture

> Goal: make `IPlayerEngine` the **only** playback contract. No Media3 type
> (`Player`, `PlayerView`, `Cue`, `MediaSession`, …) may cross the domain /
> presentation boundary. A new backend is added by writing
> one class + one Hilt binding. The render seam lives on the interface itself —
> `PlayerSurface` needs **zero** changes for a new engine.
>
> **Status: IMPLEMENTED — three engines bound.** The refactor landed in commit
> `57e66db`; the design below is in code and the "implementation phases" are
> historical. Engines today: `EXO_PLAYER`, `FFMPEG`, `NATIVE_FFMPEG`.
> Last refreshed: 2026-08-22 (three engines, equalizer contract, native player).

---

## 1. Motivation & current leaks

ExoPlayer is currently wired directly into several layers. Every one of these is
a hard coupling that blocks a second engine:

| Leak | Location | Why it blocks a 2nd engine |
|---|---|---|
| `playerView.player = getExoPlayer()` | `VideoPlayerScreen` | `PlayerView` is Media3-only; VLC/mpv expose their own `Surface`/`View`. |
| `PlayerRepositoryImpl.exoPlayer: Player` | `PlayerRepositoryImpl` | Drives playlist next/prev + skip via `.player`. A non-Media3 engine has no `Player`. |
| `subtitleCues: StateFlow<Cue>` | `MediaPlayerHolder` → repo → VM | `Cue` is a Media3 subtitle type; a 2nd engine renders subtitles differently. |
| `collectDebugStats(): DebugStats` | `ExoPlayerEngine` | Exo-only; not every engine can produce the same stats. |
| `playPlaylist` / `playAudioPlaylist` | `ExoPlayerEngine` (not in interface) | Called straight from the repo, bypassing `IPlayerEngine`. |
| `player.shuffleModeEnabled` / `repeatMode` | `PlayerRepositoryImpl` | Direct `.player` mutation. |
| `MediaSession(playerHolder.player)` | `MediaPlaybackService` | `MediaSession` requires a Media3 `Player`. |

The engine-selection preference **already exists** (`UserPreferencesRepository.activeEngine`
+ `setActiveEngine`) but is unused. This refactor wires it up.

---

## 2. Target layering

```
┌──────────────────────────────────────────────────────────────────────┐
│  VideoPlayerScreen / AudioPlayerScreen                                │
│   • No Media3 imports. Renders via PlayerSurface(engineType, engine). │
│   • engine = viewModel.getActiveEngine()                              │
└───────────────────────────┬──────────────────────────────────────────┘
                             │ IPlayerEngine (pure contract)
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│  PlayerRepositoryImpl                                                  │
│   • Map<EngineType, IPlayerEngine> (Hilt @IntoMap)                    │
│   • activeEngine resolved from UserPreferencesRepository.activeEngine │
│   • delegates every call to the active engine                         │
│   • setActiveEngine(): stop old, swap pointer, persist                │
└───────────┬──────────────────────────────────────┬───────────────────┘
            │ binds                                 │ binds
            ▼                                       ▼
┌──────────────────────────┐            ┌──────────────────────────────┐
│  ExoPlayerEngine         │            │  VlcEngine / MpvEngine (later)│
│  (implements             │            │  (implements IPlayerEngine)   │
│   IPlayerEngine +        │            │                              │
│   getMedia3Player())│            │  createRenderView() → its own │
│  createRenderView()→     │            │  SurfaceView / TextureView    │
│   PlayerView             │            │                              │
└───────────┬──────────────┘            └──────────────────────────────┘
            │ owns
            ▼
┌──────────────────────────┐
│  MediaPlayerHolder        │  (singleton; owns the single ExoPlayer)
│  (Exo-only, data layer)   │
└──────────────────────────┘
```

---

## 3. The contracts

### 3.1 `domain/player/EngineType.kt`
```kotlin
enum class EngineType {
    EXO_PLAYER,

    /** Same ExoPlayer pipeline, but the FFmpeg software renderers are indexed
     *  before the MediaCodec ones (Media3 extension-prefer semantics), forcing
     *  every FFmpeg-supported codec through software decode. MediaCodec stays
     *  as fallback. */
    FFMPEG,

    /** Standalone native FFmpeg engine (libffplayer.so). Bypasses ExoPlayer,
     *  LoadControl and buffer queues entirely. Features instant seeking on
     *  local and networked media. */
    NATIVE_FFMPEG,
}
```

### 3.2 `domain/player/IPlayerEngine.kt` (the contract — abridged)
Full KDoc-annotated source is the reference; the shape:

```kotlin
interface IPlayerEngine {
    val engineType: EngineType
    val playbackState: StateFlow<PlayerStateInfo>

    // playback
    fun play(uri, title, artist?, isVideo, mimeType?, resumePositionMs,
             headers: Map<String,String>, artworkUri?)   // headers for network streams;
                                                          // artworkUri feeds MediaSession metadata
    fun playPlaylist(items, startIndex, startPositionMs)
    fun playAudioPlaylist(items: List<AudioItem>, startIndex)
    fun pause() / resume() / stop()

    // seek
    fun seekTo(ms) / skipForward(ms) / skipBackward(ms)
    fun setScrubbing(isScrubbing) {}          // fast keyframe live-scrub flag
    fun skipToNext() / skipToPrevious()
    fun getCurrentMediaItemIndex() / getMediaItemCount() / seekToMediaItem(index)

    // queries
    fun isPlaying() / getDuration() / getCurrentPosition() / getBufferedPosition()

    // config
    fun setPlaybackSpeed(speed) / setShuffleEnabled(b) / setRepeatMode(mode)
    fun setDecoderMode(mode) {}               // SW/HW decoder selection
    fun setDisableHdr(disabled) {}            // forced SDR tone-mapping toggle
    fun setFfmpegPreferred(preferred) {}      // EngineType.FFMPEG renderer reordering

    // tracks — subtitles (embedded via extractor interception, external via libass)
    fun getSubtitleTracks() / getSelectedSubtitleTrack() / selectSubtitleTrack(index)
    fun loadExternalAss(uri) / getSubtitleTrackMimeTypes(): List<String?>
    fun addExternalSubtitle(uri): Boolean
    var subtitleTrackChangeListener: (() -> Unit)?
    fun setSubtitleDelay(ms) / getSubtitleDelay()

    // tracks — audio + A/V sync
    fun getAudioTracks() / getSelectedAudioTrack() / selectAudioTrack(index)
    fun setAudioDelay(ms) {} / getAudioDelay()

    // equalizer — all default no-op/null so engines without EQ opt out
    fun getEqualizerState(): StateFlow<EqualizerInfo>? = null
    fun setEqualizerEnabled(b) {} / setEqualizerBandLevel(band, mb) {}
    fun applyEqualizerPreset(preset) {} / resetEqualizerBands() {}
    fun setBassBoostStrength(s) {} / setLoudnessGain(mb) {}

    // engine-specific extras
    fun getDebugStats(): DebugStats? = null

    // render seam (on the interface — no casts needed in PlayerSurface)
    fun createRenderView(context, useSurfaceView): View
    fun updateRenderView(view, config: RenderViewConfig)
    fun onRenderViewPaused(view) / onRenderViewResumed(view)

    // MediaSession bridge (sole deliberate Media3 leak)
    fun getMedia3Player(): Player? = null
    fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {}

    // lifecycle
    fun clearError() / retry() / release()
}
```

The render seam methods (`createRenderView`, `updateRenderView`, `onRenderViewPaused`,
`onRenderViewResumed`) are **on the interface** — `PlayerSurface` calls them directly
without any `when`/cast. A new engine simply implements them and the surface works.

### 3.3 `IPlayerEngine.getMedia3Player()` — Media3 MediaSession bridge

`MediaSessionProvider` was removed in favor of `IPlayerEngine.getMedia3Player()` and
`setOnPlayerReplacedListener()` — the engine contract itself handles the session bridge,
so there is no separate provider interface. The service calls:

```
val player = engine.getMedia3Player()
mediaSession = player?.let { MediaSession.Builder(this, it).build() }
```

Non-Media3 backends return `null` and skip system media controls (lock screen, PiP).

### 3.4 `domain/player/RenderViewConfig.kt`

```kotlin
data class RenderViewConfig(
    val aspectRatioMode: AspectRatioMode,
)
```

Pushed through `engine.updateRenderView(view, config)` for the active engine's
render view (aspect ratio only; subtitle styling runs through the engine's own
pipeline).

### 3.5 `di/EngineKey.kt`
```kotlin
@MapKey
annotation class EngineKey(val value: EngineType)
```

### 3.6 `PlayerRepository` interface additions
`val availableEngines: List<EngineType>`, `fun setActiveEngine(type: EngineType)`,
`fun getDebugStats(): DebugStats?`, `fun skipToNext()`, `fun skipToPrevious()`,
`fun setShuffleEnabled(Boolean)`, `fun setRepeatMode(RepeatMode)`.

---

## 4. Presentation seam — `PlayerSurface`

A single composable owns all engine-specific rendering. The render methods are on
`IPlayerEngine` directly — no `when` branches, no casts, no per-engine code:

```kotlin
@Composable
fun PlayerSurface(
    engine: IPlayerEngine,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    onRenderView: (View?) -> Unit,
) {
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
            modifier = modifier,
        )
    }
}
```

Lifecycle in `VideoPlayerScreen`:
```kotlin
DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
        when (e) {
            ON_STOP -> { renderViewRef?.let { engine.onRenderViewPaused(it) }; viewModel.pause() }
            ON_RESUME -> { renderViewRef?.let { engine.onRenderViewResumed(it) } }
            else -> {}
        }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
}
```
`onRenderViewPaused/Resumed` are **on the interface** — no typed cast needed.

---

## 5. Implementation phases

### Phase 1 — Domain interface (`IPlayerEngine`)
Promote `playPlaylist`, `playAudioPlaylist`, add `skipToNext/Previous`,
`getCurrentMediaItemIndex/Count`, `setShuffleEnabled`, `setRepeatMode`,
`getDebugStats()`, `clearError`/`retry` already present. Add §3.3–3.5.

### Phase 2 — `ExoPlayerEngine`
- Implement every new interface method (delegate to `playerHolder.player` exactly
  as today: `playPlaylist`/`playAudioPlaylist` move in from the impl; `skipToNext`
  → `player.seekToNextMediaItem()`; `setShuffleEnabled` → `player.shuffleModeEnabled = …`;
  `setRepeatMode` → `player.repeatMode = …`).
- `collectDebugStats()` → override `getDebugStats(): DebugStats?` (same body).
- `createRenderView(ctx, useSurfaceView)` inflates `view_exo_player_surface` /
  `view_exo_player` (existing XML), sets `playerView.player = player` + applies the
  `CaptionStyleCompat` (HDR legibility) already in `VideoPlayerScreen`. Returns the
  `PlayerView`.
- `updateRenderView(view, config)` maps `AspectRatioMode` → `RESIZE_MODE_*` and
  reapplies subtitle style.
- `onRenderViewPaused(view)` / `onRenderViewResumed(view)` → `PlayerView.onPause/onResume`.
- `after: getMedia3Player { override fun getMedia3Player() = player }`.
- **Remove** `subtitleCues`/`videoDecoderName`/`audioDecoderName`/`pollRenderedFps`
  public getters that cross into the boundary. Keep internal decoder counters in
  `MediaPlayerHolder` for `getDebugStats()` only.

### Phase 3 — Repository (`PlayerRepositoryImpl`)
- Inject `Map<EngineType, @JvmSuppressWildcards IPlayerEngine>` instead of concrete
  `ExoPlayerEngine`.
- `activeEngine` getter = map[userPrefs.activeEngine] ?: map[EXO_PLAYER].
- `availableEngines` = map.keys.toList().
- `setActiveEngine(type)`: `activeEngine.stop()` (do **not** release — keep instance
  for switch-back); update active pointer; `userPrefs.setActiveEngine(type)`.
- All repo methods delegate to `activeEngine`. Delete `val exoPlayer: Player`,
  `val subtitleCues`, `fun collectDebugStats()`; replace with `getDebugStats()`
  (nullable guard). `skipToNext/Previous`, `setShuffleEnabled`, `setRepeatMode`
  delegate to interface.

### Phase 4 — DI (`di/PlayerEngineModule`)
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerEngineModule {
    @Binds @IntoMap @EngineKey(EngineType.EXO_PLAYER)
    abstract fun bindExo(impl: ExoPlayerEngine): IPlayerEngine

    // FFMPEG reuses the same ExoPlayerEngine singleton; the repository flips
    // HzRenderersFactory.preferFfmpeg when this type is active.
    @Binds @IntoMap @EngineKey(EngineType.FFMPEG)
    abstract fun bindFfmpeg(impl: ExoPlayerEngine): IPlayerEngine

    // Standalone native player (libffplayer.so).
    @Binds @IntoMap @EngineKey(EngineType.NATIVE_FFMPEG)
    abstract fun bindNative(impl: FfmpegNativeEngine): IPlayerEngine
}
```
`MediaPlayerHolder` stays `@Singleton` owning the one ExoPlayer; `ExoPlayerEngine`
still depends on it.

### Phase 5 — Presentation
- `VideoPlayerScreen`: delete every `androidx.media3.*` import; replace surface block
  with `PlayerSurface`. `playerViewRef` → `renderViewRef: View?`.
- `PlayerViewModel`: delete `getExoPlayer()`; `onSkipNext/Previous` →
  `playerRepository.skipToNext/Previous()`; `observeSubtitleCues()` deleted;
  debug polling uses `playerRepository.getDebugStats()` guarded for null; expose
  `uiState.activeEngineType` (= `engine.engineType`) so the surface `key` recomposes
  on switch.
- `PlayerUiState`: drop `subtitleCueTexts` (unused — native `PlayerView` renders subs);
  add `activeEngineType: EngineType`.
- `MediaPlaybackService`: build `MediaSession` via `engine.getMedia3Player()` — no
  separate provider indirection.

### Phase 6 — Settings UI
Replace the `ExoPlayer not_implemented` row with an engine selector listing
`playerRepository.availableEngines`, calling `setActiveEngine`. Switching stops
current playback (SurfaceView/engine swap on next play).

---

## 6. How to attach a NEW engine (checklist)

When adding libVLC or mpv later:

1. **Add the enum value** in `EngineType.kt`:
   ```kotlin
   enum class EngineType { EXO_PLAYER, VLC }
   ```

2. **Create the engine class** in `data/datasource/player/`, implement `IPlayerEngine`:
   ```kotlin
   @Singleton
   class VlcEngine @Inject constructor(@ApplicationContext ctx: Context) : IPlayerEngine {
       override val engineType = EngineType.VLC
       // play/pause/seek/tracks/speed via the VLC MediaPlayer API…
       override fun createRenderView(context: Context, useSurfaceView: Boolean): View { ... }
       override fun updateRenderView(view: View, config: RenderViewConfig) { ... }
       override fun onRenderViewPaused(view: View) { ... }
       override fun onRenderViewResumed(view: View) { ... }
       override fun getDebugStats(): DebugStats? = null   // optional
   }
   ```

3. **Bind it in Hilt** — one line in `PlayerEngineModule`:
   ```kotlin
   @Binds @IntoMap @EngineKey(EngineType.VLC)
   abstract fun bindVlc(impl: VlcEngine): IPlayerEngine
   ```

That is the entire integration surface. `PlayerSurface`, `PlayerViewModel`,
`PlayerRepositoryImpl` delegation, the controls overlay, and the settings switch
are all engine-agnostic by construction — **zero** changes needed.

---

## 6b. As-built notes (vs. the design above)

- **Render seam is on `IPlayerEngine`** (`createRenderView`, `updateRenderView`,
  `onRenderViewPaused/Resumed`). `PlayerSurface` calls them directly — no `when`
  branches, no typed casts. A new engine needs zero surface changes.
- **`getMedia3Player()` lives on `IPlayerEngine`**, not a separate provider.
  `MediaPlaybackService` calls `engine.getMedia3Player()` directly — no separate
  `MediaSessionProvider` interface or Hilt binding.
- **Error mapping is in place**: `domain/player/PlaybackErrorMapper.kt` produces a
  redacted `(PlaybackErrorKind, message)` from `PlaybackException`; `PlayerStateInfo`
  carries `errorKind`/`errorMessage`; `PlaybackErrorOverlay` consumes it.
- **libass subtitle pipeline** intercepts embedded tracks and renders them natively;
  `loadExternalAss(uri)` and `getSubtitleTrackMimeTypes()` are on the interface.
- **`setAudioDelay(delayMs)` / `getAudioDelay()`** on the interface — ExoPlayer
  implements via `AudioDelaySink` (a `ForwardingAudioSink` that shifts the audio
  clock). Non-Media3 engines can leave the default no-op.
- **`play(uri, …, headers)`** accepts HTTP headers for network requests (e.g.
  `Authorization` / stream tokens forwarded from VIEW intents); `artworkUri` feeds
  MediaSession metadata.
- **Equalizer contract on the interface** (`getEqualizerState()` + band/preset/bass/
  loudness controls). `ExoPlayerEngine` implements via `TenBandEqualizerProcessor`
  in the `AudioProcessor` chain; the native engine wires the same `EqualizerController`
  to its AudioTrack session id. State exposed as `StateFlow<EqualizerInfo>` and
  persisted via `EqualizerSettings`.
- **Three engines are bound** (see §3.1 / Phase 4): `EXO_PLAYER`,
  `FFMPEG` (same `ExoPlayerEngine` singleton, FFmpeg renderers preferred), and
  `NATIVE_FFMPEG` (`FfmpegNativeEngine`, standalone `libffplayer.so`). The Settings
  engine selector lists all three; `PlayerRepositoryImpl` falls back to
  `EXO_PLAYER` if a persisted engine isn't in the binding map, so stale prefs are safe.

## 7. Deferred (known ceilings, revisit when they bite)

- **Lazy release of non-active engines.** Engines stay alive on switch. The
  native engine is now the heavy case — release inactive instances and rebuild
  on demand if memory pressure shows up.
- **`MediaSession` for non-Media3 engines.** `FfmpegNativeEngine.getMedia3Player()`
  returns `null`, so system media controls (lock screen, PiP) only work with the
  ExoPlayer-backed engines today.
- **Hot mid-playback decoder handoff.** Switching engines stops playback and
  restarts on the new engine. Full seamless handoff is not needed.
