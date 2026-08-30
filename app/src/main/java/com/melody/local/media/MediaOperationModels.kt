package com.melody.local.media

import android.net.Uri

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

internal enum class MoveRecoveryAction { CLEAN_TARGET_AND_RETRY, COMMIT_REMAP, CONTINUE }

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
    volumeName == "external_primary" || volumeName == "external" ->
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
        MoveRecoveryAction.CLEAN_TARGET_AND_RETRY
    status == com.melody.local.data.MoveItemStatus.SOURCE_DELETED ||
        (status == com.melody.local.data.MoveItemStatus.COPIED && !sourceExists) ->
        MoveRecoveryAction.COMMIT_REMAP
    else -> MoveRecoveryAction.CONTINUE
}

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
