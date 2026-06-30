# Hz Player — Data Flow Architecture

> How data moves from persistence to pixels.

---

## Layer Responsibilities

```
┌────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION                               │
│                                                                    │
│  Screen (Composable)                                               │
│    • Stateless — receives UiState + callbacks                      │
│    • Observes StateFlow via collectAsStateWithLifecycle()          │
│    • Never accesses repositories directly                          │
│                                                                    │
│  ViewModel (@HiltViewModel)                                        │
│    • Owns MutableStateFlow<UiState>                                │
│    • Calls repository suspend functions / collects Flows           │
│    • Transforms domain models → UiState (mapping)                  │
│    • Handles user intents (play, delete, favorite)                 │
│                                                                    │
│  UiState (data class)                                              │
│    • Immutable snapshot of screen state                            │
│    • Contains exactly what the screen renders                      │
│    • No business logic — pure data                                 │
└───────────────────────┬────────────────────────────────────────────┘
                        │ StateFlow<UiState>
                        │ Event callbacks
┌───────────────────────▼────────────────────────────────────────────┐
│                          DOMAIN                                    │
│                                                                    │
│  Model                                                             │
│    • Pure Kotlin data classes (MediaItem, Album, Playlist...)      │
│    • No Android dependencies                                       │
│                                                                    │
│  Repository Interface                                              │
│    • Contract between data and presentation layers                 │
│    • Returns Flow<List<T>> for observable reads                    │
│    • suspend fun for writes                                        │
│                                                                    │
│  UseCase (optional, add when cross-repo orchestration needed)      │
│    • Single-responsibility operations                              │
│    • E.g., SearchMediaUseCase (queries video + audio repos)        │
└───────────────────────┬────────────────────────────────────────────┘
                        │ Flow / suspend fun
┌───────────────────────▼────────────────────────────────────────────┐
│                           DATA                                     │
│                                                                    │
│  Repository Implementation                                         │
│    • Combines data sources                                         │
│    • Maps entities → domain models                                 │
│    • Handles error recovery, caching strategy                      │
│                                                                    │
│  DataSource                                                        │
│    • Room DAO: Flow-based, type-safe SQL                           │
│    • MediaStore ContentResolver: system-wide media index           │
│    • DataStore: preference persistence                             │
│    • Media3 ExoPlayer: playback state                              │
│                                                                    │
│  Entity (Room)                                                     │
│    • Database-row representation                                   │
│    • Has @Entity annotation                                        │
└───────────────────────┬────────────────────────────────────────────┘
                        │ SQLite / ContentProvider / SharedPreferences
```

---

## Read Flows

### Video Library — Grid Display

```
User opens video tab
  → VideoLibraryScreen collects VideoLibraryViewModel.uiState
    → ViewModel collects MediaRepository.getAllVideos()
      → MediaRepositoryImpl collects MediaDao.getAllVideos()
        → Room queries SQLite, returns Flow<List<MediaEntity>>
      → Maps List<MediaEntity> → List<VideoItem>
    → Maps List<VideoItem> → VideoLibraryUiState(videos=..., isLoading=false)
  → Screen renders LazyVerticalGrid with MediaCard components
```

### Audio Browser — Albums Tab

```
User taps Albums tab
  → AudioBrowserScreen collects AudioBrowserViewModel.uiState
    → ViewModel collects AudioRepository.getAlbums()
      → AudioRepositoryImpl collects from:
          → MediaDao.getAlbums() (cached Room data)
          → MediaScanner.scanAlbums() (refreshes from MediaStore)
      → Merges lists, deduplicates, returns Flow<List<Album>>
    → Maps → AudioBrowserUiState(albums=..., currentTab=ALBUMS)
  → Screen renders LazyVerticalGrid with AlbumCard components
```

### File Browser — Directory Listing

```
User navigates to /storage/emulated/0/Movies
  → FileBrowserScreen collects FileBrowserViewModel.uiState
    → ViewModel calls FileRepository.listDirectory(path)
      → FileRepositoryImpl queries ContentResolver with URI
        → Returns List<FolderItem>
    → Maps → FileBrowserUiState(items=..., currentPath=...)
  → Screen renders LazyColumn with FileListItem components
```

