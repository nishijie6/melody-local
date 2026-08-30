# Contributing

Thanks for improving 音澜. Keep changes focused, preserve offline behavior, and include tests for playback, parsing or persistence rules whenever they change.

## Prerequisites

- JDK 17 or newer; Android Studio's bundled JBR is supported
- Android SDK Platform 36
- Git
- Windows, macOS or Linux

## Set up the project

1. Clone the repository:

   ```bash
   git clone https://github.com/nishijie6/melody-local.git
   cd melody-local
   ```

2. Create `local.properties` if Android Studio does not create it automatically:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```

3. Build a Debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

   On Windows PowerShell, use `./gradlew.bat assembleDebug`.

## Run verification

Before opening a pull request, run:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew connectedDebugAndroidTest
```

The project currently has JVM tests for playback queue policies, bounded and atomic LRC handling, model validation and playlist-name rules. Android instrumentation tests cover MediaSession mode switching, Room migrations, permission flows and Compose UI behavior. UI and MediaSession changes should still be checked on an Android 13–16 device or emulator.

## Release signing

Contributors do not need the maintainer's private key. Debug builds use the Android debug certificate.

Maintainers can create a signed Release build by copying `keystore.properties.example` to `keystore.properties`, replacing every placeholder and placing the referenced JKS file under `release-signing/`.

Never commit:

- JKS or keystore files
- `keystore.properties`
- passwords, tokens or API keys
- `local.properties`
- generated APKs or `build/` directories

## Change guidelines

- Keep the application offline unless a proposal explicitly changes the privacy model.
- Preserve the current song and position when changing playback modes.
- Keep playback-mode ownership in `MusicService`; use ExoPlayer shuffle/repeat policies instead of rewriting the active playlist.
- Keep Room access behind `PlaylistRepository` and MediaStore access behind `MusicRepository`.
- Add or update tests for pure policy and parser changes.
- Update `README.md`, `ARCHITECTURE.md` and `CHANGELOG.md` when user-visible behavior changes.

## Pull requests

Describe the user-visible outcome, list verification commands and mention any behavior that still requires a real device. Small, logically complete commits are easier to review and bisect.

Security reports do not belong in public issues. Follow [SECURITY.md](SECURITY.md).

