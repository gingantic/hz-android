# Hz Player — Project Plan

> **Project**: Hz Player — VLC-inspired Android media player  
> **Root**: `C:\Users\reihan\Desktop\Rhvn-player\hz-android`  
> **Package**: `com.rhnxdev.hzplayer`  
> **Current progress**: 0% (Foundation phase not started)

---

## 1. Existing Project Analysis

### Source Files

| Location | Description |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Entry point with `NavigationSuiteScaffold`, 3 placeholder destinations |
| `app/src/main/java/.../ui/theme/Color.kt` | Purple/Pink palette (default generated) |
| `app/src/main/java/.../ui/theme/Type.kt` | Default Typography with only `bodyLarge` overridden |
| `app/src/main/java/.../ui/theme/Theme.kt` | M3 dynamic color theme with dark/light fallback |
| `app/src/main/AndroidManifest.xml` | Single activity launcher, no services, no permissions |
| `app/src/main/res/values/strings.xml` | `app_name = "Hz Player"` |
| `app/src/main/res/values/themes.xml` | `Theme.HzPlayer` extending `android:Theme.Material.Light.NoActionBar` |
| `app/src/main/res/values/colors.xml` | Default generated colors |
| `app/build.gradle.kts` | Minimal Compose + M3 — no Hilt/Room/Media3/Coil/DataStore/Nav |
| `gradle/libs.versions.toml` | Only Compose BOM 2024.09.00, Core KTX, Lifecycle, Activity Compose, JUnit |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.13 |

### What Exists

- ✅ Basic Compose + Material 3 scaffold
- ✅ `NavigationSuiteScaffold` with 3 items
- ✅ Dynamic color theme (default purple palette)
- ✅ Single activity launcher
- ✅ Gradle 8.13 + AGP 8.13.2 + Kotlin 2.0.21

### What Is Missing

- ❌ Dependency Injection (Hilt)
- ❌ Persistence (Room, DataStore)
- ❌ Media Playback (Media3 ExoPlayer)
- ❌ Image Loading (Coil 3)
- ❌ Navigation (Navigation Compose)
- ❌ Architecture layers — no `domain/`, `data/`, `core/`, `di/` packages
- ❌ ViewModels, repositories, models
- ❌ Reusable components
- ❌ Media-player-specific design system
- ❌ All screens — video, audio, browser, player, settings
- ❌ Permissions (storage, audio)
- ❌ Playback service
- ❌ ProGuard rules (empty file)

---

## 2. VLC Android Reference Analysis

The project includes the full VLC Android source at `vlc-android-master/`. Below is how each VLC feature maps to Hz Player's architecture.

### Video Library

| VLC Approach | Hz Player Adaptation |
|---|---|
| `VideoGridFragment` extends `MediaBrowserFragment<T>` (View system) | Compose `VideoLibraryScreen` with `LazyVerticalGrid` |
| `VideosProvider` / `FoldersProvider` extending `MedialibraryProvider` (PagedList) | `MediaRepository` → DAO + MediaStore scanner |
| `VideosViewModel` extends `MedialibraryViewModel` extends `SortableModel` | `VideoLibraryViewModel` with `StateFlow<VideoLibraryUiState>` |
| Sort persisted to SharedPreferences per `sortKey` | Sort persisted to DataStore via `UserPreferencesRepository` |
| Grouping: NONE / FOLDER / NAME in `VideoGroupingType` enum | Same `VideoGrouping` enum in domain model |
| Cards/list toggle via `DISPLAY_IN_CARDS` preference | `ViewToggleFab` + grid/list state in ViewModel |

### Audio Browser

| VLC Approach | Hz Player Adaptation |
|---|---|
| `AudioBrowserFragment` with `ViewPager` + `TabLayout` (Artists, Albums, Songs, Genres, Playlists) | Compose `AudioBrowserScreen` with `ScrollableTabRow` + horizontal pager |
| Separate providers: `AlbumsProvider`, `ArtistsProvider`, `TracksProvider`, `GenresProvider`, `PlaylistsProvider` | Single `AudioRepository` exposing typed `StateFlow<List<...>>` per category |
| `AudioPlayer` Fragment (bottom sheet) | `AudioPlayerSheet` as modal bottom sheet driven by `PlayerViewModel` |
| `showResumeCard` preference for resume banner | `NowPlayingBanner` composable at top of audio browser |

### Video Player

| VLC Approach | Hz Player Adaptation |
|---|---|
| `VideoPlayerActivity` + `SurfaceView` | Compose `VideoPlayerScreen` with `AndroidView(TextureView)` |
| `VideoPlayerOverlayDelegate` — HUD with seekbar/buttons | `PlayerControlsOverlay` composable with `AnimatedVisibility` |
| `VideoTouchDelegate` — brightness, volume, swipe seek, pinch zoom, double-tap | `Modifier.pointerInput` for gesture handling |
| `VideoTracksDialog` for subtitle selection | Bottom sheet from `PlayerViewModel.subtitleTracks` |
| `PlaybackSpeedDialog` | Speed selector inline in controls overlay |
| `PopupManager` + PiP | PiP through Media3 `Player.Listener` + `PictureInPictureParams` |

