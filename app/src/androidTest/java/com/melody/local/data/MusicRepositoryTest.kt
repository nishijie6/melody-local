package com.melody.local.data

import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicRepositoryTest {

    @Test
    fun mapsMediaStoreRowsAndNormalizesUnknownMetadata() = runBlocking {
        var selectionSeen: String? = null
        var sortSeen: String? = null
        val repository = MusicRepository(AudioMediaQuery { _, projection, selection, sortOrder ->
            selectionSeen = selection
            sortSeen = sortOrder
            MatrixCursor(projection).apply {
                addRow(arrayOf(7L, "  ", MediaStore.UNKNOWN_STRING, null, 41L, 123_456L, 1_005, 99L))
                addRow(arrayOf(8L, "Song", "Artist", "Album", 0L, 65_000L, 2, 100L))
            }
        })

        val songs = repository.loadSongs()

        assertEquals(2, songs.size)
        assertEquals("未知歌曲", songs[0].title)
        assertEquals("未知歌手", songs[0].artist)
        assertEquals("未知专辑", songs[0].album)
        assertEquals(5, songs[0].trackNumber)
        assertEquals("content://media/external/audio/media/7", songs[0].contentUri.toString())
        assertEquals("content://media/external/audio/albumart/41", songs[0].albumArtUri.toString())
        assertNull(songs[1].albumArtUri)
        assertEquals("is_music != 0 AND duration > 0", selectionSeen)
        assertEquals("title COLLATE NOCASE ASC", sortSeen)
    }

    @Test
    fun returnsAnEmptyLibraryWhenMediaStoreReturnsNoCursor() = runBlocking {
        val songs = MusicRepository(AudioMediaQuery { _, _, _, _ -> null }).loadSongs()

        assertEquals(emptyList<Song>(), songs)
    }

    @Test
    fun propagatesMediaStoreQueryFailuresToTheViewModelBoundary() {
        val repository = MusicRepository(AudioMediaQuery { _, _, _, _ ->
            throw SecurityException("permission revoked")
        })

        assertThrows(SecurityException::class.java) {
            runBlocking { repository.loadSongs() }
        }
    }
}

