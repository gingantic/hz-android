# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.
> Last refreshed: 2026-08-22 (three playback engines incl. standalone native FFmpeg,
> 10-band equalizer, browser omnibox suggestions + web PiP, file operations,
> mp4fork extractor).

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
├── VideoPlayerActivity.kt          (full-screen video player host)
├── AudioPlayerActivity.kt          (full-screen audio player host)
│
├── presentation/
│   ├── navigation/
│   │   ├── AppDestinations.kt      (sealed class of 5 bottom-nav tabs + bottomNavDestinations list)
│   │   ├── AppNavigation.kt        (NavRoutes + route builder helpers)
│   │   └── MainNavHost.kt          (full-screen NavHost overlay: search, player, album/artist detail)
│   │
│   ├── theme/                      (Color.kt / Type.kt / Theme.kt — M3 dynamic)
│   │
│   ├── main/
│   │   ├── HzPlayerApp.kt          (extracted top-level app composable shell)
│   │   ├── MainViewModel.kt        (shared "now playing" / active-tab state)
│   │   └── components/
│   │       ├── MainTabPager.kt     (HorizontalPager + NavigationSuiteScaffold)
│   │       └── MiniPlayerSection.kt (scoped mini-player isolating position recomposition)
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
│   │   └── components/
│   │       ├── ArchivePasswordDialog.kt
│   │       ├── SolidArchiveWarningDialog.kt  (solid archive perf warning + dont-show-again)
│   │       ├── DirectoryStackContent.kt      (directory listing with breadcrumb + scroll save)
│   │       ├── StorageRootsContent.kt         (storage root picker + pull-to-refresh)
│   │       ├── FileBrowserTopBarActions.kt    (sort/view/media-mode top bar buttons)
│   │       └── PasteActionBar.kt              (cut/copy/move/delete paste bar)
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
│   │   ├── PlayerMoreOptionsSheet.kt  (sleep timer, jump-to, chapters, A-B repeat, play-as-audio)
│   │   └── components/   (see UI_COMPONENTS.md — overlay, seek, sheets, dialogs,
│   │                      EqualizerSheet, …)
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
│   │   ├── AspectRatioMode.kt ThemeMode.kt BrowserHistoryItem.kt
│   │   ├── ChapterInfo.kt (container chapter markers: MKV/MP4/OGG)
│   │   ├── EqualizerInfo.kt (EqualizerBand / EqualizerInfo / EqualizerSettings)
│   │   ├── UrlSuggestion.kt (browser omnibox suggestion)
│   │   └── FileMediaTypeFilter.kt (ALL/VIDEOS/AUDIO/ARCHIVES browse filter)
│   │
│   ├── player/
│   │   ├── EngineType.kt IPlayerEngine.kt
│   │   │   // EngineType: EXO_PLAYER, FFMPEG (ExoPlayer w/ FFmpeg-first renderers),
│   │   │   // NATIVE_FFMPEG (standalone libffplayer.so engine)
│   │   │   // IPlayerEngine also carries: setScrubbing, play(artworkUri), setDisableHdr,
│   │   │   // setFfmpegPreferred, equalizer block (getEqualizerState + band/preset/
│   │   │   // bass/loudness controls), setAudioDelay/getAudioDelay
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
│   │   ├── ArchiveRepositoryImpl.kt BrowserHistoryRepositoryImpl.kt
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
│   │   │   ├── ExoPlayerEngine.kt       (IPlayerEngine impl for EXO_PLAYER + FFMPEG)
│   │   │   ├── FfmpegNativeEngine.kt    (IPlayerEngine impl for NATIVE_FFMPEG)
│   │   │   ├── ffmpeg/                  (native FFmpeg player support)
│   │   │   │   ├── FfmpegNativePlayer.kt  (JNI bridge → libffplayer.so)
│   │   │   │   ├── FfmpegAudioSink.kt     (AudioTrack PCM output + latency tracking)
│   │   │   │   ├── FfmpegMimeTypes.kt
│   │   │   │   ├── FfmpegLibrary.java / FfmpegAudioRenderer.java / FfmpegVideoRenderer.java
│   │   │   │   └── FfmpegAudioDecoder.java / FfmpegVideoDecoder.java / FfmpegDecoderException.java
│   │   │   │                            (Media3 extension decoders for software decode)
│   │   │   ├── mp4fork/                 (forked Media3 MP4 extractor: Samsung SEF
│   │   │   │                             motion-photo reading, auxiliary tracks,
│   │   │   │                             container MIME resolution)
│   │   │   ├── ExoDebugStats.kt         (frame rate / decoder labeling / stats extraction)
│   │   │   ├── ExoMediaItemHelper.kt    (MediaItem building + subtitle MIME inference)
│   │   │   ├── AudioDelaySink.kt        (ForwardingAudioSink for A/V sync offset)
│   │   │   ├── NeighborSubtitleDiscoverer.kt (auto-loads neighbor .srt/.ass)
│   │   │   ├── ConnectionPool.kt        (SMB/FTP/SSH pooling)
│   │   │   ├── RemoteDataSourceBase.kt  (shared base for protocol DataSources)
│   │   │   ├── FtpDataSource.kt SftpDataSource.kt SmbDataSource.kt WebDavDataSource.kt
│   │   │   ├── SmbPathResolver.kt SftpTofuVerifier.kt
│   │   │   ├── HzRenderersFactory.kt    (single RenderersFactory: EQ + audio delay + ASS
│   │   │   │                             renderers; preferFfmpeg renderer reordering)
│   │   │   ├── TenBandEqualizerProcessor.kt EqualizerController.kt
│   │   │   └── MediaPlaybackService.kt  (Media3 MediaSessionService)
│   │   ├── subtitle/assrender/          (native libass subtitle pipeline)
│   │   │   ├── AssHandler.kt            (singleton coordinator: data→libass→bitmap)
│   │   │   ├── AssDirectBridge.kt       (JNI bridge to libass)
│   │   │   ├── AssTrackOutput.kt / AssExtractorOutput.kt / AssExtractorsFactory.kt
│   │   │   ├── AssSubtitleParserFactory.kt
│   │   │   ├── AssMatroskaExtractor.kt / AssFormat.kt / AssTimeRenderer.kt
│   │   │   ├── SubtitleConverters.kt    (SRT/VTT→ASS conversion)
│   │   │   └── SubtitleOverlayView.kt   (custom View for bitmap subtitles)
│   │   └── remote/SubdlApi.kt
│   │
│   ├── mapper/MediaMappers.kt NetworkMappers.kt
│   └── security/PasswordCrypto.kt     (encrypted server credentials in Room)
│
├── browser/                              (full in-app browser)
│   ├── adblock/AdBlockListManager.kt / AdBlockNative.kt / AdBlockUpdater.kt
│   │         (Rust adblock engine integration via JNI)
│   ├── media/DetectedMediaItem.kt / MediaDownloader.kt / MediaSnifferBridge.kt
│   │         / MediaSnifferEngine.kt / MediaStreamDecoder.kt
│   ├── ui/BrowserScreen.kt / BrowserTopBar.kt / BrowserBottomBar.kt / TabStrip.kt
│   │      / TabSidebar.kt / NewTabPage.kt / BrowserHistoryScreen.kt
│   │      / BrowserSettingsScreen.kt / MediaGrabberBottomSheet.kt
│   │      / PopupPermissionBottomSheet.kt / UrlSuggestionsPanel.kt (omnibox history suggestions)
│   ├── AdBlockEngine.kt / BrowserActivity.kt / BrowserSessionStore.kt
│   │   BrowserSettings.kt / BrowserSettingsStore.kt / BrowserTab.kt
│   │   BrowserViewModel.kt / PendingPopupRequest.kt / TabManager.kt
│
├── core/
│   ├── designsystem/  (HzPlayerIcons.kt Dimens.kt NavBarInsets.kt)
│   │   // Dimens.kt also exports Spacing, CornerRadii, CardSizes, BrowserDimens, HzPlayerShapes
│   ├── components/    (see UI_COMPONENTS.md)
│   ├── thumbnail/     (native FFmpeg extractor + Coil fetcher + MediaInfoProbe
│   │                   + RandomAccessBridge family incl. ArchiveRandomAccessBridge)
│   └── util/          (MediaTimeUtils / MediaExtensions / MimeTypeUtil /
│                       BreadcrumbBuilder / DirectoryLruCache / PlaybackFormatters /
│                       ServerDiscoverer / SubtitleLanguageResolver / UpdateChecker /
│                       ArchivePaths / IntentUtils / NetworkDomainUtils)
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
- `AssSubtitleParserFactory.kt` (with `player/HzRenderersFactory.kt`) — route ASS/SSA tracks to the
  libass pipeline instead of Media3's built-in text renderer.
