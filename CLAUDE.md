# CLAUDE.md

Guidance for Claude (claude.ai/code) when working with code in this repository.

---

## ⚠️ MANDATORY: Verify Before You Change

1. **Read the relevant file(s) in full** — never assume what already exists.
2. **Follow the existing pattern** — do not invent a new one.
3. **Unsure about an API, behaviour, or best practice?** Search the official docs (Android, Media3/ExoPlayer, Jetpack Compose) — wrong code is worse than slow delivery.
4. **Check `docs/`** before designing any feature — architecture decisions live there.
5. **Never silently delete or reorganise code** — ask first.

---

## Build & Run

```sh
gradlew.bat assembleDebug      # Windows ← use this
./gradlew assembleDebug        # Linux / macOS
./gradlew assembleRelease      # release APK
./gradlew test                 # unit tests
./gradlew connectedCheck       # instrumented (on-device) tests
./gradlew lint                 # static analysis
```

Gradle wrapper **8.13** — see `gradle/wrapper/gradle-wrapper.properties`.

---

## Native (C/C++) Cross-Compilation

Native libs (`libarchive.so`, FFmpeg, libass) are built **outside Gradle** by standalone scripts (`build_libarchive.sh`, `ffmpeg_build_android.sh`, `libass_build_android.sh`). Hard-won rules for this Windows host:

- **Toolchain:** NDK r27 ships only the `windows-x86_64` prebuilt. Drive the Windows clang wrappers (`aarch64-linux-android<N>-clang` etc.) **from WSL** (`wsl bash -c '...'`). Wrappers set `--target` but NOT `--sysroot` — do NOT pass an explicit `--sysroot` (breaks header resolution; the baked-in default works).
- **`/mnt/c` is NOT readable by Windows NDK tools** (`clang.exe`, `llvm-nm.exe`, `llvm-strings.exe`, `llvm-objdump.exe`). Pass the *argument* as a native `C:/...` path (forward slashes OK); the tool's own *location* can stay `/mnt/c/...`. Any `nm`/`strings` result of "0 symbols / no such file" is this artifact, **not** a real empty lib — re-run with a `C:/` argument.
- **`ar`/`ranlib`** = `llvm-ar.exe` / `llvm-ranlib.exe` (no extensionless wrapper exists).
- **Static → shared:** use a `Generic` CMake system (never `Linux`, which leaks host `/usr/include` → `bits/wordsize.h` not found). Build static `.a`, then fuse into a `.so` with the clang wrapper: `-shared -Wl,--whole-archive … -Wl,--no-whole-archive`. **Every static dep that supplies symbols must sit INSIDE the `--whole-archive` group** (e.g. mbedTLS) or the linker silently drops it.
- **Output path:** link to a temp file first, then `cp -f` into `app/src/main/jniLibs/<abi>/` — writing directly fails `Permission denied` on the locked file.
- **`android_lf.h`:** libarchive's copy is a *static* header at `contrib/android/include` (not installed) — add `-I<src>/contrib/android/include` via `CMAKE_C_FLAGS` in the toolchain file (FORCE-set), not the per-project cmake call.
- **mbedTLS:** pin **`mbedtls-3.6.7`** (LTS) — libarchive master uses the legacy `mbedtls_md_hmac_*` API removed in the mbedTLS dev branch. Build all three (`libmbedtls.a`, `libmbedx509.a`, `libmbedcrypto.a`).
- **Verify a built `.so`:** `nm` on a *stripped* APK-packed `.so` is empty by design — confirm with `llvm-strings.exe` on the `C:/...` path, grep for distinctive strings (e.g. `Mbed TLS 3.6.7`, `mbedtls_aes_crypt_ecb`).

---

## Project Overview

**Hz Player** — VLC-inspired Android video/audio player. Local storage, SMB, FTP, SFTP, WebDAV sources (custom DataSources per protocol) + full in-app browser with ad blocking and media sniffing.

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository + StateFlow |
| Media | Media3 ExoPlayer + standalone native FFmpeg player (`IPlayerEngine` abstraction, 3 engines) |
| Min / Target / Compile SDK | 28 / 36 / 36 |

Full detail lives in `docs/ARCHITECTURE.md` — do not duplicate it here.

---

## Architecture Rules

- **Screen = stateless Composable** — receives `UiState` + lambda callbacks only; never holds repositories.
- **ViewModel = `@HiltViewModel`** — exposes `StateFlow<XxxUiState>`, calls repositories, owns side effects.
- **Repository = interface in `domain/`, impl in `data/`** — always inject the interface.
- **UiState = `@Immutable data class`** — updated only via `copy()`.
- **Preview data** lives in `presentation/preview/PreviewMedia.kt`.
- `collectAsStateWithLifecycle()` everywhere; `remember` for local UI state only (animation, menus, scroll position).

```kotlin
@HiltViewModel
class XxxViewModel @Inject constructor(private val repo: XxxRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(XxxUiState())
    val uiState: StateFlow<XxxUiState> = _uiState.asStateFlow()
}

@Composable
fun XxxScreen(vm: XxxViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    XxxContent(state = state, onAction = vm::handleAction)
}
```

### Player stack

```
VideoPlayerScreen → PlayerViewModel → PlayerRepository(Impl)
  → Map<EngineType, IPlayerEngine> (bound via PlayerEngineModule, @Singleton)
  → ExoPlayerEngine (EXO_PLAYER + FFMPEG) / FfmpegNativeEngine (NATIVE_FFMPEG)
  → MediaPlayerHolder (Exo engines) / libffplayer.so JNI (native engine)
  → MediaPlaybackService (MediaSessionService; only engines with non-null getMedia3Player())
```

