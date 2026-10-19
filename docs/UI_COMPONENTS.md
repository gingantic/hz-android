# Hz Player — UI Component Catalog

> Every reusable composable in the design system, with spec and states.
> Verified against code at `efeba7b` — drift check: `git diff efeba7b..HEAD -- app/src`.
> Layouts are representative, not pixel-exact.

**House rules.** New sliders use `HzPlayerSlider` (never a raw `Slider`) so track
thickness, knob ring, and tick suppression stay uniform. Media-type colour comes from
`mediaAccentColor`; a white glyph on top of it goes through `MediaIconBadge`, which
darkens the accent until the glyph keeps contrast. Every reusable component takes
`@Preview` using `PreviewMedia` data — never a ViewModel.

---

## Core components (`core/components/`)

| Component | Path | Purpose |
|---|---|---|
| `MediaCard` | `MediaCard.kt` | Grid card: thumbnail, gradient overlay, duration badge, progress, title/subtitle, 3-dot menu |
| `MediaListItem` | `MediaListItem.kt` | Horizontal list row for video/audio/file |
| `AlbumArtImage` | `AlbumArtImage.kt` | Album art with a graceful fallback — renders the URI cropped to fill, and the audio `ThumbnailPlaceholder` both when the URI is absent and when the load fails. Takes the `modifier` so the placeholder occupies the same box |
| `HzPlayerSlider` | `HzPlayerSlider.kt` | House-style slider: chunky gapless track, solid round knob ringed in the colour behind it; ticks + stop indicator suppressed. Accent/size are parameters, so it scales from a settings row to the equalizer's vertical faders |
| `HzPlayerProgressBar` | `HzPlayerProgressBar.kt` | Determinate/indeterminate progress bar. `progress = null` → sweeping `IndeterminateBar`; otherwise an animated rounded fill coerced to at least the track height (so 0 % is still a visible dot) |
| `MediaIconBadge` | `MediaIconBadge.kt` | Rounded gradient badge identifying media by type; accent darkened until a white glyph keeps contrast (holds up in light/dark/dynamic colour) |
| `ViewToggleFab` | `ViewToggleFab.kt` | FAB toggling grid/list, animated crossfade |
| `MediaEmptyState` | `MediaEmptyState.kt` | Illustrated empty state + optional action |
| `MediaLoadingState` | `MediaLoadingState.kt` | Shimmer for grid/list |
| `MediaErrorState` | `MediaErrorState.kt` | Error state + retry |
| `PermissionRequiredState` | `PermissionRequiredState.kt` | Storage permission request state |
| `DurationBadge` | `DurationBadge.kt` | Pill duration badge (thumbnail corner) |
| `ThumbnailPlaceholder` | `ThumbnailPlaceholder.kt` | Theme-aware gradient placeholder by `MediaType` |
| `MediaPropertiesDialog` | `MediaPropertiesDialog.kt` | File/codec properties sheet (FFmpeg probe) with cover/thumbnail banner and `MediaIconBadge`-headed section cards |
| `FileItemCard` | `FileItemCard.kt` | File/folder card for browse (3-dot menu) |
| `FileOptionsBottomSheet` | `FileOptionsBottomSheet.kt` | File/folder context actions (thumbnail, favourite, play all, play as audio, properties) |
| `ViewSortBottomSheet` | `ViewSortBottomSheet.kt` | Sort / view-mode / media-type-filter picker |
| `BreadcrumbBar` | `BreadcrumbBar.kt` | Directory breadcrumb navigation |
| `DirectoryBrowsePane` | `DirectoryBrowsePane.kt` | Shared browse listing (grid/list) |
| `DirectoryHeader` | `DirectoryHeader.kt` | Breadcrumb trail plus the "N folders, M files" summary above a directory listing (shared by the local and remote browsers). The summary hides itself when the visible list is empty |
| `HzPlayerTopBar` | `HzPlayerTopBar.kt` | App top bar; inline search mode takes precedence over the back arrow (the leading arrow exits search first) |
| `HzPlayerSearchableScaffold` | `HzPlayerSearchableScaffold.kt` | Scaffold + search integration |
| `SearchDelegate` | `SearchDelegate.kt` | Debounced search helper |

---

## Player components (`presentation/player/components/`)

