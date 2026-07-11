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

## Project Overview

**Hz Player** is a full-featured Android video/audio media player inspired by VLC. It supports local storage, SMB, FTP, SFTP, and WebDAV sources with custom DataSources for each protocol.

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository + StateFlow |
| DI | Hilt |
| Media | Media3 ExoPlayer (`IPlayerEngine` abstraction) |
| Persistence | Room (KSP) + Preferences DataStore |
| Images / Thumbnails | Coil 3 + custom `VideoThumbnailFetcher` (NDK-backed) |
| Navigation | Navigation Compose + M3 Adaptive Navigation Suite |
| Network protocols | SMB (jcifs-ng), FTP (Apache Commons Net), SFTP (JSch/SSHJ), WebDAV (OkHttp) |
| Subtitle search | OpenSubtitles REST API v1 |
| Server discovery | NSD/mDNS + manual port-scan (`ServerDiscoverer`) |
| Min SDK | 28 |
| Target / Compile SDK | 36 |

---

## Package Structure (accurate as of now)

```
com.rhnxdev.hzplayer
├── core/
│   ├── components/          — 17 shared Composables (MediaCard, HzPlayerTopBar, FileItemCard, …)
│   ├── designsystem/        — Dimens.kt, HzPlayerIcons.kt, NavBarInsets.kt
│   ├── thumbnail/           — VideoThumbnailFetcher, NativeThumbnailExtractor, RandomAccessBridge
│   └── util/                — BreadcrumbBuilder, DirectoryLruCache, MediaExtensions,
│                              MediaTimeUtils, MimeTypeUtil, PlaybackFormatters, ServerDiscoverer
│
├── data/
│   ├── datasource/
│   │   ├── local/room/      — HzPlayerDatabase, dao/ (Media, PlaybackPosition, ServerConfig,
│   │   │                      StreamHistory), entities/ (4 matching entities)
│   │   ├── media/           — MediaScanner.kt
│   │   ├── network/         — FtpBrowserClient, SftpBrowserClient, SmbBrowserClient,
│   │   │                      WebDavBrowserClient, RemoteBrowserClient (interface)
│   │   ├── player/          — ExoPlayerEngine, MediaPlayerHolder, ConnectionPool,
│   │   │                      FtpDataSource, SftpDataSource, SmbDataSource, WebDavDataSource,
│   │   │                      SmbPathResolver, MediaPlaybackService,
│   │   │                      ExoPlayerMediaSessionProvider
│   │   └── remote/          — OpenSubtitlesApi
│   ├── mapper/              — Domain ↔ data mapping
│   └── repository/          — 9 implementations (see Repositories section)
│
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   ├── EngineKey.kt         — Hilt multibinding key for IPlayerEngine map
│   ├── PlayerEngineModule.kt — Binds ExoPlayerEngine into Map<EngineType, IPlayerEngine>
│   └── RepositoryModule.kt  — Binds all 9 repository implementations
│
├── domain/
│   ├── model/               — 18 pure Kotlin models (VideoItem, AudioItem, MediaItem,
│   │                          FolderItem, ServerConfig, SubtitleStyle, PlayerState,
│   │                          AspectRatioMode, DebugStats, NetworkTraffic, Playlist,
│   │                          RemoteFileItem, StreamHistoryItem, ThemeMode, …)
│   ├── player/              — IPlayerEngine (interface), EngineType (enum: EXO_PLAYER),
│   │                          MediaSessionProvider, RenderViewConfig, PlaybackErrorMapper
│   ├── repository/          — 9 interfaces (see Repositories section)
│   └── usecase/             — ResumeProgressUseCase
│
├── presentation/
│   ├── audio/               — AudioPlayerScreen + ViewModel
│   ├── browse/              — FileBrowserScreen, FileBrowserUiState, FileBrowserViewModel
│   ├── main/                — App shell
│   ├── navigation/          — AppDestinations (5 top-level routes), AppNavigation
│   ├── network/             — NetworkScreen, NetworkUiState, NetworkViewModel
│   ├── player/
│   │   ├── VideoPlayerScreen.kt   — Full video UI, gesture layer, surface selection
│   │   ├── AudioPlayerScreen.kt
│   │   ├── PlayerViewModel.kt
│   │   ├── PlayerUiState.kt
│   │   ├── PlayerSurface.kt
│   │   ├── SubtitleBrowserUiState/ViewModel.kt
│   │   ├── SubtitleSearchUiState/ViewModel.kt
│   │   └── components/      — 20 player Composables (PlayerControlsOverlay, PlayerSeekBar,
│   │                          SubtitleOverlay, PlaylistDrawer, AudioPlayerSheet, MiniPlayerBar,
│   │                          SpeedSelectionDialog, SubtitleSelectionDialog,
│   │                          SubtitleStylingDialog, SubtitleFileBrowserBottomSheet,
│   │                          SubtitleSearchDialog, AudioSelectionDialog,
│   │                          DragSeekIndicator, SeekIndicator, SlideIndicator,
│   │                          PlaybackErrorOverlay, DebugOverlay, UnlockPill, …)
│   ├── preview/             — PreviewMedia.kt (all @Preview data helpers)
│   ├── search/              — SearchScreen + ViewModel
│   ├── settings/            — SettingsScreen, SettingsViewModel
│   ├── theme/               — M3 theme (colors, typography, shapes)
│   └── video/               — VideoLibraryScreen, VideoLibraryUiState, VideoLibraryViewModel
│
├── HzPlayerApplication.kt
└── MainActivity.kt
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
| `SubtitleRepository` | `SubtitleRepositoryImpl` | External subtitles, OpenSubtitles search |
| `ResumeRepository` | `ResumeRepositoryImpl` | Save / restore playback positions |
| `UserPreferencesRepository` | `UserPreferencesRepositoryImpl` | DataStore preferences |

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
ExoPlayerEngine  ←  bound via PlayerEngineModule (@Singleton)
    ↓ wraps
MediaPlayerHolder  →  Media3 ExoPlayer
    ↓
MediaPlaybackService (MediaSessionService — background playback + lock-screen controls)
```

