# Privacy

Effective date: 2026-09-04

音澜 is designed as a local-first music player. It does not include accounts, advertising, analytics or telemetry. The application requests Android's `INTERNET` permission only for LRCLIB lyric search and download; automatic online matching is disabled by default, so the app does not contact LRCLIB unless you manually search or explicitly enable that setting.

## Data the app accesses

With your permission, the app reads audio metadata and content URIs exposed by Android MediaStore. This includes information such as song title, artist, album, duration, track number, date added and album-art references.

When a song does not yet have a private lyric copy, the app may query MediaStore for readable LRC files in the same indexed directory and read supported lyric tags embedded in that audio file. Some Android document providers do not index LRC files. If you use “授权歌词目录,” the system directory picker grants persistent read access to the folder you select; automatic matching scans only its direct children for supported lyric documents and does not recursively enumerate unrelated folders. You can clear these directory grants from lyric settings.

When you explicitly choose “from video,” Android's system file picker grants the app read access to that one selected document. The app reads it locally to identify and export its first audio track and, when enabled, one decodable video frame. It does not request broad video-library access, enumerate Bilibili or any other application's private cache, bypass Android sandboxing, decrypt proprietary caches, or circumvent DRM.

Local audio, video frames and existing lyric text are never uploaded. Audio is held in private cache only while an export is running, then published to the public Music collection or deleted on failure/cancellation.

If you manually search LRCLIB, the app sends your search keywords or the current song title and available artist/album fields over HTTPS. If automatic online matching is enabled, the same structured search fields are sent only after local and embedded sources do not produce a safe match. Playback duration is used locally to rank returned records; the current implementation does not include it in the search URL. Selecting or automatically accepting a result sends that LRCLIB record ID to download it. Like any Internet service, LRCLIB can receive the connection IP address and the app's identifying User-Agent. Consult [LRCLIB](https://lrclib.net) for the service's own terms and policies. Existing local lyrics are not sent to LRCLIB and are not overwritten by automatic online results.

## Data stored on the device

- Playlists and playlist membership are stored in a local Room database.
- The selected playback mode is stored in local SharedPreferences.
- Lyrics adopted from manual import, same-directory matching, embedded tags or LRCLIB are stored as LRC text in the app's private `lyrics/` directory. In-app edits atomically replace this private copy. Removing a lyric stores a zero-content per-song marker in the same private directory to prevent automatic rediscovery until you manually match, import, edit or choose a new result.
- Persisted system-directory read grants and lyric discovery preferences are stored locally; automatic local/embedded matching defaults on, while automatic LRCLIB matching defaults off.
- Floating-lyrics, notification-lyrics and automatic-delay preferences, overlay position, and the manual delay for each output-route category are stored in local preferences. The overlay defaults off; notification lyrics and automatic route estimates default on and can be disabled.
- Titles, artists and albums edited during video import, plus references to compressed first-frame artwork, are stored in the local Room database and private `artwork/` directory.
- Incomplete playlist-file moves are recorded in a local operation journal so the app can remove an uncommitted copy or finish ID remapping after process interruption.
- Video audio exports are written to `Music/音澜/视频提取/`. The “汇总歌单歌曲” action truly moves selected playlist files to `Music/音澜/<chosen folder>/`; after a successful move the old path no longer contains the file.
- Playback state exists in the local Media3 session while the app or playback service is running.

## Android permissions

| Permission | Purpose |
|---|---|
| `READ_MEDIA_AUDIO` | Read local audio on Android 13 and newer |
| `READ_EXTERNAL_STORAGE` | Read local audio on Android 12L and older |
| `INTERNET` | Search and download lyrics from LRCLIB only after a manual request or explicit automatic-online opt-in |
| `WRITE_EXTERNAL_STORAGE` (Android 8–9 only) | Save a video audio export or move playlist files in public Music storage; requested only when starting such an operation |
| `POST_NOTIFICATIONS` | Show background playback controls and, when enabled, the current lyric on Android 13 and newer |
| Display over other apps | Show the optional draggable floating lyric; granted from Android's special-access screen and disabled by default |
| Foreground media playback | Keep audio playing while the app is in the background |
| Foreground data sync | Keep a user-started video audio export alive with visible progress |
| Foreground special use | Keep the user-enabled floating lyric visible above other apps with a persistent low-priority service notification |

On Android 10, modifying user-owned MediaStore rows may show one system confirmation per song. On Android 11 and newer, Android displays batched write/delete confirmations. These dialogs are owned by the operating system; denial leaves the corresponding source song in place and the app reports a cancelled or failed item.

Declining notification permission does not give the app network access. Declining audio permission prevents the app from showing the local music library.

When notification/lock-screen lyrics are enabled, the current lyric line is exposed to Android System UI through Media3 `displayTitle`/`subtitle` metadata on every supported Android version. Before Android 13 the app also places it in notification secondary text while retaining the canonical song title; Android 13 and newer build the system media card directly from session metadata. The operating system, connected media controllers and device-manufacturer UI determine where that metadata is visible, especially on older lock screens. Disabling the setting restores the original display metadata.

Automatic output-delay compensation reads only the output device types available through Android's public audio-route API. It does not capture audio, access a microphone, inspect Bluetooth codec traffic or measure true end-to-end latency. Classic Bluetooth, Bluetooth LE, HDMI and USB receive conservative category estimates, and any per-route manual correction remains on the device.

## System backup and device transfer

The application disables Android cloud backup and device-to-device transfer for its private data. MediaStore numeric IDs are local to one device and can change after a media-database rebuild, so restoring playlists or `<songId>.lrc` files onto another library could attach them to the wrong song.

Playlists, playback preferences and adopted lyrics therefore remain on the device where they were created. Export/import with a portable song identity would be required before safe cross-device transfer can be enabled.

## Delete your data

- Remove the current stored lyric from the full-screen lyrics menu, regardless of whether it came from import, matching, an embedded tag or LRCLIB.
- Clear authorized lyric directories or disable local, embedded, online, overlay and notification lyric features from lyric settings. Android's Settings can also revoke a directory or overlay grant.
- Delete playlists inside the application.
- Delete exported songs or move them again using any compatible file or music manager. Cancelling an active operation cleans unpublished copies; already completed moves remain at their new public path.
- Clear all app data from Android Settings, or uninstall the application, to remove the app's local database, preferences and private lyric copies from the device.
- Because application backup is disabled, uninstalling or clearing app data permanently removes these local records unless you separately kept the original LRC files.

## Changes and questions

Material privacy-model changes will be documented in this file and in [CHANGELOG.md](CHANGELOG.md). For a privacy question, open a GitHub issue that does not contain private information. Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
