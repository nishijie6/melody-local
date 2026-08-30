package com.melody.local.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melody.local.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongFilteringTest {
    private val songs = listOf(
        song(1, "Beta", "Artist B", "Night", added = 10),
        song(2, "Alpha", "Artist C", "Morning", added = 30),
        song(3, "Gamma", "Artist A", "Night", added = 20),
    )

    @Test
    fun searchesTitleArtistAndAlbumIgnoringCase() {
        assertEquals(listOf(2L), filterAndSortSongs(songs, "alpha", SongSort.TITLE).map { it.id })
        assertEquals(listOf(3L), filterAndSortSongs(songs, "artist a", SongSort.TITLE).map { it.id })
        assertEquals(listOf(1L, 3L), filterAndSortSongs(songs, "NIGHT", SongSort.TITLE).map { it.id })
    }

    @Test
    fun appliesAllThreeSortOrders() {
        assertEquals(listOf(2L, 1L, 3L), filterAndSortSongs(songs, "", SongSort.TITLE).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), filterAndSortSongs(songs, "", SongSort.ARTIST).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), filterAndSortSongs(songs, "", SongSort.RECENT).map { it.id })
    }

    private fun song(id: Long, title: String, artist: String, album: String, added: Long) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 60_000L,
        trackNumber = 0,
        dateAddedSeconds = added,
        contentUri = Uri.parse("content://test/$id"),
        albumArtUri = null,
    )
}

