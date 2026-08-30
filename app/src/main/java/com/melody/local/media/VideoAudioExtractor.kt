package com.melody.local.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.melody.local.R
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.RoomSongMetadataStore
import com.melody.local.data.SongMetadataOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VideoAudioExtractor {
    suspend fun enqueue(request: VideoImportRequest): Boolean
    suspend fun cancel()
    suspend fun currentState(): MediaOperationState
}

class WorkManagerVideoAudioExtractor(context: Context) : VideoAudioExtractor {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun enqueue(request: VideoImportRequest): Boolean = withContext(Dispatchers.IO) {
        val active = currentWorkInfo()?.state?.let { !it.isFinished } == true
        if (active) return@withContext false
        val work = OneTimeWorkRequestBuilder<VideoAudioImportWorker>()
            .setInputData(
                workDataOf(
                    KEY_SOURCE_URI to request.uri.toString(),
                    KEY_TITLE to request.title.trim(),
                    KEY_ARTIST to request.artist.trim(),
                    KEY_ALBUM to request.album.trim(),
                    KEY_EXTRACT_ARTWORK to request.extractArtwork,
                )
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
        preferences.edit { putString(KEY_WORK_ID, work.id.toString()) }
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
        true
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get()
        Unit
    }

    override suspend fun currentState(): MediaOperationState = withContext(Dispatchers.IO) {
        val info = currentWorkInfo() ?: return@withContext MediaOperationState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                MediaOperationState.Preparing("正在准备视频音轨…")
            WorkInfo.State.RUNNING -> MediaOperationState.Processing(
                currentFile = info.progress.getString(KEY_CURRENT_FILE).orEmpty(),
                completed = 0,
                total = 1,
                progressPercent = info.progress.getInt(KEY_PROGRESS, 0).coerceIn(0, 100),
            )
            WorkInfo.State.SUCCEEDED -> MediaOperationState.Completed(
                MediaOperationSummary(
                    imported = 1,
                    songId = info.outputData.getLong(KEY_SONG_ID, -1L).takeIf { it >= 0L },
                    outputUri = info.outputData.getString(KEY_OUTPUT_URI),
                )
            )
            WorkInfo.State.CANCELLED -> MediaOperationState.Cancelled(
                MediaOperationSummary(cancelled = 1)
            )
            WorkInfo.State.FAILED -> MediaOperationState.Failed(
                info.outputData.getString(KEY_ERROR) ?: "视频音轨导入失败"
            )
        }
    }

