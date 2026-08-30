# Changelog

All notable changes to this project are documented in this file.

## 1.3.0 - 2026-08-31

### Added

- Added single-video selection through Android's system document picker, with persisted access to only the user-selected local file.
- Added Media3 Transformer audio-only M4A export: AAC input can be remuxed, while other platform-decodable audio is converted to AAC.
- Added editable video-import title, artist and album, plus optional first-frame artwork compressed to a maximum 1024-pixel edge.
- Added a unique foreground WorkManager import with progress, cancellation, process-restart cleanup, explicit failure reasons and collision-safe output under `Music/音澜/视频提取/`.
- Added “汇总歌单歌曲” to move the distinct union of playlist songs into an editable public `Music/音澜/<folder>/` destination.
- Added same-volume MediaStore moves, verified cross-volume copy/delete, API 29 per-item consent, API 30+ write/delete batches of at most 2000 items, and Android 8–9 on-demand write permission.
- Added Room v3 metadata overrides and a persistent relocation journal for prepared, copied, source-deleted and committed states.
- Added 17 automated tests for media-operation rules, Room migration/remapping, metadata/journal persistence, ViewModel authorization/partial completion and real Transformer audio-only output/cancellation cleanup.

### Changed

- Raised the application version to `1.3.0` / Android `versionCode` 5.
- `MusicRepository` and playback notifications now prefer local title, artist, album and artwork overrides created during video import.
- CI now packages the Release and Android-test variants and runs instrumentation on API 26, 29 and 36.

### Fixed

- Playlist relationships, imported lyrics, private artwork and metadata now survive a MediaStore ID change caused by a verified cross-volume move.
- A source song is never deleted when the destination size or SHA-256 differs, and a denied/cancelled operation safely removes copies whose source still exists.
- Interrupted media operations now clean unpublished video exports and pre-delete song copies, or finish idempotent remapping when source deletion already completed.

### Privacy

- Video import does not request `READ_MEDIA_VIDEO`, scan private Bilibili storage, bypass sandboxing, decrypt caches or handle DRM-protected content.
- The app continues to omit Android's `INTERNET` permission and performs all extraction, artwork and relocation work locally.

## 1.2.0 - 2026-08-30

### Added

- Added an explicit stop button beside the main playback controls; stopping also clears the active queue.
- Added restored-state binding for lyric file selection, so a picker result is always imported for the song that launched the picker.

### Changed

- Synchronized the stronger upstream v1.1.0 data, lyrics, playback-session, Room migration, test, CI, privacy and reproducible-build foundations.
- Kept the local warm, light visual design instead of adopting the upstream darker presentation.
- Moved random-order construction to `Dispatchers.Default`; reverse and all non-random mode changes remain constant-time main-thread policy updates.
- Isolated playback-mode requests by generation and queue size so stale random results cannot overwrite a newer mode or a replaced queue.
- Raised the application version to `1.2.0` / Android `versionCode` 4, allowing updates over both upstream code 2 and the prior local code 3 build.

### Fixed

- Prevented large-library random-mode switches from blocking Compose rendering, record rotation or playback controls.
- Prevented rapid random/reverse/order switching from reverting to an older asynchronous result.
- Reapplied the service-owned traversal policy after queue replacement without recreating, pausing, preparing or seeking the current item during a mode-only change.

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
