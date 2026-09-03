package com.melody.local.lyrics

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class LyricFileTooLargeException :
    IllegalArgumentException("歌词文件不能超过 2 MB")

internal fun InputStream.readBytesWithLimit(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) {
            val nextByte = read()
            if (nextByte < 0) break
            totalBytes++
            if (totalBytes > maxBytes) throw LyricFileTooLargeException()
            output.write(nextByte)
            continue
        }
        totalBytes += count
        if (totalBytes > maxBytes) throw LyricFileTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun replaceFileAtomically(
    target: File,
    bytes: ByteArray,
    writeTemporary: (File, ByteArray) -> Unit = { file, content ->
        FileOutputStream(file).use { output ->
            output.write(content)
            output.fd.sync()
        }
    },
    moveTemporary: (Path, Path, Boolean) -> Unit = { source, destination, atomic ->
        if (atomic) {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    },
) {
    target.parentFile?.mkdirs()
    val temporary = File.createTempFile("lyric-${target.nameWithoutExtension}-", ".tmp", target.parentFile)
    try {
        writeTemporary(temporary, bytes)
        try {
            moveTemporary(temporary.toPath(), target.toPath(), true)
        } catch (_: AtomicMoveNotSupportedException) {
            moveTemporary(temporary.toPath(), target.toPath(), false)
        }
    } finally {
        temporary.delete()
    }
}

interface LyricsStore {
    suspend fun load(songId: Long): ParsedLyrics?
    suspend fun import(songId: Long, uri: Uri): ParsedLyrics
    suspend fun importIfAbsent(songId: Long, uri: Uri): ParsedLyrics? =
        if (load(songId) == null) import(songId, uri) else null
    suspend fun readRaw(songId: Long): String? = null
    suspend fun save(songId: Long, text: String): ParsedLyrics =
        throw UnsupportedOperationException("歌词存储不支持编辑")
    suspend fun saveIfAbsent(songId: Long, text: String): ParsedLyrics? =
        if (load(songId) == null) save(songId, text) else null
    suspend fun isAutomaticDiscoverySuppressed(songId: Long): Boolean = false
    suspend fun setAutomaticDiscoverySuppressed(songId: Long, suppressed: Boolean) = Unit
    suspend fun delete(songId: Long)
    suspend fun remap(oldSongId: Long, newSongId: Long)
}

class LyricsRepository(
    private val context: Context,
    private val inputStreamOpener: (Uri) -> InputStream? = { uri ->
        context.contentResolver.openInputStream(uri)
    },
) : LyricsStore {
    private val lyricsDirectory = File(context.filesDir, "lyrics")
    private val songLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun load(songId: Long): ParsedLyrics? = withContext(Dispatchers.IO) {
        songLock(songId).withLock {
            val file = lyricFile(songId)
            if (!file.exists()) return@withLock null
            runCatching { LrcParser.parse(LrcParser.decode(file.readBytes())) }.getOrNull()
        }
    }

    override suspend fun import(songId: Long, uri: Uri): ParsedLyrics = withContext(Dispatchers.IO) {
        songLock(songId).withLock {
            importLocked(songId, uri)
        }
    }

    override suspend fun importIfAbsent(songId: Long, uri: Uri): ParsedLyrics? =
        withContext(Dispatchers.IO) {
            songLock(songId).withLock {
                if (lyricFile(songId).exists() || suppressionFile(songId).exists()) {
                    return@withLock null
                }
                importLocked(songId, uri)
            }
        }

    override suspend fun readRaw(songId: Long): String? = withContext(Dispatchers.IO) {
        songLock(songId).withLock {
            lyricFile(songId).takeIf(File::exists)?.readBytes()?.let(LrcParser::decode)
        }
    }

    override suspend fun save(songId: Long, text: String): ParsedLyrics = withContext(Dispatchers.IO) {
        songLock(songId).withLock {
            saveLocked(songId, text)
        }
    }

    override suspend fun saveIfAbsent(songId: Long, text: String): ParsedLyrics? =
        withContext(Dispatchers.IO) {
            songLock(songId).withLock {
                if (lyricFile(songId).exists() || suppressionFile(songId).exists()) {
                    return@withLock null
                }
                saveLocked(songId, text)
            }
        }

    override suspend fun delete(songId: Long): Unit = withContext(Dispatchers.IO) {
        songLock(songId).withLock { lyricFile(songId).delete() }
        Unit
    }

    override suspend fun isAutomaticDiscoverySuppressed(songId: Long): Boolean =
        withContext(Dispatchers.IO) {
            songLock(songId).withLock { suppressionFile(songId).exists() }
        }

    override suspend fun setAutomaticDiscoverySuppressed(
        songId: Long,
        suppressed: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        songLock(songId).withLock {
            val marker = suppressionFile(songId)
            if (suppressed) {
                marker.parentFile?.mkdirs()
                if (!marker.exists()) marker.createNewFile()
            } else {
                marker.delete()
            }
        }
        Unit
    }

    override suspend fun remap(oldSongId: Long, newSongId: Long): Unit =
        withContext(Dispatchers.IO) {
            if (oldSongId == newSongId) return@withContext
            val firstId = minOf(oldSongId, newSongId)
            val secondId = maxOf(oldSongId, newSongId)
            songLock(firstId).withLock {
                songLock(secondId).withLock secondLock@{
                    movePrivateFileIfPresent(lyricFile(oldSongId), lyricFile(newSongId))
                    movePrivateFileIfPresent(
                        suppressionFile(oldSongId),
                        suppressionFile(newSongId),
                    )
                }
            }
        }

    private fun lyricFile(songId: Long) = File(lyricsDirectory, "$songId.lrc")
    private fun suppressionFile(songId: Long) = File(lyricsDirectory, "$songId.no-auto")

    private fun movePrivateFileIfPresent(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun importLocked(songId: Long, uri: Uri): ParsedLyrics {
        val bytes = requireNotNull(inputStreamOpener(uri)) {
            "无法读取所选歌词文件"
        }.use { it.readBytesWithLimit(MAX_LYRIC_SIZE_BYTES) }
        val parsed = LrcParser.parse(LrcParser.decode(bytes))
        require(parsed.lines.isNotEmpty()) { "歌词文件中没有可显示的内容" }
        replaceFileAtomically(lyricFile(songId), bytes)
        return parsed
    }

    private fun saveLocked(songId: Long, text: String): ParsedLyrics {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LYRIC_SIZE_BYTES) throw LyricFileTooLargeException()
        val parsed = LrcParser.parse(text)
        require(parsed.lines.isNotEmpty()) { "歌词内容中没有可显示的内容" }
        replaceFileAtomically(lyricFile(songId), bytes)
        return parsed
    }

    private fun songLock(songId: Long): Mutex = songLocks.getOrPut(songId) { Mutex() }

    private companion object {
        const val MAX_LYRIC_SIZE_BYTES = 2 * 1024 * 1024
    }
}
