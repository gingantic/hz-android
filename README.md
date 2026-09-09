# Hz Player

Hz Player is an Android media player for local files, network sources, archive entries, and browser media. It supports video and audio playback through Media3 and native FFmpeg-based components.

## Features

- Local video and audio playback with resume positions, playlists, shuffle, repeat, seeking, and playback speed controls
- Media3 playback for HLS, DASH, and RTSP streams
- Selectable playback paths using Media3, Media3 with FFmpeg software renderers, or the native FFmpeg player
- Network browsing for SMB, FTP, SFTP, WebDAV, and WebDAVS
- File browser with thumbnails and file operations
- Playback of individual entries from supported archives through libarchive
- Subtitle support for ASS/SSA, SRT, and VTT, including embedded and external tracks
- In-app browser with tabs, ad blocking, media detection, and picture-in-picture support
- Background audio playback through a Media3 foreground service
- Ten-band equalizer with device audio effects where available
- Android intent handling for local media files and HTTP, HTTPS, and RTSP URLs

## Requirements

- Android 9 (API 28) or newer
- Android SDK 36
- JDK 17
- Android NDK `27.0.12077973`
- CMake 3.22.1 or newer
- An `arm64-v8a` device or emulator

The application currently packages only the `arm64-v8a` ABI.

## Build

Open the project in Android Studio, or use the Gradle wrapper from the repository root.

### Debug build

Windows:

```powershell
gradlew.bat assembleDebug
```

Linux, macOS, or WSL:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug application uses the package suffix `.debug`.

### Release build

Windows:

```powershell
gradlew.bat assembleRelease
```

Linux, macOS, or WSL:

```sh
./gradlew assembleRelease
```

Release builds are minified and use the configured release keystore when one is available. If no release keystore is configured, Gradle falls back to debug signing.

## Native dependencies

The Android module links prebuilt FFmpeg, libass, libarchive, dav1d, and related libraries. These files are not stored in the repository. A fresh checkout therefore requires the native artifacts to be restored from cache or built before Gradle can link the application.

The native build scripts expect Linux or WSL and the associated Unix build tools. When the artifacts are not available, run the following from the repository root after setting up the Android NDK:

```sh
./ffmpeg_build_android.sh --abi arm64-v8a
./libass_build_android.sh --abi arm64-v8a
./build_libarchive.sh --abi arm64-v8a --ndk-path "$ANDROID_NDK_ROOT"
./build_adblock_rust.sh
```

The generated libraries are placed under `app/src/main/jniLibs/arm64-v8a/` and native headers under `app/src/main/cpp/include/`.

## Tests

Run the unit test suite with:

```sh
./gradlew test
```

On Windows, use `gradlew.bat test`.

## Configuration notes

- Full file browsing on Android 11 and newer may require enabling the app's full storage access manually in Android settings.
- Online subtitle search requires a configured SubDL API key.
- The in-app updater reads `latest.json` from the URL configured by `R2_UPDATE_BASE_URL`. The repository does not provide a public update endpoint by default.

## Project structure

```text
app/src/main/java/       Kotlin application code
app/src/main/cpp/        JNI and native playback, subtitle, archive, and thumbnail code
app/src/main/assets/     Browser assets and bundled application resources
docs/                    Project documentation
```

The main application package is `com.rhnxdev.hzplayer`.

## Versioning

The version name is generated from Git metadata in the form:

```text
0.9.1-build.<commit-count>+<short-hash>
```

The version code is the number of commits in the repository.

## License

This repository does not currently include a project-level `LICENSE` file. No license is granted for the application code by this README. FFmpeg, libass, libarchive, dav1d, and other third-party components retain their respective licenses and notices; review those terms before distributing a build.
