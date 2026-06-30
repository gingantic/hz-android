# Hz Player — Implementation Roadmap

> Phased delivery from foundation to polished media player.

---

## Phase 1: Foundation (estimated: 1 session)

**Goal**: Project compiles with all dependencies, Hilt application boots, basic navigation works.

### 1.1 Gradle Dependency Catalog

**Files**: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `build.gradle.kts`

Add versions and libraries for:
- Hilt (2.51.1) + hilt-navigation-compose
- Room (2.6.1) + KSP
- Media3 ExoPlayer (1.5.1)
- Coil 3 (3.0.4) + compose
- DataStore (1.1.1)
- Navigation Compose (2.8.5)
- Lifecycle runtime-compose
- KSP plugin

### 1.2 Hilt Setup

**Files**: `app/src/main/java/.../HzPlayerApplication.kt`, `app/build.gradle.kts`

- Create `@HiltAndroidApp` application class
- Register in AndroidManifest
- Add `@AndroidEntryPoint` to MainActivity

### 1.3 Room Setup

**Files**: `data/datasource/local/room/HzPlayerDatabase.kt`, `di/DatabaseModule.kt`

- Create HzPlayerDatabase with initial DAOs
- Create entity stubs (MediaEntity, PlaylistEntity)
- Add Room KSP dependency

### 1.4 DataStore Setup

**Files**: `data/datasource/local/datastore/UserPreferencesSerializer.kt`, `data/repository/UserPreferencesRepositoryImpl.kt`

- Create UserPreferencesRepository interface + impl
- Wire via DataStore preferences

### 1.5 Navigation Setup

**Files**: `presentation/navigation/AppNavigation.kt`, `presentation/navigation/AppDestinations.kt`

- Define route sealed class
- Create NavHost with placeholder screens
- Connect MainActivity to NavigationSuiteScaffold

---

## Phase 2: Architecture (estimated: 1 session)

**Goal**: All domain models, repository interfaces, and DI modules exist. Preview data ready.

### 2.1 Domain Models

**Files**: `domain/model/*.kt`

- MediaItem, VideoItem, AudioItem, Album, Artist, Playlist, FolderItem, PlayerState, SortType, ViewMode

### 2.2 Repository Interfaces

**Files**: `domain/repository/*.kt`

- MediaRepository, AudioRepository, FileRepository, PlayerRepository, UserPreferencesRepository

### 2.3 Use Cases (optional, add when needed)

**Files**: `domain/usecase/*.kt`

- GetVideosUseCase, GetAudioUseCase, SearchMediaUseCase, ToggleFavoriteUseCase

### 2.4 DI Modules

**Files**: `di/AppModule.kt`, `di/RepositoryModule.kt`, `di/PlayerModule.kt`, `di/DatabaseModule.kt`

### 2.5 Preview Data

**Files**: `presentation/preview/PreviewMedia.kt`

- Realistic placeholder movies, albums, artists, files for UI development

---

## Phase 3: Design System (estimated: 1 session)

**Goal**: Reusable component library that makes every screen look like a polished media player.

### 3.1 Color Palette Refresh

**Files**: `presentation/theme/Color.kt`, `presentation/theme/Theme.kt`

- Shift from purple/pink to a media-player-oriented palette (deep blue/navy + accent)
- Define surface containers, media-specific tones

### 3.2 Spacing & Shape System

**Files**: `core/designsystem/Dimens.kt`

- 8dp spacing system constants
- Shape constants using M3 shapes (small/medium/large)

### 3.3 Core Components

**Files**: `core/components/*.kt`

| Component | Purpose |
|---|---|
| `MediaCard.kt` | Grid card with thumbnail, gradient overlay, duration badge, progress, title, subtitle |
| `MediaGrid.kt` | LazyVerticalGrid wrapper around MediaCard |
| `MediaListItem.kt` | List item variant for video/audio lists |
| `SortFilterChips.kt` | Horizontal chip row for sort/filter selection |
| `ViewToggleFab.kt` | FAB toggling between grid/list view |
| `MediaEmptyState.kt` | Illustrated empty state with action button |
| `MediaLoadingState.kt` | Shimmer/pulse loading placeholder |
| `MediaErrorState.kt` | Error state with retry button |
| `DurationBadge.kt` | Pill badge showing MM:SS duration |
| `ThumbnailPlaceholder.kt` | Gradient placeholder for missing thumbnails |

### 3.4 Icons

**Files**: `core/designsystem/HzPlayerIcons.kt`

- Media-player-specific icon constants (shuffle, repeat, previous, next, play, pause, equalizer, folder, video, audio, search, settings, more_vert)

---

## Phase 4: Video Library (estimated: 1-2 sessions)

**Goal**: Browse videos in grid/list, sort, filter, navigate to player.

### Files

| File | Role |
|---|---|
| `presentation/video/VideoLibraryUiState.kt` | State data class |
| `presentation/video/VideoLibraryViewModel.kt` | ViewModel with StateFlow |
| `presentation/video/VideoLibraryScreen.kt` | Stateless composable |
| `presentation/video/components/VideoCategorySection.kt` | Section header + horizontal row of category cards |
| `core/components/MediaCard.kt` | Video card with thumbnail |
| `core/components/MediaGrid.kt` | Grid layout |
| `core/components/MediaListItem.kt` | List layout |
| `core/components/SortFilterChips.kt` | Sort controls |
| `core/components/ViewToggleFab.kt` | Grid/list toggle |
| `presentation/preview/PreviewMedia.kt` | Preview data |

### UiState

```kotlin
data class VideoLibraryUiState(
    val categories: List<VideoCategory> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val searchQuery: String? = null,
)
```

### States to Cover

