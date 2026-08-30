package com.melody.local.lyrics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

class LyricsRepositoryTest {

    @Test
    fun readsAFileAtTheConfiguredLimit() {
        val bytes = ByteArray(32) { it.toByte() }

        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBytesWithLimit(bytes.size))
    }

    @Test
    fun rejectsAFileAsSoonAsItExceedsTheConfiguredLimit() {
        val bytes = ByteArray(33)

        assertThrows(LyricFileTooLargeException::class.java) {
            ByteArrayInputStream(bytes).readBytesWithLimit(32)
        }
    }

    @Test
    fun failedReplacementPreservesThePreviousLyricAndCleansTemporaryFile() {
        val directory = Files.createTempDirectory("lyrics-atomic-test").toFile()
        val target = directory.resolve("1.lrc").apply { writeText("original") }

        assertThrows(IOException::class.java) {
            replaceFileAtomically(
                target = target,
                bytes = "replacement".toByteArray(),
                writeTemporary = { temporary, content ->
                    temporary.writeBytes(content.copyOf(3))
                    throw IOException("simulated write failure")
                },
            )
        }

        assertArrayEquals("original".toByteArray(), target.readBytes())
        assertEquals(emptyList<String>(), directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.map { it.name })
        directory.deleteRecursively()
    }

    @Test
    fun toleratesInputStreamsThatTemporarilyReturnZeroBytes() {
        val expected = "lyrics".toByteArray()
        val delegate = ByteArrayInputStream(expected)
        val zeroThenData = object : InputStream() {
            var firstBulkRead = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (firstBulkRead) {
                    firstBulkRead = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }

        assertArrayEquals(expected, zeroThenData.readBytesWithLimit(expected.size))
    }

    @Test
    fun fallsBackToAReplacingMoveWhenAtomicMoveIsUnavailable() {
        val directory = Files.createTempDirectory("lyrics-move-fallback").toFile()
        val target = directory.resolve("2.lrc")
        val attemptedModes = mutableListOf<Boolean>()

        replaceFileAtomically(
            target = target,
            bytes = "replacement".toByteArray(),
            moveTemporary = { source, destination, atomic ->
                attemptedModes += atomic
                if (atomic) throw AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test")
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            },
        )

        assertEquals(listOf(true, false), attemptedModes)
        assertArrayEquals("replacement".toByteArray(), target.readBytes())
        directory.deleteRecursively()
    }
}

