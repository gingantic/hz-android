# Hz Player — Data Flow Architecture

> How data moves from persistence to pixels.
> Last refreshed: 2026-07-11. Reflects the modular `IPlayerEngine` seam, the remote
> network stack, and resumable playback.

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

---

## Write Flows

### Play (local or remote, identical call path)
```
Screen → ViewModel.onPlay(item)
  → PlayerRepository.playVideo / playAudio / playUri(uri, title, isVideo, mimeType)
    → activeEngine.play(uri, …)        // IPlayerEngine — no Media3 in caller
      → ExoPlayerEngine → MediaPlayerHolder.player.setMediaItem + prepare + play
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
PlayerRepository.playbackStateInfo (on pause / stop)
  → ResumeRepository.savePosition(mediaId, positionMs, durationMs)
    → PlaybackPositionDao upsert
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
| Room | Persistent cache for media index, server configs, resume positions, stream history | 5 DAOs; KSP-generated |
| MediaStore | System media index | `MediaScanner` syncs into Room |
| DataStore | User preferences (sort, theme, active engine) | Type-safe `Preferences` |
| Media3 ExoPlayer | Playback state | singleton in `MediaPlayerHolder` |
| Remote clients | SMB/FTP/SFTP/WebDAV browse + streaming | pooled in `ConnectionPool` |
| Native FFmpeg | Video thumbnails | JNI in `core/thumbnail` + `cpp/` |

---

## Threading Model

| Layer | Coroutine Context |
|---|---|
| UI (Composable) | Main (`collectAsStateWithLifecycle`) |
| ViewModel | Main (`viewModelScope`) |
| PlayerRepository | Main (engine delegation) + `Dispatchers.Default` for traffic polling |
| Repository (IO) | `Dispatchers.IO` |
| Room DAO | Auto-dispatched |
| MediaStore scan | `Dispatchers.IO` |
| Network clients | `ConnectionPool` threads + `Dispatchers.IO` |
| ExoPlayer | Own internal threads |
| Native thumbnail | JNI off the main thread (Coil fetcher scope) |
| DataStore | Auto-dispatched (IO) |
