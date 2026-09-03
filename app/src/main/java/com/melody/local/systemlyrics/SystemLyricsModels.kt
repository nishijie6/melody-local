package com.melody.local.systemlyrics

import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/** The output route used to select a conservative lyric latency estimate. */
enum class AudioOutputRoute {
    SPEAKER,
    WIRED,
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    USB,
    HDMI,
    UNKNOWN,
}

data class SystemLyricSnapshot(
    val songId: Long? = null,
    val currentLine: String = "",
    val nextLine: String = "",
    val isPlaying: Boolean = false,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN,
    val appliedDelayMs: Long = 0L,
    /** Monotonic process-local signal that the cached lyric content was reloaded. */
    val contentRevision: Long = 0L,
)

/**
 * Positive delay values make the lyric appear later. This compensates for an output path whose
 * sound reaches the listener after ExoPlayer's reported position (most notably Bluetooth A2DP).
 */
object LyricsTimingPolicy {
    const val MIN_MANUAL_DELAY_MS = -5_000L
    const val MAX_MANUAL_DELAY_MS = 5_000L

    fun estimatedOutputDelayMs(route: AudioOutputRoute): Long = when (route) {
        AudioOutputRoute.BLUETOOTH_CLASSIC -> 180L
        AudioOutputRoute.BLUETOOTH_LE -> 120L
        AudioOutputRoute.HDMI -> 80L
        AudioOutputRoute.USB -> 30L
        AudioOutputRoute.SPEAKER,
        AudioOutputRoute.WIRED,
        AudioOutputRoute.UNKNOWN,
        -> 0L
    }

    fun appliedDelayMs(
        route: AudioOutputRoute,
        manualDelayMs: Long,
        automaticCompensationEnabled: Boolean,
    ): Long {
        val safeManual = manualDelayMs.coerceIn(MIN_MANUAL_DELAY_MS, MAX_MANUAL_DELAY_MS)
        val automatic = if (automaticCompensationEnabled) estimatedOutputDelayMs(route) else 0L
        return (safeManual + automatic).coerceIn(
            MIN_MANUAL_DELAY_MS,
            MAX_MANUAL_DELAY_MS + estimatedOutputDelayMs(AudioOutputRoute.BLUETOOTH_CLASSIC),
        )
    }

    fun lyricPositionMs(playbackPositionMs: Long, appliedDelayMs: Long): Long =
        max(0L, playbackPositionMs - appliedDelayMs)
}

internal object SystemLyricsRuntime {
    @Volatile
    var latestSnapshot: SystemLyricSnapshot = SystemLyricSnapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(SystemLyricSnapshot) -> Unit>()

    fun publish(snapshot: SystemLyricSnapshot) {
        latestSnapshot = snapshot
        listeners.forEach { listener -> listener(snapshot) }
    }

    fun addListener(listener: (SystemLyricSnapshot) -> Unit) {
        listeners += listener
        listener(latestSnapshot)
    }

    fun removeListener(listener: (SystemLyricSnapshot) -> Unit) {
        listeners -= listener
    }
}
