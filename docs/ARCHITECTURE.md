# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.
> Last refreshed: 2026-07-21 (libass pipeline, archive support, OTA updates, audio queue).

---

## Layer Overview

```
┌─────────────────────────────────────────────────┐
│  Presentation (Compose UI)                      │
│  screens / components / viewmodels / ui-state    │
│  / navigation / player (PlayerSurface seam)      │
└──────────────────────┬──────────────────────────┘
                       │ StateFlow<UiState>
                       │ Events (callbacks)
┌──────────────────────▼──────────────────────────┐
│  Domain (pure Kotlin)                            │
│  models / repository interfaces / use cases /    │
│  player contract (IPlayerEngine)                 │
└──────────────────────┬──────────────────────────┘
                       │ suspend fun / Flow
┌──────────────────────▼──────────────────────────┐
│  Data                                            │
│  repository impls / datasources / DAOs / engines │
│  / network clients / connection pool             │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│  DI (Hilt modules)                               │
│  AppModule / RepositoryModule / DatabaseModule / │
│  PlayerEngineModule                              │
└─────────────────────────────────────────────────┘
```

**Playback seam:** The presentation layer never imports Media3 types. It talks to
`IPlayerEngine` (defined in `domain/player/`) and renders through the `PlayerSurface`
composable. The render seam methods (`createRenderView`, `updateRenderView`,
`onRenderViewPaused/Resumed`) live directly on `IPlayerEngine` — no typed casts needed.
Only `ExoPlayerEngine` (in `data/`) knows about `ExoPlayer`/`PlayerView`.
See `docs/ENGINE_MODULARITY.md`.

---

## Package Structure (current)

