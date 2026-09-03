package com.melody.local.lyrics.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalLyricsSourcesTest {
    @Test
    fun `filesystem source only searches audio directory and accepts uppercase extension`() = runBlocking {
        val root = Files.createTempDirectory("yinlan-local-lyrics").toFile()
        try {
            val music = File(root, "music").apply { mkdirs() }
            val elsewhere = File(root, "elsewhere").apply { mkdirs() }
            val audio = File(music, "My Song.mp3").apply { writeBytes(byteArrayOf()) }
            File(music, "my song.LRC").writeText("[00:01.00]local")
            File(elsewhere, "My Song.lrc").writeText("[00:01.00]wrong directory")

            val result = FileSystemSameDirectoryLyricsSource { audio }.find(track("My Song.mp3"))

            assertTrue(result is LocalLyricsLookup.Found)
            result as LocalLyricsLookup.Found
            assertEquals("my song.LRC", result.best.candidate.displayName)
            assertEquals(100, result.best.score)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `filesystem source rejects oversized lyric and reports no match`() = runBlocking {
        val root = Files.createTempDirectory("yinlan-large-lyric").toFile()
        try {
            val audio = File(root, "Song.mp3").apply { writeBytes(byteArrayOf()) }
            File(root, "Song.lrc").apply { setLengthForTest(2L * 1024L * 1024L + 1L) }

            val result = FileSystemSameDirectoryLyricsSource { audio }.find(track("Song.mp3"))

            assertTrue(result is LocalLyricsLookup.NoMatch)
            assertEquals(0, (result as LocalLyricsLookup.NoMatch).candidateCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `filesystem source reports missing path separately`() = runBlocking {
        val result = FileSystemSameDirectoryLyricsSource { null }.find(track("Song.mp3"))

        assertTrue(result is LocalLyricsLookup.Unavailable)
    }

    private fun track(fileName: String) = LyricsTrack(
        songId = 1L,
        title = fileName.substringBeforeLast('.'),
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        sourceFileName = fileName,
    )

    private fun File.setLengthForTest(length: Long) {
        outputStream().use { output ->
            output.channel.truncate(length)
            output.channel.position(length - 1)
            output.write(0)
        }
    }
}
