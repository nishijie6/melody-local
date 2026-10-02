package com.melody.local.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.melody.local.data.SongPlaybackRange

internal fun SongPlaybackRange.asClippingConfiguration(
    sourceDurationMs: Long,
): MediaItem.ClippingConfiguration = MediaItem.ClippingConfiguration.Builder()
    .setStartPositionMs(startMs)
    .apply {
        if (endMs < sourceDurationMs.coerceAtLeast(0L)) {
            setEndPositionMs(endMs)
        }
    }
    .build()

internal fun Bundle.putPlaybackRange(
    range: SongPlaybackRange,
    sourceDurationMs: Long,
) {
    putLong(EXTRA_SOURCE_DURATION_MS, sourceDurationMs.coerceAtLeast(0L))
    putLong(EXTRA_PLAYBACK_START_MS, range.startMs)
    if (range.endMs < sourceDurationMs.coerceAtLeast(0L)) {
        putLong(EXTRA_PLAYBACK_END_MS, range.endMs)
    } else {
        remove(EXTRA_PLAYBACK_END_MS)
    }
}

internal fun Bundle.sourceDurationMsOrNull(): Long? =
    takeIf { containsKey(EXTRA_SOURCE_DURATION_MS) }
        ?.getLong(EXTRA_SOURCE_DURATION_MS)
        ?.takeIf { it > 0L }

internal fun Bundle.playbackStartMsOrZero(): Long =
    getLong(EXTRA_PLAYBACK_START_MS, 0L).coerceAtLeast(0L)
