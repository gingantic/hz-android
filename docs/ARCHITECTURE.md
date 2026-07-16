# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.
> Last refreshed: 2026-07-11 (post engine-modularity refactor + i18n sweep).

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
composable. Only `ExoPlayerEngine` (in `data/`) knows about `ExoPlayer`/`PlayerView`.
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
│   │   ├── VideoLibraryScreen.kt / VideoLibraryViewModel.kt / VideoLibraryUiState.kt
│   │   └── components/VideoCategorySection.kt
│   │
│   ├── audio/
│   │   ├── AudioBrowserScreen.kt / AudioBrowserViewModel.kt / AudioBrowserUiState.kt
│   │   ├── AlbumDetailScreen.kt / ArtistDetailScreen.kt / AudioDetailViewModel.kt
│   │   └── components/AlbumCard.kt / AudioDetailHeader.kt
│   │
│   ├── browse/
│   │   ├── FileBrowserScreen.kt / FileBrowserViewModel.kt / FileBrowserUiState.kt
│   │
│   ├── network/
│   │   ├── NetworkScreen.kt / NetworkViewModel.kt / NetworkUiState.kt
│   │   └── components/ServerCard.kt / ServerConfigDialog.kt / StreamHistoryListItem.kt
│   │
│   ├── player/
│   │   ├── VideoPlayerScreen.kt / AudioPlayerScreen.kt
│   │   ├── PlayerViewModel.kt / PlayerUiState.kt
│   │   ├── PlayerSurface.kt          (engine-agnostic render seam)
│   │   ├── SubtitleBrowserViewModel.kt / SubtitleSearchViewModel.kt ( + UiState files)
│   │   └── components/   (see UI_COMPONENTS.md — overlay, seek, sheets, dialogs, …)
│   │
│   ├── search/
│   │   ├── SearchScreen.kt / SearchViewModel.kt / SearchUiState.kt
│   │
│   ├── settings/
│   │   ├── SettingsScreen.kt / SettingsViewModel.kt
│   │   └── components/SettingsDialogs.kt / SettingsItem.kt / SettingsSection.kt
│   │
│   └── preview/PreviewMedia.kt      (DEBUG-only sample data)
│
├── domain/
│   ├── model/
│   │   ├── MediaItem.kt VideoItem.kt AudioItem.kt Album.kt Artist.kt Playlist.kt
│   │   ├── FolderItem.kt FolderCounts.kt MediaType.kt
│   │   ├── PlayerState.kt PlayerStateInfo.kt RepeatMode.kt PlaybackProgress.kt
│   │   ├── NetworkProtocol.kt ServerConfig.kt RemoteFileItem.kt RemoteAuthException.kt
│   │   ├── NetworkTraffic.kt StreamHistoryItem.kt DebugStats.kt
│   │   ├── SubtitleStyle.kt AspectRatioMode.kt ThemeMode.kt
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
│   │
│   ├── datasource/
│   │   ├── local/room/  (HzPlayerDatabase + dao/ + entities/)
│   │   ├── local/ (MediaScanner)        (MediaStore index → Room cache)
│   │   ├── media/MediaScanner.kt
│   │   ├── network/
│   │   │   ├── RemoteBrowserClient.kt   (interface)
│   │   │   ├── SmbBrowserClient.kt FtpBrowserClient.kt
│   │   │   ├── SftpBrowserClient.kt WebDavBrowserClient.kt
│   │   ├── player/
│   │   │   ├── MediaPlayerHolder.kt     (owns the single ExoPlayer)
│   │   │   ├── ExoPlayerEngine.kt       (IPlayerEngine impl)
│   │   │   ├── ConnectionPool.kt        (SMB/FTP/SSH pooling)
│   │   │   ├── FtpDataSource.kt SftpDataSource.kt SmbDataSource.kt WebDavDataSource.kt
│   │   │   ├── SmbPathResolver.kt
│   │   │   └── MediaPlaybackService.kt  (Media3 MediaSessionService)
│   │   └── remote/SubdlApi.kt
│   │
│   ├── mapper/MediaMappers.kt NetworkMappers.kt
│   └── security/PasswordCrypto.kt     (encrypted server credentials in Room)
│
├── core/
│   ├── designsystem/  (HzPlayerIcons.kt Dimens.kt NavBarInsets.kt)
│   ├── components/    (see UI_COMPONENTS.md)
│   ├── thumbnail/     (native FFmpeg extractor + Coil fetcher; see below)
│   └── util/          (MediaTimeUtils / MediaExtensions / MimeTypeUtil /
│                       BreadcrumbBuilder / DirectoryLruCache / PlaybackFormatters /
│                       ServerDiscoverer)
│
└── di/
    ├── AppModule.kt RepositoryModule.kt DatabaseModule.kt
    ├── PlayerEngineModule.kt EngineKey.kt
```

### Native thumbnail pipeline (`core/thumbnail` + `cpp/`)
- `VideoThumbnailFetcher.kt` — Coil `Fetcher` that drives extraction and caches to disk.
- `NativeThumbnailExtractor.kt` — JNI bridge; guards `System.loadLibrary` so it degrades
  to a placeholder on devices without the native lib (e.g. x86 emulator).
- `RandomAccessBridge.kt` / `LocalRandomAccessBridge.kt` / `ThumbnailSource.kt` — expose a
  `seek`/`readAt` interface over any URI (local, SMB, …) so FFmpeg reads remotely.
- `cpp/ThumbnailExtractor.cpp` — FFmpeg-based frame decode → RGBA for any source URI.

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
| `UserPreferencesRepository` | `UserPreferencesRepositoryImpl` | DataStore prefs + active engine |

---

## State Management Pattern

```kotlin
// UiState — immutable data class
data class VideoLibraryUiState(
    val videos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val searchQuery: String? = null,
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
