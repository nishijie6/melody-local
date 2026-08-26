# Architecture

音澜是一个单模块、离线优先的 Android 应用。它使用 Jetpack Compose 渲染界面、Media3 ExoPlayer 播放本地音频、Room 保存歌单，并把用户导入的歌词复制到应用私有目录。

## System overview

```text
Android MediaStore
        │
        ▼
MusicRepository ───────────────┐
                              │
Room ◄── PlaylistRepository ───┤
                              ▼
Private lyrics files ◄── LyricsRepository
                              │
                              ▼
                        MainViewModel
                         │          │
                         ▼          ▼
                    Compose UI   PlayerConnection
                                      │ MediaController
                                      ▼
                               MusicService
                                      │
                                      ▼
                                  ExoPlayer
```

## Modules

### Application entry

`MainActivity` owns runtime permission requests, edge-to-edge window configuration and the `MainViewModel`. It refreshes the MediaStore library when permission becomes available or the activity returns to the foreground.

### UI and state

`ui/MelodyApp.kt` contains the Compose screens for the library, playlists, mini player, full-screen player and lyrics. `MainViewModel` is the state boundary between UI and repositories. It depends on narrow `MusicLibrary`, `PlaylistStore`, `LyricsStore` and `PlaybackController` contracts, while its production constructor supplies the real implementations. It exposes `StateFlow` values for songs, filters, playlists, lyrics and playback.

The UI does not own the ExoPlayer instance. It sends commands through `PlayerConnection` and observes `PlaybackUiState`.

### Local music library

`MusicRepository` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`. It keeps rows marked as music with a positive duration and maps their metadata to `Song` values. The application stores MediaStore IDs and content URIs, not copies of audio files.

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

The Media3 playlist always remains canonical. Mode changes update only shuffle traversal and repeat flags inside `MusicService`; the current MediaItem, position and buffer are never replaced. Random setup is O(n) and reverse setup is O(1), with one controller-to-session command per mode change.

### Playlists

Room stores playlists in `playlists` and membership in `playlist_songs`. The join table uses `(playlistId, songId)` as its primary key, so a song cannot appear twice in one playlist. A foreign key removes memberships when their playlist is deleted.

Playlist names are trimmed, limited to 40 characters and protected by a NOCASE unique Room index. Database migration 1→2 preserves duplicate legacy playlists by assigning deterministic suffixes before adding the index. Membership uses a `(playlistId, addedAt)` index for ordered reads.

### Lyrics

`LyricsRepository` stores imported files under `filesDir/lyrics/<songId>.lrc`. Imports are limited to 2 MiB. Read, parse, atomic replacement and deletion are serialized per song so an older import cannot resurrect lyrics after a later delete. `LrcParser` supports timed LRC, multiple timestamps per line, `offset`, plain text, UTF-8 and GB18030 fallback decoding.

## Persistence and backup

```text
Room database            playlist definitions and membership
SharedPreferences        selected playback mode
filesDir/lyrics          copied LRC files
MediaStore               source of audio metadata and content URIs
```

Android cloud backup and device transfer are disabled for application-private data. The manifest uses `allowBackup=false` plus legacy `fullBackupContent` exclusions and Android 12+ `dataExtractionRules` exclusions for both cloud and device transfer. Playlist membership and lyric filenames currently use device-local MediaStore IDs, which are not portable across devices or media-database rebuilds.

## Error boundaries

- MediaStore failures become a library error shown by the UI.
- Duplicate playlist names become user-visible Snackbar messages.
- Invalid or oversized lyric files are rejected without replacing the existing lyric.
- Missing metadata receives localized fallback labels.
- A missing release keystore produces an unsigned Release variant; CI builds Debug only.

## Tests

JVM tests cover LRC parsing and expansion limits, bounded/atomic import writes, queue traversal policies, duration formatting and playlist validation. Android instrumentation tests cover MediaStore query mapping, Media3 mode changes and the `PlayerConnection` control chain, real Room queries/migration, ViewModel success/error states, serialized lyric persistence, permission UI and lyric-picker restoration. Notification, lock-screen and real audio-focus behavior still benefit from manual device testing.

## Trade-offs

- The project keeps a single Android module for simple builds, at the cost of a large Compose UI file.
- Media3 shuffle orders keep navigation deterministic without rewriting the playlist, at the cost of a custom reverse `ShuffleOrder` implementation.
- Lyrics are imported manually instead of fetched online, which keeps the app offline and avoids transmitting listening data.
