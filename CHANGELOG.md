# Changelog

All notable changes to this project are documented in this file.

## [1.1.0] - 2026-08-26

### Added

- Added five playback modes: play once, list loop, random loop, single-song loop and reverse loop.
- Added full playlist management with create, rename, delete, add-song and remove-song actions.
- Added local LRC import with synchronized scrolling, line seeking, UTF-8 and GB18030 decoding.
- Added search, title/artist/recent sorting, a mini player and an animated full-screen player.
- Added an explicit stop action that clears the active playback queue.

### Changed

- Upgraded the project to Android SDK 36, Android Gradle Plugin 8.9.1 and Gradle 8.11.1.
- Random playback now uses a stable non-repeating cycle, and previous navigation follows that cycle.
- Reverse and random mode changes now update ExoPlayer traversal policy inside `MusicService` without rewriting the Media3 playlist.
- Improved full-screen player brightness and control contrast.
- Reject duplicate playlist names after trimming and case-insensitive comparison.

### Fixed

- Removed the short playback interruption that occurred when changing playback modes.
- Preserved the current MediaItem, playback position and buffering while switching playback modes.
- Enforced the 2 MiB lyric import limit while streaming, before an oversized file can fill process memory.
- Disabled cloud backup and device transfer with both legacy and Android 12+ exclusion rules until the app has a portable song identity that cannot bind restored data to the wrong MediaStore item.
- Made lyric replacement atomic and serialized per song, and capped decoded lines, timestamps and parsed entries to resist resource-exhaustion files.
- Added MediaController disconnect/failure recovery with bounded pending actions and retry backoff.
- Pinned GitHub Actions and the Gradle distribution checksum for reproducible CI tooling.
- Moved playback-mode authority into the service so Activity/Controller recreation cannot desynchronize the displayed mode and actual queue behavior.
- Added a Room 1→2 migration, case-insensitive unique playlist names and an ordered membership index.
- Added 52 JVM and Android instrumentation tests across MediaStore, Media3, Room, ViewModel, lyrics, permissions and restored picker state, including API 26/36 emulator CI jobs.

## [1.0.0] - 2026-08-24

### Added

- Initial local music library, background playback, playlists, synchronized lyrics and signed Android release.

[1.1.0]: https://github.com/nishijie6/melody-local/releases/tag/v1.1.0
[1.0.0]: https://github.com/nishijie6/melody-local/releases/tag/v1.0.0
