package com.melody.local.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A timed fragment from enhanced LRC, such as `<00:12.34>Hello `. */
data class TimedLyricSegment(
    val timeMs: Long,
    val text: String,
)

/** The semantic role of one visible line in a bilingual or trilingual lyric group. */
enum class LyricLayerType {
    ORIGINAL,
    TRANSLATION,
    ROMANIZATION,
    ALTERNATE,
}

data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val segments: List<TimedLyricSegment> = emptyList(),
    val layerTypeHint: LyricLayerType? = null,
)

data class StructuredLyricLayer(
    val type: LyricLayerType,
    val text: String,
    val segments: List<TimedLyricSegment> = emptyList(),
)

/**
 * Lines sharing a timestamp are exposed as one display unit without changing the legacy [LyricLine]
 * list. Explicit markers are preferred (`[tr]`, `[translation]`, `[roma]`, `[romaji]`, etc.).
 * Untagged two-line groups default to original + translation; untagged three-line groups use the
 * common original + romanization + translation convention.
 */
data class StructuredLyricLine(
    val timeMs: Long?,
    val layers: List<StructuredLyricLayer>,
) {
    val original: StructuredLyricLayer?
        get() = layers.firstOrNull { it.type == LyricLayerType.ORIGINAL }
    val translation: StructuredLyricLayer?
        get() = layers.firstOrNull { it.type == LyricLayerType.TRANSLATION }
    val romanization: StructuredLyricLayer?
        get() = layers.firstOrNull { it.type == LyricLayerType.ROMANIZATION }
}

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val structuredLines: List<StructuredLyricLine> = structureLyricLines(lines, isSynced),
) {
    val hasWordTiming: Boolean
        get() = lines.any { it.segments.isNotEmpty() }

    val hasTranslations: Boolean
        get() = structuredLines.any { it.translation != null }

    val hasRomanization: Boolean
        get() = structuredLines.any { it.romanization != null }

    fun activeLineIndex(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        if (positionMs < (lines.first().timeMs ?: Long.MAX_VALUE)) return -1
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

    fun activeStructuredLineIndex(positionMs: Long): Int {
        if (!isSynced || structuredLines.isEmpty()) return -1
        if (positionMs < (structuredLines.first().timeMs ?: Long.MAX_VALUE)) return -1
        val index = structuredLines.binarySearchBy(positionMs) { it.timeMs ?: Long.MAX_VALUE }
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }
}

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val enhancedTimeTag = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val metadataTag = Regex(
        """^\[(ar|al|ti|by|re|ve|length|language):.*]$""",
        RegexOption.IGNORE_CASE,
    )
    private val layerPrefix = Regex(
        """^\s*(?:\[(original|orig|原文|tr|trans|translation|translated|翻译|译文|roma|romanization|romaji|pinyin|罗马音|拼音)]|(original|orig|原文|tr|trans|translation|translated|翻译|译文|roma|romanization|romaji|pinyin|罗马音|拼音)\s*[:：])\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): ParsedLyrics {
        require(text.length <= MAX_DECODED_CHARACTERS) { "歌词文本过大" }
        val offset = offsetTag.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()
        var physicalLineCount = 0
        var totalEntryCount = 0
        var continuationTimes = emptyList<Long>()
        var continuationLayerCount = 0

        text.lineSequence().forEach { rawLine ->
            physicalLineCount++
            require(physicalLineCount <= MAX_PHYSICAL_LINES) { "歌词行数过多" }
            require(rawLine.length <= MAX_LINE_CHARACTERS) { "单行歌词过长" }
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank() || offsetTag.matches(line) || metadataTag.matches(line)) {
                continuationTimes = emptyList()
                continuationLayerCount = 0
                return@forEach
            }

            val timestamps = timeTag.findAll(line).toList()
            require(timestamps.size <= MAX_TIMESTAMPS_PER_LINE) { "单行时间标签过多" }
            if (timestamps.isEmpty()) {
                val (layerType, lyricText) = extractLayerType(timeTag.replace(line, "").trim())
                if (lyricText.isNotBlank()) {
                    if (
                        continuationTimes.isNotEmpty() &&
                        continuationLayerCount < MAX_UNTIMED_CONTINUATION_LAYERS
                    ) {
                        totalEntryCount += continuationTimes.size
                        require(totalEntryCount <= MAX_LYRIC_ENTRIES) { "歌词条目过多" }
                        continuationTimes.forEach { timeMs ->
                            timed += LyricLine(
                                timeMs = timeMs,
                                text = lyricText,
                                layerTypeHint = layerType,
                            )
                        }
                        continuationLayerCount++
                    } else {
                        totalEntryCount++
                        require(totalEntryCount <= MAX_LYRIC_ENTRIES) { "歌词条目过多" }
                        plain += LyricLine(timeMs = null, text = lyricText, layerTypeHint = layerType)
                    }
                }
                return@forEach
            }

            continuationTimes = timestamps.map { parseTime(it, offset) }
            continuationLayerCount = 0
            val lyricWithWordTags = timeTag.replace(line, "").trim()
            val (layerType, roleStrippedText) = extractLayerType(lyricWithWordTags)
            val lyricText = enhancedTimeTag.replace(roleStrippedText, "").trim()
            if (lyricText.isBlank()) return@forEach

            val wordTagCount = enhancedTimeTag.findAll(roleStrippedText).count()
            require(wordTagCount <= MAX_WORD_TIMESTAMPS_PER_LINE) { "单行逐字时间标签过多" }
            val segments = parseEnhancedSegments(roleStrippedText, offset)
            totalEntryCount += timestamps.size
            require(totalEntryCount <= MAX_LYRIC_ENTRIES) { "歌词条目过多" }
            continuationTimes.forEach { timeMs ->
                timed += LyricLine(
                    timeMs = timeMs,
                    text = lyricText,
                    segments = segments,
                    layerTypeHint = layerType,
                )
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

    private fun extractLayerType(text: String): Pair<LyricLayerType?, String> {
        val match = layerPrefix.find(text) ?: return null to text
        val marker = match.groupValues.drop(1)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .lowercase(Locale.ROOT)
        val type = when (marker) {
            "original", "orig", "原文" -> LyricLayerType.ORIGINAL
            "tr", "trans", "translation", "translated", "翻译", "译文" ->
                LyricLayerType.TRANSLATION
            "roma", "romanization", "romaji", "pinyin", "罗马音", "拼音" ->
                LyricLayerType.ROMANIZATION
            else -> null
        }
        return type to text.removeRange(match.range).trim()
    }

    private fun parseEnhancedSegments(text: String, offset: Long): List<TimedLyricSegment> {
        val matches = enhancedTimeTag.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val contentStart = match.range.last + 1
            val contentEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val segmentText = text.substring(contentStart, contentEnd)
            if (segmentText.isEmpty()) {
                null
            } else {
                TimedLyricSegment(timeMs = parseTime(match, offset), text = segmentText)
            }
        }
    }

    private fun parseTime(match: MatchResult, offset: Long): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fractionText = match.groupValues.getOrNull(3).orEmpty()
        val fractionMs = when (fractionText.length) {
            1 -> fractionText.toLong() * 100
            2 -> fractionText.toLong() * 10
            3 -> fractionText.toLong()
            else -> 0L
        }
        return (minutes * 60_000 + seconds * 1_000 + fractionMs + offset).coerceAtLeast(0L)
    }

    private const val MAX_DECODED_CHARACTERS = 2 * 1024 * 1024
    private const val MAX_PHYSICAL_LINES = 10_000
    private const val MAX_LINE_CHARACTERS = 4_096
    private const val MAX_TIMESTAMPS_PER_LINE = 32
    private const val MAX_WORD_TIMESTAMPS_PER_LINE = 512
    private const val MAX_UNTIMED_CONTINUATION_LAYERS = 2
    private const val MAX_LYRIC_ENTRIES = 20_000
}

private fun structureLyricLines(
    lines: List<LyricLine>,
    isSynced: Boolean,
): List<StructuredLyricLine> {
    if (lines.isEmpty()) return emptyList()
    if (!isSynced) {
        return lines.map { line ->
            StructuredLyricLine(
                timeMs = null,
                layers = listOf(
                    StructuredLyricLayer(
                        type = line.layerTypeHint ?: LyricLayerType.ORIGINAL,
                        text = line.text,
                        segments = line.segments,
                    )
                ),
            )
        }
    }

    return lines.groupBy { it.timeMs }.map { (timeMs, group) ->
        val assigned = assignLayerTypes(group)
        val layers = group.mapIndexed { index, line ->
            StructuredLyricLayer(
                type = assigned[index],
                text = line.text,
                segments = line.segments,
            )
        }.sortedBy { layer ->
            when (layer.type) {
                LyricLayerType.ORIGINAL -> 0
                LyricLayerType.ROMANIZATION -> 1
                LyricLayerType.TRANSLATION -> 2
                LyricLayerType.ALTERNATE -> 3
            }
        }
        StructuredLyricLine(
            timeMs = timeMs,
            layers = layers,
        )
    }
}

private fun assignLayerTypes(group: List<LyricLine>): List<LyricLayerType> {
    val result = group.map { it.layerTypeHint }.toMutableList()
    val used = result.filterNotNull().toMutableSet()

    if (LyricLayerType.ORIGINAL !in used) {
        val originalIndex = result.indexOfFirst { it == null }
        if (originalIndex >= 0) {
            result[originalIndex] = LyricLayerType.ORIGINAL
            used += LyricLayerType.ORIGINAL
        }
    }

    result.indices.filter { result[it] == null }.forEach { index ->
        val availablePreferred = when {
            group.size >= 3 && LyricLayerType.ROMANIZATION !in used -> LyricLayerType.ROMANIZATION
            LyricLayerType.TRANSLATION !in used -> LyricLayerType.TRANSLATION
            LyricLayerType.ROMANIZATION !in used -> LyricLayerType.ROMANIZATION
            else -> LyricLayerType.ALTERNATE
        }
        result[index] = availablePreferred
        used += availablePreferred
    }

    return result.map { it ?: LyricLayerType.ALTERNATE }
}
