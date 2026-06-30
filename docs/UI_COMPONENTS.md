# Hz Player — UI Component Catalog

> Every reusable composable in the design system, with spec and states.

---

## MediaCard.kt

**Path**: `core/components/MediaCard.kt`

A media item card for grid display. Resembles VLC / Plex thumbnail cards.

### Layout

```
┌──────────────────────┐
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │  ← thumbnail (16:9 for video, 1:1 for album)
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 2:15 │  ← gradient overlay + duration badge
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
├──────────────────────┤
│ Title                │  ← max 2 lines
│ Subtitle (year/artist)│ ← single line, secondary color
│ ████░░░░░░░░░░░░ 30% │  ← watched progress bar (optional)
└──────────────────────┘
```

### Parameters

```kotlin
@Composable
fun MediaCard(
    thumbnailData: Any?,          // URL, URI, or ResId (Coil model)
    title: String,
    subtitle: String,             // secondary info
    durationMs: Long? = null,     // shows duration badge if non-null
    progress: Float = -1f,        // 0f-1f, hidden if < 0
    cardShape: CardShape = CardShape.VIDEO, // determines aspect ratio
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

### States

- **Thumbnail loaded**: Coil async image
- **Thumbnail loading**: Gradient shimmer
- **Thumbnail error**: Gradient placeholder with media-type icon (film, music, folder)
- **Selected**: Elevated border + checkmark overlay (multi-select mode)

---

## MediaListItem.kt

**Path**: `core/components/MediaListItem.kt`

Horizontal list item for video, audio, and file browsing.

### Layout

```
┌──────┬────────────────────────────────┬────────┐
│ ▓▓▓▓ │ Title (1 line, bold)           │  3:45  │
│ ▓▓▓▓ │ Subtitle (1 line, secondary)   │        │
│ icon │                                 │        │
└──────┴────────────────────────────────┴────────┘
```

---

## MediaGrid.kt

**Path**: `core/components/MediaGrid.kt`

Wrapper around `LazyVerticalStaggeredGrid` or `LazyVerticalGrid` with adaptive columns.

```kotlin
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
)
```

### Column Count

- Phone portrait: 2 columns
- Phone landscape: 3 columns
- Tablet: 4-5 columns (adaptive based on width)

---

## SortFilterChips.kt

**Path**: `core/components/SortFilterChips.kt`

Horizontal scrollable chip row for sort type selection.

### Layout

```
[ Title ] [ Date ] [ Duration ] [ Size ]  ← horizontally scrollable
```

### Parameters

```kotlin
@Composable
fun SortFilterChips(
    options: List<SortChipOption>,
    selected: SortType,
    onOptionSelected: (SortType) -> Unit,
    modifier: Modifier = Modifier,
)
```

---

## ViewToggleFab.kt

**Path**: `core/components/ViewToggleFab.kt`

Floating action button that toggles between grid and list view.

```kotlin
@Composable
fun ViewToggleFab(
    currentView: ViewMode,
    onToggle: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
)
```

- Grid state: grid icon
- List state: list icon
- Animated crossfade on toggle

---

## MediaEmptyState.kt

**Path**: `core/components/MediaEmptyState.kt`

### Layout

```
        🎬/🎵/📁        ← large icon
    No videos found     ← title
  Tap browse to find    ← subtitle
  media on your device.
  
  [ Browse Files ]      ← action button (optional)
```

---

## MediaLoadingState.kt

**Path**: `core/components/MediaLoadingState.kt`

Shimmer/pulse animation replicating the grid or list layout.

### Modes

- `LoadingMode.GRID`: 2×3 grid of `MediaCard`-shaped shimmer boxes
- `LoadingMode.LIST`: 4 rows of `MediaListItem`-shaped shimmer rows

---

## MediaErrorState.kt

**Path**: `core/components/MediaErrorState.kt`

### Layout

```
        ⚠️
  Something went wrong
  Could not load media.
  
  [ Retry ]
