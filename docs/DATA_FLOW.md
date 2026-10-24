# Hz Player — Data Flow Architecture

> How data moves from persistence to pixels.
> Verified against code at `efeba7b` — drift check: `git diff efeba7b..HEAD -- app/src`.

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

HistoryScreen        ← HistoryViewModel.uiState
  ← PlayHistoryRepository.observeHistory()   // Room play_history, ORDER BY playedAt DESC

FileBrowserScreen   ← FileRepository.listDirectory(path)      // java.io.File + StorageManager
  ← FileRepository.getStorageRoots()                          // StorageManager volumes + /storage fallback

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
  → TabManager (BrowserTab stack; TabSidebar switcher)
  → AdBlockEngine (Rust ad filtering via JNI + filter-list updates)
  → MediaSnifferEngine (detects video/audio on pages)
  → BrowserScreen: WebView per tab (pooled via BrowserSessionStore)
    · BrowserHistoryRepository (persisted history)
    · UrlSuggestionsPanel (Chrome-like omnibox suggestions)
    · MediaGrabberBottomSheet (stream detected media to the player)
    · PopupPermissionBottomSheet (cross-domain popup approval)
    · JsDialog (alert / confirm / prompt / beforeunload)
    · ElementPickerPanel (ad-block cosmetic rule authoring)
    · Real desktop mode (desktop UA + wide viewport)
    · Web PiP bridge: site PiP button → native Picture-in-Picture window
    · WebView-native certificate handling (invalid certs cancelled; renderer
      crashes reload a fresh blank renderer)
```

### The cache-first listing flow

`MediaRepositoryImpl.getAllVideos` and `AudioRepositoryImpl.getAllSongs` both run
through `cachedScanFlow` (`data/repository/CachedScanFlow.kt`):

```
in-memory cache → Room → debug preview → MediaStore scan
```

Each stage emits as soon as it has data, so the list never blanks between the cache
hit and the fresh scan. A scan failure is swallowed only if something was already
emitted — otherwise it is rethrown, so an empty list is never mistaken for "no
media". The memory cache is read lazily because the flow is cold and is
re-collected on every tab re-entry.

---

## Write Flows

### Play (local or remote — identical call path)
```
Screen → ViewModel.onPlay(item)
  → PlayerRepository.playVideo / playAudio / playUri(uri, title, isVideo, mimeType,
                                                    resumePositionMs, headers)
    → dispatchPlay { … }                   // waits for the persisted engine preference
      → activeEngine.play(uri, …, headers) // IPlayerEngine — no Media3 in the caller
        → ExoPlayerEngine → MediaPlayerHolder.player.setMediaItem + prepare + play
    → NeighborSubtitleDiscoverer auto-loads sibling .srt/.ass files
    → startTrafficPolling() when the URI is remote
  → playbackStateInfo Flow updates PlayerUiState
  → PlayHistoryRepository.recordPlay(uri, title, isVideo, artist, mimeType, thumbnail)
```

### Record recently-played history
```
PlayerViewModel.recordHistory(uri, title, isVideo, artist, mimeType)
  → PlayHistoryRepository.recordPlay(…)
    → PlayHistoryDao.upsert(PlayHistoryEntity(uri, title, isVideo, playedAt, …))
      // @Insert(REPLACE) on the uri primary key — replaying updates the row
      // and bumps playedAt instead of adding a duplicate
  ← PlayHistoryRepository.observeHistory()
    → PlayHistoryDao.getAll()  // SELECT * FROM play_history ORDER BY playedAt DESC
      → HistoryViewModel → HistoryUiState.items → HistoryScreen
