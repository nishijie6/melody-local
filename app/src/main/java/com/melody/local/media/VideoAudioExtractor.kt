@file:Suppress("UseKtx")

package com.melody.local.media

import android.annotation.SuppressLint
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
import androidx.work.ListenableWorker
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

internal const val VIDEO_IMPORT_PENDING_TITLE_PREFIX = "com.melody.local:video-import:"
private const val VIDEO_IMPORT_WORK_PREFERENCES = "video_audio_import"
private const val LEGACY_KEY_CANCEL_REQUESTED_WORK_ID = "cancel_requested_work_id"
private const val KEY_CANCEL_REQUESTED_WORK_PREFIX = "unresolved_cancel:"
private const val VIDEO_IMPORT_COMPLETION_PREFERENCES = "video_audio_completed_imports"
private const val VIDEO_IMPORT_COMPLETION_KEY_PREFIX = "worker:"
private const val LEGACY_VIDEO_IMPORT_PENDING_PREFERENCES = "video_audio_legacy_pending"
private const val LEGACY_VIDEO_IMPORT_PENDING_RECORD_KEY = "record_v2"

private class LegacyScanTimeoutException : IOException(
    "系统音乐库收录超时；已保留文件和恢复记录，可稍后重试",
)

internal fun videoImportCancelRequestKey(workerId: UUID): String =
    "$KEY_CANCEL_REQUESTED_WORK_PREFIX$workerId"

private fun unresolvedVideoImportCancelIds(context: Context): Set<UUID> {
    val preferences = context.getSharedPreferences(
        VIDEO_IMPORT_WORK_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    val perWorker = preferences.all.keys.mapNotNull { key ->
        key.removePrefix(KEY_CANCEL_REQUESTED_WORK_PREFIX)
            .takeIf { key.startsWith(KEY_CANCEL_REQUESTED_WORK_PREFIX) }
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
    }
    val legacy = preferences.getString(LEGACY_KEY_CANCEL_REQUESTED_WORK_ID, null)
        ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
    return (perWorker + listOfNotNull(legacy)).toSet()
}

private fun persistUnresolvedVideoImportCancel(context: Context, workerId: UUID) {
    val committed = context.getSharedPreferences(
        VIDEO_IMPORT_WORK_PREFERENCES,
        Context.MODE_PRIVATE,
    ).edit().putLong(videoImportCancelRequestKey(workerId), System.currentTimeMillis()).commit()
    check(committed) { "无法保存视频导入取消状态" }
}

private fun unresolvedVideoImportCancelRequestedAt(context: Context, workerId: UUID): Long? {
    val value = context.getSharedPreferences(
        VIDEO_IMPORT_WORK_PREFERENCES,
        Context.MODE_PRIVATE,
    ).all[videoImportCancelRequestKey(workerId)]
    return when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }
}

private fun clearUnresolvedVideoImportCancel(context: Context, workerId: UUID): Boolean {
    val preferences = context.getSharedPreferences(
        VIDEO_IMPORT_WORK_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    val perWorkerKey = videoImportCancelRequestKey(workerId)
    val hasPerWorkerKey = preferences.contains(perWorkerKey)
    val hasLegacyKey = preferences.getString(LEGACY_KEY_CANCEL_REQUESTED_WORK_ID, null) ==
        workerId.toString()
    if (!hasPerWorkerKey && !hasLegacyKey) return true
    val editor = preferences.edit().remove(perWorkerKey)
    if (hasLegacyKey) editor.remove(LEGACY_KEY_CANCEL_REQUESTED_WORK_ID)
    return editor.commit()
}

private fun verifiedPublishedVideoImport(
    context: Context,
    workerId: UUID,
): MediaOperationSummary? = runCatching {
    val encoded = context.getSharedPreferences(
        VIDEO_IMPORT_COMPLETION_PREFERENCES,
        Context.MODE_PRIVATE,
    ).getString("$VIDEO_IMPORT_COMPLETION_KEY_PREFIX$workerId", null) ?: return@runCatching null
    val json = JSONObject(encoded)
    if (UUID.fromString(json.getString("workerId")) != workerId) return@runCatching null
    if (json.getString("state") !in setOf("PREPARED", "PUBLISHED")) return@runCatching null
    val uri = json.getString("outputUri").toUri()
    val songId = json.getLong("songId")
    if (ContentUris.parseId(uri) != songId) return@runCatching null
    val expectedSize = json.optLong("expectedSize", -1L)
    val expectedSha256 = json.optString("expectedSha256")
    if (expectedSize <= 0L || expectedSha256.length != 64) return@runCatching null
    val projection = buildList {
        add(MediaStore.Audio.Media._ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.IS_PENDING)
        }
    }.toTypedArray()
    val published = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst() || cursor.getLong(0) != songId) return@use false
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || cursor.getInt(1) == 0
    } ?: false
    if (!published) return@runCatching null
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    if (!inputMatchesExpectedContent(input, expectedSize, expectedSha256)) {
        return@runCatching null
    }
    MediaOperationSummary(imported = 1, songId = songId, outputUri = uri.toString())
}.getOrNull()

private enum class VideoImportReceiptPhase { NONE, ALLOCATED, PREPARED, PUBLISHED, INVALID }

private data class VideoImportReceiptSnapshot(
    val phase: VideoImportReceiptPhase,
    val outputUri: Uri? = null,
    val songId: Long? = null,
    val requestFingerprint: String? = null,
)

private fun videoImportReceiptSnapshot(
    context: Context,
    workerId: UUID,
): VideoImportReceiptSnapshot {
    val encoded = context.getSharedPreferences(
        VIDEO_IMPORT_COMPLETION_PREFERENCES,
        Context.MODE_PRIVATE,
    ).getString("$VIDEO_IMPORT_COMPLETION_KEY_PREFIX$workerId", null)
        ?: return VideoImportReceiptSnapshot(VideoImportReceiptPhase.NONE)
    return runCatching {
        val json = JSONObject(encoded)
        require(UUID.fromString(json.getString("workerId")) == workerId)
        val phase = VideoImportReceiptPhase.valueOf(json.getString("state"))
        val outputUri = json.getString("outputUri").toUri()
        val songId = json.getLong("songId")
        require(ContentUris.parseId(outputUri) == songId)
        VideoImportReceiptSnapshot(
            phase = phase,
            outputUri = outputUri,
            songId = songId,
            requestFingerprint = json.optString("requestFingerprint").takeIf(String::isNotBlank),
        )
    }.getOrElse { VideoImportReceiptSnapshot(VideoImportReceiptPhase.INVALID) }
}

private fun unresolvedLegacyVideoImportStage(context: Context, workerId: UUID): String? =
    runCatching {
        val encoded = context.getSharedPreferences(
            LEGACY_VIDEO_IMPORT_PENDING_PREFERENCES,
            Context.MODE_PRIVATE,
        ).getString(LEGACY_VIDEO_IMPORT_PENDING_RECORD_KEY, null) ?: return@runCatching null
        val json = JSONObject(encoded)
        if (UUID.fromString(json.getString("workerId")) != workerId) return@runCatching null
        json.getString("stage")
    }.getOrNull()

private fun preparedVideoImportOutputIsPending(
    context: Context,
    receipt: VideoImportReceiptSnapshot,
): Boolean? {
    if (receipt.phase != VideoImportReceiptPhase.PREPARED) return false
    // Legacy MediaStore has no IS_PENDING bit. PREPARED means the cancelled worker may still be
    // inside its bounded, non-cancellable scan/finalization section, so "unknown" must keep UI
    // reconciliation alive instead of being misreported as a terminal failure.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val uri = receipt.outputUri ?: return false
    val songId = receipt.songId ?: return false
    return runCatching {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_PENDING),
            null,
            null,
            null,
        ) ?: return@runCatching null
        cursor.use {
            if (!it.moveToFirst() || it.getLong(0) != songId) false else it.getInt(1) != 0
        }
    }.getOrNull()
}

internal fun moveLegacyFileNoReplace(source: File, destination: File): Boolean = try {
    Files.move(source.toPath(), destination.toPath())
    true
} catch (_: FileAlreadyExistsException) {
    false
}

internal fun legacyFileMatchesExpectedContent(
    file: File,
    expectedSize: Long?,
    expectedSha256: String?,
): Boolean {
    if (expectedSize == null || expectedSize <= 0L || expectedSha256.isNullOrBlank()) return false
    if (!file.isFile || file.length() != expectedSize) return false
    return runCatching { sha256(file).equals(expectedSha256, ignoreCase = true) }
        .getOrDefault(false)
}

internal fun legacyMediaRowMatchesExpectedPath(
    rowId: Long,
    rowPath: String?,
    expectedSongId: Long,
    expectedPath: String,
): Boolean {
    if (rowId != expectedSongId || rowPath.isNullOrBlank()) return false
    return runCatching { File(rowPath).canonicalFile == File(expectedPath).canonicalFile }
        .getOrDefault(false)
}

internal fun inputMatchesExpectedContent(
    input: InputStream,
    expectedSize: Long,
    expectedSha256: String,
): Boolean {
    if (expectedSize <= 0L || expectedSha256.length != 64) return false
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    input.buffered().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > expectedSize) return false
            digest.update(buffer, 0, count)
        }
    }
    if (total != expectedSize) return false
    val actual = digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    return actual.equals(expectedSha256, ignoreCase = true)
}

internal fun shouldAdoptRecoveredVideoImport(
    sourceWorkerId: UUID,
    currentWorkerId: UUID,
    isPublishedReceipt: Boolean,
    hasSameRequestFingerprint: Boolean,
    cancelRequestedWorkerIds: Set<UUID>,
): Boolean = sourceWorkerId != currentWorkerId &&
    hasSameRequestFingerprint &&
    (!isPublishedReceipt || sourceWorkerId in cancelRequestedWorkerIds)

internal fun shouldInspectRecoveredVideoImport(
    isPublishedReceipt: Boolean,
    hasSameRequestFingerprint: Boolean,
): Boolean = !isPublishedReceipt || hasSameRequestFingerprint

