package com.melody.local.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val dateAddedSeconds: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    /** App-local, non-destructive playback boundary in the original file timeline. */
    val playbackStartMs: Long = 0L,
    /** Null means play to the physical end of the source file. */
    val playbackEndMs: Long? = null,
)

data class SongPlaybackRange(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

internal object SongPlaybackRangeConstraints {
    const val MIN_RETAINED_DURATION_MS = 1_000L

    fun minimumRetainedDurationMs(sourceDurationMs: Long): Long =
        minOf(sourceDurationMs.coerceAtLeast(0L), MIN_RETAINED_DURATION_MS)

    fun isValid(
        sourceDurationMs: Long,
        startMs: Long,
        endMs: Long?,
    ): Boolean {
        if (sourceDurationMs <= 0L) return startMs == 0L && (endMs == null || endMs == 0L)
        val resolvedEndMs = endMs ?: sourceDurationMs
        return startMs >= 0L &&
            resolvedEndMs <= sourceDurationMs &&
            resolvedEndMs - startMs >= minimumRetainedDurationMs(sourceDurationMs)
    }

    fun requireValid(
        sourceDurationMs: Long,
        startMs: Long,
        endMs: Long?,
    ): SongPlaybackRange {
        require(isValid(sourceDurationMs, startMs, endMs)) {
            "裁剪范围无效，至少需要保留 1 秒可播放内容"
        }
        return SongPlaybackRange(startMs, endMs ?: sourceDurationMs)
    }

    /**
     * Sanitizes persisted values against the duration currently reported by MediaStore. This
     * keeps a song playable if its source file was replaced with a shorter version.
     */
    fun normalize(
        sourceDurationMs: Long,
        startMs: Long,
        endMs: Long?,
    ): SongPlaybackRange {
        val safeDurationMs = sourceDurationMs.coerceAtLeast(0L)
        if (safeDurationMs == 0L) return SongPlaybackRange(0L, 0L)
        val minimumDurationMs = minimumRetainedDurationMs(safeDurationMs)
        val safeStartMs = startMs.coerceIn(0L, safeDurationMs - minimumDurationMs)
        val safeEndMs = (endMs ?: safeDurationMs).coerceIn(
            safeStartMs + minimumDurationMs,
            safeDurationMs,
        )
        return SongPlaybackRange(safeStartMs, safeEndMs)
    }
}

fun Song.playbackRange(): SongPlaybackRange = SongPlaybackRangeConstraints.normalize(
    sourceDurationMs = durationMs,
    startMs = playbackStartMs,
    endMs = playbackEndMs,
)

fun Song.playbackDurationMs(): Long = playbackRange().durationMs

fun Song.isPlaybackTrimmed(): Boolean {
    val range = playbackRange()
    return range.startMs > 0L || range.endMs < durationMs.coerceAtLeast(0L)
}

internal fun SongPlaybackRange.persistedEndMs(sourceDurationMs: Long): Long? =
    endMs.takeIf { it < sourceDurationMs.coerceAtLeast(0L) }

fun Long.asDuration(): String {
    if (this <= 0L) return "0:00"
    val totalSeconds = this / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
