package com.melody.local.media

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
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
    private var mediaAccess: RelocationMediaAccess = AndroidRelocationMediaAccess
    private val operationMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val authorizedWrites = mutableSetOf<Long>()
    private val authorizedDeletes = mutableSetOf<Long>()
    private var activeOperationId: String? = null
    private var permissionContext: PermissionContext? = null
    private var lastCancellationState: MediaOperationState.Cancelled? = null

    internal constructor(
        context: Context,
        playlists: PlaylistStore,
        metadata: SongMetadataStore,
        lyrics: LyricsStore,
        journal: MoveJournalStore,
        mediaAccess: RelocationMediaAccess,
    ) : this(context, playlists, metadata, lyrics, journal) {
        this.mediaAccess = mediaAccess
    }

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
            journal.pendingOperations().firstOrNull { it.id == pendingOperationId }?.let { operation ->
                upgradeSyntheticSourceUris(operation.id)
                repairInterruptedItems(operation)
            }
            return@withLock execute(pendingOperationId, onState)
        }
        // Startup recovery is launched asynchronously by the ViewModel, so a user can tap Start
        // before that coroutine reaches recover(). The coordinator mutex is the final gate: never
        // create another journal until persisted work has been claimed and advanced first.
        val persistedOperations = orderedPendingOperations()
        persistedOperations.firstOrNull()?.let { persisted ->
            cancelRequested.set(false)
            lastCancellationState = null
            authorizedWrites.clear()
            authorizedDeletes.clear()
            permissionContext = null
            activeOperationId = persisted.id
            val prefix = if (persistedOperations.size > 1) {
                "检测到 ${persistedOperations.size} 个历史移动任务，先恢复最早的任务…"
            } else {
                "正在恢复上次未完成的文件移动…"
            }
            onState(MediaOperationState.Preparing(prefix))
            upgradeSyntheticSourceUris(persisted.id)
            repairInterruptedItems(persisted)
            return@withLock execute(persisted.id, onState)
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
                when {
                    item.status == MoveItemStatus.COPIED ->
                        cancelItemWithoutDestructiveRollback(item, "用户拒绝系统授权")
                    item.status == MoveItemStatus.PREPARED && item.destinationUri != null ->
                        cancelItemWithoutDestructiveRollback(item, "用户拒绝系统授权")
                    item.status == MoveItemStatus.PREPARED -> journal.updateItem(
                        item.copy(status = MoveItemStatus.CANCELLED, error = "用户拒绝系统授权")
                    )
                    else -> Unit
                }
            }
        }
        execute(operationId, onState)
    }

    override suspend fun recover(
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep? = operationMutex.withLock {
        // A cancellation can win the mutex before this startup recovery coroutine. In that case
        // the cancellation has already claimed and terminated the durable operation; do not
        // immediately start the next pending record behind the user's "cancelled" result.
        if (activeOperationId == null && lastCancellationState != null) {
            return@withLock null
        }
        val pending = orderedPendingOperations()
        val operation = activeOperationId
            ?.let { activeId -> pending.firstOrNull { it.id == activeId } }
            ?: pending.firstOrNull()
            ?: return@withLock null
        activeOperationId = operation.id
        cancelRequested.set(false)
        val message = if (pending.size > 1) {
            "检测到 ${pending.size} 个历史移动任务，先恢复最早的任务…"
        } else {
            "正在恢复上次未完成的文件移动…"
        }
        onState(MediaOperationState.Preparing(message))
        upgradeSyntheticSourceUris(operation.id)
        repairInterruptedItems(operation)
        execute(operation.id, onState)
    }

    private suspend fun orderedPendingOperations(): List<MoveOperationRecord> =
        orderPendingMoveOperations(journal.pendingOperations())

    override suspend fun cancel(
        onState: (MediaOperationState) -> Unit,
    ): MediaOperationState {
        cancelRequested.set(true)
        return operationMutex.withLock {
            // The ViewModel exposes recovery synchronously, but its coroutine may not have claimed
            // the journal yet. Cancellation must therefore claim the same oldest durable operation
            // under this mutex instead of returning a cosmetic Cancelled state while recover()
            // later continues moving files.
            val operationId = activeOperationId ?: orderedPendingOperations().firstOrNull()?.id
            permissionContext = null
            if (operationId == null) {
                return@withLock (lastCancellationState ?: MediaOperationState.Cancelled()).also(onState)
            }
            activeOperationId = operationId
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
                    error = sourceResolutionError(songId),
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
        upgradeSyntheticSourceUris(operationId)
        var operation = journal.pendingOperations().firstOrNull { it.id == operationId }
            ?: return finish(operationId, onState)
        if (operation.status == MoveOperationStatus.CANCELLING) {
            return RelocationStep.Finished(finishCancellation(operationId, onState))
        }
        journal.updateOperation(operationId, MoveOperationStatus.MOVING)
        operation = operation.copy(status = MoveOperationStatus.MOVING)
        var items = journal.items(operationId)
        if (cancelRequested.get()) {
            return RelocationStep.Finished(finishCancellation(operationId, onState))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val writeCandidates = items.asSequence()
                .filter { it.status == MoveItemStatus.PREPARED && it.oldSongId !in authorizedWrites }
                .filter { item -> querySource(item)?.isPrimaryVolume == true }
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
            val source = querySource(item)
            if (source == null) {
                journal.updateItem(item.copy(status = MoveItemStatus.FAILED, error = "歌曲文件已丢失"))
                return@forEachIndexed
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                runCatching { moveLegacy(operation, item, source) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
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
                runCatching {
                    if (item.destinationUri == null) {
                        copyToPrimary(operation, item)
                    } else {
                        resumeCopyToPrimary(operation, item)
                    }
                }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
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
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    deleteLegacySourceAndCommit(item)
                } else {
                    deleteSourceAndCommit(item)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    commitFailure = commitFailure ?: error
                    val latest = journal.items(operationId)
                        .firstOrNull { it.oldSongId == item.oldSongId } ?: item
                    journal.updateItem(
                        latest.copy(status = MoveItemStatus.SOURCE_DELETED, error = error.safeMessage())
                    )
                }
            }
        }
        val finalCommitFailure = commitFailure
        if (finalCommitFailure != null) {
            journal.updateOperation(operationId, MoveOperationStatus.COMMITTING)
            val failed = MediaOperationState.Failed(
                "歌曲文件已安全移动，但关联信息尚未全部提交；再次打开汇总功能或重启应用即可恢复：" +
                    finalCommitFailure.safeMessage()
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
        val uris = candidates.map(::concreteSourceUri)
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
            concreteSourceUri(item),
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
        val cancellationContext = currentCoroutineContext()
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
        val sourceHash = digestFile(sourceFile, cancellationContext)
        val prepared = item.copy(
            status = MoveItemStatus.PREPARED,
            destinationUri = target.toUri().toString(),
            checksum = sourceHash.toHex(),
        )
        journal.updateItem(prepared)
        // Android 8-9 deliberately uses the verified copy/delete protocol even when a filesystem
        // rename would be possible. A rename creates an unrecoverable gap between moving the only
        // file, updating MediaStore.DATA and committing the journal. Keeping the source until the
        // target copy is durable makes every persisted state recoverable after process death.
        val temporary = File(targetDirectory, ".${operation.id}-${item.oldSongId}.yinlan-moving")
        check(!temporary.exists()) { "本次移动的临时文件已存在，请先恢复上次操作" }
        val copyDigest = MessageDigest.getInstance("SHA-256")
        val copiedBytes = sourceFile.inputStream().use { input ->
            temporary.outputStream().use { output ->
                copyAndDigest(input, output, copyDigest, cancellationContext)
            }
        }
        val streamedHash = copyDigest.digest()
        val temporaryDigest = digestFile(temporary, cancellationContext)
        check(
            copyVerificationPassed(
                sourceFile.length(),
                copiedBytes,
                sourceHash,
                streamedHash,
            ) && streamedHash.contentEquals(temporaryDigest)
        ) {
            "歌曲复制校验失败"
        }
        check(installLegacyDestinationFromVerifiedTemporary(prepared, cancellationContext)) {
            "无法安全创建并校验目标歌曲文件，原文件未删除"
        }
        val copied = prepared.copy(
            status = MoveItemStatus.COPIED,
            checksum = temporaryDigest.toHex(),
        )
        journal.updateItem(copied)
        deleteLegacySourceAndCommit(copied)
    }

    @Suppress("DEPRECATION")
    private suspend fun deleteLegacySourceAndCommit(item: MoveItemRecord) {
        val cancellationContext = currentCoroutineContext()
        val source = querySource(item) ?: throw IOException("无法读取旧版媒体库源记录")
        val sourceFile = source.absolutePath?.let(::File)
            ?: throw IOException("无法确定歌曲原始位置")
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: throw IOException("目标歌曲路径无效")
        val temporary = legacyTemporary(item)
            ?: throw IOException("无法确定本次移动的安全临时文件")
        check(fileMatchesJournal(temporary, item, cancellationContext)) {
            "安全临时副本不存在或校验失败，未处理原歌曲"
        }
        if (!fileMatchesJournal(destination, item, cancellationContext)) {
            check(installLegacyDestinationFromVerifiedTemporary(item, cancellationContext)) {
                "目标歌曲不存在或已变化，安全临时副本和原歌曲均已保留"
            }
        }

        val quarantine = legacySourceQuarantine(item, sourceFile)
        if (!quarantine.exists() && sourceFile.exists()) {
            if (!fileMatchesJournal(sourceFile, item, cancellationContext)) {
                // The path no longer contains the bytes copied by this operation. Never delete or
                // move that replacement. The verified target/temp remain the canonical old bytes;
                // updating MediaStore.DATA later leaves the replacement filesystem entry alone.
                val sourceDetached = item.copy(
                    status = MoveItemStatus.SOURCE_DELETED,
                    error = "原路径内容已被替换，未删除该外部文件",
                )
                journal.updateItem(sourceDetached)
                check(fileMatchesJournal(destination, sourceDetached, cancellationContext)) {
                    "目标歌曲随后发生变化；安全临时副本和恢复日志已保留"
                }
                commitRemap(sourceDetached)
                return
            }
            throwIfCancellationRequested(cancellationContext)
            currentCoroutineContext().ensureActive()
            check(moveFileNoReplace(sourceFile, quarantine)) {
                "无法把原歌曲隔离到本次操作的安全路径，未执行删除"
            }
        }

        if (quarantine.exists()) {
            var quarantineDeleted = false
            try {
                check(fileMatchesJournal(quarantine, item, cancellationContext)) {
                    "隔离后的原歌曲校验失败"
                }
                // Hash the final destination after the quarantined source. No subsequent
                // operation touches sourceFile, so a replacement appearing at the old path
                // cannot be deleted.
                check(fileMatchesJournal(destination, item, cancellationContext)) {
                    "目标歌曲在删除前发生变化"
                }
                throwIfCancellationRequested(cancellationContext)
                currentCoroutineContext().ensureActive()
                if (!quarantine.delete() || quarantine.exists()) {
                    throw IOException("无法删除已验证的隔离源")
                }
                quarantineDeleted = true
            } catch (error: Throwable) {
                // Hashing is cooperatively cancellable. Once the source has been renamed into
                // quarantine, *every* exit before the verified delete must restore it without
                // replacing a concurrently-created file. Otherwise a perfectly ordinary Cancel
                // tap can leave the song missing at its original path until the next recovery.
                if (!quarantineDeleted && quarantine.exists()) {
                    if (restoreLegacyQuarantineNoReplace(quarantine, sourceFile)) {
                        throw error
                    }
                    withContext(NonCancellable + Dispatchers.IO) {
                        journal.updateItem(
                            item.copy(
                                status = MoveItemStatus.FAILED,
                                error = "${error.safeMessage()}；原路径已被占用或无法恢复，" +
                                    "隔离源、目标、临时副本和恢复日志均已保留",
                            )
                        )
                    }
                    throw IOException(
                        "${error.safeMessage()}；无法把隔离源恢复到原路径，已保留恢复日志",
                        error,
                    )
                }
                if (!quarantineDeleted && !sourceFile.exists()) {
                    // An external actor may remove the quarantine between the failed delete and
                    // this check. The source is then genuinely gone, so persist fail-forward
                    // state before cancellation can resume; recovery will publish/remap the
                    // already verified destination rather than pretending rollback succeeded.
                    withContext(NonCancellable + Dispatchers.IO) {
                        journal.updateItem(
                            item.copy(
                                status = MoveItemStatus.SOURCE_DELETED,
                                error = "${error.safeMessage()}；隔离源已不可见，将从已验证目标恢复",
                            )
                        )
                    }
                }
                throw error
            }
        } else {
            // A crash may occur after quarantine deletion but before SOURCE_DELETED is journaled.
            check(!sourceFile.exists()) {
                "原歌曲与隔离文件状态不一致，未执行任何删除"
            }
        }

        val sourceDeleted = item.copy(status = MoveItemStatus.SOURCE_DELETED)
        journal.updateItem(sourceDeleted)
        check(fileMatchesJournal(destination, sourceDeleted, cancellationContext)) {
            "原歌曲已删除，但目标歌曲随后发生变化；安全临时副本和日志已保留"
        }
        // Preserve the existing MediaStore row/ID whenever it survived the file move. commitRemap
        // updates DATA first and scans only as a fallback when that row has disappeared.
        commitRemap(sourceDeleted, expectedLegacySourcePath = sourceFile.absolutePath)
    }

    private suspend fun copyToPrimary(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
    ) {
        val source = querySource(item) ?: throw IOException("歌曲文件已丢失")
        val targetCollection = MediaStore.Audio.Media.getContentUri(PRIMARY_EXTERNAL_MEDIA_VOLUME)
        val recoveryRelativePath = pendingMoveDestinationRelativePath(
            operation.targetRelativePath,
            item.operationId,
            item.oldSongId,
        )
        val destination = resolver.insert(
            targetCollection,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, item.displayName)
                // This deterministic, operation-scoped marker closes the otherwise unavoidable
                // crash window between MediaStore.insert() and persisting the returned Uri.
                put(MediaStore.Audio.Media.TITLE, source.title)
                put(MediaStore.Audio.Media.ARTIST, source.artist)
                put(MediaStore.Audio.Media.ALBUM, source.album)
                put(MediaStore.Audio.Media.MIME_TYPE, source.mimeType)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, recoveryRelativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: throw IOException("无法创建目标歌曲文件")
        val preparedWithDestination = item.copy(
            status = MoveItemStatus.PREPARED,
            newSongId = ContentUris.parseId(destination),
            destinationUri = destination.toString(),
        )
        var destinationWasJournaled = false
        try {
            journal.updateItem(preparedWithDestination)
            destinationWasJournaled = true
            check(journaledPendingDestinationMatches(operation, preparedWithDestination, source)) {
                "目标歌曲记录不再属于本次移动"
            }
            writePendingDestination(preparedWithDestination, source)
        } catch (error: Throwable) {
            if (!destinationWasJournaled) {
                val cleanupFailure = runCatching { cleanupDestination(preparedWithDestination) }
                    .exceptionOrNull()
                if (cleanupFailure != null) {
                    runCatching {
                        journal.updateItem(
                            preparedWithDestination.copy(
                                status = MoveItemStatus.FAILED,
                                error = cleanupFailureMessage(error, cleanupFailure),
                            )
                        )
                    }
                }
            }
            throw error
        }
    }

    /**
     * Continues a copy whose app-owned pending MediaStore row was durably journaled before the
     * process stopped. The exact row, primary volume, path, name and operation marker/title are
     * checked before it is reopened with mode "w"; an arbitrary existing row is never adopted.
     */
    private suspend fun resumeCopyToPrimary(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
    ) {
        val source = querySource(item) ?: throw IOException("歌曲文件已丢失")
        check(journaledPendingDestinationMatches(operation, item, source)) {
            "无法确认崩溃前的目标记录，原文件未删除"
        }
        writePendingDestination(item, source)
    }

    private suspend fun writePendingDestination(
        item: MoveItemRecord,
        source: SongSource,
    ) {
        val cancellationContext = currentCoroutineContext()
        val sourceUri = concreteSourceUri(item)
        val destination = item.destinationUri?.toUri()
            ?: throw IOException("目标歌曲记录不完整")
        throwIfCancellationRequested(cancellationContext)
        cancellationContext.ensureActive()
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val copiedBytes = resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(destination, "w")?.use { output ->
                copyAndDigest(input, output, sourceDigest, cancellationContext)
            }
        } ?: throw IOException("无法读取或写入歌曲文件")
        val sourceHash = sourceDigest.digest()
        val destinationHash = resolver.openInputStream(destination)?.use {
            digestStream(it, cancellationContext)
        }
            ?: throw IOException("无法校验目标歌曲文件")
        check(copyVerificationPassed(item.sourceSize, copiedBytes, sourceHash, destinationHash)) {
            "歌曲复制校验失败，原文件未删除"
        }
        // Keep the verified copy inside its operation-scoped hidden recovery directory until the
        // source has actually been deleted. Some MediaStore implementations reject an early
        // RELATIVE_PATH move on a pending row. Finalize the path and publish atomically in
        // commitRemap(), after SOURCE_DELETED is durable, so recovery can always find this row.
        journal.updateItem(
            item.copy(
                status = MoveItemStatus.COPIED,
                checksum = sourceHash.toHex(),
                error = null,
            )
        )
    }

    private fun journaledPendingDestinationMatches(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
        source: SongSource,
    ): Boolean {
        val destination = item.destinationUri?.toUri() ?: return false
        if (destination.scheme != "content" ||
            destination.pathSegments.firstOrNull() != PRIMARY_EXTERNAL_MEDIA_VOLUME ||
            runCatching { ContentUris.parseId(destination) }.getOrNull() != item.newSongId
        ) {
            return false
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.IS_PENDING,
            MediaStore.MediaColumns.VOLUME_NAME,
        )
        return runCatching {
            val queryUri = includePendingMedia(destination)
            val cursor = mediaAccess.query(resolver, queryUri, projection) ?: return false
            cursor.use {
                if (!it.moveToFirst()) return false
                fun string(column: String): String? =
                    it.getColumnIndex(column).takeIf { index -> index >= 0 }?.let(it::getString)
                fun long(column: String): Long =
                    it.getColumnIndex(column).takeIf { index -> index >= 0 }?.let(it::getLong) ?: -1L
                val title = string(MediaStore.Audio.Media.TITLE)
                val displayName = string(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePath = string(MediaStore.Audio.Media.RELATIVE_PATH)
                val recoveryRelativePath = pendingMoveDestinationRelativePath(
                    operation.targetRelativePath,
                    item.operationId,
                    item.oldSongId,
                )
                long(MediaStore.Audio.Media._ID) == item.newSongId &&
                    long(MediaStore.Audio.Media.IS_PENDING) == 1L &&
                    (displayName.equals(item.displayName, ignoreCase = true) ||
                        displayName?.startsWith(
                            pendingMoveDestinationDisplayName(
                                item.operationId,
                                item.oldSongId,
                            ),
                        ) == true) &&
                    (relativePath.equals(operation.targetRelativePath, ignoreCase = true) ||
                        relativePath.equals(recoveryRelativePath, ignoreCase = true)) &&
                    string(MediaStore.MediaColumns.VOLUME_NAME)
                        .equals(PRIMARY_EXTERNAL_MEDIA_VOLUME, ignoreCase = true) &&
                    (title == pendingMoveDestinationMarker(item.operationId, item.oldSongId) ||
                        title == source.title ||
                        relativePath.equals(recoveryRelativePath, ignoreCase = true) ||
                        displayName?.startsWith(
                            pendingMoveDestinationDisplayName(
                                item.operationId,
                                item.oldSongId,
                            ),
                        ) == true)
            }
        }.getOrDefault(false)
    }

    private suspend fun deleteSourceAndCommit(item: MoveItemRecord) {
        val cancellationContext = currentCoroutineContext()
        val sourceUri = concreteSourceUri(item)
        when (
            pendingDeletionIntegrityResult(
                // The destination is an app-owned IS_PENDING row. Android keeps it hidden from
                // other apps, so validate it first and make the source hash the final long read
                // immediately before deleting the concrete provider URI.
                destinationMatches = {
                    contentCopyMatchesJournal(
                        item.destinationUri?.toUri(),
                        item,
                        cancellationContext,
                    )
                },
                sourceMatches = {
                    contentCopyMatchesJournal(sourceUri, item, cancellationContext)
                },
            )
        ) {
            DeletionIntegrityResult.SOURCE_CHANGED ->
                throw IOException("原歌曲在授权等待期间发生变化，未执行删除")
            DeletionIntegrityResult.DESTINATION_CHANGED ->
                throw IOException("目标歌曲在授权等待期间发生变化，原文件未删除")
            DeletionIntegrityResult.VERIFIED -> Unit
        }
        throwIfCancellationRequested(cancellationContext)
        cancellationContext.ensureActive()
        val deleted = resolver.delete(sourceUri, null, null)
        check(deleted == 0 || deleted == 1) { "系统返回了异常的歌曲删除数量" }
        check(sourceAvailability(sourceUri) == SourceAvailability.MISSING) {
            "系统未能确认原歌曲文件已删除"
        }
        val sourceDeleted = item.copy(status = MoveItemStatus.SOURCE_DELETED)
        journal.updateItem(sourceDeleted)
        check(
            contentCopyMatchesJournal(
                item.destinationUri?.toUri(),
                sourceDeleted,
                cancellationContext,
            )
        ) {
            "原歌曲已删除，但目标歌曲随后发生变化；恢复日志已保留"
        }
        commitRemap(sourceDeleted)
    }

    @Suppress("DEPRECATION")
    private suspend fun commitRemap(
        item: MoveItemRecord,
        expectedLegacySourcePath: String? = null,
    ) {
        val cancellationContext = currentCoroutineContext()
        var resolvedItem = item
        val newSongId = item.newSongId ?: run {
            val destinationUri = item.destinationUri?.toUri()
                ?: throw IOException("目标歌曲记录不完整")
            if (destinationUri.scheme == "file") {
                val file = destinationUri.path?.let(::File)
                    ?: throw IOException("目标歌曲路径无效")
                check(file.isFile && file.canRead()) { "目标歌曲文件不存在或不可读" }
                check(destinationMatchesJournal(resolvedItem, cancellationContext)) {
                    "目标歌曲文件校验失败，关联信息尚未提交"
                }
                // Preserve the legacy MediaStore ID only in the uninterrupted call that still
                // knows the exact pre-move DATA path. Recovery has no durable original path, so
                // it scans/adopts the target instead of guessing that a possibly reused bare ID
                // is still our stale source row. The provider-side predicate also closes the
                // query/update race for ordinary row changes.
                val updated = expectedLegacySourcePath
                    ?.takeIf { !File(it).exists() }
                    ?.let { oldPath ->
                        resolver.update(
                            concreteSourceUri(item),
                            ContentValues().apply {
                                put(MediaStore.Audio.Media.DATA, file.absolutePath)
                            },
                            "${MediaStore.Audio.Media._ID} = ? AND " +
                                "${MediaStore.Audio.Media.DATA} = ? AND " +
                                "${MediaStore.Audio.Media.SIZE} = ?",
                            arrayOf(
                                item.oldSongId.toString(),
                                oldPath,
                                item.sourceSize.toString(),
                            ),
                        )
                    } ?: 0
                if (updated == 1 &&
                    legacyMediaRowMatchesPath(concreteSourceUri(item), file.absolutePath)
                ) {
                    deleteLegacyTemporary(item)
                    deleteLegacyOwnershipMarker(item)
                    journal.updateItem(
                        item.copy(
                            status = MoveItemStatus.COMMITTED,
                            newSongId = item.oldSongId,
                            error = null,
                        )
                    )
                    return
                }
                resolvedItem = resolveLegacyMediaRowAfterSourceDeletion(item, file)
                resolvedItem.newSongId!!
            } else {
                ContentUris.parseId(destinationUri)
            }
        }
        ensureConcreteDestinationIdentity(resolvedItem, newSongId)
        resolvedItem.destinationUri?.toUri()?.takeIf { it.scheme == "content" }?.let { destination ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(destinationMatchesJournal(resolvedItem, cancellationContext)) {
                    "目标歌曲文件不存在或校验失败，关联信息尚未提交"
                }
                val targetRelativePath = orderedPendingOperations()
                    .firstOrNull { it.id == resolvedItem.operationId }
                    ?.targetRelativePath
                    ?: throw IOException("无法确定目标歌曲目录，关联信息尚未提交")
                val published = resolver.update(
                    destination,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, targetRelativePath)
                        put(MediaStore.Audio.Media.DISPLAY_NAME, resolvedItem.displayName)
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
                check(mediaStorePublishSucceeded(published)) {
                    "系统未能发布目标歌曲，关联信息尚未提交"
                }
                check(modernPublishedDestinationMatches(resolvedItem, targetRelativePath)) {
                    "目标歌曲发布后路径、名称或内容校验失败，关联信息尚未提交"
                }
            }
        }
        if (newSongId != item.oldSongId) {
            playlists.remapSongIds(mapOf(item.oldSongId to newSongId))
            metadata.remap(item.oldSongId, newSongId)
            lyrics.remap(item.oldSongId, newSongId)
        }
        deleteLegacyTemporary(item)
        deleteLegacyOwnershipMarker(item)
        journal.updateItem(
            resolvedItem.copy(
                status = MoveItemStatus.COMMITTED,
                newSongId = newSongId,
                error = null,
            )
        )
    }

    private fun ensureConcreteDestinationIdentity(item: MoveItemRecord, newSongId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val destination = item.destinationUri?.toUri()
            ?: throw IOException("目标歌曲记录不完整")
        val candidates = concreteSourcesForId(newSongId)
            ?: throw IOException("无法完整查询所有存储卷，尚未重映射歌曲关联")
        check(
            concreteDestinationIdentityIsUnique(
                destinationUri = destination.toString(),
                candidateUris = candidates.map { it.uri.toString() },
            )
        ) {
            "目标歌曲 ID 与其他存储卷冲突，尚未重映射歌曲关联"
        }
    }

    private suspend fun repairInterruptedItems(operation: MoveOperationRecord) {
        journal.items(operation.id).forEach { item ->
            if (item.status == MoveItemStatus.PREPARED && item.destinationUri == null) {
                val source = querySource(item)
                if (source != null && sourceIsAlreadyInTarget(source, operation.targetRelativePath) &&
                    source.displayName.equals(item.displayName, ignoreCase = true)
                ) {
                    // MediaStore may have committed the atomic RELATIVE_PATH update immediately
                    // before the process died. The stable ID makes this safe to recognize.
                    journal.updateItem(
                        item.copy(
                            status = MoveItemStatus.COMMITTED,
                            newSongId = item.oldSongId,
                            error = null,
                        )
                    )
                    return@forEach
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cleanupUnjournaledPendingDestinations(operation, item)
                }
                return@forEach
            }
            if (item.status == MoveItemStatus.PREPARED && item.destinationUri != null) {
                val destination = item.destinationUri.toUri()
                val availability = sourceAvailability(item)
                if (destination.scheme == "file") {
                    if (availability == SourceAvailability.INACCESSIBLE) {
                        retainDestinationForSafety(
                            item,
                            "无法确认原歌曲是否仍存在；目标与临时文件均已保留以便恢复",
                        )
                        return@forEach
                    }
                    repairLegacyPreparedItem(item, availability)
                    return@forEach
                }
                repairPendingPreparedItem(operation, item, availability)
                return@forEach
            }
            when (item.status) {
                MoveItemStatus.COPIED -> repairCopiedItem(item)
                MoveItemStatus.CANCELLING -> if (
                    restoreLegacyQuarantineForCancellation(item)
                ) {
                    cancelItemWithoutDestructiveRollback(
                        item,
                        item.error ?: "用户取消",
                    )
                }
                MoveItemStatus.FAILED -> if (item.destinationUri != null) {
                    resolveInterruptedDestination(
                        item = item,
                        statusAfterCleanup = MoveItemStatus.FAILED,
                        messageAfterCleanup = item.error,
                    )
                }
                MoveItemStatus.SOURCE_DELETED -> Unit
                else -> Unit
            }
        }
    }

    private suspend fun repairLegacyPreparedItem(
        item: MoveItemRecord,
        sourceState: SourceAvailability,
    ) {
        val cancellationContext = currentCoroutineContext()
        if (legacyMediaRowPointsAtDestination(item)) {
            if (destinationMatchesJournal(item, cancellationContext)) {
                journal.updateItem(
                    item.copy(
                        status = MoveItemStatus.COMMITTED,
                        newSongId = item.oldSongId,
                        error = null,
                    )
                )
                deleteLegacyTemporary(item)
                deleteLegacyOwnershipMarker(item)
            } else {
                retainDestinationForSafety(
                    item,
                    "媒体库已指向目标路径，但文件无法通过校验；未删除任何文件",
                )
            }
            return
        }
        if (sourceState == SourceAvailability.INACCESSIBLE) {
            retainDestinationForSafety(
                item,
                "无法确认原歌曲是否仍存在；目标与临时文件均已保留以便恢复",
            )
            return
        }
        var destinationVerified = destinationMatchesJournal(item, cancellationContext)
        val temporaryVerified = legacyTemporary(item)?.let {
            fileMatchesJournal(it, item, cancellationContext)
        } == true
        if (!destinationVerified && temporaryVerified) {
            destinationVerified = installLegacyDestinationFromVerifiedTemporary(
                item,
                cancellationContext,
            )
        }
        when {
            sourceState == SourceAvailability.MISSING && destinationVerified ->
                journal.updateItem(item.copy(status = MoveItemStatus.SOURCE_DELETED, error = null))
            sourceState == SourceAvailability.MISSING -> retainDestinationForSafety(
                item,
                "原歌曲已不可见，且目标/临时副本无法通过校验；所有恢复信息已保留",
            )
            destinationVerified && temporaryVerified ->
                // A crash after installing the target but before persisting COPIED is safe to
                // continue: the verified temporary remains available through source quarantine,
                // delete, target post-verification and metadata commit.
                journal.updateItem(item.copy(status = MoveItemStatus.COPIED, error = null))
            destinationVerified -> retainDestinationForSafety(
                item,
                "目标歌曲完整，但本次操作的安全临时副本缺失；未删除原文件",
            )
            else -> resolveInterruptedDestination(
                item = item,
                statusAfterCleanup = MoveItemStatus.PREPARED,
                messageAfterCleanup = null,
            )
        }
    }

    private suspend fun repairPendingPreparedItem(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
        sourceState: SourceAvailability,
    ) {
        val cancellationContext = currentCoroutineContext()
        when (sourceState) {
            SourceAvailability.PRESENT -> {
                val source = querySource(item)
                if (source != null && journaledPendingDestinationMatches(operation, item, source)) {
                    // Leave PREPARED intact. execute() reopens only this exact app-owned pending
                    // row, recopies from the concrete source URI, verifies it and proceeds.
                    return
                }
                val destination = requireNotNull(item.destinationUri).toUri()
                if (sourceAvailability(destination) == SourceAvailability.MISSING) {
                    journal.updateItem(
                        item.copy(
                            status = MoveItemStatus.PREPARED,
                            newSongId = null,
                            destinationUri = null,
                            checksum = null,
                            error = null,
                        )
                    )
                } else {
                    retainDestinationForSafety(
                        item,
                        "无法确认崩溃前的 pending 目标记录；未覆盖或删除任何内容",
                    )
                }
            }
            SourceAvailability.MISSING -> if (
                destinationMatchesJournal(item, cancellationContext)
            ) {
                journal.updateItem(item.copy(status = MoveItemStatus.SOURCE_DELETED, error = null))
            } else {
                retainDestinationForSafety(
                    item,
                    "原歌曲已不可见，且 pending 目标尚未完成校验；恢复记录已保留",
                )
            }
            SourceAvailability.INACCESSIBLE -> retainDestinationForSafety(
                item,
                "无法确认原歌曲状态；pending 目标与恢复记录已保留",
            )
        }
    }

    private suspend fun repairCopiedItem(item: MoveItemRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // The source may already be in the operation-specific quarantine. The legacy delete
            // routine owns the only safe interpretation of source/quarantine/temp and resumes it.
            return
        }
        val destinationVerified = destinationMatchesJournal(item, currentCoroutineContext())
        when (sourceAvailability(item)) {
            SourceAvailability.PRESENT -> if (!destinationVerified) {
                retainDestinationForSafety(
                    item,
                    "原歌曲仍存在，但已复制的目标无法通过校验；未请求删除授权，恢复信息已保留",
                )
            }
            SourceAvailability.MISSING -> if (destinationVerified) {
                journal.updateItem(item.copy(status = MoveItemStatus.SOURCE_DELETED, error = null))
            } else {
                retainDestinationForSafety(
                    item,
                    "原歌曲已不可见，且目标无法通过校验；恢复记录已保留",
                )
            }
            SourceAvailability.INACCESSIBLE -> retainDestinationForSafety(
                item,
                "无法确认原歌曲状态；已验证目标与恢复记录已保留",
            )
        }
        // PRESENT + verified deliberately remains COPIED. execute() will recreate the platform
        // delete request after process death instead of converting a normal wait into FAILED.
    }

    private suspend fun finishCancellation(
        operationId: String,
        onState: (MediaOperationState) -> Unit,
    ): MediaOperationState {
        // Persist intent before publishing/retaining any complete pending target. A process death
        // after publication must resume cancellation, never return to MOVING and delete a source
        // the user had already asked us to keep.
        withContext(NonCancellable + Dispatchers.IO) {
            journal.updateOperation(operationId, MoveOperationStatus.CANCELLING)
        }
        journal.items(operationId).forEach { item ->
            when (item.status) {
                MoveItemStatus.COPIED -> if (restoreLegacyQuarantineForCancellation(item)) {
                    cancelItemWithoutDestructiveRollback(item, "用户取消")
                }
                MoveItemStatus.CANCELLING -> if (
                    restoreLegacyQuarantineForCancellation(item)
                ) {
                    cancelItemWithoutDestructiveRollback(item, item.error ?: "用户取消")
                }
                MoveItemStatus.PREPARED -> if (item.destinationUri == null) {
                    journal.updateItem(item.copy(status = MoveItemStatus.CANCELLED, error = "用户取消"))
                } else {
                    cancelItemWithoutDestructiveRollback(item, "用户取消")
                }
                MoveItemStatus.FAILED -> if (
                    item.destinationUri != null && restoreLegacyQuarantineForCancellation(item)
                ) {
                    cancelItemWithoutDestructiveRollback(item, "用户取消")
                }
                else -> Unit
            }
        }
        // No further source deletion is allowed past this point. Clear the cooperative stop flag
        // only so already-SOURCE_DELETED items can finish their mandatory publish/remap commit.
        cancelRequested.set(false)
        var commitFailure: Throwable? = null
        journal.items(operationId)
            .filter { it.status == MoveItemStatus.SOURCE_DELETED }
            .forEach { item ->
                try {
                    commitRemap(item)
                } catch (error: Throwable) {
                    commitFailure = commitFailure ?: error
                    val latest = journal.items(operationId)
                        .firstOrNull { it.oldSongId == item.oldSongId } ?: item
                    journal.updateItem(
                        latest.copy(status = MoveItemStatus.SOURCE_DELETED, error = error.safeMessage())
                    )
                }
            }
        val finalCommitFailure = commitFailure
        if (finalCommitFailure != null) {
            journal.updateOperation(operationId, MoveOperationStatus.COMMITTING)
            cancelRequested.set(false)
            val failed = MediaOperationState.Failed(
                "已停止继续移动，但有歌曲已删除原文件，关联信息将在下次打开时恢复：" +
                    finalCommitFailure.safeMessage()
            )
            onState(failed)
            return failed
        }
        if (journal.items(operationId).any {
                it.status == MoveItemStatus.FAILED && it.destinationUri != null
            }
        ) {
            journal.updateOperation(operationId, MoveOperationStatus.COMMITTING)
            cancelRequested.set(false)
            val failed = MediaOperationState.Failed(
                "已停止继续移动，但未能安全清理本次创建的目标；" +
                    "恢复记录已保留，可稍后重试终止"
            )
            onState(failed)
            return failed
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

    private suspend fun cancelItemWithoutDestructiveRollback(
        item: MoveItemRecord,
        reason: String,
    ) {
        if (item.destinationUri == null) {
            journal.updateItem(item.copy(status = MoveItemStatus.CANCELLED, error = reason))
            return
        }
        val cancelling = item.copy(status = MoveItemStatus.CANCELLING, error = reason)
        // A permission denial may cancel only this song while the larger operation keeps moving.
        // Persist the per-item intent before a verified pending target can be published, so a
        // crash never turns the denied item back into COPIED and later deletes its source.
        withContext(NonCancellable + Dispatchers.IO) {
            journal.updateItem(cancelling)
        }
        resolveInterruptedDestination(
            item = cancelling,
            statusAfterCleanup = MoveItemStatus.CANCELLED,
            messageAfterCleanup = reason,
        )
    }

    private suspend fun restoreLegacyQuarantineForCancellation(item: MoveItemRecord): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val sourcePath = querySource(item)?.absolutePath
        if (sourcePath.isNullOrBlank()) {
            retainDestinationForSafety(
                item,
                "取消时无法确定旧版原歌曲路径；未清理目标或恢复记录，以免遗漏隔离源",
            )
            return false
        }
        val sourceFile = File(sourcePath)
        val quarantine = legacySourceQuarantine(item, sourceFile)
        if (!quarantine.exists()) return true
        if (!fileMatchesJournal(quarantine, item)) {
            retainDestinationForSafety(
                item,
                "取消时无法验证隔离源；未删除隔离源、目标或临时副本，恢复记录已保留",
            )
            return false
        }
        if (!restoreLegacyQuarantineNoReplace(quarantine, sourceFile)) {
            retainDestinationForSafety(
                item,
                "取消时原路径已被占用或无法恢复；隔离源、目标和恢复记录均已保留",
            )
            return false
        }
        return true
    }

    private suspend fun finish(
        operationId: String,
        onState: (MediaOperationState) -> Unit,
    ): RelocationStep {
        val items = journal.items(operationId)
        val unfinished = items.any {
            it.status == MoveItemStatus.PREPARED ||
                it.status == MoveItemStatus.COPIED ||
                it.status == MoveItemStatus.CANCELLING ||
                it.status == MoveItemStatus.SOURCE_DELETED
        }
        if (unfinished) return execute(operationId, onState)
        if (items.any { it.status == MoveItemStatus.FAILED && it.destinationUri != null }) {
            journal.updateOperation(operationId, MoveOperationStatus.COMMITTING)
            val failed = MediaOperationState.Failed(
                "有目标文件暂时无法安全清理；目标 URI 与校验值已保留，稍后可恢复"
            )
            onState(failed)
            return RelocationStep.Finished(failed)
        }
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
        if (latest.status == MoveItemStatus.SOURCE_DELETED) {
            journal.updateItem(
                latest.copy(
                    error = error.safeMessage(),
                )
            )
            return
        }
        if (latest.status == MoveItemStatus.FAILED && latest.destinationUri != null) {
            // A previous cleanup attempt already failed and deliberately retained its URI/hash.
            // Never let the outer error handler erase the only diagnostic/recovery reference.
            return
        }
        if (latest.destinationUri != null) {
            resolveInterruptedDestination(
                item = latest,
                statusAfterCleanup = if (cancelRequested.get()) {
                    MoveItemStatus.CANCELLED
                } else {
                    MoveItemStatus.FAILED
                },
                messageAfterCleanup = if (cancelRequested.get()) "用户取消" else error.safeMessage(),
            )
            return
        }
        journal.updateItem(
            latest.copy(
                status = if (cancelRequested.get()) MoveItemStatus.CANCELLED else MoveItemStatus.FAILED,
                newSongId = null,
                destinationUri = null,
                checksum = null,
                error = if (cancelRequested.get()) "用户取消" else error.safeMessage(),
            )
        )
    }

    private suspend fun cleanupDestination(item: MoveItemRecord) {
        val uri = item.destinationUri?.toUri() ?: return
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: throw IOException("目标歌曲路径无效")
            if (!file.exists()) {
                deleteLegacyOwnershipMarker(item)
                return
            }
            if (!legacyDestinationOwnedAndVerified(item)) {
                throw IOException("目标文件归属或校验不匹配，未执行清理")
            }
            if (!file.delete()) throw IOException("无法清理目标歌曲文件")
            deleteLegacyOwnershipMarker(item)
            return
        }
        val targetRelativePath = orderedPendingOperations()
            .firstOrNull { it.id == item.operationId }
            ?.targetRelativePath
        if (!deleteModernPendingDestination(
                item = item,
                expectedRelativePath = targetRelativePath,
                requireRecoveryMarker = true,
            )
        ) {
            throw IOException("无法清理目标歌曲记录")
        }
    }

    private suspend fun cleanupPreparedArtifacts(item: MoveItemRecord): DestinationCleanupOutcome {
        // A checksum-verified destination is a complete safety copy. Deleting it after a
        // non-atomic source-presence check creates an unavoidable window in which the source can
        // disappear and both copies are lost. A modern hidden row is therefore conditionally
        // published, never rolled back; a legacy target is already visible and is retained.
        if (destinationMatchesJournal(item)) {
            return when (sourceAvailability(item)) {
                SourceAvailability.PRESENT,
                SourceAvailability.INACCESSIBLE -> preserveVerifiedDestination(item)
                SourceAvailability.MISSING -> DestinationCleanupOutcome.SOURCE_MISSING
            }
        }
        val destination = item.destinationUri?.toUri()
        if (item.checksum != null && destination != null &&
            sourceAvailability(destination) != SourceAvailability.MISSING
        ) {
            // A checksum means this journal reached (or, for legacy, intended) a verified-copy
            // state. If the artifact is now unreadable or changed, that is not permission to erase
            // it: a transient read failure can otherwise turn a good last copy into a deletion.
            return when (sourceAvailability(item)) {
                SourceAvailability.PRESENT -> if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    destination.scheme == "content"
                ) {
                    // A changed checksum does not revoke ownership of an exact, still-pending
                    // MediaStore row created and journaled by this operation. The provider-side
                    // path/name/pending predicate can clean it safely. Published or changed rows
                    // fail that predicate and remain retained.
                    cleanupDestinationIfSourcePreserved(item)
                } else {
                    DestinationCleanupOutcome.DESTINATION_RETAINED
                }
                SourceAvailability.MISSING -> DestinationCleanupOutcome.SOURCE_MISSING
                SourceAvailability.INACCESSIBLE -> DestinationCleanupOutcome.SOURCE_INACCESSIBLE
            }
        }
        return when (sourceAvailability(item)) {
            SourceAvailability.PRESENT -> {
                val outcome = cleanupDestinationIfSourcePreserved(item)
                if (outcome != DestinationCleanupOutcome.REMOVED) return outcome
                // The target has been cleaned while the source was confirmed present. Check once
                // more before deleting the exact operation temporary; it may now be the only
                // recoverable complete copy if the source disappeared concurrently.
                when (sourceAvailability(item)) {
                    SourceAvailability.PRESENT -> {
                        deleteLegacyTemporary(item)
                        DestinationCleanupOutcome.REMOVED
                    }
                    SourceAvailability.MISSING -> {
                        installLegacyDestinationFromVerifiedTemporary(item)
                        DestinationCleanupOutcome.SOURCE_MISSING
                    }
                    SourceAvailability.INACCESSIBLE ->
                        DestinationCleanupOutcome.SOURCE_INACCESSIBLE
                }
            }
            SourceAvailability.MISSING -> {
                // Never discard the operation temporary when the source is gone. If it is a full
                // checksum match, use it to reconstruct the no-replace final target; otherwise
                // retain every artifact and the journal for later/manual recovery.
                if (!destinationMatchesJournal(item)) {
                    installLegacyDestinationFromVerifiedTemporary(item)
                }
                DestinationCleanupOutcome.SOURCE_MISSING
            }
            SourceAvailability.INACCESSIBLE -> DestinationCleanupOutcome.SOURCE_INACCESSIBLE
        }
    }

    private suspend fun resolveInterruptedDestination(
        item: MoveItemRecord,
        statusAfterCleanup: MoveItemStatus,
        messageAfterCleanup: String?,
    ) {
        val outcome = try {
            cleanupPreparedArtifacts(item)
        } catch (cleanupError: Throwable) {
            retainDestinationForSafety(
                item,
                cleanupFailureMessage(
                    IOException(messageAfterCleanup ?: "移动未完成"),
                    cleanupError,
                ),
            )
            return
        }
        val destinationVerified = destinationMatchesJournal(item)
        when (outcome) {
            DestinationCleanupOutcome.REMOVED -> journal.updateItem(
                item.copy(
                    status = statusAfterCleanup,
                    newSongId = null,
                    destinationUri = null,
                    checksum = null,
                    error = messageAfterCleanup,
                )
            )
            DestinationCleanupOutcome.SOURCE_MISSING,
            DestinationCleanupOutcome.DESTINATION_IS_INDEXED_SOURCE -> if (destinationVerified) {
                // The verified destination is now the only known copy. Keep the durable URI/hash
                // and let execute() publish/remap it even if the user denied an obsolete request.
                journal.updateItem(item.copy(status = MoveItemStatus.SOURCE_DELETED, error = null))
            } else {
                retainDestinationForSafety(
                    item,
                    "原歌曲已不可见且目标歌曲无法通过校验；目标记录已保留以便人工恢复",
                )
            }
            DestinationCleanupOutcome.SOURCE_INACCESSIBLE -> retainDestinationForSafety(
                item,
                "无法确认原歌曲是否仍存在；未删除目标歌曲，恢复信息已保留",
            )
            DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED -> if (
                statusAfterCleanup == MoveItemStatus.CANCELLED
            ) {
                // A complete destination that is already public (or a legacy filesystem target)
                // is not a hidden half-file and needs no mandatory cleanup. Keep its exact URI and
                // checksum for diagnostics, but allow the user to terminate without deleting it.
                try {
                    if (item.destinationUri?.toUri()?.scheme == "file") {
                        deleteLegacyTemporary(item)
                        deleteLegacyOwnershipMarker(item)
                    }
                    journal.updateItem(
                        item.copy(
                            status = MoveItemStatus.CANCELLED,
                            error = listOfNotNull(
                                messageAfterCleanup,
                                "已发布的完整目标文件予以保留",
                            ).joinToString("；"),
                        )
                    )
                } catch (cleanupError: Throwable) {
                    retainDestinationForSafety(
                        item,
                        "已保留完整目标，但无法清理本次操作的临时文件：" +
                            cleanupError.safeMessage(),
                    )
                }
            } else {
                retainDestinationForSafety(
                    item,
                    "目标歌曲已完整发布并予以保留；可使用“重试安全终止”结束恢复记录",
                )
            }
            DestinationCleanupOutcome.DESTINATION_RETAINED -> retainDestinationForSafety(
                item,
                listOfNotNull(
                    messageAfterCleanup,
                    "目标记录仍存在；为避免原歌曲并发消失或覆盖外部替换文件，未执行破坏性回滚，恢复信息已保留",
                ).joinToString("；"),
            )
        }
    }

    private suspend fun retainDestinationForSafety(item: MoveItemRecord, message: String) {
        journal.updateItem(
            item.copy(
                status = MoveItemStatus.FAILED,
                error = message,
            )
        )
    }

    private fun cleanupFailureMessage(original: Throwable, cleanup: Throwable): String =
        "${original.safeMessage()}；${cleanup.safeMessage()}，目标 URI 与校验值已保留"

    private fun querySource(songId: Long): SongSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return querySourceResult(songId, songUri(songId)).valueOrNull()
        }
        return concreteSourcesForId(songId)?.singleOrNull()
    }

    private fun querySource(item: MoveItemRecord): SongSource? =
        concreteSourceUriOrNull(item)
            ?.let { querySourceResult(item.oldSongId, it).valueOrNull() }

    private suspend fun upgradeSyntheticSourceUris(operationId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Android 10 has no recent-volume API. Once a removable volume is physically detached,
        // neither a legacy merged URI nor a bare playlist ID can prove which volume owned it.
        // Keep the journal untouched rather than ever adopting a coincidentally matching row.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) return
        journal.items(operationId).forEach { item ->
            val storedUri = item.sourceUri.toUri()
            if (!isSyntheticExternalMediaUri(storedUri.toString())) return@forEach
            val discovered = discoverConcreteSourceForLegacyJournal(item) ?: return@forEach
            // Persist the volume-qualified URI before any consent request, mutation, copy or
            // delete. The merged /external collection is read-only and can select the wrong row
            // when removable volumes reuse numeric MediaStore IDs.
            journal.updateItem(item.copy(sourceUri = discovered.uri.toString()))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun discoverConcreteSourceForLegacyJournal(item: MoveItemRecord): SongSource? {
        val destination = item.destinationUri?.toUri()
        val candidates = concreteSourcesForId(item.oldSongId)
            ?: return null
        val qualifiedCandidates = candidates
            .filterNot { candidate -> candidate.uri == destination }
            .filter { candidate -> item.sourceSize <= 0L || candidate.size == item.sourceSize }
            .let { sizeMatched ->
                if (item.checksum != null) {
                    sizeMatched.filter { candidate ->
                        contentCopyMatchesJournal(candidate.uri, item)
                    }
                } else {
                    sizeMatched
                }
            }
        // A bare legacy ID cannot distinguish equal rows on two volumes. Even a matching digest
        // proves byte equality, not which physical file the user put in the playlist, so ambiguity
        // must remain unresolved rather than being "broken" by cursor order or filename.
        return qualifiedCandidates.singleOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun concreteSourcesForId(songId: Long): List<SongSource>? {
        val mountedBefore = mountedExternalVolumeNames() ?: return null
        if (!allKnownExternalVolumesAreMounted(mountedBefore)) return null
        val results = mountedBefore.map { volume ->
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.getContentUri(volume),
                    songId,
                )
                querySourceResult(songId, uri)
            }
        val candidates = completeMediaRowQuery(results) ?: return null
        // A detach/remount while the per-volume queries are running invalidates the completeness
        // proof. Never accept whichever row happened to remain visible during that race.
        val mountedAfter = mountedExternalVolumeNames() ?: return null
        if (mountedAfter != mountedBefore || !allKnownExternalVolumesAreMounted(mountedAfter)) {
            return null
        }
        return candidates
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mountedExternalVolumeNames(): Set<String>? = runCatching {
        mediaAccess.externalVolumeNames(appContext)
            .filterNotTo(linkedSetOf()) { it.equals("external", ignoreCase = true) }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun allKnownExternalVolumesAreMounted(mounted: Set<String>): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val recent = runCatching { mediaAccess.recentExternalVolumeNames(appContext) }
                .getOrElse { return false }
                .filterNot { it.equals("external", ignoreCase = true) }
            if (recent.any { it !in mounted }) return false
        } else {
            val storageManager = appContext.getSystemService(StorageManager::class.java)
                ?: return false
            val hasUnavailableSecondary = storageManager.storageVolumes.any { volume ->
                !volume.isPrimary &&
                    volume.state != Environment.MEDIA_MOUNTED &&
                    volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
            }
            if (hasUnavailableSecondary) return false
        }
        return mounted.isNotEmpty()
    }

    private fun concreteSourceUriOrNull(item: MoveItemRecord): Uri? {
        val uri = item.sourceUri.toUri()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isSyntheticExternalMediaUri(uri.toString())
        ) {
            return null
        }
        return uri
    }

    private fun concreteSourceUri(item: MoveItemRecord): Uri = concreteSourceUriOrNull(item)
        ?: throw IOException("无法确认歌曲所在的具体存储卷，未执行文件操作")

    private fun sourceResolutionError(songId: Long): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val candidates = concreteSourcesForId(songId)
            if (candidates != null && candidates.size > 1) {
                return "存储卷歌曲 ID 冲突，未移动该歌曲"
            }
        }
        return "歌曲文件不存在、存储卷未挂载、查询不可用或来源无法唯一确认"
    }

    private fun querySourceResult(
        songId: Long,
        queryUri: Uri,
    ): MediaRowQueryResult<SongSource> {
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
        return try {
            val cursor = mediaAccess.query(resolver, queryUri, projection)
                ?: return MediaRowQueryResult.Inaccessible
            cursor.use {
                if (!it.moveToFirst()) return MediaRowQueryResult.Absent
                fun string(column: String): String? =
                    it.getColumnIndex(column).takeIf { index -> index >= 0 }?.let(it::getString)
                fun long(column: String): Long =
                    it.getColumnIndex(column).takeIf { index -> index >= 0 }?.let(it::getLong) ?: 0L
                val reportedVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    string(MediaStore.MediaColumns.VOLUME_NAME)
                } else null
                val queryVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryUri.pathSegments.firstOrNull()
                } else null
                if (!reportedVolume.isNullOrBlank() &&
                    !queryVolume.isNullOrBlank() &&
                    !reportedVolume.equals(queryVolume, ignoreCase = true)
                ) {
                    return MediaRowQueryResult.Inaccessible
                }
                val volume = queryVolume ?: reportedVolume
                @Suppress("DEPRECATION")
                MediaRowQueryResult.Found(
                    SongSource(
                        uri = queryUri,
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
                )
            }
        } catch (_: Throwable) {
            MediaRowQueryResult.Inaccessible
        }
    }

    private fun queryTargetNames(relativePath: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                relativePath.removePrefix("${Environment.DIRECTORY_MUSIC}/"),
            )
            if (!directory.exists()) return emptyList()
            return directory.list()?.toList()
                ?: throw IOException("无法读取目标文件夹，未开始移动")
        }
        val names = mutableListOf<String>()
        val cursor = resolver.query(
            MediaStore.Audio.Media.getContentUri(PRIMARY_EXTERNAL_MEDIA_VOLUME),
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        ) ?: throw IOException("无法读取目标媒体目录，未开始移动")
        cursor.use { result ->
            while (result.moveToNext()) result.getString(0)?.let(names::add)
        }
        return names
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

    private fun sourceAvailability(item: MoveItemRecord): SourceAvailability =
        concreteSourceUriOrNull(item)?.let(::sourceAvailability) ?: SourceAvailability.INACCESSIBLE

    @Suppress("DEPRECATION")
    private fun includePendingMedia(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.setIncludePending(uri)
        } else {
            uri
        }

    private fun sourceAvailability(uri: Uri): SourceAvailability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isSyntheticExternalMediaUri(uri.toString())
        ) {
            return SourceAvailability.INACCESSIBLE
        }
        if (uri.scheme == "file") {
            return if (uri.path?.let(::File)?.isFile == true) {
                SourceAvailability.PRESENT
            } else {
                SourceAvailability.MISSING
            }
        }
        return try {
            val cursor = mediaAccess.query(
                resolver,
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
            ) ?: return SourceAvailability.INACCESSIBLE
            val rowExists = cursor.use { it.moveToFirst() }
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

    private fun deletionIsConfirmed(deletedRows: Int, uri: Uri): Boolean = when (deletedRows) {
        1 -> true
        0 -> sourceAvailability(uri) == SourceAvailability.MISSING
        else -> false
    }

    private fun preparedDestinationIsComplete(
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        val destination = item.destinationUri?.toUri() ?: return false
        if (destination.scheme != "file") return false
        val file = destination.path?.let(::File) ?: return false
        return fileMatchesJournal(file, item, cancellationContext)
    }

    private fun fileMatchesJournal(
        file: File,
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        val expectedHash = item.checksum ?: return false
        if (!file.isFile || file.length() <= 0L) return false
        if (item.sourceSize > 0L && file.length() != item.sourceSize) return false
        return try {
            digestFile(file, cancellationContext).toHex() == expectedHash
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private fun legacyDestinationOwnedAndVerified(
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: return false
        return legacyOwnershipMarkerMatches(item) &&
            fileMatchesJournal(destination, item, cancellationContext)
    }

    private fun legacyOwnershipMarkerMatches(item: MoveItemRecord): Boolean {
        val marker = legacyOwnershipMarker(item) ?: return false
        if (!marker.isFile || marker.length() !in 1..512) return false
        val expected = legacyOwnershipToken(item) ?: return false
        return runCatching { marker.readText() == expected }.getOrDefault(false)
    }

    private fun legacyOwnershipMarker(item: MoveItemRecord): File? {
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: return null
        return destination.parentFile
            ?.resolve(".${item.operationId}-${item.oldSongId}.yinlan-owned")
    }

    private fun legacyOwnershipToken(item: MoveItemRecord): String? = item.checksum?.let { checksum ->
        "${item.operationId}:${item.oldSongId}:$checksum"
    }

    private fun writeLegacyOwnershipMarker(marker: File?, item: MoveItemRecord) {
        val actualMarker = marker ?: throw IOException("无法创建目标文件归属标记")
        val bytes = legacyOwnershipToken(item)?.toByteArray(Charsets.UTF_8)
            ?: throw IOException("目标文件校验值尚未持久化")
        FileChannel.open(
            actualMarker.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /**
     * Creates the legacy final path without ever replacing a racing file. A verified operation
     * temporary is the source of truth. An existing target is never opened for writing: a sidecar
     * identifies the operation but cannot prove that the inode at that path was not replaced.
     */
    private fun installLegacyDestinationFromVerifiedTemporary(
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: return false
        val temporary = legacyTemporary(item) ?: return false
        if (!fileMatchesJournal(temporary, item, cancellationContext)) return false

        val targetAlreadyExists = destination.exists()
        // A sidecar names an operation but is not tied to an inode. After a crash another process
        // can replace the path while leaving the marker behind, so no existing path is ever opened
        // with TRUNCATE_EXISTING. A complete matching target may be adopted non-destructively;
        // every other existing target remains untouched for manual recovery/name reselection.
        if (targetAlreadyExists) {
            return legacyDestinationOwnedAndVerified(item, cancellationContext)
        }
        val staleMarker = legacyOwnershipMarker(item)
        if (staleMarker?.exists() == true) deleteLegacyOwnershipMarker(item)

        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        val options = arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        temporary.inputStream().use { input ->
            FileChannel.open(destination.toPath(), *options).use { channel ->
                // Persist ownership only after CREATE_NEW proved that this operation created
                // the path. A crash before the marker is written leaves an ambiguous target
                // that recovery conservatively retains rather than deleting.
                writeLegacyOwnershipMarker(legacyOwnershipMarker(item), item)
                copiedBytes = copyAndDigest(
                    input,
                    Channels.newOutputStream(channel),
                    digest,
                    cancellationContext,
                )
                channel.force(true)
            }
        }
        val expectedHash = item.checksum ?: return false
        return copiedBytes > 0L &&
            (item.sourceSize <= 0L || copiedBytes == item.sourceSize) &&
            digest.digest().toHex() == expectedHash &&
            legacyDestinationOwnedAndVerified(item, cancellationContext)
    }

    private fun deleteLegacyOwnershipMarker(item: MoveItemRecord) {
        val marker = legacyOwnershipMarker(item) ?: return
        if (!marker.exists()) return
        val expected = legacyOwnershipToken(item)
            ?: throw IOException("无法验证目标文件归属标记")
        val matches = marker.isFile && marker.length() in 1..512 &&
            runCatching { marker.readText() == expected }.getOrDefault(false)
        if (!matches) throw IOException("目标文件归属标记不匹配，未执行清理")
        if (!marker.delete()) throw IOException("无法清理目标文件归属标记")
    }

    private fun legacyTemporary(item: MoveItemRecord): File? {
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: return null
        return destination.parentFile
            ?.resolve(".${item.operationId}-${item.oldSongId}.yinlan-moving")
    }

    private fun legacySourceQuarantine(item: MoveItemRecord, sourceFile: File): File =
        sourceFile.parentFile?.resolve(
            ".${item.operationId}-${item.oldSongId}.yinlan-source-quarantine"
        ) ?: throw IOException("无法确定原歌曲隔离目录")

    private fun moveFileNoReplace(source: File, destination: File): Boolean {
        if (!source.isFile || destination.exists()) return false
        return try {
            // No REPLACE_EXISTING option is supplied. The operation-specific destination is in
            // the same directory, so the platform provider performs a rename while refusing an
            // already occupied quarantine path.
            Files.move(source.toPath(), destination.toPath())
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            false
        }
    }

    private fun restoreLegacyQuarantineNoReplace(quarantine: File, sourceFile: File): Boolean {
        if (!quarantine.isFile) return !sourceFile.exists()
        if (sourceFile.exists()) return false
        return try {
            Files.move(quarantine.toPath(), sourceFile.toPath())
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun deleteLegacyTemporary(item: MoveItemRecord) {
        val temporary = legacyTemporary(item) ?: return
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("无法清理本次移动遗留的临时文件")
        }
    }

    private fun destinationMatchesJournal(
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        val destination = item.destinationUri?.toUri() ?: return false
        return if (destination.scheme == "file") {
            preparedDestinationIsComplete(item, cancellationContext)
        } else {
            contentCopyMatchesJournal(destination, item, cancellationContext)
        }
    }

    private fun contentCopyMatchesJournal(
        uri: Uri?,
        item: MoveItemRecord,
        cancellationContext: CoroutineContext? = null,
    ): Boolean {
        if (uri == null || uri.scheme != "content") return false
        val expectedHash = item.checksum ?: return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            resolver.openInputStream(uri)?.use { input ->
                while (true) {
                    throwIfCancellationRequested(cancellationContext)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    byteCount += count
                }
            } ?: return false
            (item.sourceSize <= 0L || byteCount == item.sourceSize) &&
                digest.digest().toHex() == expectedHash
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyMediaRowPointsAtDestination(item: MoveItemRecord): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        val destination = item.destinationUri?.toUri()?.takeIf { it.scheme == "file" }
            ?.path?.let(::File) ?: return false
        if (!destination.isFile) return false
        val indexedPath = runCatching {
            resolver.query(
                concreteSourceUri(item),
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: return false
        val indexedFile = File(indexedPath)
        if (indexedFile.absolutePath == destination.absolutePath) return true
        return runCatching { indexedFile.canonicalFile == destination.canonicalFile }.getOrDefault(false)
    }

    private suspend fun preserveVerifiedDestination(
        item: MoveItemRecord,
    ): DestinationCleanupOutcome {
        val destination = item.destinationUri?.toUri()
            ?: return DestinationCleanupOutcome.DESTINATION_RETAINED
        if (destination.scheme == "file") {
            return DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || destination.scheme != "content") {
            return DestinationCleanupOutcome.DESTINATION_RETAINED
        }
        val operation = orderedPendingOperations().firstOrNull { it.id == item.operationId }
            ?: return DestinationCleanupOutcome.DESTINATION_RETAINED
        if (modernPublishedDestinationMatches(item, operation.targetRelativePath)) {
            return DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED
        }
        if (!publishModernVerifiedDestination(item, operation.targetRelativePath)) {
            return DestinationCleanupOutcome.DESTINATION_RETAINED
        }
        return if (modernPublishedDestinationMatches(item, operation.targetRelativePath)) {
            DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED
        } else {
            DestinationCleanupOutcome.DESTINATION_RETAINED
        }
    }

    private suspend fun cleanupDestinationIfSourcePreserved(
        item: MoveItemRecord,
    ): DestinationCleanupOutcome {
        if (legacyMediaRowPointsAtDestination(item)) {
            return DestinationCleanupOutcome.DESTINATION_IS_INDEXED_SOURCE
        }
        val destination = item.destinationUri?.toUri() ?: return DestinationCleanupOutcome.REMOVED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && destination.scheme == "content") {
            return when (sourceAvailability(item)) {
                SourceAvailability.MISSING -> DestinationCleanupOutcome.SOURCE_MISSING
                SourceAvailability.INACCESSIBLE -> DestinationCleanupOutcome.SOURCE_INACCESSIBLE
                SourceAvailability.PRESENT -> {
                    val operation = orderedPendingOperations().firstOrNull {
                        it.id == item.operationId
                    } ?: return DestinationCleanupOutcome.DESTINATION_RETAINED
                    if (
                        deleteModernPendingDestination(
                            item = item,
                            expectedRelativePath = operation.targetRelativePath,
                            requireRecoveryMarker = item.status == MoveItemStatus.PREPARED,
                        )
                    ) {
                        DestinationCleanupOutcome.REMOVED
                    } else if (
                        modernPublishedDestinationMatches(
                            item = item,
                            expectedRelativePath = operation.targetRelativePath,
                        )
                    ) {
                        DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED
                    } else {
                        DestinationCleanupOutcome.DESTINATION_RETAINED
                    }
                }
            }
        }
        // Once a destination URI/path is durable in the journal, never delete an existing artifact
        // as rollback. Source presence and destination deletion cannot be made atomic, and a
        // sidecar cannot prove that an existing filesystem inode was not replaced. Only clear a
        // destination reference after an explicit successful query proves it is already absent.
        return when (sourceAvailability(destination)) {
            SourceAvailability.PRESENT -> DestinationCleanupOutcome.VISIBLE_DESTINATION_RETAINED
            SourceAvailability.INACCESSIBLE -> DestinationCleanupOutcome.DESTINATION_RETAINED
            SourceAvailability.MISSING -> {
                if (destination.scheme == "file") deleteLegacyOwnershipMarker(item)
                DestinationCleanupOutcome.REMOVED
            }
        }
    }

    private fun deleteModernPendingDestination(
        item: MoveItemRecord,
        expectedRelativePath: String?,
        requireRecoveryMarker: Boolean,
        requireDisplayName: Boolean = true,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val uri = item.destinationUri?.toUri() ?: return false
        if (uri.scheme != "content" ||
            uri.pathSegments.firstOrNull() != PRIMARY_EXTERNAL_MEDIA_VOLUME ||
            runCatching { ContentUris.parseId(uri) }.getOrNull() != item.newSongId
        ) {
            return false
        }
        val rowMatches = try {
            val queryUri = includePendingMedia(uri)
            resolver.query(
                queryUri,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.IS_PENDING,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.TITLE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return true
                val displayName = cursor.getString(2)
                val relativePath = cursor.getString(3)
                val recoveryRelativePath = expectedRelativePath?.let {
                    pendingMoveDestinationRelativePath(
                        it,
                        item.operationId,
                        item.oldSongId,
                    )
                }
                cursor.getLong(0) == item.newSongId &&
                    cursor.getInt(1) == 1 &&
                    (!requireDisplayName ||
                        displayName.equals(item.displayName, ignoreCase = true) ||
                        displayName?.startsWith(
                            pendingMoveDestinationDisplayName(
                                item.operationId,
                                item.oldSongId,
                            ),
                        ) == true) &&
                    (expectedRelativePath == null ||
                        relativePath.equals(expectedRelativePath, ignoreCase = true) ||
                        relativePath.equals(
                            recoveryRelativePath,
                            ignoreCase = true,
                        )) &&
                    (!requireRecoveryMarker ||
                        relativePath.equals(recoveryRelativePath, ignoreCase = true) ||
                        displayName?.startsWith(
                            pendingMoveDestinationDisplayName(
                                item.operationId,
                                item.oldSongId,
                            ),
                        ) == true || cursor.getString(4) == pendingMoveDestinationMarker(
                            item.operationId,
                            item.oldSongId,
                        )) &&
                    !cursor.moveToNext()
            } ?: return false
        } catch (_: Throwable) {
            return false
        }
        if (!rowMatches) return false

        // The exact app-created IS_PENDING row is private to this app. Some MediaStore versions
        // reject or ignore predicates on an item-URI delete, so validate every ownership field
        // first and then delete that concrete URI without a provider-specific selection clause.
        val deleted = runCatching { resolver.delete(uri, null, null) }.getOrElse { return false }
        return when (deleted) {
            1 -> true
            0 -> sourceAvailability(uri) == SourceAvailability.MISSING
            else -> false
        }
    }

    private fun modernPublishedDestinationMatches(
        item: MoveItemRecord,
        expectedRelativePath: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val uri = item.destinationUri?.toUri() ?: return false
        val expectedId = item.newSongId ?: return false
        if (uri.scheme != "content" ||
            uri.pathSegments.firstOrNull() != PRIMARY_EXTERNAL_MEDIA_VOLUME ||
            runCatching { ContentUris.parseId(uri) }.getOrNull() != expectedId
        ) {
            return false
        }
        val metadataMatches = runCatching {
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.IS_PENDING,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getLong(0) == expectedId &&
                    cursor.getInt(1) == 0 &&
                    cursor.getString(2) == expectedRelativePath &&
                    cursor.getString(3) == item.displayName &&
                    !cursor.moveToNext()
            } == true
        }.getOrDefault(false)
        return metadataMatches && contentCopyMatchesJournal(uri, item)
    }

    private fun publishModernVerifiedDestination(
        item: MoveItemRecord,
        expectedRelativePath: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val uri = item.destinationUri?.toUri() ?: return false
        val expectedId = item.newSongId ?: return false
        if (uri.scheme != "content" ||
            uri.pathSegments.firstOrNull() != PRIMARY_EXTERNAL_MEDIA_VOLUME ||
            runCatching { ContentUris.parseId(uri) }.getOrNull() != expectedId
        ) {
            return false
        }
        val recoveryRelativePath = pendingMoveDestinationRelativePath(
            expectedRelativePath,
            item.operationId,
            item.oldSongId,
        )
        val clauses = mutableListOf(
            "${MediaStore.Audio.Media.IS_PENDING} = 1",
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            "(${MediaStore.Audio.Media.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?)",
        )
        val arguments = mutableListOf(
            item.displayName,
            expectedRelativePath,
            recoveryRelativePath,
        )
        val updated = runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, expectedRelativePath)
                    put(MediaStore.Audio.Media.DISPLAY_NAME, item.displayName)
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                },
                clauses.joinToString(" AND "),
                arguments.toTypedArray(),
            )
        }.getOrElse { return false }
        // A zero-row update can be a benign publication race. The caller always performs a fresh
        // path/name/id/hash verification before treating either result as visible and complete.
        return updated == 0 || updated == 1
    }

    private suspend fun cleanupUnjournaledPendingDestinations(
        operation: MoveOperationRecord,
        item: MoveItemRecord,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val collection = MediaStore.Audio.Media.getContentUri(PRIMARY_EXTERNAL_MEDIA_VOLUME)
        val ids = mutableListOf<Long>()
        val recoveryRelativePath = pendingMoveDestinationRelativePath(
            operation.targetRelativePath,
            item.operationId,
            item.oldSongId,
        )
        val pendingCollection = includePendingMedia(collection)
        val cursor = resolver.query(
            pendingCollection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_PENDING} = 1 AND (" +
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? OR (" +
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND (" +
                "${MediaStore.Audio.Media.TITLE} = ? OR " +
                "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?)))",
            arrayOf(
                recoveryRelativePath,
                operation.targetRelativePath,
                pendingMoveDestinationMarker(item.operationId, item.oldSongId),
                "${pendingMoveDestinationDisplayName(item.operationId, item.oldSongId)}%",
            ),
            null,
        ) ?: throw IOException("无法确认崩溃前创建的目标记录，恢复日志已保留")
        cursor.use { result ->
            while (result.moveToNext()) ids += result.getLong(0)
        }
        ids.forEach { id ->
            val destination = ContentUris.withAppendedId(collection, id)
            try {
                val discovered = item.copy(
                    newSongId = id,
                    destinationUri = destination.toString(),
                )
                if (!deleteModernPendingDestination(
                        item = discovered,
                        expectedRelativePath = operation.targetRelativePath,
                        requireRecoveryMarker = true,
                        // The provider may normalize a colliding name between insert() and the
                        // crash. The unjournaled row is instead owned by the unpredictable
                        // operation/id marker plus exact path, pending state and concrete URI.
                        requireDisplayName = false,
                    )
                ) {
                    throw IOException("无法清理未完成的目标歌曲记录")
                }
            } catch (error: Throwable) {
                // The deterministic marker rediscovered a row created immediately before a crash.
                // If cleanup now fails, make that URI durable in the journal instead of losing it.
                journal.updateItem(
                    item.copy(
                        status = MoveItemStatus.FAILED,
                        newSongId = id,
                        destinationUri = destination.toString(),
                        error = "${error.safeMessage()}，目标 URI 已保留",
                    )
                )
                return
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacyMediaRowAfterSourceDeletion(
        item: MoveItemRecord,
        file: File,
    ): MoveItemRecord = withContext(NonCancellable + Dispatchers.IO) {
        val existing = legacyMediaRowsForPath(file.absolutePath)
        check(existing.size <= 1) {
            "目标路径存在多个系统媒体记录，已保留恢复日志"
        }
        val resolvedUri = existing.singleOrNull() ?: scanFile(file, "audio/*")
        check(legacyMediaRowMatchesPath(resolvedUri, file.absolutePath)) {
            "系统扫描返回的媒体记录不属于目标文件"
        }
        val resolved = item.copy(
            newSongId = ContentUris.parseId(resolvedUri),
            destinationUri = resolvedUri.toString(),
        )
        // Persist the exact row before cancellation can resume. If the process dies first, the
        // next recovery queries DATA and adopts the already indexed row instead of rescanning a
        // duplicate.
        journal.updateItem(resolved)
        resolved
    }

    @Suppress("DEPRECATION")
    private fun legacyMediaRowsForPath(path: String): List<Uri> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA} = ?",
            arrayOf(path),
            null,
        ) ?: throw IOException("无法查询目标文件的系统媒体记录")
        return cursor.use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(ContentUris.withAppendedId(collection, result.getLong(0)))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyMediaRowMatchesPath(uri: Uri, expectedPath: String): Boolean {
        val expectedId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return false
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        ) ?: return false
        return cursor.use { result ->
            result.moveToFirst() &&
                result.getLong(0) == expectedId &&
                result.getString(1) == expectedPath
        }
    }

    private suspend fun scanFile(file: File, mimeType: String): Uri =
        withContext(NonCancellable) {
            withTimeout(60_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                    ) { _, uri ->
                        if (!continuation.isActive) return@scanFile
                        if (uri != null) continuation.resume(uri)
                        else continuation.cancel(IOException("系统音乐库未能收录目标歌曲"))
                    }
                }
            }
        }

    private fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        cancellationContext: CoroutineContext? = null,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            throwIfCancellationRequested(cancellationContext)
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

    private fun digestFile(
        file: File,
        cancellationContext: CoroutineContext? = null,
    ): ByteArray = file.inputStream().use { digestStream(it, cancellationContext) }

    private fun digestStream(
        input: InputStream,
        cancellationContext: CoroutineContext? = null,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            throwIfCancellationRequested(cancellationContext)
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun throwIfCancellationRequested(cancellationContext: CoroutineContext? = null) {
        cancellationContext?.ensureActive()
        // A non-null context identifies foreground copy/delete work and therefore honours the
        // cooperative Cancel flag. Mandatory rollback/recovery verification deliberately passes
        // null: it must be allowed to hash and conditionally clean the exact operation-owned
        // artifact after cancellation, otherwise every Cancel would strand an IS_PENDING row.
        if ((cancellationContext != null && cancelRequested.get()) ||
            Thread.currentThread().isInterrupted
        ) {
            throw CancellationException("用户取消")
        }
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

    private enum class DestinationCleanupOutcome {
        REMOVED,
        SOURCE_MISSING,
        SOURCE_INACCESSIBLE,
        DESTINATION_IS_INDEXED_SOURCE,
        VISIBLE_DESTINATION_RETAINED,
        DESTINATION_RETAINED,
    }

    private companion object {
        const val MAX_AUTHORIZATION_BATCH = 2_000
    }
}

internal interface RelocationMediaAccess {
    fun externalVolumeNames(context: Context): Set<String>
    fun recentExternalVolumeNames(context: Context): Set<String>
    fun query(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
    ): Cursor?
}

private object AndroidRelocationMediaAccess : RelocationMediaAccess {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun externalVolumeNames(context: Context): Set<String> =
        MediaStore.getExternalVolumeNames(context)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun recentExternalVolumeNames(context: Context): Set<String> =
        MediaStore.getRecentExternalVolumeNames(context)

    override fun query(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
    ): Cursor? = resolver.query(uri, projection, null, null, null)
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
