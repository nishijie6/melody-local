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

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.createdAt, COUNT(ps.songId) AS songCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        GROUP BY p.id
        ORDER BY p.createdAt DESC
        """
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(crossRef: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun observeSongIds(playlistId: Long): Flow<List<Long>>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun getSongIds(playlistId: Long): List<Long>

    @Query("SELECT id FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findPlaylistIdByName(name: String): Long?
}

@Database(
    entities = [PlaylistEntity::class, PlaylistSongEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: PlaylistDatabase? = null

        fun getInstance(context: Context): PlaylistDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PlaylistDatabase::class.java,
                "melody_playlists.db",
            )
                .addMigrations(MIGRATION_1_2)
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
    }
}
