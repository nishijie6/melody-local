# Privacy

Effective date: 2026-08-31

音澜 is designed as an offline local music player. The application does not request the Android `INTERNET` permission and does not include accounts, advertising, analytics or telemetry.

## Data the app accesses

With your permission, the app reads audio metadata and content URIs exposed by Android MediaStore. This includes information such as song title, artist, album, duration, track number, date added and album-art references.

When you explicitly choose “from video,” Android's system file picker grants the app read access to that one selected document. The app reads it locally to identify and export its first audio track and, when enabled, one decodable video frame. It does not request broad video-library access, enumerate Bilibili or any other application's private cache, bypass Android sandboxing, decrypt proprietary caches, or circumvent DRM.

The app does not upload this information. Audio is held in private cache only while an export is running, then published to the public Music collection or deleted on failure/cancellation.

## Data stored on the device

- Playlists and playlist membership are stored in a local Room database.
- The selected playback mode is stored in local SharedPreferences.
- LRC files that you explicitly import are copied into the app's private `lyrics/` directory so they remain available if the original file moves.
- Titles, artists and albums edited during video import, plus references to compressed first-frame artwork, are stored in the local Room database and private `artwork/` directory.
- Incomplete playlist-file moves are recorded in a local operation journal so the app can remove an uncommitted copy or finish ID remapping after process interruption.
- Video audio exports are written to `Music/音澜/视频提取/`. The “汇总歌单歌曲” action truly moves selected playlist files to `Music/音澜/<chosen folder>/`; after a successful move the old path no longer contains the file.
- Playback state exists in the local Media3 session while the app or playback service is running.

## Android permissions

| Permission | Purpose |
|---|---|
| `READ_MEDIA_AUDIO` | Read local audio on Android 13 and newer |
| `READ_EXTERNAL_STORAGE` | Read local audio on Android 12L and older |
| `WRITE_EXTERNAL_STORAGE` (Android 8–9 only) | Save a video audio export or move playlist files in public Music storage; requested only when starting such an operation |
| `POST_NOTIFICATIONS` | Show background playback controls on Android 13 and newer |
| Foreground media playback | Keep audio playing while the app is in the background |
| Foreground data sync | Keep a user-started video audio export alive with visible progress |

On Android 10, modifying user-owned MediaStore rows may show one system confirmation per song. On Android 11 and newer, Android displays batched write/delete confirmations. These dialogs are owned by the operating system; denial leaves the corresponding source song in place and the app reports a cancelled or failed item.

Declining notification permission does not give the app network access. Declining audio permission prevents the app from showing the local music library.

## System backup and device transfer

The application disables Android cloud backup and device-to-device transfer for its private data. MediaStore numeric IDs are local to one device and can change after a media-database rebuild, so restoring playlists or `<songId>.lrc` files onto another library could attach them to the wrong song.

Playlists, playback preferences and imported lyrics therefore remain on the device where they were created. Export/import with a portable song identity would be required before safe cross-device transfer can be enabled.

## Delete your data

- Remove an imported lyric from the full-screen lyrics menu.
- Delete playlists inside the application.
- Delete exported songs or move them again using any compatible file or music manager. Cancelling an active operation cleans unpublished copies; already completed moves remain at their new public path.
- Clear all app data from Android Settings, or uninstall the application, to remove the app's local database, preferences and private lyric copies from the device.
- Because application backup is disabled, uninstalling or clearing app data permanently removes these local records unless you separately kept the original LRC files.

## Changes and questions

Material privacy-model changes will be documented in this file and in [CHANGELOG.md](CHANGELOG.md). For a privacy question, open a GitHub issue that does not contain private information. Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
