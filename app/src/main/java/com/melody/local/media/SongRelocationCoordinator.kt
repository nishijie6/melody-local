package com.melody.local.media

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.annotation.RequiresApi
import com.melody.local.data.MoveItemRecord
import com.melody.local.data.MoveItemStatus
import com.melody.local.data.MoveJournalStore
import com.melody.local.data.MoveOperationRecord
import com.melody.local.data.MoveOperationStatus
import com.melody.local.data.PlaylistStore
import com.melody.local.data.SongMetadataStore
import com.melody.local.lyrics.LyricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class MediaAuthorizationRequest(
    val pendingIntent: PendingIntent,
    val message: String,
    val completed: Int,
    val total: Int,
)

sealed interface RelocationStep {
    data class AwaitingAuthorization(val request: MediaAuthorizationRequest) : RelocationStep
    data class Finished(val state: MediaOperationState) : RelocationStep
}

interface SongRelocationCoordinator {
    suspend fun preview(): PlaylistMovePreview
    suspend fun start(
        folderName: String,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep
    suspend fun resume(
        authorizationGranted: Boolean,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep
    suspend fun recover(onState: (MediaOperationState) -> Unit): RelocationStep?
    suspend fun cancel(onState: (MediaOperationState) -> Unit): MediaOperationState
}

class MediaStoreSongRelocationCoordinator(
    context: Context,
    private val playlists: PlaylistStore,
    private val metadata: SongMetadataStore,
    private val lyrics: LyricsStore,
    private val journal: MoveJournalStore,
) : SongRelocationCoordinator {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val operationMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val authorizedWrites = mutableSetOf<Long>()
    private val authorizedDeletes = mutableSetOf<Long>()
    private var activeOperationId: String? = null
    private var permissionContext: PermissionContext? = null
    private var lastCancellationState: MediaOperationState.Cancelled? = null

    override suspend fun preview(): PlaylistMovePreview = withContext(Dispatchers.IO) {
        val ids = deduplicateSongIds(playlists.getAllSongIds())
        var totalBytes = 0L
        var unavailable = 0
        ids.forEach { id ->
            val source = querySource(id)
            if (source == null) unavailable++ else totalBytes += source.size.coerceAtLeast(0L)
        }
        PlaylistMovePreview(ids.size, totalBytes, unavailable)
    }

    override suspend fun start(
        folderName: String,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep = operationMutex.withLock {
        activeOperationId?.let { pendingOperationId ->
            cancelRequested.set(false)
            return@withLock execute(pendingOperationId, onState)
        }
        val folder = validateDestinationFolder(folderName)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val failed = MediaOperationState.Failed("需要存储写入权限才能移动歌曲")
            onState(failed)
            return@withLock RelocationStep.Finished(failed)
        }
        cancelRequested.set(false)
        lastCancellationState = null
        authorizedWrites.clear()
        authorizedDeletes.clear()
        onState(MediaOperationState.Preparing("正在读取所有歌单歌曲…"))
        val operation = prepareOperation(folder)
        activeOperationId = operation.id
        execute(operation.id, onState)
    }

    override suspend fun resume(
        authorizationGranted: Boolean,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep = operationMutex.withLock {
        val context = permissionContext
            ?: return@withLock RelocationStep.Finished(
                MediaOperationState.Failed("系统授权请求已经失效，请重新开始")
            )
        permissionContext = null
        val operationId = context.operationId
        activeOperationId = operationId
        if (authorizationGranted) {
            when (context.kind) {
                PermissionKind.WRITE -> authorizedWrites += context.songIds
                PermissionKind.DELETE -> authorizedDeletes += context.songIds
            }
        } else {
            context.songIds.forEach { songId ->
                val item = journal.items(operationId).firstOrNull { it.oldSongId == songId }
                    ?: return@forEach
                if (item.status == MoveItemStatus.COPIED) cleanupDestination(item)
                journal.updateItem(
                    item.copy(
                        status = MoveItemStatus.CANCELLED,
                        newSongId = null,
                        destinationUri = null,
                        checksum = null,
                        error = "用户拒绝系统授权",
                    )
                )
            }
        }
        execute(operationId, onState)
    }

    override suspend fun recover(
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep? = operationMutex.withLock {
        val operation = journal.pendingOperations().firstOrNull() ?: return@withLock null
        activeOperationId = operation.id
        cancelRequested.set(false)
        onState(MediaOperationState.Preparing("正在恢复上次未完成的文件移动…"))
        cleanupLegacyMoveTemporaries(operation.targetRelativePath)
        repairInterruptedItems(operation.id)
        execute(operation.id, onState)
    }

    override suspend fun cancel(
        onState: (MediaOperationState) -> Unit,
    ): MediaOperationState {
        cancelRequested.set(true)
        return operationMutex.withLock {
            val operationId = activeOperationId
            permissionContext = null
            if (operationId == null) {
                return@withLock (lastCancellationState ?: MediaOperationState.Cancelled()).also(onState)
            }
            finishCancellation(operationId, onState)
        }
    }

    private suspend fun prepareOperation(folder: String): MoveOperationRecord {
        val targetPath = targetRelativePath(folder)
        val operation = MoveOperationRecord(
            id = UUID.randomUUID().toString(),
            targetRelativePath = targetPath,
            status = MoveOperationStatus.PREPARING,
        )
        cleanupLegacyMoveTemporaries(targetPath)
        val occupiedNames = queryTargetNames(targetPath).toMutableList()
        val items = deduplicateSongIds(playlists.getAllSongIds()).map { songId ->
            val source = querySource(songId)
            if (source == null) {
                MoveItemRecord(
                    operationId = operation.id,
                    oldSongId = songId,
                    sourceUri = songUri(songId).toString(),
                    displayName = "$songId.audio",
                    sourceSize = 0L,
                    status = MoveItemStatus.FAILED,
                    error = "歌曲文件不存在或不可读",
                )
            } else if (sourceIsAlreadyInTarget(source, targetPath)) {
                MoveItemRecord(
                    operationId = operation.id,
                    oldSongId = songId,
                    sourceUri = source.uri.toString(),
                    displayName = source.displayName,
                    sourceSize = source.size,
                    status = MoveItemStatus.SKIPPED,
                )
            } else {
                val targetName = uniqueDisplayName(source.displayName, occupiedNames)
                occupiedNames += targetName
                MoveItemRecord(
                    operationId = operation.id,
                    oldSongId = songId,
                    sourceUri = source.uri.toString(),
                    displayName = targetName,
                    sourceSize = source.size,
                    status = MoveItemStatus.PREPARED,
                )
            }
        }
        journal.create(operation, items)
        return operation
    }

    private suspend fun execute(
        operationId: String,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep {
        journal.updateOperation(operationId, MoveOperationStatus.MOVING)
        var operation = journal.pendingOperations().firstOrNull { it.id == operationId }
            ?: return finish(operationId, onState)
        var items = journal.items(operationId)
        if (cancelRequested.get()) {
            return RelocationStep.Finished(finishCancellation(operationId, onState))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val writeCandidates = items.asSequence()
                .filter { it.status == MoveItemStatus.PREPARED && it.oldSongId !in authorizedWrites }
                .filter { item -> querySource(item.oldSongId)?.isPrimaryVolume == true }
                .take(MAX_AUTHORIZATION_BATCH)
                .toList()
            if (writeCandidates.isNotEmpty()) {
                return requestModernPermission(
                    operation = operation,
                    items = items,
                    candidates = writeCandidates,
                    kind = PermissionKind.WRITE,
                    onState = onState,
                )
            }
        }

        items.forEachIndexed { index, item ->
            if (cancelRequested.get()) {
                return RelocationStep.Finished(finishCancellation(operationId, onState))
            }
            if (item.status != MoveItemStatus.PREPARED) return@forEachIndexed
            onState(
                MediaOperationState.Processing(
                    currentFile = item.displayName,
                    completed = completedCount(items),
                    total = items.size,
                    progressPercent = overallProgress(items, index, 10),
                )
            )
            val source = querySource(item.oldSongId)
            if (source == null) {
                journal.updateItem(item.copy(status = MoveItemStatus.FAILED, error = "歌曲文件已丢失"))
                return@forEachIndexed
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                runCatching { moveLegacy(operation, item, source) }
                    .onFailure { error ->
                        recordMoveFailure(operationId, item, error)
                    }
                return@forEachIndexed
            }
            if (source.isPrimaryVolume) {
                try {
                    moveInPlace(operation, item)
                } catch (security: SecurityException) {
                    val action = Api29RecoverableSecurity.actionIntent(security)
                    if (action != null) {
                        return requestLegacyPermission(
                            operation,
                            items,
                            item,
                            action,
                            PermissionKind.WRITE,
                            onState,
                        )
                    }
                    recordMoveFailure(operationId, item, security)
                } catch (error: Throwable) {
                    recordMoveFailure(operationId, item, error)
                }
            } else {
                runCatching { copyToPrimary(operation, item) }
                    .onFailure { error ->
                        recordMoveFailure(operationId, item, error)
                    }
            }
            items = journal.items(operationId)
        }

        items = journal.items(operationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val deleteCandidates = items.asSequence()
                .filter { it.status == MoveItemStatus.COPIED && it.oldSongId !in authorizedDeletes }
                .take(MAX_AUTHORIZATION_BATCH)
                .toList()
            if (deleteCandidates.isNotEmpty()) {
                return requestModernPermission(
                    operation = operation,
                    items = items,
                    candidates = deleteCandidates,
                    kind = PermissionKind.DELETE,
                    onState = onState,
                )
            }
        }

        items.forEach { item ->
            if (cancelRequested.get()) {
                return RelocationStep.Finished(finishCancellation(operationId, onState))
            }
            if (item.status != MoveItemStatus.COPIED) return@forEach
            try {
                deleteSourceAndCommit(item)
            } catch (security: SecurityException) {
                val action = Api29RecoverableSecurity.actionIntent(security)
                if (action != null) {
                    return requestLegacyPermission(
                        operation,
                        items,
                        item,
                        action,
                        PermissionKind.DELETE,
                        onState,
                    )
                }
                recordMoveFailure(operationId, item, security)
            } catch (error: Throwable) {
                recordMoveFailure(operationId, item, error)
            }
        }

        items = journal.items(operationId)
        var commitFailure: Throwable? = null
        for (item in items) {
            if (item.status == MoveItemStatus.SOURCE_DELETED) {
                try {
                    commitRemap(item)
                } catch (error: Throwable) {
                    commitFailure = commitFailure ?: error
                }
            }
        }
        if (commitFailure != null) {
            journal.updateOperation(operationId, MoveOperationStatus.COMMITTING)
            val failed = MediaOperationState.Failed(
                "歌曲文件已安全移动，但关联信息尚未全部提交；再次打开汇总功能或重启应用即可恢复：" +
                    commitFailure.safeMessage()
            )
            onState(failed)
            return RelocationStep.Finished(failed)
        }
        return finish(operationId, onState)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestModernPermission(
        operation: MoveOperationRecord,
        items: List<MoveItemRecord>,
        candidates: List<MoveItemRecord>,
        kind: PermissionKind,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep {
        val uris = candidates.map { it.sourceUri.toUri() }
        val pendingIntent = if (kind == PermissionKind.WRITE) {
            MediaStore.createWriteRequest(resolver, uris)
        } else {
            MediaStore.createDeleteRequest(resolver, uris)
        }
        return setWaitingPermission(operation, items, candidates, pendingIntent, kind, onState)
    }

    private suspend fun requestLegacyPermission(
        operation: MoveOperationRecord,
        items: List<MoveItemRecord>,
        item: MoveItemRecord,
        pendingIntent: PendingIntent,
        kind: PermissionKind,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep = setWaitingPermission(
        operation,
        items,
        listOf(item),
        pendingIntent,
        kind,
        onState,
    )

    private suspend fun setWaitingPermission(
        operation: MoveOperationRecord,
        items: List<MoveItemRecord>,
        candidates: List<MoveItemRecord>,
        pendingIntent: PendingIntent,
        kind: PermissionKind,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep {
        journal.updateOperation(operation.id, MoveOperationStatus.AWAITING_PERMISSION)
        permissionContext = PermissionContext(
            operationId = operation.id,
            songIds = candidates.map { it.oldSongId },
            kind = kind,
        )
        val verb = if (kind == PermissionKind.WRITE) "移动" else "删除原文件"
        val state = MediaOperationState.AwaitingSystemAuthorization(
            message = "需要系统授权以${verb} ${candidates.size} 首歌曲",
            completed = completedCount(items),
            total = items.size,
        )
        onState(state)
        return RelocationStep.AwaitingAuthorization(
            MediaAuthorizationRequest(
                pendingIntent = pendingIntent,
                message = state.message,
                completed = state.completed,
                total = state.total,
            )
        )
    }

    private suspend fun moveInPlace(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
    ) {
        val count = resolver.update(
            item.sourceUri.toUri(),
            ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, operation.targetRelativePath)
                put(MediaStore.Audio.Media.DISPLAY_NAME, item.displayName)
            },
            null,
            null,
        )
        check(count == 1) { "系统未能移动歌曲" }
        journal.updateItem(item.copy(status = MoveItemStatus.COMMITTED))
    }

    @Suppress("DEPRECATION")
    private suspend fun moveLegacy(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
        source: SongSource,
    ) {
        val sourceFile = source.absolutePath?.let(::File)
            ?: throw IOException("无法确定歌曲原始位置")
        check(sourceFile.isFile && sourceFile.canRead()) { "歌曲文件不存在或不可读" }
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            operation.targetRelativePath.removePrefix("${Environment.DIRECTORY_MUSIC}/"),
        ).apply { mkdirs() }
        val target = File(targetDirectory, item.displayName)
        if (sourceFile.canonicalFile == target.canonicalFile) {
            journal.updateItem(item.copy(status = MoveItemStatus.COMMITTED))
            return
        }
        check(!target.exists()) { "目标文件名已被其他应用占用，请重试" }
        val sourceHash = digestFile(sourceFile)
        val prepared = item.copy(
            status = MoveItemStatus.PREPARED,
            destinationUri = target.toUri().toString(),
            checksum = sourceHash.toHex(),
        )
        journal.updateItem(prepared)
        val sameFileStore = runCatching {
            Files.getFileStore(sourceFile.toPath()) == Files.getFileStore(targetDirectory.toPath())
        }.getOrDefault(false)
        val movedInPlace = sameFileStore && runCatching {
            Files.move(sourceFile.toPath(), target.toPath())
            true
        }.getOrDefault(false)
        if (movedInPlace) {
            val changed = resolver.update(
                source.uri,
                ContentValues().apply { put(MediaStore.Audio.Media.DATA, target.absolutePath) },
                null,
                null,
            )
            if (changed != 1) {
                val rolledBack = runCatching {
                    Files.move(target.toPath(), sourceFile.toPath())
                }.isSuccess
                if (!rolledBack) {
                    journal.updateItem(
                        prepared.copy(
                            status = MoveItemStatus.SOURCE_DELETED,
                            error = "歌曲已移动，但系统音乐库位置更新失败",
                        )
                    )
                }
                throw IOException("系统音乐库未能更新歌曲位置")
            }
            journal.updateItem(prepared.copy(status = MoveItemStatus.COMMITTED))
            return
        }

        val temporary = File(targetDirectory, ".${operation.id}-${item.oldSongId}.yinlan-moving")
        temporary.delete()
        val copyDigest = MessageDigest.getInstance("SHA-256")
        val copiedBytes = sourceFile.inputStream().use { input ->
            temporary.outputStream().use { output -> copyAndDigest(input, output, copyDigest) }
        }
        val streamedHash = copyDigest.digest()
        val temporaryDigest = digestFile(temporary)
        check(
            copyVerificationPassed(
                sourceFile.length(),
                copiedBytes,
                sourceHash,
                streamedHash,
            ) && streamedHash.contentEquals(temporaryDigest)
        ) {
            temporary.delete()
            "歌曲复制校验失败"
        }
        check(!target.exists() && temporary.renameTo(target)) {
            temporary.delete()
            "无法安全创建目标歌曲文件，请重试"
        }
        var copied = prepared.copy(
            status = MoveItemStatus.COPIED,
            checksum = temporaryDigest.toHex(),
        )
        journal.updateItem(copied)
        check(sourceFile.delete()) { "无法删除原歌曲文件" }
        copied = copied.copy(status = MoveItemStatus.SOURCE_DELETED)
        journal.updateItem(copied)
        val scanned = scanFile(target, source.mimeType)
        copied = copied.copy(
            newSongId = ContentUris.parseId(scanned),
            destinationUri = scanned.toString(),
        )
        journal.updateItem(copied)
        commitRemap(copied)
    }

    private suspend fun copyToPrimary(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
    ) {
        val sourceUri = item.sourceUri.toUri()
        val source = querySource(item.oldSongId) ?: throw IOException("歌曲文件已丢失")
        val targetCollection = MediaStore.Audio.Media.getContentUri("external_primary")
        val destination = resolver.insert(
            targetCollection,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, item.displayName)
                put(MediaStore.Audio.Media.TITLE, source.title)
                put(MediaStore.Audio.Media.ARTIST, source.artist)
                put(MediaStore.Audio.Media.ALBUM, source.album)
                put(MediaStore.Audio.Media.MIME_TYPE, source.mimeType)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, operation.targetRelativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: throw IOException("无法创建目标歌曲文件")
        val preparedWithDestination = item.copy(
            status = MoveItemStatus.PREPARED,
            newSongId = ContentUris.parseId(destination),
            destinationUri = destination.toString(),
        )
        journal.updateItem(preparedWithDestination)
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val copiedBytes = resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destination, "w")?.use { output ->
                    copyAndDigest(input, output, sourceDigest)
                }
            } ?: throw IOException("无法读取或写入歌曲文件")
            val sourceHash = sourceDigest.digest()
            val destinationHash = resolver.openInputStream(destination)?.use(::digestStream)
                ?: throw IOException("无法校验目标歌曲文件")
            check(copyVerificationPassed(item.sourceSize, copiedBytes, sourceHash, destinationHash)) {
                "歌曲复制校验失败，原文件未删除"
            }
            journal.updateItem(
                preparedWithDestination.copy(
                    status = MoveItemStatus.COPIED,
                    checksum = sourceHash.toHex(),
                )
            )
        } catch (error: Throwable) {
            runCatching { resolver.delete(destination, null, null) }
            throw error
        }
    }

    private suspend fun deleteSourceAndCommit(item: MoveItemRecord) {
        val deleted = resolver.delete(item.sourceUri.toUri(), null, null)
        check(
            deleted == 1 || sourceAvailability(item.sourceUri.toUri()) == SourceAvailability.MISSING
        ) { "无法删除原歌曲文件" }
        val sourceDeleted = item.copy(status = MoveItemStatus.SOURCE_DELETED)
        journal.updateItem(sourceDeleted)
        commitRemap(sourceDeleted)
    }

    private suspend fun commitRemap(item: MoveItemRecord) {
        var resolvedItem = item
        val newSongId = item.newSongId ?: run {
            val destinationUri = item.destinationUri?.toUri()
                ?: throw IOException("目标歌曲记录不完整")
            if (destinationUri.scheme == "file") {
                val file = destinationUri.path?.let(::File)
                    ?: throw IOException("目标歌曲路径无效")
                val scanned = scanFile(file, "audio/*")
                resolvedItem = item.copy(
                    newSongId = ContentUris.parseId(scanned),
                    destinationUri = scanned.toString(),
                )
                journal.updateItem(resolvedItem)
                resolvedItem.newSongId!!
            } else {
                ContentUris.parseId(destinationUri)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching { resolver.delete(item.sourceUri.toUri(), null, null) }
        }
        playlists.remapSongIds(mapOf(item.oldSongId to newSongId))
        metadata.remap(item.oldSongId, newSongId)
        lyrics.remap(item.oldSongId, newSongId)
        resolvedItem.destinationUri?.toUri()?.takeIf { it.scheme == "content" }?.let { destination ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
        journal.updateItem(
            resolvedItem.copy(
                status = MoveItemStatus.COMMITTED,
                newSongId = newSongId,
                error = null,
            )
        )
    }

    private suspend fun repairInterruptedItems(operationId: String) {
        journal.items(operationId).forEach { item ->
            if (item.status == MoveItemStatus.PREPARED && item.destinationUri != null) {
                val availability = sourceAvailability(item.sourceUri.toUri())
                if (availability == SourceAvailability.MISSING && preparedDestinationIsComplete(item)) {
                    journal.updateItem(item.copy(status = MoveItemStatus.SOURCE_DELETED))
                } else {
                    cleanupDestination(item)
                    journal.updateItem(
                        item.copy(
                            status = if (availability == SourceAvailability.PRESENT) {
                                MoveItemStatus.PREPARED
                            } else {
                                MoveItemStatus.FAILED
                            },
                            newSongId = null,
                            destinationUri = null,
                            checksum = null,
                            error = if (availability == SourceAvailability.PRESENT) {
                                null
                            } else {
                                "源文件不可访问，未删除原文件"
                            },
                        )
                    )
                }
                return@forEach
            }
            val availability = sourceAvailability(item.sourceUri.toUri())
            if (availability == SourceAvailability.INACCESSIBLE &&
                item.status != MoveItemStatus.SOURCE_DELETED
            ) {
                if (item.status == MoveItemStatus.COPIED) cleanupDestination(item)
                journal.updateItem(
                    item.copy(
                        status = MoveItemStatus.FAILED,
                        newSongId = null,
                        destinationUri = null,
                        checksum = null,
                        error = "源文件不可访问，未删除原文件",
                    )
                )
                return@forEach
            }
            when (moveRecoveryAction(item.status, availability == SourceAvailability.PRESENT)) {
                MoveRecoveryAction.CLEAN_TARGET_AND_RETRY -> {
                        cleanupDestination(item)
                        journal.updateItem(
                            item.copy(
                                status = MoveItemStatus.PREPARED,
                                newSongId = null,
                                destinationUri = null,
                                checksum = null,
                            )
                        )
                }
                MoveRecoveryAction.COMMIT_REMAP -> {
                    val sourceDeleted = item.copy(status = MoveItemStatus.SOURCE_DELETED)
                    journal.updateItem(sourceDeleted)
                }
                MoveRecoveryAction.CONTINUE -> Unit
            }
        }
    }

    private suspend fun finishCancellation(
        operationId: String,
        onState: (MediaOperationState) -> Unit,
    ): MediaOperationState {
        journal.items(operationId).forEach { item ->
            when (item.status) {
                MoveItemStatus.COPIED -> {
                    if (sourceAvailability(item.sourceUri.toUri()) != SourceAvailability.MISSING) {
                        cleanupDestination(item)
                        journal.updateItem(
                            item.copy(
                                status = MoveItemStatus.CANCELLED,
                                newSongId = null,
                                destinationUri = null,
                                checksum = null,
                                error = "用户取消",
                            )
                        )
                    } else {
                        commitRemap(item.copy(status = MoveItemStatus.SOURCE_DELETED))
                    }
                }
                MoveItemStatus.SOURCE_DELETED -> commitRemap(item)
                MoveItemStatus.PREPARED -> {
                    cleanupPreparedArtifacts(item)
                    journal.updateItem(
                        item.copy(
                            status = MoveItemStatus.CANCELLED,
                            newSongId = null,
                            destinationUri = null,
                            checksum = null,
                            error = "用户取消",
                        )
                    )
                }
                else -> Unit
            }
        }
        journal.updateOperation(operationId, MoveOperationStatus.CANCELLED)
        val summary = summarize(journal.items(operationId))
        activeOperationId = null
        cancelRequested.set(false)
        return MediaOperationState.Cancelled(summary).also {
            lastCancellationState = it
            onState(it)
        }
    }

    private suspend fun finish(
        operationId: String,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep {
        val items = journal.items(operationId)
        val unfinished = items.any {
            it.status == MoveItemStatus.PREPARED ||
                it.status == MoveItemStatus.COPIED ||
                it.status == MoveItemStatus.SOURCE_DELETED
        }
        if (unfinished) return execute(operationId, onState)
        journal.updateOperation(operationId, MoveOperationStatus.COMPLETED)
        activeOperationId = null
        permissionContext = null
        val completed = MediaOperationState.Completed(summarize(items))
        onState(completed)
        return RelocationStep.Finished(completed)
    }

    private fun summarize(items: List<MoveItemRecord>) = MediaOperationSummary(
        moved = items.count { it.status == MoveItemStatus.COMMITTED },
        skipped = items.count { it.status == MoveItemStatus.SKIPPED },
        failed = items.count { it.status == MoveItemStatus.FAILED },
        cancelled = items.count { it.status == MoveItemStatus.CANCELLED },
    )

    private suspend fun recordMoveFailure(
        operationId: String,
        original: MoveItemRecord,
        error: Throwable,
    ) {
        val latest = journal.items(operationId).firstOrNull { it.oldSongId == original.oldSongId }
            ?: original
        val destinationExists = latest.destinationUri != null
        val availability = sourceAvailability(latest.sourceUri.toUri())
        val sourceWasDeleted = latest.status == MoveItemStatus.SOURCE_DELETED ||
            (latest.status == MoveItemStatus.COPIED && destinationExists &&
                availability == SourceAvailability.MISSING)
        if (sourceWasDeleted) {
            journal.updateItem(
                latest.copy(
                    status = MoveItemStatus.SOURCE_DELETED,
                    error = error.safeMessage(),
                )
            )
            return
        }
        if (cancelRequested.get()) {
            cleanupPreparedArtifacts(latest)
            journal.updateItem(
                latest.copy(
                    status = MoveItemStatus.CANCELLED,
                    newSongId = null,
                    destinationUri = null,
                    checksum = null,
                    error = "用户取消",
                )
            )
            return
        }
        if (latest.status == MoveItemStatus.COPIED) cleanupDestination(latest)
        if (latest.status == MoveItemStatus.PREPARED) cleanupPreparedArtifacts(latest)
        journal.updateItem(
            latest.copy(
                status = MoveItemStatus.FAILED,
                newSongId = null,
                destinationUri = null,
                checksum = null,
                error = error.safeMessage(),
            )
        )
    }

    private suspend fun cleanupDestination(item: MoveItemRecord) {
        val uri = item.destinationUri?.toUri() ?: return
        if (uri.scheme == "file") uri.path?.let(::File)?.delete()
        else runCatching { resolver.delete(uri, null, null) }
    }

    private suspend fun cleanupPreparedArtifacts(item: MoveItemRecord) {
        cleanupDestination(item)
        val destination = item.destinationUri?.toUri()
        if (destination?.scheme == "file") {
            val directory = destination.path?.let(::File)?.parentFile
            directory?.resolve(".${item.operationId}-${item.oldSongId}.yinlan-moving")?.delete()
        }
    }

    private fun querySource(songId: Long): SongSource? {
        val uri = songUri(songId)
        val projection = buildList {
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun string(column: String): String? =
                    cursor.getColumnIndex(column).takeIf { it >= 0 }?.let(cursor::getString)
                fun long(column: String): Long =
                    cursor.getColumnIndex(column).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L
                val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    string(MediaStore.MediaColumns.VOLUME_NAME)
                } else null
                @Suppress("DEPRECATION")
                SongSource(
                    uri = uri,
                    displayName = string(MediaStore.Audio.Media.DISPLAY_NAME)
                        ?.takeIf(String::isNotBlank) ?: "$songId.audio",
                    size = long(MediaStore.Audio.Media.SIZE),
                    mimeType = string(MediaStore.Audio.Media.MIME_TYPE) ?: "audio/*",
                    title = string(MediaStore.Audio.Media.TITLE) ?: "未知歌曲",
                    artist = string(MediaStore.Audio.Media.ARTIST) ?: "未知歌手",
                    album = string(MediaStore.Audio.Media.ALBUM) ?: "未知专辑",
                    relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        string(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else null,
                    volumeName = volume,
                    absolutePath = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        string(MediaStore.Audio.Media.DATA)
                    } else null,
                )
            }
        }.getOrNull()
    }

    private fun queryTargetNames(relativePath: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                relativePath.removePrefix("${Environment.DIRECTORY_MUSIC}/"),
            )
            return directory.list()?.toList().orEmpty()
        }
        val names = mutableListOf<String>()
        resolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
        }
        return names
    }

    @Suppress("DEPRECATION")
    private fun cleanupLegacyMoveTemporaries(relativePath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            relativePath.removePrefix("${Environment.DIRECTORY_MUSIC}/"),
        )
        directory.listFiles()
            ?.filter { it.name.startsWith('.') && it.name.endsWith(".yinlan-moving") }
            ?.forEach(File::delete)
    }

