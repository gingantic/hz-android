# Hz Player — Architecture

> Clean MVVM with unidirectional data flow for a Compose-first media player.
> Verified against code at `efeba7b` — drift check: `git diff efeba7b..HEAD -- app/src`.

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

**The browser** is a separate top-level package (`browser/`) with its own activity,
not part of the main nav graph. It still uses the same layers: `BrowserViewModel`
(`@HiltViewModel`) → `TabManager` (WebView host) → `AdBlockEngine` / `MediaSnifferEngine`.

---

## Package Structure

```
com.rhnxdev.hzplayer/
├── HzPlayerApplication.kt          (@HiltAndroidApp · Coil SingletonImageLoader
│                                   factory · app-scope thumbnail prune · SFTP
│                                   known-hosts path · ConnectionPool shutdown)
├── MainActivity.kt                 (single activity host → AppNavigation; PiP)
├── VideoPlayerActivity.kt          (full-screen video player host; PiP)
├── AudioPlayerActivity.kt          (full-screen audio player host)
│
├── presentation/
│   ├── navigation/                 AppDestinations.kt (5 tabs) · AppNavigation.kt
│   │                               (NavRoutes) · MainNavHost.kt
│   │                               (full-screen overlay: search, history, player,
│   │                               detail)
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
│   ├── history/                    HistoryScreen · HistoryViewModel · HistoryUiState
│   │                               (recently-played list)
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
│   │                               EnumSelectionDialog · EnumLabels · ColorPickerDialog ·
│   │                               SubdlApiKeyDialog
│   └── preview/PreviewMedia.kt     (DEBUG-only sample data)
│
├── domain/
│   ├── model/                      MediaItem (MediaType/SortType/SortDirection/
│   │                               ViewMode/RepeatMode) · VideoItem · AudioItem ·
│   │                               FolderItem (+ StorageKind) · FolderCounts · PlayerState
│   │                               (PlayerState/PlaybackErrorKind/PlayerStateInfo) ·
│   │                               PlaybackProgress · DecoderMode · OrientationMode ·
│   │                               ResumeMode · NetworkProtocol · ServerConfig ·
│   │                               RemoteFileItem · RemoteAuthException ·
│   │                               NetworkTraffic · StreamHistoryItem · DebugStats ·
│   │                               AspectRatioMode · ThemeMode · BrowserHistoryItem ·
│   │                               PlayHistoryItem · StorageKind · ChapterInfo ·
│   │                               EqualizerInfo (EqualizerBand / EqualizerInfo /
│   │                               EqualizerSettings) · UrlSuggestion ·
│   │                               FileMediaTypeFilter (ALL/VIDEOS/AUDIO/ARCHIVES)
│   ├── player/                     EngineType (EXO_PLAYER / FFMPEG / NATIVE_FFMPEG) ·
│   │                               IPlayerEngine (+ shared `SEEK_END_MARGIN_MS` /
│   │                               `clampSeekPosition`) · RenderViewConfig ·
│   │                               PlaybackErrorMapper · NativeErrorMapper
│   ├── repository/                 one interface per domain area (table below)
│   └── usecase/                    none — ViewModels call repositories directly
│
├── data/
│   ├── repository/                 Media/Audio/File/Network/RemoteBrowse/Player/
│   │                               Resume/Subtitle/UserPreferences/Archive/
│   │                               BrowserHistory/PlayHistory — one Impl each ·
│   │                               CachedScanFlow.kt (shared cache-first listing flow)
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
│   │   │                           (FfmpegNativeEngine + ffmpeg/ + NativeTlsCaBundle),
│   │   │                           network DataSources (RemoteDataSourceBase + FtpDataSource ·
│   │   │                           SftpDataSource · SmbDataSource · WebDavDataSource ·
│   │   │                           ConnectionPool (SMB/FTP/SSH/WebDAV pooling) ·
│   │   │                           SmbPathResolver · SftpTofuVerifier), and mp4fork/
│   │   │                           (forked Media3 MP4 extractor: Samsung SEF motion
│   │   │                           photos, auxiliary tracks, container MIME)
│   │   ├── subtitle/assrender/     see "Native pipelines" below
│   │   └── remote/SubdlApi.kt      online subtitle search
│   ├── mapper/                     MediaMappers.kt · NetworkMappers.kt
│   └── security/PasswordCrypto.kt  encrypted server credentials in Room
│
├── browser/                        full in-app browser (own activity)
│   ├── adblock/                    AdBlockEngine · AdBlockListManager · AdBlockNative ·
│   │                               AdBlockUpdater · ElementPickerBridge ·
│   │                               SelectorHeuristics · CosmeticRuleBuilder
│   │                               (Rust engine via JNI)
│   ├── media/                      DetectedMediaItem · MediaDownloader ·
│   │                               MediaSnifferBridge · MediaSnifferEngine ·
│   │                               MediaStreamDecoder
│   ├── ui/                         BrowserScreen · BrowserTopBar · BrowserBottomBar ·
│   │                               TabSidebar · NewTabPage ·
│   │                               BrowserHistoryScreen · BrowserSettingsScreen ·
│   │                               MediaGrabberBottomSheet · PopupPermissionBottomSheet ·
│   │                               ElementPickerPanel · JsDialog · UrlSuggestionsPanel
│   └── (root)                      BrowserActivity · BrowserSessionStore ·
│                                   BrowserSettings(+Store) · BrowserTab · BrowserViewModel ·
│                                   BrowserDialogs · PendingPopupRequest · TabManager
│
│   Note: `AdBlockEngine.kt` physically sits in `browser/` but declares
│   `package …browser.adblock` — the ad-block package owns it.
│
├── core/
│   ├── designsystem/               HzPlayerIcons · Dimens (Spacing / CornerRadii/
│   │                               CardSizes / BrowserDimens / HzPlayerShapes) ·
│   │                               NavBarInsets · MediaAccent (mediaAccentColor)
│   ├── components/                 see UI_COMPONENTS.md
│   ├── io/                         RandomAccessMediaSource family (Local/Channel/Smb/
│   │                               Archive) + MediaInfoProbe + SmbSourceBridge
│   ├── thumbnail/                  native FFmpeg frame extractor + Coil fetcher +
│   │                               ThumbnailCacheCleanup
│   └── util/                       MediaTimeUtils · MediaExtensions · MimeTypeUtil ·
│                                   BreadcrumbBuilder · DirectoryLruCache ·
│                                   PlaybackFormatters · ServerDiscoverer ·
│                                   SubtitleLanguageResolver · UpdateChecker ·
│                                   ArchivePaths · IntentUtils · NetworkDomainUtils ·
│                                   HttpHeaderSanitizer · PipUtils · StorageVolumes ·
│                                   UriUtils · DebouncedAction
│
└── di/                             AppModule · RepositoryModule · DatabaseModule ·
                                    PlayerEngineModule · EngineKey
```

