# CLAUDE.md

This file provides guidance to Claude (claude.ai/code) when working with code in this repository.

---

## ⚠️ MANDATORY: Verify Before You Change

> **Before making ANY change to existing code:**
>
> 1. **Read the relevant file(s) in full** — never assume what already exists.
> 2. **Understand the existing pattern** — follow it; do not invent a new one.
> 3. **If unsure about an API, behaviour, or best practice** — search the internet (Android docs, Media3/ExoPlayer docs, Jetpack Compose docs) before writing code. Wrong code is worse than slow delivery.
> 4. **Check the `docs/` directory** before designing any feature — architecture decisions live there.
> 5. **Never silently delete or reorganise code** — ask first.

---

## Build & Run Commands

```sh
# Build debug APK
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows ← use this

# Build release APK
./gradlew assembleRelease

# Unit tests
./gradlew test
./gradlew test --tests "com.rhnxdev.hzplayer.*"

# Instrumented (on-device) tests
./gradlew connectedCheck

# Static analysis
./gradlew lint

# Clean
./gradlew clean
```

> **Gradle wrapper:** version **8.13** — see `gradle/wrapper/gradle-wrapper.properties`.

---

## Native (C/C++) Cross-Compilation

Native libs (e.g. `libarchive.so`) are built **outside Gradle** by standalone scripts (`build_libarchive.sh`, `ffmpeg_build_android.sh`, `libass_build_android.sh`). When editing or rebuilding them on this Windows host, obey these hard-won rules:

