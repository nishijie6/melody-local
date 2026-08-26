package com.melody.local.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDatabaseTest {
    private lateinit var database: PlaylistDatabase
    private lateinit var dao: PlaylistDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.playlistDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun nameLookupAndUniqueIndexAreCaseInsensitive() {
        runBlocking {
            dao.insertPlaylist(PlaylistEntity(name = "Road Trip"))

            assertEquals(1L, dao.findPlaylistIdByName("road trip"))
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertPlaylist(PlaylistEntity(name = "ROAD TRIP")) }
            }
        }
    }

    @Test
    fun membershipIsOrderedIdempotentCountedAndCascadeDeleted() {
        runBlocking {
            val playlistId = dao.insertPlaylist(PlaylistEntity(name = "Focus"))
            assertEquals(1L, dao.addSong(PlaylistSongEntity(playlistId, 20L, addedAt = 2L)))
            assertEquals(2L, dao.addSong(PlaylistSongEntity(playlistId, 10L, addedAt = 1L)))
            assertEquals(-1L, dao.addSong(PlaylistSongEntity(playlistId, 10L, addedAt = 3L)))

            assertEquals(listOf(10L, 20L), dao.getSongIds(playlistId))
            assertEquals(listOf(10L, 20L), dao.observeSongIds(playlistId).first())
            assertEquals(2, dao.observePlaylists().first().single().songCount)

            dao.removeSong(playlistId, 10L)
            assertEquals(listOf(20L), dao.observeSongIds(playlistId).first())

            dao.deletePlaylist(playlistId)
            assertEquals(emptyList<Long>(), dao.getSongIds(playlistId))
        }
    }
}