```
com.rhnxdev.hzplayer/
├── HzPlayerApplication.kt          (@HiltAndroidApp)
├── MainActivity.kt                 (single activity host → AppNavigation)
│
├── presentation/
│   ├── navigation/
│   │   ├── AppDestinations.kt      (sealed class of 5 bottom-nav tabs)
│   │   └── AppNavigation.kt        (NavRoutes + NavHost route builder)
│   │
│   ├── theme/                      (Color.kt / Type.kt / Theme.kt — M3 dynamic)
│   │
│   ├── main/
│   │   └── MainViewModel.kt        (shared "now playing" / active-tab state)
│   │
│   ├── video/
│   │   └── VideoLibraryScreen.kt / VideoLibraryViewModel.kt / VideoLibraryUiState.kt
│   │
│   ├── audio/
│   │   ├── AudioBrowserScreen.kt / AudioBrowserViewModel.kt / AudioBrowserUiState.kt
│   │   ├── AlbumDetailScreen.kt / ArtistDetailScreen.kt / AudioDetailViewModel.kt
│   │   └── components/AlbumCard.kt / AudioDetailHeader.kt
│   │
│   ├── browse/
│   │   ├── FileBrowserScreen.kt / FileBrowserViewModel.kt / FileBrowserUiState.kt
│   │   └── components/ArchivePasswordDialog.kt
│   │
│   ├── network/
│   │   ├── NetworkScreen.kt / NetworkViewModel.kt / NetworkUiState.kt
│   │   └── components/NetworkScreenContent.kt / ServerCard.kt / ServerConfigDialog.kt
│   │              / StreamHistoryListItem.kt
│   │
│   ├── player/
│   │   ├── VideoPlayerScreen.kt / AudioPlayerScreen.kt
│   │   ├── PlayerViewModel.kt / PlayerUiState.kt
│   │   ├── PlayerSurface.kt          (engine-agnostic render seam)
│   │   ├── PlayerPositionController.kt (250ms position tick + resume persistence)
│   │   ├── PlayerTrackCache.kt       (cached subtitle/audio track lists)
│   │   ├── PlayerPlaylistController.kt / PlayerDebugController.kt
│   │   ├── SubtitleBrowserViewModel.kt / SubtitleSearchViewModel.kt ( + UiState files)
│   │   └── components/   (see UI_COMPONENTS.md — overlay, seek, sheets, dialogs, …)
│   │
│   ├── search/
│   │   ├── SearchScreen.kt / SearchViewModel.kt / SearchUiState.kt
│   │
│   ├── settings/
│   │   ├── SettingsScreen.kt / SettingsViewModel.kt / LicensesScreen.kt
│   │   └── components/SettingsDialogs.kt / SettingsItem.kt / SettingsSection.kt
│   │              / AboutDialog.kt / UpdateDialog.kt / EnumSelectionDialog.kt
│   │              / ColorPickerDialog.kt / SubdlApiKeyDialog.kt
│   │
│   └── preview/PreviewMedia.kt      (DEBUG-only sample data)
│
├── domain/
│   ├── model/
│   │   ├── MediaItem.kt (MediaType/SortType/SortDirection/ViewMode/RepeatMode)
│   │   ├── VideoItem.kt AudioItem.kt FolderItem.kt FolderCounts.kt
│   │   ├── PlayerState.kt (PlayerState/PlaybackErrorKind/PlayerStateInfo)
│   │   ├── PlaybackProgress.kt DecoderMode.kt OrientationMode.kt ResumeMode.kt
│   │   ├── NetworkProtocol.kt ServerConfig.kt RemoteFileItem.kt RemoteAuthException.kt
│   │   ├── NetworkTraffic.kt StreamHistoryItem.kt DebugStats.kt
│   │   ├── AspectRatioMode.kt ThemeMode.kt
│   │
│   ├── player/
│   │   ├── EngineType.kt IPlayerEngine.kt
│   │   ├── RenderViewConfig.kt PlaybackErrorMapper.kt
│   │
│   ├── repository/   (one interface per domain area — see below)
│   │
│   └── usecase/   (none — ViewModels call repositories directly)
│
├── data/
│   ├── repository/
│   │   ├── MediaRepositoryImpl.kt AudioRepositoryImpl.kt FileRepositoryImpl.kt
│   │   ├── NetworkRepositoryImpl.kt RemoteBrowseRepositoryImpl.kt
│   │   ├── PlayerRepositoryImpl.kt ResumeRepositoryImpl.kt
│   │   ├── SubtitleRepositoryImpl.kt UserPreferencesRepositoryImpl.kt
│   │   ├── ArchiveRepositoryImpl.kt
│   │
│   ├── datasource/
│   │   ├── local/room/  (HzPlayerDatabase + dao/ + entities/)
│   │   ├── media/MediaScanner.kt   (MediaStore index → Room cache)
│   │   ├── archive/
│   │   │   ├── ArchiveNative.kt     (JNI bridge → libarchive)
│   │   │   └── ArchiveDataSource.kt (Media3 DataSource for archive:// URIs)
│   │   ├── network/
│   │   │   ├── RemoteBrowserClient.kt   (interface)
│   │   │   ├── SmbBrowserClient.kt FtpBrowserClient.kt
│   │   │   ├── SftpBrowserClient.kt WebDavBrowserClient.kt
│   │   ├── player/
│   │   │   ├── MediaPlayerHolder.kt     (owns the single ExoPlayer)
│   │   │   ├── ExoPlayerEngine.kt       (IPlayerEngine impl)
│   │   │   ├── NeighborSubtitleDiscoverer.kt (auto-loads neighbor .srt/.ass)
│   │   │   ├── ConnectionPool.kt        (SMB/FTP/SSH pooling)
│   │   │   ├── RemoteDataSourceBase.kt  (shared base for protocol DataSources)
│   │   │   ├── FtpDataSource.kt SftpDataSource.kt SmbDataSource.kt WebDavDataSource.kt
│   │   │   ├── SmbPathResolver.kt SftpTofuVerifier.kt
│   │   │   └── MediaPlaybackService.kt  (Media3 MediaSessionService)
│   │   ├── subtitle/assrender/          (native libass subtitle pipeline)
│   │   │   ├── AssHandler.kt            (singleton coordinator: data→libass→bitmap)
│   │   │   ├── AssDirectBridge.kt       (JNI bridge to libass)
│   │   │   ├── AssTrackOutput.kt / AssExtractorOutput.kt / AssExtractorsFactory.kt
│   │   │   ├── AssRenderersFactory.kt / AssSubtitleParserFactory.kt
│   │   │   ├── AssMatroskaExtractor.kt / AssFormat.kt / AssTimeRenderer.kt
│   │   │   ├── SubtitleConverters.kt    (SRT/VTT→ASS conversion)
│   │   │   └── SubtitleOverlayView.kt   (custom View for bitmap subtitles)
│   │   └── remote/SubdlApi.kt
│   │
│   ├── mapper/MediaMappers.kt NetworkMappers.kt
│   └── security/PasswordCrypto.kt     (encrypted server credentials in Room)
│
├── core/
│   ├── designsystem/  (HzPlayerIcons.kt Dimens.kt NavBarInsets.kt)
│   ├── components/    (see UI_COMPONENTS.md)
│   ├── thumbnail/     (native FFmpeg extractor + Coil fetcher + MediaInfoProbe)
│   └── util/          (MediaTimeUtils / MediaExtensions / MimeTypeUtil /
│                       BreadcrumbBuilder / DirectoryLruCache / PlaybackFormatters /
│                       ServerDiscoverer / SubtitleLanguageResolver / UpdateChecker /
│                       ArchivePaths)
│
└── di/
    ├── AppModule.kt RepositoryModule.kt DatabaseModule.kt
    ├── PlayerEngineModule.kt EngineKey.kt
```

### Native thumbnail pipeline (`core/thumbnail` + `cpp/`)
- `VideoThumbnailFetcher.kt` — Coil `Fetcher` that drives extraction and caches to disk.
- `NativeThumbnailExtractor.kt` — JNI bridge; guards `System.loadLibrary` so it degrades
  to a placeholder on devices without the native lib (e.g. x86 emulator).
- `MediaInfoProbe.kt` — FFmpeg-based container/codec metadata probe for the Properties dialog.
- `RandomAccessBridge.kt` / `LocalRandomAccessBridge.kt` / `ChannelRandomAccessBridge.kt`
  / `ThumbnailSource.kt` — expose a `seek`/`readAt` interface over any URI (local, SMB, …)
  so FFmpeg reads remotely.