    private fun currentWorkInfo(): WorkInfo? {
        val id = preferences.getString(KEY_WORK_ID, null)?.let(UUID::fromString) ?: return null
        return runCatching { workManager.getWorkInfoById(id).get() }.getOrNull()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "video-audio-import"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_EXTRACT_ARTWORK = "extract_artwork"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_SONG_ID = "song_id"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val PREFERENCES_NAME = "video_audio_import"
        private const val KEY_WORK_ID = "work_id"
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class VideoAudioImportWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val resolver = applicationContext.contentResolver
    private var transformer: Transformer? = null

    override suspend fun doWork(): Result {
        createNotificationChannel()
        setForeground(createForegroundInfo(0, "正在准备视频音轨"))
        val sourceUri = inputData.getString(WorkManagerVideoAudioExtractor.KEY_SOURCE_URI)?.toUri()
            ?: return failure("缺少视频地址")
        val title = inputData.getString(WorkManagerVideoAudioExtractor.KEY_TITLE)
            ?.trim().orEmpty().ifBlank { "视频提取歌曲" }
        val artist = inputData.getString(WorkManagerVideoAudioExtractor.KEY_ARTIST)
            ?.trim().orEmpty().ifBlank { "未知歌手" }
        val album = inputData.getString(WorkManagerVideoAudioExtractor.KEY_ALBUM)
            ?.trim().orEmpty().ifBlank { "视频提取" }
        val extractArtwork = inputData.getBoolean(
            WorkManagerVideoAudioExtractor.KEY_EXTRACT_ARTWORK,
            true,
        )
        val displayName = queryDisplayName(sourceUri) ?: title
        val tempDirectory = File(applicationContext.cacheDir, "video-audio-import").apply { mkdirs() }
        val tempOutput = File(tempDirectory, "${id}.m4a")
        tempDirectory.listFiles()?.forEach(File::delete)
        var publishedUri: Uri? = null
        var publishedSongId: Long? = null
        var savedArtworkUri: Uri? = null

        return try {
            cleanupInterruptedImports()
            val audioMime = inspectAudioTrack(sourceUri)
            audioExportStrategy(audioMime)
            updateProgress(2, displayName)
            tempOutput.delete()
            exportAudio(sourceUri, tempOutput, displayName)
            ensureActiveWork()
            require(tempOutput.exists() && tempOutput.length() > 0L) { "没有生成可用的音频文件" }
            val artwork = if (extractArtwork) extractFirstFrame(sourceUri) else null
            val published = publishAudio(tempOutput, title, artist, album)
            publishedUri = published.uri
            publishedSongId = published.songId
            val artworkUri = try {
                artwork?.let { saveArtwork(it, published.songId) }
            } finally {
                artwork?.recycle()
            }
            savedArtworkUri = artworkUri
            RoomSongMetadataStore(PlaylistDatabase.getInstance(applicationContext).songStateDao()).put(
                SongMetadataOverride(
                    songId = published.songId,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkPath = artworkUri?.toString(),
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishedCount = resolver.update(
                    published.uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                check(publishedCount == 1) { "系统音乐库未能发布导出的歌曲" }
            }
            clearLegacyPendingMarker()
            updateProgress(100, displayName)
            Result.success(
                workDataOf(
                    WorkManagerVideoAudioExtractor.KEY_SONG_ID to published.songId,
                    WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI to published.uri.toString(),
                )
            )
        } catch (cancelled: CancellationException) {
            publishedUri?.let { runCatching { resolver.delete(it, null, null) } }
            publishedSongId?.let { songId ->
                runCatching {
                    RoomSongMetadataStore(
                        PlaylistDatabase.getInstance(applicationContext).songStateDao()
                    ).delete(songId)
                }
            }
            savedArtworkUri?.path?.let(::File)?.delete()
            throw cancelled
        } catch (error: Throwable) {
            publishedUri?.let { runCatching { resolver.delete(it, null, null) } }
            publishedSongId?.let { songId ->
                runCatching {
                    RoomSongMetadataStore(
                        PlaylistDatabase.getInstance(applicationContext).songStateDao()
                    ).delete(songId)
                }
            }
            savedArtworkUri?.path?.let(::File)?.delete()
            failure(error.userFacingImportMessage())
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                transformer?.cancel()
                transformer = null
            }
            tempOutput.delete()
            tempDirectory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach(File::delete)
        }
    }

    private suspend fun inspectAudioTrack(uri: Uri): String {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(applicationContext, uri, null)
                var audioMime: String? = null
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (format.containsKey("pssh") ||
                        (Build.VERSION.SDK_INT >= 29 && format.containsKey("ca-system-id"))
                    ) {
                        throw IllegalArgumentException("视频受到 DRM 或内容保护，无法导入")
                    }
                    if (audioMime == null && mime?.startsWith("audio/") == true) audioMime = mime
                }
                requireNotNull(audioMime) { "所选视频没有可解码的音轨" }
            } catch (error: SecurityException) {
                throw IllegalArgumentException("无法访问所选视频，请重新选择", error)
            } finally {
                extractor.release()
            }
        }
    }

    private suspend fun exportAudio(sourceUri: Uri, output: File, displayName: String) {
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setRemoveVideo(true)
            .build()
        coroutineScope {
            val completion = async {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val builtTransformer = Transformer.Builder(applicationContext)
                        .setLooper(Looper.getMainLooper())
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(exportException)
                                    }
                                }
                            }
                        )
                        .build()
                    transformer = builtTransformer
                    Handler(Looper.getMainLooper()).post {
                        runCatching { builtTransformer.start(editedItem, output.absolutePath) }
                            .onFailure { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                    }
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { builtTransformer.cancel() }
                    }
                }
            }
            while (!completion.isCompleted) {
                ensureActiveWork()
                val percent = withContext(Dispatchers.Main.immediate) {
                    val holder = ProgressHolder()
                    val state = transformer?.getProgress(holder)
                        ?: Transformer.PROGRESS_STATE_NOT_STARTED
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 5
                }
                updateProgress(percent.coerceIn(3, 94), displayName)
                delay(350L)
            }
            completion.await()
        }
        updateProgress(95, displayName)
    }

    private suspend fun updateProgress(percent: Int, displayName: String) {
        setProgress(
            workDataOf(
                WorkManagerVideoAudioExtractor.KEY_PROGRESS to percent,
                WorkManagerVideoAudioExtractor.KEY_CURRENT_FILE to displayName,
            )
        )
        setForeground(createForegroundInfo(percent, displayName))
    }

    private fun inspectAvailableNames(relativePath: String): List<String> {
        val names = mutableListOf<String>()
        val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
        val selection: String?
        val args: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
            args = arrayOf(relativePath)
        } else {
            selection = null
            args = null
        }
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) cursor.getString(column)?.let(names::add)
        }
        return names
    }

    private suspend fun publishAudio(
        source: File,
        title: String,
        artist: String,
        album: String,
    ): PublishedAudio = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext publishLegacyAudio(source, title, artist, album)
        }
        val relativePath = "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/"
        val desiredName = "${safeAudioFileBase(title)}.m4a"
        val outputName = uniqueDisplayName(desiredName, inspectAvailableNames(relativePath))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在系统音乐库中创建文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入系统音乐库")
            PublishedAudio(ContentUris.parseId(uri), uri)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun publishLegacyAudio(
        source: File,
        title: String,
        artist: String,
        album: String,
    ): PublishedAudio {
        require(
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { "需要存储写入权限才能保存提取的歌曲" }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/视频提取",
        ).apply { mkdirs() }
        val desired = "${safeAudioFileBase(title)}.m4a"
        val target = File(directory, uniqueDisplayName(desired, directory.list()?.toList().orEmpty()))
        val marker = legacyPendingPreferences()
        val temporary = File(directory, ".${id}-${target.name}.yinlan-pending")
        marker.edit { putString(KEY_LEGACY_PENDING_PATH, temporary.absolutePath) }
        var targetCreated = false
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = false)
                temporary.delete()
            }
            targetCreated = true
            marker.edit { putString(KEY_LEGACY_PENDING_PATH, target.absolutePath) }
            val uri = scanFile(target)
            val songId = ContentUris.parseId(uri)
            marker.edit {
                putString(KEY_LEGACY_PENDING_URI, uri.toString())
                putLong(KEY_LEGACY_PENDING_SONG_ID, songId)
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }, null, null)
            return PublishedAudio(songId, uri)
        } catch (error: Throwable) {
            temporary.delete()
            if (targetCreated) target.delete()
            clearLegacyPendingMarker()
            throw error
        }
    }

    private suspend fun scanFile(file: File): Uri = suspendCancellableCoroutine { continuation ->
        MediaScannerConnection.scanFile(
            applicationContext,
            arrayOf(file.absolutePath),
            arrayOf("audio/mp4"),
        ) { _, uri ->
            if (uri != null) continuation.resume(uri)
            else continuation.resumeWithException(IOException("系统音乐库未能收录导出的歌曲"))
        }
    }

    private fun extractFirstFrame(uri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(applicationContext, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private suspend fun cleanupInterruptedImports() = withContext(Dispatchers.IO) {
        val metadataStore = RoomSongMetadataStore(
            PlaylistDatabase.getInstance(applicationContext).songStateDao()
        )
        val legacyMarker = legacyPendingPreferences()
        legacyMarker.getString(KEY_LEGACY_PENDING_URI, null)?.let(Uri::parse)?.let { uri ->
            runCatching { resolver.delete(uri, null, null) }
        }
        legacyMarker.getString(KEY_LEGACY_PENDING_PATH, null)?.let(::File)?.delete()
        legacyMarker.getLong(KEY_LEGACY_PENDING_SONG_ID, -1L).takeIf { it >= 0L }?.let { songId ->
            metadataStore.delete(songId)
        }
        clearLegacyPendingMarker()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/"
            val pending = mutableListOf<Pair<Long, Uri>>()
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Audio.Media.IS_PENDING} = 1",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val songId = cursor.getLong(0)
                    pending += songId to ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        songId,
                    )
                }
            }
            pending.forEach { (songId, uri) ->
                runCatching { resolver.delete(uri, null, null) }
                metadataStore.delete(songId)
            }
        }
        val referencedArtwork = metadataStore.getAll().values
            .mapNotNull { it.artworkPath?.let(Uri::parse)?.path }
            .toSet()
        File(applicationContext.filesDir, "artwork").listFiles()?.forEach { artwork ->
            if (artwork.absolutePath !in referencedArtwork) artwork.delete()
        }
    }

    private fun legacyPendingPreferences() = applicationContext.getSharedPreferences(
        LEGACY_PENDING_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun clearLegacyPendingMarker() {
        legacyPendingPreferences().edit { clear() }
    }

    private fun saveArtwork(bitmap: Bitmap, songId: Long): Uri {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxEdge > MAX_ARTWORK_EDGE) {
            val scale = MAX_ARTWORK_EDGE.toFloat() / maxEdge
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
            )
        } else bitmap
        val directory = File(applicationContext.filesDir, "artwork").apply { mkdirs() }
        val temporary = File(directory, "$songId.jpg.tmp")
        val target = File(directory, "$songId.jpg")
        FileOutputStream(temporary).use { output ->
            require(scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "无法保存视频封面" }
            output.fd.sync()
        }
        if (scaled !== bitmap) scaled.recycle()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target.toUri()
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "视频音轨导入",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun createForegroundInfo(progress: Int, current: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在提取视频音轨")
            .setContentText(current)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, progress <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(WorkManagerVideoAudioExtractor.KEY_ERROR to message)
    )

    private fun ensureActiveWork() {
        if (isStopped) throw CancellationException("任务已取消")
    }

    private fun Throwable.userFacingImportMessage(): String = when (this) {
        is ExportException -> "音轨解码或转码失败：${message ?: "格式不受设备支持"}"
        is SecurityException -> "无法访问所选视频，请重新选择"
        is IOException -> if (message?.contains("space", ignoreCase = true) == true) {
            "存储空间不足，无法完成导入"
        } else message ?: "读取或写入文件失败"
        is IllegalArgumentException -> message ?: "所选视频无法导入"
        else -> message ?: "视频音轨导入失败"
    }

    private data class PublishedAudio(val songId: Long, val uri: Uri)

    private companion object {
        const val MAX_ARTWORK_EDGE = 1024
        const val NOTIFICATION_CHANNEL_ID = "video_audio_import"
        const val NOTIFICATION_ID = 1301
        const val LEGACY_PENDING_PREFERENCES = "video_audio_legacy_pending"
        const val KEY_LEGACY_PENDING_PATH = "path"
        const val KEY_LEGACY_PENDING_URI = "uri"
        const val KEY_LEGACY_PENDING_SONG_ID = "song_id"
    }
}
