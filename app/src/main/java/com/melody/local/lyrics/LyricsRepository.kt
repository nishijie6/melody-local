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
    suspend fun delete(songId: Long)
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
            val bytes = requireNotNull(inputStreamOpener(uri)) {
                "无法读取所选歌词文件"
            }.use { it.readBytesWithLimit(MAX_LYRIC_SIZE_BYTES) }
            val parsed = LrcParser.parse(LrcParser.decode(bytes))
            require(parsed.lines.isNotEmpty()) { "歌词文件中没有可显示的内容" }
            replaceFileAtomically(lyricFile(songId), bytes)
            parsed
        }
    }

    override suspend fun delete(songId: Long): Unit = withContext(Dispatchers.IO) {
        songLock(songId).withLock { lyricFile(songId).delete() }
        Unit
    }

    private fun lyricFile(songId: Long) = File(lyricsDirectory, "$songId.lrc")

    private fun songLock(songId: Long): Mutex = songLocks.getOrPut(songId) { Mutex() }

    private companion object {
        const val MAX_LYRIC_SIZE_BYTES = 2 * 1024 * 1024
    }
}
