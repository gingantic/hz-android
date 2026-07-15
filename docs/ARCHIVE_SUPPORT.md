# Archive Support — Design & Implementation Plan

**Status:** Design (not yet implemented)
**Decision log:** libarchive · virtual FS / play-in-place · full format set · native delivery
**Owner:** to be assigned
**Last updated:** 2026-07-14

---

## 1. Goal

Let the player open compressed archives as **virtual folders** and **play media directly from inside them without extracting to disk**.

- User taps a `.zip`/`.7z`/`.rar`/`.tar*`/`.iso`/`.cab` in the file browser.
- App shows the archive's contents as a browsable folder.
- Tapping a video/audio entry starts playback by streaming that single entry's bytes straight into ExoPlayer.
- External subtitle files that sit next to the media inside the same archive are auto-detected and loadable.
- Password-protected archives are supported (prompt, then read).

No files are written to storage for playback. (Optional "extract to folder" action is explicitly **out of scope for v1** — see §10.)

---

## 2. Why libarchive (verified capability)

Source: official libarchive wiki — *LibarchiveFormats* and *Examples* (fetched 2026-07-14).

### 2.1 Container formats (read support — we only read)
| Format | Read | Notes |
|---|---|---|
| zip (jar/apk) | ✅ | uses zlib internally |
| 7-Zip | ✅ | read+write in lib; we read |
| rar | ✅ | wiki: *"read only, original and RAR v5 format"* |
| tar / pax / cpio | ✅ | |
| ISO9660 | ✅ | Joliet/Rockridge, some limits |
| Microsoft CAB | ✅ | read only |
| xar / lha / WARC / ar / mtree / raw | ✅ | fringe; supported |

### 2.2 Compression algorithms (filter chain)
gzip/deflate, bzip2, zstd, compress(.Z), **xz / lzma / LZMA2** (liblzma), uu/base64.
PPMd is handled *inside* the rar/7z readers (not a top-level filter).
> ⚠️ **lz4 is NOT in libarchive's filter list** — do not advertise lz4 support. Rare for media archives.

### 2.3 Encryption (decrypt-capable, read only)
- zip: AES-256 (AE-2) + legacy ZipCrypto
- 7z: AES-256 (incl. header-encrypted archives — needs password *before* listing)
- rar: v2/v3/v5 AES
API: `archive_read_add_passphrase(archive, pw)` before reading.

### 2.4 Streaming model
libarchive reads via a callback chain. For a **local archive file** the file is fully seekable, so non-streaming formats (7z/zip with central directory at end) work natively. We stream one entry's decompressed bytes to ExoPlayer; ExoPlayer's scrub/seek is served by reopen-and-skip (see §6).

---

## 3. Architecture overview

```
FileBrowserScreen
   │  tap archive  →  ArchiveRepository.listEntries(archivePath)
   ▼
ArchiveRepository  (domain)            ← NEW
   │  wraps libarchive (AAR or self-built .so)
   ▼
ArchiveEntry  (domain model)           ← NEW  (path, name, isDir, size, encrypted)
   │
   ├── browse: render entries as FolderItem-like rows (virtual folder)
   │
   └── play media entry:
         buildEntryUri(archivePath, entryPath) → "archive://<enc abs path>?entry=<enc entry>"
                        │
                        ▼
              PlayerRepository.play(archiveUri)
                        │
                        ▼
        MediaPlayerHolder.buildCompositeDataSourceFactory   (MODIFY: MediaPlayerHolder.kt:446)
                        │
                        ▼
              ProtocolRoutingDataSource  (MODIFY: MediaPlayerHolder.kt:464)
                 "archive" → ArchiveDataSource()            ← NEW
                        │
                        ▼
              ArchiveDataSource  (Media3 BaseDataSource)    ← NEW
                 opens archive, locates entry, streams bytes
                        │
                        ▼
                    ExoPlayer  (sees a normal mp4/mkv byte stream)
```

Subtitles (§8): sibling `.srt/.vtt/.ass` entries in the same archive are offered via
`IPlayerEngine.addExternalSubtitle(archiveUri)` — served by the **same** `ArchiveDataSource`.

