package com.melody.local.lyrics

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class EmbeddedLyricsSource {
    PLATFORM_METADATA,
    ID3_USLT,
    ID3_SYLT,
    ID3_USER_TEXT,
    FLAC_VORBIS_COMMENT,
    MP4_ITUNES_LYRICS,
}

data class EmbeddedLyrics(
    val text: String,
    val source: EmbeddedLyricsSource,
    val language: String? = null,
    val description: String? = null,
)

/**
 * Bounded, dependency-free parser for the lyric tags most often found in local music files.
 * It reads only the ID3/FLAC metadata prefix or the MP4 `moov` atom and never decodes audio.
 */
object EmbeddedLyricsBinaryParser {
    fun parse(bytes: ByteArray): EmbeddedLyrics? = parse(ByteArrayInputStream(bytes))

    fun parse(source: InputStream): EmbeddedLyrics? = runCatching {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        input.mark(SNIFF_BYTES)
        val prefix = ByteArray(SNIFF_BYTES)
        val prefixSize = input.readAtMost(prefix)
        input.reset()
        when {
            prefixSize >= 3 && prefix.copyOfRange(0, 3).contentEquals(ID3_MAGIC) -> parseId3(input)
            prefixSize >= 4 && prefix.copyOfRange(0, 4).contentEquals(FLAC_MAGIC) -> parseFlac(input)
            prefixSize >= 8 && isPlausibleMp4Atom(prefix) -> parseMp4(input)
            else -> null
        }
    }.getOrNull()

    private fun parseId3(input: InputStream): EmbeddedLyrics? {
        val header = input.readExactly(ID3_HEADER_BYTES) ?: return null
        if (!header.copyOfRange(0, 3).contentEquals(ID3_MAGIC)) return null
        val version = header[3].toInt() and 0xff
        if (version !in 2..4 ||
            header.sliceArray(6..9).any { (it.toInt() and 0x80) != 0 }
        ) return null
        val tagSize = syncSafeInt(header, 6)
        if (tagSize !in 1..MAX_METADATA_BYTES) return null
        var tag = input.readExactly(tagSize) ?: return null
        val tagFlags = header[5].toInt() and 0xff
        val tagUnsynchronized = (tagFlags and 0x80) != 0
        // ID3v2.4 defines unsynchronisation per frame. Removing bytes from the complete tag would
        // invalidate the frame sizes before they can be walked safely.
        if (tagUnsynchronized && version < 4) tag = removeUnsynchronization(tag)

        var position = id3FrameStart(tag, version, tagFlags) ?: return null
        var preferred: EmbeddedLyrics? = null
        var frameCount = 0
        while (position < tag.size && frameCount++ < MAX_ID3_FRAMES) {
            val frameHeaderSize = if (version == 2) 6 else 10
            if (position + frameHeaderSize > tag.size) break
            val idLength = if (version == 2) 3 else 4
            val frameId = String(tag, position, idLength, StandardCharsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val frameSize = if (version == 2) {
                unsignedInt24(tag, position + 3)
            } else if (version == 4) {
                syncSafeInt(tag, position + 4)
            } else {
                unsignedInt32(tag, position + 4).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
            }
            if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES ||
                position + frameHeaderSize + frameSize > tag.size
            ) break
            val flags = if (version >= 3) unsignedInt16(tag, position + 8) else 0
            val storedPayload = tag.copyOfRange(
                position + frameHeaderSize,
                position + frameHeaderSize + frameSize,
            )
            position += frameHeaderSize + frameSize
            val payload = prepareId3FramePayload(
                version = version,
                flags = flags,
                storedPayload = storedPayload,
                tagUnsynchronized = tagUnsynchronized,
            ) ?: continue

            val parsed = when (frameId) {
                "USLT", "ULT" -> parseUslt(payload)
                "SYLT", "SLT" -> parseSylt(payload)
                "TXXX", "TXX" -> parseUserText(payload)
                else -> null
            } ?: continue
            if (parsed.source == EmbeddedLyricsSource.ID3_SYLT) return parsed
            if (preferred == null || parsed.source == EmbeddedLyricsSource.ID3_USLT) preferred = parsed
        }
        return preferred
    }

