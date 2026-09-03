package com.melody.local.systemlyrics

import com.melody.local.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingPolicyTest {
    @Test
    fun automaticLatencyUsesConservativeRouteProfiles() {
        assertEquals(180L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.BLUETOOTH_CLASSIC))
        assertEquals(120L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.BLUETOOTH_LE))
        assertEquals(80L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.HDMI))
        assertEquals(30L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.USB))
        assertEquals(0L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.WIRED))
        assertEquals(0L, LyricsTimingPolicy.estimatedOutputDelayMs(AudioOutputRoute.SPEAKER))
    }

    @Test
    fun positiveDelayShowsLyricsLaterAndManualValuesAreBounded() {
        val delay = LyricsTimingPolicy.appliedDelayMs(
            route = AudioOutputRoute.BLUETOOTH_CLASSIC,
            manualDelayMs = 250L,
            automaticCompensationEnabled = true,
        )
        assertEquals(430L, delay)
        assertEquals(9_570L, LyricsTimingPolicy.lyricPositionMs(10_000L, delay))
        assertEquals(0L, LyricsTimingPolicy.lyricPositionMs(100L, delay))
        assertEquals(
            LyricsTimingPolicy.MAX_MANUAL_DELAY_MS,
            LyricsTimingPolicy.appliedDelayMs(
                AudioOutputRoute.SPEAKER,
                Long.MAX_VALUE,
                automaticCompensationEnabled = false,
            ),
        )
    }

    @Test
    fun automaticCompensationCanBeDisabledWithoutDroppingManualCalibration() {
        assertEquals(
            -75L,
            LyricsTimingPolicy.appliedDelayMs(
                route = AudioOutputRoute.BLUETOOTH_CLASSIC,
                manualDelayMs = -75L,
                automaticCompensationEnabled = false,
            ),
        )
    }

    @Test
    fun syncedSelectionExposesCurrentAndNextAndDoesNotPreHighlightFirstLine() {
        val lyrics = LrcParser.parse(
            """
            [00:01.00]first
            [00:02.00]second
            [00:03.00]third
            """.trimIndent(),
        )

        assertEquals("" to "first", SystemLyricsLineSelector.visibleLines(lyrics, 999L))
        assertEquals("first" to "second", SystemLyricsLineSelector.visibleLines(lyrics, 1_500L))
        assertEquals("third" to "", SystemLyricsLineSelector.visibleLines(lyrics, 9_000L))
    }

    @Test
    fun plainLyricsExposeFirstTwoLinesWithoutPretendingToBeTimed() {
        val lyrics = LrcParser.parse("first\nsecond\nthird")
        assertEquals("first" to "second", SystemLyricsLineSelector.visibleLines(lyrics, 50_000L))
    }

    @Test
    fun bilingualSystemLyricsKeepOriginalAndTranslationTogether() {
        val lyrics = LrcParser.parse(
            "[00:01.00]你好\n[00:01.00]Hello\n[00:03.00]再见\n[00:03.00]Goodbye"
        )

        assertEquals("你好\nHello" to "再见\nGoodbye", SystemLyricsLineSelector.visibleLines(lyrics, 1_500L))
    }
}