internal fun videoImportRequestFingerprint(
    sourceUri: Uri,
    title: String,
    artist: String,
    album: String,
    extractArtwork: Boolean,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(sourceUri.toString(), title, artist, album, extractArtwork.toString()).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

enum class VideoSongIdLookup {
    PRESENT,
    ABSENT,
    INACCESSIBLE,
}

internal fun isUniquePrimaryVideoSongId(
    primaryVolume: String,
    insertedVolume: String,
    volumeLookups: Map<String, VideoSongIdLookup>,
): Boolean = insertedVolume == primaryVolume &&
    volumeLookups.isNotEmpty() &&
    volumeLookups[primaryVolume] == VideoSongIdLookup.PRESENT &&
    volumeLookups.values.none { it == VideoSongIdLookup.INACCESSIBLE } &&
    volumeLookups.none { (volume, lookup) ->
        volume != primaryVolume && lookup == VideoSongIdLookup.PRESENT
    }

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

interface VideoAudioExtractor {
    suspend fun enqueue(request: VideoImportRequest): Boolean
    suspend fun cancel()
    suspend fun currentState(): MediaOperationState
}

class WorkManagerVideoAudioExtractor(context: Context) : VideoAudioExtractor {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(
        VIDEO_IMPORT_WORK_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override suspend fun enqueue(request: VideoImportRequest): Boolean = withContext(Dispatchers.IO) {
        enqueueMutex.withLock {
            val existing = resolveCurrentWorkInfo()
            if (existing?.state?.isFinished == false) return@withLock false
            val requestFingerprint = videoImportRequestFingerprint(
                sourceUri = request.uri,
                title = request.title.trim().ifBlank { "视频提取歌曲" },
                artist = request.artist.trim().ifBlank { "未知歌手" },
                album = request.album.trim().ifBlank { "视频提取" },
                extractArtwork = request.extractArtwork,
            )
            if (unresolvedCancellationBlocksAdmission(requestFingerprint)) {
                // A cancelled worker can still be in PREPARED's mandatory publication or legacy
                // scanner section after WorkManager flips its visible state to CANCELLED. Do not
                // admit a second exporter until that bounded reconciliation closes. A damaged,
                // already-PUBLISHED receipt for another fingerprint never blocks this request.
                return@withLock false
            }
            existing?.let { clearStoredWorkId(it.id) }

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

            // Persist the candidate before enqueue. This closes the process-death window in which
            // WorkManager could finish (or publish while being cancelled) before the wrapper had
            // remembered any ID with which to reconcile its durable receipt.
            persistWorkId(work.id)
            try {
                workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
                    .result.get()
            } catch (error: Throwable) {
                clearStoredWorkId(work.id)
                throw error
            }
            val retainedWork = uniqueWorkInfos()
            val actual = retainedWork.firstOrNull { !it.state.isFinished }
                ?: retainedWork.firstOrNull { it.id == work.id }
            if (actual == null) {
                clearStoredWorkId()
                return@withLock false
            }
            persistWorkId(actual.id)
            actual.id == work.id
        }
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        enqueueMutex.withLock {
            resolveCurrentWorkInfo()?.takeIf { !it.state.isFinished }?.let { current ->
                persistUnresolvedVideoImportCancel(appContext, current.id)
            }
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get()
        }
        Unit
    }

    override suspend fun currentState(): MediaOperationState = withContext(Dispatchers.IO) {
        val info = resolveCurrentWorkInfo()
        val unresolved = unresolvedVideoImportCancelIds(appContext)
        if (info?.state?.isFinished != false) {
            val reconciled = when {
                info == null -> firstVerifiedCancelledPublication()
                info.id in unresolved -> verifiedPublishedVideoImport(appContext, info.id)
                    ?.let { info.id to it }
                else -> null
            }
            reconciled?.let { (workerId, summary) ->
                check(clearUnresolvedVideoImportCancel(appContext, workerId)) {
                    "无法更新视频导入取消状态"
                }
                if (info?.id == workerId) clearStoredWorkId(workerId)
                return@withContext MediaOperationState.Completed(summary)
            }
        }
        if (info == null) {
            unresolved.sortedBy(UUID::toString).forEach { workerId ->
                unresolvedCancellationState(workerId)?.let { return@withContext it }
                check(clearUnresolvedVideoImportCancel(appContext, workerId)) {
                    "无法更新视频导入取消状态"
                }
            }
            return@withContext MediaOperationState.Idle
        }
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                MediaOperationState.Preparing("正在准备视频音轨…")
            WorkInfo.State.RUNNING -> MediaOperationState.Processing(
                currentFile = info.progress.getString(KEY_CURRENT_FILE).orEmpty(),
                completed = 0,
                total = 1,
                progressPercent = info.progress.getInt(KEY_PROGRESS, 0).coerceIn(0, 100),
            )
            WorkInfo.State.SUCCEEDED -> {
                if (info.id in unresolvedVideoImportCancelIds(appContext)) {
                    check(clearUnresolvedVideoImportCancel(appContext, info.id)) {
                        "无法更新视频导入取消状态"
                    }
                }
                MediaOperationState.Completed(
                    MediaOperationSummary(
                        imported = 1,
                        songId = info.outputData.getLong(KEY_SONG_ID, -1L).takeIf { it >= 0L },
                        outputUri = info.outputData.getString(KEY_OUTPUT_URI),
                    )
                )
            }
            WorkInfo.State.CANCELLED -> {
                val completed = info.id
                    .takeIf { it in unresolved }
                    ?.let { verifiedPublishedVideoImport(appContext, it) }
                if (completed != null) {
                    // Cancellation is best effort. A worker that had already made PREPARED durable
                    // must fail forward, so reconcile WorkManager's CANCELLED bit with the verified
                    // public output rather than inviting a duplicate retry.
                    check(clearUnresolvedVideoImportCancel(appContext, info.id)) {
                        "无法更新视频导入取消状态"
                    }
                    MediaOperationState.Completed(completed)
                } else if (info.id in unresolved) {
                    // Publication can race the first verification above. Re-read immediately
                    // before deciding whether the durable state is still reconciling or damaged.
                    verifiedPublishedVideoImport(appContext, info.id)?.let { summary ->
                        check(clearUnresolvedVideoImportCancel(appContext, info.id)) {
                            "无法更新视频导入取消状态"
                        }
                        return@withContext MediaOperationState.Completed(summary)
                    }
                    unresolvedCancellationState(info.id) ?: run {
                        // No durable fail-forward state exists after the short stop race. The UI
                        // is now observing the ordinary cancellation, so its key can retire.
                        check(clearUnresolvedVideoImportCancel(appContext, info.id)) {
                            "无法更新视频导入取消状态"
                        }
                        MediaOperationState.Cancelled(MediaOperationSummary(cancelled = 1))
                    }
                } else {
                    MediaOperationState.Cancelled(MediaOperationSummary(cancelled = 1))
                }
            }
            WorkInfo.State.FAILED -> MediaOperationState.Failed(
                info.outputData.getString(KEY_ERROR) ?: "视频音轨导入失败"
            )
        }
        val awaitingCancellationReconciliation = info.state == WorkInfo.State.CANCELLED &&
            (state is MediaOperationState.Preparing || state is MediaOperationState.Processing)
        if (info.state.isFinished && !awaitingCancellationReconciliation) {
            clearStoredWorkId(info.id)
        }
        state
    }

    private fun resolveCurrentWorkInfo(): WorkInfo? {
        val storedId = storedWorkId()
        val storedInfo = storedId?.let(::workInfo)
        if (storedInfo?.state?.isFinished == false) return storedInfo

        val active = uniqueWorkInfos().firstOrNull { !it.state.isFinished }
        if (active != null) {
            persistWorkId(active.id)
            return active
        }
        if (storedId != null && storedInfo == null) clearStoredWorkId(storedId)
        return storedInfo
    }

    private fun storedWorkId(): UUID? {
        val rawId = preferences.getString(KEY_WORK_ID, null) ?: return null
        return runCatching { UUID.fromString(rawId) }.getOrElse {
            clearStoredWorkId()
            null
        }
    }

    private fun workInfo(id: UUID): WorkInfo? = workManager.getWorkInfoById(id).get()

    private fun uniqueWorkInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()

    private fun firstVerifiedCancelledPublication(
        preferredWorkerId: UUID? = null,
    ): Pair<UUID, MediaOperationSummary>? {
        val unresolved = unresolvedVideoImportCancelIds(appContext)
        val ordered = buildList {
            preferredWorkerId?.takeIf { it in unresolved }?.let(::add)
            unresolved.filterNot { it == preferredWorkerId }
                .sortedBy(UUID::toString)
                .forEach(::add)
        }
        for (workerId in ordered) {
            verifiedPublishedVideoImport(appContext, workerId)?.let { summary ->
                return workerId to summary
            }
        }
        return null
    }

    private fun unresolvedCancellationBlocksAdmission(requestFingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        return unresolvedVideoImportCancelIds(appContext).any { workerId ->
            val receipt = videoImportReceiptSnapshot(appContext, workerId)
            val requestedAt = unresolvedVideoImportCancelRequestedAt(appContext, workerId)
            val age = requestedAt?.let { (now - it).coerceAtLeast(0L) }
            val legacyStillOpen = unresolvedLegacyVideoImportStage(appContext, workerId)
                ?.let { it != "COMPLETION_DURABLE" } == true
            when {
                receipt.phase == VideoImportReceiptPhase.PUBLISHED ->
                    receipt.requestFingerprint == requestFingerprint
                legacyStillOpen || receipt.phase == VideoImportReceiptPhase.PREPARED ||
                    receipt.phase == VideoImportReceiptPhase.ALLOCATED ->
                    age == null || age < CANCEL_RECONCILIATION_MAX_MILLIS
                receipt.phase == VideoImportReceiptPhase.INVALID ||
                    receipt.phase == VideoImportReceiptPhase.NONE ->
                    age != null && age < CANCEL_RECONCILIATION_GRACE_MILLIS
                else -> false
            }
        }
    }

    private fun unresolvedCancellationState(workerId: UUID): MediaOperationState? {
        val receipt = videoImportReceiptSnapshot(appContext, workerId)
        val requestedAt = unresolvedVideoImportCancelRequestedAt(appContext, workerId)
        val age = requestedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        val withinGrace = age != null && age < CANCEL_RECONCILIATION_GRACE_MILLIS
        val withinMaximum = age == null || age < CANCEL_RECONCILIATION_MAX_MILLIS
        val legacyStillOpen = unresolvedLegacyVideoImportStage(appContext, workerId)
            ?.let { it != "COMPLETION_DURABLE" } == true
        val pending = preparedVideoImportOutputIsPending(appContext, receipt)
        return when {
            receipt.phase == VideoImportReceiptPhase.PUBLISHED ||
                receipt.phase == VideoImportReceiptPhase.INVALID -> MediaOperationState.Failed(
                "取消后的歌曲未能通过完整性校验，恢复记录已保留"
            )
            legacyStillOpen && withinMaximum -> MediaOperationState.Processing(
                currentFile = "正在完成取消后的旧版存储收录…",
                completed = 0,
                total = 1,
                progressPercent = 99,
            )
            legacyStillOpen -> MediaOperationState.Failed(
                "取消后的旧版存储收录未在限定时间内完成，文件和恢复记录已保留；可重试导入以恢复"
            )
            receipt.phase == VideoImportReceiptPhase.PREPARED && pending != false &&
                withinMaximum -> MediaOperationState.Processing(
                currentFile = "正在完成取消后的安全发布…",
                completed = 0,
                total = 1,
                progressPercent = 99,
            )
            receipt.phase == VideoImportReceiptPhase.PREPARED -> MediaOperationState.Failed(
                "取消后的歌曲发布未能完成或完整性校验失败，恢复记录已保留；可重试导入以恢复"
            )
            receipt.phase == VideoImportReceiptPhase.ALLOCATED && withinMaximum ->
                MediaOperationState.Preparing("正在安全清理取消的视频导入…")
            receipt.phase == VideoImportReceiptPhase.ALLOCATED -> MediaOperationState.Failed(
                "取消后仍有未完成的输出需要恢复，恢复记录已保留；可重试导入以恢复"
            )
            withinGrace -> MediaOperationState.Preparing("正在确认视频导入取消状态…")
            else -> null
        }
    }

    private fun persistWorkId(id: UUID) {
        check(preferences.edit().putString(KEY_WORK_ID, id.toString()).commit()) {
            "无法保存视频导入任务状态"
        }
    }

    private fun clearStoredWorkId(expectedId: UUID? = null) {
        if (expectedId != null && preferences.getString(KEY_WORK_ID, null) != expectedId.toString()) {
            return
        }
        check(preferences.edit().remove(KEY_WORK_ID).commit()) {
            "无法更新视频导入任务状态"
        }
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
        const val KEY_AUDIO_CONVERSION_PROCESS = "audio_conversion_process"
        const val KEY_ERROR = "error"
        private const val KEY_WORK_ID = "work_id"
        private const val CANCEL_RECONCILIATION_GRACE_MILLIS = 2_000L
        private const val CANCEL_RECONCILIATION_MAX_MILLIS = 75_000L
        private val enqueueMutex = Mutex()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
open class VideoAudioImportWorker(
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
        val requestFingerprint = videoImportRequestFingerprint(
            sourceUri = sourceUri,
            title = title,
            artist = artist,
            album = album,
            extractArtwork = extractArtwork,
        )
        val tempDirectory = File(applicationContext.cacheDir, "video-audio-import").apply { mkdirs() }
        val tempOutput = File(tempDirectory, "${id}.m4a")
        var publishedUri: Uri? = null
        var publishedSongId: Long? = null
        var savedArtworkUri: Uri? = null
        var completedReceiptDurable = false

        return try {
            pruneCompletedImportRecords()
            recoverCompletedImport(title, artist, album)?.let { return it }
            recoverMatchingInterruptedImport(requestFingerprint)?.let { return it }
            // URI grants can be revoked between selection and WorkManager execution. Keep this
            // provider call inside the main failure boundary so the task records an actionable
            // KEY_ERROR instead of escaping with an unclassified worker exception.
            val displayName = queryDisplayName(sourceUri) ?: title
            cleanupInterruptedImports()
            tempDirectory.listFiles()?.forEach(File::delete)
            val audioMime = inspectAudioTrack(sourceUri)
            val exportStrategy = audioExportStrategy(audioMime)
            updateProgress(2, displayName)
            tempOutput.delete()
            beforeAudioExport()
            val audioConversionProcess = exportAudio(
                sourceUri = sourceUri,
                output = tempOutput,
                displayName = displayName,
                strategy = exportStrategy,
            )
            ensureActiveWork()
            require(tempOutput.exists() && tempOutput.length() > 0L) { "没有生成可用的音频文件" }
            val expectedOutputSize = tempOutput.length()
            val expectedOutputSha256 = sha256(tempOutput)
            // Hashing can take long enough for WorkManager cancellation to arrive while it is
            // running. Do not enter the publication boundary after such a cancellation.
            currentCoroutineContext().ensureActive()
            ensureActiveWork()
            val published = publishAudio(
                source = tempOutput,
                title = title,
                artist = artist,
                album = album,
                expectedSize = expectedOutputSize,
                expectedSha256 = expectedOutputSha256,
                onOutputInserted = { insertedUri ->
                    // Modern MediaStore allocation happens before blocking copy/readback work.
                    // Publish the URI to the outer NonCancellable cleanup boundary immediately.
                    publishedUri = insertedUri
                },
            )
            val allocatedCompletion = CompletedImportRecord(
                workerId = id,
                state = CompletedImportState.ALLOCATED,
                outputUri = published.uri,
                songId = published.songId,
                audioConversionProcess = audioConversionProcess,
                createdAtMillis = System.currentTimeMillis(),
                requestFingerprint = requestFingerprint,
                expectedSize = expectedOutputSize,
                expectedSha256 = expectedOutputSha256,
                metadataWrittenByWorker = false,
                artworkUri = null,
                metadataTitle = title,
                metadataArtist = artist,
                metadataAlbum = album,
                artifactOwnerWorkerId = id,
                metadataWriteIntent = false,
                previousMetadataExisted = false,
                previousMetadataTitle = null,
                previousMetadataArtist = null,
                previousMetadataAlbum = null,
                previousMetadataArtworkPath = null,
            )
            // Record the allocated output before writing artwork or Room metadata. If either of
            // those stores is interrupted, recovery can still remove the exact pending output and
            // then clean metadata/artwork before it removes this final recovery reference.
            persistCompletedImportRecord(allocatedCompletion)
            val artwork = if (extractArtwork) extractFirstFrame(sourceUri) else null
            val artworkUri = try {
                artwork?.let { saveArtwork(it, published.songId) }
            } finally {
                artwork?.recycle()
            }
            savedArtworkUri = artworkUri
            val metadataStore = RoomSongMetadataStore(
                PlaylistDatabase.getInstance(applicationContext).songStateDao()
            )
            beforeMetadataOwnershipIntent(published.songId)
            val previousMetadata = metadataStore.getAll()[published.songId]
            val metadataIntent = allocatedCompletion.copy(
                artworkUri = artworkUri,
                metadataWriteIntent = true,
                previousMetadataExisted = previousMetadata != null,
                previousMetadataTitle = previousMetadata?.title,
                previousMetadataArtist = previousMetadata?.artist,
                previousMetadataAlbum = previousMetadata?.album,
                previousMetadataArtworkPath = previousMetadata?.artworkPath,
            )
            // Persist both the expected value and the complete old value before the DAO write.
            // Recovery can then compare-and-delete a newly created override or restore an old one
            // even if the SQL commit wins a race with coroutine cancellation/process death.
            persistCompletedImportRecord(metadataIntent)
            metadataStore.put(
                SongMetadataOverride(
                    songId = published.songId,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkPath = artworkUri?.toString(),
                )
            )
            afterMetadataOverrideCommitted()
            // Only this durable flag authorizes recovery to touch the bare-ID Room override. An
            // allocation/copy failure must never delete an override belonging to another volume.
            publishedSongId = published.songId
            val metadataCompletion = metadataIntent.copy(
                metadataWrittenByWorker = true,
            )
            persistCompletedImportRecord(metadataCompletion)
            val preparedCompletion = metadataCompletion.copy(
                state = CompletedImportState.PREPARED,
            )
            // This durable PREPARED record closes the final publication crash window: the same
            // worker ID can recognize and reuse the exact output instead of exporting a duplicate.
            updateProgress(99, displayName)
            persistCompletedImportRecord(preparedCompletion)
            // No cancellable operation follows PREPARED. From this point the safe direction is
            // always forward to publication; rollback failure could otherwise leave a public row
            // paired with a failed WorkManager result and cause the next request to duplicate it.
            completedReceiptDurable = true
            withContext(NonCancellable + Dispatchers.IO) {
                beforeFinalizePublishedAudio()
                finalizePublishedAudio(published, title, artist, album)
                val completed = preparedCompletion.copy(state = CompletedImportState.PUBLISHED)
                val success = completed.toWorkResult()
                // PREPARED plus a successfully published row is already sufficient for idempotent
                // replay. If this upgrade cannot reach disk, return success and let startup
                // recovery retry the promotion instead of attempting a destructive rollback.
                tryPersistCompletedImportRecord(completed)
                markLegacyCompletionDurable(id)
                clearLegacyPendingMarker(id)
                success
            }
        } catch (cancelled: CancellationException) {
            if (!completedReceiptDurable &&
                (publishedUri != null || publishedSongId != null || savedArtworkUri != null)
            ) {
                cleanupPublishedImport(publishedUri, publishedSongId, savedArtworkUri)
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!completedReceiptDurable &&
                (publishedUri != null || publishedSongId != null || savedArtworkUri != null)
            ) {
                cleanupPublishedImport(publishedUri, publishedSongId, savedArtworkUri)
            }
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

    /** Cleanup must also finish when called from the cancelled worker coroutine. */
    internal suspend fun cleanupPublishedImport(
        publishedUri: Uri?,
        publishedSongId: Long?,
        savedArtworkUri: Uri?,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val metadataStore = RoomSongMetadataStore(
            PlaylistDatabase.getInstance(applicationContext).songStateDao()
        )
        val completion = loadCompletedImportRecord(id)
        if (completion != null && publishedSongId != null && completion.songId != publishedSongId) {
            // Conflicting recovery identities are never deletion authority.
            return@withContext
        }
        val legacy = loadLegacyPendingRecord()?.takeIf { it.workerId == id }
        val mediaRemoved = if (legacy != null) {
            cleanupLegacyPendingRecord(legacy, metadataStore)
        } else {
            publishedUri?.let { deleteOwnedPendingVideoRow(it, id) } ?: true
        }
        if (!mediaRemoved) return@withContext
        val ownershipRemoved = if (completion?.metadataWriteIntent == true ||
            completion?.metadataWrittenByWorker == true
        ) {
            cleanupRecordedMetadataAndArtwork(metadataStore, completion)
        } else {
            // No durable ownership intent means there is no authority to mutate a Room row by
            // bare MediaStore ID. Only the in-memory, worker-tokenized artwork can be removed.
            val artwork = savedArtworkUri?.path?.let(::File)
            (artwork == null || isOwnedVideoArtwork(artwork, id)) &&
                (artwork?.let(::deleteFileIfPresent) ?: true)
        }
        if (!ownershipRemoved) return@withContext
        // The receipt is the final recovery authority and is always removed last.
        check(removeCompletedImportRecord(id)) { "无法更新视频导入恢复记录" }
    }

    /** Test seam for the PREPARED -> public transition; production returns immediately. */
    protected open suspend fun beforeFinalizePublishedAudio() = Unit

    /** Test seam for deterministic cancellation before Transformer owns an output file. */
    protected open suspend fun beforeAudioExport() = Unit

    /** Test seams around the durable metadata-intent/DAO-commit boundary. */
    protected open suspend fun beforeMetadataOwnershipIntent(songId: Long) = Unit

    protected open suspend fun afterMetadataOverrideCommitted() = Unit

    protected open fun deletePendingVideoMediaStoreRow(
        uri: Uri,
        expectedTitle: String,
    ): Int = resolver.delete(
        uri,
        "${MediaStore.Audio.Media.IS_PENDING} = ? AND ${MediaStore.Audio.Media.TITLE} = ?",
        arrayOf("1", expectedTitle),
    )

    private fun deleteOwnedPendingVideoRow(uri: Uri, workerId: UUID): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val expectedTitle = "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$workerId"
        val deleted = runCatching {
            deletePendingVideoMediaStoreRow(uri, expectedTitle)
        }.getOrNull() ?: return false
        if (deleted == 1) return true
        if (deleted != 0) return false
        // A zero count can mean either an already-absent row or a row atomically published by the
        // old worker after our earlier query. Only absence is cleanup success; every live row is
        // preserved together with its metadata and receipt.
        return mediaStoreRowExists(uri) == false
    }

    @Suppress("DEPRECATION")
    protected open fun deleteLegacyVideoMediaStoreRow(
        uri: Uri,
        expectedPath: String,
    ): Int = resolver.delete(
        uri,
        "${MediaStore.Audio.Media.DATA} = ?",
        arrayOf(expectedPath),
    )

    @Suppress("DEPRECATION")
    private fun deleteOwnedLegacyVideoRow(uri: Uri, expectedPath: String): Boolean {
        val deleted = runCatching {
            deleteLegacyVideoMediaStoreRow(uri, expectedPath)
        }.getOrNull() ?: return false
        if (deleted == 1) return true
        if (deleted != 0) return false
        // DATA may have been changed after discovery. A still-live row is never ours to delete,
        // regardless of its numeric ID; preserve it and the recovery journal.
        return mediaStoreRowExists(uri) == false
    }

    private fun mediaStoreRowExists(uri: Uri): Boolean? = runCatching {
        resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() }
    }.getOrNull()

    private fun deleteFileIfPresent(file: File): Boolean =
        !file.exists() || file.delete() || !file.exists()

    private suspend fun recoverMatchingInterruptedImport(
        requestFingerprint: String,
    ): Result? = withContext(Dispatchers.IO) {
        val metadataStore = RoomSongMetadataStore(
            PlaylistDatabase.getInstance(applicationContext).songStateDao()
        )
        val metadata = metadataStore.getAll()
        val cancelRequestedWorkerIds = unresolvedVideoImportCancelIds(applicationContext)
        val interrupted = loadAllCompletedImportRecords()
            .filter { record ->
                record.workerId != id &&
                    (record.state != CompletedImportState.PUBLISHED ||
                        record.workerId in cancelRequestedWorkerIds)
            }
            .sortedBy { it.createdAtMillis }
        for (record in interrupted) {
            if (!shouldInspectRecoveredVideoImport(
                    isPublishedReceipt = record.state == CompletedImportState.PUBLISHED,
                    hasSameRequestFingerprint = record.requestFingerprint == requestFingerprint,
                )
            ) {
                // A completed cancellation receipt belongs to a different import. It may be
                // damaged or temporarily unreadable, but it has no authority to block, mutate or
                // satisfy this request. Keep its receipt/marker for its own UI reconciliation.
                continue
            }
            if (!record.hasExpectedAudioUri()) {
                check(removeCompletedImportRecord(record.workerId)) {
                    "无法清除无效的视频导入恢复记录"
                }
                continue
            }
            if (record.state == CompletedImportState.ALLOCATED) {
                check(cleanupAllocatedImport(record, metadataStore)) {
                    "无法清理上次尚未准备完成的视频导入，恢复记录已保留"
                }
                continue
            }
            val output = queryCompletedOutput(record)
            if (output == null) {
                check(cleanupRecordedMetadataAndArtwork(metadataStore, record)) {
                    "无法安全清理已丢失视频导入的自定义元数据"
                }
                check(removeCompletedImportRecord(record.workerId)) {
                    "无法更新已丢失视频导入的恢复记录"
                }
                continue
            }
            check(output.matchesExpectedContent) { "已导出的歌曲内容校验失败，恢复记录已保留" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                output.isPending &&
                output.title != "$VIDEO_IMPORT_PENDING_TITLE_PREFIX${record.workerId}"
            ) {
                throw IOException("未完成的视频导入标记不匹配，恢复记录已保留")
            }
            val override = metadata[record.songId]
                ?: throw IOException("未完成的视频导入缺少元数据，恢复记录已保留")
            val legacyMarkerPresent = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                loadLegacyPendingRecord()?.workerId == record.workerId
            if (output.isPending || legacyMarkerPresent) {
                finalizePublishedAudio(
                    published = PublishedAudio(record.songId, record.outputUri),
                    title = override.title,
                    artist = override.artist,
                    album = override.album,
                    ownerWorkerId = record.workerId,
                )
            }
            val published = record.copy(state = CompletedImportState.PUBLISHED)
            if (shouldAdoptRecoveredVideoImport(
                    sourceWorkerId = record.workerId,
                    currentWorkerId = id,
                    isPublishedReceipt = record.state == CompletedImportState.PUBLISHED,
                    hasSameRequestFingerprint = record.requestFingerprint == requestFingerprint,
                    cancelRequestedWorkerIds = cancelRequestedWorkerIds,
                )
            ) {
                val adopted = published.copy(
                    workerId = id,
                    createdAtMillis = System.currentTimeMillis(),
                )
                // Persist the current worker mapping before returning. If this commit fails,
                // the old PREPARED receipt remains available for another safe adoption.
                persistCompletedImportRecord(adopted)
                tryPersistCompletedImportRecord(published)
                markLegacyCompletionDurable(record.workerId)
                clearLegacyPendingMarker(record.workerId)
                // Keep the source cancellation unresolved until currentState() actually exposes a
                // verified Completed result to the UI. If this process dies after adoption but
                // before WorkManager stores our success, that durable key still prevents a later
                // same-request worker from exporting a second public copy.
                return@withContext adopted.toWorkResult()
            }
            tryPersistCompletedImportRecord(published)
            markLegacyCompletionDurable(record.workerId)
            clearLegacyPendingMarker(record.workerId)
            // A worker handling another request may safely finish this publication, but it must
            // not consume the source worker's cancellation marker. Only UI-facing reconciliation
            // may do that; otherwise A(cancelled), B(cancelled), A(late publish), C(retry A) can
            // lose A's identity and produce a duplicate.
        }
        null
    }

    private suspend fun recoverCompletedImport(
        title: String,
        artist: String,
        album: String,
    ): Result? = withContext(Dispatchers.IO) {
        val record = loadCompletedImportRecord(id) ?: return@withContext null
        if (!record.hasExpectedAudioUri()) {
            check(removeCompletedImportRecord(id)) { "无法清除无效的视频导入恢复记录" }
            return@withContext null
        }
        if (record.state == CompletedImportState.ALLOCATED) {
            val metadataStore = RoomSongMetadataStore(
                PlaylistDatabase.getInstance(applicationContext).songStateDao()
            )
            check(cleanupAllocatedImport(record, metadataStore)) {
                "无法清理尚未准备完成的视频导入，恢复记录已保留"
            }
            return@withContext null
        }
        val output = queryCompletedOutput(record)
        if (output == null) {
            val metadataStore = RoomSongMetadataStore(
                PlaylistDatabase.getInstance(applicationContext).songStateDao()
            )
            check(cleanupRecordedMetadataAndArtwork(metadataStore, record)) {
                "无法安全清理已丢失视频导入的自定义元数据"
            }
            check(removeCompletedImportRecord(id)) { "无法更新已丢失视频导入的恢复记录" }
            return@withContext null
        }
        check(output.matchesExpectedContent) { "已导出的歌曲内容校验失败，已保留恢复记录" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            output.isPending &&
            output.title != "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$id"
        ) {
            // A private preference or MediaStore row should never disagree this way. Do not
            // mutate or delete an item that can no longer be proven to be this worker's output.
            throw IOException("已导出的歌曲状态异常，已保留恢复记录")
        }

        val legacyMarkerPresent = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            loadLegacyPendingRecord()?.workerId == record.workerId
        if (output.isPending || legacyMarkerPresent) {
            finalizePublishedAudio(
                PublishedAudio(record.songId, record.outputUri),
                title,
                artist,
                album,
            )
        }
        val publishedRecord = record.copy(state = CompletedImportState.PUBLISHED)
        val success = publishedRecord.toWorkResult()
        if (publishedRecord != record) tryPersistCompletedImportRecord(publishedRecord)
        markLegacyCompletionDurable(id)
        clearLegacyPendingMarker(id)
        success
    }

    private fun finalizePublishedAudio(
        published: PublishedAudio,
        title: String,
        artist: String,
        album: String,
        ownerWorkerId: UUID = id,
    ) {
        val legacyRecord = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requireLegacyTargetOwnership(ownerWorkerId, published)
        } else null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
        }
        val publishedCount = if (legacyRecord != null) {
            updateLegacyVideoMediaStoreRow(
                uri = published.uri,
                values = values,
                expectedPath = legacyRecord.targetPath,
            )
        } else {
            resolver.update(published.uri, values, null, null)
        }
        check(publishedCount == 1) { "系统音乐库未能发布导出的歌曲" }
        if (legacyRecord != null) {
            requireLegacyTargetOwnership(ownerWorkerId, published)
        }
    }

    @Suppress("DEPRECATION")
    protected open fun updateLegacyVideoMediaStoreRow(
        uri: Uri,
        values: ContentValues,
        expectedPath: String,
    ): Int = resolver.update(
        uri,
        values,
        "${MediaStore.Audio.Media.DATA} = ?",
        arrayOf(expectedPath),
    )

    private fun requireLegacyTargetOwnership(
        workerId: UUID,
        published: PublishedAudio,
    ): LegacyPendingRecord {
        val record = loadLegacyPendingRecord()?.takeIf { it.workerId == workerId }
            ?: throw IOException("旧版音频缺少可验证的恢复记录")
        check(record.matchesTarget()) { "旧版音频内容发生变化，文件和恢复记录已保留" }
        check(record.outputUri == published.uri && record.songId == published.songId) {
            "旧版音频恢复记录与系统音乐库编号不匹配"
        }
        check(
            legacyReceiptRowMatchesTarget(
                uri = published.uri,
                songId = published.songId,
                targetPath = record.targetPath,
            )
        ) { "旧版音频系统记录已改变，文件和恢复记录已保留" }
        return record
    }

    private fun queryCompletedOutput(record: CompletedImportRecord): CompletedOutputState? {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.IS_PENDING)
            }
        }.toTypedArray()
        val cursor = resolver.query(record.outputUri, projection, null, null, null)
            ?: throw IOException("无法确认已导出的歌曲是否存在")
        val row = cursor.use {
            if (!it.moveToFirst()) return@use null
            val rowId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            if (rowId != record.songId) return@use null
            CompletedOutputState(
                isPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    it.getInt(it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)) != 0,
                title = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
                matchesExpectedContent = false,
            )
        } ?: return null
        val expectedSize = record.expectedSize ?: return row
        val expectedSha256 = record.expectedSha256 ?: return row
        val input = resolver.openInputStream(record.outputUri)
            ?: throw IOException("无法读取已导出的歌曲")
        return row.copy(
            matchesExpectedContent = inputMatchesExpectedContent(
                input = input,
                expectedSize = expectedSize,
                expectedSha256 = expectedSha256,
            )
        )
    }

    private suspend fun cleanupAllocatedImport(
        record: CompletedImportRecord,
        metadataStore: RoomSongMetadataStore,
    ): Boolean {
        if (!record.hasExpectedAudioUri()) return false
        val mediaRemoved = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val legacy = loadLegacyPendingRecord()?.takeIf { marker ->
                marker.workerId == record.workerId &&
                    marker.stage == LegacyPendingStage.MEDIA_INDEXED &&
                    marker.outputUri == record.outputUri &&
                    marker.songId == record.songId
            }
            if (legacy != null) {
                cleanupLegacyPendingRecord(legacy, metadataStore)
            } else {
                // A prior cleanup may have removed the row and marker but crashed before the
                // receipt commit. Only that proven-absent state may advance to receipt cleanup.
                mediaStoreRowExists(record.outputUri) == false
            }
        } else {
            val output = queryCompletedOutput(record)
            if (output == null) {
                true
            } else {
                if (!output.isPending ||
                    output.title != "$VIDEO_IMPORT_PENDING_TITLE_PREFIX${record.workerId}"
                ) {
                    return false
                }
                deleteOwnedPendingVideoRow(record.outputUri, record.workerId)
            }
        }
        if (!mediaRemoved) return false
        return runCatching {
            check(cleanupRecordedMetadataAndArtwork(metadataStore, record)) {
                "无法安全清理视频导入的自定义元数据"
            }
            check(removeCompletedImportRecord(record.workerId)) {
                "无法更新视频导入恢复记录"
            }
        }.isSuccess
    }

    private fun completedImportPreferences() = applicationContext.getSharedPreferences(
        COMPLETED_IMPORT_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun completedImportKey(workerId: UUID): String = "$KEY_COMPLETED_IMPORT_PREFIX$workerId"

    private fun loadCompletedImportRecord(workerId: UUID): CompletedImportRecord? {
        val encoded = runCatching {
            completedImportPreferences().getString(completedImportKey(workerId), null)
        }.getOrNull() ?: return null
        return CompletedImportRecord.decode(encoded)?.takeIf { it.workerId == workerId }
    }

    private fun loadAllCompletedImportRecords(): List<CompletedImportRecord> =
        completedImportPreferences().all.values.mapNotNull { value ->
            (value as? String)?.let { CompletedImportRecord.decode(it) }
        }

    private fun persistCompletedImportRecord(record: CompletedImportRecord) {
        check(tryPersistCompletedImportRecord(record)) { "无法保存视频导入恢复状态" }
    }

    private fun tryPersistCompletedImportRecord(record: CompletedImportRecord): Boolean =
        runCatching {
            commitCompletedImportReceipt(
                key = completedImportKey(record.workerId),
                encoded = record.encode(),
            )
        }.getOrDefault(false)

    protected open fun commitCompletedImportReceipt(key: String, encoded: String): Boolean =
        completedImportPreferences().edit().putString(key, encoded).commit()

    private fun removeCompletedImportRecord(workerId: UUID): Boolean =
        completedImportPreferences().edit().remove(completedImportKey(workerId)).commit()

    @SuppressLint("ApplySharedPref")
    private fun pruneCompletedImportRecords() {
        val preferences = completedImportPreferences()
        val oldestRetained = System.currentTimeMillis() - COMPLETED_IMPORT_RETENTION_MILLIS
        val protectedLegacyWorker = loadLegacyPendingRecord()?.workerId
        val protectedCancelledWorkers = unresolvedVideoImportCancelIds(applicationContext)
        val expiredKeys = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_COMPLETED_IMPORT_PREFIX) || key == completedImportKey(id)) {
                return@mapNotNull null
            }
            val record = (value as? String)?.let { CompletedImportRecord.decode(it) }
            if (record?.workerId == protectedLegacyWorker ||
                (record != null && record.workerId in protectedCancelledWorkers)
            ) {
                return@mapNotNull null
            }
            if (record == null ||
                (record.state == CompletedImportState.PUBLISHED &&
                    record.createdAtMillis < oldestRetained)
            ) {
                key
            } else null
        }
        // Pruning removes only private idempotency receipts; it never deletes a published song.
        if (expiredKeys.isNotEmpty()) {
            preferences.edit().apply { expiredKeys.forEach { remove(it) } }.commit()
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

    private suspend fun exportAudio(
        sourceUri: Uri,
        output: File,
        displayName: String,
        strategy: AudioExportStrategy,
    ): Int {
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setRemoveVideo(true)
            .build()
        val conversionProcess = coroutineScope {
            val completion = async {
                suspendCancellableCoroutine<Int> { continuation ->
                    val transformerBuilder = Transformer.Builder(applicationContext)
                        .setLooper(Looper.getMainLooper())
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resume(exportResult.audioConversionProcess)
                                    }
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
                    // Leaving the audio MIME unset is what allows an AAC track to be copied into
                    // the M4A container without decoding. Non-AAC input explicitly requests AAC.
                    if (strategy == AudioExportStrategy.TRANSCODE_TO_AAC) {
                        transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }
                    val builtTransformer = transformerBuilder.build()
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
        return conversionProcess
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
        expectedSize: Long,
        expectedSha256: String,
        onOutputInserted: (Uri) -> Unit,
    ): PublishedAudio = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val published = publishLegacyAudio(
                source = source,
                title = title,
                artist = artist,
                album = album,
                expectedSize = expectedSize,
                expectedSha256 = expectedSha256,
            )
            onOutputInserted(published.uri)
            return@withContext published
        }
        val relativePath = "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/"
        val desiredName = "${safeAudioFileBase(title)}.m4a"
        val outputName = uniqueDisplayName(desiredName, inspectAvailableNames(relativePath))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, outputName)
            // This marker identifies only this app's unpublished video exports. Directory-only
            // cleanup could otherwise delete an unrelated pending playlist relocation.
            put(MediaStore.Audio.Media.TITLE, "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$id")
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(PRIMARY_EXTERNAL_MEDIA_VOLUME),
            values,
        )
            ?: throw IOException("无法在系统音乐库中创建文件")
        // Expose the concrete row before any blocking copy/hash operation. Prompt cancellation at
        // a withContext boundary can then still remove this exact pending row immediately.
        onOutputInserted(uri)
        try {
            val published = PublishedAudio(ContentUris.parseId(uri), uri)
            validateModernSongIdOwnership(published)
            copyModernAudioOutput(source, uri)
            val verified = verifyModernAudioOutput(uri, expectedSize, expectedSha256)
            check(verified) { "系统音乐库中的音频写入不完整" }
            published
        } catch (error: Throwable) {
            if (!deleteOwnedPendingVideoRow(uri, id)) {
                throw IOException("无法安全清理未完成的视频音轨，系统恢复标记已保留", error)
            }
            throw error
        }
    }

    protected open suspend fun copyModernAudioOutput(source: File, uri: Uri) {
        resolver.openOutputStream(uri, "w")?.use { output ->
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureActiveWork()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
        } ?: throw IOException("无法写入系统音乐库")
    }

    protected open fun openModernAudioOutput(uri: Uri): InputStream? =
        resolver.openInputStream(uri)

    private suspend fun verifyModernAudioOutput(
        uri: Uri,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        val input = openModernAudioOutput(uri) ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureActiveWork()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expectedSize) return false
                digest.update(buffer, 0, count)
            }
        }
        if (total != expectedSize) return false
        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    private fun validateModernSongIdOwnership(published: PublishedAudio) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val volumes = currentModernAudioVolumes()
        val lookups = volumes.associateWith { volume ->
            queryModernAudioSongId(volume, published.songId)
        }
        val insertedVolume = published.uri.pathSegments.firstOrNull().orEmpty()
        if (!isUniquePrimaryVideoSongId(
                primaryVolume = PRIMARY_EXTERNAL_MEDIA_VOLUME,
                insertedVolume = insertedVolume,
                volumeLookups = lookups,
            )
        ) {
            val inaccessible = lookups.values.any { it == VideoSongIdLookup.INACCESSIBLE }
            throw IOException(
                if (inaccessible) {
                    "无法确认所有存储卷中的歌曲编号，未写入自定义元数据"
                } else {
                    "多个存储卷存在相同歌曲编号，未写入自定义元数据"
                }
            )
        }
    }

    protected open fun currentModernAudioVolumes(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(applicationContext)
        } else {
            emptySet()
        }

    protected open fun queryModernAudioSongId(
        volumeName: String,
        songId: Long,
    ): VideoSongIdLookup = runCatching {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(volumeName),
            songId,
        )
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        ) ?: return@runCatching VideoSongIdLookup.INACCESSIBLE
        cursor.use {
            if (it.moveToFirst() && it.getLong(0) == songId) {
                VideoSongIdLookup.PRESENT
            } else {
                VideoSongIdLookup.ABSENT
            }
        }
    }.getOrDefault(VideoSongIdLookup.INACCESSIBLE)

    @Suppress("DEPRECATION")
    private suspend fun publishLegacyAudio(
        source: File,
        title: String,
        artist: String,
        album: String,
        expectedSize: Long,
        expectedSha256: String,
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
        var target = File(directory, uniqueDisplayName(desired, directory.list()?.toList().orEmpty()))
        val temporary = File(directory, ".$id.yinlan-pending")
        var recovery = LegacyPendingRecord(
            workerId = id,
            stage = LegacyPendingStage.PATHS_RESERVED,
            temporaryPath = temporary.absolutePath,
            targetPath = target.absolutePath,
            outputUri = null,
            songId = null,
            expectedSize = null,
            expectedSha256 = null,
        )
        try {
            persistLegacyPendingRecord(recovery)
            check(!temporary.exists()) { "旧版存储中存在未清理的视频导入临时文件" }
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val copyContext = currentCoroutineContext()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        copyContext.ensureActive()
                        ensureActiveWork()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            ensureActiveWork()
            check(
                legacyFileMatchesExpectedContentCancellable(
                    temporary,
                    expectedSize,
                    expectedSha256,
                )
            ) { "旧版音频暂存校验失败" }
            currentCoroutineContext().ensureActive()
            ensureActiveWork()
            recovery = recovery.copy(
                stage = LegacyPendingStage.STAGING_VERIFIED,
                expectedSize = expectedSize,
                expectedSha256 = expectedSha256,
            )
            persistLegacyPendingRecord(recovery)

            var attempts = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureActiveWork()
                if (moveLegacyFileNoReplace(temporary, target)) break
                attempts += 1
                check(attempts <= MAX_LEGACY_NAME_ATTEMPTS) { "目标目录中的同名文件过多" }
                val occupiedNames = directory.list()?.toList().orEmpty() + target.name
                target = File(directory, uniqueDisplayName(desired, occupiedNames))
                recovery = recovery.copy(targetPath = target.absolutePath)
                persistLegacyPendingRecord(recovery)
            }
            check(recovery.matchesTarget()) { "旧版音频落位后校验失败，文件已保留" }
            recovery = recovery.copy(stage = LegacyPendingStage.TARGET_READY)
            persistLegacyPendingRecord(recovery)
            check(recovery.matchesTarget()) { "旧版音频在收录前发生变化，文件已保留" }
            // MediaScanner cannot cancel an in-flight platform request. Await its callback in a
            // non-cancellable section and journal MEDIA_INDEXED before cancellation cleanup may
            // remove the target; otherwise a late callback could create a public orphan row.
            return legacyScannerMutex.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    val uri = scanFile(target, recovery)
                    val songId = ContentUris.parseId(uri)
                    check(recovery.matchesTarget()) {
                        "旧版音频在系统收录期间发生变化，文件已保留"
                    }
                    check(legacyReceiptRowMatchesTarget(uri, songId, recovery.targetPath)) {
                        "旧版音频收录结果与目标路径不匹配，文件和恢复记录已保留"
                    }
                    recovery = recovery.copy(
                        stage = LegacyPendingStage.MEDIA_INDEXED,
                        outputUri = uri,
                        songId = songId,
                    )
                    persistLegacyPendingRecord(recovery)
                    check(recovery.matchesTarget()) { "旧版音频在发布前发生变化，文件已保留" }
                    PublishedAudio(songId, uri)
                }
            }
        } catch (error: Throwable) {
            if (recovery.stage == LegacyPendingStage.TARGET_READY) {
                throw if (error is LegacyScanTimeoutException) {
                    error
                } else {
                    IOException(
                        "旧版系统收录未完成；已保留文件和恢复记录，可稍后重试",
                        error,
                    )
                }
            }
            val metadataStore = RoomSongMetadataStore(
                PlaylistDatabase.getInstance(applicationContext).songStateDao()
            )
            if (!cleanupLegacyPendingRecord(recovery, metadataStore)) {
                throw IOException(
                    "旧版视频导入未完成；无法安全清理输出，文件和恢复记录已保留",
                    error,
                )
            }
            throw error
        }
    }

    private suspend fun legacyFileMatchesExpectedContentCancellable(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile || file.length() != expectedSize || expectedSha256.length != 64) {
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureActiveWork()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expectedSize) return false
                digest.update(buffer, 0, count)
            }
        }
        if (total != expectedSize) return false
        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    private suspend fun scanFile(file: File, record: LegacyPendingRecord): Uri {
        val token = LegacyScanToken(record.workerId, record.targetPath)
        markLegacyScanOutstanding(token)
        val callback = try {
            withTimeoutOrNull(legacyMediaScanTimeoutMillis()) {
                suspendCancellableCoroutine<LegacyScanCallback> { continuation ->
                    requestLegacyMediaScan(file) { uri ->
                        if (continuation.isActive) {
                            continuation.resume(LegacyScanCallback(uri))
                        } else {
                            reconcileLateLegacyScan(record, uri, token)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            clearLegacyScanOutstanding(token)
            throw error
        }
        if (callback == null) {
            // Keep the token until the callback arrives. Cleanup sees it and preserves the
            // TARGET_READY file/journal, while the mutex itself is released at the bounded wait.
            throw LegacyScanTimeoutException()
        }
        clearLegacyScanOutstanding(token)
        return callback.uri
            ?: throw IOException("系统音乐库未能收录导出的歌曲")
    }

    protected open fun legacyMediaScanTimeoutMillis(): Long = LEGACY_SCAN_TIMEOUT_MILLIS

    protected open fun requestLegacyMediaScan(file: File, callback: (Uri?) -> Unit) {
        MediaScannerConnection.scanFile(
            applicationContext,
            arrayOf(file.absolutePath),
            arrayOf("audio/mp4"),
        ) { _, uri ->
            callback(uri)
        }
    }

    private fun reconcileLateLegacyScan(
        expected: LegacyPendingRecord,
        uri: Uri?,
        token: LegacyScanToken,
    ) {
        try {
            if (uri == null) return
            val current = loadLegacyPendingRecord()?.takeIf { record ->
                record.workerId == expected.workerId &&
                    record.stage == LegacyPendingStage.TARGET_READY &&
                    record.targetPath == expected.targetPath &&
                    record.temporaryPath == expected.temporaryPath
            } ?: return
            val songId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
            if (!current.matchesTarget() ||
                !legacyReceiptRowMatchesTarget(uri, songId, current.targetPath)
            ) {
                return
            }
            runCatching {
                persistLegacyPendingRecord(
                    current.copy(
                        stage = LegacyPendingStage.MEDIA_INDEXED,
                        outputUri = uri,
                        songId = songId,
                    )
                )
            }
        } finally {
            clearLegacyScanOutstanding(token)
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
        loadLegacyPendingRecord()?.let { legacy ->
            val completed = loadCompletedImportRecord(legacy.workerId)
            val publishedCompletion = completed?.takeIf {
                it.outputUri == legacy.outputUri && it.songId == legacy.songId
            }?.let { completion ->
                queryCompletedOutput(completion)?.let { output ->
                    !output.isPending && output.matchesExpectedContent
                }
            } == true
            if (legacy.stage == LegacyPendingStage.COMPLETION_DURABLE || publishedCompletion) {
                // A failed marker-clear commit must never turn a successfully imported song into
                // cleanup collateral on the next launch.
                clearLegacyPendingMarker(legacy.workerId)
            } else {
                check(cleanupLegacyPendingRecord(legacy, metadataStore)) {
                    "无法清理上次未完成的视频导入，恢复记录已保留"
                }
                check(removeCompletedImportRecord(legacy.workerId)) {
                    "无法更新上次视频导入的恢复记录"
                }
            }
        }
        cleanupLegacyV1PendingRecord(metadataStore)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/"
            val pending = mutableListOf<PendingVideoRow>()
            val collection = MediaStore.Audio.Media.getContentUri(PRIMARY_EXTERNAL_MEDIA_VOLUME)
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Audio.Media.IS_PENDING} = 1 AND " +
                    "${MediaStore.Audio.Media.TITLE} LIKE ?",
                arrayOf(relativePath, "$VIDEO_IMPORT_PENDING_TITLE_PREFIX%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val songId = cursor.getLong(0)
                    val workerId = cursor.getString(1)
                        ?.removePrefix(VIDEO_IMPORT_PENDING_TITLE_PREFIX)
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: continue
                    pending += PendingVideoRow(
                        songId = songId,
                        uri = ContentUris.withAppendedId(collection, songId),
                        workerId = workerId,
                    )
                }
            }
            pending.forEach { item ->
                val completion = loadCompletedImportRecord(item.workerId)?.takeIf { record ->
                    record.outputUri == item.uri && record.songId == item.songId
                }
                check(deleteOwnedPendingVideoRow(item.uri, item.workerId)) {
                    "无法清理上次未发布的视频音轨，恢复记录已保留"
                }
                // A marker row can exist before ALLOCATED is durable. Its numeric ID alone never
                // authorizes touching Room/artwork, which may belong to a secondary volume.
                if (completion != null) {
                    check(cleanupRecordedMetadataAndArtwork(metadataStore, completion)) {
                        "无法安全清理上次视频导入的自定义元数据"
                    }
                    check(removeCompletedImportRecord(item.workerId)) {
                        "无法更新上次视频导入的恢复记录"
                    }
                }
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

    private fun loadLegacyPendingRecord(): LegacyPendingRecord? = runCatching {
        legacyPendingPreferences().getString(KEY_LEGACY_PENDING_RECORD, null)
    }.getOrNull()?.let { LegacyPendingRecord.decode(it) }

    private fun persistLegacyPendingRecord(record: LegacyPendingRecord) {
        check(
            legacyPendingPreferences().edit()
                .putString(KEY_LEGACY_PENDING_RECORD, record.encode())
                .remove(KEY_LEGACY_PENDING_PATH)
                .remove(KEY_LEGACY_PENDING_URI)
                .remove(KEY_LEGACY_PENDING_SONG_ID)
                .commit()
        ) { "无法保存旧版存储导入恢复状态" }
    }

    private fun markLegacyCompletionDurable(workerId: UUID) {
        val record = loadLegacyPendingRecord()?.takeIf { it.workerId == workerId } ?: return
        runCatching {
            persistLegacyPendingRecord(record.copy(stage = LegacyPendingStage.COMPLETION_DURABLE))
        }
    }

    private fun clearLegacyPendingMarker(expectedWorkerId: UUID): Boolean {
        val record = loadLegacyPendingRecord() ?: return true
        if (record.workerId != expectedWorkerId) return false
        return runCatching {
            legacyPendingPreferences().edit().remove(KEY_LEGACY_PENDING_RECORD).commit()
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private suspend fun cleanupLegacyPendingRecord(
        record: LegacyPendingRecord,
        metadataStore: RoomSongMetadataStore,
    ): Boolean {
        if (record.stage == LegacyPendingStage.COMPLETION_DURABLE) {
            return clearLegacyPendingMarker(record.workerId)
        }
        if (!record.hasSafeLegacyPaths()) {
            // A path outside the exact app directory or without this worker's private temporary
            // token cannot be proven safe. Keep both the file and journal for diagnosis/recovery.
            return false
        }
        if (record.stage == LegacyPendingStage.TARGET_READY &&
            isLegacyScanOutstanding(LegacyScanToken(record.workerId, record.targetPath))
        ) {
            // A timed-out platform callback may still create its row. Preserve the target and
            // journal until that callback records MEDIA_INDEXED or the process restarts.
            return false
        }
        val target = File(record.targetPath)
        val temporary = File(record.temporaryPath)
        if (record.stage == LegacyPendingStage.PATHS_RESERVED) {
            // By invariant no final-path operation happens before STAGING_VERIFIED is durable.
            // A file already at targetPath therefore belongs to the racing creator, not this job.
            val temporaryRemoved = deleteFileIfPresent(temporary)
            return temporaryRemoved && clearLegacyPendingMarker(record.workerId)
        }
        if (record.stage == LegacyPendingStage.STAGING_VERIFIED && temporary.exists()) {
            // A durable STAGING_VERIFIED record with the private staging file still present proves
            // that no-replace placement did not complete. Even an identical target may belong to
            // the caller that won the name race, so only the digest-matching private temp is ours.
            if (!legacyFileMatchesExpectedContent(
                    temporary,
                    record.expectedSize,
                    record.expectedSha256,
                )
            ) {
                return false
            }
            return deleteFileIfPresent(temporary) && clearLegacyPendingMarker(record.workerId)
        }
        if (record.stage == LegacyPendingStage.TARGET_READY) {
            // A platform scan may have survived a prior process while its callback did not. Never
            // delete/forget the target based on an empty pre-callback query. Re-scan the durable
            // target and await convergence before moving to the normal indexed cleanup path.
            if (!target.exists() || !record.matchesTarget()) return false
            val indexed = legacyScannerMutex.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    val uri = scanFile(target, record)
                    val songId = ContentUris.parseId(uri)
                    check(record.matchesTarget()) {
                        "旧版音频在恢复收录期间发生变化，文件和恢复记录已保留"
                    }
                    check(legacyReceiptRowMatchesTarget(uri, songId, record.targetPath)) {
                        "旧版音频收录结果无法验证，文件和恢复记录已保留"
                    }
                    record.copy(
                        stage = LegacyPendingStage.MEDIA_INDEXED,
                        outputUri = uri,
                        songId = songId,
                    ).also(::persistLegacyPendingRecord)
                }
            }
            return cleanupLegacyPendingRecord(indexed, metadataStore)
        }
        if (!target.exists()) {
            val indexedUris = queryLegacyIndexedUris(record.targetPath) ?: return false
            if (indexedUris.isNotEmpty()) return false
            if (record.outputUri != null && mediaStoreRowExists(record.outputUri) != false) {
                return false
            }
            val completion = loadCompletedImportRecord(record.workerId)
            val metadataRemoved = completion == null ||
                cleanupRecordedMetadataAndArtwork(metadataStore, completion)
            return metadataRemoved && clearLegacyPendingMarker(record.workerId)
        }
        // Never treat a pathname as proof of ownership. A racing app may have created or replaced
        // this name after it was selected; only the synchronously journaled bytes authorize delete.
        if (!record.matchesTarget()) return false
        val indexedUris = queryLegacyIndexedUris(record.targetPath) ?: return false
        record.outputUri?.takeIf { uri ->
            record.songId?.let { songId ->
                isExpectedAudioUri(uri, songId, requirePrimary = false) &&
                    legacyReceiptRowMatchesTarget(uri, songId, record.targetPath)
            } ?: false
        }?.let(indexedUris::add)
        for (uri in indexedUris) {
            // Narrow the provider-delete race: MediaProvider may remove the DATA path as part of
            // row deletion, so revalidate our bytes immediately before every such call.
            if (target.exists() && !record.matchesTarget()) return false
            if (!deleteOwnedLegacyVideoRow(uri, record.targetPath)) return false
        }
        val temporaryRemoved = deleteFileIfPresent(temporary)
        val targetRemoved = if (!target.exists()) {
            true
        } else if (record.matchesTarget()) {
            deleteFileIfPresent(target)
        } else {
            false
        }
        if (!temporaryRemoved || !targetRemoved) return false
        val completion = loadCompletedImportRecord(record.workerId)
        val metadataRemoved = completion == null ||
            cleanupRecordedMetadataAndArtwork(metadataStore, completion)
        return metadataRemoved && clearLegacyPendingMarker(record.workerId)
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyIndexedUris(targetPath: String): LinkedHashSet<Uri>? = runCatching {
        val cursor = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA} = ?",
            arrayOf(targetPath),
            null,
        ) ?: return@runCatching null
        linkedSetOf<Uri>().apply {
            cursor.use {
                while (it.moveToNext()) {
                    add(
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            it.getLong(0),
                        )
                    )
                }
            }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyReceiptRowMatchesTarget(
        uri: Uri,
        songId: Long,
        targetPath: String,
    ): Boolean = runCatching {
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        ) ?: return@runCatching false
        cursor.use {
            it.moveToFirst() && legacyMediaRowMatchesExpectedPath(
                rowId = it.getLong(0),
                rowPath = it.getString(1),
                expectedSongId = songId,
                expectedPath = targetPath,
            )
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private suspend fun cleanupLegacyV1PendingRecord(metadataStore: RoomSongMetadataStore) {
        val preferences = legacyPendingPreferences()
        if (preferences.contains(KEY_LEGACY_PENDING_RECORD)) return
        val path = preferences.getString(KEY_LEGACY_PENDING_PATH, null)
        val uri = preferences.getString(KEY_LEGACY_PENDING_URI, null)?.let(Uri::parse)
        val songId = preferences.getLong(KEY_LEGACY_PENDING_SONG_ID, -1L).takeIf { it >= 0L }
        if (path == null && uri == null && songId == null) return
        val safePath = path?.let(::File)?.takeIf(::isSafeLegacyImportFile)
        val existingMetadata = songId?.let { metadataStore.getAll()[it] }
        if (uri != null && songId != null && existingMetadata != null &&
            isExpectedAudioUri(uri, songId, requirePrimary = false)
        ) {
            // The former implementation wrote its Room override only after MediaScanner and
            // MediaStore metadata had completed. A leftover asynchronous clear from that version
            // must therefore be forgotten, not interpreted as permission to delete the song.
            check(preferences.edit().clear().commit()) {
                "无法清除旧版视频导入恢复记录"
            }
            return
        }
        val provablyPrivateTemporary = safePath?.takeIf {
            it.name.startsWith(".") && it.name.endsWith(".yinlan-pending")
        }
        check(provablyPrivateTemporary != null && uri == null && songId == null) {
            // The former format did not retain a digest or ownership token for a final filename.
            // Preserving an unverifiable file is safer than deleting a later creator's content.
            "旧版视频导入文件无法验证归属，文件和恢复记录已保留"
        }
        check(deleteFileIfPresent(provablyPrivateTemporary)) {
            "无法清理旧版未完成的视频导入，恢复记录已保留"
        }
        // Compatibility cleanup is also synchronous: an old marker must not be replayed after a
        // process loss between deleting its artifacts and clearing SharedPreferences.
        check(preferences.edit().clear().commit()) {
            "无法清除旧版视频导入恢复记录"
        }
    }

    private suspend fun cleanupRecordedMetadataAndArtwork(
        metadataStore: RoomSongMetadataStore,
        record: CompletedImportRecord,
    ): Boolean = runCatching {
        val recordedArtwork = record.artworkUri?.path?.let(::File)
        if (!record.metadataWriteIntent && !record.metadataWrittenByWorker) {
            // ALLOCATED is durable before Room is touched. At this stage only a worker-token file
            // recorded in the receipt could be ours; a bare song ID is never deletion authority.
            check(
                recordedArtwork == null ||
                    isOwnedVideoArtwork(recordedArtwork, record.artifactOwnerWorkerId)
            )
            check(recordedArtwork?.let(::deleteFileIfPresent) ?: true)
            return@runCatching
        }

        val current = metadataStore.getAll()[record.songId]
        val matchesExpected = current != null &&
            record.metadataTitle != null && current.title == record.metadataTitle &&
            record.metadataArtist != null && current.artist == record.metadataArtist &&
            record.metadataAlbum != null && current.album == record.metadataAlbum &&
            current.artworkPath == record.artworkUri?.toString()
        val matchesPrevious = if (record.previousMetadataExisted) {
            current != null &&
                current.title == record.previousMetadataTitle &&
                current.artist == record.previousMetadataArtist &&
                current.album == record.previousMetadataAlbum &&
                current.artworkPath == record.previousMetadataArtworkPath
        } else {
            current == null
        }
        // INTENT may represent either side of the DAO commit. Any third value is a later user
        // change, so fail closed and retain the receipt rather than deleting or overwriting it.
        check(matchesExpected || matchesPrevious)
        if (matchesExpected) {
            if (record.previousMetadataExisted) {
                metadataStore.put(
                    SongMetadataOverride(
                        songId = record.songId,
                        title = requireNotNull(record.previousMetadataTitle),
                        artist = requireNotNull(record.previousMetadataArtist),
                        album = requireNotNull(record.previousMetadataAlbum),
                        artworkPath = record.previousMetadataArtworkPath,
                    )
                )
            } else {
                metadataStore.delete(record.songId)
            }
        }
        check(
            recordedArtwork == null ||
                isOwnedVideoArtwork(recordedArtwork, record.artifactOwnerWorkerId)
        )
        val previousOverrideUsesRecordedArtwork = record.previousMetadataExisted &&
            record.previousMetadataArtworkPath == record.artworkUri?.toString()
        check(
            previousOverrideUsesRecordedArtwork ||
                (recordedArtwork?.let(::deleteFileIfPresent) ?: true)
        )
        if (record.previousMetadataExisted) {
            val restored = metadataStore.getAll()[record.songId]
            check(
                restored != null &&
                    restored.title == record.previousMetadataTitle &&
                    restored.artist == record.previousMetadataArtist &&
                    restored.album == record.previousMetadataAlbum &&
                    restored.artworkPath == record.previousMetadataArtworkPath
            )
        } else {
            check(metadataStore.getAll()[record.songId] == null)
        }
    }.isSuccess

    private fun isOwnedVideoArtwork(file: File, workerId: UUID): Boolean {
        val artworkDirectory = File(applicationContext.filesDir, "artwork")
        return file.name.endsWith("-video-$workerId.jpg") &&
            runCatching { file.canonicalFile.parentFile == artworkDirectory.canonicalFile }
                .getOrDefault(false)
    }

    private fun markLegacyScanOutstanding(token: LegacyScanToken) {
        synchronized(legacyScanStateLock) { outstandingLegacyScans += token }
    }

    private fun clearLegacyScanOutstanding(token: LegacyScanToken) {
        synchronized(legacyScanStateLock) { outstandingLegacyScans -= token }
    }

    private fun isLegacyScanOutstanding(token: LegacyScanToken): Boolean =
        synchronized(legacyScanStateLock) { token in outstandingLegacyScans }

    @Suppress("DEPRECATION")
    private fun LegacyPendingRecord.hasSafeLegacyPaths(): Boolean {
        val temporary = File(temporaryPath)
        val expectedPrefix = ".$workerId"
        val temporaryOwned = temporary.name.startsWith(expectedPrefix) &&
            temporary.name.endsWith(".yinlan-pending")
        return temporaryOwned &&
            isSafeLegacyImportFile(temporary) &&
            isSafeLegacyImportFile(File(targetPath))
    }

    @Suppress("DEPRECATION")
    private fun isSafeLegacyImportFile(file: File): Boolean {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/视频提取",
        )
        return runCatching { file.canonicalFile.parentFile == directory.canonicalFile }
            .getOrDefault(false)
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
        // Include the worker token so a reused bare MediaStore ID can never overwrite artwork
        // that belongs to a song on another storage volume.
        val target = File(directory, "$songId-video-$id.jpg")
        val temporary = File(directory, ".${target.name}.tmp")
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

    protected open fun queryDisplayName(uri: Uri): String? = resolver.query(
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

    private data class CompletedOutputState(
        val isPending: Boolean,
        val title: String?,
        val matchesExpectedContent: Boolean,
    )

    private data class PendingVideoRow(
        val songId: Long,
        val uri: Uri,
        val workerId: UUID,
    )

    private data class LegacyScanCallback(val uri: Uri?)

    private data class LegacyScanToken(val workerId: UUID, val targetPath: String)

    private enum class CompletedImportState { ALLOCATED, PREPARED, PUBLISHED }

    private data class CompletedImportRecord(
        val workerId: UUID,
        val state: CompletedImportState,
        val outputUri: Uri,
        val songId: Long,
        val audioConversionProcess: Int,
        val createdAtMillis: Long,
        val requestFingerprint: String?,
        val expectedSize: Long?,
        val expectedSha256: String?,
        val metadataWrittenByWorker: Boolean,
        val artworkUri: Uri?,
        val metadataTitle: String?,
        val metadataArtist: String?,
        val metadataAlbum: String?,
        val artifactOwnerWorkerId: UUID,
        val metadataWriteIntent: Boolean,
        val previousMetadataExisted: Boolean,
        val previousMetadataTitle: String?,
        val previousMetadataArtist: String?,
        val previousMetadataAlbum: String?,
        val previousMetadataArtworkPath: String?,
    ) {
        fun encode(): String = JSONObject()
            .put("workerId", workerId.toString())
            .put("state", state.name)
            .put("outputUri", outputUri.toString())
            .put("songId", songId)
            .put("audioConversionProcess", audioConversionProcess)
            .put("createdAtMillis", createdAtMillis)
            .apply {
                requestFingerprint?.let { put("requestFingerprint", it) }
                expectedSize?.let { put("expectedSize", it) }
                expectedSha256?.let { put("expectedSha256", it) }
                put("metadataWrittenByWorker", metadataWrittenByWorker)
                artworkUri?.let { put("artworkUri", it.toString()) }
                metadataTitle?.let { put("metadataTitle", it) }
                metadataArtist?.let { put("metadataArtist", it) }
                metadataAlbum?.let { put("metadataAlbum", it) }
                put("artifactOwnerWorkerId", artifactOwnerWorkerId.toString())
                put("metadataWriteIntent", metadataWriteIntent)
                put("previousMetadataExisted", previousMetadataExisted)
                previousMetadataTitle?.let { put("previousMetadataTitle", it) }
                previousMetadataArtist?.let { put("previousMetadataArtist", it) }
                previousMetadataAlbum?.let { put("previousMetadataAlbum", it) }
                previousMetadataArtworkPath?.let { put("previousMetadataArtworkPath", it) }
            }
            .toString()

        fun toWorkResult(): ListenableWorker.Result = ListenableWorker.Result.success(
            workDataOf(
                WorkManagerVideoAudioExtractor.KEY_SONG_ID to songId,
                WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI to outputUri.toString(),
                WorkManagerVideoAudioExtractor.KEY_AUDIO_CONVERSION_PROCESS to
                    audioConversionProcess,
            )
        )

        companion object {
            fun decode(encoded: String): CompletedImportRecord? = runCatching {
                val json = JSONObject(encoded)
                val workerId = UUID.fromString(json.getString("workerId"))
                CompletedImportRecord(
                    workerId = workerId,
                    state = CompletedImportState.valueOf(json.getString("state")),
                    outputUri = json.getString("outputUri").toUri(),
                    songId = json.getLong("songId").also { require(it >= 0L) },
                    audioConversionProcess = json.getInt("audioConversionProcess"),
                    createdAtMillis = json.getLong("createdAtMillis").also {
                        require(it > 0L)
                    },
                    requestFingerprint = json.optString("requestFingerprint")
                        .takeIf { it.length == SHA_256_HEX_LENGTH },
                    expectedSize = json.optLong("expectedSize", -1L).takeIf { it > 0L },
                    expectedSha256 = json.optString("expectedSha256")
                        .takeIf { it.length == SHA_256_HEX_LENGTH },
                    metadataWrittenByWorker = json.optBoolean(
                        "metadataWrittenByWorker",
                        false,
                    ),
                    artworkUri = json.optString("artworkUri")
                        .takeIf(String::isNotBlank)
                        ?.let(Uri::parse),
                    metadataTitle = json.optString("metadataTitle")
                        .takeIf(String::isNotBlank),
                    metadataArtist = json.optString("metadataArtist")
                        .takeIf(String::isNotBlank),
                    metadataAlbum = json.optString("metadataAlbum")
                        .takeIf(String::isNotBlank),
                    artifactOwnerWorkerId = json.optString("artifactOwnerWorkerId")
                        .takeIf(String::isNotBlank)
                        ?.let(UUID::fromString)
                        ?: workerId,
                    metadataWriteIntent = json.optBoolean("metadataWriteIntent", false),
                    previousMetadataExisted = json.optBoolean(
                        "previousMetadataExisted",
                        false,
                    ),
                    previousMetadataTitle = json.optString("previousMetadataTitle")
                        .takeIf { json.has("previousMetadataTitle") },
                    previousMetadataArtist = json.optString("previousMetadataArtist")
                        .takeIf { json.has("previousMetadataArtist") },
                    previousMetadataAlbum = json.optString("previousMetadataAlbum")
                        .takeIf { json.has("previousMetadataAlbum") },
                    previousMetadataArtworkPath = json.optString("previousMetadataArtworkPath")
                        .takeIf { json.has("previousMetadataArtworkPath") },
                )
            }.getOrNull()
        }
    }

    private enum class LegacyPendingStage {
        PATHS_RESERVED,
        STAGING_VERIFIED,
        TARGET_READY,
        MEDIA_INDEXED,
        COMPLETION_DURABLE,
    }

    private data class LegacyPendingRecord(
        val workerId: UUID,
        val stage: LegacyPendingStage,
        val temporaryPath: String,
        val targetPath: String,
        val outputUri: Uri?,
        val songId: Long?,
        val expectedSize: Long?,
        val expectedSha256: String?,
    ) {
        fun encode(): String = JSONObject()
            .put("workerId", workerId.toString())
            .put("stage", stage.name)
            .put("temporaryPath", temporaryPath)
            .put("targetPath", targetPath)
            .apply {
                outputUri?.let { put("outputUri", it.toString()) }
                songId?.let { put("songId", it) }
                expectedSize?.let { put("expectedSize", it) }
                expectedSha256?.let { put("expectedSha256", it) }
            }
            .toString()

        companion object {
            fun decode(encoded: String): LegacyPendingRecord? = runCatching {
                val json = JSONObject(encoded)
                val stage = LegacyPendingStage.valueOf(json.getString("stage"))
                val uri = json.optString("outputUri").takeIf(String::isNotBlank)?.let(Uri::parse)
                val songId = json.optLong("songId", -1L).takeIf { it >= 0L }
                val expectedSize = json.optLong("expectedSize", -1L).takeIf { it > 0L }
                val expectedSha256 = json.optString("expectedSha256")
                    .takeIf { it.length == SHA_256_HEX_LENGTH }
                if (stage == LegacyPendingStage.MEDIA_INDEXED ||
                    stage == LegacyPendingStage.COMPLETION_DURABLE
                ) {
                    require(uri != null && songId != null)
                }
                LegacyPendingRecord(
                    workerId = UUID.fromString(json.getString("workerId")),
                    stage = stage,
                    temporaryPath = json.getString("temporaryPath").also {
                        require(it.isNotBlank())
                    },
                    targetPath = json.getString("targetPath").also { require(it.isNotBlank()) },
                    outputUri = uri,
                    songId = songId,
                    expectedSize = expectedSize,
                    expectedSha256 = expectedSha256,
                )
            }.getOrNull()
        }
    }

    private fun CompletedImportRecord.hasExpectedAudioUri(): Boolean =
        isExpectedAudioUri(
            uri = outputUri,
            songId = songId,
            requirePrimary = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )

    private fun isExpectedAudioUri(
        uri: Uri,
        songId: Long,
        requirePrimary: Boolean,
    ): Boolean {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
        if (runCatching { ContentUris.parseId(uri) }.getOrNull() != songId) return false
        val segments = uri.pathSegments
        val audioSegment = segments.indexOf("audio")
        if (audioSegment < 1 || segments.getOrNull(audioSegment + 1) != "media") return false
        return !requirePrimary || segments.firstOrNull() == PRIMARY_EXTERNAL_MEDIA_VOLUME
    }

    private fun LegacyPendingRecord.matchesTarget(): Boolean =
        legacyFileMatchesExpectedContent(File(targetPath), expectedSize, expectedSha256)

    private companion object {
        const val MAX_ARTWORK_EDGE = 1024
        const val MAX_LEGACY_NAME_ATTEMPTS = 1_000
        const val LEGACY_SCAN_TIMEOUT_MILLIS = 60_000L
        const val SHA_256_HEX_LENGTH = 64
        const val NOTIFICATION_CHANNEL_ID = "video_audio_import"
        const val NOTIFICATION_ID = 1301
        const val COMPLETED_IMPORT_PREFERENCES = "video_audio_completed_imports"
        const val KEY_COMPLETED_IMPORT_PREFIX = "worker:"
        const val COMPLETED_IMPORT_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val LEGACY_PENDING_PREFERENCES = "video_audio_legacy_pending"
        const val KEY_LEGACY_PENDING_RECORD = "record_v2"
        const val KEY_LEGACY_PENDING_PATH = "path"
        const val KEY_LEGACY_PENDING_URI = "uri"
        const val KEY_LEGACY_PENDING_SONG_ID = "song_id"
        val legacyScannerMutex = Mutex()
        val legacyScanStateLock = Any()
        val outstandingLegacyScans = mutableSetOf<LegacyScanToken>()
    }
}