| Component | Purpose |
|---|---|
| `PlayerControlsOverlay` | HUD: top bar, seekbar + buffer, centre controls, bottom bar (shuffle/repeat/speed) |
| `PlayerSeekBar` | Styled seekbar with buffered indicator (the only thing that recomposes per position tick) |
| `pauseRenderView` / `resumeRenderView` | `PlayerRenderView.kt` — engine render-view helpers used by `PlayerSurface` |
| `Modifier.playerGestures` | `PlayerGestures.kt` — gesture modifier (tap/double-tap/swipe/pinch/hold-to-speed) with `PlayerGestureState` / `PlayerGestureCallbacks` |
| `GestureCueIndicators` | Visual feedback for gesture actions |
| `MiniPlayerBar` | Persistent bottom bar while media plays; tap → player. Shows an **"open as video"** button when the session is a video playing in the background |
| `FloatingVideoPlayer` | Draggable PiP-style floating video overlay; doubles as the system-PiP surface (full-bleed black, no HUD) |
| `AudioPlayerSheet` | Full-height audio bottom sheet (cover, controls, seek) |
| `AudioQueueSheet` | Audio now-playing queue list |
| `AudioSelectionDialog` | Audio-track picker |
| `EqualizerSheet` | 10-band equalizer (vertical `HzPlayerSlider` bank centred in the sheet), device presets, bass boost, loudness |
| `PlaylistDrawer` | Video queue/playlist drawer |
| `PlayerMoreOptionsSheet` | Sleep timer, jump-to-time, chapters, A-B repeat, play-as-audio |
| `SleepTimerDialog` | Sleep-timer presets (15/30/45/60/90/120 min + end-of-video + off) — in `PlayerMoreOptionsSheet.kt` |
| `JumpToTimeDialog` | Numpad jump-to-position (HH:MM:SS), clamped to the media duration — in `PlayerMoreOptionsSheet.kt` |
| `ChapterSelectionDialog` | Chapter list with current-position highlight — in `PlayerMoreOptionsSheet.kt` |
| `SpeedSelectionDialog` | Playback speed picker |
| `SubtitleSelectionDialog` | Subtitle-track picker (flag icons) |
| `SubtitleSearchDialog` | SubDL search |
| `RootsContent` / `LocalBrowseContent` / `RemoteBrowseContent` | `SubtitleBrowserContent.kt` — subtitle browser panes |
| `SubtitleFileBrowserBottomSheet` | Pick a local `.srt/.vtt/.ass` |
| `AssSubtitleOverlay` | Compose wrapper for the libass `SubtitleOverlayView` |
| `TrackSelectionRow` | Reusable horizontal track-selection row |
| `FlagIcon` | Country flag icon for subtitle-language display |
| `SheetScaffold` | Reusable bottom-sheet scaffold |
| `PlaybackErrorOverlay` | Error by `PlaybackErrorKind` + Retry |
| `DebugOverlay` | "Stats for nerds" from `getDebugStats()` |
| `DragSeekIndicator` | Drag-to-seek indicator |
| `SeekIndicator` / `SeekIndicators` | Seek-forward/back indicators |
| `SlideIndicator` | Brightness/volume slide indicator |
| `UnlockPill` | Swipe-to-unlock lock pill |

---

## Audio / settings / network / browse components

| Component | Path |
|---|---|
| `AlbumCard` | `presentation/audio/components/AlbumCard.kt` |
| `AudioDetailHeader` | `presentation/audio/components/AudioDetailHeader.kt` |
| `ArchivePasswordDialog` | `presentation/browse/components/ArchivePasswordDialog.kt` |
| `SolidArchiveWarningDialog` | `presentation/browse/components/SolidArchiveWarningDialog.kt` |
| `DirectoryStackContent` | `presentation/browse/components/DirectoryStackContent.kt` |
| `StorageRootsContent` | `presentation/browse/components/StorageRootsContent.kt` — storage roots with a per-root icon derived from `StorageKind` (USB / SD card / generic storage) |
| `FileBrowserTopBarActions` | `presentation/browse/components/FileBrowserTopBarActions.kt` |
| `NewFolderDialog` | `presentation/browse/components/NewFolderDialog.kt` |
| `PasteActionBar` | `presentation/browse/components/PasteActionBar.kt` (cut/copy/move/delete). While a paste is running it swaps the buttons for a percentage label + `HzPlayerProgressBar` (indeterminate when the fraction is null, e.g. an atomic move) |
| `NetworkHomeContent` / `ServerBrowseStackContent` / `CredentialDialog` | `presentation/network/components/NetworkScreenContent.kt` — saved and discovered servers are laid out as **two columns** (`chunked(2)` of `ServerCard`s) inside the scrolling `LazyColumn`; the list-view variant renders them full width instead |
| `ServerCard` | `presentation/network/components/ServerCard.kt` |
| `ServerConfigDialog` | `presentation/network/components/ServerConfigDialog.kt` |
| `StreamHistoryListItem` | `presentation/network/components/StreamHistoryListItem.kt` |
| `SettingsSection` / `SettingsItem` / `SettingsSliderItem` | `presentation/settings/components/` |
| `AboutDialog` / `UpdateDialog` / `ColorPickerDialog` / `SubdlApiKeyDialog` | `presentation/settings/components/` |
| `EnumSelectionDialog` | `presentation/settings/components/EnumSelectionDialog.kt` (generic enum picker) |
| `EnumLabels` | `presentation/settings/components/EnumLabels.kt` — display labels for the shared enums |
| `ThemeSelectionDialog` / `OrientationDialog` / `DecoderModeDialog` / `ResumeModeDialog` | `presentation/settings/components/SettingsDialogs.kt` — thin `EnumSelectionDialog` wrappers |

