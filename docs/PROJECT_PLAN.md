# Hz Player — Project Plan

> **Project**: Hz Player — VLC-inspired Android media player
> **Root**: `C:\Users\reihan\Desktop\Rhvn-player\hz-android`
> **Package**: `com.rhnxdev.hzplayer`
> **Last refreshed**: 2026-07-21. All original phases complete; six net-new
> work streams (network streaming, native thumbnails, modular engine, libass
> subtitles, archive support, OTA updates) since then.

---

## 1. Current State

Hz Player is past foundation: all 10 original roadmap phases are implemented, and
six additional subsystems have been added on top:

- **Multi-source playback** — local MediaStore media, device filesystem, remote
  SMB / FTP / SFTP / WebDAV servers, and compressed archives (zip/7z/rar/tar/iso),
  all played through one modular playback engine.
- **Native thumbnail extraction** — FFmpeg frame decode for video over any URI
  (local or remote), bridged via JNI into a Coil `Fetcher`.
- **Engine modularity** — `IPlayerEngine` contract so a second backend (libVLC/mpv)
  can be added with one class + one Hilt binding.
- **Native libass subtitles** — pixel-perfect ASS/SSA rendering via libass JNI;
  SRT/VTT converted on-the-fly; embedded + external track support.
- **Archive support** — libarchive-based virtual folder navigation + play-in-place
  streaming from inside archives (no extraction to disk).
- **OTA updates** — Cloudflare R2 update checker with startup reminder, About dialog,
  and open-source licenses screen (AboutLibraries).

### What exists
- ✅ Compose + M3, 5-tab `NavigationSuiteScaffold`, M3 dynamic theme (`presentation/theme`)
- ✅ Hilt DI (`AppModule` / `RepositoryModule` / `DatabaseModule` / `PlayerEngineModule`)
- ✅ Room (4 DAOs) + DataStore preferences + encrypted server credentials
- ✅ Media3 ExoPlayer via singleton `MediaPlayerHolder` + `MediaPlaybackService`
- ✅ Coil 3 image loading + native thumbnail fetcher + codec metadata probe
- ✅ Full screens: video library, audio browser (+ album/artist detail), file browser,
  network, player (video + audio), search, settings, licenses
- ✅ Network stack: SMB/FTP/SFTP/WebDAV clients, connection pool, LAN discovery
- ✅ Native libass subtitle pipeline (ASS/SSA/SRT/VTT rendering)
- ✅ Archive support: libarchive JNI + virtual navigation + `archive://` DataSource
- ✅ Audio queue + floating video player (PiP-style)
- ✅ OTA update checker + startup reminder + About/Licenses
- ✅ Full i18n (strings.xml; `DEBUG`-gated preview data only)
- ✅ Native FFmpeg thumbnail pipeline (`cpp/ThumbnailExtractor.cpp`)
- ✅ Web landing page (Svelte + Vite in `web/`)

### What is missing / deferred
- ⏸ `MainActivity` extraction to `presentation/navigation/HzPlayerApp` (heavy cross-file)
- ⏸ `ConnectionPool` per-protocol split (heavy cross-file)
- ⏸ `domain/usecase` layer (no use case layer; VMs call repositories directly)
- ⏸ In-archive sibling subtitle auto-detection
- ⏸ Multi-part / split archive support
- ❌ Second playback engine (libVLC/mpv) — architecture supports it, none implemented
- ❌ Android Auto, equalizer
- ❌ Tests beyond mapper/cache/path unit tests + Robolectric

---

## 2. VLC Android Reference

The full VLC Android source lives at `vlc-android-master/` for UX reference. Adapt,
do not copy. Mapping table in the original plan remains accurate; notable updates:

| VLC | Hz Player (current) |
|---|---|
| `PlaybackService` (MediaBrowserServiceCompat) | `MediaPlaybackService` (Media3 MediaSessionService) |
| libVLC `MediaPlayer` | `ExoPlayer` behind `IPlayerEngine` (`ExoPlayerEngine`) |
| `Medialibrary` indexer | MediaStore → Room cache (`MediaScanner` + DAOs) |
| `NetworkBrowserFragment` | `RemoteBrowseRepository` + `Smb/Ftp/Sftp/WebDavBrowserClient` |
| `VideoTouchDelegate` gestures | `Modifier.pointerInput` in `VideoPlayerScreen` |

---

## 3. Key Architectural Decisions (unchanged)
- **Media3 ExoPlayer** over libVLC (Compose fit, MediaSession, maintenance).
- **Room + MediaStore** over Medialibrary (compile-time SQL, system index).
- **DataStore** over SharedPreferences (Flow, type safety).
- **Native FFmpeg** for thumbnails rather than MediaStore/ExoPlayer frame capture —
  gives reliable remote-source thumbnails and platform-independent decoding.

---

## 4. Implementation Status

| Phase | Task | Status |
|---|---|---|
| 1 Foundation | Gradle / Hilt / Room / DataStore / Nav | ✅ 100% |
| 2 Architecture | Models / repos / DI / preview | ✅ 100% |
| 3 Design System | Theme / components / icons | ✅ 100% |
| 4 Video Library | Screen + VM + components | ✅ 100% |
| 5 Audio Browser | Browser + album/artist detail | ✅ 100% |
| 6 File Browser | Screen + VM + breadcrumb + archive nav | ✅ 100% |
| 7 Player | Video + audio + gestures + subs + queue | ✅ 100% |
| 8 Settings | Screen + dialogs + engine select + about/licenses | ✅ 100% |
| 9 Search | Search screen | ✅ 100% |
| 10 Integration | Repo wiring + service + PiP + resume | ✅ 100% |
| + Network streaming | SMB/FTP/SFTP/WebDAV + discovery | ✅ 100% |
| + Native thumbnails | FFmpeg JNI + Coil fetcher + probe | ✅ 100% |
| + Modular engine | IPlayerEngine + DI map + seam | ✅ 100% |
| + libass subtitles | Native ASS rendering + SRT/VTT conversion | ✅ 100% |
| + Archive support | libarchive + virtual nav + play-in-place | ✅ 100% |
| + OTA updates | R2 checker + startup reminder + About/Licenses | ✅ 100% |
| + Audio queue / Floating player | Queue sheet + PiP-style overlay | ✅ 100% |
| Cleanup/i18n pass | 27-item bug/reliability/i18n | ✅ 100% |

**Overall: foundation + all subsystems complete.**

---

## 5. Record of Changes

| Date | Change | Author |
|---|---|---|
| 2026-06-29 | Initial plan created | Claude |
| 2026-07-11 | Docs synced to current codebase (architecture, roadmap, data flow, player, UI, engine, cleanup) | Claude |
| 2026-07-21 | Docs synced: libass pipeline, archive support, OTA updates, audio queue, floating player, render seam on interface | Claude |
