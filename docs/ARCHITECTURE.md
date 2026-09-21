# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.
> Verified against code at `8ffb763` — drift check: `git diff 8ffb763..HEAD -- app/src`.

---

## Layer Overview

```
┌─────────────────────────────────────────────────┐
│  Presentation (Compose UI)                      │
│  screens / components / viewmodels / ui-state    │
│  / navigation / player (PlayerSurface seam)      │
└──────────────────────┬──────────────────────────┘
                       │ StateFlow<UiState>  /  Events (callbacks)
┌──────────────────────▼──────────────────────────┐
│  Domain (pure Kotlin)                            │
│  models / repository interfaces / player         │
│  contract (IPlayerEngine)                        │
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

**Playback seam:** the presentation layer never imports Media3 types. It talks to
`IPlayerEngine` (`domain/player/`) and renders through the `PlayerSurface`
composable. The render-seam methods (`createRenderView`, `updateRenderView`,
`onRenderViewPaused/Resumed`) live on `IPlayerEngine`, so no typed casts are
needed. Only `ExoPlayerEngine` (in `data/`) knows about `ExoPlayer`/`PlayerView`.
See `docs/PLAYER_ARCHITECTURE.md`.

---

## Package Structure

```
com.rhnxdev.hzplayer/
├── HzPlayerApplication.kt          (@HiltAndroidApp)
├── MainActivity.kt                 (single activity host → AppNavigation)
├── VideoPlayerActivity.kt          (full-screen video player host)
├── AudioPlayerActivity.kt          (full-screen audio player host)
│
├── presentation/
│   ├── navigation/                 AppDestinations.kt (5 tabs) · AppNavigation.kt
│   │                               (NavRoutes + route builders) · MainNavHost.kt
│   │                               (full-screen overlay: search, player, detail)
│   ├── theme/                      Color.kt · Type.kt · Theme.kt (M3 dynamic)
│   ├── main/                       HzPlayerApp.kt · MainViewModel.kt ·
│   │                               components/MainTabPager.kt (HorizontalPager +
│   │                               NavigationSuiteScaffold) · MiniPlayerSection.kt
│   ├── video/                      VideoLibraryScreen / ViewModel / UiState
│   ├── audio/                      AudioBrowser* · AlbumDetailScreen ·
│   │                               ArtistDetailScreen · AudioDetailViewModel ·
│   │                               components/AlbumCard.kt · AudioDetailHeader.kt
│   ├── browse/                     FileBrowser* · components/ArchivePasswordDialog ·
│   │                               SolidArchiveWarningDialog · DirectoryStackContent ·
│   │                               StorageRootsContent · FileBrowserTopBarActions ·
│   │                               NewFolderDialog · PasteActionBar
│   ├── network/                    Network* · components/NetworkScreenContent ·
│   │                               ServerCard · ServerConfigDialog · StreamHistoryListItem
│   ├── player/                     VideoPlayerScreen · AudioPlayerScreen ·
│   │                               PlayerViewModel/UiState · PlayerSurface (render seam) ·
│   │                               PlayerPositionController (250 ms tick + resume save) ·
│   │                               PlayerTrackCache · PlayerPlaylistController ·
│   │                               PlayerDebugController · SubtitleBrowser/SearchViewModel ·
│   │                               components/ — overlay, seek, sheets, dialogs,
│   │                               EqualizerSheet, … (see UI_COMPONENTS.md)
│   ├── search/                     SearchScreen / ViewModel / UiState
│   ├── settings/                   SettingsScreen / ViewModel ·
│   │                               components/SettingsDialogs · SettingsItem ·
│   │                               SettingsSection · AboutDialog · UpdateDialog ·
│   │                               EnumSelectionDialog · ColorPickerDialog · SubdlApiKeyDialog
│   └── preview/PreviewMedia.kt     (DEBUG-only sample data)
│
├── domain/
│   ├── model/                      MediaItem (MediaType/SortType/SortDirection/
│   │                               ViewMode/RepeatMode) · VideoItem · AudioItem ·
│   │                               FolderItem · FolderCounts · PlayerState
│   │                               (PlayerState/PlaybackErrorKind/PlayerStateInfo) ·
│   │                               PlaybackProgress · DecoderMode · OrientationMode ·
│   │                               ResumeMode · NetworkProtocol · ServerConfig ·
│   │                               RemoteFileItem · RemoteAuthException ·
│   │                               NetworkTraffic · StreamHistoryItem · DebugStats ·
│   │                               AspectRatioMode · ThemeMode · BrowserHistoryItem ·
│   │                               ChapterInfo (container chapters: MKV/MP4/OGG) ·
│   │                               EqualizerInfo (EqualizerBand / EqualizerInfo /
│   │                               EqualizerSettings) ·
│   │                               UrlSuggestion · FileMediaTypeFilter (ALL/VIDEOS/
│   │                               AUDIO/ARCHIVES browse filter)
│   ├── player/                     EngineType (EXO_PLAYER / FFMPEG / NATIVE_FFMPEG) ·
│   │                               IPlayerEngine (+ shared `SEEK_END_MARGIN_MS` /
│   │                               `clampSeekPosition`) · RenderViewConfig ·
│   │                               PlaybackErrorMapper
│   ├── repository/                 one interface per domain area (table below)
│   └── usecase/                    none — ViewModels call repositories directly
│
├── data/
│   ├── repository/                 Media/Audio/File/Network/RemoteBrowse/Player/
│   │                               Resume/Subtitle/UserPreferences/Archive/
│   │                               BrowserHistory — one Impl each
│   ├── datasource/
│   │   ├── local/room/             HzPlayerDatabase + dao/ + entities/
│   │   ├── media/MediaScanner.kt   MediaStore index → Room cache
│   │   ├── archive/                ArchiveNative (JNI → libarchive) ·
│   │   │                           ArchiveDataSource (Media3 DataSource, archive://)
│   │   ├── network/                RemoteBrowserClient (interface) + Smb/Ftp/Sftp/WebDav
│   │   ├── player/                 ExoPlayer stack (MediaPlayerHolder · ExoPlayerEngine ·
│   │   │                           HzRenderersFactory · ExoDebugStats · ExoMediaItemHelper ·
│   │   │                           AudioDelaySink (ForwardingAudioSink) ·
│   │   │                           NeighborSubtitleDiscoverer · TenBandEqualizerProcessor ·
│   │   │                           EqualizerController · MediaPlaybackService — a Media3
│   │   │                           MediaSessionService), the native engine
│   │   │                           (FfmpegNativeEngine + ffmpeg/), network DataSources
│   │   │                           (RemoteDataSourceBase + FtpDataSource · SftpDataSource ·
│   │   │                           SmbDataSource · WebDavDataSource · ConnectionPool
│   │   │                           (SMB/FTP/SSH pooling) · SmbPathResolver ·
│   │   │                           SftpTofuVerifier), and mp4fork/ (forked Media3 MP4
│   │   │                           extractor: Samsung SEF motion photos, auxiliary tracks,
│   │   │                           container MIME)
│   │   ├── subtitle/assrender/     see "Native pipelines" below
│   │   └── remote/SubdlApi.kt      online subtitle search
│   ├── mapper/                     MediaMappers.kt · NetworkMappers.kt
│   └── security/PasswordCrypto.kt  encrypted server credentials in Room
│
├── browser/                        full in-app browser (own activity)
│   ├── adblock/                    AdBlockListManager · AdBlockNative · AdBlockUpdater
│   │                               (Rust engine via JNI)
│   ├── media/                      DetectedMediaItem · MediaDownloader ·
│   │                               MediaSnifferBridge · MediaSnifferEngine ·
│   │                               MediaStreamDecoder
│   ├── ui/                         BrowserScreen · BrowserTopBar · BrowserBottomBar ·
│   │                               TabStrip · TabSidebar · NewTabPage ·
│   │                               BrowserHistoryScreen · BrowserSettingsScreen ·
│   │                               MediaGrabberBottomSheet · PopupPermissionBottomSheet ·
│   │                               UrlSuggestionsPanel
│   └── (root)                      AdBlockEngine · BrowserActivity · BrowserSessionStore ·
│                                   BrowserSettings(+Store) · BrowserTab · BrowserViewModel ·
│                                   PendingPopupRequest · TabManager
│
├── core/
│   ├── designsystem/               HzPlayerIcons · Dimens (Spacing / CornerRadii /
│   │                               CardSizes / BrowserDimens / HzPlayerShapes) ·
│   │                               NavBarInsets · MediaAccent (mediaAccentColor)
│   ├── components/                 see UI_COMPONENTS.md
│   ├── io/                         RandomAccessMediaSource family (Local/Channel/Smb/
│   │                               Archive) + MediaInfoProbe
│   ├── thumbnail/                  native FFmpeg frame extractor + Coil fetcher
│   └── util/                       MediaTimeUtils · MediaExtensions · MimeTypeUtil ·
│                                   BreadcrumbBuilder · DirectoryLruCache ·
│                                   PlaybackFormatters · ServerDiscoverer ·
│                                   SubtitleLanguageResolver · UpdateChecker ·
│                                   ArchivePaths · IntentUtils · NetworkDomainUtils
│
└── di/                             AppModule · RepositoryModule · DatabaseModule ·
                                    PlayerEngineModule · EngineKey
```

### Native pipelines (JNI → `app/src/main/cpp/`)

| Pipeline | Kotlin entry points | Native side |
|---|---|---|
| Random-access media | `core/io/`: `RandomAccessMediaSource` (shared `seek`/`readAt` interface) with Local / Channel (`content://`) / Smb / Archive sources; `MediaInfoProbe` (container + codec probe for the Properties dialog) | — (sources feed the FFmpeg pipelines below) |
| Thumbnails | `core/thumbnail/VideoThumbnailFetcher` (Coil fetcher + disk cache) → `NativeThumbnailExtractor` (guards `System.loadLibrary`, degrades to a placeholder where the lib is absent) | `cpp/ThumbnailExtractor.cpp` — FFmpeg frame decode → RGBA |
| libass subtitles | `assrender/AssHandler` (singleton coordinator) · `AssDirectBridge` · `AssTrackOutput` / `AssExtractorOutput` / `AssExtractorsFactory` · `AssSubtitleParserFactory` · `AssMatroskaExtractor` / `AssFormat` / `AssTimeRenderer` · `SubtitleConverters` (SRT/VTT→ASS) · `SubtitleOverlayView` | `cpp/ass_direct.c` + `ass_direct_jni.c` (fontconfig-free libass) |
| Archive | `archive/ArchiveNative` → `ArchiveDataSource` (`archive://` open/read/seek/close) | `cpp/ArchiveExtractor.cpp` — libarchive, pinned v3.7.9 |
| Native FFmpeg player | `FfmpegNativeEngine` (bridges surface, aspect ratio, and `content://` / `smb://` / `file://` / `archive://` via `RandomAccessMediaSource` AVIO callbacks) · `ffmpeg/FfmpegNativePlayer` (typed JNI wrapper) · `ffmpeg/FfmpegAudioSink` (AudioTrack PCM + latency tracking) | `cpp/ffplayer/` (`libffplayer.so`) — demux/decode/AV-sync threads, ANativeWindow blit, AMediaCodec HW decode (H.264/HEVC/VP9/AV1 + HDR) with libdav1d/CPU fallback |
| ExoPlayer FFmpeg decoders | `ffmpeg/` Java extension decoders (`FfmpegLibrary` · `FfmpegAudioRenderer` + `FfmpegVideoRenderer` · `FfmpegAudioDecoder` + `FfmpegVideoDecoder` · `FfmpegDecoderException`) | FFmpeg JNI extension |
| Ad blocker | `browser/adblock/` (Rust engine via JNI) | Rust `libadblock` |

`FfmpegNativePlayer` also routes subtitle packets to the shared libass pipeline and
exposes the equalizer by forwarding its AudioTrack session id to `EqualizerController`.

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

`HzPlayerDatabase` (version 6) — 5 entities, KSP-generated DAOs:

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
// UiState — immutable snapshot, updated only via copy()
@Immutable
data class VideoLibraryUiState(
    val categories: List<VideoCategory> = emptyList(),
    val recentVideos: List<VideoItem> = emptyList(),
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val isEmpty: Boolean = false,
    val selectedFolder: String? = null,   // null = folder-list root; RECENT_KEY = Recent pseudo-folder
)

// ViewModel — @HiltViewModel exposing StateFlow<UiState>
@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VideoLibraryUiState())
    val uiState: StateFlow<VideoLibraryUiState> = _uiState.asStateFlow()
}

// Screen — stateless: state + lambda callbacks only
@Composable
fun VideoLibraryScreen(
    uiState: VideoLibraryUiState,
    onVideoClick: (VideoItem) -> Unit,
    onSortChanged: (SortType) -> Unit,
    onViewModeChanged: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
)
```

Engine selection is **not** in the screen: `PlayerRepositoryImpl` holds
`Map<EngineType, IPlayerEngine>` and exposes `activeEngine` + `availableEngines`.
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

A new backend = implement `IPlayerEngine` + add one `@Binds @IntoMap @EngineKey` line.

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
The browser is a separate activity with its own UI stack.