- `SubtitleConverters.kt` — converts SRT/VTT to ASS on-the-fly for unified libass rendering.
- `SubtitleOverlayView.kt` — custom Android View that displays the rendered bitmap overlay.
- `cpp/ass_direct.c` + `ass_direct_jni.c` — native libass rendering (fontconfig-free).

### Native archive pipeline (`data/datasource/archive/` + `cpp/`)
- `ArchiveNative.kt` — JNI bridge to `cpp/ArchiveExtractor.cpp` (libarchive, pinned v3.7.9).
- `ArchiveDataSource.kt` — Media3 `DataSource` for `archive://` URIs (open/read/seek/close).
- `cpp/ArchiveExtractor.cpp` — libarchive list/open/read/seek/close via JNI.

### Native FFmpeg player pipeline (`FfmpegNativeEngine` + `cpp/FfmpegPlayer.cpp`)
- `FfmpegNativeEngine.kt` — `IPlayerEngine` impl for `NATIVE_FFMPEG`; bridges surface
  events, aspect ratio, and data sources (`content://`, `smb://`, `file://`) into the
  native player via `RandomAccessBridge` AVIO callbacks.
- `ffmpeg/FfmpegNativePlayer.kt` — typed JNI wrapper over `cpp/FfmpegPlayer.cpp`
  (`libffplayer.so`): demux/decode/AV-sync threads, ANativeWindow blit,
  AMediaCodec hardware decode (H.264/HEVC/VP9/AV1 + HDR) with libdav1d/CPU fallback.
- `ffmpeg/FfmpegAudioSink.kt` — AudioTrack PCM output with head-position latency tracking.
- `FfmpegNativePlayer` also routes subtitle packets to the shared libass pipeline
  (`AssHandler`) and exposes the equalizer by forwarding the AudioTrack session id to
  `EqualizerController`.
- See `docs/FFMPEG_NATIVE_AUDIT_AND_ROADMAP.md` for the deep technical audit.

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
| `BrowserHistoryRepository` | `BrowserHistoryRepositoryImpl` | Browser history persistence |

---

## Room Database

`HzPlayerDatabase` (version 6) holds 5 entities with KSP-generated DAOs:

| Entity | DAO | Key columns |
|---|---|---|
| `MediaEntity` | `MediaDao` | uri, mediaType, title, album, artist + indices |
| `ServerConfigEntity` | `ServerConfigDao` | protocol, host, port, credentials (encrypted via `PasswordCrypto`) |
| `StreamHistoryEntity` | `StreamHistoryDao` | url, headersJson, pageUrl, mimeType, isFavorite |
| `PlaybackPositionEntity` | `PlaybackPositionDao` | mediaId, positionMs, durationMs |
| `BrowserHistoryEntity` | `BrowserHistoryDao` | url, title, timestamp |

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