    private fun id3FrameStart(tag: ByteArray, version: Int, flags: Int): Int? {
        if ((flags and 0x40) == 0 || version == 2) return 0
        if (tag.size < 4) return null
        return when (version) {
            3 -> (4L + unsignedInt32(tag, 0)).takeIf { it <= tag.size }?.toInt()
            4 -> syncSafeInt(tag, 0).takeIf { it in 4..tag.size }
            else -> 0
        }
    }

    private fun prepareId3FramePayload(
        version: Int,
        flags: Int,
        storedPayload: ByteArray,
        tagUnsynchronized: Boolean,
    ): ByteArray? {
        return when (version) {
            2 -> storedPayload
            3 -> {
                if ((flags and 0x00c0) != 0) return null // compression or encryption
                val dataStart = if ((flags and 0x0020) != 0) 1 else 0 // grouping identity
                if (dataStart > storedPayload.size) {
                    null
                } else {
                    storedPayload.copyOfRange(dataStart, storedPayload.size)
                }
            }
            4 -> {
                // Status flags 0x70 and format flags 0x4f are the only defined v2.4 bits.
                if ((flags and 0x8fb0) != 0) return null
                if ((flags and 0x000c) != 0) return null // compression or encryption

                var position = 0
                if ((flags and 0x0040) != 0) { // grouping identity byte
                    if (position >= storedPayload.size) return null
                    position++
                }
                val declaredDataLength = if ((flags and 0x0001) != 0) {
                    if (position + 4 > storedPayload.size ||
                        storedPayload.sliceArray(position until position + 4)
                            .any { (it.toInt() and 0x80) != 0 }
                    ) return null
                    syncSafeInt(storedPayload, position).also { position += 4 }
                } else {
                    null
                }
                var data = storedPayload.copyOfRange(position, storedPayload.size)
                if (tagUnsynchronized || (flags and 0x0002) != 0) {
                    data = removeUnsynchronization(data)
                }
                if (declaredDataLength != null && declaredDataLength != data.size) return null
                data
            }
            else -> null
        }
    }

