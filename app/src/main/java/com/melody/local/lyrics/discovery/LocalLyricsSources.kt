package com.melody.local.lyrics.discovery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LOCAL_LYRIC_BYTES = 2L * 1024L * 1024L
private const val MAX_DIRECTORY_CANDIDATES = 5_000

class FileSystemSameDirectoryLyricsSource(
    private val audioFileFor: (LyricsTrack) -> File?,
) : LocalLyricsSource {
    override suspend fun find(track: LyricsTrack): LocalLyricsLookup = withContext(Dispatchers.IO) {
        val audioFile = audioFileFor(track)
            ?: return@withContext LocalLyricsLookup.Unavailable("没有可读取的歌曲文件路径")
        val directory = audioFile.parentFile
            ?: return@withContext LocalLyricsLookup.Unavailable("歌曲没有父目录")
        if (!directory.isDirectory || !directory.canRead()) {
            return@withContext LocalLyricsLookup.Unavailable("无法读取歌曲所在目录")
        }

        val candidates = runCatching {
            directory.listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isFile)
                .filter { it.extension.equals("lrc", ignoreCase = true) }
                .filter { it.length() in 0..MAX_LOCAL_LYRIC_BYTES }
                .take(MAX_DIRECTORY_CANDIDATES)
                .map { file ->
                    LocalLyricsCandidate(
                        location = file.toURI().toString(),
                        displayName = file.name,
                        sizeBytes = file.length(),
                    )
                }
                .toList()
        }.getOrElse { error ->
            return@withContext LocalLyricsLookup.Failure(
                message = error.message ?: "读取歌曲目录失败",
                cause = error,
            )
        }
        rankedLookup(
            track = track.copy(sourceFileName = track.sourceFileName ?: audioFile.name),
            candidates = candidates,
        )
    }
}

/**
 * Finds indexed `.lrc` files next to a MediaStore audio item. On Android 10+ this deliberately
 * avoids the deprecated raw filesystem path. Some device MediaStore implementations do not index
 * text files; callers can add a SAF-backed [LocalLyricsSource] alongside this source in that case.
 */
class MediaStoreSameDirectoryLyricsSource(
    context: Context,
    private val audioUriFor: (LyricsTrack) -> Uri?,
) : LocalLyricsSource {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun find(track: LyricsTrack): LocalLyricsLookup = withContext(Dispatchers.IO) {
        val audioUri = audioUriFor(track)
            ?: return@withContext LocalLyricsLookup.Unavailable("没有可查询的 MediaStore 歌曲 URI")
        try {
            val location = queryAudioLocation(audioUri)
                ?: return@withContext LocalLyricsLookup.Unavailable("MediaStore 未返回歌曲目录")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val audioFile = location.absolutePath?.let(::File)
                    ?: return@withContext LocalLyricsLookup.Unavailable("MediaStore 未返回歌曲文件路径")
                return@withContext FileSystemSameDirectoryLyricsSource { audioFile }
                    .find(track.copy(sourceFileName = location.displayName))
            }

            val relativePath = location.relativePath
                ?: return@withContext LocalLyricsLookup.Unavailable("MediaStore 未返回歌曲相对目录")
            val filesUri = MediaStore.Files.getContentUri(volumeName(audioUri))
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            )
            val candidates = buildList {
                resolver.query(
                    filesUri,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(relativePath),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext() && size < MAX_DIRECTORY_CANDIDATES) {
                        val displayName = cursor.getString(nameColumn) ?: continue
                        if (!displayName.substringAfterLast('.', "").equals("lrc", ignoreCase = true)) {
                            continue
                        }
                        val lyricSize = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn)
                        if (lyricSize != null && lyricSize !in 0..MAX_LOCAL_LYRIC_BYTES) continue
                        val candidateUri = ContentUris.withAppendedId(filesUri, cursor.getLong(idColumn))
                        val readable = runCatching {
                            resolver.openAssetFileDescriptor(candidateUri, "r")?.use { true } ?: false
                        }.getOrDefault(false)
                        if (readable) {
                            add(
                                LocalLyricsCandidate(
                                    location = candidateUri.toString(),
                                    displayName = displayName,
                                    sizeBytes = lyricSize,
                                ),
                            )
                        }
                    }
                }
            }
            rankedLookup(
                track = track.copy(sourceFileName = track.sourceFileName ?: location.displayName),
                candidates = candidates,
            )
        } catch (error: SecurityException) {
            LocalLyricsLookup.Unavailable("系统未授予同目录歌词读取权限")
        } catch (error: Exception) {
            LocalLyricsLookup.Failure(
                message = error.message ?: "查询同目录歌词失败",
                cause = error,
            )
        }
    }

    private fun queryAudioLocation(uri: Uri): AudioLocation? {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
            )
        }
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AudioLocation(
                    displayName = displayName,
                    relativePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                AudioLocation(
                    displayName = displayName,
                    absolutePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA),
                    ),
                )
            }
        }
    }

    private fun volumeName(uri: Uri): String {
        // MediaStore.VOLUME_EXTERNAL was added in API 29, but the legacy external volume has
        // always used this stable provider name.
        val fallback = uri.pathSegments.firstOrNull().orEmpty().ifBlank { "external" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getVolumeName(uri) }.getOrDefault(fallback)
        } else {
            fallback
        }
    }

    private data class AudioLocation(
        val displayName: String,
        val relativePath: String? = null,
        val absolutePath: String? = null,
    )

}