```

The write happens in the common play funnel (`startWithResumeDecision`), so
`playVideo`, `playUri` and `playAudio` all record; the audio and video playlist
entry points record their selected item directly, and `playNetworkUri` routes
through `playUri`, so network streams are covered too. There is **no size cap** on
the table — the only bound is the dedup by URI.

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
  → periodic save (every ~5 s, only while playing and not seeking):
    ResumeRepository.savePosition(mediaId, positionMs, durationMs)
    → PlaybackPositionDao upsert
  → final save on pause / stop / onCleared
  → the tick is skipped entirely while the app is backgrounded
```

### Change sort
```
Screen → ViewModel.onSortChanged(SortType) → UserPreferencesRepository.setSortOrder(...)
  → DataStore.edit { prefs[sortKey] = sortType.name }
```

### Copy / move a file
```
FileBrowserScreen → PasteActionBar (paste) → FileBrowserViewModel.onPasteClipboard()
  → FileRepository.copyEntry / moveEntry(source, destDir, onProgress)
    → CopyProgress tracks bytes against a known total and reports a 0f..1f fraction,
      throttled to whole-percent steps; finish() guarantees a final 1f
    → moveEntry fast path: same-volume renameTo → a single onProgress(1f)
    → cross-volume: copy-then-delete with incremental progress
  → FileBrowserUiState.pasteProgress → HzPlayerProgressBar in PasteActionBar
  → MediaScannerConnection.scanFile(...) keeps MediaStore in sync
```

### Delete a file (deferred, with undo)
```
FileBrowserScreen → delete → FileBrowserViewModel.onConfirmDelete(path)
  → the item is hidden optimistically and the disk delete is deferred by DELETE_GRACE_MS (4 s)
  → onUndoDelete() cancels the pending delete
  → otherwise commitPendingDeleteNow() → FileRepository.deleteEntry(path)
```

### Delete a video (video library, deferred, with undo)
```
VideoLibraryScreen → delete → VideoLibraryViewModel.onConfirmDelete()
  → the video is removed from allVideos optimistically (removeFromLibrary); the disk delete is deferred by DELETE_GRACE_MS (4 s)
  → a MediaStore reload during the grace window keeps the video hidden (emitLoaded)
  → onUndoDelete() cancels the timer and puts the video back (restoreToLibrary)
  → otherwise commitPendingDelete() → FileRepository.deleteEntry(uri)
  → ViewToggleFab is hidden while any snackbar (undo or result) is showing
```

### Discover network servers
```
NetworkScreen → scan → ServerDiscoverer.startScan()
  → NsdManager.discoverServices("_smb._tcp" / "_ftp._tcp" / "_webdav._tcp")
      // needs NEARBY_WIFI_DEVICES on API 33+; without it the scan is skipped
  → fallback: /24 subnet sweep probing port 445 (SMB) and 21 (FTP)
  → per host: reverse DNS / NetBIOS name → NetworkDomainUtils.verifiedHostName(name, ip)
      // the name must resolve back to the same IP
    → else NetworkDomainUtils.resolveDomain(...) → else the numeric IP
  → discoveredServers: StateFlow<List<ServerConfig>>
  → NetworkViewModel.onSaveDiscoveredServer(server)
    → resolveServerHostDomain(server)   // prefer a domain over the IP
      → NetworkRepository.saveServer(…)  // credentials encrypted via PasswordCrypto
```

### Thumbnail cache pruning (on app start)
```
HzPlayerApplication.onCreate → appScope (IO)
  → pruneOldThumbnails(cacheDir/video_thumbs, THUMB_MAX_AGE_MS = 180 days)
    → deletes files older than the max age; per-file IO errors are swallowed
    → a missing directory is a no-op; Coil's own image_cache is NOT touched
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
  skipForward/skipBackward are interface defaults on IPlayerEngine — they offset the
  current position and let seekTo clamp.
  Native seek landing is generation-gated — `docs/PLAYER_ARCHITECTURE.md` → "Seek transactions".
```