- `cpp/ThumbnailExtractor.cpp` — FFmpeg-based frame decode → RGBA for any source URI.

### Native libass subtitle pipeline (`data/datasource/subtitle/assrender/` + `cpp/`)
- `AssHandler.kt` — singleton coordinator: receives raw ASS data from ExoPlayer's extractor
  pipeline, feeds it to libass via JNI, renders subtitle bitmaps synced to playback time.
- `AssDirectBridge.kt` — JNI bridge to `cpp/ass_direct.c` (libass init/render/set-step).
- `AssTrackOutput.kt` / `AssExtractorOutput.kt` / `AssExtractorsFactory.kt` — intercept
  embedded subtitle tracks in the ExoPlayer extractor chain.
- `AssRenderersFactory.kt` / `AssSubtitleParserFactory.kt` — route ASS/SSA tracks to the
  libass pipeline instead of Media3's built-in text renderer.
- `SubtitleConverters.kt` — converts SRT/VTT to ASS on-the-fly for unified libass rendering.
- `SubtitleOverlayView.kt` — custom Android View that displays the rendered bitmap overlay.
- `cpp/ass_direct.c` + `ass_direct_jni.c` — native libass rendering (fontconfig-free).

### Native archive pipeline (`data/datasource/archive/` + `cpp/`)
- `ArchiveNative.kt` — JNI bridge to `cpp/ArchiveExtractor.cpp` (libarchive).
- `ArchiveDataSource.kt` — Media3 `DataSource` for `archive://` URIs (open/read/seek/close).
- `cpp/ArchiveExtractor.cpp` — libarchive list/open/read/seek/close via JNI.

---

## Domain Repository Interfaces

| Interface | Impl | Responsibility |
|---|---|---|
| `MediaRepository` | `MediaRepositoryImpl` | Video library from MediaStore→Room cache |
| `AudioRepository` | `AudioRepositoryImpl` | Albums/artists/tracks + detail queries |
| `FileRepository` | `FileRepositoryImpl` | Local filesystem browse (SAF / MediaStore) |
| `NetworkRepository` | `NetworkRepositoryImpl` | Server config CRUD, stream history |
| `RemoteBrowseRepository` | `RemoteBrowseRepositoryImpl` | SMB/FTP/SFTP/WebDAV directory listing |
| `PlayerRepository` | `PlayerRepositoryImpl` | Delegates to active `IPlayerEngine` |
| `ResumeRepository` | `ResumeRepositoryImpl` | Persisted playback position resume |
| `SubtitleRepository` | `SubtitleRepositoryImpl` | SubDL search + local subs |
| `ArchiveRepository` | `ArchiveRepositoryImpl` | Archive listing + entry URIs via libarchive |
| `UserPreferencesRepository` | `UserPreferencesRepositoryImpl` | DataStore prefs + active engine |

---

## State Management Pattern

```kotlin
// UiState — immutable data class
@Immutable
data class VideoLibraryUiState(
    val categories: List<VideoCategory> = emptyList(),
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val isEmpty: Boolean = false,
    val selectedFolder: String? = null,
)

// ViewModel — exposes StateFlow
@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VideoLibraryUiState())
    val uiState: StateFlow<VideoLibraryUiState> = _uiState.asStateFlow()
}

// Screen — stateless, receives state + callbacks
@Composable
fun VideoLibraryScreen(
    uiState: VideoLibraryUiState,
    onVideoClick: (VideoItem) -> Unit,
    onSortChanged: (SortType) -> Unit,
    onViewModeChanged: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
)
```

Engine selection in the player stack is **not** in the screen: `PlayerRepositoryImpl`
holds `Map<EngineType, IPlayerEngine>` and exposes `activeEngine` + `availableEngines`.
`PlayerUiState.activeEngineType` lets `PlayerSurface` `key()` on the engine to swap
render views.

---

## Dependency Injection

```kotlin
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun provideExoPlayer(...) = MediaPlayerHolder(...)   // single instance
}

@Module @InstallIn(SingletonComponent::class)
abstract class PlayerEngineModule {
    @Binds @IntoMap @EngineKey(EngineType.EXO_PLAYER) @Singleton
    abstract fun bindExoPlayerEngine(impl: ExoPlayerEngine): IPlayerEngine
}
```

---

## Navigation

```kotlin
sealed class AppDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object VideoLibrary : AppDestination("video_library", R.string.nav_video, …)
    data object AudioBrowser : AppDestination("audio_browser", R.string.nav_audio, …)
    data object FileBrowser  : AppDestination("file_browser",  R.string.nav_browse, …)
    data object Network      : AppDestination("network",       R.string.nav_network, …)
    data object Settings     : AppDestination("settings",      R.string.nav_settings, …)
}

// Detail / overlay routes live in AppNavigation.NavRoutes:
//   video_player/{videoId}  audio_player  search
//   album_detail/{title}    artist_detail/{name}
```

`NavigationSuiteScaffold` hosts the 5 tabs; detail screens push onto the same NavHost.