    private fun parseUslt(payload: ByteArray): EmbeddedLyrics? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xff
        val language = String(payload, 1, 3, StandardCharsets.ISO_8859_1).takeUnless { it == "XXX" }
        val descriptorEnd = findEncodedTerminator(payload, 4, encoding) ?: return null
        val lyricsStart = descriptorEnd + terminatorWidth(encoding)
        if (lyricsStart > payload.size) return null
        val description = decodeEncoded(payload, 4, descriptorEnd, encoding).trim().ifBlank { null }
        val lyrics = decodeEncoded(payload, lyricsStart, payload.size, encoding).cleanLyricText() ?: return null
        return EmbeddedLyrics(
            text = lyrics,
            source = EmbeddedLyricsSource.ID3_USLT,
            language = language,
            description = description,
        )
    }

    private fun parseSylt(payload: ByteArray): EmbeddedLyrics? {
        if (payload.size < 11) return null
        val encoding = payload[0].toInt() and 0xff
        val language = String(payload, 1, 3, StandardCharsets.ISO_8859_1).takeUnless { it == "XXX" }
        if ((payload[4].toInt() and 0xff) != 2) return null // only millisecond timestamps are absolute
        val descriptorEnd = findEncodedTerminator(payload, 6, encoding) ?: return null
        val description = decodeEncoded(payload, 6, descriptorEnd, encoding).trim().ifBlank { null }
        var position = descriptorEnd + terminatorWidth(encoding)
        val output = StringBuilder()
        val line = mutableListOf<SyltSegment>()
        var lineLength = 0
        var entryCount = 0

        fun flushLine(): Boolean {
            if (line.isEmpty()) return true
            val rendered = buildString {
                append(formatLrcTimestamp(line.first().timestampMs))
                line.forEach { segment ->
                    append(formatEnhancedLrcTimestamp(segment.timestampMs))
                    append(segment.text)
                }
            }
            if (output.length + rendered.length + 1 > MAX_TEXT_BYTES) return false
            output.append(rendered).append('\n')
            line.clear()
            lineLength = 0
            return true
        }

        fun appendFragment(fragment: String, timestampMs: Long): Boolean {
            if (fragment.isEmpty()) return true
            var fragmentStart = 0
            while (fragmentStart < fragment.length) {
                val fragmentEnd = minOf(
                    fragment.length,
                    fragmentStart + MAX_SYLT_SEGMENT_CHARACTERS,
                )
                val part = fragment.substring(fragmentStart, fragmentEnd)
                val cost = formatEnhancedLrcTimestamp(timestampMs).length + part.length
                if (
                    line.isNotEmpty() &&
                    (line.size >= MAX_SYLT_SEGMENTS_PER_LINE ||
                        lineLength + cost > MAX_SYLT_LRC_LINE_CHARACTERS)
                ) {
                    if (!flushLine()) return false
                }
                line += SyltSegment(timestampMs, part)
                lineLength += cost
                fragmentStart = fragmentEnd
            }
            return true
        }

        while (position < payload.size && entryCount++ < MAX_SYNCED_ENTRIES) {
            val textEnd = findEncodedTerminator(payload, position, encoding) ?: break
            val timestampStart = textEnd + terminatorWidth(encoding)
            if (timestampStart + 4 > payload.size) break
            val lyric = decodeEncoded(payload, position, textEnd, encoding)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            val timestamp = unsignedInt32(payload, timestampStart)
            if (timestamp <= MAX_REASONABLE_TIMESTAMP_MS) {
                var fragmentStart = 0
                lyric.forEachIndexed { index, character ->
                    if (character == '\n') {
                        if (!appendFragment(lyric.substring(fragmentStart, index), timestamp)) return null
                        if (!flushLine()) return null
                        fragmentStart = index + 1
                    }
                }
                if (!appendFragment(lyric.substring(fragmentStart), timestamp)) return null
            }
            position = timestampStart + 4
        }
        if (!flushLine()) return null
        val lyrics = output.toString().trimEnd().cleanLyricText() ?: return null
        return EmbeddedLyrics(
            text = lyrics,
            source = EmbeddedLyricsSource.ID3_SYLT,
            language = language,
            description = description,
        )
    }

    private fun parseUserText(payload: ByteArray): EmbeddedLyrics? {
        if (payload.size < 3) return null
        val encoding = payload[0].toInt() and 0xff
        val descriptionEnd = findEncodedTerminator(payload, 1, encoding) ?: return null
        val description = decodeEncoded(payload, 1, descriptionEnd, encoding).trim()
        if (description.uppercase(Locale.ROOT) !in USER_TEXT_LYRIC_KEYS) return null
        val valueStart = descriptionEnd + terminatorWidth(encoding)
        val lyrics = decodeEncoded(payload, valueStart, payload.size, encoding).cleanLyricText() ?: return null
        return EmbeddedLyrics(
            text = lyrics,
            source = EmbeddedLyricsSource.ID3_USER_TEXT,
            description = description,
        )
    }

    private fun parseFlac(input: InputStream): EmbeddedLyrics? {
        if (input.readExactly(4)?.contentEquals(FLAC_MAGIC) != true) return null
        var totalMetadataBytes = 0L
        var blockCount = 0
        while (blockCount++ < MAX_FLAC_BLOCKS) {
            val header = input.readExactly(4) ?: return null
            val isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7f
            val blockSize = unsignedInt24(header, 1)
            totalMetadataBytes += 4L + blockSize
            if (totalMetadataBytes > MAX_FLAC_METADATA_SCAN_BYTES) return null
            if (blockType == 4) {
                if (blockSize > MAX_METADATA_BYTES) return null
                return parseVorbisComment(input.readExactly(blockSize) ?: return null)
            }
            if (!input.skipExactly(blockSize.toLong())) return null
            if (isLast) break
        }
        return null
    }

    private fun parseVorbisComment(block: ByteArray): EmbeddedLyrics? {
        var position = 0
        val vendorLength = littleEndianInt(block, position) ?: return null
        position += 4
        if (vendorLength < 0 || position + vendorLength > block.size) return null
        position += vendorLength
        val count = littleEndianInt(block, position) ?: return null
        position += 4
        if (count !in 0..MAX_VORBIS_COMMENTS) return null
        var best: Pair<Int, EmbeddedLyrics>? = null
        repeat(count) {
            val length = littleEndianInt(block, position) ?: return@repeat
            position += 4
            if (length < 0 || length > MAX_TEXT_BYTES || position + length > block.size) return null
            val comment = String(block, position, length, StandardCharsets.UTF_8)
            position += length
            val separator = comment.indexOf('=')
            if (separator <= 0) return@repeat
            val key = comment.substring(0, separator).trim().uppercase(Locale.ROOT)
            val priority = FLAC_LYRIC_KEYS[key] ?: return@repeat
            val text = comment.substring(separator + 1).cleanLyricText() ?: return@repeat
            if (best == null || priority < best!!.first) {
                best = priority to EmbeddedLyrics(
                    text = text,
                    source = EmbeddedLyricsSource.FLAC_VORBIS_COMMENT,
                    description = key,
                )
            }
        }
        return best?.second
    }

    private fun parseMp4(input: InputStream): EmbeddedLyrics? {
        var atomCount = 0
        var scannedBytes = 0L
        while (atomCount++ < MAX_MP4_TOP_LEVEL_ATOMS && scannedBytes < MAX_MP4_SCAN_BYTES) {
            val header = readStreamAtomHeader(input) ?: return null
            scannedBytes += header.headerSize
            val payloadSize = if (header.size == 0L) -1L else header.size - header.headerSize
            if (payloadSize < -1L) return null
            if (header.type == "moov") {
                val payload = if (payloadSize < 0) {
                    input.readBytesWithLimit(MAX_MP4_MOOV_BYTES)
                } else {
                    if (payloadSize > MAX_MP4_MOOV_BYTES) return null
                    input.readExactly(payloadSize.toInt()) ?: return null
                }
                return findMp4Lyrics(payload, 0, payload.size, 0)
            }
            if (payloadSize < 0 || !input.skipExactly(payloadSize)) return null
            scannedBytes += payloadSize
        }
        return null
    }

    private fun findMp4Lyrics(bytes: ByteArray, start: Int, end: Int, depth: Int): EmbeddedLyrics? {
        if (depth > MAX_MP4_DEPTH || start !in 0..end || end > bytes.size) return null
        var position = start
        var atomCount = 0
        while (position + 8 <= end && atomCount++ < MAX_MP4_CHILD_ATOMS) {
            val atom = readByteArrayAtom(bytes, position, end) ?: break
            when (atom.type) {
                "©lyr" -> extractMp4Data(bytes, atom.payloadStart, atom.end)?.let { return it }
                "----" -> extractMp4FreeformLyrics(bytes, atom.payloadStart, atom.end)?.let { return it }
                "meta" -> {
                    val childStart = (atom.payloadStart + 4).coerceAtMost(atom.end)
                    findMp4Lyrics(bytes, childStart, atom.end, depth + 1)?.let { return it }
                }
                "moov", "udta", "ilst" ->
                    findMp4Lyrics(bytes, atom.payloadStart, atom.end, depth + 1)?.let { return it }
            }
            position = atom.end
        }
        return null
    }

    private fun extractMp4Data(bytes: ByteArray, start: Int, end: Int): EmbeddedLyrics? {
        var position = start
        while (position + 8 <= end) {
            val atom = readByteArrayAtom(bytes, position, end) ?: break
            if (atom.type == "data" && atom.payloadStart + 8 <= atom.end) {
                val dataType = unsignedInt32(bytes, atom.payloadStart)
                val textStart = atom.payloadStart + 8
                val decoded = when (dataType) {
                    2L -> String(bytes, textStart, atom.end - textStart, StandardCharsets.UTF_16BE)
                    else -> String(bytes, textStart, atom.end - textStart, StandardCharsets.UTF_8)
                }.cleanLyricText()
                if (decoded != null) {
                    return EmbeddedLyrics(decoded, EmbeddedLyricsSource.MP4_ITUNES_LYRICS)
                }
            }
            position = atom.end
        }
        return null
    }

    private fun extractMp4FreeformLyrics(bytes: ByteArray, start: Int, end: Int): EmbeddedLyrics? {
        var position = start
        var mean: String? = null
        var name: String? = null
        var data: EmbeddedLyrics? = null
        while (position + 8 <= end) {
            val atom = readByteArrayAtom(bytes, position, end) ?: break
            when (atom.type) {
                "mean", "name" -> if (atom.payloadStart + 4 <= atom.end) {
                    val value = String(
                        bytes,
                        atom.payloadStart + 4,
                        atom.end - atom.payloadStart - 4,
                        StandardCharsets.UTF_8,
                    ).trim('\u0000', ' ')
                    if (atom.type == "mean") mean = value else name = value
                }
                "data" -> data = extractMp4Data(
                    bytes = normalizedDataAtom(bytes, atom),
                    start = 0,
                    end = atom.end - atom.start,
                )
            }
            position = atom.end
        }
        val normalizedName = name?.uppercase(Locale.ROOT) ?: return null
        return data?.takeIf {
            mean.equals("com.apple.iTunes", ignoreCase = true) &&
                normalizedName in USER_TEXT_LYRIC_KEYS
        }
    }

    private fun normalizedDataAtom(bytes: ByteArray, atom: ByteArrayAtom): ByteArray =
        bytes.copyOfRange(atom.start, atom.end)

    private fun readStreamAtomHeader(input: InputStream): StreamAtomHeader? {
        val base = input.readExactly(8) ?: return null
        val size32 = unsignedInt32(base, 0)
        val type = String(base, 4, 4, StandardCharsets.ISO_8859_1)
        return when {
            size32 == 0L -> StreamAtomHeader(type, 0L, 8L)
            size32 == 1L -> {
                val extended = input.readExactly(8) ?: return null
                val size64 = unsignedInt64(extended, 0) ?: return null
                if (size64 < 16L) null else StreamAtomHeader(type, size64, 16L)
            }
            size32 < 8L -> null
            else -> StreamAtomHeader(type, size32, 8L)
        }
    }

    private fun readByteArrayAtom(bytes: ByteArray, start: Int, limit: Int): ByteArrayAtom? {
        if (start + 8 > limit) return null
        val size32 = unsignedInt32(bytes, start)
        val type = String(bytes, start + 4, 4, StandardCharsets.ISO_8859_1)
        val headerSize: Int
        val atomSize: Long
        when (size32) {
            0L -> {
                headerSize = 8
                atomSize = (limit - start).toLong()
            }
            1L -> {
                if (start + 16 > limit) return null
                headerSize = 16
                atomSize = unsignedInt64(bytes, start + 8) ?: return null
            }
            else -> {
                headerSize = 8
                atomSize = size32
            }
        }
        if (atomSize < headerSize || atomSize > Int.MAX_VALUE || start + atomSize > limit) return null
        return ByteArrayAtom(start, start + headerSize, (start + atomSize).toInt(), type)
    }

    private fun isPlausibleMp4Atom(prefix: ByteArray): Boolean {
        val size = unsignedInt32(prefix, 0)
        val type = String(prefix, 4, 4, StandardCharsets.ISO_8859_1)
        return (size == 0L || size == 1L || size >= 8L) && type.all { it.code in 0x20..0x7e }
    }

    private fun findEncodedTerminator(bytes: ByteArray, start: Int, encoding: Int): Int? {
        if (start !in 0..bytes.size) return null
        return if (terminatorWidth(encoding) == 1) {
            (start until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
        } else {
            var position = start
            while (position + 1 < bytes.size) {
                if (bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte()) return position
                position += 2
            }
            null
        }
    }

    private fun decodeEncoded(bytes: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start !in 0..end || end > bytes.size) return ""
        val charset: Charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return ""
        }
        return String(bytes, start, end - start, charset).trim('\u0000', '\uFEFF')
    }

    private fun terminatorWidth(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun String.cleanLyricText(): String? {
        val cleaned = replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim()
        return cleaned.takeIf { it.isNotBlank() && it.length <= MAX_TEXT_BYTES }
    }

    private fun formatLrcTimestamp(timestampMs: Long): String {
        val minutes = timestampMs / 60_000
        val seconds = timestampMs % 60_000 / 1_000
        val milliseconds = timestampMs % 1_000
        return "[%02d:%02d.%03d]".format(Locale.ROOT, minutes, seconds, milliseconds)
    }

    private fun formatEnhancedLrcTimestamp(timestampMs: Long): String =
        formatLrcTimestamp(timestampMs).replace('[', '<').replace(']', '>')

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        var position = 0
        while (position < bytes.size) {
            val current = bytes[position]
            output.write(current.toInt())
            if (current == 0xff.toByte() && position + 1 < bytes.size && bytes[position + 1] == 0.toByte()) {
                position++
            }
            position++
        }
        return output.toByteArray()
    }

    private fun InputStream.readExactly(size: Int): ByteArray? {
        if (size < 0) return null
        val bytes = ByteArray(size)
        var position = 0
        while (position < size) {
            val count = read(bytes, position, size - position)
            if (count < 0) return null
            if (count == 0) {
                val next = read()
                if (next < 0) return null
                bytes[position++] = next.toByte()
            } else {
                position += count
            }
        }
        return bytes
    }

    private fun InputStream.readAtMost(buffer: ByteArray): Int {
        var position = 0
        while (position < buffer.size) {
            val count = read(buffer, position, buffer.size - position)
            if (count <= 0) break
            position += count
        }
        return position
    }

    private fun InputStream.skipExactly(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                return false
            }
        }
        return true
    }

    private fun InputStream.readBytesWithLimit(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, limit))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "MP4 metadata atom is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)

    private fun unsignedInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun unsignedInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun unsignedInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun unsignedInt64(bytes: ByteArray, offset: Int): Long? {
        if ((bytes[offset].toInt() and 0x80) != 0) return null
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        return value
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private data class StreamAtomHeader(val type: String, val size: Long, val headerSize: Long)
    private data class ByteArrayAtom(val start: Int, val payloadStart: Int, val end: Int, val type: String)
    private data class SyltSegment(val timestampMs: Long, val text: String)

    private val ID3_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private val USER_TEXT_LYRIC_KEYS = setOf("LYRICS", "LYRIC", "UNSYNCEDLYRICS", "SYNCEDLYRICS")
    private val FLAC_LYRIC_KEYS = mapOf(
        "SYNCEDLYRICS" to 0,
        "LYRICS" to 1,
        "UNSYNCEDLYRICS" to 2,
        "LYRIC" to 3,
    )

    private const val SNIFF_BYTES = 16
    private const val ID3_HEADER_BYTES = 10
    private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    private const val MAX_ID3_FRAMES = 10_000
    private const val MAX_SYNCED_ENTRIES = 20_000
    private const val MAX_SYLT_SEGMENTS_PER_LINE = 256
    private const val MAX_SYLT_SEGMENT_CHARACTERS = 1_024
    private const val MAX_SYLT_LRC_LINE_CHARACTERS = 3_800
    private const val MAX_REASONABLE_TIMESTAMP_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_FLAC_BLOCKS = 256
    private const val MAX_FLAC_METADATA_SCAN_BYTES = 64L * 1024 * 1024
    private const val MAX_VORBIS_COMMENTS = 10_000
    private const val MAX_MP4_MOOV_BYTES = 32 * 1024 * 1024
    private const val MAX_MP4_SCAN_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_MP4_TOP_LEVEL_ATOMS = 4_096
    private const val MAX_MP4_CHILD_ATOMS = 100_000
    private const val MAX_MP4_DEPTH = 12
}
