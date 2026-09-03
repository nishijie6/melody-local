package com.melody.local.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LyricsTimelineEditorTest {
    @Test
    fun formatsAndInsertsCurrentPlaybackTimestamp() {
        assertEquals("[01:02.34]", LyricsTimelineEditor.formatTimestamp(62_349L))
        assertEquals("<00:00.00>", LyricsTimelineEditor.formatTimestamp(-5L, wordTimestamp = true))

        val result = LyricsTimelineEditor.insertTimestamp("一行歌词", 0, 1_230L)
        assertEquals("[00:01.23]一行歌词", result.text)
        assertEquals(10, result.cursor)
    }

    @Test
    fun shiftsLineAndEnhancedWordTimestampsTogether() {
        val source = "[00:01.50]<00:01.50>你<00:02.00>好\n[offset:-200]"

        assertEquals(
            "[00:01.75]<00:01.75>你<00:02.25>好\n[offset:-200]",
            LyricsTimelineEditor.shiftAll(source, 250L),
        )
    }

    @Test
    fun negativeShiftClampsAtZeroAndPreservesMalformedPairs() {
        assertEquals(
            "[00:00.00]开始 [00:01.00>原样",
            LyricsTimelineEditor.shiftAll("[00:00.50]开始 [00:01.00>原样", -2_000L),
        )
    }

    @Test
    fun timestampDigitsRemainAsciiUnderNonLatinDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("[01:02.34]", LyricsTimelineEditor.formatTimestamp(62_349L))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
