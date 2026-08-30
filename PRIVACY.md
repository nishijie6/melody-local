# Privacy

Effective date: 2026-08-25

音澜 is designed as an offline local music player. The application does not request the Android `INTERNET` permission and does not include accounts, advertising, analytics or telemetry.

## Data the app accesses

With your permission, the app reads audio metadata and content URIs exposed by Android MediaStore. This includes information such as song title, artist, album, duration, track number, date added and album-art references.

The app does not upload this information and does not copy your audio files into its private storage.

## Data stored on the device

- Playlists and playlist membership are stored in a local Room database.
- The selected playback mode is stored in local SharedPreferences.
- LRC files that you explicitly import are copied into the app's private `lyrics/` directory so they remain available if the original file moves.
- Playback state exists in the local Media3 session while the app or playback service is running.

## Android permissions

| Permission | Purpose |
|---|---|
| `READ_MEDIA_AUDIO` | Read local audio on Android 13 and newer |
| `READ_EXTERNAL_STORAGE` | Read local audio on Android 12L and older |
| `POST_NOTIFICATIONS` | Show background playback controls on Android 13 and newer |
| Foreground media playback | Keep audio playing while the app is in the background |

Declining notification permission does not give the app network access. Declining audio permission prevents the app from showing the local music library.

## System backup and device transfer

The application disables Android cloud backup and device-to-device transfer for its private data. MediaStore numeric IDs are local to one device and can change after a media-database rebuild, so restoring playlists or `<songId>.lrc` files onto another library could attach them to the wrong song.

Playlists, playback preferences and imported lyrics therefore remain on the device where they were created. Export/import with a portable song identity would be required before safe cross-device transfer can be enabled.

## Delete your data

- Remove an imported lyric from the full-screen lyrics menu.
- Delete playlists inside the application.
- Clear all app data from Android Settings, or uninstall the application, to remove the app's local database, preferences and private lyric copies from the device.
- Because application backup is disabled, uninstalling or clearing app data permanently removes these local records unless you separately kept the original LRC files.

## Changes and questions

Material privacy-model changes will be documented in this file and in [CHANGELOG.md](CHANGELOG.md). For a privacy question, open a GitHub issue that does not contain private information. Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

