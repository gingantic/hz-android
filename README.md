# Hz Player

A modern Android video/audio player inspired by VLC, built with Jetpack Compose.

## Features

- 🎬 Video & audio playback (Media3 ExoPlayer + FFmpeg)
- 🌐 Network streaming: SMB, FTP, SFTP, WebDAV
- 📦 Archive playback: zip, 7z, rar, tar, iso
- 💬 Subtitles: ASS/SSA (native libass), SRT, VTT + online search (SubDL)
- 🌍 In-app browser with ad blocking and media sniffing
- 📁 File browser with thumbnails
- ▶️ Resume playback, playlists, background audio

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- MVVM + Hilt + Room + DataStore
- Media3 ExoPlayer
- Native: FFmpeg, libass, libarchive (NDK/JNI)

## Build

```sh
# Debug APK
./gradlew assembleDebug      # Linux / macOS
gradlew.bat assembleDebug    # Windows

# Release APK
./gradlew assembleRelease
```

Requires Android SDK 36 · Min SDK 28.

## License

See [Licenses](app/src/main/assets) in the app's Settings screen.
