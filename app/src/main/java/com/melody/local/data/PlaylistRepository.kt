package com.melody.local.data

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.Flow

sealed class PlaylistNameException(message: String) : IllegalArgumentException(message)
class BlankPlaylistNameException : PlaylistNameException("歌单名称不能为空")
class PlaylistNameTooLongException : PlaylistNameException("歌单名称不能超过 40 个字符")
class DuplicatePlaylistNameException(name: String) : PlaylistNameException("歌单“$name”已存在")

interface PlaylistStore {
    val playlists: Flow<List<PlaylistSummary>>
    suspend fun create(name: String): Long
    suspend fun rename(playlistId: Long, name: String)
    suspend fun delete(playlistId: Long)
    suspend fun addSong(playlistId: Long, songId: Long): Boolean
    suspend fun removeSong(playlistId: Long, songId: Long)
    fun observeSongIds(playlistId: Long): Flow<List<Long>>
    suspend fun getAllSongIds(): List<Long>
    suspend fun remapSongIds(remaps: Map<Long, Long>)
}

class PlaylistRepository(private val dao: PlaylistDao) : PlaylistStore {
    override val playlists: Flow<List<PlaylistSummary>> = dao.observePlaylists()

    override suspend fun create(name: String): Long {
        val normalizedName = normalizeName(name)
        if (dao.findPlaylistIdByName(normalizedName) != null) {
            throw DuplicatePlaylistNameException(normalizedName)
        }
        return try {
            dao.insertPlaylist(PlaylistEntity(name = normalizedName))
        } catch (_: SQLiteConstraintException) {
            throw DuplicatePlaylistNameException(normalizedName)
        }
    }

    override suspend fun rename(playlistId: Long, name: String) {
        val normalizedName = normalizeName(name)
        val existingPlaylistId = dao.findPlaylistIdByName(normalizedName)
        if (existingPlaylistId != null && existingPlaylistId != playlistId) {
            throw DuplicatePlaylistNameException(normalizedName)
        }
        try {
            dao.renamePlaylist(playlistId, normalizedName)
        } catch (_: SQLiteConstraintException) {
            throw DuplicatePlaylistNameException(normalizedName)
        }
    }

    override suspend fun delete(playlistId: Long) = dao.deletePlaylist(playlistId)

    override suspend fun addSong(playlistId: Long, songId: Long): Boolean =
        dao.addSong(PlaylistSongEntity(playlistId = playlistId, songId = songId)) != -1L

    override suspend fun removeSong(playlistId: Long, songId: Long) =
        dao.removeSong(playlistId, songId)

    override fun observeSongIds(playlistId: Long): Flow<List<Long>> = dao.observeSongIds(playlistId)

    override suspend fun getAllSongIds(): List<Long> = dao.getAllSongIds()

    override suspend fun remapSongIds(remaps: Map<Long, Long>) = dao.remapSongIds(remaps)

    suspend fun getSongIds(playlistId: Long): List<Long> = dao.getSongIds(playlistId)

    private fun normalizeName(name: String): String {
        val normalized = name.trim()
        if (normalized.isBlank()) throw BlankPlaylistNameException()
        if (normalized.length > MAX_PLAYLIST_NAME_LENGTH) throw PlaylistNameTooLongException()
        return normalized
    }

    private companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 40
    }
}
