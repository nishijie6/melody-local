package com.melody.local.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class LyricLine(
    val timeMs: Long?,
    val text: String,
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
) {
    fun activeLineIndex(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        val index = lines.binarySearchBy(positionMs) { it.timeMs ?: Long.MAX_VALUE }
        return if (index >= 0) {
            var lastAtSameTime = index
            while (lastAtSameTime + 1 < lines.size && lines[lastAtSameTime + 1].timeMs == positionMs) {
                lastAtSameTime++
            }
            lastAtSameTime
        } else {
            (-index - 2).coerceAtLeast(0)
        }
    }
}

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val metadataTag = Regex("""^\[(ar|al|ti|by|re|ve):.*]$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ParsedLyrics {
        require(text.length <= MAX_DECODED_CHARACTERS) { "歌词文本过大" }
        val offset = offsetTag.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()
        var physicalLineCount = 0
        var totalEntryCount = 0

        text.lineSequence().forEach { rawLine ->
            physicalLineCount++
            require(physicalLineCount <= MAX_PHYSICAL_LINES) { "歌词行数过多" }
            require(rawLine.length <= MAX_LINE_CHARACTERS) { "单行歌词过长" }
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank() || offsetTag.matches(line) || metadataTag.matches(line)) return@forEach

            var timestampCount = 0
            timeTag.findAll(line).forEach {
                timestampCount++
                require(timestampCount <= MAX_TIMESTAMPS_PER_LINE) { "单行时间标签过多" }
            }
            if (timestampCount == 0) {
                totalEntryCount++
                require(totalEntryCount <= MAX_LYRIC_ENTRIES) { "歌词条目过多" }
                plain += LyricLine(timeMs = null, text = line)
                return@forEach
            }

            val lyricText = timeTag.replace(line, "").trim()
            if (lyricText.isBlank()) return@forEach
            totalEntryCount += timestampCount
            require(totalEntryCount <= MAX_LYRIC_ENTRIES) { "歌词条目过多" }
            timeTag.findAll(line).forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fractionText = match.groupValues.getOrNull(3).orEmpty()
                val fractionMs = when (fractionText.length) {
                    1 -> fractionText.toLong() * 100
                    2 -> fractionText.toLong() * 10
                    3 -> fractionText.toLong()
                    else -> 0L
                }
                val timeMs = (minutes * 60_000 + seconds * 1_000 + fractionMs + offset)
                    .coerceAtLeast(0L)
                timed += LyricLine(timeMs = timeMs, text = lyricText)
            }
        }

        return if (timed.isNotEmpty()) {
            ParsedLyrics(lines = timed.sortedBy { it.timeMs }, isSynced = true)
        } else {
            ParsedLyrics(lines = plain, isSynced = false)
        }
    }

    fun decode(bytes: ByteArray): String {
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, charset("GB18030")) }
            .removePrefix("\uFEFF")
    }

    private const val MAX_DECODED_CHARACTERS = 2 * 1024 * 1024
    private const val MAX_PHYSICAL_LINES = 10_000
    private const val MAX_LINE_CHARACTERS = 4_096
    private const val MAX_TIMESTAMPS_PER_LINE = 32
    private const val MAX_LYRIC_ENTRIES = 20_000
}
