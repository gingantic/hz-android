# Engine Modularity — Multi-Backend Playback Architecture

> Goal: make `IPlayerEngine` the **only** playback contract. No Media3 type
> (`Player`, `PlayerView`, `Cue`, `MediaSession`, …) may cross the domain /
> presentation boundary. A second backend (libVLC, mpv, …) is added by writing
> one class + one Hilt binding + one `when` branch in the surface composable.
>
> **Status: IMPLEMENTED.** The refactor landed in commit `57e66db`. This doc
> describes the design that is now in code; the "implementation phases" below are
> historical and complete. Last refreshed: 2026-07-11.

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
│   MediaSessionProvider)  │            │  createRenderView() → its own │
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
    fun play(uri: String, title: String, artist: String? = null, isVideo: Boolean = false)
    fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long)
    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int)
    fun pause()
    fun resume()
    fun stop()

    // seek
    fun seekTo(positionMs: Long)
    fun skipForward(ms: Long = 10_000)
    fun skipBackward(ms: Long = 10_000)
    fun skipToNext()                 // replaces player.seekToNextMediaItem()
    fun skipToPrevious()
    fun getCurrentMediaItemIndex(): Int
    fun getMediaItemCount(): Int

    // queries
    fun isPlaying(): Boolean
    fun getDuration(): Long
    fun getCurrentPosition(): Long
    fun getBufferedPosition(): Long

    // config
    fun setPlaybackSpeed(speed: Float)
    fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    // tracks
    fun getSubtitleTracks(): List<String>
    fun getSelectedSubtitleTrack(): Int
    fun selectSubtitleTrack(index: Int)
    fun addExternalSubtitle(uri: Uri): Boolean
    fun setSubtitleDelay(delayMs: Long)
    fun getSubtitleDelay(): Long = 0
    fun getAudioTracks(): List<String>
    fun getSelectedAudioTrack(): Int
    fun selectAudioTrack(index: Int)

    // engine-specific extras (nullable → engine may not support)
    fun getDebugStats(): DebugStats? = null

    // lifecycle
    fun clearError()
    fun retry()
    fun release()
}
```

**No `android.view.View` lives in this interface.** Rendering is the engine's
private concern, surfaced through the `PlayerSurface` composable (§4), not the
contract. This keeps `domain` pure and lets a future engine paint however it
wants.

### 3.3 `domain/player/MediaSessionProvider.kt` (optional capability)
```kotlin
/** Implemented only by engines that can back a Media3 MediaSession. */
interface MediaSessionProvider {
    fun getMedia3Player(): androidx.media3.common.Player?
}
```
`ExoPlayerEngine` implements it (returns its `Player`). Non-Media3 engines do
not. The service asks `engine as? MediaSessionProvider`.

### 3.4 `domain/player/RenderViewConfig.kt`
```kotlin
data class RenderViewConfig(
    val aspectRatioMode: AspectRatioMode,
    val subtitleStyle: SubtitleStyle,
    val hdrEnabled: Boolean,
)
```

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

A single composable owns all engine-specific rendering. `VideoPlayerScreen`
never touches Media3:

```kotlin
@Composable
fun PlayerSurface(
    engine: IPlayerEngine,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    onRenderView: (View?) -> Unit,     // expose ref for lifecycle pause/resume
) {
    key(engine.engineType) {
        AndroidView(
            factory = { ctx ->
                when (engine.engineType) {
                    EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine)
                        .createRenderView(ctx, uiState.useSurfaceView)
                    // EngineType.VLC -> (engine as VlcEngine).createRenderView(ctx)
                    // EngineType.MPV -> (engine as MpvEngine).createRenderView(ctx)
                }.also { onRenderView(it) }
            },
            update = { view ->
                when (engine.engineType) {
                    EngineType.EXO_PLAYER -> (engine as ExoPlayerEngine)
                        .updateRenderView(view, RenderViewConfig(
                            uiState.aspectRatioMode, uiState.subtitleStyle, uiState.hdrEnabled))
                    // other branches delegate to their engine's updateRenderView
                }
            },
            modifier = modifier,
        )
    }
}
```

Lifecycle in `VideoPlayerScreen`:
```kotlin
val renderViewRef = remember<View?> { null }

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
`IPlayerEngine` is **not** given `onRenderViewPaused/Resumed` — those live on the
concrete engine class (e.g. `ExoPlayerEngine.onRenderViewPaused(view)` casts to
`PlayerView` and calls `.onPause()`). The composable calls them through the
typed cast in the `when`.

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
- `implements MediaSessionProvider { override fun getMedia3Player() = player }`.
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
- `MediaPlaybackService`: inject engine map + prefs; build `MediaSession` only when
  `activeEngine as? MediaSessionProvider != null`.

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
       override fun getDebugStats(): DebugStats? = null   // optional
   }
   ```
   - `createRenderView(ctx)` returns the engine's own `SurfaceView`/`TextureView`
     (and wires it to the VLC `IVLCVout`).
   - `updateRenderView(view, config)` applies aspect ratio + subtitle style.
   - `onRenderViewPaused/Resumed(view)` release/reattach the surface.
   - If it can back system media control, `implement MediaSessionProvider`.

3. **Bind it in Hilt** — one line in `PlayerEngineModule`:
   ```kotlin
   @Binds @IntoMap @EngineKey(EngineType.VLC)
   abstract fun bindVlc(impl: VlcEngine): IPlayerEngine
   ```

4. **Add the surface branch** in `PlayerSurface`:
   ```kotlin
   EngineType.VLC -> (engine as VlcEngine).createRenderView(ctx)
   ```
   and the matching `update` + lifecycle branches.

That is the entire integration surface. Nothing in `PlayerViewModel`,
`PlayerRepositoryImpl` delegation, the controls overlay, or the settings switch
changes — they are engine-agnostic by construction.

---

## 6b. As-built notes (vs. the design above)

- **`MediaSessionProvider` is a standalone Hilt binding**, not reached via
  `engine as?`. `PlayerEngineModule` binds `ExoPlayerMediaSessionProvider` directly to
  `MediaSessionProvider`; `MediaPlaybackService` injects it and builds the `MediaSession`
  from `getMedia3Player()`. Simpler than the `as?` cast and avoids leaking the question
  into the service.
- **Error mapping is in place**: `domain/player/PlaybackErrorMapper.kt` produces a
  redacted `(PlaybackErrorKind, message)` from `PlaybackException`; `PlayerStateInfo`
  carries `errorKind`/`errorMessage`; `PlaybackErrorOverlay` consumes it. The
  `subtitleCueTexts`/`subtitleCues` boundary types mentioned in §5 were dropped — native
  `PlayerView` renders subtitles.
- **`EngineType` currently has only `EXO_PLAYER`.** `PlayerRepositoryImpl` falls back to
  `EXO_PLAYER` if a persisted engine isn't in the binding map, so stale prefs are safe.
- **`getMedia3Player()` lives on the provider**, returning `playerHolder.player`. The
  service asks only for the provider, never for the engine map.

## 7. Deferred (YAGNI until a real 2nd engine exists)

- **Lazy release of non-active engines.** Today both engine singletons stay alive
  on switch. For Exo-only that is free. When a heavy native player (mpv/VLC) is
  added, release the inactive instance and rebuild on demand.
- **`MediaSession` for non-Media3 engines.** Out of scope; system media controls
  only work with `EXO_PLAYER` until a 2nd engine implements `MediaSessionProvider`.
- **Custom subtitle overlay.** `SubtitleOverlay` is already commented out; native
  rendering suffices. Revisit only if a 2nd engine lacks native subtitle painting.
- **Hot mid-playback decoder handoff.** Switching engines stops playback and
  restarts on the new engine. Full seamless handoff is not needed.