### Native pipelines (JNI → `app/src/main/cpp/`)

| Pipeline | Kotlin entry points | Native side |
|---|---|---|
| Random-access media | `core/io/`: `RandomAccessMediaSource` (shared `seek`/`readAt` interface) with Local / Channel (`content://`) / Smb / Archive sources; `MediaInfoProbe` (container + codec probe for the Properties dialog); `SmbSourceBridge` (`withSmbSource` — turns an `smb://` URI into a pooled random-access source for the native probes) | — (sources feed the FFmpeg pipelines below) |
| Thumbnails | `core/thumbnail/VideoThumbnailFetcher` (Coil fetcher + disk cache) → `NativeThumbnailExtractor` (guards `System.loadLibrary`, degrades to a placeholder where the lib is absent) | `cpp/ThumbnailExtractor.cpp` — FFmpeg frame decode → RGBA |
| libass subtitles | `assrender/AssHandler` (singleton coordinator) · `AssDirectBridge` · `AssTrackOutput` / `AssExtractorOutput` / `AssExtractorsFactory` · `AssSubtitleParserFactory` · `AssMatroskaExtractor` / `AssFormat` / `AssTimeCodec` / `AssTimeRenderer` · `SubtitleConverters` (SRT/VTT→ASS) · `SubtitleOverlayView` | `cpp/ass_direct.c` + `ass_direct_jni.c` (fontconfig-free libass) |
| Archive | `archive/ArchiveNative` → `ArchiveDataSource` (`archive://` open/read/seek/close) | `cpp/ArchiveExtractor.cpp` — libarchive, pinned v3.7.9 |
| Native FFmpeg player | `FfmpegNativeEngine` (bridges surface, aspect ratio, and `content://` / `smb://` / `file://` / `archive://` / `http(s)://` via `RandomAccessMediaSource` AVIO callbacks) · `ffmpeg/FfmpegNativePlayer` (typed JNI wrapper) · `ffmpeg/FfmpegAudioSink` (AudioTrack PCM + latency tracking) | `cpp/ffplayer/` (`libffplayer.so`) — demux/decode/AV-sync threads, ANativeWindow blit, AMediaCodec HW decode (H.264/HEVC/VP9/AV1 + HDR) with libdav1d/CPU fallback, `NetworkIo.cpp` (web opens, signed-playlist query re-attachment, PNG-prefix stripping) |
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
| `FileRepository` | `FileRepositoryImpl` | Local filesystem browse + copy/move/delete/mkdir |
| `NetworkRepository` | `NetworkRepositoryImpl` | Server config CRUD, stream history |
| `RemoteBrowseRepository` | `RemoteBrowseRepositoryImpl` | SMB/FTP/SFTP/WebDAV directory listing |
| `PlayerRepository` | `PlayerRepositoryImpl` | Delegates to active `IPlayerEngine` |
| `ResumeRepository` | `ResumeRepositoryImpl` | Persisted playback position resume |
| `SubtitleRepository` | `SubtitleRepositoryImpl` | SubDL search + local subs |
| `ArchiveRepository` | `ArchiveRepositoryImpl` | Archive listing + entry URIs via libarchive |
| `UserPreferencesRepository` | `UserPreferencesRepositoryImpl` | DataStore prefs + active engine |
| `BrowserHistoryRepository` | `BrowserHistoryRepositoryImpl` | Browser history persistence |
| `PlayHistoryRepository` | `PlayHistoryRepositoryImpl` | Recently-played list (deduped by URI) |

