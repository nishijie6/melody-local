package com.melody.local.data

data class SongMetadataOverride(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artworkPath: String?,
)

interface SongMetadataStore {
    suspend fun getAll(): Map<Long, SongMetadataOverride>
    suspend fun put(value: SongMetadataOverride)
    suspend fun remap(oldSongId: Long, newSongId: Long)
    suspend fun delete(songId: Long)
}

class RoomSongMetadataStore(
    private val dao: SongStateDao,
) : SongMetadataStore {
    override suspend fun getAll(): Map<Long, SongMetadataOverride> =
        dao.getAllOverrides().associate { entity ->
            entity.songId to entity.asExternal()
        }

    override suspend fun put(value: SongMetadataOverride) {
        dao.upsertOverride(value.asEntity())
    }

    override suspend fun remap(oldSongId: Long, newSongId: Long) {
        dao.remapOverride(oldSongId, newSongId)
    }

    override suspend fun delete(songId: Long) {
        dao.deleteOverride(songId)
    }

    private fun SongOverrideEntity.asExternal() = SongMetadataOverride(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        artworkPath = artworkPath,
    )

    private fun SongMetadataOverride.asEntity() = SongOverrideEntity(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        artworkPath = artworkPath,
    )
}

internal object EmptySongMetadataStore : SongMetadataStore {
    override suspend fun getAll(): Map<Long, SongMetadataOverride> = emptyMap()
    override suspend fun put(value: SongMetadataOverride) = Unit
    override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
    override suspend fun delete(songId: Long) = Unit
}

enum class MoveOperationStatus {
    PREPARING,
    MOVING,
    AWAITING_PERMISSION,
    COMMITTING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

enum class MoveItemStatus {
    PREPARED,
    COPIED,
    SOURCE_DELETED,
    COMMITTED,
    SKIPPED,
    CANCELLED,
    FAILED,
}

data class MoveOperationRecord(
    val id: String,
    val targetRelativePath: String,
    val status: MoveOperationStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class MoveItemRecord(
    val operationId: String,
    val oldSongId: Long,
    val sourceUri: String,
    val displayName: String,
    val sourceSize: Long,
    val status: MoveItemStatus,
    val newSongId: Long? = null,
    val destinationUri: String? = null,
    val checksum: String? = null,
    val error: String? = null,
)

interface MoveJournalStore {
    suspend fun create(operation: MoveOperationRecord, items: List<MoveItemRecord>)
    suspend fun pendingOperations(): List<MoveOperationRecord>
    suspend fun items(operationId: String): List<MoveItemRecord>
    suspend fun updateOperation(operationId: String, status: MoveOperationStatus)
    suspend fun updateItem(item: MoveItemRecord)
    suspend fun delete(operationId: String)
}

class RoomMoveJournalStore(
    private val dao: SongStateDao,
) : MoveJournalStore {
    override suspend fun create(
        operation: MoveOperationRecord,
        items: List<MoveItemRecord>,
    ) {
        dao.createMoveOperation(operation.asEntity(), items.map { it.asEntity() })
    }

    override suspend fun pendingOperations(): List<MoveOperationRecord> =
        dao.getPendingMoveOperations().map { it.asExternal() }

    override suspend fun items(operationId: String): List<MoveItemRecord> =
        dao.getMoveItems(operationId).map { it.asExternal() }

    override suspend fun updateOperation(operationId: String, status: MoveOperationStatus) {
        dao.updateMoveOperationStatus(operationId, status.name)
    }

    override suspend fun updateItem(item: MoveItemRecord) {
        dao.updateMoveItem(
            operationId = item.operationId,
            oldSongId = item.oldSongId,
            status = item.status.name,
            newSongId = item.newSongId,
            destinationUri = item.destinationUri,
            checksum = item.checksum,
            error = item.error,
        )
    }

    override suspend fun delete(operationId: String) {
        dao.deleteMoveOperation(operationId)
    }

    private fun MoveOperationRecord.asEntity() = MoveOperationEntity(
        id = id,
        targetRelativePath = targetRelativePath,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MoveOperationEntity.asExternal() = MoveOperationRecord(
        id = id,
        targetRelativePath = targetRelativePath,
        status = MoveOperationStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MoveItemRecord.asEntity() = MoveItemEntity(
        operationId = operationId,
        oldSongId = oldSongId,
        sourceUri = sourceUri,
        displayName = displayName,
        sourceSize = sourceSize,
        status = status.name,
        newSongId = newSongId,
        destinationUri = destinationUri,
        checksum = checksum,
        error = error,
    )

    private fun MoveItemEntity.asExternal() = MoveItemRecord(
        operationId = operationId,
        oldSongId = oldSongId,
        sourceUri = sourceUri,
        displayName = displayName,
        sourceSize = sourceSize,
        status = MoveItemStatus.valueOf(status),
        newSongId = newSongId,
        destinationUri = destinationUri,
        checksum = checksum,
        error = error,
    )
}
