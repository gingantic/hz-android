# Hz Player — Data Flow Architecture

> How data moves from persistence to pixels.
> Verified against code at `8ffb763` — drift check: `git diff 8ffb763..HEAD -- app/src`.

---

## Layer Responsibilities

```
PRESENTATION  Screen (stateless, collectAsStateWithLifecycle) → ViewModel
              (@HiltViewModel, MutableStateFlow<UiState>) → immutable UiState
              PlayerSurface is the ONLY place rendering touches the engine type
     │ StateFlow<UiState> / callbacks
DOMAIN        Model (pure Kotlin) • Repository interfaces • IPlayerEngine
              UseCase layer — none; ViewModels call repositories directly
     │ Flow / suspend
DATA          Repository impls • Datasources (Room / MediaStore / DataStore /
              ExoPlayer / network clients / connection pool) • Entities
     │ SQLite / ContentProvider / network / JNI
```

Layer boundaries and package layout: `docs/ARCHITECTURE.md`.

---

## Read Flows

```
VideoLibraryScreen ← VideoLibraryViewModel.uiState
  ← MediaRepository.getAllVideos()        // MediaStore → Room cache (MediaScanner + MediaDao)
  ← ResumeRepository.getResumeFor(id)     // "resume from 01:23" badge

AudioBrowserScreen  ← AudioRepository.getAlbums() / getArtists() / getTracks()
AlbumDetailScreen   ← AudioRepository.getTracksForAlbum(title)
ArtistDetailScreen  ← AudioRepository.getAlbumsForArtist(name)

FileBrowserScreen   ← FileRepository.listDirectory(path)      // SAF / MediaStore

NetworkScreen → ServerCard tap → file-browser-style listing
  ← RemoteBrowseRepositoryImpl.listDirectory(serverConfig, path)
    → RemoteBrowserClient (Smb/Ftp/Sftp/WebDav)               // ConnectionPool reuse
    → maps RemoteFileItem → domain

FileBrowserScreen → tap archive → FileBrowserViewModel.onOpenArchive(path)
  → ArchiveRepository.listEntries(archivePath, password)
    → ArchiveNative.nativeList (JNI → libarchive)
    → synthesizes virtual directory levels from entry paths (BreadcrumbBar shows layers)
  → tap media entry → play via archive:// URI

BrowserActivity (separate activity) → BrowserViewModel
  → TabManager (BrowserTab stack; swipe-to-switch with slide animation)
  → AdBlockEngine (Rust ad filtering via JNI + filter-list updates)
  → MediaSnifferEngine (detects video/audio on pages)
  → BrowserScreen: WebView per tab (pooled via BrowserSessionStore)
    · BrowserHistoryRepository (persisted history)
    · UrlSuggestionsPanel (Chrome-like omnibox suggestions)
    · MediaGrabberBottomSheet (stream detected media to the player)
    · PopupPermissionBottomSheet (cross-domain popup approval)
    · Real desktop mode (desktop UA + wide viewport)
    · Web PiP bridge: site PiP button → native Picture-in-Picture window
    · WebView-native certificate handling (invalid certs cancelled; renderer
      crashes reload a fresh blank renderer)
```

---

## Write Flows

### Play (local or remote — identical call path)
```
Screen → ViewModel.onPlay(item)
  → PlayerRepository.playVideo / playAudio / playUri(uri, title, isVideo, mimeType,
                                                    resumePositionMs, headers)
    → activeEngine.play(uri, …, headers)      // IPlayerEngine — no Media3 in the caller
      → ExoPlayerEngine → MediaPlayerHolder.player.setMediaItem + prepare + play
    → NeighborSubtitleDiscoverer auto-loads sibling .srt/.ass files
    → startTrafficPolling() when the URI is remote
  → playbackStateInfo Flow updates PlayerUiState
```

### Play-as-audio (video played audio-only)
```
VideoPlayerScreen → onPlayAsAudio()
  → PlayerViewModel sets isVideo=false, playingVideoAsAudio=true
  → engine keeps playing; video surface hidden; MiniPlayerBar takes over
  → return to video via FloatingVideoPlayer or navigating back
```

