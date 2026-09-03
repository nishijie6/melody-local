# Changelog

All notable changes to this project are documented in this file.

## 1.4.0 - 2026-09-04

### Added

- Added local-first lyric discovery: MediaStore-indexed LRC files beside the song, direct children of user-authorized document trees, and common ID3v2, FLAC Vorbis Comment and MP4/M4A embedded lyric tags.
- Added manual LRCLIB search with ranked synchronized/plain-text results, plus an optional high-confidence automatic lookup after local sources fail.
- Added enhanced-LRC word timing and combined original, translation and romanization layers with synchronized scrolling, seeking and word-level karaoke highlighting.
- Added an in-app LRC and timeline editor with current-position timestamp insertion, whole-timeline millisecond shifting, validation and atomic save.
- Added an optional draggable foreground floating lyric with Android overlay consent and persisted position.
- Added synchronized notification and lock-screen lyrics. Pre-Android 13 notifications use the custom Media3 provider; Android 13+ System UI uses supported `displayTitle`/`subtitle` session metadata while preserving canonical song fields.
- Added public audio-route detection for speaker, wired, classic Bluetooth, Bluetooth LE, USB and HDMI, conservative per-route lyric-delay estimates, and a separately persisted `-5000…5000 ms` manual correction for each route.
- Added automated coverage for local/online matching, LRCLIB throttling and rate limits, embedded tags, enhanced/multilingual LRC, editing, system-line selection, output-route classification and lyric settings.

### Changed

- Raised the application version to `1.4.0` / Android `versionCode` 6.
- Adopted lyrics from every source are normalized through the same bounded parser and stored as a private per-song LRC copy; automatic discovery never replaces an existing copy.
- Lyrics now use the same route-adjusted position in the app, floating overlay, notification and lock-screen surfaces.
- Automatic local/embedded/opted-in online discovery now runs from the playback service, shares per-song synchronization with the UI, and refreshes an open lyric panel through a content revision without duplicate requests.
- Explicitly removing lyrics now suppresses automatic rediscovery for that song until the user manually matches, imports, edits or selects a new result.
- Output classification now follows the selected media route: Android 13+ uses attribute-aware routed devices, while older releases combine the selected live-audio route with connected-device detail.

### Privacy

- Added Android's `INTERNET` permission exclusively for LRCLIB lyric search/download. Automatic online lookup is off by default; manual search or explicit opt-in is required before a request is sent.
- LRCLIB receives search keywords or current title and available artist/album metadata, followed by a selected record ID. Local audio and existing lyrics are never uploaded.
- Additional lyric folders require explicit Android document-tree access and only their direct children are scanned. Floating lyrics remain disabled until the user enables them and grants Android overlay access.
- Bluetooth timing is a conservative estimate based on public output-route categories, not a measurement of codec or device latency; per-route manual calibration remains available.

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
