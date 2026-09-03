package com.melody.local.lyrics

import java.util.Locale

/**
 * Pure helpers used by the in-app lyric editor. Both regular LRC timestamps
 * (`[mm:ss.xx]`) and enhanced-LRC word timestamps (`<mm:ss.xx>`) are handled.
 */
object LyricsTimelineEditor {
    private val timestamp = Regex("""([<\[])(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?([>\]])""")

    fun formatTimestamp(positionMs: Long, wordTimestamp: Boolean = false): String {
        val safePosition = positionMs.coerceAtLeast(0L)
        val minutes = safePosition / 60_000L
        val seconds = (safePosition % 60_000L) / 1_000L
        val centiseconds = (safePosition % 1_000L) / 10L
        val opening = if (wordTimestamp) '<' else '['
        val closing = if (wordTimestamp) '>' else ']'
        return String.format(
            Locale.ROOT,
            "%c%02d:%02d.%02d%c",
            opening,
            minutes,
            seconds,
            centiseconds,
            closing,
        )
    }

    /** Inserts a line timestamp at [cursor], retaining the caller's selection position. */
    fun insertTimestamp(text: String, cursor: Int, positionMs: Long): EditResult {
        val safeCursor = cursor.coerceIn(0, text.length)
        val tag = formatTimestamp(positionMs)
        return EditResult(
            text = text.substring(0, safeCursor) + tag + text.substring(safeCursor),
            cursor = safeCursor + tag.length,
        )
    }

    /**
     * Moves every line and word timestamp by [deltaMs]. Negative results are clamped to zero.
     * Metadata such as `[offset:...]` is intentionally left untouched.
     */
    fun shiftAll(text: String, deltaMs: Long): String = timestamp.replace(text) { match ->
        val opening = match.groupValues[1].single()
        val closing = match.groupValues[5].single()
        // Only accept matching LRC bracket pairs so arbitrary text like "[01:02>" is preserved.
        if ((opening == '[' && closing != ']') || (opening == '<' && closing != '>')) {
            return@replace match.value
        }
        val fraction = match.groupValues[4]
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.toLong()
            else -> 0L
        }
        val original = match.groupValues[2].toLong() * 60_000L +
            match.groupValues[3].toLong() * 1_000L + fractionMs
        formatTimestamp(
            positionMs = (original + deltaMs).coerceAtLeast(0L),
            wordTimestamp = opening == '<',
        )
    }
}

data class EditResult(
    val text: String,
    val cursor: Int,
)
