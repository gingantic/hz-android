# Hz Player — Cleanup & Tech Debt Plan

> Tracked tech debt, cleanup tasks, and known shortcuts. Last refreshed: 2026-07-24.

---

## Known Debt

### Architecture

| Item | Severity | Notes |
|---|---|---|
| No `domain/usecase` layer | Low | ViewModels call repositories directly. Accepted; add only when a use case is reused across 3+ ViewModels. |
| `MainActivity` monolith | Low | Single activity hosts all navigation. Extraction to `HzPlayerApp` composable is a heavy cross-file refactor. |
| `ConnectionPool` shared across protocols | Low | Single pool for SMB/FTP/SFTP/WebDAV. Works but harder to tune per-protocol. Split when a protocol needs specific lifecycle. |

### Code Quality

| Item | Severity | Notes |
|---|---|---|
| Minimal test coverage | Medium | Only mapper/cache/path unit tests + Robolectric. No integration or UI tests. |
| `vlc-android-master/` directory | Low | ~50MB of reference code in repo. Low priority to remove. |

### Deferred Features

| Item | Priority | Notes |
|---|---|---|
| In-archive sibling subtitle auto-detection | Medium | Design complete (`docs/ARCHIVE_SUPPORT.md` §8). Needs wiring in `FileBrowserViewModel`. |
| Multi-part / split archives | Low | v2 per `docs/ARCHIVE_SUPPORT.md` §12. |
| In-archive thumbnails | Low | Feed entry bytes through `RandomAccessBridge` → `NativeThumbnailExtractor`. |
| Library/Search indexing of archive contents | Low | v2 feature. |
| Second playback engine | Low | Architecture supports it via `IPlayerEngine`. No implementation started. |

---

## Completed Cleanup (reference)

| Item | Date | Notes |
|---|---|---|
| 27-item bug/reliability/i18n pass | 2026-07 | Player error redaction, SMB path traversal, JNI guards, connection-pool lifecycle, manifest fixes |
| Render seam on `IPlayerEngine` | 2026-07 | `createRenderView`/`updateRenderView`/`onRenderViewPaused`/`onRenderViewResumed` on interface |
| `MediaSessionProvider` removal | 2026-07 | Replaced by `IPlayerEngine.getMedia3Player()` |
| `subtitleCues`/`videoDecoderName` boundary leak removal | 2026-07 | Clean domain boundary |
