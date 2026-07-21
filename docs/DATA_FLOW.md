# Hz Player — Data Flow Architecture

> How data moves from persistence to pixels.
> Last refreshed: 2026-07-21. Reflects the modular `IPlayerEngine` seam, the remote
> network stack, resumable playback, libass subtitle pipeline, and archive support.

---

## Layer Responsibilities

```
┌────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION                               │
│  Screen (stateless) — collects StateFlow via collectAsStateWithLife │
│  ViewModel (@HiltViewModel) — owns MutableStateFlow<UiState>        │
│  UiState — immutable snapshot                                       │
│  PlayerSurface — the ONLY place rendering touches the engine type   │
└───────────────────────┬────────────────────────────────────────────┘
                        │ StateFlow<UiState> / callbacks
┌───────────────────────▼────────────────────────────────────────────┐
│                          DOMAIN                                    │
│  Model (pure Kotlin) • Repository interfaces • IPlayerEngine        │
│  UseCase layer (none currently -- ViewModels call repos directly)                        │
└───────────────────────┬────────────────────────────────────────────┘
                        │ Flow / suspend
┌───────────────────────▼────────────────────────────────────────────┐
│                           DATA                                     │
│  Repository impls • Datasources (Room / MediaStore / DataStore /    │
│  ExoPlayer / network clients / connection pool) • Entities          │
└───────────────────────┬────────────────────────────────────────────┘
                        │ SQLite / ContentProvider / network / JNI
```

---

## Read Flows

### Video Library (with resume badges)
```
VideoLibraryScreen
  ← VideoLibraryViewModel.uiState
    ← MediaRepository.getAllVideos()        // MediaStore → Room cache
      ← MediaScanner (refresh) + MediaDao
    ← ResumeRepository.getResumeFor(id)     // shows "resume from 01:23"
```

### Audio Browser + Detail
```
AudioBrowserScreen
  ← AudioRepository.getAlbums() / getArtists() / getTracks()
AlbumDetailScreen(album)
  ← AudioRepository.getTracksForAlbum(title)
ArtistDetailScreen(name)
  ← AudioRepository.getAlbumsForArtist(name)
```

### File Browser
```
FileBrowserScreen
  ← FileRepository.listDirectory(path)      // SAF / MediaStore
```

### Network Browse (remote server)
```
NetworkScreen → ServerCard tap
  → Navigates to FileBrowser-style listing backed by:
  RemoteBrowseRepositoryImpl.listDirectory(serverConfig, path)
    → RemoteBrowserClient (Smb/Ftp/Sftp/WebDav)      // ConnectionPool reuse
    → maps RemoteFileItem → domain
```

### Archive Browse (virtual folder)
```
FileBrowserScreen → tap archive file
  → FileBrowserViewModel.onOpenArchive(path)
    → ArchiveRepository.listEntries(archivePath, password)
      → ArchiveNative.nativeList (JNI → libarchive)
    → synthesizes virtual directory levels from entry paths
    → BreadcrumbBar shows archive layers
  → tap media entry → play via archive:// URI
```

---

## Write Flows

### Play (local or remote, identical call path)
```
Screen → ViewModel.onPlay(item)
  → PlayerRepository.playVideo / playAudio / playUri(uri, title, isVideo, mimeType, resumePositionMs)
    → activeEngine.play(uri, …)        // IPlayerEngine — no Media3 in caller
      → ExoPlayerEngine → MediaPlayerHolder.player.setMediaItem + prepare + play
    → NeighborSubtitleDiscoverer auto-loads sibling .srt/.ass files
    → startTrafficPolling() if uri is remote
  → playbackStateInfo Flow updates PlayerUiState
```

### Switch playback engine
```
SettingsScreen → onEngineSelected(type)
  → PlayerRepository.setActiveEngine(type)
    → active engine .stop() (kept alive for switch-back)
    → _activeEngineType.value = type
    → userPreferencesRepository.setActiveEngine(type)   // persisted
  → PlayerSurface key(activeEngineType) recomposes render view
```

### Persist playback position (resume)
```
PlayerPositionController (250ms tick)
  → periodic save: ResumeRepository.savePosition(mediaId, positionMs, durationMs)
    → PlaybackPositionDao upsert
  → on pause/stop: final save
```