### `IPlayerEngine` contract

`domain/player/IPlayerEngine` is the **only** playback boundary — no Media3 type crosses it. Adding a new backend (mpv, libVLC) = implement `IPlayerEngine` + add one `@Binds @IntoMap @EngineKey(...)` line to `PlayerEngineModule`. Current engines: `EXO_PLAYER` (only active engine).

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
- **Subtitle styling:** `SubtitleStyle` model → `SubtitleStylingDialog` UI.
- **Online search:** `OpenSubtitlesApi` (REST v1, plain `HttpURLConnection`) → `SubtitleSearchViewModel`.
- **SMB auto-discovery:** `findSmbNeighborSubtitles()` uses jcifs-ng `SmbFile` to list sibling files.
- **Built-in rendering is active** (`PlayerView` built-in). Custom `SubtitleOverlay` is parked pending refinement.

---

## DI Modules

| Module | Installs in | Purpose |
|---|---|---|
| `AppModule` | `SingletonComponent` | App-level singletons (DataStore, OkHttp, …) |
| `DatabaseModule` | `SingletonComponent` | Room DB + all DAOs |
| `PlayerEngineModule` | `SingletonComponent` | `Map<EngineType, IPlayerEngine>` multibinding |
| `RepositoryModule` | `SingletonComponent` | All 9 repository bindings |

---

## Core Components & Utils

### `core/components/` (17 shared Composables)
`BreadcrumbBar`, `DirectoryBrowsePane`, `DurationBadge`, `FileItemCard`, `HzPlayerSearchBar`, `HzPlayerSearchableScaffold`, `HzPlayerTopBar`, `MediaCard`, `MediaEmptyState`, `MediaErrorState`, `MediaGrid`, `MediaListItem`, `MediaLoadingState`, `SearchDelegate`, `SortFilterChips`, `ThumbnailPlaceholder`, `ViewToggleFab`

### `core/designsystem/`
`Dimens` — spacing/size tokens · `HzPlayerIcons` — icon references · `NavBarInsets` — inset helpers

### `core/thumbnail/`
`VideoThumbnailFetcher` — Coil `Fetcher` for video frames · `NativeThumbnailExtractor` — NDK frame extraction · `RandomAccessBridge` / `LocalRandomAccessBridge` — unified seek interface · `ThumbnailSource`

