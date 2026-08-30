package com.melody.local.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true, name = "index_playlists_name_nocase")],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId", "addedAt"], name = "index_playlist_songs_playlistId_addedAt")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "song_overrides")
data class SongOverrideEntity(
    @PrimaryKey val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artworkPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "move_operations")
data class MoveOperationEntity(
    @PrimaryKey val id: String,
    val targetRelativePath: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "move_items",
    primaryKeys = ["operationId", "oldSongId"],
    foreignKeys = [
        ForeignKey(
            entity = MoveOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"], name = "index_move_items_operationId"),
        Index(value = ["oldSongId"], name = "index_move_items_oldSongId"),
    ],
)
data class MoveItemEntity(
    val operationId: String,
    val oldSongId: Long,
    val sourceUri: String,
    val displayName: String,
    val sourceSize: Long,
    val status: String,
    val newSongId: Long? = null,
    val destinationUri: String? = null,
    val checksum: String? = null,
    val error: String? = null,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
)

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.createdAt, COUNT(ps.songId) AS songCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        GROUP BY p.id
        ORDER BY p.createdAt DESC
        """
    )
    abstract fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    abstract suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addSong(crossRef: PlaylistSongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addSongs(crossRefs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    abstract suspend fun removeSongFromAllPlaylists(songId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId IN (:songIds)")
    abstract suspend fun removeSongsFromAllPlaylists(songIds: List<Long>)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    abstract fun observeSongIds(playlistId: Long): Flow<List<Long>>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    abstract suspend fun getSongIds(playlistId: Long): List<Long>

    @Query("SELECT DISTINCT songId FROM playlist_songs ORDER BY songId ASC")
    abstract suspend fun getAllSongIds(): List<Long>

    @Query("SELECT * FROM playlist_songs WHERE songId = :songId ORDER BY addedAt ASC")
    abstract suspend fun getMemberships(songId: Long): List<PlaylistSongEntity>

    @Query("SELECT * FROM playlist_songs WHERE songId IN (:songIds) ORDER BY addedAt ASC")
    abstract suspend fun getMemberships(songIds: List<Long>): List<PlaylistSongEntity>

    @Query("SELECT id FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findPlaylistIdByName(name: String): Long?

    @Transaction
    open suspend fun remapSongIds(remaps: Map<Long, Long>) {
        val effective = remaps.filter { (oldSongId, newSongId) -> oldSongId != newSongId }
        if (effective.isEmpty()) return
        val oldSongIds = effective.keys.toList()
        val replacements = getMemberships(oldSongIds).map { membership ->
            membership.copy(songId = effective.getValue(membership.songId))
        }
        removeSongsFromAllPlaylists(oldSongIds)
        addSongs(replacements)
    }
}

@Dao
abstract class SongStateDao {
    @Query("SELECT * FROM song_overrides")
    abstract suspend fun getAllOverrides(): List<SongOverrideEntity>

    @Query("SELECT * FROM song_overrides WHERE songId = :songId LIMIT 1")
    abstract suspend fun getOverride(songId: Long): SongOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOverride(value: SongOverrideEntity)

    @Query("DELETE FROM song_overrides WHERE songId = :songId")
    abstract suspend fun deleteOverride(songId: Long)

    @Transaction
    open suspend fun remapOverride(oldSongId: Long, newSongId: Long) {
        if (oldSongId == newSongId) return
        val existing = getOverride(oldSongId) ?: return
        upsertOverride(existing.copy(songId = newSongId))
        deleteOverride(oldSongId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMoveOperation(value: MoveOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMoveItems(values: List<MoveItemEntity>)

    @Transaction
    open suspend fun createMoveOperation(
        operation: MoveOperationEntity,
        items: List<MoveItemEntity>,
    ) {
        upsertMoveOperation(operation)
        upsertMoveItems(items)
    }

    @Query(
        "SELECT * FROM move_operations " +
            "WHERE status IN ('PREPARING', 'MOVING', 'AWAITING_PERMISSION', 'COMMITTING') " +
            "ORDER BY createdAt ASC"
    )
    abstract suspend fun getPendingMoveOperations(): List<MoveOperationEntity>

    @Query("SELECT * FROM move_items WHERE operationId = :operationId ORDER BY oldSongId ASC")
    abstract suspend fun getMoveItems(operationId: String): List<MoveItemEntity>

    @Query(
        "UPDATE move_operations SET status = :status, updatedAt = :updatedAt WHERE id = :operationId"
    )
    abstract suspend fun updateMoveOperationStatus(
        operationId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE move_items SET status = :status, newSongId = :newSongId,
            destinationUri = :destinationUri, checksum = :checksum, error = :error
        WHERE operationId = :operationId AND oldSongId = :oldSongId
        """
    )
    abstract suspend fun updateMoveItem(
        operationId: String,
        oldSongId: Long,
        status: String,
        newSongId: Long?,
        destinationUri: String?,
        checksum: String?,
        error: String?,
    )

    @Query("DELETE FROM move_operations WHERE id = :operationId")
    abstract suspend fun deleteMoveOperation(operationId: String)
}

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SongOverrideEntity::class,
        MoveOperationEntity::class,
        MoveItemEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun songStateDao(): SongStateDao

    companion object {
        @Volatile
        private var instance: PlaylistDatabase? = null

        fun getInstance(context: Context): PlaylistDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PlaylistDatabase::class.java,
                "melody_playlists.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val usedNames = mutableSetOf<String>()
                val nextSuffixByBaseName = mutableMapOf<String, Int>()
                val renames = mutableListOf<Pair<Long, String>>()
                db.query("SELECT id, name FROM playlists ORDER BY id ASC").use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow("id")
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val originalName = cursor.getString(nameColumn)
                        var candidate = originalName
                        val normalizedBaseName = originalName.lowercase()
                        var suffix = nextSuffixByBaseName[normalizedBaseName] ?: 2
                        while (!usedNames.add(candidate.lowercase())) {
                            candidate = "$originalName ($suffix)"
                            suffix++
                        }
                        nextSuffixByBaseName[normalizedBaseName] = suffix
                        if (candidate != originalName) renames += id to candidate
                    }
                }
                renames.forEach { (id, name) ->
                    db.execSQL("UPDATE playlists SET name = ? WHERE id = ?", arrayOf(name, id))
                }
                db.execSQL("DROP INDEX IF EXISTS index_playlist_songs_playlistId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playlist_songs_playlistId_addedAt " +
                        "ON playlist_songs(playlistId, addedAt)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_name_nocase " +
                        "ON playlists(name COLLATE NOCASE)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS song_overrides (
                        songId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        artworkPath TEXT,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(songId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS move_operations (
                        id TEXT NOT NULL,
                        targetRelativePath TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS move_items (
                        operationId TEXT NOT NULL,
                        oldSongId INTEGER NOT NULL,
                        sourceUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        sourceSize INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        newSongId INTEGER,
                        destinationUri TEXT,
                        checksum TEXT,
                        error TEXT,
                        PRIMARY KEY(operationId, oldSongId),
                        FOREIGN KEY(operationId) REFERENCES move_operations(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_move_items_operationId " +
                        "ON move_items(operationId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_move_items_oldSongId " +
                        "ON move_items(oldSongId)"
                )
            }
        }
    }
}
