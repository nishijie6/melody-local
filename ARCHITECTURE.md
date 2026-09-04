# Architecture

音澜是一个单模块、本地优先的 Android 应用。它使用 Jetpack Compose 渲染界面、Media3 ExoPlayer 播放本地音频、Media3 Transformer 提取视频音轨、Room 保存歌单和可恢复媒体操作，并把采用的歌词与自定义封面保存在应用私有目录。只有用户手动搜索或明确开启自动在线歌词时，歌词发现才访问 LRCLIB。

## System overview

```text
SAF video URI ── WorkManager/Transformer ────────────────┐
                                                        ▼
Android MediaStore ◄── relocation ── MusicRepository ───┐
        │                       │                        │
        │  same-directory LRC  ▼                        │
SAF lyric tree ─────────── LyricsResolver ───────────────┤
        │                ▲       ▲                      │
        │                │       └── LRCLIB (opt-in)    │
        │          embedded tags                        ▼
Private lyrics/artwork ◄──── Room v3 ─────────── MainViewModel
                                                   │       │
                                                   ▼       ▼
                                              Compose UI  PlayerConnection
                                                             │
                                                             ▼
                                                      MusicService/ExoPlayer
                                                             │
                                                             ▼
                                              overlay / notification / lock screen
```

## Modules

### Application entry

`MainActivity` owns runtime permission requests, edge-to-edge window configuration and the `MainViewModel`. It refreshes the MediaStore library when permission becomes available or the activity returns to the foreground.

### UI and state

`ui/MelodyApp.kt` contains the Compose screens for the library, playlists, mini player, full-screen player, lyrics and media-operation dialogs. `MainViewModel` is the state boundary between UI and repositories. It depends on narrow `MusicLibrary`, `PlaylistStore`, `LyricsStore`, `PlaybackController`, `VideoAudioExtractor` and `SongRelocationCoordinator` contracts, and coordinates the production `LyricsResolver` for local, embedded and online discovery. It exposes `StateFlow` values for songs, filters, playlists, parsed lyrics, online-search/editor state, playback, video import and file relocation. Both long media operations use the shared `MediaOperationState` model: idle, preparing, processing, awaiting system authorization, completed, failed or cancelled.

The UI does not own the ExoPlayer instance. It sends commands through `PlayerConnection` and observes `PlaybackUiState`. The visual layer deliberately uses one warm light color scheme; system status/navigation bars use dark icons to preserve contrast.

### Local music library