- `domain/player/IPlayerEngine` is the **only** playback boundary — no Media3 type crosses it. New backend = implement `IPlayerEngine` + one `@Binds @IntoMap @EngineKey(...)` line in `PlayerEngineModule`. Engines: `EXO_PLAYER`, `FFMPEG` (same ExoPlayer pipeline, FFmpeg renderers preferred), `NATIVE_FFMPEG` (standalone native).
- **Position is NOT in `PlayerUiState`** — the 250 ms tick is a separate `StateFlow` in the ViewModel to avoid full recompose.
- VideoPlayerScreen gestures are **mutually exclusive** — one per touch sequence (hold-to-speed-up, horizontal scrub, brightness left half, volume right half, double-tap seek).
- Deep-dive: `docs/PLAYER_ARCHITECTURE.md`.

### Network & subtitles

- SMB / FTP / SFTP / WebDAV — one browser client + one DataSource each, per-protocol seek strategy. `ConnectionPool` (`@Singleton`) caches by `host:port:user`: SMB single `CIFSContext` per server, FTP control-connection reuse, SFTP session reuse, WebDAV via OkHttp pool.
- `ServerDiscoverer`: NSD/mDNS discovery with port-scan fallback → `StateFlow<List<ServerConfig>>`.
- Subtitles: built-in tracks via `IPlayerEngine.selectSubtitleTrack()`; external `.srt/.vtt/.ass` via `addExternalSubtitle(uri)`; delay via `setSubtitleDelay()`; online search via `SubdlApi` → `SubtitleSearchViewModel`; SMB sibling auto-discovery (`findSmbNeighborSubtitles()`, jcifs-ng). Native engine renders via libass (`AssHandler` → `AssSubtitleOverlay`, zero-flicker).

### Navigation

5 tabs — `video_library`, `audio_browser`, `file_browser`, `network`, `settings` (`AppDestinations.kt`). Player launches as a full-screen destination on top of the nav graph.

### VLC reference

`vlc-android-master/` tree was **removed** — VLC concepts are already adapted into the Compose + Media3 + MVVM codebase. Do not re-add.

---

## Code Conventions

- Files: `XxxScreen.kt` / `XxxViewModel.kt` / `XxxUiState.kt` / `XxxRepository.kt` / `XxxRepositoryImpl.kt`. Keep files under **~300 lines**; extract reusable Composables to `components/` sub-package.
- Every public Composable: `modifier: Modifier = Modifier` parameter. Every reusable component: `@Preview` using `PreviewMedia` (no ViewModel in preview).
- `data class` + `copy()` for models; `sealed interface` for UI events/actions; extension functions → `core/util/` or `core/extensions/`; no `lateinit` in ViewModels — constructor injection only.

Hard rules:
- ❌ No `LiveData` · no `SharedPreferences` (use `DataStore`) · no `runBlocking` in production
- ❌ No hardcoded fake data in Composables — use `PreviewMedia`
- ❌ No direct `Context` in ViewModels — `@ApplicationContext` only when unavoidable
- ❌ No Media3 types crossing `IPlayerEngine`

---

## Research Protocol

1. **Read existing code** — find what already exists and reuse it.
2. **Check `docs/`** — architecture decisions are documented there.
3. **Do not guess** — if an API or behaviour is unclear, check official docs before writing a single line. A wrong API call is worse than slow delivery.
4. **Complex change** (multi-step, architectural, 3+ files)? Run **Sequential Thinking** (`mcp__sequential-thinking__sequentialthinking`) *before* editing — comprehension first, then the smallest diff. Apply the Ponytail ladder after understanding the full flow: reuse existing code → stdlib → platform feature → installed dep → one line → minimum code.

Before modifying existing code: read the **entire file**, understand *why* it's written that way (`docs/` or git history), check callers (blast radius), trace the data flow (event → ViewModel → state → UI). When in doubt, **ask** — a wrong fix is worse than no fix.

---

## Known In-Progress Work

| Feature | Status |
|---|---|
| HDR→SDR colour correction (`GlEffect` / custom GLSL) | 🔧 In progress — pref wired, pipeline no-op |
| Custom `SubtitleOverlay` (replace built-in PlayerView subtitles) | ⏸ Parked — built-in active for reliability; native engine uses libass overlay |

---

## Docs Index

| File | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Full app architecture, module boundaries |
| [`docs/PLAYER_ARCHITECTURE.md`](docs/PLAYER_ARCHITECTURE.md) | Player stack deep-dive |
| [`docs/DATA_FLOW.md`](docs/DATA_FLOW.md) | Data flow diagrams |
| [`docs/UI_COMPONENTS.md`](docs/UI_COMPONENTS.md) | Composable component catalogue |

---

## 🚦 Guidelines for AI Development

* **Minor Features & Bug Fixes:** the AI may implement and verify minor features, bug fixes, refactoring, pipeline adjustments, and styling alignments.
  * **Versioning Increment:** the AI must always ask for the developer's confirmation before changing version numbers (e.g. `X.Y.Z` → `X.Y.Z+1` for patches, `X.Y.0` → `X.Y+1.0` for minor features) — proposed as examples for approval.
* **Major Features & Core Architecture:** only the human developer makes major architecture modifications, structural design changes, or major features.
