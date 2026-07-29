# Engine Modularity — Multi-Backend Playback Architecture

> Goal: make `IPlayerEngine` the **only** playback contract. No Media3 type
> (`Player`, `PlayerView`, `Cue`, `MediaSession`, …) may cross the domain /
> presentation boundary. A second backend (libVLC, mpv, …) is added by writing
> one class + one Hilt binding. The render seam lives on the interface itself —
> `PlayerSurface` needs **zero** changes for a new engine.
>
> **Status: IMPLEMENTED.** The refactor landed in commit `57e66db`. This doc
> describes the design that is now in code; the "implementation phases" below are
> historical and complete. Last refreshed: 2026-07-29 (audio delay, HTTP headers
> on play, expanded interface contract).

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
    // VLC, MPV added later — alphabetical, stable names (persisted to DataStore).
}
```

### 3.2 `domain/player/IPlayerEngine.kt` (the full contract)
```kotlin
interface IPlayerEngine {

    val engineType: EngineType
    val playbackState: StateFlow<PlayerStateInfo>

    // playback
    fun play(uri: String, title: String, artist: String? = null, isVideo: Boolean = false, mimeType: String? = null, resumePositionMs: Long = 0, headers: Map<String, String> = emptyMap())
    fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long)
    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int)
    fun pause()
    fun resume()
    fun stop()

    // seek
    fun seekTo(positionMs: Long)
    fun skipForward(ms: Long = 10_000)
    fun skipBackward(ms: Long = 10_000)
    fun skipToNext()
    fun skipToPrevious()
    fun getCurrentMediaItemIndex(): Int
    fun getMediaItemCount(): Int
    fun seekToMediaItem(index: Int)

    // queries
    fun isPlaying(): Boolean
    fun getDuration(): Long
    fun getCurrentPosition(): Long
    fun getBufferedPosition(): Long

    // config
    fun setPlaybackSpeed(speed: Float)
    fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)
    fun isShuffleEnabled(): Boolean = false
    fun getRepeatMode(): RepeatMode = RepeatMode.NONE
    fun setDecoderMode(mode: DecoderMode) {}

    // tracks — subtitles
    fun getSubtitleTracks(): List<String>
    fun getSelectedSubtitleTrack(): Int
    fun selectSubtitleTrack(index: Int)
    fun loadExternalAss(uri: Uri)
    fun getSubtitleTrackMimeTypes(): List<String?> = emptyList()
    fun addExternalSubtitle(uri: Uri): Boolean
    var subtitleTrackChangeListener: (() -> Unit)?
    fun setSubtitleDelay(delayMs: Long)
    fun getSubtitleDelay(): Long = 0

    // tracks — audio
    fun getAudioTracks(): List<String>
    fun getSelectedAudioTrack(): Int
    fun selectAudioTrack(index: Int)
    fun setAudioDelay(delayMs: Long) {}      // NEW: A/V sync offset
    fun getAudioDelay(): Long = 0             // NEW: A/V sync offset

    // engine-specific extras (nullable → engine may not support)
    fun getDebugStats(): DebugStats? = null

    // render seam (on the interface — no casts needed in PlayerSurface)
    fun createRenderView(context: Context, useSurfaceView: Boolean): View
    fun updateRenderView(view: View, config: RenderViewConfig)
    fun onRenderViewPaused(view: View)
    fun onRenderViewResumed(view: View)

    // MediaSession bridge
    fun getMedia3Player(): Player? = null
    fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {}

    // lifecycle
    fun clearError()
    fun retry()
    fun release()
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
    // future: @Binds @IntoMap @EngineKey(EngineType.VLC) abstract fun bindVlc(impl: VlcEngine): IPlayerEngine
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
  `Authorization` / stream tokens forwarded from VIEW intents).
- **`EngineType` currently has only `EXO_PLAYER`.** `PlayerRepositoryImpl` falls back to
  `EXO_PLAYER` if a persisted engine isn't in the binding map, so stale prefs are safe.

## 7. Deferred (YAGNI until a real 2nd engine exists)

- **Lazy release of non-active engines.** Today the single engine stays alive
  on switch. When a heavy native player (mpv/VLC) is
  added, release the inactive instance and rebuild on demand.
- **`MediaSession` for non-Media3 engines.** Out of scope; system media controls
  only work with `EXO_PLAYER` until a 2nd engine implements `getMedia3Player()`.
- **Custom subtitle overlay.** `SubtitleOverlay` is already commented out; native
  rendering suffices. Revisit only if a 2nd engine lacks native subtitle painting.
- **Hot mid-playback decoder handoff.** Switching engines stops playback and
  restarts on the new engine. Full seamless handoff is not needed.