---

## Write Flows

### Play Video

```
User taps MediaCard
  → Screen calls onVideoClick(videoItem)
    → ViewModel.onPlayVideo(videoItem)
      → PlayerUseCase.play(videoItem)
        → PlayerRepository.play(mediaItem)
          → MediaPlayerHolder.player.setMediaItem(Media3 item)
          → MediaPlayerHolder.player.prepare()
          → MediaPlayerHolder.player.play()
      → Updates PlayerUiState
```

### Toggle Favorite

```
User long-presses MediaCard, selects "Favorite"
  → Screen calls onFavoriteClick(videoItem)
    → ViewModel.onToggleFavorite(videoItem)
      → MediaRepository.toggleFavorite(videoItem.id)
        → MediaDao.updateFavorite(id, !currentState)
      → Flow re-emits, UiState updates automatically
```

### Change Sort Order

```
User selects "Date" in SortFilterChips
  → Screen calls onSortChanged(SortType.DATE)
    → ViewModel.onSortChanged(SortType.DATE)
      → UserPreferencesRepository.setSortOrder("video_sort", SortType.DATE)
        → DataStore.edit { prefs[sortKey] = sortType.name }
      → Re-collects MediaRepository with new sort
```

---

## Player Data Flow

### Playback State

```
ExoPlayer (in MediaPlayerHolder)
  → Player.Listener.onIsPlayingChanged(), onPlaybackStateChanged()
    → PlayerRepository updates MutableStateFlow<PlayerState>
      → PlayerViewModel collects and maps to PlayerUiState.isPlaying, .currentPosition
        → Screen shows play/pause icon, seekbar position
```

### Seeking

```
User drags seekbar
  → onSeek(position)
    → PlayerViewModel.onSeek(position)
      → PlayerRepository.seekTo(position)
        → MediaPlayerHolder.player.seekTo(position)
      → ViewModel updates uiState.currentPosition (optimistic)
```

### Track Selection

```
User opens subtitle track selector
  → PlayerViewModel collects subtitleTracks from PlayerRepository
    → PlayerRepository reads ExoPlayer.getCurrentTracks()
      → Returns List<TrackInfo>
  → Screen shows bottom sheet with track list
  → User selects track
    → ViewModel.onSubtitleTrackSelected(index)
      → PlayerRepository.selectSubtitleTrack(index)
        → Calls player.setTrackSelectionParameters()
```

---

## Data Source Strategy

### Room (Primary Cache)

```kotlin
@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE type = 'video' ORDER BY date_added DESC")
    fun getAllVideos(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Query("UPDATE media SET is_favorite = :fav WHERE id = :id")
    suspend fun updateFavorite(id: Long, fav: Boolean)
}
```

### MediaStore (System Index)

```kotlin
class MediaScanner(private val context: Context) {
    fun scanVideos(): Flow<List<VideoItem>> = flow {
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )
        cursor?.use { /* iterate, map to VideoItem */ }
    }
}
```

### DataStore (Preferences)

```kotlin
class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {
    val sortPreferences: Flow<Map<String, SortType>> = dataStore.data.map { prefs ->
        SORT_KEYS.associateWith { key ->
            SortType.valueOf(prefs[stringPreferencesKey(key)] ?: "TITLE")
        }
    }

    suspend fun setSortOrder(key: String, sort: SortType) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = sort.name
        }
    }
}
```

---

## Threading Model

| Layer | Coroutine Context |
|---|---|
| UI (Composable) | Main (via `collectAsStateWithLifecycle`) |
| ViewModel | Main (via `viewModelScope`) |
| Repository | Dispatchers.Default or IO (via `withContext`) |
| Room DAO | Auto-dispatched by Room |
| MediaStore scan | Dispatchers.IO |
| ExoPlayer | Manages its own internal thread |
| DataStore | Auto-dispatched (IO) |