---

## 4. Domain layer (new)

### 4.1 `domain/model/ArchiveEntry.kt`
```kotlin
@Immutable
data class ArchiveEntry(
    val path: String,        // full entry name inside archive, e.g. "movies/foo.mkv"
    val name: String,        // "foo.mkv"
    val isDirectory: Boolean,
    val size: Long,          // decompressed size
    val compressedSize: Long,
    val isEncrypted: Boolean,
)
```

### 4.2 `domain/repository/ArchiveRepository.kt`
```kotlin
interface ArchiveRepository {
    /** List entries of an archive file. Fails with crypto error if header-encrypted and no password. */
    suspend fun listEntries(archivePath: String, password: String? = null): Result<List<ArchiveEntry>>

    /** Build a playable URI for a single entry. */
    fun buildEntryUri(archivePath: String, entryPath: String): String

    /** Find subtitle siblings of a media entry inside the same archive. */
    suspend fun findSiblingSubtitles(
        archivePath: String, mediaEntryPath: String, password: String? = null
    ): List<ArchiveEntry>
}
```

`buildEntryUri` format (never crosses `IPlayerEngine` as a Media3 type — only as a String URI, consistent with `RemoteBrowseRepository.buildPlaybackUri`):
```
archive://<URL-encoded absolute archive path>?entry=<URL-encoded entry path>
```
Example: `archive:///storage/emulated/0/Movies/pack.zip?entry=movies/foo.mkv`

---

## 5. Data layer (new)

### 5.1 `ArchiveRepositoryImpl` (wraps libarchive)
- `listEntries`: `archive_read_new()` → `support_format_all` + `support_filter_all` → `read_open_filename(path)` → loop `next_header()` building `ArchiveEntry`s. Honor `password` via `archive_read_add_passphrase`.
- On `ARCHIVE_FAILED` with crypto/encrypted indication → return typed error so UI can prompt for password.
- `findSiblingSubtitles`: reuse the entry list; match `dirOf(media)/stemOf(media).{srt,vtt,ass,ssa}`.

### 5.2 `ArchiveDataSource` (Media3 `BaseDataSource`)
Mirrors the pattern of `RemoteDataSourceBase` (`data/datasource/player/RemoteDataSourceBase.kt`).
- `open(dataSpec)`: parse `archive://` URI → abs path + `entry` query + optional `password` query; open archive; walk `next_header` until `entry.pathname == target`; record `entry.size`.
- `read(buffer, offset, length)`: pull from `archive_read_data_block` loop; track `position`.
- `seek(dataSpec)`: **close + reopen + locate entry + skip to `dataSpec.position`** (see §6).
- `close()`: release archive + entry handles.
- Threading: ExoPlayer calls DataSource on its playback thread; libarchive handles are **not** shared across threads (one handle per `open()`).

### 5.3 Native delivery — **self-built `.so` (LOCKED decision)**
Cross-compile libarchive (NDK r27, `arm64-v8a`) into `jniLibs/arm64-v8a/`, matching the existing
FFmpeg/`CMakeLists.txt` convention (this project already prebuilds `libav*`/`libass` the same way).
Self-contained, exactly one ABI, no third-party Maven dependency.

- Pin **libarchive ≥ 3.7.9** (pre-3.7.8 has RAR CVEs; 3.8.x is also fine).
- Required support libs: **zlib** (NDK-provided, link `-lz`), **liblzma** (xz — for 7z/LZMA), **libbz2** (bzip2).
  zstd is optional; skip for v1 to keep the build small. lz4 is **not** supported (see §2.2).
- `CMakeLists.txt` imports `archive` like the existing `avformat`/`ass` macros (see Appendix C).
- JNI wrapper `cpp/ArchiveExtractor.{cpp,h}` exposes: `nativeList`, `nativeOpen`, `nativeRead`, `nativeSeek`, `nativeClose`.
- Licensing: libarchive is BSD-3 — keep the NOTICE/attribution in `jniLibs` or about screen.

> (The `me.zhanghai.android.libarchive` AAR was considered as a faster spike path but rejected;
> self-built `.so` keeps the build self-contained and consistent with the FFmpeg pipeline.)