### `core/util/`
`BreadcrumbBuilder` · `DirectoryLruCache` · `MediaExtensions` · `MediaTimeUtils` · `MimeTypeUtil` · `PlaybackFormatters` · `ServerDiscoverer`

### `presentation/player/components/` (20 Composables)
`PlayerControlsOverlay` · `PlayerSeekBar` · `SubtitleOverlay` · `PlaylistDrawer` · `AudioPlayerSheet` · `MiniPlayerBar` · `SpeedSelectionDialog` · `SubtitleSelectionDialog` · `SubtitleStylingDialog` · `SubtitleFileBrowserBottomSheet` · `SubtitleSearchDialog` · `AudioSelectionDialog` · `DragSeekIndicator` · `SeekIndicator` · `SeekIndicators` · `SlideIndicator` · `PlaybackErrorOverlay` · `DebugOverlay` · `UnlockPill` · `PlayerRenderView`

---

## VLC Reference

VLC source is in `vlc-android-master/`. **UX reference only — do not copy code.** Adapt concepts to Compose + Media3 + MVVM.

| Feature | VLC Reference File |
|---|---|
| Video library | `vlc-android/src/.../video/VideoGridFragment.kt` |
| Audio browser | `vlc-android/src/.../audio/AudioBrowserFragment.kt` |
| Audio player | `vlc-android/src/.../audio/AudioPlayer.kt` |
| Video player | `vlc-android/src/.../video/VideoPlayerActivity.kt` |
| Player controls | `vlc-android/src/.../video/VideoPlayerOverlayDelegate.kt` |
| Touch gestures | `vlc-android/src/.../video/VideoTouchDelegate.kt` |
| Playback service | `vlc-android/src/.../PlaybackService.kt` |
| File browser | `vlc-android/src/.../browser/FileBrowserFragment.kt` |
| Media providers | `vlc-android/src/.../providers/medialibrary/` |

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
3. **Check `TODO.md`** — see if the feature is tracked or partially done.
4. **Check VLC reference** — for UX pattern guidance.
5. **Search official docs if uncertain:**
   - [Android Developers](https://developer.android.com)
   - [Media3 / ExoPlayer](https://developer.android.com/media/media3)
   - [Jetpack Compose](https://developer.android.com/jetpack/compose/documentation)
   - [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
   - [Room](https://developer.android.com/training/data-storage/room)
6. **Do not guess** — if unclear, search the internet before writing a single line.

### Before modifying existing code

1. Read the **entire file** being changed — no patching in isolation.
2. Understand **why** the code is written that way (check `docs/` or git history).
3. Check **callers** — use "Find Usages" to understand the blast radius.
4. Trace the full **data flow**: event → ViewModel → state → UI.
5. When in doubt, **ask** — a wrong fix is worse than no fix.

---

## Known In-Progress Work (see TODO.md for full tracking)

| Feature | Status |
|---|---|
| HDR→SDR colour correction (`GlEffect` / custom GLSL) | 🔧 In progress — pref wired, pipeline no-op |
| Custom `SubtitleOverlay` (replace built-in PlayerView subtitles) | ⏸ Parked — built-in active for reliability |
| ASS/SSA animated subtitles (libass or VLC subtitle renderer) | 📋 Planned — significant effort |
| Remove unused `vlc-android-master/` directory | 🧹 Low priority cleanup |

---

## Docs Index

| File | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Full app architecture, module boundaries |
| [`docs/PLAYER_ARCHITECTURE.md`](docs/PLAYER_ARCHITECTURE.md) | Player stack deep-dive |
| [`docs/DATA_FLOW.md`](docs/DATA_FLOW.md) | Data flow diagrams |
| [`docs/ENGINE_MODULARITY.md`](docs/ENGINE_MODULARITY.md) | `IPlayerEngine` abstraction, adding new backends |
| [`docs/UI_COMPONENTS.md`](docs/UI_COMPONENTS.md) | Composable component catalogue |
| [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md) | Feature roadmap |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | High-level roadmap |
| [`docs/CLEANUP_PLAN.md`](docs/CLEANUP_PLAN.md) | Tech debt and cleanup tasks |
| [`TODO.md`](TODO.md) | Current task tracking |
