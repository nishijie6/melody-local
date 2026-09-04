package com.melody.local.media

import android.net.Uri

// MediaStore introduced the named-volume constant in API 29, but the URI segment itself is a
// stable wire value. Keeping the value here lets recovery code validate persisted URIs on every
// supported API without referencing an inlined newer-SDK field from minSdk 26 code.
internal const val PRIMARY_EXTERNAL_MEDIA_VOLUME = "external_primary"

sealed interface MediaOperationState {
    data object Idle : MediaOperationState
    data class Preparing(val message: String) : MediaOperationState
    data class Processing(
        val currentFile: String,
        val completed: Int,
        val total: Int,
        val progressPercent: Int,
    ) : MediaOperationState
    data class AwaitingSystemAuthorization(
        val message: String,
        val completed: Int,
        val total: Int,
    ) : MediaOperationState
    data class Completed(val summary: MediaOperationSummary) : MediaOperationState
    data class Failed(val message: String) : MediaOperationState
    data class Cancelled(val summary: MediaOperationSummary = MediaOperationSummary()) : MediaOperationState
}

data class MediaOperationSummary(
    val imported: Int = 0,
    val moved: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val songId: Long? = null,
    val outputUri: String? = null,
)

data class VideoImportDraft(
    val uri: Uri,
    val suggestedTitle: String,
    val artist: String = "未知歌手",
    val album: String = "视频提取",
    val extractArtwork: Boolean = true,
)

data class VideoImportRequest(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val extractArtwork: Boolean,
)

data class PlaylistMovePreview(
    val songCount: Int,
    val totalBytes: Long,
    val unavailableCount: Int,
)

enum class AudioExportStrategy { PASSTHROUGH_AAC, TRANSCODE_TO_AAC }

internal enum class SongRelocationRoute { LEGACY_FILE_MOVE, IN_PLACE_MEDIASTORE, COPY_VERIFY_DELETE }

internal enum class MoveRecoveryAction { RETAIN_TARGET_FOR_RECOVERY, COMMIT_REMAP, CONTINUE }

internal enum class LegacyPreparedRecoveryAction {
    COMMIT_EXISTING_ROW,
    COMMIT_VERIFIED_DESTINATION,
    RETAIN_VERIFIED_TARGET,
    CLEAN_TARGET_AND_RETRY,
    FAIL_WITHOUT_DELETING_TARGET,
}

internal enum class DeletionIntegrityResult { VERIFIED, SOURCE_CHANGED, DESTINATION_CHANGED }

/**
 * Result of querying one concrete MediaStore volume for one row ID.
 *
 * [Absent] is deliberately different from [Inaccessible]: only a successfully returned cursor
 * with no row proves absence. A provider exception or a null cursor must stop bare-ID resolution,
 * otherwise a row with the same numeric ID on another volume could be selected by mistake.
 */
internal sealed interface MediaRowQueryResult<out T> {
    data class Found<T>(val value: T) : MediaRowQueryResult<T>
    data object Absent : MediaRowQueryResult<Nothing>
    data object Inaccessible : MediaRowQueryResult<Nothing>
}

internal fun <T> completeMediaRowQuery(
    results: Iterable<MediaRowQueryResult<T>>,
): List<T>? {
    val values = mutableListOf<T>()
    results.forEach { result ->
        when (result) {
            is MediaRowQueryResult.Found -> values += result.value
            MediaRowQueryResult.Absent -> Unit
            MediaRowQueryResult.Inaccessible -> return null
        }
    }
    return values
}

internal fun <T> MediaRowQueryResult<T>.valueOrNull(): T? =
    (this as? MediaRowQueryResult.Found)?.value

internal fun audioExportStrategy(mimeType: String?): AudioExportStrategy =
    if (mimeType.equals("audio/mp4a-latm", ignoreCase = true) ||
        mimeType.equals("audio/aac", ignoreCase = true)
    ) {
        AudioExportStrategy.PASSTHROUGH_AAC
    } else {
        AudioExportStrategy.TRANSCODE_TO_AAC
    }

internal fun deduplicateSongIds(songIds: Iterable<Long>): List<Long> = songIds.distinct()

internal fun relocationRoute(apiLevel: Int, volumeName: String?): SongRelocationRoute = when {
    apiLevel < 29 -> SongRelocationRoute.LEGACY_FILE_MOVE
    volumeName == "external_primary" ->
        SongRelocationRoute.IN_PLACE_MEDIASTORE
    else -> SongRelocationRoute.COPY_VERIFY_DELETE
}

internal fun copyVerificationPassed(
    expectedSourceSize: Long,
    copiedBytes: Long,
    sourceHash: ByteArray,
    destinationHash: ByteArray,
): Boolean = copiedBytes > 0L &&
    (expectedSourceSize <= 0L || copiedBytes == expectedSourceSize) &&
    sourceHash.contentEquals(destinationHash)

internal fun moveRecoveryAction(
    status: com.melody.local.data.MoveItemStatus,
    sourceExists: Boolean,
): MoveRecoveryAction = when {
    status == com.melody.local.data.MoveItemStatus.COPIED && sourceExists ->
        MoveRecoveryAction.RETAIN_TARGET_FOR_RECOVERY
    status == com.melody.local.data.MoveItemStatus.SOURCE_DELETED ||
        (status == com.melody.local.data.MoveItemStatus.COPIED && !sourceExists) ->
        MoveRecoveryAction.COMMIT_REMAP
    else -> MoveRecoveryAction.CONTINUE
}

