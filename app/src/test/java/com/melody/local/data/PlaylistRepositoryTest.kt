package com.melody.local.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import android.database.sqlite.SQLiteConstraintException

class PlaylistRepositoryTest {

    @Test
    fun createRejectsAnExistingNameIgnoringCaseAndOuterWhitespace() {
        val repository = PlaylistRepository(FakePlaylistDao(listOf(PlaylistEntity(id = 1, name = "Road Trip"))))

        assertThrows(DuplicatePlaylistNameException::class.java) {
            runBlocking { repository.create("  road trip  ") }
        }
    }

    @Test
    fun renameRejectsANameOwnedByAnotherPlaylist() {
        val repository = PlaylistRepository(
            FakePlaylistDao(
                listOf(
                    PlaylistEntity(id = 1, name = "Road Trip"),
                    PlaylistEntity(id = 2, name = "Focus"),
                )
            )
        )

        assertThrows(DuplicatePlaylistNameException::class.java) {
            runBlocking { repository.rename(2, " road trip ") }
        }
    }

    @Test
    fun createAndRenameNormalizeNames() = runBlocking {
        val dao = FakePlaylistDao(emptyList())
        val repository = PlaylistRepository(dao)

        val id = repository.create("  Focus  ")
        repository.rename(id, "  Deep Focus  ")

        assertEquals("Deep Focus", dao.nameOf(id))
    }

    @Test
    fun rejectsBlankAndOverlongNames() {
        val repository = PlaylistRepository(FakePlaylistDao(emptyList()))

        assertThrows(BlankPlaylistNameException::class.java) {
            runBlocking { repository.create("   ") }
        }
        assertThrows(PlaylistNameTooLongException::class.java) {
            runBlocking { repository.create("x".repeat(41)) }
        }
    }

    @Test
    fun translatesCreateAndRenameConstraintRacesIntoDuplicateNameErrors() {
        val dao = FakePlaylistDao(emptyList())
        val repository = PlaylistRepository(dao)

        dao.failNextWriteWithConstraint = true
        assertThrows(DuplicatePlaylistNameException::class.java) {
            runBlocking { repository.create("Racing create") }
        }

        val id = runBlocking { repository.create("Existing") }
        dao.failNextWriteWithConstraint = true
        assertThrows(DuplicatePlaylistNameException::class.java) {
            runBlocking { repository.rename(id, "Racing rename") }
        }
    }

    private class FakePlaylistDao(initialPlaylists: List<PlaylistEntity>) : PlaylistDao() {
        private val storedPlaylists = initialPlaylists.toMutableList()
        private val summaries = MutableStateFlow<List<PlaylistSummary>>(emptyList())
        var failNextWriteWithConstraint = false

        override fun observePlaylists(): Flow<List<PlaylistSummary>> = summaries

        override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
            if (failNextWriteWithConstraint) {
                failNextWriteWithConstraint = false
                throw SQLiteConstraintException("simulated competing insert")
            }
            val id = (storedPlaylists.maxOfOrNull { it.id } ?: 0L) + 1L
            storedPlaylists += playlist.copy(id = id)
            return id
        }

        override suspend fun renamePlaylist(playlistId: Long, name: String) {
            if (failNextWriteWithConstraint) {
                failNextWriteWithConstraint = false
                throw SQLiteConstraintException("simulated competing rename")
            }
            val index = storedPlaylists.indexOfFirst { it.id == playlistId }
            if (index >= 0) storedPlaylists[index] = storedPlaylists[index].copy(name = name)
        }

        override suspend fun deletePlaylist(playlistId: Long) {
            storedPlaylists.removeAll { it.id == playlistId }
        }

        override suspend fun addSong(crossRef: PlaylistSongEntity): Long = 1L

        override suspend fun addSongs(crossRefs: List<PlaylistSongEntity>) = Unit

        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit

        override suspend fun removeSongFromAllPlaylists(songId: Long) = Unit

        override suspend fun removeSongsFromAllPlaylists(songIds: List<Long>) = Unit

        override fun observeSongIds(playlistId: Long): Flow<List<Long>> = MutableStateFlow(emptyList())

        override suspend fun getSongIds(playlistId: Long): List<Long> = emptyList()

        override suspend fun getAllSongIds(): List<Long> = emptyList()

        override suspend fun getMemberships(songId: Long): List<PlaylistSongEntity> = emptyList()

        override suspend fun getMemberships(songIds: List<Long>): List<PlaylistSongEntity> = emptyList()

        override suspend fun findPlaylistIdByName(name: String): Long? = storedPlaylists
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.id

        fun nameOf(playlistId: Long): String? = storedPlaylists
            .firstOrNull { it.id == playlistId }
            ?.name
    }
}
