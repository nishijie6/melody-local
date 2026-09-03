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

        assertEquals(-1, lyrics.activeLineIndex(0L))
        assertEquals(1, lyrics.activeLineIndex(1_000L))
        assertEquals(1, lyrics.activeLineIndex(2_999L))
    }

    @Test
    fun parsesEnhancedLrcWordTimestampsAndAppliesOffset() {
        val lyrics = LrcParser.parse(
            "[offset:100]\n[00:01.00]<00:01.00>你<00:01.40>好 <00:01.80>world"
        )

        assertTrue(lyrics.hasWordTiming)
        assertEquals("你好 world", lyrics.lines.single().text)
        assertEquals(listOf(1_100L, 1_500L, 1_900L), lyrics.lines.single().segments.map { it.timeMs })
        assertEquals(listOf("你", "好 ", "world"), lyrics.lines.single().segments.map { it.text })
    }

    @Test
    fun structuresExplicitOriginalRomanizationAndTranslationInDisplayOrder() {
        val lyrics = LrcParser.parse(
            """
            [00:01.00][translation]Hello
            [00:01.00][original]你好
            [00:01.00]romanization: Ni hao
            """.trimIndent()
        )

        val structured = lyrics.structuredLines.single()
        assertTrue(lyrics.hasTranslations)
        assertTrue(lyrics.hasRomanization)
        assertEquals("你好", structured.original?.text)
        assertEquals("Ni hao", structured.romanization?.text)
        assertEquals("Hello", structured.translation?.text)
        assertEquals(
            listOf(
                LyricLayerType.ORIGINAL,
                LyricLayerType.ROMANIZATION,
                LyricLayerType.TRANSLATION,
            ),
            structured.layers.map { it.type },
        )
    }

    @Test
    fun structuresCommonUntaggedBilingualAndTrilingualLrcWithoutChangingLegacyLines() {
        val lyrics = LrcParser.parse(
            """
            [00:01.00]你好
            [00:01.00]Hello
            [00:03.00]ありがとう
            [00:03.00]Arigatou
            [00:03.00]Thank you
            """.trimIndent()
        )

        assertEquals(5, lyrics.lines.size)
        assertEquals(2, lyrics.structuredLines.size)
        assertEquals("Hello", lyrics.structuredLines[0].translation?.text)
        assertEquals("Arigatou", lyrics.structuredLines[1].romanization?.text)
        assertEquals("Thank you", lyrics.structuredLines[1].translation?.text)
        assertEquals(1, lyrics.activeStructuredLineIndex(3_500L))
    }

    @Test
    fun attachesUntimedTranslationAndRomanizationLinesToPreviousTimestamp() {
        val lyrics = LrcParser.parse(
            """
            [00:01.00]你好
            Ni hao
            Hello
            [00:03.00]再见
            [tr]Goodbye
            """.trimIndent()
        )

        assertEquals(2, lyrics.structuredLines.size)
        assertEquals("你好", lyrics.structuredLines[0].original?.text)
        assertEquals("Ni hao", lyrics.structuredLines[0].romanization?.text)
        assertEquals("Hello", lyrics.structuredLines[0].translation?.text)
        assertEquals("再见", lyrics.structuredLines[1].original?.text)
        assertEquals("Goodbye", lyrics.structuredLines[1].translation?.text)
    }

    @Test
    fun rejectsExcessiveEnhancedWordTimestamps() {
        val tooManyWordTimestamps = "[00:00]" + "<00:00>a".repeat(513)

        assertThrows(IllegalArgumentException::class.java) {
            LrcParser.parse(tooManyWordTimestamps)
        }
    }
}