internal fun legacyPreparedRecoveryAction(
    mediaRowPointsAtDestination: Boolean,
    sourcePresent: Boolean,
    destinationVerified: Boolean,
): LegacyPreparedRecoveryAction = when {
    mediaRowPointsAtDestination && destinationVerified ->
        LegacyPreparedRecoveryAction.COMMIT_EXISTING_ROW
    mediaRowPointsAtDestination -> LegacyPreparedRecoveryAction.FAIL_WITHOUT_DELETING_TARGET
    !sourcePresent && destinationVerified ->
        LegacyPreparedRecoveryAction.COMMIT_VERIFIED_DESTINATION
    sourcePresent && destinationVerified ->
        LegacyPreparedRecoveryAction.RETAIN_VERIFIED_TARGET
    sourcePresent -> LegacyPreparedRecoveryAction.CLEAN_TARGET_AND_RETRY
    else -> LegacyPreparedRecoveryAction.FAIL_WITHOUT_DELETING_TARGET
}

internal fun pendingMoveDestinationMarker(operationId: String, oldSongId: Long): String =
    "yinlan-pending-move:$operationId:$oldSongId"

/**
 * MediaProvider may derive TITLE from DISPLAY_NAME while inserting a pending item. Keep the
 * crash-recovery identity in the unpublished file name as well, then replace it with the user's
 * final name only after the audio bytes have been verified.
 */
internal fun pendingMoveDestinationDisplayName(operationId: String, oldSongId: Long): String =
    "yinlan-pending-move-$operationId-$oldSongId"

internal fun pendingMoveDestinationRelativePath(
    targetRelativePath: String,
    operationId: String,
    oldSongId: Long,
): String = targetRelativePath.trimEnd('/') +
    "/.yinlan-pending-move-$operationId-$oldSongId/"

internal fun isSyntheticExternalMediaUri(uri: String): Boolean =
    SYNTHETIC_EXTERNAL_MEDIA_URI.matches(uri.substringBefore('?'))

internal fun mediaStorePublishSucceeded(updatedRows: Int): Boolean = updatedRows == 1

internal fun pendingDeletionIntegrityResult(
    destinationMatches: () -> Boolean,
    sourceMatches: () -> Boolean,
): DeletionIntegrityResult = when {
    !destinationMatches() -> DeletionIntegrityResult.DESTINATION_CHANGED
    !sourceMatches() -> DeletionIntegrityResult.SOURCE_CHANGED
    else -> DeletionIntegrityResult.VERIFIED
}

internal fun concreteDestinationIdentityIsUnique(
    destinationUri: String,
    candidateUris: List<String>,
): Boolean = candidateUris.size == 1 && candidateUris.single() == destinationUri

internal fun orderPendingMoveOperations(
    operations: Iterable<com.melody.local.data.MoveOperationRecord>,
): List<com.melody.local.data.MoveOperationRecord> =
    operations.sortedWith(
        compareBy<com.melody.local.data.MoveOperationRecord> { it.createdAt }.thenBy { it.id }
    )

internal fun defaultVideoTitle(displayName: String?): String {
    val name = displayName?.trim().orEmpty()
    val withoutExtension = name.substringBeforeLast('.', name).trim()
    return withoutExtension.ifBlank { "视频提取歌曲" }
}

internal fun validateDestinationFolder(raw: String): String {
    val folder = raw.trim()
    require(folder.isNotEmpty()) { "目标文件夹名称不能为空" }
    require(folder != "." && folder != "..") { "目标文件夹名称无效" }
    require(folder.length <= 60) { "目标文件夹名称不能超过 60 个字符" }
    require(folder.none { it in INVALID_FILE_NAME_CHARACTERS || it.code < 32 }) {
        "目标文件夹名称不能包含 \\ / : * ? \" < > |"
    }
    require(!folder.endsWith('.') && !folder.endsWith(' ')) { "目标文件夹名称不能以点或空格结尾" }
    return folder
}

internal fun safeAudioFileBase(raw: String): String {
    val cleaned = raw.trim()
        .map { character ->
            if (character in INVALID_FILE_NAME_CHARACTERS || character.code < 32) '_' else character
        }
        .joinToString("")
        .trimEnd('.', ' ')
        .take(120)
    return cleaned.ifBlank { "视频提取歌曲" }
}

internal fun uniqueDisplayName(desired: String, existingNames: Collection<String>): String {
    val occupied = existingNames.mapTo(hashSetOf()) { it.lowercase() }
    if (desired.lowercase() !in occupied) return desired
    val dot = desired.lastIndexOf('.')
    val base = if (dot > 0) desired.substring(0, dot) else desired
    val extension = if (dot > 0) desired.substring(dot) else ""
    var suffix = 2
    while (true) {
        val candidate = "$base ($suffix)$extension"
        if (candidate.lowercase() !in occupied) return candidate
        suffix++
    }
}

private val INVALID_FILE_NAME_CHARACTERS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

private val SYNTHETIC_EXTERNAL_MEDIA_URI =
    Regex("^content://(?:[0-9]+@)?media/external/.*$", RegexOption.IGNORE_CASE)
