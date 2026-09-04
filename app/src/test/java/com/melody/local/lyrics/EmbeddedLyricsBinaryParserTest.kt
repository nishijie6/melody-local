package com.melody.local.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class EmbeddedLyricsBinaryParserTest {
    @Test
    fun readsUtf8Id3v23Uslt() {
        val framePayload = byteArrayOf(3) +
            "zho".toByteArray(StandardCharsets.ISO_8859_1) +
            "karaoke".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) +
            "[00:01.00]你好\n[00:02.00]世界".toByteArray(StandardCharsets.UTF_8)
        val file = id3v23(frame("USLT", framePayload))

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.ID3_USLT, result?.source)
        assertEquals("zho", result?.language)
        assertEquals("karaoke", result?.description)
        assertEquals("[00:01.00]你好\n[00:02.00]世界", result?.text)
    }

    @Test
    fun prefersSynchronizedId3LyricsAndPreservesEveryFieldTimestampAsEnhancedLrc() {
        val syltPayload = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(2, 1, 0) +
            "Hello ".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + int32(1_234) +
            "world\n".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + int32(2_506) +
            "Next".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + int32(3_007)
        val usltPayload = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            "plain fallback".toByteArray(StandardCharsets.UTF_8)
        val file = id3v23(frame("USLT", usltPayload) + frame("SYLT", syltPayload))

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.ID3_SYLT, result?.source)
        assertEquals(
            "[00:01.234]<00:01.234>Hello <00:02.506>world\n" +
                "[00:03.007]<00:03.007>Next",
            result?.text,
        )
        val parsed = LrcParser.parse(requireNotNull(result).text)
        assertEquals(listOf("Hello world", "Next"), parsed.lines.map { it.text })
        assertEquals(listOf(1_234L, 2_506L), parsed.lines.first().segments.map { it.timeMs })
        assertEquals(listOf("Hello ", "world"), parsed.lines.first().segments.map { it.text })
    }

    @Test
    fun boundsSyltGroupsSoGeneratedEnhancedLrcRemainsParseable() {
        val entries = ByteArrayOutputStream().apply {
            repeat(300) { index ->
                write("x".toByteArray(StandardCharsets.UTF_8))
                write(0)
                write(int32(index * 10))
            }
        }.toByteArray()
        val syltPayload = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(2, 1, 0) + entries

        val result = requireNotNull(EmbeddedLyricsBinaryParser.parse(id3v23(frame("SYLT", syltPayload))))
        val parsed = LrcParser.parse(result.text)

        assertEquals(2, parsed.lines.size)
        assertTrue(parsed.lines.all { it.segments.size <= 256 })
        assertEquals(300, parsed.lines.sumOf { it.segments.size })
        assertEquals(2_990L, parsed.lines.last().segments.last().timeMs)
    }

    @Test
    fun handlesId3v24GroupingDataLengthAndFrameUnsynchronizationPrefixes() {
        val logicalPayload = byteArrayOf(0) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf('A'.code.toByte(), 0xff.toByte(), 'B'.code.toByte())
        val storedPayload = byteArrayOf(0x7f) +
            syncSafe32(logicalPayload.size) +
            addUnsynchronization(logicalPayload)
        val file = id3v24(frameV24("USLT", storedPayload, flags = 0x0043))

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.ID3_USLT, result?.source)
        assertEquals("AÿB", result?.text)
    }

    @Test
    fun rejectsMalformedOrUnsupportedId3v24FrameTransforms() {
        val uslt = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            "lyrics".toByteArray(StandardCharsets.UTF_8)
        val wrongLength = syncSafe32(uslt.size + 1) + uslt

        assertNull(EmbeddedLyricsBinaryParser.parse(id3v24(frameV24("USLT", wrongLength, 0x0001))))
        assertNull(EmbeddedLyricsBinaryParser.parse(id3v24(frameV24("USLT", uslt, 0x0008))))
    }

    @Test
    fun readsFlacVorbisLyricsAndPrefersSyncedField() {
        val vendor = "音澜".toByteArray(StandardCharsets.UTF_8)
        val comments = listOf(
            "ARTIST=歌手",
            "LYRICS=普通歌词",
            "SYNCEDLYRICS=[00:01.00]同步歌词",
        ).map { it.toByteArray(StandardCharsets.UTF_8) }
        val block = ByteArrayOutputStream().apply {
            write(littleEndian32(vendor.size))
            write(vendor)
            write(littleEndian32(comments.size))
            comments.forEach {
                write(littleEndian32(it.size))
                write(it)
            }
        }.toByteArray()
        val file = "fLaC".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0x84.toByte()) + int24(block.size) + block

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.FLAC_VORBIS_COMMENT, result?.source)
        assertEquals("SYNCEDLYRICS", result?.description)
        assertEquals("[00:01.00]同步歌词", result?.text)
    }

    @Test
    fun readsM4aItunesCopyrightLyricsAtomAfterSkippedMediaData() {
        val data = atom(
            "data",
            int32(1) + int32(0) + "[00:04.20]M4A lyric".toByteArray(StandardCharsets.UTF_8),
        )
        val lyricItem = atom(byteArrayOf(0xa9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), data)
        val ilst = atom("ilst", lyricItem)
        val meta = atom("meta", int32(0) + ilst)
        val moov = atom("moov", atom("udta", meta))
        val file = atom("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray(StandardCharsets.ISO_8859_1)) +
            atom("mdat", ByteArray(32) { 0x55 }) + moov

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.MP4_ITUNES_LYRICS, result?.source)
        assertEquals("[00:04.20]M4A lyric", result?.text)
    }

    @Test
    fun readsId3UserTextLyricsAndRejectsMalformedOrOversizedMetadata() {
        val payload = byteArrayOf(3) + "LYRICS".toByteArray() + byteArrayOf(0) + "line one".toByteArray()
        assertEquals(
            "line one",
            EmbeddedLyricsBinaryParser.parse(id3v23(frame("TXXX", payload)))?.text,
        )

        assertNull(EmbeddedLyricsBinaryParser.parse(byteArrayOf(1, 2, 3, 4)))
        val oversizedHeader = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            3, 0, 0,
            0x08, 0, 0, 1,
        )
        assertNull(EmbeddedLyricsBinaryParser.parse(oversizedHeader))
    }

    private fun id3v23(frames: ByteArray): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
    ) + syncSafe32(frames.size) + frames

    private fun id3v24(frames: ByteArray): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
    ) + syncSafe32(frames.size) + frames

    private fun frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(StandardCharsets.ISO_8859_1) + int32(payload.size) + byteArrayOf(0, 0) + payload

    private fun frameV24(id: String, payload: ByteArray, flags: Int): ByteArray =
        id.toByteArray(StandardCharsets.ISO_8859_1) + syncSafe32(payload.size) +
            byteArrayOf((flags ushr 8).toByte(), flags.toByte()) + payload

    private fun addUnsynchronization(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        bytes.forEach { byte ->
            write(byte.toInt())
            if (byte == 0xff.toByte()) write(0)
        }
    }.toByteArray()

    private fun atom(type: String, payload: ByteArray): ByteArray =
        atom(type.toByteArray(StandardCharsets.ISO_8859_1), payload)

    private fun atom(type: ByteArray, payload: ByteArray): ByteArray =
        int32(payload.size + 8) + type + payload

    private fun int24(value: Int) = byteArrayOf(
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun int32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun littleEndian32(value: Int) = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun syncSafe32(value: Int) = byteArrayOf(
        (value ushr 21 and 0x7f).toByte(),
        (value ushr 14 and 0x7f).toByte(),
        (value ushr 7 and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