- **Toolchain:** NDK r27 only ships the `windows-x86_64` prebuilt. Drive the Windows clang wrappers (`aarch64-linux-android<N>-clang` etc.) **from WSL** (`wsl bash -c '...'`). The wrappers set `--target` but NOT `--sysroot`; do NOT pass an explicit `--sysroot` (it breaks header resolution — the wrapper's baked-in default works).
- **`/mnt/c` is NOT readable by Windows NDK tools.** `clang.exe`, `llvm-nm.exe`, `llvm-strings.exe`, `llvm-objdump.exe` cannot open `/mnt/c/...` paths. When invoking them from WSL, pass the *argument* as a native `C:/...` path (forward slashes OK); the tool's own *location* can stay `/mnt/c/...`. Any `nm`/`strings` result of "0 symbols / no such file" is this artifact, **not** a real empty lib — re-run with a `C:/` argument.
- **`ar`/`ranlib`** are `llvm-ar.exe` / `llvm-ranlib.exe` (no extensionless wrapper exists).
- **Hand-linking static → shared:** a `Generic` CMake system (never `Linux`, which leaks host `/usr/include` → `bits/wordsize.h` not found) refuses `BUILD_SHARED_LIBS`. Build static `.a`, then fuse into a `.so` with the clang wrapper: `-shared -Wl,--whole-archive … -Wl,--no-whole-archive`. **Every static dep that supplies symbols must sit INSIDE the `--whole-archive` group** (e.g. mbedTLS) or the linker silently drops it.
- **Output path:** link to a temp file first, then `cp -f` into `app/src/main/jniLibs/<abi>/`. Writing directly into `jniLibs` fails with `Permission denied` on the existing locked file.
- **`android_lf.h`:** libarchive's `android_lf.h` is a *static* header at `contrib/android/include` (not installed). Add `-I<src>/contrib/android/include` via `CMAKE_C_FLAGS` in the toolchain file (FORCE-set), not the per-project cmake call.
- **mbedTLS version:** libarchive master uses the legacy `mbedtls_md_hmac_*` API removed in the mbedTLS dev branch — pin **`mbedtls-3.6.7`** (LTS). Build all three (`libmbedtls.a`, `libmbedx509.a`, `libmbedcrypto.a`).
- **Verify a built `.so`:** `nm` on a *stripped* APK-packed `.so` is empty by design — confirm code presence with `llvm-strings.exe` on the `C:/...` path and grep for distinctive strings (e.g. `Mbed TLS 3.6.7`, `mbedtls_aes_crypt_ecb`).

---

## Project Overview

**Hz Player** is a full-featured Android video/audio media player inspired by VLC. It supports local storage, SMB, FTP, SFTP, and WebDAV sources with custom DataSources for each protocol. Includes a full in-app browser with ad blocking and media sniffing.

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository + StateFlow |
| DI | Hilt |
| Media | Media3 ExoPlayer + standalone native FFmpeg player (`IPlayerEngine` abstraction, 3 engines) |
| Persistence | Room (KSP) + Preferences DataStore |
| Images / Thumbnails | Coil 3 + custom `VideoThumbnailFetcher` (NDK-backed) |
| Navigation | Navigation Compose + M3 Adaptive Navigation Suite |
| Network protocols | SMB (jcifs-ng), FTP (Apache Commons Net), SFTP (SSHJ), WebDAV (OkHttp) |
| Subtitle search | SubDL Search & Download API |
| Subtitle rendering | Native libass (ASS/SSA/SRT/VTT) via JNI |
| Server discovery | NSD/mDNS + manual port-scan (`ServerDiscoverer`) |
| Archive support | libarchive (zip/7z/rar/tar/iso/cab) via JNI |
| Security | AES-256-GCM encrypted credentials (AndroidKeyStore) |
| In-app browser | WebView + native ad blocking + media sniffing |
| Min SDK | 28 |
| Target / Compile SDK | 36 |

---

## Package Structure (accurate as of now)

```
com.rhnxdev.hzplayer
├── core/
│   ├── components/          — 18 shared Composables (MediaCard, HzPlayerTopBar, FileItemCard,
│   │                          FileOptionsBottomSheet, ViewSortBottomSheet, …)
│   ├── designsystem/        — Dimens.kt, HzPlayerIcons.kt, NavBarInsets.kt
│   ├── thumbnail/           — VideoThumbnailFetcher, NativeThumbnailExtractor, RandomAccessBridge,
│   │                          LocalRandomAccessBridge, ChannelRandomAccessBridge,
│   │                          ArchiveRandomAccessBridge, ThumbnailSource, MediaInfoProbe
│   └── util/                — BreadcrumbBuilder, DirectoryLruCache, MediaExtensions,
│                              MediaTimeUtils, MimeTypeUtil, PlaybackFormatters, ServerDiscoverer,
│                              SubtitleLanguageResolver, UpdateChecker, ArchivePaths, IntentUtils,
│                              NetworkDomainUtils
│
├── data/
│   ├── datasource/
│   │   ├── local/room/      — HzPlayerDatabase, dao/ (Media, PlaybackPosition, ServerConfig,
│   │   │                      StreamHistory, BrowserHistory), entities/ (5 matching entities)
│   │   ├── media/           — MediaScanner.kt
│   │   ├── network/         — FtpBrowserClient, SftpBrowserClient, SmbBrowserClient,
│   │   │                      WebDavBrowserClient, RemoteBrowserClient (interface)
│   │   ├── player/          — ExoPlayerEngine (backs EXO_PLAYER + FFMPEG), FfmpegNativeEngine
│   │   │                      (NATIVE_FFMPEG), MediaPlayerHolder, ConnectionPool,
│   │   │                      HzRenderersFactory, TenBandEqualizerProcessor, EqualizerController,
│   │   │                      AudioDelaySink, ExoDebugStats, ExoMediaItemHelper,
│   │   │                      FtpDataSource, SftpDataSource, SmbDataSource, WebDavDataSource,
│   │   │                      RemoteDataSourceBase, SmbPathResolver, SftpTofuVerifier,
│   │   │                      NeighborSubtitleDiscoverer, MediaPlaybackService,
│   │   │                      ffmpeg/ (FfmpegNativePlayer JNI bridge → libffplayer.so,
│   │   │                      FfmpegAudioSink, FfmpegLibrary/AudioRenderer/VideoRenderer/
│   │   │                      AudioDecoder/VideoDecoder extension decoders, FfmpegMimeTypes),
│   │   │                      mp4fork/ (HzMp4Extractor + BoxParser + SefReader — forked
│   │   │                      Media3 MP4 extractor: Samsung SEF motion photos, aux tracks)
│   │   ├── archive/         — ArchiveNative (JNI), ArchiveDataSource, ArchiveRepositoryImpl
│   │   ├── subtitle/assrender/ — AssHandler, AssDirectBridge, AssTrackOutput,
│   │   │                      AssExtractorOutput, AssExtractorsFactory,
│   │   │                      AssSubtitleParserFactory, AssMatroskaExtractor, AssFormat,
│   │   │                      AssTimeRenderer, SubtitleConverters, SubtitleOverlayView
│   │   └── remote/          — SubdlApi
│   ├── mapper/              — Domain ↔ data mapping
│   ├── security/            — PasswordCrypto (AES-256-GCM via AndroidKeyStore)
│   └── repository/          — 11 implementations (see Repositories section)
│
├── browser/                 — Full in-app browser (WebView + ad block + media sniffing)
│   ├── adblock/             — AdBlockListManager, AdBlockNative (JNI), AdBlockUpdater
│   ├── media/               — DetectedMediaItem, MediaDownloader, MediaSnifferBridge,
│   │                          MediaSnifferEngine, MediaStreamDecoder
│   ├── ui/                  — BrowserScreen, BrowserTopBar, BrowserBottomBar, TabStrip,
│   │                          TabSidebar, NewTabPage, BrowserHistoryScreen,
│   │                          BrowserSettingsScreen, MediaGrabberBottomSheet,
│   │                          PopupPermissionBottomSheet, UrlSuggestionsPanel
│   ├── AdBlockEngine.kt, BrowserActivity.kt, BrowserSessionStore.kt,
│   │   BrowserSettings.kt, BrowserSettingsStore.kt, BrowserTab.kt,
│   │   BrowserViewModel.kt, PendingPopupRequest.kt, TabManager.kt
│
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   ├── EngineKey.kt         — Hilt multibinding key for IPlayerEngine map
│   ├── PlayerEngineModule.kt — Binds ExoPlayerEngine (EXO_PLAYER + FFMPEG) and
│   │                           FfmpegNativeEngine (NATIVE_FFMPEG) into Map<EngineType, IPlayerEngine>
│   └── RepositoryModule.kt  — Binds all 11 repository implementations
│
├── domain/
│   ├── model/               — 24 pure Kotlin models (VideoItem, AudioItem, MediaItem,
│   │                          FolderItem, FolderCounts, ServerConfig, PlayerState,
│   │                          PlaybackProgress, DecoderMode, OrientationMode, ResumeMode,
│   │                          NetworkProtocol, AspectRatioMode, ThemeMode, DebugStats,
│   │                          NetworkTraffic, RemoteFileItem, RemoteAuthException,
│   │                          StreamHistoryItem, BrowserHistoryItem, ChapterInfo,
│   │                          EqualizerInfo, UrlSuggestion, FileMediaTypeFilter)
│   ├── player/              — IPlayerEngine (interface), EngineType (enum: EXO_PLAYER, FFMPEG,
│   │                          NATIVE_FFMPEG), RenderViewConfig, PlaybackErrorMapper
│   ├── repository/          — 11 interfaces (see Repositories section)
│   └── usecase/             — (none; ViewModels call repositories directly)
│
├── presentation/
│   ├── audio/               — AudioBrowserScreen, AlbumDetailScreen, ArtistDetailScreen,
│   │                          AudioDetailViewModel, components/ (AlbumCard, AudioDetailHeader)
│   ├── browse/              — FileBrowserScreen, FileBrowserUiState, FileBrowserViewModel,
│   │                          components/ (ArchivePasswordDialog, SolidArchiveWarningDialog,
│   │                          DirectoryStackContent, StorageRootsContent,
│   │                          FileBrowserTopBarActions, PasteActionBar)
│   ├── main/                — App shell (HzPlayerApp, MainViewModel, components/)
│   ├── navigation/          — AppDestinations (5 top-level routes), AppNavigation, MainNavHost
│   ├── network/             — NetworkScreen, NetworkUiState, NetworkViewModel,
│   │                          components/ (NetworkScreenContent, ServerCard,
│   │                          ServerConfigDialog, StreamHistoryListItem)
│   ├── player/
│   │   ├── VideoPlayerScreen.kt   — Full video UI, gesture layer, surface selection
│   │   ├── AudioPlayerScreen.kt
│   │   ├── PlayerViewModel.kt, PlayerUiState.kt
│   │   ├── PlayerSurface.kt
│   │   ├── PlayerPositionController.kt  — 250ms position tick + resume persistence
│   │   ├── PlayerTrackCache.kt          — cached subtitle/audio track lists
│   │   ├── PlayerPlaylistController.kt  — video playlist + audio queue
│   │   ├── PlayerDebugController.kt     — debug stats polling
│   │   ├── PlayerMoreOptionsSheet.kt    — sleep timer, chapters, A-B repeat, play-as-audio
│   │   ├── SubtitleBrowserUiState/ViewModel.kt
│   │   ├── SubtitleSearchUiState/ViewModel.kt
│   │   └── components/      — 29 player Composables (PlayerControlsOverlay, PlayerSeekBar,
│   │                          PlayerGestures, GestureCueIndicators, AssSubtitleOverlay,
│   │                          PlaylistDrawer, AudioPlayerSheet, AudioQueueSheet,
│   │                          MiniPlayerBar, FloatingVideoPlayer, SpeedSelectionDialog,
│   │                          SubtitleSelectionDialog, SubtitleFileBrowserBottomSheet,
│   │                          SubtitleSearchDialog, SubtitleBrowserContent,
│   │                          AudioSelectionDialog, TrackSelectionRow, FlagIcon,
│   │                          SheetScaffold, DragSeekIndicator, SeekIndicator,
│   │                          SeekIndicators, SlideIndicator, PlaybackErrorOverlay,
│   │                          DebugOverlay, UnlockPill, PlayerRenderView,
│   │                          PlayerMoreOptionsSheet content, EqualizerSheet)
│   ├── preview/             — PreviewMedia.kt (all @Preview data helpers)
│   ├── search/              — SearchScreen + ViewModel
│   ├── settings/            — SettingsScreen, SettingsViewModel, LicensesScreen,
│   │                          components/ (SettingsDialogs, SettingsItem, SettingsSection,
│   │                          AboutDialog, UpdateDialog, EnumSelectionDialog,
│   │                          ColorPickerDialog, SubdlApiKeyDialog)
│   ├── theme/               — M3 theme (colors, typography, shapes)
│   └── video/               — VideoLibraryScreen, VideoLibraryUiState, VideoLibraryViewModel
│
├── HzPlayerApplication.kt
├── MainActivity.kt
├── VideoPlayerActivity.kt
└── AudioPlayerActivity.kt
```

---

## Architecture

### Core Rules

- **Screen = stateless Composable** — receives `UiState` + lambda callbacks only; never holds repositories.
- **ViewModel = `@HiltViewModel`** — exposes `StateFlow<XxxUiState>`, calls use cases / repositories, owns all side effects.
- **Repository = interface in `domain/`**, implementation in `data/`. Always inject the interface.
- **UiState = `@Immutable data class`** — one per screen, updated only via `copy()`.
- **Preview data** lives in `presentation/preview/PreviewMedia.kt` — never hardcode fake data in Composables.

### State & Collection

```kotlin
// ViewModel
@HiltViewModel
class XxxViewModel @Inject constructor(
    private val repo: XxxRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(XxxUiState())
    val uiState: StateFlow<XxxUiState> = _uiState.asStateFlow()
}

// Screen
@Composable
fun XxxScreen(vm: XxxViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    XxxContent(state = state, onAction = vm::handleAction)
}
```

- `collectAsStateWithLifecycle()` everywhere — lifecycle-aware.
- `remember` for local UI state only (animation, expanded menus, scroll position).
- Never `LiveData`. Never `SharedPreferences`.

### Repositories (all `@Singleton`)

| Interface | Implementation | Responsibility |
|---|---|---|
| `PlayerRepository` | `PlayerRepositoryImpl` | Active engine proxy, playback commands |
| `MediaRepository` | `MediaRepositoryImpl` | Video/audio library queries |
| `AudioRepository` | `AudioRepositoryImpl` | Audio file scanning |
| `FileRepository` | `FileRepositoryImpl` | Local file system browsing |
| `NetworkRepository` | `NetworkRepositoryImpl` | Server CRUD, stream history |
| `RemoteBrowseRepository` | `RemoteBrowseRepositoryImpl` | Browse SMB/FTP/SFTP/WebDAV directories |
| `SubtitleRepository` | `SubtitleRepositoryImpl` | External subtitles, SubDL search |
| `ResumeRepository` | `ResumeRepositoryImpl` | Save / restore playback positions |
| `UserPreferencesRepository` | `UserPreferencesRepositoryImpl` | DataStore preferences |
| `ArchiveRepository` | `ArchiveRepositoryImpl` | Archive listing + entry URIs via libarchive |
| `BrowserHistoryRepository` | `BrowserHistoryRepositoryImpl` | Browser history persistence |

---

## Player Architecture

### Layer diagram

```
VideoPlayerScreen (Compose)
    ↓ UiState / events
PlayerViewModel (@HiltViewModel)
    ↓
PlayerRepository (interface)
    ↓ implemented by
PlayerRepositoryImpl
    ↓ holds Map<EngineType, IPlayerEngine>
ExoPlayerEngine (EXO_PLAYER + FFMPEG)  /  FfmpegNativeEngine (NATIVE_FFMPEG)
    ↓ bound via PlayerEngineModule (@Singleton)
MediaPlayerHolder (ExoPlayer engines)  /  libffplayer.so JNI (native engine)
    ↓
MediaPlaybackService (MediaSessionService — background playback + lock-screen controls;
                      only for engines whose getMedia3Player() returns non-null)
```

### `IPlayerEngine` contract

`domain/player/IPlayerEngine` is the **only** playback boundary — no Media3 type crosses it. Adding a new backend = implement `IPlayerEngine` + add one `@Binds @IntoMap @EngineKey(...)` line to `PlayerEngineModule`. Current engines: `EXO_PLAYER`, `FFMPEG` (same ExoPlayer pipeline, FFmpeg renderers preferred), `NATIVE_FFMPEG` (standalone native player).

### `PlayerUiState` highlights

| Field | Note |
|---|---|
| `isPlaying`, `isLoading`, `duration`, `bufferedPercentage` | Core playback state |
| `playbackSpeed`, `shuffleMode`, `repeatMode` | Playback configuration |
| `subtitleTracks`, `audioTracks`, `selectedSubtitleTrack`, `selectedAudioTrack` | Track selection |
| `externalSubtitles`, `subtitleDelayMs`, `subtitleStyle` | External subtitle handling |
| `playerLocked` | Screen lock gesture |
| `errorMessage`, `errorKind` | Playback error overlay |
| `networkTraffic` | Realtime bandwidth display |
| `seekSensitivity`, `aspectRatioMode` | User preference overrides |
| `useSurfaceView` / `useTextureView` | Surface selection (HDR passthrough vs. composited) |
| `hdrEnabled`, `drmSessionActive` | HDR / DRM state |
| `videoPlaylist`, `currentPlaylistIndex`, `showPlaylistDrawer` | Playlist management |
| `debugMode`, `debugStats`, `debugOverlayVisible` | "Stats for nerds" overlay |
| `activeEngineType` | Drives surface type in `VideoPlayerScreen` |
| **position NOT here** | Ticks every 250 ms → separate `StateFlow` in ViewModel to avoid full recompose |

### Gesture rules (VideoPlayerScreen)

Gestures are **mutually exclusive** — commit to one per touch sequence:

| Gesture | Trigger | Notes |
|---|---|---|
| Hold-to-speed-up | Long-press without movement | Finger moves past threshold → cancelled |
| Seek (scrub) | Horizontal drag past threshold | — |
| Brightness | Vertical drag, left half | — |
| Volume | Vertical drag, right half | — |
| Double-tap seek | Two quick taps, minimal movement | — |

---

## Network DataSources

### Protocol support

| Protocol | Browser client | Playback DataSource | Seek strategy |
|---|---|---|---|
| SMB | `SmbBrowserClient` | `SmbDataSource` | `SmbRandomAccessFile.seek()` |
| FTP | `FtpBrowserClient` | `FtpDataSource` | `FTPClient.setRestartOffset()` |
| SFTP | `SftpBrowserClient` | `SftpDataSource` | Direct offset via `sftpHandle.read(pos, …)` |
| WebDAV | `WebDavBrowserClient` | `WebDavDataSource` | HTTP `Range: bytes=…` header |

### Connection pooling (`ConnectionPool` — `@Singleton`)

- Caches connections keyed by `host:port:user`.
- SMB: single `CIFSContext` per server — prevents "No more connections" crash.
- FTP: reuses control connection — eliminates ~200 ms login per seek.
- SFTP: reuses SSH session — eliminates ~500 ms key exchange per seek.
- WebDAV: OkHttp connection pool via `ConnectionPool`.

### Server discovery (`ServerDiscoverer`)

- Uses Android NSD (mDNS) for automatic server discovery on LAN.
- Falls back to manual port-scan when mDNS is unavailable.
- Exposes `StateFlow<List<ServerConfig>>` consumed by `NetworkViewModel`.

---

## Room Database

Database class: `HzPlayerDatabase`

| DAO | Entity | Purpose |
|---|---|---|
| `MediaDao` | `MediaEntity` | Cached video/audio metadata |
| `PlaybackPositionDao` | `PlaybackPositionEntity` | Resume position per URI |
| `ServerConfigDao` | `ServerConfigEntity` | Saved SMB/FTP/SFTP/WebDAV servers |
| `StreamHistoryDao` | `StreamHistoryEntity` | Recently opened stream URLs |
| `BrowserHistoryDao` | `BrowserHistoryEntity` | In-app browser history |

---

## Navigation

5 top-level destinations (bottom nav + rail):

| Destination | Route | Screen |
|---|---|---|
| `VideoLibrary` | `video_library` | `VideoLibraryScreen` |
| `AudioBrowser` | `audio_browser` | `AudioPlayerScreen` |
| `FileBrowser` | `file_browser` | `FileBrowserScreen` |
| `Network` | `network` | `NetworkScreen` |
| `Settings` | `settings` | `SettingsScreen` |

Player (`VideoPlayerScreen`) is launched as a full-screen destination on top of the nav graph.

---

## Subtitle System

- **Built-in tracks:** ExoPlayer track selection via `IPlayerEngine.selectSubtitleTrack()`.
- **External subtitles:** Added via `IPlayerEngine.addExternalSubtitle(uri)` — supports `.srt`, `.vtt`, `.ass`.
- **Subtitle delay:** `IPlayerEngine.setSubtitleDelay(delayMs)`.
- **Online search:** `SubdlApi` (Search & Download API, plain `HttpURLConnection`) → `SubtitleSearchViewModel`.
- **SMB auto-discovery:** `findSmbNeighborSubtitles()` uses jcifs-ng `SmbFile` to list sibling files.
- **Rendering:** Exo-backed engines use the built-in `PlayerView`; the native FFmpeg engine
  renders via the libass pipeline (`AssHandler` → `AssSubtitleOverlay`, zero-flicker).

---

## DI Modules

| Module | Installs in | Purpose |
|---|---|---|
| `AppModule` | `SingletonComponent` | App-level singletons (DataStore, OkHttp, …) |
| `DatabaseModule` | `SingletonComponent` | Room DB + all DAOs |
| `PlayerEngineModule` | `SingletonComponent` | `Map<EngineType, IPlayerEngine>` multibinding |
| `RepositoryModule` | `SingletonComponent` | All 11 repository bindings |

---

## Core Components & Utils

### `core/components/` (18 shared Composables)
`BreadcrumbBar`, `DirectoryBrowsePane`, `DurationBadge`, `FileItemCard`, `FileOptionsBottomSheet`, `HzPlayerSearchableScaffold`, `HzPlayerTopBar`, `MediaCard`, `MediaEmptyState`, `MediaErrorState`, `MediaListItem`, `MediaLoadingState`, `MediaPropertiesDialog`, `PermissionRequiredState`, `SearchDelegate`, `ThumbnailPlaceholder`, `ViewSortBottomSheet`, `ViewToggleFab`

### `core/designsystem/`
`Dimens` — spacing/size tokens · `HzPlayerIcons` — icon references · `NavBarInsets` — inset helpers

### `core/thumbnail/`
`VideoThumbnailFetcher` — Coil `Fetcher` for video frames · `NativeThumbnailExtractor` — NDK frame extraction · `RandomAccessBridge` / `LocalRandomAccessBridge` / `ChannelRandomAccessBridge` / `ArchiveRandomAccessBridge` — unified seek interface · `ThumbnailSource` · `MediaInfoProbe`

### `core/util/`
`BreadcrumbBuilder` · `DirectoryLruCache` · `MediaExtensions` · `MediaTimeUtils` · `MimeTypeUtil` · `PlaybackFormatters` · `ServerDiscoverer` · `SubtitleLanguageResolver` · `UpdateChecker` · `ArchivePaths` · `IntentUtils` · `NetworkDomainUtils`

### `presentation/player/components/` (29 Composables)
`PlayerControlsOverlay` · `PlayerSeekBar` · `AssSubtitleOverlay` · `PlaylistDrawer` · `AudioPlayerSheet` · `AudioQueueSheet` · `MiniPlayerBar` · `FloatingVideoPlayer` · `SpeedSelectionDialog` · `SubtitleSelectionDialog` · `SubtitleFileBrowserBottomSheet` · `SubtitleSearchDialog` · `SubtitleBrowserContent` · `AudioSelectionDialog` · `TrackSelectionRow` · `FlagIcon` · `SheetScaffold` · `DragSeekIndicator` · `SeekIndicator` · `SeekIndicators` · `SlideIndicator` · `PlaybackErrorOverlay` · `DebugOverlay` · `UnlockPill` · `PlayerRenderView` · `PlayerGestures` · `GestureCueIndicators` · `EqualizerSheet` · `PlayerMoreOptionsSheet` (sleep timer, chapters, A-B repeat content)

---

## VLC Reference

The `vlc-android-master/` source tree was **removed** from the repository (cleanup).
VLC concepts (gesture model, playback service, browser UX) are already adapted into
the Compose + Media3 + MVVM codebase — do not re-add the tree.

---

## Code Conventions

### Naming & size
- Files: `XxxScreen.kt`, `XxxViewModel.kt`, `XxxUiState.kt`, `XxxRepository.kt`, `XxxRepositoryImpl.kt`
- Keep files under **~300 lines**; extract reusable Composables to `components/` sub-package.
- Every public Composable: `modifier: Modifier = Modifier` parameter.
- Every reusable component: `@Preview` using `PreviewMedia` data (no ViewModel in preview).

### Kotlin
- `data class` + `copy()` for all models; `@Immutable` on UiState.
- Extension functions → `core/util/` or `core/extensions/`.
- `sealed interface` for UI events/actions.
- No `lateinit` in ViewModels — constructor injection only.

### Hard rules
- ❌ No `LiveData` anywhere.
- ❌ No `SharedPreferences` — use `DataStore`.
- ❌ No hardcoded fake data in Composables — use `PreviewMedia`.
- ❌ No `runBlocking` in production.
- ❌ No direct `Context` in ViewModels — `@ApplicationContext` only when unavoidable.
- ❌ No Media3 types crossing `IPlayerEngine` — keep the abstraction clean.

---

## Research Protocol

### Before implementing any feature

1. **Read existing code** — find what already exists and reuse it.
2. **Check `docs/`** — architecture decisions are documented there.
3. **Check `docs/PROJECT_PLAN.md`** — "What is missing / deferred" lists open work.
4. **Fetch current docs via MCP — never guess.** Use these before any web search:
   - **Context7** (`mcp__context7__*`) — official library/framework/SDK/API docs. `resolve-library-id` first, then `query-docs`. Covers Android, Media3/ExoPlayer, Compose, Hilt, Room, Kotlin, OkHttp, Coil, etc. Prefer over web search for library docs.
   - **Firecrawl** (`mcp__firecrawl__*`) — `firecrawl_scrape` for live pages, `firecrawl_search` for broad web. Use when Context7 has no match (e.g. xAI Grok API, third-party tools) or page is newer than Context7 snapshot.
   - **Fetch** (`mcp__fetch__imageFetch`) — fetch+markdown a single URL when Context7/Firecrawl unavailable or you need page text + inline images extracted. Also `firecrawl_search_feedback` after searches to improve quality and refund credits.
   - Fallback links (only if all MCPs fail):
     - [Android Developers](https://developer.android.com)
     - [Media3 / ExoPlayer](https://developer.android.com/media/media3)
     - [Jetpack Compose](https://developer.android.com/jetpack/compose/documentation)
     - [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
     - [Room](https://developer.android.com/training/data-storage/room)
5. **Do not guess** — if unclear, query the MCP docs before writing a single line. A wrong API call is worse than slow delivery.

### Complex change? Reason it through first
- Use **Sequential Thinking** (`mcp__sequential-thinking__sequentialthinking`) for multi-step problems, architectural decisions, or anything touching 3+ files. Build the analysis step-by-step, revise prior steps as understanding deepens, branch when exploring alternatives. Run this *before* editing — comprehension first, then the smallest diff.
- Apply the Ponytail ladder (CLAUDE.md header) *after* understanding the full flow: reuse existing code → stdlib → installed dep → one line → minimum code.

### Before modifying existing code

1. Read the **entire file** being changed — no patching in isolation.
2. Understand **why** the code is written that way (check `docs/` or git history).
3. Check **callers** — use "Find Usages" to understand the blast radius.
4. Trace the full **data flow**: event → ViewModel → state → UI.
5. When in doubt, **ask** — a wrong fix is worse than no fix.

---

## Known In-Progress Work

| Feature | Status |
|---|---|
| HDR→SDR colour correction (`GlEffect` / custom GLSL) | 🔧 In progress — pref wired, pipeline no-op |
| Custom `SubtitleOverlay` (replace built-in PlayerView subtitles) | ⏸ Parked — built-in active for reliability; native engine uses libass overlay |

---

## Docs Index

| File | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Full app architecture, module boundaries |
| [`docs/PLAYER_ARCHITECTURE.md`](docs/PLAYER_ARCHITECTURE.md) | Player stack deep-dive |
| [`docs/DATA_FLOW.md`](docs/DATA_FLOW.md) | Data flow diagrams |
| [`docs/ENGINE_MODULARITY.md`](docs/ENGINE_MODULARITY.md) | `IPlayerEngine` abstraction, adding new backends |
| [`docs/UI_COMPONENTS.md`](docs/UI_COMPONENTS.md) | Composable component catalogue |
| [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md) | Feature roadmap + "what is missing" list |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | High-level roadmap |
| [`docs/ARCHIVE_SUPPORT.md`](docs/ARCHIVE_SUPPORT.md) | Archive support design & implementation |
| [`docs/FFMPEG_NATIVE_AUDIT_AND_ROADMAP.md`](docs/FFMPEG_NATIVE_AUDIT_AND_ROADMAP.md) | Native FFmpeg player audit + VLC-parity roadmap |

---

## 🚦 Guidelines for AI Development

* **Minor Features & Bug Fixes**:
  * The AI assistant is permitted to implement and verify minor features, bug fixes, refactoring, pipeline adjustments, and styling alignments.
  * **Versioning Increment**: The AI must always ask for the developer's opinion and confirmation before bumping or increasing the versioning numbers. Any version changes (for example, bumping from `X.Y.Z` to `X.Y.Z+1` for patches, or `X.Y.0` to `X.Y+1.0` for minor features) should be proposed as examples for developer approval.
* **Major Features & Core Architecture**:
  * **Only the human developer can make major architecture modifications, structural design changes, or major features.** The AI is restricted from executing major updates autonomously.