### Sleep timer
```
PlayerMoreOptionsSheet → onSleepTimerClick → SleepTimerDialog (presets / end-of-video / off)
  → PlayerViewModel.setSleepTimer(durationMs)
      -1 = end of video (auto-stop on PlayerState.ENDED)   >0 = countdown → pause + reset
       0 = cancel
  → sleepTimerRemainingMs (1 Hz StateFlow) drives the label in PlayerMoreOptionsSheet
```

### A-B repeat loop
```
PlayerMoreOptionsSheet → PlayerViewModel.onCycleAbRepeat()
  → tap 1: abLoopStartMs = current position (A set)
  → tap 2: abLoopEndMs = current position, must be > A + 500 ms (loop active)
  → tap 3: both cleared (off)
  → startAbRepeatLoop() collects the position flow in viewModelScope;
    position >= abLoopEndMs → onSeekTo(abLoopStartMs)
```

### Chapter navigation
```
PlayerViewModel.loadChaptersIfNeeded() — once per URI, on IO, for video only
  → MediaInfoProbe.probeChapters(context, uri)      // native FFmpeg demuxer
    → List<ChapterInfo> (startMs, endMs, title) → PlayerUiState.chapters
      → ChapterSelectionDialog (current-position highlight) → tap → seekTo(startMs)
```

### Audio delay (A/V sync offset)
```
PlayerMoreOptionsSheet / settings → onAudioDelayChanged(ms) → PlayerViewModel.setAudioDelay
  → engine.setAudioDelay(ms) → ExoPlayerEngine → AudioDelaySink.delayUs = ms * 1000
    → ForwardingAudioSink shifts getCurrentPositionUs
      → ExoPlayer syncs video to the shifted audio clock (no pipeline change)
```

### Switch playback engine
```
SettingsScreen → onEngineSelected(type) → PlayerRepository.setActiveEngine(type)
  → active engine .stop() (kept alive for switch-back)
  → _activeEngineType.value = type
  → userPreferencesRepository.setActiveEngine(type)          // persisted
  → FFMPEG also flips HzRenderersFactory.preferFfmpeg
    (NATIVE_FFMPEG swaps to the standalone native engine instance)
  → PlayerSurface key(activeEngineType) recomposes the render view
```

### Equalizer adjustment
```
EqualizerSheet → band slider / preset / bass / loudness
  → PlayerViewModel → PlayerRepository → activeEngine.setEqualizerBandLevel(band, mb)
      → ExoPlayerEngine: TenBandEqualizerProcessor (AudioProcessor chain)
      → FfmpegNativeEngine: mirrors EQ state into the native EQ; session effects attach
        via MediaPlayerHolder.audioSessionId (fed by both engines)
  → getEqualizerState(): StateFlow<EqualizerInfo> drives the sheet UI
  → UserPreferencesRepository persists EqualizerSettings across restarts
```

### Persist playback position (resume)
```
PlayerPositionController (250 ms tick)
  → periodic save: ResumeRepository.savePosition(mediaId, positionMs, durationMs)
    → PlaybackPositionDao upsert
  → final save on pause / stop / onCleared
```

### Change sort
```
Screen → ViewModel.onSortChanged(SortType) → UserPreferencesRepository.setSortOrder(...)
  → DataStore.edit { prefs[sortKey] = sortType.name }
```
---

## Player Data Flow (through the engine seam)

```
ExoPlayer (in MediaPlayerHolder)
  → Player.Listener → MediaPlayerHolder updates PlayerStateInfo
    → ExoPlayerEngine.playbackState: StateFlow<PlayerStateInfo>
      → PlayerRepositoryImpl.playbackStateInfo (flatMapLatest over the active engine)
        → PlayerViewModel maps → PlayerUiState
          → VideoPlayerScreen (controls) + PlayerSurface (render)
```

Only `ExoPlayerEngine` imports Media3 `Player`/`PlayerView`. `PlayerViewModel` and both
player screens import **only** domain types (`IPlayerEngine`, `PlayerUiState`,
`PlayerStateInfo`), so new engines plug in without touching the ViewModel or screens.

