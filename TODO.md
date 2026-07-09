# Hz Player — TODO

Tracking known gaps and planned improvements after the VLC → ExoPlayer migration.

## Completed

### [x] Wire subtitle cues to SubtitleOverlay

- **What:** `SubtitleOverlay` was receiving `cues = emptyList()` — now populated from ExoPlayer's `onCues()` listener
- **How:** `MediaPlayerHolder` exposes `subtitleCues: StateFlow<List<Cue>>` → `ExoPlayerEngine` → `PlayerRepositoryImpl` → `PlayerViewModel` → `VideoPlayerScreen` → `SubtitleOverlay`
- **Status:** Switched back to built-in PlayerView subtitle rendering with transparent background. Custom overlay parked for future refinement.

### [x] Surface selection & window color mode for HDR/SDR

- **What:** Toggle HDR passthrough surfaces (`SurfaceView`/`TextureView`) and `Window.colorMode` (`COLOR_MODE_HDR`, `COLOR_MODE_WIDE_COLOR_GAMUT`, `COLOR_MODE_DEFAULT`) without trying to rewrite color in software.
- **How:**
  - `Window.colorMode` set to `COLOR_MODE_HDR` on API 34+, `COLOR_MODE_WIDE_COLOR_GAMUT` as fallback, `COLOR_MODE_DEFAULT` when user disables HDR.
  - `setSecure(true)` only applied for DRM-backed media (Widevine L1). Local HDR keeps regular un-secure path so screenshots/recording stay enabled.
  - State: `PlayerUiState.hdrEnabled` mirrors the pref; `useTextureView` derived from it (and DRM); `drmSessionActive` derived from `MediaItem.localConfiguration.drmConfiguration`.
- **Status:** App-level HDR passthrough and SDR fallback work without per-frame color manipulation. (An earlier attempt at an in-app `RgbMatrix` HDR→SDR effect was removed because it double-applied conversion that Media3 already does, and any meaningful tone-quality fix must be a custom OpenGL fragment shader.)

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

## High Priority

### [ ] HDR→SDR colour correction (In progress)

- **What:** Plan a clean media3 `GlEffect` that does HDR→SDR conversion without the issues
  of the abandoned `RgbMatrix` route (double gamut conversion, crushed shadows, red tint).
- **Status:** Toggle persists preference but currently has no effect on the video pipeline.
  See `PlayerViewModel.observeHdrSettings` and `SettingsScreen` for the current no-op
  wiring. Future work should add a custom `BaseGlShaderProgram` derived from Media3's
  builtin `fragment_shader_transformation_hdr_internal_es3.glsl` so EOTF/EOCF/OOTF are
  applied once instead of twice.

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

claude --resume 6b8a6699-d204-43b2-a684-f3608fa57e6e
