# Video playlist from file browser + right-side drawer

## Context

User wants two things:
1. A **FAB on the file browser** (BROWSING mode) that collects all video files in the current (flat) directory, loads them as a playlist, and plays them in the video player with next/prev navigation.
2. A **playlist drawer** on the right side of the video player showing the queue: compact rows with title, file name, duration. Opens via a button next to the aspect ratio button in the top HUD.

## Design

### PlayerViewModel — add playlist/queue

New state in `PlayerUiState`:
```kotlin
val videoPlaylist: List<VideoItem> = emptyList()
val currentPlaylistIndex: Int = 0
```

New methods in `PlayerViewModel`:
- `fun playVideoPlaylist(items: List<VideoItem>, startIndex: Int = 0)` — sets playlist, starts at index
- `fun onPlaylistNext()` / `onPlaylistPrevious()` — navigate within playlist
- `fun onPlaylistSelect(index: Int)` — jump to index
- `fun onPlaylistToggle()` — show/hide drawer (boolean flag in uiState)

### ExoPlayerEngine — multi-item playlist

Change `play()` to use `player.setMediaItems(items, startIndex, positionMs)` when a playlist is provided. Single-item playback stays as-is (wraps as list of 1).

### File browser — Play All FAB

- Add `onPlayAllVideos: (List<VideoItem>) -> Unit` callback on `FileBrowserScreen`
- `FileBrowserViewModel` gets new method `fun collectVideoPlaylist(): List<VideoItem>` — filters current directory `layer.items` by video extension, maps to `VideoItem`
- FAB with `Icons.Filled.PlayArrow` overlaid on `Icons.Filled.PlaylistPlay` appears in BOTTOM-RIGHT of `DirectoryStackContent` when any video files exist in the current layer
- `MainActivity` receives this callback and passes to `playerViewModel.playVideoPlaylist()`, then navigates to video player

### Playlist drawer on video player

- Add playlist toggle button on top HUD right side (next to aspect ratio button)
- `AnimatedVisibility` with `slideInHorizontally`/`slideOutHorizontally` coming from the right
- Drawer: `Column` with semi-transparent background overlay, width ~300dp
- Each row: **title** (single line), **file name** (smaller, gray), **duration** (right-aligned)
- Current playing item highlighted with accent color
- Tap to jump to that video

## Files to change

| File | Change |
|------|--------|
| `PlayerUiState.kt` | Add `videoPlaylist`, `currentPlaylistIndex`, `showPlaylistDrawer` |
| `PlayerViewModel.kt` | Add playlist methods, next/prev, toggle drawer |
| `ExoPlayerEngine.kt` | Use `setMediaItems()` when playlist > 1 |
| `PlayerControlsOverlay.kt` | Add playlist toggle button in top HUD |
| `VideoPlayerScreen.kt` | Add playlist drawer composable, wire animation |
| `FileBrowserScreen.kt` | Add Play All FAB in BROWSING mode |
| `FileBrowserViewModel.kt` | Add `collectVideoPlaylist()` helper |
| `FileBrowserUiState.kt` | (no change needed — data is already in layers) |
| `MainActivity.kt` | Wire play-all callback to ViewModel + nav |

### Row design in playlist drawer

```
┌──────────────────────────────────────────────┐
│ ▶ Big Buck Bunny                   12:34     │  ← accent bg row
│   /storage/emulated/0/Download/bb.mp4        │
├──────────────────────────────────────────────┤
│   Sintel                           09:41     │
│   /storage/emulated/0/Videos/sintel.mkv     │
├──────────────────────────────────────────────┤
│   Tears of Steel                   06:27     │
│   .../TearsOfSteel.mp4                       │
└──────────────────────────────────────────────┘
```

Playlist drawer slides over the video, not pushing it. Has a `×` close button at top or closes when tapping outside.

## Verification

1. Build: `./gradlew.bat assembleDebug` — must pass
2. Browse to a folder with video files → see FAB
3. Tap FAB → video player opens, first video plays
4. Tap playlist button (top right HUD) → drawer slides in from right
5. Show current video highlighted, tap another → switches
6. Next/prev skip buttons work through playlist
7. Last video → next wraps or stops