`MusicRepository` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`. It keeps rows marked as music with a positive duration and maps their metadata to `Song` values. `SongMetadataStore` overlays user-edited title, artist, album and private artwork before the values reach playback notifications or Compose. The application stores MediaStore IDs and content URIs, not private copies of audio files.

### Video audio import

The UI uses `ACTION_OPEN_DOCUMENT` for one `video/*` URI and persists only the returned read grant. The app does not request `READ_MEDIA_VIDEO` and never enumerates another app's private storage. `VideoAudioImportWorker` validates that a non-DRM audio track exists and removes video with `EditedMediaItem.setRemoveVideo(true)`. For AAC input it deliberately leaves Transformer's audio MIME unset so Media3 can transmux the track; for every other platform-decodable input it explicitly requests `AUDIO_AAC` and verifies the resulting conversion mode in device tests.

The worker writes to an app cache file first. On Android 10+, it then inserts an `IS_PENDING` MediaStore row under `Music/音澜/视频提取/`; the row has a worker-specific title marker, and its URI is immediately exposed to the outer non-cancellable cleanup boundary before blocking copy or verification begins. After copying, the worker reopens the pending URI and verifies its complete byte length and SHA-256 against the cache file before any receipt or publication can proceed. Before it can address Room metadata by MediaStore's bare numeric ID, it enumerates every current concrete external volume: every query must succeed, the inserted primary row must be present, and the ID must be absent from every other volume. A collision or inaccessible volume removes only this worker's conditionally matched pending row and never touches existing metadata or artwork. An `ALLOCATED` receipt is synchronously committed before either worker-tokenized private artwork or Room metadata is written; a separate ownership flag is committed after the exact override is written, so unjournaled and allocation-only cleanup has no authority over a bare ID. After those stores are durable the receipt becomes `PREPARED`, recording the worker ID, request fingerprint, output URI, song ID, expected length, expected SHA-256 and Transformer's conversion process; publication upgrades it to `PUBLISHED`. Every same-worker replay and cross-worker adoption re-reads and fully verifies those expected bytes first. If only the final receipt commit fails, the worker treats the already-public row as success and a later worker adopts the same verified output instead of exporting a duplicate. Cleanup uses one provider-side conditional delete with both `IS_PENDING=1` and the exact worker title marker. Consequently, an older worker that publishes between cleanup's query and delete wins atomically and its now-public song, metadata and receipt are preserved. Receipts are retained for 30 days, but unresolved and cancellation-reconciliation receipts are never age-pruned, and pruning removes only private receipts, never published songs. Android 8–9 synchronously journals path reservation, verified staging length and SHA-256, no-replace target placement, MediaStore indexing and durable completion around its worker-tokenized hidden file. It revalidates the target after placement, scanning and publication. Because a platform MediaScanner request cannot be cancelled, publication waits for its callback in a non-cancellable section and restart recovery re-scans a durable `TARGET_READY` file before any deletion; a delayed callback therefore cannot outlive the journal and create a missing-file public row. A still-present staging file proves that no-replace placement did not complete, so cleanup removes only that verified private temp even when the racing target has identical bytes; otherwise a final file is deleted only while its persisted length and digest still match. Both discovery and deletion of a journaled MediaStore row are bound to its current `DATA` path; deletion uses a provider-side `DATA=?` condition and treats zero rows as success only when the row is confirmed absent, preventing query/delete races, database rebuilds or ID reuse from targeting another file. Unverifiable files and journals are preserved. A process-wide mutex makes the active-work check and unique `KEEP` enqueue atomic. The candidate work ID is synchronously persisted before enqueue to close the enqueue/process-death gap and is reconciled to whichever ID `KEEP` retained. Cancellation is also journaled before requesting WorkManager cancellation: if PREPARED must fail forward after WorkManager records `CANCELLED`, `currentState` reports the fully reverified public receipt as completed, while an immediate same-request retry may consume that one explicitly cancelled `PUBLISHED` receipt. Ordinary historical published imports remain repeatable. The ViewModel enters its busy state synchronously before launching the enqueue coroutine. Startup cleanup removes stale cache files, only worker-marked unpublished video rows, provably owned incomplete legacy artifacts and unreferenced private artwork; failed row or artwork deletion keeps metadata and its receipt for recovery.

Cancellation recovery additionally stores one independent unresolved key per worker. State reconciliation and every worker started by enqueue enumerate the complete set; a recovery worker never consumes another worker's key, and only UI-facing `currentState()` reconciliation removes the exact verified completion it returns. A later cancellation therefore cannot hide an earlier worker that publishes late. Enqueue remains allowed when a late publication is waiting so the queued worker can surface or adopt it in the same UI session.

Room writes use a durable compare-and-restore intent: before `put`, the receipt records the exact expected override, worker-owned artwork and complete previous override snapshot. If the DAO commit wins a process-death or cancellation race, recovery deletes a newly created exact match or restores the exact previous value; a third value is treated as a later user change and is preserved with the receipt. On Android 8–9, both the initial scanner callback and final metadata update are bound to the journaled `_ID + DATA` pair, including a provider-side `DATA=?` update and post-update verification. MediaScanner waits are capped at 60 seconds. A timeout releases the process mutex but retains `TARGET_READY`, the verified target and an outstanding callback token; a late callback can durably promote the record to `MEDIA_INDEXED`, while a never-returning OEM callback cannot hang later work.

### Playlist file relocation

`SongRelocationCoordinator` obtains the distinct union of every `playlist_songs.songId`, stops playback through the ViewModel, resolves case-insensitive target-name collisions and writes an operation plus all item rows transactionally. MediaStore discovery enumerates concrete volumes and accepts an ID only when exactly one mounted-volume row owns it; a collision becomes an explicit per-song failure and cannot remap playlists, metadata or lyrics. A provider exception or null cursor from any volume makes the whole enumeration inaccessible rather than absent, so a coincidentally visible same-ID row can never become the source. The newly created destination ID receives the same all-volume uniqueness check before any association remap. Recovery on Android 11+ also upgrades old journals that still contain a synthetic `content://media/external/...` URI, but only after every recently seen volume is mounted and a size/checksum-qualified candidate is unique. Android 10 cannot prove recent detached-volume identity, so legacy synthetic journals are deliberately left unresolved. No synthetic URI is ever used as an authorization or mutation target. Primary-volume songs use `RELATIVE_PATH` updates and preserve IDs. Other-volume songs are copied to the primary volume, checked against source size and SHA-256, then deleted and remapped. Android 8–9 always uses a verified temporary copy followed by atomic no-replace target creation and source deletion; it deliberately avoids moving the only file before a recoverable journal state exists. Its cleanup addresses only the exact `operationId + oldSongId` temporary path. A sidecar does not bind an inode, so an existing final path is never truncated or overwritten during recovery even when its marker still matches; source-unavailable recovery may create only an absent path with `CREATE_NEW`. If the source disappears or becomes inaccessible, a verified operation temporary is retained (and can reconstruct an absent final target) instead of being deleted speculatively.

Android 10 handles `RecoverableSecurityException` one song at a time. Android 11–16 uses `createWriteRequest` and `createDeleteRequest` in batches of at most 2000 URIs. A denial marks only that item or batch as cancelled and continues, so earlier successes remain committed.

The journal distinguishes prepared, copied, source-deleted and committed items. `start()` and `recover()` share the coordinator mutex, and `start()` queries durable pending operations before validating or creating any new operation; if historical records are multiple, they are claimed oldest-first with an ID tie-break and the UI is told that earlier work is being recovered. This prevents the asynchronous startup recovery/user-tap race from allocating a second journal or destination. Cross-volume pending rows carry an operation/item marker until their returned URI is journaled, allowing recovery to remove the otherwise unreferenced row after a crash between `MediaStore.insert` and the journal write. A journaled app-owned pending row can be reopened after process death only after its exact ID, primary volume, path, name, pending flag and operation marker/title are revalidated; a `COPIED` item remains resumable and recreates the platform delete-consent request. Cancellation first persists a `CANCELLING` operation state, which recovery can only continue toward termination and never convert back to `MOVING`. A checksum-verified destination is never deleted based on a non-atomic source-presence observation: an exact modern pending row is conditionally published and reverified, while a legacy visible target is retained; the terminal cancelled item keeps its URI/hash so later consolidations are not blocked. Only an incomplete, operation-owned pending target may be conditionally removed while the source is still present. A missing source promotes a checksum-verified destination to `SOURCE_DELETED`; an inaccessible source preserves or publishes the verified destination instead of guessing or deleting. Cleanup failures are recorded with the same recovery references. For modern cross-volume moves, the app-owned `IS_PENDING` target is hashed first, the concrete source is hashed last immediately before provider deletion, and deletion is followed by an explicit source-absence check plus another destination verification before remapping. Android 8–9 keeps the verified operation temp, atomically moves the source path without replacement into an operation-specific same-directory quarantine, verifies both quarantine and target, and deletes only that verified quarantine; cancellation during any post-quarantine hash restores the source without replacement, or persists a failure/source-deleted recovery state non-cancellably. The temp remains until target post-verification and metadata commit. If deletion already happened, recovery verifies and retains the destination and idempotently completes playlist, lyric, metadata and MediaStore publication remapping. Legacy ID preservation is attempted only in the uninterrupted call with an exact provider-side old `DATA` and size predicate; restart recovery scans and adopts an `_ID + DATA` verified target instead of guessing a reused bare ID. `IS_PENDING=0` must update exactly one row before the item can become `COMMITTED`; otherwise it remains recoverable in `SOURCE_DELETED`/`COMMITTING`. Compatibility recovery also recognizes the former Android 8–9 rename window and never deletes a destination that has become the indexed or only surviving file. No source is deleted after a read, size or digest failure, and long copy/hash loops observe both user cancellation and coroutine cancellation.

The current schema still keys playlist membership, lyric files and metadata overrides by a bare `Long` MediaStore ID rather than `(volumeName, id)`. Runtime all-volume uniqueness checks prevent known current collisions from being mutated or remapped, but Android 10 exposes no recent-detached-volume list; therefore an ID collision involving a physically removed historical volume cannot be reconstructed automatically. Legacy synthetic journals are left unresolved on Android 10, and a future schema migration to volume-qualified song identity would be required to eliminate that platform limitation completely.

### Playback

`MusicService` owns the ExoPlayer and MediaSession. Media3 supplies background playback, system media controls, audio-focus handling and noisy-output handling.

`SystemLyricsCoordinator` runs beside the service-owned player. It loads the current song's private lyric copy and, when none exists, performs the same local-first automatic discovery from service scope so discovery continues after the Activity stops. The UI and service share one process-wide repository/resolver, per-song locks and a monotonic content revision, preventing duplicate network requests and stale UI state. The coordinator samples the active line while playing, applies the configured output-route delay and publishes only when the visible system-lyrics snapshot changes. On every supported Android version, `MusicService` exposes the line through the MediaSession item's `displayTitle`/`subtitle` while preserving title, artist and album; the original display metadata is restored when the line is blank, the setting is disabled, the queue advances or playback is stopped. Before Android 13, the custom notification provider additionally keeps the canonical song title in the notification and places the current line in secondary text. Android 13 and newer render the system media card directly from session metadata. Whether a pre-Android 13 OEM lock screen consumes those display fields, and the final layout on every version, remain controlled by Android and the device manufacturer.

The optional `FloatingLyricsService` is a user-enabled special-use foreground service. It requires Android's overlay consent, shows a draggable two-line light overlay, persists its screen position and can be closed without stopping playback. Notification/lock-screen lyrics and the overlay read the same snapshot, so they do not maintain competing timelines.

`AudioOutputMonitor` observes only public Android routing APIs and classifies the selected media output as speaker, wired, classic Bluetooth, Bluetooth LE, USB, HDMI or unknown. Android 13+ uses `AudioManager.getAudioDevicesForAttributes` for media attributes; Android 8–12 combines the selected live-audio `MediaRouter` route with connected-device detail, so a paired but inactive Bluetooth device cannot override the active speaker route. `LyricsTimingPolicy` applies a conservative per-route estimate; it does not measure Bluetooth codec or hardware latency. A separately persisted `-5000…5000 ms` manual correction for each route is added to that estimate, allowing device-specific calibration.

`MusicService` owns the active playback mode and ExoPlayer traversal policy. `PlayerConnection` only sends Media3 player commands and a custom mode command, so Activity/ViewModel recreation cannot lose queue authority.

| Mode | ExoPlayer traversal | Repeat policy |
|---|---|---|
| Sequential | Canonical | Off |
| Loop | Canonical | All |
| Random | `DefaultShuffleOrder` | All |
| Single | Canonical | One |
| Reverse | `ReverseShuffleOrder` | All |

The Media3 playlist always remains canonical. Mode changes update only shuffle traversal and repeat flags inside `MusicService`; the current MediaItem, position and buffer are never replaced. Reverse and all non-random changes are O(1). Random setup is O(n), but its shuffle arrays are built on `Dispatchers.Default`, outside the main/UI thread. A monotonically increasing request generation and a queue-size check discard stale random results, so rapid mode changes and queue replacement cannot apply an obsolete traversal policy.

### Playlists

Room stores playlists in `playlists` and membership in `playlist_songs`. The join table uses `(playlistId, songId)` as its primary key, so a song cannot appear twice in one playlist. A foreign key removes memberships when their playlist is deleted.

Playlist names are trimmed, limited to 40 characters and protected by a NOCASE unique Room index. Database migration 1→2 preserves duplicate legacy playlists by assigning deterministic suffixes before adding the index. Migration 2→3 adds `song_overrides`, `move_operations` and `move_items` without rewriting existing playlists or membership. Membership uses a `(playlistId, addedAt)` index for ordered reads; ID remapping removes old memberships and inserts their replacements inside one Room transaction, so either the complete mapping commits or the original memberships remain.

### Lyrics

The automatic path through `LyricsResolver` runs only when a song has no private lyric copy; manual online search can also be requested from the lyric menu. Automatic resolution is deliberately local-first:

1. Query MediaStore for readable `.lrc` rows with the same `RELATIVE_PATH` as the audio item (or the legacy filesystem parent on Android 8–9).
2. Search the direct children of directories the user explicitly selected with `ACTION_OPEN_DOCUMENT_TREE`; persisted read grants provide the scoped-storage fallback when a document provider does not index LRC in MediaStore.
3. Read common bounded embedded tags from the already-authorized audio URI: ID3v2 `USLT`/`SYLT`/lyrics `TXXX`, FLAC Vorbis lyrics fields, and MP4/M4A `©lyr` or lyrics freeform fields.
4. Search LRCLIB only after local sources fail and only for a manual search or when automatic online lookup has been opted into. The automatic setting defaults to off; a high-confidence result is required for automatic application, while manual search presents ranked choices.

Local and online matching normalizes file/title/artist names and uses explicit confidence thresholds. Existing private lyrics are never overwritten by automatic discovery. LRCLIB requests are serialized with a minimum interval, respect `Retry-After`, and surface rate limits or network failures to the UI.

`LyricsRepository` stores every adopted source under `filesDir/lyrics/<songId>.lrc`, including imported, sidecar, embedded and downloaded lyrics. Files are limited to 2 MiB. Read, parse, atomic replacement, editing and deletion are serialized per song so an older operation cannot resurrect lyrics after a later delete. An explicit removal creates a private `<songId>.no-auto` marker; automatic adoption checks that marker atomically with file absence, while a later manual match/import/edit clears it. Both files follow a MediaStore ID remap during playlist relocation. `LrcParser` supports timed LRC, multiple timestamps per line, `offset`, plain text, UTF-8 and GB18030 fallback decoding. Enhanced `<mm:ss.xx>` tags become word-timed segments; equal-timestamp lines are grouped as original, romanization, translation or alternate layers using explicit markers when present and deterministic conventions otherwise. The Compose renderer highlights word segments and displays the available layers together.

The in-app editor operates on the stored LRC source. It can insert the adjusted current playback timestamp at the cursor, shift both line and enhanced word timestamps by a millisecond delta, validate the result and save through the repository's atomic replacement path.

## Persistence and backup

```text
Room database            playlist definitions and membership
                         metadata overrides and relocation journal
SharedPreferences        selected playback mode
                         active WorkManager/legacy import recovery IDs
                         lyric discovery preferences and persisted SAF tree URIs
                         overlay/notification settings and per-output-route lyric delays
filesDir/lyrics          adopted local, embedded, downloaded or edited LRC text
                         per-song automatic-rediscovery suppression markers
filesDir/artwork         compressed video-frame covers (max edge 1024 px)
MediaStore               source of audio metadata and content URIs
```

Android cloud backup and device transfer are disabled for application-private data. The manifest uses `allowBackup=false` plus legacy `fullBackupContent` exclusions and Android 12+ `dataExtractionRules` exclusions for both cloud and device transfer. Playlist membership and lyric filenames currently use device-local MediaStore IDs, which are not portable across devices or media-database rebuilds.

## Error boundaries

- MediaStore failures become a library error shown by the UI.
- Duplicate playlist names become user-visible Snackbar messages.
- Invalid or oversized lyric files are rejected without replacing the existing lyric.
- Missing or low-confidence local lyrics leave the current song unchanged; an unavailable SAF grant is treated as a local-source miss.
- LRCLIB network, service and rate-limit failures are shown without deleting or replacing local lyrics.
- Missing audio, DRM, decoder failure, revoked SAF access and storage failures abort video import and clean unpublished output.
- File relocation never deletes a source until a copy passes size and SHA-256 validation.
- System authorization denial is represented separately from I/O failure and may produce a partial-success summary.
- Missing metadata receives localized fallback labels.
- A missing release keystore produces an unsigned R8 Release variant; CI builds that variant and the Debug Android-test package without receiving signing secrets.

## Tests

JVM tests cover LRC/enhanced-LRC parsing, multilingual grouping, embedded tag extraction, local and LRCLIB matching, request throttling, timeline editing, output-route timing, bounded/atomic writes, queue traversal, playlist validation and media-operation recovery rules. Android instrumentation tests cover lyric persistence and system-lyrics settings in addition to MediaStore mapping, Media3 control, Room 1→2→3 migrations, multi-playlist ID remapping, metadata/journal persistence, ViewModel state and real Transformer output. CI runs device tests on API 26, 29 and 36; actual notification/lock-screen layouts, overlay policies, OEM document providers and Bluetooth codec latency still require representative-device testing.

## Trade-offs

- The project keeps a single Android module for simple builds, at the cost of a large Compose UI file.
- Media3 shuffle orders keep navigation deterministic without rewriting the playlist, at the cost of a custom reverse `ShuffleOrder` implementation.
- Lyrics discovery is local-first and automatic online lookup is opt-in. Manual/opted-in LRCLIB matching improves coverage at the cost of sending search metadata to a third-party service; audio and existing local lyric content are never uploaded.
- Android does not expose reliable end-to-end latency for every Bluetooth codec/device combination. Route estimates improve common cases, while per-route manual correction remains the authoritative user calibration.
- MediaStore IDs remain the local song identity. The relocation journal makes changes recoverable on one device, but does not make playlists portable across devices or MediaStore database rebuilds.
