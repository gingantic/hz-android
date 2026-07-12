# Hz Player — Implementation Roadmap

> Phased delivery from foundation to a working multi-source media player.
> Last refreshed: 2026-07-11. Original Phases 1–10 are complete; three net-new
> work streams shipped after the roadmap was written (network streaming, native
> thumbnails, modular playback engine).

---

## Phase 1: Foundation ✅

Gradle catalog, Hilt, Room, DataStore, Navigation Compose — all wired. See `gradle/libs.versions.toml`.

## Phase 2: Architecture ✅

Domain models, repository interfaces, DI modules, `PreviewMedia` placeholder data.
`domain/usecase` is currently empty (ViewModels call repositories directly) (ViewModels call repos
directly otherwise — documented as accepted debt in `CLEANUP_PLAN.md`).

## Phase 3: Design System ✅

`core/designsystem` (icons, dimens, nav-bar insets) + reusable `core/components`.
M3 dynamic theme in `presentation/theme` (purple generation replaced).

## Phase 4: Video Library ✅

`VideoLibraryScreen` with category sections, grid/list toggle, sort chips, search.

## Phase 5: Audio Browser ✅

`AudioBrowserScreen` (tabs: songs/albums/artists/genres) + `AlbumDetailScreen` /
`ArtistDetailScreen` detail routes.

## Phase 6: File Browser ✅

`FileBrowserScreen` with breadcrumb bar, directory LRU cache, grid/list toggle.

## Phase 7: Player ✅

`VideoPlayerScreen` + `AudioPlayerScreen` + `AudioPlayerSheet` + `MiniPlayerBar`.
Gestures: tap = toggle controls, double-tap = seek ±10s, brightness/volume swipe,
swipe up/down = dismiss. Subtitle + audio track selection, speed, shuffle/repeat,
zoom/pip, lock pill. Rendering via `PlayerSurface` seam (no Media3 in the screen).

## Phase 8: Settings ✅

`SettingsScreen` with sections; `ThemeSelectionDialog`, `ColorPickerDialog`,
`OpenSubtitlesApiKeyDialog`, engine selector. Fully localized (i18n done).

## Phase 9: Search ✅

`SearchScreen` with debounced query across video/audio/files.

## Phase 10: Integration ✅

Real `MediaRepositoryImpl` (MediaStore → Room), `MediaPlaybackService`
(Media3 `MediaSessionService` + notification), PiP, resumable playback positions.

---

## Net-new work shipped after the original roadmap

| Stream | What | Where |
|---|---|---|
| **Network streaming** | SMB / FTP / SFTP / WebDAV browse + playback, LAN server discovery (`ServerDiscoverer`, NSD/mDNS), server config UI with encrypted credentials, stream history | `data/datasource/network/*`, `data/datasource/player/*DataSource.kt`, `presentation/network/*` |
| **Native thumbnails** | FFmpeg frame extraction over any URI (incl. remote) via JNI + Coil `Fetcher`, disk cache + `.fail` tombstone | `core/thumbnail/*`, `cpp/ThumbnailExtractor.cpp` |
| **Modular engine** | `IPlayerEngine` contract, `Map<EngineType, IPlayerEngine>` DI, `PlayerSurface` render seam, error-kind mapping | `domain/player/*`, `data/datasource/player/ExoPlayerEngine.kt`, `docs/ENGINE_MODULARITY.md` |
| **Reliability / cleanup** | 27-item bug+reliability+i18n pass (player error redaction, SMB path traversal, JNI guards, connection-pool lifecycle, manifest fixes) | `docs/CLEANUP_PLAN.md` |

---

## Summary Timeline (actual)

| Phase | Status |
|---|---|
| 1–3 Foundation / Architecture / Design System | ✅ |
| 4 Video Library | ✅ |
| 5 Audio Browser (+ detail screens) | ✅ |
| 6 File Browser | ✅ |
| 7 Player (video + audio + gestures) | ✅ |
| 8 Settings | ✅ |
| 9 Search | ✅ |
| 10 Integration (MediaStore/Room, service, PiP) | ✅ |
| Network streaming stack | ✅ |
| Native thumbnail pipeline | ✅ |
| Modular playback engine | ✅ |
| Cleanup / reliability / i18n pass | ✅ |

**Overall: feature-complete foundation + network/thumbnail/engine work streams landed.**
Remaining items are polish/deferred cross-file refactors (see `CLEANUP_PLAN.md` "Known debt").
