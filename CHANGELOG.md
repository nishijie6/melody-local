# Changelog

All notable changes to this project are documented in this file.

## 1.4.0 - 2026-09-04

### Added

- Added local-first lyric discovery: MediaStore-indexed LRC files beside the song, direct children of user-authorized document trees, and common ID3v2, FLAC Vorbis Comment and MP4/M4A embedded lyric tags.
- Added manual LRCLIB search with ranked synchronized/plain-text results, plus an optional high-confidence automatic lookup after local sources fail.
- Added enhanced-LRC word timing and combined original, translation and romanization layers with synchronized scrolling, seeking and word-level karaoke highlighting.
- Added an in-app LRC and timeline editor with current-position timestamp insertion, whole-timeline millisecond shifting, validation and atomic save.
- Added an optional draggable foreground floating lyric with Android overlay consent and persisted position.
- Added synchronized notification lyrics and best-effort lock-screen lyrics. Every supported version receives `displayTitle`/`subtitle` MediaSession metadata while preserving canonical song fields; pre-Android 13 notifications also use the custom Media3 provider, and the final OEM lock-screen layout remains device-dependent.
- Added public audio-route detection for speaker, wired, classic Bluetooth, Bluetooth LE, USB and HDMI, conservative per-route lyric-delay estimates, and a separately persisted `-5000…5000 ms` manual correction for each route.
- Added automated coverage for local/online matching, LRCLIB throttling and rate limits, embedded tags, enhanced/multilingual LRC, editing, system-line selection, output-route classification and lyric settings.

### Changed

- Raised the application version to `1.4.0` / Android `versionCode` 6.
- Adopted lyrics from every source are normalized through the same bounded parser and stored as a private per-song LRC copy; automatic discovery never replaces an existing copy.
- Lyrics now use the same route-adjusted position in the app, floating overlay, notification and lock-screen surfaces.
- Automatic local/embedded/opted-in online discovery now runs from the playback service, shares per-song synchronization with the UI, and refreshes an open lyric panel through a content revision without duplicate requests.
- Explicitly removing lyrics now suppresses automatic rediscovery for that song until the user manually matches, imports, edits or selects a new result.
- Output classification now follows the selected media route: Android 13+ uses attribute-aware routed devices, while older releases combine the selected live-audio route with connected-device detail.

### Fixed

- Local lyric matching now queries the song URI's real display name, so a metadata title such as “歌曲名” no longer prevents `01.mp3` from matching an authorized `01.lrc`; automatic and manual online searches use the same track identity.
- ID3 SYLT imports now retain every field timestamp as bounded enhanced LRC, and ID3v2.4 grouping, data-length and per-frame unsynchronization prefixes are handled or rejected before lyric decoding.
- Made video-import admission atomic across rapid taps and concurrent callers. The candidate ID is committed before enqueue (closing the enqueue/process-death gap) and then reconciled to the unique WorkManager request that `KEEP` retained.
- AAC video tracks now take a true no-forced-encoder transmux path; non-AAC tracks explicitly request AAC, with device tests asserting the reported conversion process and no-frame artwork fallback.
- Video imports now synchronously retain a worker-scoped publication receipt, including expected length and SHA-256, and a staged Android 8–9 recovery record, so a process death after MediaStore publication—or failure to persist the final published state—reuses only a fully reverified output instead of creating a duplicate. A PREPARED import that finishes publication after WorkManager cancellation is reconciled as completed; only an explicitly cancelled work ID may donate its verified `PUBLISHED` receipt to an immediate same-request retry, so intentional later duplicate imports remain possible. Modern MediaStore copies are cancellation-aware, read back and checked by length and SHA-256, and their inserted URI is available to immediate non-cancellable cleanup. Every current external volume must be queryable and free of the inserted primary row's numeric ID before worker-token artwork or Room metadata is written; allocation-only and unjournaled cleanup never deletes by bare ID. Pending cleanup uses an atomic provider-side pending/title condition so it cannot delete a song concurrently made public. Legacy placement is no-replace; cleanup requires the persisted length and SHA-256, verifies a receipt URI's current `DATA` path, binds deletion atomically to `DATA=?`, and awaits non-cancellable MediaScanner completion (including a restart re-scan from `TARGET_READY`) before cleanup, preventing delayed orphan rows. Revoked document queries return an explicit task failure, and cleanup ignores playlist-relocation pending rows.
- Cancellation reconciliation now uses an independent durable key per worker; state polling and every enqueued retry worker enumerate every unresolved cancellation, recovery never consumes another worker's key, and only the exact verified completion returned to the UI clears its key. Cancelling B therefore cannot hide a late publication from cancelled worker A.
- Video metadata writes now persist a compare-and-restore intent before the Room DAO call, including the old override snapshot and worker-owned artwork. Recovery handles a crash immediately after SQL commit without leaving ghost metadata or deleting a later user value. Android 8–9 also verifies the scanner URI's `_ID + DATA` binding before journaling and before/after a provider-side `DATA=?` metadata update.
- Android 8–9 MediaScanner waits are bounded. Timeout preserves `TARGET_READY` and its verified file, releases the scan mutex, and lets a delayed callback promote the durable record for later cleanup; an OEM callback that never arrives no longer leaves the worker permanently non-cancellable.
- Android 8–9 playlist relocation now always uses a verified copy/delete journal path, and compatibility recovery preserves the destination from the former rename crash window instead of risking deletion of the only copy.
- Playlist relocation now retains concrete MediaStore volume URIs, marks newly inserted cross-volume pending rows for crash cleanup, and includes real same-volume, cross-volume and interrupted-operation device coverage.
- Playlist relocation now fails closed when a bare MediaStore ID is ambiguous, its newly created ID collides on another current volume, or any concrete-volume query is inaccessible. It never guesses an Android 10 legacy synthetic URI and keeps playlist/lyric/metadata IDs untouched for those songs. Android 8–9 final paths use no-replace creation, a sidecar never authorizes truncating an existing path, and deletion operates only on an operation-specific, verified source quarantine while retaining the verified temp through commit. Modern deletion verifies the private pending target first and the concrete source last, then confirms source absence and revalidates the destination. `PREPARED` copies resume only into their exact validated pending row, `COPIED` operations recreate deletion consent after process death, and cancellation is durably marked `CANCELLING` before cleanup. A fully checksum-verified modern target is conditionally published and retained instead of being deleted across a racy source-presence check; only incomplete operation-owned pending rows are conditionally removed. Android 8–9 cancellation restores a quarantined source without replacement, and restart remapping never guesses a reused legacy ID. Long copy/hash loops observe cancellation.
- Starting consolidation now checks and claims a durable pending relocation inside the coordinator mutex before any new journal is created. This closes the asynchronous startup-recovery/user-tap race; multiple historical operations are recovered deterministically oldest-first instead of being overwritten.

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