### Change Sort
```
Screen → ViewModel.onSortChanged(SortType)
  → UserPreferencesRepository.setSortOrder(...)
    → DataStore.edit { prefs[sortKey] = sortType.name }
```

---

## Player Data Flow (through the engine seam)

```
ExoPlayer (in MediaPlayerHolder)
  → Player.Listener → MediaPlayerHolder updates PlayerStateInfo
    → ExoPlayerEngine.playbackState: StateFlow<PlayerStateInfo>
      → PlayerRepositoryImpl.playbackStateInfo (flatMapLatest over active engine)
        → PlayerViewModel maps → PlayerUiState
          → VideoPlayerScreen (controls) + PlayerSurface (render)
```

Only `ExoPlayerEngine` imports Media3 `Player`/`PlayerView`. `PlayerViewModel` and
both player screens import **only** `domain` types (`IPlayerEngine`, `PlayerUiState`,
`PlayerStateInfo`). New engines plug in without touching the ViewModel or screens.

### Subtitle pipeline (libass)
```
ExoPlayer extractor chain
  → AssExtractorsFactory / AssMatroskaExtractor intercept subtitle samples
    → AssTrackOutput buffers header + dialogue events
      → AssHandler receives raw ASS data
        → AssDirectBridge (JNI) → libass renders bitmap at time T
          → SubtitleOverlayView displays bitmap
            → AssSubtitleOverlay (Compose AndroidView wrapper)
```
SRT/VTT tracks are converted to ASS on-the-fly via `SubtitleConverters` before
feeding libass, giving unified rendering for all subtitle formats.

### Position & seek (high-frequency)
```
PlayerPositionController (250ms tick)
  → PlayerRepository.getCurrentPosition()
    → position: StateFlow<Long>  (separate from PlayerUiState)
      → only PlayerSeekBar recomposes per tick
```

### Seeking / track selection / error
- Seek: `ViewModel.onSeekTo` → `PlayerRepository.seekTo` → `engine.seekTo`.
- Tracks: `engine.getSubtitleTracks()/getAudioTracks()` surfaced to bottom sheets.
- Error: `MediaPlayerHolder.onPlayerError` → `PlaybackErrorMapper.map(error)`
  returns a redacted `(PlaybackErrorKind, message)`; `errorKind` drives the overlay
  icon + which errors get a Retry button (network/timeout/auth/file — not format).

---

## Data Source Strategy

| Source | Use | Notes |
|---|---|---|
| Room | Persistent cache for media index, server configs, resume positions, stream history | 4 DAOs; KSP-generated |
| MediaStore | System media index | `MediaScanner` syncs into Room |
| DataStore | User preferences (sort, theme, active engine, archive passwords) | Type-safe `Preferences` |
| Media3 ExoPlayer | Playback state | singleton in `MediaPlayerHolder` |
| Remote clients | SMB/FTP/SFTP/WebDAV browse + streaming | pooled in `ConnectionPool` |
| Native FFmpeg | Video thumbnails + codec metadata probe | JNI in `core/thumbnail` + `cpp/` |
| Native libass | ASS/SSA/SRT/VTT subtitle rendering | JNI in `data/datasource/subtitle/assrender` + `cpp/` |
| Native libarchive | Archive listing + streaming playback | JNI in `data/datasource/archive` + `cpp/` |
| Cloudflare R2 | OTA update checks + APK download | `UpdateChecker` reads `BuildConfig.R2_UPDATE_BASE_URL` |

---

## Threading Model

| Layer | Coroutine Context |
|---|---|
| UI (Composable) | Main (`collectAsStateWithLifecycle`) |
| ViewModel | Main (`viewModelScope`) |
| PlayerPositionController | Main (250ms tick via `delay`) |
| PlayerRepository | Main (engine delegation) + `Dispatchers.Default` for traffic polling |
| Repository (IO) | `Dispatchers.IO` |
| Room DAO | Auto-dispatched |
| MediaStore scan | `Dispatchers.IO` |
| Network clients | `ConnectionPool` threads + `Dispatchers.IO` |
| ExoPlayer | Own internal threads |
| Native thumbnail | JNI off the main thread (Coil fetcher scope) |
| Native libass | AssHandler renders on a background thread; bitmap posted to Main |
| Native libarchive | ExoPlayer playback thread (DataSource callbacks) |
| DataStore | Auto-dispatched (IO) |