### Subtitle pipeline (libass)
```
ExoPlayer extractor chain
  → AssExtractorsFactory / AssMatroskaExtractor intercept subtitle samples
    → AssTrackOutput buffers header + dialogue events
      → AssHandler receives raw ASS data
        → AssDirectBridge (JNI) → libass renders a bitmap at time T
          → SubtitleOverlayView displays it → AssSubtitleOverlay (Compose AndroidView)
```
SRT/VTT tracks are converted to ASS on the fly via `SubtitleConverters` before feeding
libass, giving unified rendering for every subtitle format.

### Position & seek (high-frequency)
```
PlayerPositionController (250 ms tick)
  → position: StateFlow<Long>   (separate from PlayerUiState → only PlayerSeekBar recomposes)

Seek path — every engine clamps through the shared guard:
  ViewModel.onSeekTo / onScrub / onSkipForward|Backward
    → PlayerPositionController.clampSeekTarget()   // live engine duration, else UI-state
      → clampSeekPosition(ms, duration)            // ≥ 0, and 1 s short of the end
        → PlayerRepository.seekTo → activeEngine.seekTo → ExoPlayerEngine / FfmpegNativeEngine
  JumpToTimeDialog clamps the typed HH:MM:SS to the duration before calling onJump.
  Native seek landing is generation-gated — `docs/PLAYER_ARCHITECTURE.md` → "Seek transactions".
```

### Track selection / errors
- Tracks: `engine.getSubtitleTracks()/getAudioTracks()` surfaced to the bottom sheets.
- Error: `MediaPlayerHolder.onPlayerError` → `PlaybackErrorMapper.map(error)` returns a
  redacted `(PlaybackErrorKind, message)`; `errorKind` drives the overlay icon and which
  errors get a Retry button (network/timeout/auth/file — not format).

---

## Data Source Strategy

| Source | Use | Notes |
|---|---|---|
| Room | Persistent cache: media index, server configs, resume positions, stream history, browser history | 5 DAOs; KSP-generated; v6 (`headersJson`, `pageUrl`, `mimeType` on stream_history) |
| MediaStore | System media index | `MediaScanner` syncs into Room |
| DataStore | User preferences (sort, theme, active engine, archive passwords) | Type-safe `Preferences` |
| Media3 ExoPlayer | Playback state | singleton in `MediaPlayerHolder` (backs `EXO_PLAYER` + `FFMPEG`) |
| Native FFmpeg player | Standalone engine (`NATIVE_FFMPEG`) | `FfmpegNativeEngine` + `ffmpeg/FfmpegNativePlayer.kt` → `cpp/ffplayer/` (`libffplayer.so`) |
| Remote clients | SMB/FTP/SFTP/WebDAV browse + streaming | pooled in `ConnectionPool` |
| Native FFmpeg | Video thumbnails + codec metadata probe | `core/io/` sources + `core/thumbnail/` JNI + `cpp/` |
| Native libass | ASS/SSA/SRT/VTT subtitle rendering | JNI in `data/datasource/subtitle/assrender` + `cpp/` |
| Native libarchive | Archive listing + streaming playback | JNI in `data/datasource/archive` + `cpp/` |
| Native ad blocker | WebView ad filtering | Rust engine in `browser/adblock/` (JNI) |
| Cloudflare R2 | OTA update checks + APK download | `UpdateChecker` reads `BuildConfig.R2_UPDATE_BASE_URL` |

---

## Threading Model

| Layer | Coroutine context |
|---|---|
| UI (Composable) | Main (`collectAsStateWithLifecycle`) |
| ViewModel | Main (`viewModelScope`) |
| PlayerPositionController | Main (250 ms tick via `delay`) |
| PlayerRepository | Main (engine delegation) + `Dispatchers.Default` for traffic polling |
| Repository (IO) | `Dispatchers.IO` |
| Room DAO | Auto-dispatched |
| MediaStore scan | `Dispatchers.IO` |
| Network clients | `ConnectionPool` threads + `Dispatchers.IO` |
| ExoPlayer | Own internal threads |
| Native thumbnail | JNI off the main thread (Coil fetcher scope) |
| Native libass | `AssHandler` renders on a background thread; bitmap posted to Main |
| Native libarchive | ExoPlayer playback thread (DataSource callbacks) |
| DataStore | Auto-dispatched (IO) |
