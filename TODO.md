# Hz Player — TODO

Tracking known gaps and planned improvements after the VLC → ExoPlayer migration.

## Completed

### [x] Wire subtitle cues to SubtitleOverlay
- **What:** `SubtitleOverlay` was receiving `cues = emptyList()` — now populated from ExoPlayer's `onCues()` listener
- **How:** `MediaPlayerHolder` exposes `subtitleCues: StateFlow<List<Cue>>` → `ExoPlayerEngine` → `PlayerRepositoryImpl` → `PlayerViewModel` → `VideoPlayerScreen` → `SubtitleOverlay`
- **Status:** Switched back to built-in PlayerView subtitle rendering with transparent background. Custom overlay parked for future refinement.

### [x] Seek optimization for SMB / FTP / SFTP
- **SMB:** Replaced `InputStream` + skip-loop with `SmbRandomAccessFile.seek()` — instant seeks, no network waste
- **FTP:** Uses `FTPClient.setRestartOffset()` before opening data stream — server sends from offset directly
- **SFTP:** Custom `InputStream` initialized `pos` to `dataSpec.position` — writes directly at offset via `sftpHandle.read(pos, ...)`
- **Status:** All three DataSources now seek instantly

### [x] SMB subtitle auto-discovery
- **What:** `findNeighborSubtitleFiles()` was using `File(parentDir).listFiles()` which only works for local files — returned nothing for SMB shares
- **Fix:** Added `findSmbNeighborSubtitles()` that uses jcifs-ng `SmbFile` to list remote sibling files and match by base name + extension. FTP/SFTP use extension-swap (try each sub extension at the same path)
- **Status:** SMB subtitles now auto-discovered on the network share

### [x] Auto SurfaceView based on HDR display
- **What:** Previously had a Settings toggle for SurfaceView that users had to manually enable
- **Fix:** `MediaPlayerHolder` checks `display.isHdr` at init and exposes `displayNeedsSurfaceView: StateFlow<Boolean>`. `PlayerViewModel` consumes it and forces SurfaceView when HDR display is detected. Settings toggle removed.
- **Status:** Completely automatic — no user configuration needed

### [x] FTP + SFTP connection pooling
- **What:** Each `open()` created a new FTP control connection or SSH session (~200ms FTP login, ~500ms SSH key exchange per seek)
- **Fix:** Added `ConnectionPool` singleton that caches control connections by `host:port:user`. DataSource `open()` borrows from pool, `close()` returns it. Stale connections detected via `isAvailable`/`isConnected` and auto-replaced.
- **Status:** FTP and SFTP reconnections eliminated — first seek on a new server is fast, subsequent seeks are instant

### [x] SMB context pooling (fix "No more connections" crash)
- **What:** Parallel subtitle probes + video playback opened multiple SMB sessions, hitting Windows SMB connection limit (~20). Crashed with `SmbException: No more connections can be made`.
- **Fix:** Added `ConnectionPool.borrowSmbContext()` — all `SmbRandomAccessFile` and subtitle-listing `SmbFile` instances now share a single `CIFSContext` per server, reusing the same SMB transport session.
- **Status:** SMB no longer creates new sessions per DataSource open.

## High Priority

### [ ] Refine custom SubtitleOverlay (replace built-in)
- **What:** Current built-in PlayerView subtitles work but don't support custom subtitle style UI (font size, colors from `SubtitleStyle`). Re-enable `SubtitleOverlay` with polished rendering.
- **Why:** User-controlled font size, outline, background alpha, positioning not available via PlayerView's `CaptionStyleCompat`.
- **Fix:** Uncomment `SubtitleOverlay` in `VideoPlayerScreen.kt` + re-hide `subtitleView`.
- **Status:** Parked after switching to built-in for reliability.

### [ ] ASS/SSA animated subtitles (karaoke, scroll, fade)
- **What:** ExoPlayer flattens ASS/SSA to static `Cue` objects — no karaoke fills, scrolling credits, or complex positioning
- **Why:** ExoPlayer uses a basic ASS-to-text parser without libass
- **Options:**
  - A) Compile `libass` via NDK + write a Media3 `SubtitleDecoder` — full animation support, significant effort
  - B) Re-add VLC just for subtitle rendering — simpler but adds 20MB APK
  - C) Accept static rendering — works fine for SRT/VTT, most content
- **Status:** Substantial effort, only needed for anime fansubs

### [ ] Background playback
- **What:** ExoPlayer supports background audio via `MediaSessionService`, but it's not wired
- **Why:** Needed for playing audio in the background / lock screen controls
- **Status:** Planned in docs/PROJECT_PLAN.md

## Low Priority

### [ ] Remove unused VLC files
- **What:** `vlc-android-master/` directory at project root (VLC reference source)
- **Why:** No longer needed since VLC was removed
- **Fix:** `rm -rf vlc-android-master/` — saves disk space
- **Status:** Cleanup