/**
 * SAF fallback for devices that do not expose non-media `.lrc` rows through MediaStore. The caller
 * persists an ACTION_OPEN_DOCUMENT_TREE read grant and maps each song to its actual parent tree.
 */
class DocumentTreeSameDirectoryLyricsSource(
    context: Context,
    private val directoryTreeFor: (LyricsTrack) -> Uri?,
) : LocalLyricsSource {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun find(track: LyricsTrack): LocalLyricsLookup = withContext(Dispatchers.IO) {
        val treeUri = directoryTreeFor(track)
            ?: return@withContext LocalLyricsLookup.Unavailable("未授权歌曲所在文件夹")
        try {
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val candidates = buildList {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    val nameColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    val sizeColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_SIZE,
                    )
                    while (cursor.moveToNext() && size < MAX_DIRECTORY_CANDIDATES) {
                        val displayName = cursor.getString(nameColumn) ?: continue
                        if (!displayName.substringAfterLast('.', "").equals("lrc", ignoreCase = true)) {
                            continue
                        }
                        val lyricSize = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn)
                        if (lyricSize != null && lyricSize !in 0..MAX_LOCAL_LYRIC_BYTES) continue
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idColumn),
                        )
                        add(
                            LocalLyricsCandidate(
                                location = documentUri.toString(),
                                displayName = displayName,
                                sizeBytes = lyricSize,
                            ),
                        )
                    }
                }
            }
            rankedLookup(track, candidates)
        } catch (error: SecurityException) {
            LocalLyricsLookup.Unavailable("文件夹授权已失效，请重新选择歌曲目录")
        } catch (error: Exception) {
            LocalLyricsLookup.Failure(
                message = error.message ?: "读取已授权歌词目录失败",
                cause = error,
            )
        }
    }
}

internal fun rankedLookup(
    track: LyricsTrack,
    candidates: List<LocalLyricsCandidate>,
): LocalLyricsLookup {
    val ranked = LyricsMatchScorer.rankLocal(track, candidates)
    val best = ranked.firstOrNull { it.canAutoImport }
        ?: return LocalLyricsLookup.NoMatch(candidateCount = candidates.size)
    return LocalLyricsLookup.Found(
        best = best,
        alternatives = ranked.filterNot { it.candidate.location == best.candidate.location },
    )
}
