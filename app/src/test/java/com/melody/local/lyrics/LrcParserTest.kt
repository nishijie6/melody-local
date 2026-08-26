package com.melody.local.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultipleTimestampsAndOffset() {
        val lyrics = LrcParser.parse(
            """
            [offset:100]
            [ar:Artist]
            [00:01.20][00:03.250]同一句
            [00:02.00]第二句
            """.trimIndent()
        )

        assertTrue(lyrics.isSynced)
        assertEquals(listOf(1_300L, 2_100L, 3_350L), lyrics.lines.map { it.timeMs })
        assertEquals("同一句", lyrics.lines.first().text)
    }

    @Test
    fun keepsPlainTextLyrics() {
        val lyrics = LrcParser.parse("第一行\n第二行")

        assertFalse(lyrics.isSynced)
        assertEquals(listOf("第一行", "第二行"), lyrics.lines.map { it.text })
    }

    @Test
    fun findsActiveLine() {
        val lyrics = LrcParser.parse("[00:01.00]一\n[00:03.00]二")

        assertEquals(0, lyrics.activeLineIndex(2_500))
        assertEquals(1, lyrics.activeLineIndex(3_500))
    }

    @Test
    fun handlesEmptyMetadataOnlyAndBlankTimedLyrics() {
        val empty = LrcParser.parse("")
        val metadataOnly = LrcParser.parse("[ar:Artist]\n[ti:Title]\n[00:01.00]   ")

        assertFalse(empty.isSynced)
        assertEquals(emptyList<LyricLine>(), empty.lines)
        assertEquals(-1, empty.activeLineIndex(10_000L))
        assertFalse(metadataOnly.isSynced)
        assertEquals(emptyList<LyricLine>(), metadataOnly.lines)
    }

    @Test
    fun parsesOneDigitFractionsAndClampsNegativeOffsets() {
        val lyrics = LrcParser.parse("[offset:-2000]\n[00:01.5]开始\n[00:03]继续")

        assertEquals(listOf(0L, 1_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun decodesUtf8BomAndFallsBackToGb18030() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "第一行".toByteArray(Charsets.UTF_8)
        val gb18030 = "中文歌词".toByteArray(charset("GB18030"))

        assertEquals("第一行", LrcParser.decode(utf8Bom))
        assertEquals("中文歌词", LrcParser.decode(gb18030))
    }

    @Test
    fun rejectsTimestampExpansionAndExcessivePhysicalLines() {
        val tooManyTimestamps = "[00:00]".repeat(33) + "歌词"
        val tooManyLines = List(10_001) { "歌词" }.joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            LrcParser.parse(tooManyTimestamps)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LrcParser.parse(tooManyLines)
        }
    }

    @Test
    fun rejectsDecodedTextSingleLinesAndCumulativeEntriesAboveTheirLimits() {
        val tooMuchDecodedText = "a".repeat(2 * 1024 * 1024 + 1)
        val tooLongLine = "a".repeat(4_097)
        val tooManyEntries = List(6_667) { "[00:00][00:01][00:02]歌词" }.joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse(tooMuchDecodedText) }
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse(tooLongLine) }
        assertThrows(IllegalArgumentException::class.java) { LrcParser.parse(tooManyEntries) }
    }

    @Test
    fun activeLineUsesTheLastDuplicateAndHandlesPositionsBeforeTheFirstTimestamp() {
        val lyrics = LrcParser.parse("[00:01.00]first\n[00:01.00]replacement\n[00:03.00]third")

        assertEquals(0, lyrics.activeLineIndex(0L))
        assertEquals(1, lyrics.activeLineIndex(1_000L))
        assertEquals(1, lyrics.activeLineIndex(2_999L))
    }
}
