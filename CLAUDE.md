# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```sh
# Build debug APK
./gradlew assembleDebug

# Build release
./gradlew assembleRelease

# Run tests
./gradlew test                                   # Unit tests
./gradlew connectedCheck                         # Instrumented tests
./gradlew test --tests "com.rhnxdev.hzplayer.*"  # Single test class

# Lint
./gradlew lint

# Clean
./gradlew clean
```

Use `gradlew.bat` instead of `./gradlew` on Windows. Gradle wrapper is at `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.13).

## Project Overview

**Hz Player** — a VLC-inspired Android media player built from scratch. Currently in early foundation phase.

### Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository + StateFlow |
| DI | Hilt |
| Media | Media3 ExoPlayer |
| Persistence | Room + Preferences DataStore |
| Images | Coil 3 |
| Navigation | Navigation Compose + M3 Adaptive Navigation Suite |
| SDK | compileSdk/targetSdk 36, minSdk 28 |

### Current State

The project has a `MainActivity` with `NavigationSuiteScaffold` and 3 placeholder destinations. Theme uses M3 dynamic colors with dark/light support. All architecture layers (`domain/`, `data/`, `di/`, `core/`) need to be built. The `docs/` directory contains the full roadmap.

## Architecture

```
presentation/     — Compose screens, ViewModels, UiState, preview data
domain/           — Pure Kotlin models, repository interfaces, use cases
data/             — Repository implementations, Room DAOs, DataStore, MediaScanner, ExoPlayer holder
core/             — Reusable composable components, design system tokens, utilities, extensions
di/               — Hilt modules (AppModule, DatabaseModule, RepositoryModule, PlayerModule)
```

### Key Patterns

- **Screen = stateless composable** receiving `UiState` + callbacks, never accessing repositories
- **ViewModel = `@HiltViewModel`** exposing `StateFlow<UiState>`, calling repositories
- **Repository = interface in `domain/`**, implementation in `data/`
- **UiState = immutable data class**, one per screen
- **Preview data** lives in `presentation/preview/PreviewMedia.kt`, never hardcoded in composables

### Dependencies

- Hilt scopes: `@Singleton` for player, database, repositories
- Room with KSP code generation
- Coil 3 for async image loading in composables
- DataStore for user preferences (not SharedPreferences)

## VLC Reference

The official VLC Android source is at `vlc-android-master/`. Use it for UX and interaction reference but **do not copy code** — adapt concepts to Compose + Media3 + MVVM.

### Where to Look in VLC Source

| Feature | VLC File |
|---|---|
| Video library | `vlc-android/src/.../video/VideoGridFragment.kt` |
| Audio browser | `vlc-android/src/.../audio/AudioBrowserFragment.kt` |
| Audio player | `vlc-android/src/.../audio/AudioPlayer.kt` |
| Video player | `vlc-android/src/.../video/VideoPlayerActivity.kt` |
| Player controls | `vlc-android/src/.../video/VideoPlayerOverlayDelegate.kt` |
| Touch gestures | `vlc-android/src/.../video/VideoTouchDelegate.kt` |
| Playback service | `vlc-android/src/.../PlaybackService.kt` |
| File browser | `vlc-android/src/.../browser/FileBrowserFragment.kt` |
| Media providers | `vlc-android/src/.../providers/medialibrary/` |

## Code Conventions

- StateFlow in ViewModel, `collectAsStateWithLifecycle()` in composables
- `remember` for local UI state only (expanded menus, animation state, scroll state)
- No `LiveData` or `mutableStateOf` for business data
- No hardcoded fake data in composables — use `PreviewMedia` helpers
- Follow existing file naming (`XxxScreen.kt`, `XxxViewModel.kt`, `XxxUiState.kt`)
- Keep files under ~300 lines; extract reusable composables to `components/`
- Every reusable component gets a `@Preview` using `PreviewMedia` data
- Preview composables never require a ViewModel

## Research First

Before implementing a feature:
1. Check existing code for reuse opportunities
2. Read the relevant VLC source file for UX reference
3. Verify Android/Compose/Media3 APIs in official docs
4. Do not guess — incorrect code is worse than slower implementation
