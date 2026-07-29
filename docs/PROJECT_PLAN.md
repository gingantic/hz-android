# Hz Player — Project Plan

> **Project**: Hz Player — VLC-inspired Android media player
> **Root**: `C:\Users\reihan\Desktop\Rhvn-player\hz-android`
> **Package**: `com.rhnxdev.hzplayer`
> **Last refreshed**: 2026-07-29. All original phases complete; eight net-new
> work streams (network streaming, native thumbnails, modular engine, libass
> subtitles, archive support, OTA updates, in-app browser, player enhancements)
> since then.

---

## 1. Current State

Hz Player is past foundation: all 10 original roadmap phases are implemented, and
eight additional subsystems have been added on top:

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
  streaming from inside archives (no extraction to disk). Solid archive warnings.
- **OTA updates** — Cloudflare R2 update checker with startup reminder, About dialog,
  and open-source licenses screen (AboutLibraries).
- **In-app browser** — WebView-based browser with Rust ad blocking engine (JNI),
  media sniffing, tab management (swipe-to-switch), browser history, popup
  permissions, real desktop mode, and settings.
- **Player enhancements** — Sleep timer (presets + end-of-video), container chapter
  navigation (MKV/MP4/OGG), A-B repeat loop, audio delay (A/V sync via
  AudioDelaySink), play-as-audio mode, hold-to-fast-forward cue with real skipped
  time display.

### What exists
- ✅ Compose + M3, 5-tab `NavigationSuiteScaffold`, M3 dynamic theme (`presentation/theme`)
- ✅ Hilt DI (`AppModule` / `RepositoryModule` / `DatabaseModule` / `PlayerEngineModule`)
- ✅ Room (5 DAOs, v6) + DataStore preferences + encrypted server credentials
- ✅ Media3 ExoPlayer via singleton `MediaPlayerHolder` + `MediaPlaybackService`
- ✅ Coil 3 image loading + native thumbnail fetcher + codec metadata probe
- ✅ Full screens: video library, audio browser (+ album/artist detail), file browser,
  network, player (video + audio), search, settings, licenses
- ✅ Network stack: SMB/FTP/SFTP/WebDAV clients, connection pool, LAN discovery
- ✅ Native libass subtitle pipeline (ASS/SSA/SRT/VTT rendering)
- ✅ Archive support: libarchive JNI + virtual navigation + `archive://` DataSource + solid archive warnings
- ✅ Audio queue + floating video player (PiP-style)
- ✅ OTA update checker + startup reminder + About/Licenses
- ✅ Full i18n (strings.xml; `DEBUG`-gated preview data only)
- ✅ Native FFmpeg thumbnail pipeline (`cpp/ThumbnailExtractor.cpp`)
- ✅ In-app browser (WebView + Rust ad block + media sniffing + tabs + history + desktop mode)
- ✅ Player enhancements: sleep timer, chapters, A-B repeat, audio delay, play-as-audio
- ✅ Extracted `HzPlayerApp` composable shell + `MainTabPager` / `MainNavHost` split
- ✅ `FileOptionsBottomSheet` context menus, `SolidArchiveWarningDialog`

### What is missing / deferred
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
| + In-app browser | WebView + Rust ad block + media sniffing + tabs + history + desktop mode | ✅ 100% |
| + Player enhancements | Sleep timer + chapters + A-B repeat + audio delay + play-as-audio | ✅ 100% |
| + App shell & UI overhaul | HzPlayerApp extraction + bottom-sheet menus + solid archive warnings | ✅ 100% |
| Cleanup/i18n pass | 27-item bug/reliability/i18n | ✅ 100% |

**Overall: foundation + all subsystems complete.**

---

## 5. Record of Changes

| Date | Change | Author |
|---|---|---|
| 2026-06-29 | Initial plan created | Claude |
| 2026-07-11 | Docs synced to current codebase (architecture, roadmap, data flow, player, UI, engine, cleanup) | Claude |
| 2026-07-21 | Docs synced: libass pipeline, archive support, OTA updates, audio queue, floating player, render seam on interface | Claude |
| 2026-07-24 | Docs synced: in-app browser, 11 repositories, 5 Room tables, 27 player components, 20 domain models, corrected counts throughout | Claude |
| 2026-07-29 | Docs synced: sleep timer, chapters, A-B repeat, audio delay, play-as-audio, app shell extraction, bottom-sheet menus, solid archive warnings, Rust adblock, Room v6, new components throughout | Claude |
