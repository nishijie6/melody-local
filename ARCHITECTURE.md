# Architecture

音澜是一个单模块、离线优先的 Android 应用。它使用 Jetpack Compose 渲染界面、Media3 ExoPlayer 播放本地音频、Media3 Transformer 提取视频音轨、Room 保存歌单和可恢复媒体操作，并把歌词与自定义封面保存在应用私有目录。

## System overview

```text
SAF video URI ── WorkManager/Transformer ──┐
                                           ▼
Android MediaStore ◄── relocation ── MusicRepository ──┐
        ▲                       │                       │
        │                       ▼                       │
        └──────── Room v3 / operation journal ─────────┤
                                │                       ▼
Private lyrics/artwork ◄────────┴────────────── MainViewModel
                                                     │       │
                                                     ▼       ▼
                                                Compose UI  PlayerConnection
                                                               │
                                                               ▼
                                                        MusicService/ExoPlayer
```

## Modules

### Application entry

`MainActivity` owns runtime permission requests, edge-to-edge window configuration and the `MainViewModel`. It refreshes the MediaStore library when permission becomes available or the activity returns to the foreground.

### UI and state

`ui/MelodyApp.kt` contains the Compose screens for the library, playlists, mini player, full-screen player, lyrics and media-operation dialogs. `MainViewModel` is the state boundary between UI and repositories. It depends on narrow `MusicLibrary`, `PlaylistStore`, `LyricsStore`, `PlaybackController`, `VideoAudioExtractor` and `SongRelocationCoordinator` contracts, while its production constructor supplies the real implementations. It exposes `StateFlow` values for songs, filters, playlists, lyrics, playback, video import and file relocation. Both long operations use the shared `MediaOperationState` model: idle, preparing, processing, awaiting system authorization, completed, failed or cancelled.

The UI does not own the ExoPlayer instance. It sends commands through `PlayerConnection` and observes `PlaybackUiState`. The visual layer deliberately uses one warm light color scheme; system status/navigation bars use dark icons to preserve contrast.

### Local music library

`MusicRepository` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`. It keeps rows marked as music with a positive duration and maps their metadata to `Song` values. `SongMetadataStore` overlays user-edited title, artist, album and private artwork before the values reach playback notifications or Compose. The application stores MediaStore IDs and content URIs, not private copies of audio files.

### Video audio import

The UI uses `ACTION_OPEN_DOCUMENT` for one `video/*` URI and persists only the returned read grant. The app does not request `READ_MEDIA_VIDEO` and never enumerates another app's private storage. `VideoAudioImportWorker` validates that a non-DRM audio track exists, removes video with `EditedMediaItem.setRemoveVideo(true)`, and configures Transformer for AAC. AAC input is eligible for transmuxing; other platform-decodable audio is transcoded.

The worker writes to an app cache file first. On Android 10+, it then inserts an `IS_PENDING` MediaStore row under `Music/音澜/视频提取/`; the row is published only after metadata and optional first-frame artwork are durable. Android 8–9 uses a hidden temporary file and a recovery marker before MediaScanner publication. A unique WorkManager name prevents concurrent imports. Startup cleanup removes stale cache files, unpublished MediaStore rows, legacy recovery markers and unreferenced private artwork.

### Playlist file relocation

`SongRelocationCoordinator` obtains the distinct union of every `playlist_songs.songId`, stops playback through the ViewModel, resolves case-insensitive target-name collisions and writes an operation plus all item rows transactionally. Primary-volume songs use `RELATIVE_PATH` updates and preserve IDs. Other-volume songs are copied to the primary volume, checked against source size and SHA-256, then deleted and remapped. Android 8–9 uses equivalent checked filesystem moves/copies.

Android 10 handles `RecoverableSecurityException` one song at a time. Android 11–16 uses `createWriteRequest` and `createDeleteRequest` in batches of at most 2000 URIs. A denial marks only that item or batch as cancelled and continues, so earlier successes remain committed.

The journal distinguishes prepared, copied, source-deleted and committed items. Recovery deletes a destination copy when the source still exists; if deletion already happened, it idempotently completes playlist, lyric, metadata and MediaStore publication remapping. No source is deleted after a read, size or digest failure.

### Playback

`MusicService` owns the ExoPlayer and MediaSession. Media3 supplies background playback, system media controls, audio-focus handling and noisy-output handling.

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

Playlist names are trimmed, limited to 40 characters and protected by a NOCASE unique Room index. Database migration 1→2 preserves duplicate legacy playlists by assigning deterministic suffixes before adding the index. Migration 2→3 adds `song_overrides`, `move_operations` and `move_items` without rewriting existing playlists or membership. Membership uses a `(playlistId, addedAt)` index for ordered reads; ID remapping inserts every replacement before removing the old ID in one Room transaction.

### Lyrics

`LyricsRepository` stores imported files under `filesDir/lyrics/<songId>.lrc`. Imports are limited to 2 MiB. Read, parse, atomic replacement and deletion are serialized per song so an older import cannot resurrect lyrics after a later delete. `LrcParser` supports timed LRC, multiple timestamps per line, `offset`, plain text, UTF-8 and GB18030 fallback decoding.

## Persistence and backup

```text
Room database            playlist definitions and membership
                         metadata overrides and relocation journal
SharedPreferences        selected playback mode
                         active WorkManager/legacy import recovery IDs
filesDir/lyrics          copied LRC files
filesDir/artwork         compressed video-frame covers (max edge 1024 px)
MediaStore               source of audio metadata and content URIs
```

Android cloud backup and device transfer are disabled for application-private data. The manifest uses `allowBackup=false` plus legacy `fullBackupContent` exclusions and Android 12+ `dataExtractionRules` exclusions for both cloud and device transfer. Playlist membership and lyric filenames currently use device-local MediaStore IDs, which are not portable across devices or media-database rebuilds.

## Error boundaries

- MediaStore failures become a library error shown by the UI.
- Duplicate playlist names become user-visible Snackbar messages.
- Invalid or oversized lyric files are rejected without replacing the existing lyric.
- Missing audio, DRM, decoder failure, revoked SAF access and storage failures abort video import and clean unpublished output.
- File relocation never deletes a source until a copy passes size and SHA-256 validation.
- System authorization denial is represented separately from I/O failure and may produce a partial-success summary.
- Missing metadata receives localized fallback labels.
- A missing release keystore produces an unsigned Release variant; CI builds Debug only.

## Tests

JVM tests cover LRC parsing and expansion limits, bounded/atomic writes, queue traversal, playlist validation, AAC decisions, folder/name rules, relocation routing, copy verification and journal recovery decisions. Android instrumentation tests cover MediaStore mapping, Media3 control, Room 1→2→3 migrations, multi-playlist ID remapping, metadata/journal persistence, ViewModel progress/authorization/partial completion and real Transformer output. CI runs device tests on API 26, 29 and 36; notification, lock-screen, OEM document providers and very large removable media libraries still benefit from manual device testing.

## Trade-offs

- The project keeps a single Android module for simple builds, at the cost of a large Compose UI file.
- Media3 shuffle orders keep navigation deterministic without rewriting the playlist, at the cost of a custom reverse `ShuffleOrder` implementation.
- Lyrics are imported manually instead of fetched online, which keeps the app offline and avoids transmitting listening data.
- MediaStore IDs remain the local song identity. The relocation journal makes changes recoverable on one device, but does not make playlists portable across devices or MediaStore database rebuilds.
