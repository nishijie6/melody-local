package com.melody.local.lyrics

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LyricsRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LyricsRepository(context)

    @Test
    fun importsLoadsAndDeletesPrivateLyricCopy() = runBlocking {
        val songId = 9_001L
        repository.delete(songId)
        val source = File(context.cacheDir, "lyrics-roundtrip.lrc").apply {
            writeText("[00:01.00]第一句\n[00:03.00]第二句")
        }

        val imported = repository.import(songId, Uri.fromFile(source))
        source.delete()

        assertEquals(listOf("第一句", "第二句"), imported.lines.map { it.text })
        assertEquals(imported, repository.load(songId))

        repository.delete(songId)
        assertNull(repository.load(songId))
    }

    @Test
    fun savesAndReadsEditedLyricsAtomically() = runBlocking {
        val songId = 9_008L
        repository.delete(songId)
        val edited = "[00:01.20]原文\n[00:01.20]Translation"

        val parsed = repository.save(songId, edited)

        assertEquals(listOf("原文", "Translation"), parsed.lines.map { it.text })
        assertEquals(edited, repository.readRaw(songId))
        assertEquals(parsed, repository.load(songId))
        repository.delete(songId)
    }

    @Test
    fun automaticAdoptionNeverOverwritesAnExistingLyric() = runBlocking {
        val songId = 9_009L
        repository.delete(songId)
        repository.save(songId, "[00:01.00]manual")
        val automaticSource = File(context.cacheDir, "automatic-lyrics.lrc").apply {
            writeText("[00:01.00]automatic")
        }

        assertNull(repository.saveIfAbsent(songId, "[00:01.00]downloaded"))
        assertNull(repository.importIfAbsent(songId, Uri.fromFile(automaticSource)))
        assertEquals("[00:01.00]manual", repository.readRaw(songId))

        automaticSource.delete()
        repository.delete(songId)
    }

    @Test
    fun automaticDiscoverySuppressionSurvivesSongIdRemapping() = runBlocking {
        val oldId = 9_010L
        val newId = 9_011L
        repository.setAutomaticDiscoverySuppressed(oldId, false)
        repository.setAutomaticDiscoverySuppressed(newId, false)
        repository.setAutomaticDiscoverySuppressed(oldId, true)
        assertNull(repository.saveIfAbsent(oldId, "[00:01.00]must not return"))

        repository.remap(oldId, newId)

        assertFalse(repository.isAutomaticDiscoverySuppressed(oldId))
        assertEquals(true, repository.isAutomaticDiscoverySuppressed(newId))
        repository.setAutomaticDiscoverySuppressed(newId, false)
    }

    @Test
    fun rejectsAFileWithoutDisplayableLyrics() {
        val source = File(context.cacheDir, "empty-lyrics.lrc").apply {
            writeText("[ar:Artist]\n[ti:Title]")
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.import(9_002L, Uri.fromFile(source)) }
        }
    }

    @Test
    fun enforcesTheRepositoryLimitAndRejectsUnreadableUris() {
        runBlocking {
            val exactLimit = File(context.cacheDir, "exact-limit.lrc").apply {
                outputStream().buffered().use { output ->
                    val chunk = ByteArray(256) { 'a'.code.toByte() }.apply {
                        this[lastIndex] = '\n'.code.toByte()
                    }
                    repeat(8_192) { output.write(chunk) }
                }
            }
            val imported = repository.import(9_003L, Uri.fromFile(exactLimit))
            assertEquals(8_192, imported.lines.size)
            repository.delete(9_003L)
        }

        assertThrows(Exception::class.java) {
            runBlocking {
                repository.import(9_004L, Uri.fromFile(File(context.cacheDir, "missing.lrc")))
            }
        }
    }

    @Test
    fun deleteRequestedDuringImportRunsAfterTheImportAndDoesNotResurrectLyrics() = runBlocking {
        val songId = 9_005L
        repository.delete(songId)
        val readStarted = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val bytes = "[00:01.00]temporary".toByteArray()
        val serializedRepository = LyricsRepository(context) {
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readStarted.countDown()
                    check(allowRead.await(5, TimeUnit.SECONDS)) { "timed out waiting to resume import" }
                    return super.read(buffer, offset, length)
                }
            }
        }

        val importJob = async(Dispatchers.IO) {
            serializedRepository.import(songId, Uri.parse("content://lyrics/slow"))
        }
        assertEquals(true, readStarted.await(5, TimeUnit.SECONDS))
        val deleteJob = async(Dispatchers.IO) { serializedRepository.delete(songId) }
        delay(100)
        assertFalse(deleteJob.isCompleted)

        allowRead.countDown()
        importJob.await()
        deleteJob.await()

        assertNull(serializedRepository.load(songId))
    }

    @Test
    fun rejectsNullStreamsAndTreatsOversizedPrivateFilesAsMissing() = runBlocking {
        val nullStreamRepository = LyricsRepository(context) { null }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                nullStreamRepository.import(9_006L, Uri.parse("content://lyrics/null"))
            }
        }

        val songId = 9_007L
        val privateFile = File(context.filesDir, "lyrics/$songId.lrc")
        privateFile.parentFile?.mkdirs()
        privateFile.writeText("a".repeat(2 * 1024 * 1024 + 1))

        assertNull(repository.load(songId))
        privateFile.delete()
        Unit
    }
}