`MediaRepositoryImpl.getAllVideos` and `AudioRepositoryImpl.getAllSongs` share
`CachedScanFlow` (`data/repository/CachedScanFlow.kt`) — the cache-first listing
order is **in-memory cache → Room → debug preview → MediaStore scan**, and each
stage emits as soon as it has data.

---

## Room Database

`HzPlayerDatabase` (version 7) — 6 entities, KSP-generated DAOs:

| Entity | DAO | Key columns |
|---|---|---|
| `MediaEntity` | `MediaDao` | uri, mediaType, title, album, artist + indices |
| `ServerConfigEntity` | `ServerConfigDao` | protocol, host, port, credentials (encrypted via `PasswordCrypto`) |
| `StreamHistoryEntity` | `StreamHistoryDao` | url, headersJson, pageUrl, mimeType, isFavorite |
| `PlaybackPositionEntity` | `PlaybackPositionDao` | mediaId, positionMs, durationMs |
| `BrowserHistoryEntity` | `BrowserHistoryDao` | url, title, timestamp (capped at 500 rows) |
| `PlayHistoryEntity` | `PlayHistoryDao` | uri (PK), title, isVideo, playedAt + index on playedAt |

Migrations (all declared in `HzPlayerDatabase` and registered in `DatabaseModule`):

| Migration | Change |
|---|---|
| `MIGRATION_3_4` | Indices on `media` (mediaType, uri, album, artist) and `stream_history` (url, isFavorite) |
| `MIGRATION_4_5` | `browser_history` table + indices |
| `MIGRATION_5_6` | `stream_history` gains `headersJson`, `pageUrl`, `mimeType` |
| `MIGRATION_6_7` | `play_history` table + `index_play_history_playedAt` |

The builder uses `fallbackToDestructiveMigrationOnDowngrade()` — a **downgrade**
wipe only. `exportSchema = true`; the regenerated schemas are committed under
`app/schemas/com.rhnxdev.hzplayer.data.datasource.local.room.HzPlayerDatabase/`
(`3.json` … `7.json`).

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

Four Hilt modules, all `@InstallIn(SingletonComponent::class)`:

| Module | Provides |
|---|---|
| `AppModule` | `DataStore<Preferences>` (`user_preferences`) · `ContentResolver` |
| `DatabaseModule` | `HzPlayerDatabase` (singleton, name `hz_player_database`, 4 migrations) + the 6 DAOs |
| `RepositoryModule` | 12 `@Binds @Singleton` — one impl per repository interface (table above) |
| `PlayerEngineModule` | 3 `@Binds @IntoMap @EngineKey` — `EXO_PLAYER` and `FFMPEG` both bind the same `ExoPlayerEngine` singleton; `NATIVE_FFMPEG` binds `FfmpegNativeEngine` |

`MediaPlayerHolder` is not provided by a module — it is a `@Singleton` class with
an `@Inject` constructor, so Hilt constructs it directly.

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
//   video_player/{videoId}  video_player/-1  audio_player  search
//   history                  album_detail/{title}  artist_detail/{name}
```

`NavigationSuiteScaffold` hosts the 5 tabs; detail screens push onto the same NavHost.
The browser is a separate activity with its own UI stack.

**History** is a full-screen overlay route (`NavRoutes.HISTORY`), not a sixth tab.
It is reached from the Video Library top-bar action (`Icons.Filled.History`) and
lists the recently-played entries; tapping one calls `playUri(...)` and pushes
`video_player/-1` or `audio_player`.