### Track selection / errors
- Tracks: `engine.getSubtitleTracks()/getAudioTracks()` surfaced to the bottom sheets.
- Error: `MediaPlayerHolder.onPlayerError` → `PlaybackErrorMapper.map(error)` returns a
  redacted `(PlaybackErrorKind, message)`; `errorKind` drives the overlay icon and which
  errors get a Retry button (network/timeout/auth/file — not format).
- Native engine, web sources: `FfmpegNativeEngine.openAndStart` sanitizes headers, resolves the
  CA bundle, and calls `nativeOpen`; failures come back as a native error class
  (`nativeGetLastErrorClass`) mapped by `NativeErrorMapper` to the same `errorKind` values.
  See `PLAYER_ARCHITECTURE.md` → "Web playback on the native engine".

---

## Browser Data Flow

### Detecting media on a page
```
Page load → TabManager.onPageStarted / onPageFinished / onProgressChanged(30 %, 60 %)
  → MediaSnifferBridge.injectSnifferJs(webView)      // JS injected into the document
    → the script scans performance resources, media elements, hooked fetch / XHR
    → every call carries a per-document token; the bridge drops calls without it
  → shouldInterceptRequest → MediaSnifferEngine.isMediaUrl(url, headers)
    → processCapturedMedia(tabId, url, …)
      → MediaSnifferEngine.createMediaItem(…)     // type, mime, title, headers, tokens
      → MediaSnifferEngine.parseHlsQualities(item)  // for .m3u8 — variant ladder
      → MediaSnifferEngine.consolidateMediaTree(items)  // master + child variants
  → BrowserViewModel.activeTabMediaItems → MediaGrabberBottomSheet
```

### Handing a detected stream to the player
```
MediaGrabberBottomSheet → "Play in Hz Player"
  → item.headers.withLiveCookies(targetUrl, refererUrl)   // cookies for the TARGET url
  → Intent(ACTION_VIEW + setDataAndType) → VideoPlayerActivity / AudioPlayerActivity
    → extras: EXTRA_MEDIA_TITLE, EXTRA_PAGE_URL, EXTRA_FROM_BROWSER, EXTRA_HEADERS_JSON
    → putHttpHeaders(merged) writes the headers in three shapes:
      an alternating key/value String[] extra, the
      "android.media.intent.extra.HTTP_HEADERS" Bundle, and a JSON copy
  → the player activity calls networkRepository.addStreamToHistory(…)
```

### Ad-block engine rebuild
```
BrowserViewModel.updateSettings(…)
  → if adBlockEnabled / enabledFilterLists / customAdBlockRules / cosmeticFilteringEnabled changed
    → AdBlockEngine.reload()      // build-then-swap — a failed build keeps the old engine
      → AdBlockNative.nativeCreateEngine(rules) → nativeShouldBlock / nativeGetCosmeticCss
  → custom-rule keystrokes are debounced 500 ms (DebouncedAction)
```

### Authoring a cosmetic rule (element picker)
```
BrowserBottomBar → "Block element" → TabManager.startElementPicker()
  → ElementPickerBridge.injectPickerJs(webView)   // ~380 lines of injected JS
    → the user taps an element → the JS generates a tag-first selector
      (stable id → stable attribute → stable classes → :nth-of-type fallback)
    → ElementPickerBridge.onElementPicked(selector, matchCount, canWiden)
  → ElementPickerPanel shows the selector + "Wider" + "Apply to all sites"
  → BrowserViewModel.blockPickedElement()
    → TabManager.applyPickedHideAndExit()      // hides the element immediately
    → CosmeticRuleBuilder.build(host, selector, applyToAllSites)  // "host##selector"
    → BrowserViewModel.updateSettings(customAdBlockRules = appended)
      → the engine rebuilds and the cosmetic CSS now hides the element
```

