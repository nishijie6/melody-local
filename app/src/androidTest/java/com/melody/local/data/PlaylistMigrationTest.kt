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
import org.junit.Assert.assertNull
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
            .addMigrations(
                PlaylistDatabase.MIGRATION_1_2,
                PlaylistDatabase.MIGRATION_2_3,
                PlaylistDatabase.MIGRATION_3_4,
            )
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

    @Test
    fun migrationFromTwoToThreePreservesPlaylistsAndCreatesOperationTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playlist-migration-2-3-test.db"
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE playlists (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL COLLATE NOCASE, createdAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX index_playlists_name_nocase ON playlists(name COLLATE NOCASE)"
            )
            db.execSQL(
                "CREATE TABLE playlist_songs (" +
                    "playlistId INTEGER NOT NULL, songId INTEGER NOT NULL, addedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(playlistId, songId), " +
                    "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX index_playlist_songs_playlistId_addedAt " +
                    "ON playlist_songs(playlistId, addedAt)"
            )
            db.execSQL("INSERT INTO playlists(id, name, createdAt) VALUES (1, 'Focus', 1)")
            db.execSQL("INSERT INTO playlist_songs(playlistId, songId, addedAt) VALUES (1, 55, 2)")
            db.version = 2
        }

        val migrated = Room.databaseBuilder(context, PlaylistDatabase::class.java, databaseName)
            .addMigrations(PlaylistDatabase.MIGRATION_2_3, PlaylistDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals(listOf(55L), migrated.playlistDao().getAllSongIds())
                val metadata = RoomSongMetadataStore(migrated.songStateDao())
                metadata.put(
                    SongMetadataOverride(
                        55L,
                        "Custom",
                        "Artist",
                        "Album",
                        null,
                        playbackStartMs = 4_000L,
                        playbackEndMs = 50_000L,
                    )
                )
                val saved = metadata.getAll().getValue(55L)
                assertEquals("Custom", saved.title)
                assertEquals(4_000L, saved.playbackStartMs)
                assertEquals(50_000L, saved.playbackEndMs)
            }
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationFromThreeToFourKeepsOverridesAndAddsFullSongPlaybackDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playlist-migration-3-4-test.db"
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE playlists (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL COLLATE NOCASE, createdAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX index_playlists_name_nocase " +
                    "ON playlists(name COLLATE NOCASE)"
            )
            db.execSQL(
                "CREATE TABLE playlist_songs (" +
                    "playlistId INTEGER NOT NULL, songId INTEGER NOT NULL, addedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(playlistId, songId), " +
                    "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX index_playlist_songs_playlistId_addedAt " +
                    "ON playlist_songs(playlistId, addedAt)"
            )
            db.execSQL(
                "CREATE TABLE song_overrides (" +
                    "songId INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                    "album TEXT NOT NULL, artworkPath TEXT, createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(songId))"
            )
            db.execSQL(
                "CREATE TABLE move_operations (" +
                    "id TEXT NOT NULL, targetRelativePath TEXT NOT NULL, status TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE TABLE move_items (" +
                    "operationId TEXT NOT NULL, oldSongId INTEGER NOT NULL, sourceUri TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, sourceSize INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "newSongId INTEGER, destinationUri TEXT, checksum TEXT, error TEXT, " +
                    "PRIMARY KEY(operationId, oldSongId), " +
                    "FOREIGN KEY(operationId) REFERENCES move_operations(id) ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX index_move_items_operationId ON move_items(operationId)")
            db.execSQL("CREATE INDEX index_move_items_oldSongId ON move_items(oldSongId)")
            db.execSQL(
                "INSERT INTO song_overrides(" +
                    "songId, title, artist, album, artworkPath, createdAt" +
                    ") VALUES (55, 'Custom', 'Artist', 'Album', NULL, 1)"
            )
            db.version = 3
        }

        val migrated = Room.databaseBuilder(context, PlaylistDatabase::class.java, databaseName)
            .addMigrations(PlaylistDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val saved = runBlocking {
                RoomSongMetadataStore(migrated.songStateDao()).getAll().getValue(55L)
            }
            assertEquals("Custom", saved.title)
            assertEquals(0L, saved.playbackStartMs)
            assertNull(saved.playbackEndMs)
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }
}
