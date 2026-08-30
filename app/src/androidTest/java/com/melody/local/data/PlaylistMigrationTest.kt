package com.melody.local.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistMigrationTest {

    @Test
    fun migrationPreservesDuplicatePlaylistsWithUniqueNamesAndNewIndexes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playlist-migration-test.db"
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE playlists (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, createdAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE playlist_songs (" +
                    "playlistId INTEGER NOT NULL, songId INTEGER NOT NULL, addedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(playlistId, songId), " +
                    "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX index_playlist_songs_playlistId ON playlist_songs(playlistId)")
            db.execSQL("INSERT INTO playlists(id, name, createdAt) VALUES (1, 'Road Trip', 1)")
            db.execSQL("INSERT INTO playlists(id, name, createdAt) VALUES (2, 'ROAD TRIP', 2)")
            db.version = 1
        }

        val migrated = Room.databaseBuilder(context, PlaylistDatabase::class.java, databaseName)
            .addMigrations(PlaylistDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals(
                    listOf("ROAD TRIP (2)", "Road Trip"),
                    migrated.playlistDao().observePlaylists().first().map { it.name },
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    migrated.playlistDao().insertPlaylist(PlaylistEntity(name = "road trip"))
                }
            }
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }
}