### Browser dialogs, popups and file uploads
```
WebChromeClient.onJsAlert / onJsConfirm / onJsPrompt / onJsBeforeUnload
  → TabManager.showJsDialog(request)   // single slot — WebView blocks the page's JS
                                        // thread until the result is delivered
  → JsDialog composable → resolveJsDialog(confirmed, input)

WebChromeClient.onShowFileChooser
  → TabManager stores a FileChooserRequest
  → BrowserActivity's StartActivityForResult launcher
    → deliverFileChooserResult(uris) → the page's file input is filled

WebChromeClient.onCreateWindow (target=_blank)
  → a temporary WebView is created and handed to the transport
  → same-domain → a new tab is opened directly
  → cross-domain → promptCrossDomainPopup() → PopupPermissionBottomSheet
    → allowPendingPopup() reuses the temp WebView in a new tab
    → denyPendingPopup() destroys it
```

---

## Data Source Strategy

| Source | Use | Notes |
|---|---|---|
| Room | Persistent cache: media index, server configs, resume positions, stream history, browser history, recently played | 6 DAOs; KSP-generated; v7 (`play_history` added in `MIGRATION_6_7`) |
| MediaStore | System media index | `MediaScanner` syncs into Room; also the search backend for `FileRepository.searchFiles` |
| DataStore | User preferences (sort, theme, active engine, archive passwords) | Type-safe `Preferences`; name `user_preferences` |
| `java.io.File` | Local directory listing and file operations | `FileRepositoryImpl` walks the filesystem directly; SAF is not used |
| `StorageManager` | Storage-root enumeration | `StorageManager.getStorageVolumes()` + a `/storage` sibling fallback for OEM mounts |
| Media3 ExoPlayer | Playback state | singleton in `MediaPlayerHolder` (backs `EXO_PLAYER` + `FFMPEG`) |
| Native FFmpeg player | Standalone engine (`NATIVE_FFMPEG`) | `FfmpegNativeEngine` + `ffmpeg/FfmpegNativePlayer.kt` → `cpp/ffplayer/` (`libffplayer.so`) |
| Remote clients | SMB/FTP/SFTP/WebDAV browse + streaming | pooled in `ConnectionPool` (4 DataSource pools + 4 browser pools) |
| Native FFmpeg | Video thumbnails + codec metadata probe | `core/io/` sources + `core/thumbnail/` JNI + `cpp/` |
| Native libass | ASS/SSA/SRT/VTT subtitle rendering | JNI in `data/datasource/subtitle/assrender` + `cpp/` |
| Native libarchive | Archive listing + streaming playback | JNI in `data/datasource/archive` + `cpp/` |
| Native ad blocker | WebView ad filtering | Rust engine in `browser/adblock/` (JNI) |
| Cloudflare R2 | OTA update checks + APK download | `UpdateChecker` reads `BuildConfig.R2_UPDATE_BASE_URL` |

### Per-protocol seek strategy

| Protocol | Seek mechanism |
|---|---|
| SMB | Resolve the target once per URI into a cached `SmbFile`, then `skipFully(stream, position)`; a `SmbException` retires the pooled `CIFSContext` and re-borrows a fresh one |
| FTP | Server-side restart offset — `ftp.setRestartOffset(position)` before `retrieveFileStream`; the pooled control connection is reused so a seek does not re-login |
| SFTP | Positional reads through the SSHJ `RemoteFile` API; the SSH session is pooled so a seek does not re-run the key exchange |
| WebDAV | HTTP byte range (`Range: bytes=<pos>-`); if the server ignores Range and answers 200, the stream is skipped forward instead |

All four extend `RemoteDataSourceBase`, which owns the shared `read()`/`skipFully()`
helpers, the `safeUri()` credential stripping, and the connection-stage retry
backoff (`REMOTE_OPEN_BACKOFF_MS` = 250 / 750 / 2000 ms).

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
| Native FFmpeg player | Own demux / video / audio threads; JNI callbacks marshalled onto a single-thread daemon executor |
| DataStore | Auto-dispatched (IO) |
| App startup | `HzPlayerApplication.appScope` (`Dispatchers.IO`) — thumbnail prune |