---

## 6. Streaming & seek — deep dive

### 6.1 Sequential playback
ExoPlayer pulls bytes front-to-back. `ArchiveDataSource.read()` returns the next decompressed chunk of the single entry. Works for every supported format.

### 6.2 Seek / scrub
libarchive has **no general per-entry seek** — `archive_read_seek_data` only works for *uncompressed RAR* (per libarchive NEWS). Therefore:
```
seek(target):
    close archive
    reopen archive
    walk next_header until target entry
    decompress-and-discard bytes [0, target)   // re-inflate from entry start
    resume read at target
```
Correct for all formats. Cost = CPU to re-decompress up to `target`.

### 6.3 Solid archives (the real cost)
Solid = files share one compression stream; entry N needs entries 1..N-1 decompressed first.
- Open entry #N ⇒ libarchive transparently decompresses preceding entries (discarded). One-time on open.
- Seek within entry ⇒ reopen + re-skip from entry start ⇒ **also** re-pays preceding-entry cost.
- Result: solid archives **work**, but scrubbing is CPU-heavy. Identical limitation to VLC's archive access.
- v1: accept the cost. v2 optimization: cache the open handle + read position; on forward seek within buffered range continue instead of reopen; or offer "extract to temp on open" for solid archives (toggle in settings).

### 6.4 Password flow
- `entry.isEncrypted` or header-encrypted archive → UI shows lock, prompts password.
- Password passed to `listEntries`/`buildEntryUri` (as a query param the DataSource reads).
- Wrong password → libarchive fails decryption → typed error → re-prompt.

---

## 7. UI / UX behavior