    @Suppress("DEPRECATION")
    private fun sourceIsAlreadyInTarget(source: SongSource, relativePath: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return source.relativePath.equals(relativePath, ignoreCase = true)
        }
        val sourceParent = source.absolutePath?.let(::File)?.parentFile ?: return false
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            relativePath.removePrefix("${Environment.DIRECTORY_MUSIC}/"),
        )
        return runCatching { sourceParent.canonicalFile == targetDirectory.canonicalFile }
            .getOrDefault(false)
    }

    private fun sourceAvailability(uri: Uri): SourceAvailability {
        if (uri.scheme == "file") {
            return if (uri.path?.let(::File)?.isFile == true) {
                SourceAvailability.PRESENT
            } else {
                SourceAvailability.MISSING
            }
        }
        return try {
            val rowExists = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            if (!rowExists) return SourceAvailability.MISSING
            val readable = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { true } == true
            }.getOrDefault(false)
            if (readable) {
                SourceAvailability.PRESENT
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val songId = runCatching { ContentUris.parseId(uri) }.getOrNull()
                val physicalFileExists = songId?.let(::querySource)?.absolutePath
                    ?.let(::File)?.isFile == true
                if (physicalFileExists) SourceAvailability.INACCESSIBLE else SourceAvailability.MISSING
            } else {
                SourceAvailability.INACCESSIBLE
            }
        } catch (_: SecurityException) {
            SourceAvailability.INACCESSIBLE
        } catch (_: Throwable) {
            SourceAvailability.INACCESSIBLE
        }
    }

    private fun preparedDestinationIsComplete(item: MoveItemRecord): Boolean {
        val expectedHash = item.checksum ?: return false
        val destination = item.destinationUri?.toUri() ?: return false
        if (destination.scheme != "file") return false
        val file = destination.path?.let(::File) ?: return false
        if (!file.isFile || file.length() <= 0L) return false
        if (item.sourceSize > 0L && file.length() != item.sourceSize) return false
        return runCatching { digestFile(file).toHex() == expectedHash }.getOrDefault(false)
    }

    private suspend fun scanFile(file: File, mimeType: String): Uri =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
            ) { _, uri ->
                if (uri != null) continuation.resume(uri)
                else continuation.cancel(IOException("系统音乐库未能收录目标歌曲"))
            }
        }

    private fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (cancelRequested.get()) throw IOException("用户取消")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
        }
        output.flush()
        return total
    }

    private fun digestFile(file: File): ByteArray = file.inputStream().use(::digestStream)

    private fun digestStream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (cancelRequested.get()) throw IOException("用户取消")
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun songUri(songId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)

    private fun targetRelativePath(folder: String): String =
        "${Environment.DIRECTORY_MUSIC}/音澜/$folder/"

    private fun completedCount(items: List<MoveItemRecord>): Int = items.count {
        it.status == MoveItemStatus.COMMITTED ||
            it.status == MoveItemStatus.SKIPPED ||
            it.status == MoveItemStatus.FAILED ||
            it.status == MoveItemStatus.CANCELLED
    }

    private fun overallProgress(items: List<MoveItemRecord>, index: Int, withinItem: Int): Int {
        if (items.isEmpty()) return 100
        return (((completedCount(items) + withinItem / 100f) / items.size) * 100)
            .toInt().coerceIn(0, 99)
    }

    private fun Throwable.safeMessage(): String = when (this) {
        is SecurityException -> "没有修改该歌曲文件的权限"
        is IOException -> message ?: "文件读写失败"
        else -> message ?: "移动歌曲失败"
    }

    private data class SongSource(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val mimeType: String,
        val title: String,
        val artist: String,
        val album: String,
        val relativePath: String?,
        val volumeName: String?,
        val absolutePath: String?,
    ) {
        val isPrimaryVolume: Boolean
            get() = relocationRoute(Build.VERSION.SDK_INT, volumeName) ==
                SongRelocationRoute.IN_PLACE_MEDIASTORE
    }

    private data class PermissionContext(
        val operationId: String,
        val songIds: List<Long>,
        val kind: PermissionKind,
    )

    private enum class PermissionKind { WRITE, DELETE }

    private enum class SourceAvailability { PRESENT, MISSING, INACCESSIBLE }

    private companion object {
        const val MAX_AUTHORIZATION_BATCH = 2_000
    }
}

private object Api29RecoverableSecurity {
    fun actionIntent(error: SecurityException): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return actionIntentApi29(error)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun actionIntentApi29(error: SecurityException): PendingIntent? =
        (error as? RecoverableSecurityException)?.userAction?.actionIntent
}