---

## History screen

`presentation/history/` — the recently-played list.

| Component | Purpose |
|---|---|
| `HistoryScreen` | Full-screen overlay route (`NavRoutes.HISTORY`); top bar with a back arrow and a "clear all" action, a `MediaEmptyState` when the list is empty, and a borderless `LazyColumn` of rows |
| `HistoryRow` | A row: a video frame (`VideoFrame`) or album art, the title, and the artist — or "Video"/"Audio" when there is no artist. No per-item delete; only clear-all |
| `HistoryViewModel` / `HistoryUiState` | Collects `PlayHistoryRepository.observeHistory()` into `StateFlow<HistoryUiState>` |

---

## Browser components (`browser/ui/`)

The in-app browser owns its UI package (hosted by `BrowserActivity`, not the main nav graph):

| Component | Purpose |
|---|---|
| `BrowserScreen` | Full browser scaffold; hides chrome during system PiP; hosts the JS dialog and the popup-permission sheet |
| `BrowserTopBar` / `BrowserBottomBar` | Omnibox top bar (with `UrlSuggestionsPanel`) + navigation toolbar. The bottom bar carries a media-count badge and the "Block element" overflow action |
| `TabSidebar` | Tab switcher — a left-side vertical panel that snaps in over the content |
| `NewTabPage` | Start page |
| `MediaGrabberBottomSheet` | Detected media list (ALL / VIDEO / AUDIO / STREAMS filter chips) + quality picker, variant tree, token inspector, download/stream/share/cURL actions |
| `PopupPermissionBottomSheet` | Cross-domain popup approval (source page + target domain, Allow / Deny) |
| `ElementPickerPanel` | Ad-block cosmetic-rule authoring — shows the generated selector, the match count, a "Wider" action and an "Apply to all sites" toggle. Non-modal so the page stays tappable |
| `JsDialog` | WebView `alert` / `confirm` / `prompt` / `beforeunload` dialog. Dismissing counts as cancel so the page's JS thread is never left blocked |
| `BrowserHistoryScreen` / `BrowserSettingsScreen` | History and browser settings (JavaScript, ad blocker, privacy, user agent, content, cache, session) |

---

## Design system (`core/designsystem/`)

- `HzPlayerIcons.kt` — media-player icon constants (shuffle, repeat, prev/next, play/pause, folder, video, audio, search, settings, more, star/favourite, network, browser).
- `Dimens.kt` — exports `Spacing` (4/8/12/16/24/32/48 dp), `CornerRadii`, `CardSizes`, `BrowserDimens`, `HzPlayerShapes` (M3 shapes).
- `MediaAccent.kt` — `mediaAccentColor(MediaType)`: video → primary, audio → tertiary, folder → secondary, file → neutral. Use for fills/tints; darken before putting a white glyph on it.
- `NavBarInsets.kt` — navigation-bar inset helpers.

Theme (M3 dynamic, dark/light) lives in `presentation/theme/` (`Color.kt`, `Type.kt`,
`Theme.kt`), not `ui/theme/` (deleted).

---

## Sample layouts

### MediaCard
```
┌──────────────────────┐
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │  ← thumbnail (16:9 video / 1:1 album)
│ ▓▓▓▓▓▓▓▓▓▓▓ 2:15 │  ← gradient overlay + duration badge
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

### ElementPickerPanel
```
┌──────────────────────────────────┐
│  Tap the element you want to hide │
│                                  │
│  div#ad-slot            [ Wider ]│  ← selector (monospace) + widen action
│  3 matches on this page          │
│  [x] Apply to all sites          │
│                [ Cancel ] [Block]│  ← Block is error-coloured
└──────────────────────────────────┘
```