- **File browser:** detect archive by extension (`.zip .7z .rar .tar .tar.gz .tgz .tar.xz .txz .iso .cab`). Tapping an archive pushes a **virtual path** into the existing `BreadcrumbBar` / `DirectoryBrowsePane` (reuse, don't rebuild).
- **Inside archive:** render entries as `FolderItem`-like rows; nested archives can be opened as a new virtual level (virtual path stacks — cheap).
- **Media entry tap:** `buildEntryUri` → `PlayerRepository.play`. No extraction UI, no progress dialog.
- **Password:** dialog on encrypted/list-fail; breadcrumb shows a lock chip when inside an encrypted archive.
- **Symlinks/hardlinks:** shown but not playable (skip / disabled).
- **Extract action:** deferred (§10).

---

## 8. External subtitle detection (inside archive)

Because we already enumerate every entry:
- For media entry `movies/foo.mkv`, scan the same entry list for siblings `movies/foo.{srt,vtt,ass,ssa}`.
- Offer them in the existing subtitle UI (`SubtitleRepository` / `SubtitleSelectionDialog` area).
- Load via `IPlayerEngine.addExternalSubtitle(archiveUri)` — the **same `ArchiveDataSource`** serves the sub entry (ExoPlayer loads external subs through a DataSource).
- Mirror the existing `findSmbNeighborSubtitles()` logic → `ArchiveRepository.findSiblingSubtitles()`.
- Embedded in-container subtitle tracks are handled by ExoPlayer normally (it reads the whole entry stream).
- **Edge case:** subs inside a *nested* archive are not auto-found (defer).

---

## 9. Integration points (modify)

| File | Change |
|---|---|
| `MediaPlayerHolder.kt:464` `ProtocolRoutingDataSource` | add `"archive" -> ArchiveDataSource()` |
| `FileBrowserViewModel` / `FileBrowserUiState` | archive ext detection; virtual-path navigation; breadcrumb for archive levels |
| `MimeTypeUtil` (or new `ArchiveUtil`) | `isArchive(path)`, `archiveExtensions` |
| `PlayerRepository` / nav | route `archive://` URI to engine like any other source |
| (optional) `MediaScanner` | v2: index media inside archives into Video/Audio Library + search |

No Media3 type crosses `IPlayerEngine` (only the `archive://` String URI) — keeps the abstraction clean per `ENGINE_MODULARITY.md`.

---

## 10. Limitations & explicitly deferred

- **Multi-part / split archives** (`.001`, `.z01`, spanned zip, multipart rar): libarchive can read appended parts, but v1 = **single-file archives only**. Common for large rar/zip splits — flagged as a concern (§12).
- **lz4 compression:** not supported by libarchive filter chain — do not advertise.
- **Extract-to-folder action:** deferred (v2). Play-in-place is the v1 deliverable.
- **Nested-archive subtitle auto-detection:** deferred.
- **Thumbnail of media inside archive:** v2 (feed entry bytes through `RandomAccessBridge`/`ThumbnailSource` into `NativeThumbnailExtractor`).
- **Library/Search indexing of archive contents:** deferred (v2) unless scoped otherwise (§12).

---

## 11. Phased plan

**Phase 0 — Spike (self-built `.so`):**
1. Cross-compile libarchive → `jniLibs/arm64-v8a/libarchive.so` (+ `liblzma`, `libbz2` if built standalone). See Appendix C (`build_libarchive.sh`).
2. `CMakeLists.txt`: import `archive`; add `ArchiveExtractor` JNI lib.
3. `ArchiveRepositoryImpl` (JNI) `listEntries` + `buildEntryUri` (local file).
4. `ArchiveDataSource` (open/read/seek/close) via JNI.
5. Wire `ProtocolRoutingDataSource` `archive` scheme (`MediaPlayerHolder.kt:464`).
6. Verify: open a zip containing an mp4 → browse → play (incl. one scrub) → sibling `.srt` loads.

**Phase 1 — v1 (local, robust):**
- Password UI + header-encrypted handling.
- Virtual breadcrumb navigation + nested archives.
- Sibling subtitle detection + load.
- Solid-archive seek (reopen/skip) — accepted cost.
- Pin libarchive **≥ 3.7.9** (pre-3.7.8 has RAR CVEs).

**Phase 2 — v2 (optional):**
- Self-built `.so` (Option B) if self-contained delivery required.
- Temp-extract-on-open toggle for solid archives.
- In-archive thumbnails.
- Library/Search indexing of archive media.
- Multi-part / split archive support.

---

## 12. Risks & open concerns — DECISIONS LOCKED (2026-07-14)

| # | Concern | Decision |
|---|---|---|
| 1 | Remote archives (SMB/FTP/WebDAV)? | **Local files only** for v1. Network archive streaming deferred. |
| 2 | Library tab & search indexing? | **File Browser virtual navigation only**. No Library/Search indexing in v1. |
| 3 | Solid-archive scrub perf | Accept reopen/skip cost for v1 (§6.3). Temp-extract toggle is v2. |
| 4 | Delivery | **Self-built `.so`** from the start (§5.3). AAR rejected. |
| 5 | Multi-part / split archives | **Defer to v2**. v1 = single-file archives only. |

Residual risks (non-blocking, tracked):
- Solid-archive scrub CPU cost on very large archives (mitigated in v2).
- Nested-archive subtitle auto-detection deferred (§10).
- In-archive thumbnails deferred to v2 (Appendix A / §10).

---

## Appendix A — libarchive API surface used
- `archive_read_new`, `archive_read_support_format_all`, `archive_read_support_filter_all`
- `archive_read_open_filename` (local, seekable)
- `archive_read_next_header`, `archive_entry_pathname/size/isEncrypted/...`
- `archive_read_data_block` (zero-copy read loop)
- `archive_read_add_passphrase` (decryption)
- `archive_read_free` / `archive_read_close`

## Appendix B — references
- libarchive wiki: LibarchiveFormats (format/filter tables)
- libarchive wiki: Examples (seek/skip callback notes; 7z/zip non-streaming)
- Project: `MediaPlayerHolder.kt` (`buildCompositeDataSourceFactory` :446, `ProtocolRoutingDataSource` :464)
- Project: `RemoteDataSourceBase.kt`, `RemoteBrowseRepository.buildPlaybackUri`
- Project: `SubtitleRepository.findSmbNeighborSubtitles()` (mirror for siblings)
- Project: `core/thumbnail/RandomAccessBridge.kt` (v2 thumbnails)

## Appendix C — Spike recipe (self-built `.so`)

### C.1 `build_libarchive.ps1` (Windows-native, NDK r27, arm64-v8a) — VERIFIED ✅
> Produced `app/src/main/jniLibs/arm64-v8a/libarchive.so` (aarch64) on 2026-07-14.
> Builds libbz2 (static, via tiny CMake) + liblzma/xz (static) then libarchive (shared),
> fusing bz2+lzma statically into the single `.so`; zlib comes from the NDK sysroot.
> Uses the SDK `cmake 3.22.1` + its bundled `ninja`, driving the NDK android toolchain
> exactly like the project's own `externalNativeBuild`. Run:
> `powershell -ExecutionPolicy Bypass -File build_libarchive.ps1`
> (WSL was considered but the installed NDK only ships the Windows toolchain; the
> Windows-native path reuses the exact same NDK r27, so the `.so` is version-exact.)

### C.2 `CMakeLists.txt` (import like FFmpeg)
Add after the existing `add_ffmpeg_lib` / `add_ass_dep` macros:
```cmake
add_library(archive SHARED IMPORTED)
set_target_properties(archive PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libarchive.so)

add_library(archive-extractor SHARED ArchiveExtractor.cpp)
target_include_directories(archive-extractor PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(archive-extractor archive ${LOG})
```

### C.3 `cpp/ArchiveExtractor.h` (JNI seam)
```c
#ifndef ARCHIVE_EXTRACTOR_H
#define ARCHIVE_EXTRACTOR_H
#include <jni.h>
#ifdef __cplusplus
extern "C" {
#endif
JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_data_archive_ArchiveRepositoryImpl_nativeList(
    JNIEnv*, jobject, jstring archivePath, jstring password);
JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_archive_ArchiveRepositoryImpl_nativeOpen(
    JNIEnv*, jobject, jstring archivePath, jstring entryPath, jstring password);
JNIEXPORT jint JNICALL
Java_com_rhnxdev_hzplayer_data_archive_ArchiveRepositoryImpl_nativeRead(
    JNIEnv*, jobject, jlong handle, jbyteArray buf, jint off, jint len);
JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_archive_ArchiveRepositoryImpl_nativeSeek(
    JNIEnv*, jobject, jlong handle, jlong offset);
JNIEXPORT void JNICALL
Java_com_rhnxdev_hzplayer_data_archive_ArchiveRepositoryImpl_nativeClose(
    JNIEnv*, jobject, jlong handle);
#ifdef __cplusplus
}
#endif
#endif
```
`nativeList` returns `String[]` rows `"path\tisDir\tsize\tencrypted"`. `nativeOpen` returns an opaque `long`
(a heap `struct { struct archive*; struct archive_entry*; int64_t pos; }`). `nativeRead` pulls from
`archive_read_data_block`; `nativeSeek` closes+reopens+skips (§6.2).

### C.4 Kotlin skeleton — `ArchiveDataSource` (read/seek core)
```kotlin
class ArchiveDataSource : BaseDataSource(/* isNetwork = */ false) {
    private var handle: Long = 0
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val (archivePath, entryPath, password) = parseArchiveUri(uri!!)
        handle = ArchiveRepositoryImpl.nativeOpen(archivePath, entryPath, password)
        // if dataSpec.position > 0, nativeOpen already seeks (reopen+skip)
        return dataSpec.length  // entry size reported by native
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        return ArchiveRepositoryImpl.nativeRead(handle, buffer, offset, readLength)
    }

    override fun seek(position: Long) { /* handled inside nativeOpen via dataSpec.position */ }

    override fun close() {
        if (handle != 0L) { ArchiveRepositoryImpl.nativeClose(handle); handle = 0 }
    }
}
```
Register in `ProtocolRoutingDataSource` (`MediaPlayerHolder.kt:464`):
```kotlin
"archive" -> ArchiveDataSource()
```

### C.5 Verify
Drop `test.zip` containing `movie.mp4` + `movie.srt` on device storage → File Browser → tap `test.zip`
→ see entries → tap `movie.mp4` → plays → subtitle auto-loads. Scrub once to confirm reopen/skip seek.

---

## Status (2026-07-14) — native seam implemented & compiling ✅

The Appendix C skeleton is illustrative; the **actual** implementation landed as:

- `app/src/main/cpp/ArchiveExtractor.cpp` — JNI shim (compiles via NDK clang, links `libarchive`).
  JNI class `ArchiveNative` in package `data.datasource.archive` (not `ArchiveRepositoryImpl`).
  Methods: `nativeList`, `nativeOpen`, `nativeLength`, `nativeRead`, `nativeSeek`, `nativeClose`.
  `nativeRead`/`nativeSeek` take `(jbyteArray,off,len)` / `(long)` — no separate offset param.
- `app/src/main/cpp/CMakeLists.txt` — imports prebuilt `libarchive` (from jniLibs/arm64-v8a) and adds
  the `archive-extractor` shared lib; public headers copied to `cpp/include/libarchive`.
- `data/datasource/archive/ArchiveNative.kt` (JNI bridge, `System.loadLibrary("archive-extractor")`).
- `data/datasource/archive/ArchiveDataSource.kt` — Media3 `DataSource` (extends `DataSource` directly,
  not `BaseDataSource`); `archive:///<urlEncContainer>/<urlEncEntry>` URI shape via `buildUri`/`parse`.
- `domain/repository/ArchiveRepository.kt` (+ `ArchiveEntry`) and `data/repository/ArchiveRepositoryImpl.kt`
  — `listEntries(...): Result<List<ArchiveEntry>>`; bound in `di/RepositoryModule.kt`.
- `MediaPlayerHolder.kt` `ProtocolRoutingDataSource` — `"archive" -> ArchiveDataSource()` wired (line ~478),
  and the player's `DefaultMediaSourceFactory` already uses `buildCompositeDataSourceFactory`, so playback routes here.

`gradlew.bat assembleDebug` → BUILD SUCCESSFUL (native + Kotlin).

### File Browser virtual navigation — DONE ✅ (2026-07-14)

Tapping an archive in the File Browser now opens it as an in-place virtual layer; nested dirs navigate;
media entries play via `archive://`. No extraction. Wiring:

- `core/util/ArchivePaths.kt` — pure codecs + helpers:
  - `ARCHIVE_EXTENSIONS` / `isArchiveExtension` (zip/7z/rar/tar/iso/cab/gz/tgz/bz2/tbz2/xz/txz/cpio; **no lz4**).
  - `ArchiveUri.build/parse` — playback URI `archive:///<encContainer>/<encEntry>` (shared with `ArchiveDataSource`).
  - `ArchiveBrowsePath.build/parse/isArchiveBrowsePath/isRealFilePath` — virtual browse path
    `archivebrowse:<container>\n<innerPrefix>` for listing a level.
  - `buildArchiveBreadcrumbs` — `[container.zip, dir, subdir, …]`.
- `FileBrowserViewModel` — injects `ArchiveRepository`; `onOpenArchive`, `loadArchiveDirectory`,
  `archiveChildren` (synthesizes dir levels from entry segments since archives may omit dir entries).
  `pushLayer`/`loadDirectory` branch on `isArchiveBrowsePath`. Listing cached in the existing `DirectoryLruCache`.
- `FileBrowserScreen` — `handleFileClicked` routes a real archive file → `onOpenArchive`; everything else
  (incl. media entries whose path is an `archive://` URI) → existing play path. MainActivity untouched.
- `ArchivePathsTest` — 7 round-trip/parse tests, all green (`testDebugUnitTest`).

**Behaviour notes / deferred:**
- Nested archives (archive inside archive) fall through to the play path (deferred to v2, per §12).
- Media-mode (video-thumbnail grid) hides archive containers — only normal list mode opens them (acceptable first cut).
- Resume-by-URI works for free: positions key on the `archive://` URI.
- Sibling-subtitle auto-load *inside* an archive: not yet wired (design §subtitles) — next task.
- Password prompt UI still deferred (native accepts it; DataSource/repo pass `null`).

**Remaining:** on-device smoke test (C.5); in-archive sibling-subtitle discovery.

