# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.

---

## Layer Overview

```
┌─────────────────────────────────────────────────┐
│  Presentation (Compose UI)                      │
│  screens / components / viewmodels / preview    │
└──────────────────────┬──────────────────────────┘
                       │ StateFlow<UiState>
                       │ Events (callbacks)
┌──────────────────────▼──────────────────────────┐
│  Domain (pure Kotlin)                            │
│  models / repository interfaces / use cases      │
└──────────────────────┬──────────────────────────┘
                       │ suspend fun / Flow
┌──────────────────────▼──────────────────────────┐
│  Data                                            │
│  repository impls / datasources / DAOs / player  │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│  DI (Hilt modules)                               │
│  AppModule / RepositoryModule / PlayerModule     │
└─────────────────────────────────────────────────┘
```

---

## Package Structure (Full)

```
com.rhnxdev.hzplayer/
├── HzPlayerApplication.kt          (@HiltAndroidApp)
├── MainActivity.kt                 (single activity host)
│
├── presentation/
│   ├── navigation/
│   │   ├── AppNavigation.kt        (NavHost with route definitions)
│   │   └── AppDestinations.kt      (sealed class of routes)
│   │
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Type.kt
│   │   └── Theme.kt
│   │
│   ├── video/
│   │   ├── VideoLibraryScreen.kt
│   │   ├── VideoLibraryViewModel.kt
│   │   ├── VideoLibraryUiState.kt
│   │   └── components/
│   │       └── VideoCategorySection.kt
│   │
│   ├── audio/
│   │   ├── AudioBrowserScreen.kt
│   │   ├── AudioBrowserViewModel.kt
│   │   ├── AudioBrowserUiState.kt
│   │   └── components/
│   │       └── AlbumCard.kt
│   │
│   ├── browse/
│   │   ├── FileBrowserScreen.kt
│   │   ├── FileBrowserViewModel.kt
│   │   ├── FileBrowserUiState.kt
│   │   └── components/
│   │       └── FileListItem.kt
│   │
│   ├── player/
│   │   ├── VideoPlayerScreen.kt
│   │   ├── PlayerViewModel.kt
│   │   ├── PlayerUiState.kt
│   │   └── components/
│   │       ├── PlayerControlsOverlay.kt
│   │       ├── PlayerSeekBar.kt
│   │       ├── MiniPlayerBar.kt
│   │       └── AudioPlayerSheet.kt
│   │
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── components/
│   │       ├── SettingsSection.kt
│   │       └── SettingsItem.kt
│   │
│   ├── search/
│   │   ├── SearchScreen.kt
│   │   ├── SearchViewModel.kt
│   │   └── SearchUiState.kt
│   │
│   └── preview/
│       └── PreviewMedia.kt
│
├── domain/
│   ├── model/
│   │   ├── MediaItem.kt           (generic media model)
│   │   ├── VideoItem.kt
│   │   ├── AudioItem.kt
│   │   ├── Album.kt
│   │   ├── Artist.kt
│   │   ├── Playlist.kt
│   │   ├── FolderItem.kt
│   │   └── PlayerState.kt
│   │
│   ├── repository/
│   │   ├── MediaRepository.kt
│   │   ├── AudioRepository.kt
│   │   ├── FileRepository.kt
│   │   ├── PlayerRepository.kt
│   │   └── UserPreferencesRepository.kt
│   │
│   └── usecase/
│       ├── GetVideosUseCase.kt
│       ├── GetAudioUseCase.kt
│       ├── SearchMediaUseCase.kt
│       └── ToggleFavoriteUseCase.kt
│
├── data/
│   ├── repository/
│   │   ├── MediaRepositoryImpl.kt
│   │   ├── AudioRepositoryImpl.kt
│   │   ├── FileRepositoryImpl.kt
│   │   ├── PlayerRepositoryImpl.kt
│   │   └── UserPreferencesRepositoryImpl.kt
│   │
│   ├── datasource/
│   │   ├── local/
│   │   │   ├── room/
│   │   │   │   ├── HzPlayerDatabase.kt
│   │   │   │   ├── MediaDao.kt
│   │   │   │   ├── PlaylistDao.kt
│   │   │   │   ├── FavoriteDao.kt
│   │   │   │   └── entities/
│   │   │   │       ├── MediaEntity.kt
│   │   │   │       ├── PlaylistEntity.kt
│   │   │   │       └── PlaylistMediaCrossRef.kt
│   │   │   └── datastore/
│   │   │       └── UserPreferencesSerializer.kt
│   │   │
│   │   ├── media/
│   │   │   └── MediaScanner.kt
│   │   │
│   │   └── player/
│   │       └── MediaPlayerHolder.kt
│   │
│   ├── mapper/
│   │   ├── MediaMappers.kt
│   │   └── AudioMappers.kt
│   │
│   └── model/
│       └── PlaybackStateAdapter.kt
│
├── core/
│   ├── designsystem/
│   │   ├── HzPlayerIcons.kt
│   │   └── Dimens.kt
│   │
│   ├── components/
│   │   ├── MediaCard.kt
│   │   ├── MediaGrid.kt
│   │   ├── MediaListItem.kt
│   │   ├── SortFilterChips.kt
│   │   ├── ViewToggleFab.kt
│   │   ├── MediaEmptyState.kt
│   │   ├── MediaLoadingState.kt
│   │   ├── MediaErrorState.kt
│   │   ├── DurationBadge.kt
│   │   └── ThumbnailPlaceholder.kt
│   │
│   ├── util/
│   │   ├── DateUtils.kt
│   │   ├── FileSizeUtils.kt
│   │   └── MediaTimeUtils.kt
│   │
│   └── extensions/
│       ├── ContextExtensions.kt
│       ├── UriExtensions.kt
│       └── FlowExtensions.kt
│
└── di/
    ├── AppModule.kt
    ├── RepositoryModule.kt
    ├── PlayerModule.kt
    └── DatabaseModule.kt
```

---

## Data Flow

### Read (display media)

```
UI (Composable) 
  ← observes StateFlow<UiState>
    ← VideoLibraryViewModel
      ← MediaRepository.getAllVideos(): Flow<List<VideoItem>>
        ← MediaDao.getAll(): Flow<List<MediaEntity>>
```

### Write (play, favorite, delete)

```
UI (user taps play)
  → ViewModel.onPlayVideo(item)
    → useCase(PlayVideoUseCase) 
      → PlayerRepository.play(mediaItem)
        → MediaPlayerHolder.play(uri)
```

### Settings

```
UI (user changes sort)
  → ViewModel.onSortChanged(SortType)
    → UserPreferencesRepository.setSortPreference(key, value)
      → DataStore.edit { ... }
```

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

---

## Dependency Injection

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer = ...
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HzPlayerDatabase = ...
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides @Singleton
    fun provideMediaRepository(impl: MediaRepositoryImpl): MediaRepository = impl
}
```

---

## Navigation

```kotlin
sealed class AppDestinations(val route: String, val label: String) {
    data object VideoLibrary : AppDestinations("video", "Video")
    data object AudioBrowser : AppDestinations("audio", "Audio")
    data object FileBrowser : AppDestinations("browse", "Browse")
    data object VideoPlayer : AppDestinations("player/{videoId}", "Player")
    data object Settings : AppDestinations("settings", "Settings")
}

// Bottom navigation destinations (for NavigationSuiteScaffold)
val bottomNavItems = listOf(AppDestinations.VideoLibrary, AppDestinations.AudioBrowser, AppDestinations.FileBrowser, AppDestinations.Settings)
```