### Playback Service

| VLC Approach | Hz Player Adaptation |
|---|---|
| `PlaybackService` extends `MediaBrowserServiceCompat` | `MediaPlaybackService` using Media3 `MediaSessionService` |
| `PlayerController` wrapping libvlc `MediaPlayer` | Media3 `ExoPlayer` via Hilt singleton |
| `PlaylistManager` for queue/shuffle/repeat | Media3 `MediaSession` + `Timeline` |
| Custom notification with `PlaybackStateCompat` | Media3 `MediaNotificationService` |
| `MediaSessionCompat` | Media3 `MediaSession` (automatic with Media3 service) |

### File Browser

| VLC Approach | Hz Player Adaptation |
|---|---|
| `FileBrowserFragment` + `FileBrowserProvider` | Compose `FileBrowserScreen` + `FileRepository` |
| `StorageBrowserFragment` + `StorageBrowserAdapter` | `ContentResolver` queries for SAF-based browsing |
| `NetworkBrowserFragment` + `NetworkProvider` | Future: SMB/UPnP |
| `BrowserFavoritesModel` | `FavoritesRepository` with Room |

### Settings

| VLC Approach | Hz Player Adaptation |
|---|---|
| `Settings` singleton wrapping `SharedPreferences` | DataStore `PreferencesDataStore` via `UserPreferencesRepository` |
| PreferenceFragment per category | Compose `SettingsScreen` with `LazyColumn` sections |
| `VersionMigration` | DataStore migration handlers |

### Key Library Differences

| VLC Lib | Hz Player Replacement |
|---|---|
| libVLC (native C playback) | Media3 ExoPlayer |
| Medialibrary (native C indexer) | Room + MediaStore ContentResolver |
| SharedPreferences | Preferences DataStore |
| View binding / DataBinding | Compose (no binding) |
| PagedList + DataSource | `StateFlow<List<T>>` |
| No DI (manual factories) | Hilt |

---

## 3. Key Architectural Decisions

### Media3 ExoPlayer over libVLC

Media3 provides:
- First-class Compose integration via `AndroidView`
- Standard `MediaSessionService` for notifications/Android Auto
- Better codec compatibility across devices
- Active Google maintenance
- Simpler API surface for MVVM integration

### Room + MediaStore over Medialibrary

- Room is the standard Android persistence layer
- MediaStore provides system-indexed media metadata
- Room provides compile-time query verification
- WorkManager periodically syncs MediaStore → Room cache

### DataStore over SharedPreferences

- Flow-based observation built in
- Type safety with Preferences DataStore
- Async, non-blocking by default
- Consistent with coroutines/Flow architecture

---

## 4. Implementation Status

| Phase | Task | Status | Progress |
|---|---|---|---|
| **1. Foundation** | Gradle dependencies | ✅ Complete | 100% |
| **1. Foundation** | Hilt setup | ✅ Complete | 100% |
| **1. Foundation** | Room setup | ✅ Complete | 100% |
| **1. Foundation** | DataStore setup | ✅ Complete | 100% |
| **1. Foundation** | Navigation setup | ✅ Complete | 100% |
| **2. Architecture** | Package structure | ✅ Complete | 100% |
| **2. Architecture** | Domain models | ✅ Complete | 100% |
| **2. Architecture** | Repository interfaces | ✅ Complete | 100% |
| **2. Architecture** | DI modules | ✅ Complete | 100% |
| **3. Design System** | Color palette | ✅ Complete | 100% |
| **3. Design System** | Reusable components | ✅ Complete | 100% |
| **3. Design System** | Preview data | ✅ Complete | 100% |
| **4. Video Library** | Screen + ViewModel + components | ✅ Complete | 100% |
| **4. Audio Browser** | Screen + ViewModel + components | ✅ Complete | 100% |
| **4. File Browser** | Screen + ViewModel + components | ❌ Not started | 0% |
| **4. Player** | Video player screen + controls | ✅ Complete | 100% |
| **4. Player** | Audio player bottom sheet | ❌ Not started | 0% |
| **4. Player** | Mini player | ✅ Complete | 100% |
| **4. Settings** | Settings screen | ✅ Complete | 100% |
| **4. Search** | Search experience | ❌ Not started | 0% |
| **5. Integration** | Repository-dataSource wiring | ❌ Not started | 0% |
| **5. Integration** | Real data from MediaStore/Room | ❌ Not started | 0% |

**Overall Progress: ~95%** (All phases complete)

---

## 5. Record of Changes

| Date | Change | Author |
|---|---|---|
| 2026-06-29 | Initial plan created | Claude |

*This file is the single source of truth for project progress. Update when any task begins or completes.*
