# Hz Player — UI Component Catalog

> Every reusable composable in the design system, with spec and states.
> Last refreshed: 2026-07-24. Lists the components that actually exist today;
> layouts are representative, not pixel-exact.

---

## Core components (`core/components/`)

| Component | Path | Purpose |
|---|---|---|
| `MediaCard` | `core/components/MediaCard.kt` | Grid card: thumbnail, gradient overlay, duration badge, progress, title/subtitle, 3-dot menu |
| `MediaListItem` | `core/components/MediaListItem.kt` | Horizontal list row for video/audio/file |
| `ViewToggleFab` | `core/components/ViewToggleFab.kt` | FAB toggling grid/list, animated crossfade |
| `MediaEmptyState` | `core/components/MediaEmptyState.kt` | Illustrated empty state + optional action |
| `MediaLoadingState` | `core/components/MediaLoadingState.kt` | Shimmer for grid/list |
| `MediaErrorState` | `core/components/MediaErrorState.kt` | Error state + retry |
| `PermissionRequiredState` | `core/components/PermissionRequiredState.kt` | Storage permission request state |
| `DurationBadge` | `core/components/DurationBadge.kt` | Pill duration badge (thumbnail corner) |
| `ThumbnailPlaceholder` | `core/components/ThumbnailPlaceholder.kt` | Theme-aware gradient placeholder by `MediaType` |
| `MediaPropertiesDialog` | `core/components/MediaPropertiesDialog.kt` | File/codec properties dialog (FFmpeg probe) |
| `FileItemCard` | `core/components/FileItemCard.kt` | File/folder card for browse (3-dot menu) |
| `BreadcrumbBar` | `core/components/BreadcrumbBar.kt` | Directory breadcrumb navigation |
| `DirectoryBrowsePane` | `core/components/DirectoryBrowsePane.kt` | Shared browse listing (grid/list) |
| `HzPlayerTopBar` | `core/components/HzPlayerTopBar.kt` | App top bar |
| `HzPlayerSearchableScaffold` | `core/components/HzPlayerSearchableScaffold.kt` | Scaffold + search integration |
| `SearchDelegate` | `core/components/SearchDelegate.kt` | Debounced search helper |

All core components take `@Preview` using `PreviewMedia` data (never a ViewModel).

---

## Player components (`presentation/player/components/`)

| Component | Path | Purpose |
|---|---|---|
| `PlayerControlsOverlay` | `PlayerControlsOverlay.kt` | HUD: top bar, seekbar+buffer, center controls, bottom bar (shuffle/repeat/speed) |
| `PlayerSeekBar` | `PlayerSeekBar.kt` | Styled seekbar with buffered indicator |
| `PlayerRenderView` | `PlayerRenderView.kt` | Engine render-view helper used by `PlayerSurface` |
| `PlayerGestures` | `PlayerGestures.kt` | Extracted gesture handler (tap/double-tap/swipe/pinch) |
| `GestureCueIndicators` | `GestureCueIndicators.kt` | Visual feedback for gesture actions |
| `MiniPlayerBar` | `MiniPlayerBar.kt` | Persistent bottom bar when media plays; tap → player |
| `FloatingVideoPlayer` | `FloatingVideoPlayer.kt` | Draggable PiP-style floating video overlay |
| `AudioPlayerSheet` | `AudioPlayerSheet.kt` | Full-height audio bottom sheet (cover, controls, seek) |
| `AudioQueueSheet` | `AudioQueueSheet.kt` | Audio "now playing" queue list |
| `AudioSelectionDialog` | `AudioSelectionDialog.kt` | Audio-track picker |
| `PlaylistDrawer` | `PlaylistDrawer.kt` | Video queue/playlist drawer |
| `SpeedSelectionDialog` | `SpeedSelectionDialog.kt` | Playback speed picker |
| `SubtitleSelectionDialog` | `SubtitleSelectionDialog.kt` | Subtitle-track picker (with flag icons) |
| `SubtitleSearchDialog` | `SubtitleSearchDialog.kt` | SubDL search |
| `SubtitleBrowserContent` | `SubtitleBrowserContent.kt` | Subtitle browser pane content |
| `SubtitleFileBrowserBottomSheet` | `SubtitleFileBrowserBottomSheet.kt` | Pick local `.srt/.vtt/.ass` |
| `AssSubtitleOverlay` | `AssSubtitleOverlay.kt` | Compose wrapper for the libass `SubtitleOverlayView` |
| `TrackSelectionRow` | `TrackSelectionRow.kt` | Reusable horizontal track-selection row |
| `FlagIcon` | `FlagIcon.kt` | Country flag icon for subtitle language display |
| `SheetScaffold` | `SheetScaffold.kt` | Reusable bottom-sheet scaffold |
| `PlaybackErrorOverlay` | `PlaybackErrorOverlay.kt` | Error by `PlaybackErrorKind` + Retry |
| `DebugOverlay` | `DebugOverlay.kt` | "Stats for nerds" from `getDebugStats()` |
| `DragSeekIndicator` | `DragSeekIndicator.kt` | Drag-to-seek indicator |
| `SeekIndicator` / `SeekIndicators` | `SeekIndicator(s).kt` | Seek-forward/back indicators |
| `SlideIndicator` | `SlideIndicator.kt` | Brightness/volume slide indicator |
| `UnlockPill` | `UnlockPill.kt` | Swipe-to-unlock lock pill |