- **Loading**: Shimmer placeholders in grid layout
- **Empty**: "No videos found" with icon and "Browse files" button
- **Error**: Error message with retry button
- **Content**: Grid/list of video cards
- **Search active**: Filtered results with "No results" fallback

---

## Phase 5: Audio Browser (estimated: 1-2 sessions)

**Goal**: Browse artists/albums/songs/genres, play from any view.

### Files

| File | Role |
|---|---|
| `presentation/audio/AudioBrowserUiState.kt` | State with tabs + content |
| `presentation/audio/AudioBrowserViewModel.kt` | Tab management + content loading |
| `presentation/audio/AudioBrowserScreen.kt` | Tab layout with lazy content |
| `presentation/audio/components/AlbumCard.kt` | Album cover card |
| `core/components/MediaListItem.kt` | Song list item |

### UiState

```kotlin
data class AudioBrowserUiState(
    val currentTab: AudioTab = AudioTab.SONGS,
    val songs: List<AudioItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val isLoading: Boolean = true,
)
```

### States to Cover

- **Loading**: Shimmer per tab
- **Empty**: "No audio files" per tab
- **Error**: Retry
- **Content**: Tab-aware grid/list
- **Resume card**: "Resume" banner if audio was playing

---

## Phase 6: File Browser (estimated: 1 session)

**Goal**: Browse device storage, navigate directories, tap to play.

### Files

| File | Role |
|---|---|
| `presentation/browse/FileBrowserUiState.kt` | Navigation stack + file listing |
| `presentation/browse/FileBrowserViewModel.kt` | Directory navigation + file fetching |
| `presentation/browse/FileBrowserScreen.kt` | Directory content with breadcrumb |
| `presentation/browse/components/FileListItem.kt` | File/folder row item |

### UiState

```kotlin
data class FileBrowserUiState(
    val currentPath: String = "/",
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem("Device", "/")),
    val items: List<FolderItem> = emptyList(),
    val isLoading: Boolean = true,
)
```

---

## Phase 7: Video Player (estimated: 2-3 sessions)

**Goal**: Full video playback with controls, gestures, subtitles, audio tracks.

### Files

| File | Role |
|---|---|
| `presentation/player/PlayerUiState.kt` | Playback state, controls visibility, tracks |
| `presentation/player/PlayerViewModel.kt` | Binds Media3 player state → UiState |
| `presentation/player/VideoPlayerScreen.kt` | Player view + overlay |
| `presentation/player/components/PlayerControlsOverlay.kt` | HUD: seekbar, buttons, time |
| `presentation/player/components/PlayerSeekBar.kt` | Styled seekbar with buffer indicator |
| `presentation/player/components/MiniPlayerBar.kt` | Persistent mini player bar |
| `presentation/player/components/AudioPlayerSheet.kt` | Full audio player bottom sheet |
| `data/datasource/player/MediaPlayerHolder.kt` | Singleton ExoPlayer holder |
| `data/repository/PlayerRepositoryImpl.kt` | Player state management |

### PlayerUiState

```kotlin
data class PlayerUiState(
    val currentMedia: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPercentage: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleMode: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val audioTracks: List<TrackInfo> = emptyList(),
    val selectedSubtitleTrack: Int = -1,
    val selectedAudioTrack: Int = -1,
)
```

### Gestures to Support

- **Single tap**: Toggle controls overlay
- **Double tap left/right**: Seek back/forward (10s)
- **Swipe left side**: Brightness
- **Swipe right side**: Volume
- **Pinch**: Zoom to fit / fill
- **Swipe up/down**: Dismiss player (portrait)

---

## Phase 8: Settings (estimated: 1 session)

**Goal**: Configure audio, video, subtitle, display preferences.

### Files

| File | Role |
|---|---|
| `presentation/settings/SettingsScreen.kt` | Categorized settings list |
| `presentation/settings/components/SettingsSection.kt` | Section header + items |
| `presentation/settings/components/SettingsItem.kt` | Single preference row |

### Categories

- **Video**: Default subtitle track, jump delay, background play
- **Audio**: Audio jump delay, equalizer (future), track info
- **Playback**: Speed default, resume playback
- **Display**: View mode defaults, dark theme
- **Storage**: Media directories, hidden files
- **Advanced**: About, licenses, logs

---

## Phase 9: Search (estimated: 1 session)

**Goal**: Global search across videos, audio, and files.

### Files

| File | Role |
|---|---|
| `presentation/search/SearchScreen.kt` | Search bar + results |
| `presentation/search/SearchViewModel.kt` | Debounced search query |
| `presentation/search/SearchUiState.kt` | Query + filtered results |

---

## Phase 10: Integration (estimated: 2-3 sessions)

**Goal**: Wire real data sources, replace preview data with true repositories.

### Tasks

- Implement `MediaRepositoryImpl` using Room DAO + MediaScanner
- Implement `AudioRepositoryImpl` using Room + MediaStore
- Implement `FileRepositoryImpl` using ContentResolver
- Wire Media3 MediaSessionService for background playback
- Add notification with playback controls
- Add PiP support
- Add Android Auto support (future)
- Test playback across common formats

---

## Summary Timeline

| Phase | Sessions | Dependencies |
|---|---|---|
| 1. Foundation | 1 | None |
| 2. Architecture | 1 | Phase 1 |
| 3. Design System | 1 | Phase 2 |
| 4. Video Library | 1-2 | Phase 3 |
| 5. Audio Browser | 1-2 | Phase 3 |
| 6. File Browser | 1 | Phase 3 |
| 7. Video Player | 2-3 | Phase 1, 2, 3 |
| 8. Settings | 1 | Phase 3 |
| 9. Search | 1 | Phase 4, 5 |
| 10. Integration | 2-3 | All above |
| **Total** | **12-16** | |