```

---

## DurationBadge.kt

**Path**: `core/components/DurationBadge.kt`

Pill-shaped badge showing media duration.

### Layout

```
┌────────┐
│  2:15  │  ← dark background, white text, small rounded pill
└────────┘
```

Located at bottom-right corner of thumbnail.

---

## ThumbnailPlaceholder.kt

**Path**: `core/components/ThumbnailPlaceholder.kt`

Gradient placeholder with media-type icon used while thumbnails load or fail.

### Parameters

```kotlin
@Composable
fun ThumbnailPlaceholder(
    mediaType: MediaType,  // VIDEO, AUDIO, FOLDER, FILE
    modifier: Modifier = Modifier,
)
```

- VIDEO → film icon on gradient background
- AUDIO → music note icon
- FOLDER → folder icon
- FILE → generic file icon

---

## MiniPlayerBar.kt

**Path**: `presentation/player/components/MiniPlayerBar.kt`

Persistent bar at bottom of video/audio browser when media is playing.

### Layout

```
┌──────────────────────────────────────────────┐
│ ▓▓▓▓  Title                    ▶  ⏭️  ░░░░░  │
│ ▓▓▓▓  Artist                   ││             │
│ icon                            progress bar  │
└──────────────────────────────────────────────┘
```

- Thumbnail (40dp) on left
- Title + artist in middle
- Play/pause + next buttons on right
- Thin progress bar at bottom
- Tap → open player
- Ripple feedback on tap

---

## AudioPlayerSheet.kt

**Path**: `presentation/player/components/AudioPlayerSheet.kt`

Full-height bottom sheet for audio playback.

### Layout

```
┌──────────────────────────────────────┐
│            ─── drag handle           │  ← top drag bar
│                                      │
│          ┌──────────────┐            │
│          │              │            │
│          │  Album Art   │            │  ← large cover (280dp)
│          │              │            │
│          └──────────────┘            │
│                                      │
│          Song Title                  │  ← titleLarge, center
│          Artist Name                 │  ← bodyMedium, center
│                                      │
│    ◄◄  ⏪  ▶║⏩  ►►                 │  ← control buttons row
│                                      │
│    1:23 ─────●──────── 4:56          │  ← seekbar
│                                      │
│    🔀 🔁              ⏭️  ⋮          │  ← shuffle, repeat, queue, menu
└──────────────────────────────────────┘
```

---

## PlayerControlsOverlay.kt

**Path**: `presentation/player/components/PlayerControlsOverlay.kt`

HUD overlay on the video player screen.

### Layout

```
┌──────────────────────────────────────────┐
│   ◀ back          ⋮ more                │  ← top bar
│                  CC  ⏬                   │  ← subtitle/audio track buttons
│                                          │
│                                          │
│              ◄◄  ▶║  ►►                │  ← center controls (large icons)
│                                          │
│                                          │
│   1:23 ─────●──────────── 4:56          │  ← seekbar with buffer
│   ░░░░░░░░░░░░░░░░░░░░░░░░░░            │  ← buffered indicator
│                                          │
│   🔀  ⏪  ▶║  ⏩  🔁     1.0x  ⋮       │  ← bottom bar
└──────────────────────────────────────────┘
```

### Visibility

- Visible on tap, auto-hide after 3 seconds
- Animated fade in/out
- System bars match visibility

---

## AlbumCard.kt

**Path**: `presentation/audio/components/AlbumCard.kt`

Album grid card with square cover art.

### Layout

```
┌──────────┐
│ ▓▓▓▓▓▓▓▓ │  ← square cover art
│ ▓▓▓▓▓▓▓▓ │
│ ▓▓▓▓▓▓▓▓ │
│ ▓▓▓▓▓▓▓▓ │
├──────────┤
│ Album    │  ← title, max 2 lines
│ Artist   │  ← subtitle, secondary
│ 12 songs │  ← track count, tertiary
└──────────┘
```

---

## FileListItem.kt

**Path**: `presentation/browse/components/FileListItem.kt`

File/folder row for the file browser.

### Layout

```
┌──────┬──────────────────────────┬─────────────┐
│ 📁   │ Folder Name              │  12 items   │  ← folder
│ 📄   │ video.mp4                │  1.2 GB     │  ← file
│      │                          │  Yesterday  │  ← modified date
└──────┴──────────────────────────┴─────────────┘
```

### States

- **Folder**: folder icon, item count
- **Video file**: video icon, file size, duration
- **Audio file**: audio icon, file size
- **Generic file**: file icon, file size
