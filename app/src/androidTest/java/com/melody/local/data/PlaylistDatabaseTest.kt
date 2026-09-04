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

    @Test
    fun remapUpdatesOneOldSongIdInEveryPlaylistWithoutDuplicates() = runBlocking {
        val first = dao.insertPlaylist(PlaylistEntity(name = "First"))
        val second = dao.insertPlaylist(PlaylistEntity(name = "Second"))
        dao.addSong(PlaylistSongEntity(first, 42L, addedAt = 1L))
        dao.addSong(PlaylistSongEntity(second, 42L, addedAt = 2L))
        dao.addSong(PlaylistSongEntity(second, 99L, addedAt = 3L))

        dao.remapSongIds(mapOf(42L to 99L))

        assertEquals(listOf(99L), dao.getSongIds(first))
        assertEquals(listOf(99L), dao.getSongIds(second))
        assertEquals(listOf(99L), dao.getAllSongIds())
    }

    @Test
    fun metadataAndMoveJournalArePersistedAndUpdated() = runBlocking {
        val metadata = RoomSongMetadataStore(database.songStateDao())
        val journal = RoomMoveJournalStore(database.songStateDao())
        metadata.put(SongMetadataOverride(7L, "Title", "Artist", "Album", "file:///cover.jpg"))
        journal.create(
            MoveOperationRecord("op", "Music/音澜/歌单汇总/", MoveOperationStatus.PREPARING),
            listOf(
                MoveItemRecord(
                    operationId = "op",
                    oldSongId = 7L,
                    sourceUri = "content://media/external/audio/media/7",
                    displayName = "song.mp3",
                    sourceSize = 12L,
                    status = MoveItemStatus.PREPARED,
                )
            ),
        )

        metadata.remap(7L, 8L)
        val item = journal.items("op").single()
        journal.updateItem(
            item.copy(
                sourceUri = "content://media/external_primary/audio/media/7",
                status = MoveItemStatus.COPIED,
                newSongId = 8L,
            )
        )

        assertEquals("Title", metadata.getAll().getValue(8L).title)
        assertEquals(MoveItemStatus.COPIED, journal.items("op").single().status)
        assertEquals(
            "content://media/external_primary/audio/media/7",
            journal.items("op").single().sourceUri,
        )
        assertEquals(listOf("op"), journal.pendingOperations().map { it.id })
    }

    @Test
    fun cancellingOperationRemainsDiscoverableForCrashRecovery() = runBlocking {
        val journal = RoomMoveJournalStore(database.songStateDao())
        journal.create(
            MoveOperationRecord(
                id = "cancel-recovery",
                targetRelativePath = "Music/音澜/歌单汇总/",
                status = MoveOperationStatus.MOVING,
            ),
            listOf(
                MoveItemRecord(
                    operationId = "cancel-recovery",
                    oldSongId = 17L,
                    sourceUri = "content://media/external_primary/audio/media/17",
                    displayName = "song.m4a",
                    sourceSize = 128L,
                    status = MoveItemStatus.SOURCE_DELETED,
                    newSongId = 23L,
                    destinationUri = "content://media/external_primary/audio/media/23",
                    checksum = "abc",
                )
            ),
        )

        journal.updateOperation("cancel-recovery", MoveOperationStatus.CANCELLING)

        assertEquals(
            listOf("cancel-recovery"),
            journal.pendingOperations().map { it.id },
        )
        assertEquals(
            MoveItemStatus.SOURCE_DELETED,
            journal.items("cancel-recovery").single().status,
        )
    }
}
