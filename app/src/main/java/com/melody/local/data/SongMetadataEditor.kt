package com.melody.local.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun interface SongMetadataEditor {
    suspend fun save(
        song: Song,
        title: String,
        artist: String,
        album: String,
        replacementArtwork: Uri?,
    ): Song
}

internal data class NormalizedSongMetadata(
    val title: String,
    val artist: String,
    val album: String,
)

internal object SongMetadataConstraints {
    const val MAX_TEXT_LENGTH = 200
    const val MAX_ARTWORK_MIB = 20
    const val MAX_ARTWORK_BYTES = MAX_ARTWORK_MIB.toLong() * 1024 * 1024
    const val MAX_ARTWORK_DIMENSION = 1024

    fun fieldsAreValid(title: String, artist: String, album: String): Boolean =
        title.isNotBlank() && listOf(title, artist, album).all {
            it.trim().length <= MAX_TEXT_LENGTH
        }

    fun normalize(title: String, artist: String, album: String): NormalizedSongMetadata {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "歌曲标题不能为空" }
        require(listOf(cleanTitle, artist.trim(), album.trim()).all { it.length <= MAX_TEXT_LENGTH }) {
            "标题、歌手和专辑不能超过 $MAX_TEXT_LENGTH 个字符"
        }
        return NormalizedSongMetadata(
            title = cleanTitle,
            artist = artist.trim().ifBlank { "未知歌手" },
            album = album.trim().ifBlank { "未知专辑" },
        )
    }
}

/** App-local metadata; editing never rewrites or moves the source audio file. */
class LocalSongMetadataEditor(
    context: Context,
    private val metadata: SongMetadataStore,
    private val artworkDirectory: File = File(context.applicationContext.filesDir, "artwork"),
) : SongMetadataEditor {
    private val appContext = context.applicationContext

    override suspend fun save(
        song: Song,
        title: String,
        artist: String,
        album: String,
        replacementArtwork: Uri?,
    ): Song {
        val normalized = SongMetadataConstraints.normalize(title, artist, album)
        // Once Save is accepted, finish the file/DB commit even if the Activity is closed.
        // On a DB failure only the newly created artwork is removed; old metadata stays usable.
        return withContext(NonCancellable + Dispatchers.IO) {
            var newArtwork: File? = null
            try {
                val previouslyDisplayedArtwork = song.albumArtUri
                    ?.toString()
                    ?.let(::appOwnedArtworkFileOrNull)
                newArtwork = replacementArtwork?.let(::copyArtwork)
                val updated = song.copy(
                    title = normalized.title,
                    artist = normalized.artist,
                    album = normalized.album,
                    albumArtUri = newArtwork?.toUri() ?: song.albumArtUri,
                )
                metadata.put(
                    SongMetadataOverride(
                        songId = song.id,
                        title = updated.title,
                        artist = updated.artist,
                        album = updated.album,
                        artworkPath = updated.albumArtUri?.toString(),
                    )
                )
                if (newArtwork != null) {
                    runCatching { cleanupUnreferencedArtwork(previouslyDisplayedArtwork) }
                }
                updated
            } catch (error: Exception) {
                newArtwork?.delete()
                throw error
            }
        }
    }

    private fun copyArtwork(uri: Uri): File {
        check(artworkDirectory.isDirectory || artworkDirectory.mkdirs()) { "无法创建封面目录" }
        val stagedSource = File(
            artworkDirectory,
            "$ARTWORK_SOURCE_PREFIX${UUID.randomUUID()}.tmp",
        )
        try {
            copyArtworkSource(uri, stagedSource)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stagedSource.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) {
                "所选文件不是可读取的图片"
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (
                maxOf(options.outWidth, options.outHeight) / options.inSampleSize >
                SongMetadataConstraints.MAX_ARTWORK_DIMENSION
            ) {
                options.inSampleSize *= 2
            }
            var bitmap = BitmapFactory.decodeFile(stagedSource.absolutePath, options)
                ?: error("无法解码所选封面")
            val destination = File(artworkDirectory, "$ARTWORK_FILE_PREFIX${UUID.randomUUID()}.jpg")
            try {
                val oriented = applyExifOrientation(bitmap, stagedSource)
                if (oriented !== bitmap) {
                    bitmap.recycle()
                    bitmap = oriented
                }
                val maxDimension = maxOf(bitmap.width, bitmap.height)
                if (maxDimension > SongMetadataConstraints.MAX_ARTWORK_DIMENSION) {
                    val scale = SongMetadataConstraints.MAX_ARTWORK_DIMENSION.toFloat() / maxDimension
                    val scaled = bitmap.scale(
                        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    if (scaled !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaled
                    }
                }
                FileOutputStream(destination).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                        "无法保存封面"
                    }
                    output.fd.sync()
                }
                return destination
            } catch (error: Exception) {
                destination.delete()
                throw error
            } finally {
                bitmap.recycle()
            }
        } finally {
            stagedSource.delete()
        }
    }

    private fun copyArtworkSource(uri: Uri, destination: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("无法读取所选封面，请重新选择图片")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= SongMetadataConstraints.MAX_ARTWORK_BYTES) {
                        "封面图片不能超过 ${SongMetadataConstraints.MAX_ARTWORK_MIB} MiB"
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, source: File): Bitmap {
        val exif = runCatching { ExifInterface(source.absolutePath) }.getOrNull() ?: return bitmap
        val rotationDegrees = exif.rotationDegrees
        val flipped = exif.isFlipped
        if (!flipped && rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply {
            if (flipped) setScale(-1f, 1f)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private suspend fun cleanupUnreferencedArtwork(exempt: File?) {
        val directory = artworkDirectory.canonicalFile
        val referencedPaths = metadata.getAll().values.mapNotNull { value ->
            value.artworkPath?.let(::appOwnedArtworkFileOrNull)?.canonicalPath
        }.toSet()
        val cutoff = System.currentTimeMillis() - UNREFERENCED_ARTWORK_GRACE_MS
        directory.listFiles().orEmpty()
            .asSequence()
            .filter {
                it.isFile &&
                    it.name.startsWith(ARTWORK_FILE_PREFIX) &&
                    it.extension.equals("jpg", ignoreCase = true)
            }
            .filter { it.canonicalPath != exempt?.canonicalPath }
            .filter { it.canonicalPath !in referencedPaths }
            .filter { it.lastModified() in 1L..cutoff }
            .forEach(File::delete)
    }

    private fun appOwnedArtworkFileOrNull(value: String): File? {
        val uri = value.toUri()
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val directory = runCatching { artworkDirectory.canonicalFile }.getOrNull() ?: return null
        return file.takeIf {
            it.parentFile == directory && it.name.startsWith(ARTWORK_FILE_PREFIX)
        }
    }

    private companion object {
        const val ARTWORK_FILE_PREFIX = "song-edit-"
        const val ARTWORK_SOURCE_PREFIX = "song-edit-source-"
        const val UNREFERENCED_ARTWORK_GRACE_MS = 24L * 60 * 60 * 1_000
    }
}
