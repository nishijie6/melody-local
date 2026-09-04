package com.melody.local.playback

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.melody.local.R
import com.melody.local.systemlyrics.SystemLyricSnapshot
import com.melody.local.systemlyrics.SystemLyricsSettings

/** Places the synchronized current line in the standard Media3 notification's secondary text. */
@OptIn(markerClass = [UnstableApi::class])
internal class LyricsMediaNotificationProvider(
    context: Context,
    private val settings: SystemLyricsSettings,
) : DefaultMediaNotificationProvider(context) {
    @Volatile
    private var snapshot = SystemLyricSnapshot()

    init {
        setSmallIcon(R.drawable.ic_stat_music)
    }

    fun updateSnapshot(value: SystemLyricSnapshot) {
        snapshot = value
    }

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence {
        // Pre-Android 13 notifications keep the canonical song title while the accompanying
        // MediaSession display title is temporarily used by compatible lock-screen surfaces.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return metadata.title?.takeIf { it.isNotBlank() }
                ?: super.getNotificationContentTitle(metadata)
                ?: ""
        }
        return super.getNotificationContentTitle(metadata) ?: ""
    }

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence {
        // Android 13+ renders MediaSession metadata directly; displayTitle carries the lyric there.
        // Keeping the custom secondary text as well would duplicate the same line.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.getNotificationContentText(metadata) ?: ""
        }
        val lyric = snapshot.currentLine
            .takeIf { settings.notificationLyricsEnabled && it.isNotBlank() }
        return lyric ?: super.getNotificationContentText(metadata) ?: ""
    }
}