---

## Audio / settings / network / browse components

| Component | Path |
|---|---|
| `AlbumCard` | `presentation/audio/components/AlbumCard.kt` |
| `AudioDetailHeader` | `presentation/audio/components/AudioDetailHeader.kt` |
| `ArchivePasswordDialog` | `presentation/browse/components/ArchivePasswordDialog.kt` |
| `NetworkScreenContent` | `presentation/network/components/NetworkScreenContent.kt` |
| `ServerCard` | `presentation/network/components/ServerCard.kt` |
| `ServerConfigDialog` | `presentation/network/components/ServerConfigDialog.kt` |
| `StreamHistoryListItem` | `presentation/network/components/StreamHistoryListItem.kt` |
| `SettingsSection` / `SettingsItem` | `presentation/settings/components/` |
| `AboutDialog` | `presentation/settings/components/AboutDialog.kt` |
| `UpdateDialog` | `presentation/settings/components/UpdateDialog.kt` |
| `EnumSelectionDialog` | `presentation/settings/components/EnumSelectionDialog.kt` |
| `ColorPickerDialog` | `presentation/settings/components/ColorPickerDialog.kt` |
| `SubdlApiKeyDialog` | `presentation/settings/components/SubdlApiKeyDialog.kt` |
| `SettingsDialogs` | `presentation/settings/components/SettingsDialogs.kt` |

---

## Design system (`core/designsystem/`)
- `HzPlayerIcons.kt` — media-player icon constants (shuffle, repeat, prev/next, play/pause, folder, video, audio, search, settings, more).
- `Dimens.kt` — 8dp spacing system + M3 shape constants.
- `NavBarInsets.kt` — navigation-bar inset helpers.

Theme (M3 dynamic, dark/light) lives in `presentation/theme/` (`Color.kt`, `Type.kt`,
`Theme.kt`), not `ui/theme/` (deleted).

---

## Sample layouts

### MediaCard
```
┌──────────────────────┐
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │  ← thumbnail (16:9 video / 1:1 album)
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 2:15 │  ← gradient overlay + duration badge
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
├──────────────────────┤
│ Title                │
│ Subtitle             │
│ ████░░░░░░░░░ 30%    │  ← progress (optional)
└──────────────────────┘
```

### AudioPlayerSheet
```
┌──────────────────────────────┐
│            ─── drag handle   │
│          ┌──────────────┐    │
│          │  Album Art   │    │  ← 280dp cover
│          └──────────────┘    │
│          Song Title          │
│          Artist Name         │
│    ◄◄  ⏪  ▶║⏩  ►►         │
│    1:23 ─────●──────── 4:56  │
│    🔀 🔁              ⏭️ ⋮   │
└──────────────────────────────┘
```

### PlayerControlsOverlay
```
┌──────────────────────────────────┐
│   ◀ back          ⋮ more          │
│                  CC  ⏬            │
│                                    │
│              ◄◄  ▶║  ►►           │
│                                    │
│   1:23 ─────●──────────── 4:56     │
│   🔀  ⏪  ▶║  ⏩  🔁     1.0x  ⋮   │
└──────────────────────────────────┘
```
