package com.melody.local.data

import android.database.MatrixCursor
import android.os.Build
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
                addRow(mediaRow(projection, 7L, "  ", MediaStore.UNKNOWN_STRING, null, 41L, 123_456L, 1_005, 99L, "external_primary"))
                addRow(mediaRow(projection, 8L, "Song", "Artist", "Album", 0L, 65_000L, 2, 100L, "0123-4567"))
            }
        })

        val songs = repository.loadSongs()

        assertEquals(2, songs.size)
        assertEquals("未知歌曲", songs[0].title)
        assertEquals("未知歌手", songs[0].artist)
        assertEquals("未知专辑", songs[0].album)
        assertEquals(5, songs[0].trackNumber)
        val expectedFirstUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "content://media/external_primary/audio/media/7"
        } else {
            "content://media/external/audio/media/7"
        }
        assertEquals(expectedFirstUri, songs[0].contentUri.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals("content://media/0123-4567/audio/media/8", songs[1].contentUri.toString())
        }
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
    fun localMetadataAndArtworkOverrideMediaStoreValues() = runBlocking {
        val metadata = object : SongMetadataStore {
            override suspend fun getAll() = mapOf(
                7L to SongMetadataOverride(
                    7L,
                    "Custom title",
                    "Custom artist",
                    "Custom album",
                    "file:///private/cover.jpg",
                )
            )
            override suspend fun put(value: SongMetadataOverride) = Unit
            override suspend fun remap(oldSongId: Long, newSongId: Long) = Unit
            override suspend fun delete(songId: Long) = Unit
        }
        val repository = MusicRepository(
            AudioMediaQuery { _, projection, _, _ ->
                MatrixCursor(projection).apply {
                    addRow(mediaRow(projection, 7L, "Store title", "Store artist", "Store album", 41L, 1_000L, 1, 1L, "external_primary"))
                }
            },
            metadata,
        )

        val song = repository.loadSongs().single()

        assertEquals("Custom title", song.title)
        assertEquals("Custom artist", song.artist)
        assertEquals("Custom album", song.album)
        assertEquals("file:///private/cover.jpg", song.albumArtUri.toString())
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

    private fun mediaRow(
        projection: Array<String>,
        id: Long,
        title: String?,
        artist: String?,
        album: String?,
        albumId: Long,
        duration: Long,
        track: Int,
        dateAdded: Long,
        volumeName: String,
    ): Array<Any?> = projection.map { column ->
        when (column) {
            MediaStore.Audio.Media._ID -> id
            MediaStore.Audio.Media.TITLE -> title
            MediaStore.Audio.Media.ARTIST -> artist
            MediaStore.Audio.Media.ALBUM -> album
            MediaStore.Audio.Media.ALBUM_ID -> albumId
            MediaStore.Audio.Media.DURATION -> duration
            MediaStore.Audio.Media.TRACK -> track
            MediaStore.Audio.Media.DATE_ADDED -> dateAdded
            MediaStore.MediaColumns.VOLUME_NAME -> volumeName
            else -> null
        }
    }.toTypedArray()
}
