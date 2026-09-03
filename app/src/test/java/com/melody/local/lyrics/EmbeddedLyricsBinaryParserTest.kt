package com.melody.local.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun prefersSynchronizedId3LyricsAndConvertsMillisecondsToLrc() {
        val syltPayload = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(2, 1, 0) +
            "Hello".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + int32(1_230) +
            "world".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + int32(2_500)
        val usltPayload = byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
            "plain fallback".toByteArray(StandardCharsets.UTF_8)
        val file = id3v23(frame("USLT", usltPayload) + frame("SYLT", syltPayload))

        val result = EmbeddedLyricsBinaryParser.parse(file)

        assertEquals(EmbeddedLyricsSource.ID3_SYLT, result?.source)
        assertEquals("[00:01.23]Hello\n[00:02.50]world", result?.text)
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

    private fun frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(StandardCharsets.ISO_8859_1) + int32(payload.size) + byteArrayOf(0, 0) + payload

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
