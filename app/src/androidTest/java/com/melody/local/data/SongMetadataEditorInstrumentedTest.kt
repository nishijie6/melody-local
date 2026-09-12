package com.melody.local.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongMetadataEditorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: PlaylistDatabase
    private lateinit var metadata: RoomSongMetadataStore
    private lateinit var artworkDirectory: File
    private val createdFiles = mutableListOf<File>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        metadata = RoomSongMetadataStore(database.songStateDao())
        artworkDirectory = File(context.cacheDir, "song-metadata-artwork-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        database.close()
        createdFiles.forEach { it.delete() }
        artworkDirectory.deleteRecursively()
    }

    @Test fun savesMetadataWithoutChangingAudioIdentityOrPlaylistMembership() = runBlocking {
        val original = song()
        val playlistId = database.playlistDao().insertPlaylist(PlaylistEntity(name = "Edited songs"))
        database.playlistDao().addSong(PlaylistSongEntity(playlistId, original.id))
        val editor = editor()
        val result = editor.save(original, "  New title  ", "New artist", "New album", null)
        assertEquals(original.id, result.id)
        assertEquals(original.contentUri, result.contentUri)
        assertEquals(original.durationMs, result.durationMs)
        assertEquals(original.albumArtUri, result.albumArtUri)
        assertEquals(listOf(original.id), database.playlistDao().getSongIds(playlistId))
        val reloaded = RoomSongMetadataStore(database.songStateDao()).getAll().getValue(original.id)
        assertEquals("New title", reloaded.title)
        assertEquals("New artist", reloaded.artist)
        assertEquals("New album", reloaded.album)
        assertEquals(original.albumArtUri.toString(), reloaded.artworkPath)
    }

    @Test fun selectedArtworkIsCopiedDownsampledAndSurvivesSourceRemoval() = runBlocking {
        val source = image()
        val updated = editor().save(
            song(), "Cover song", "", "", Uri.fromFile(source),
        )
        val saved = File(updated.albumArtUri!!.path!!).also(createdFiles::add)
        assertTrue(saved.exists())
        assertNotEquals(source.absolutePath, saved.absolutePath)
        source.delete()
        val bitmap = BitmapFactory.decodeFile(saved.absolutePath)
        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1024)
        bitmap.recycle()
        assertEquals("未知歌手", updated.artist)
        assertEquals("未知专辑", updated.album)
        assertEquals(updated.albumArtUri.toString(), metadata.getAll().getValue(7).artworkPath)
    }

    @Test fun selectedArtworkAppliesExifOrientationBeforeSaving() = runBlocking {
        val source = File(context.cacheDir, "editor-oriented-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        createdFiles += source

        val updated = editor().save(
            song(), "Portrait cover", "Artist", "Album", Uri.fromFile(source),
        )
        val saved = File(updated.albumArtUri!!.path!!).also(createdFiles::add)
        val savedBitmap = BitmapFactory.decodeFile(saved.absolutePath)
        try {
            assertEquals(40, savedBitmap.width)
            assertEquals(80, savedBitmap.height)
        } finally {
            savedBitmap.recycle()
        }
    }

    @Test fun invalidInputKeepsExistingMetadataAndCover() = runBlocking {
        val editor = editor()
        editor.save(song(), "Original", "Artist", "Album", null)
        val before = metadata.getAll()
        val invalidImage = File(context.cacheDir, "invalid-${UUID.randomUUID()}").also {
            it.writeText("not an image")
            createdFiles += it
        }
        for ((title, artwork) in listOf(
            " " to null, "x".repeat(201) to null, "Changed" to Uri.fromFile(invalidImage),
        )) {
            assertTrue(runCatching { editor.save(song(), title, "A", "B", artwork) }.isFailure)
            assertEquals(before, metadata.getAll())
        }
    }

    @Test fun artworkLargerThanTwentyMiBIsRejectedBeforeDecodeOrPersistence() = runBlocking {
        val oversized = File(context.cacheDir, "oversized-${UUID.randomUUID()}.jpg")
            .also(createdFiles::add)
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(20L * 1024 * 1024 + 1L)
        }

        val failure = runCatching {
            editor().save(
                song(), "Too large", "Artist", "Album", Uri.fromFile(oversized),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("20 MiB"))
        assertTrue(metadata.getAll().isEmpty())
        assertTrue(artworkDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun databaseFailureRemovesOnlyNewArtwork() = runBlocking {
        val directory = artworkDirectory
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val failingStore = object : SongMetadataStore by metadata {
            override suspend fun put(value: SongMetadataOverride) { throw IOException("disk full") }
        }
        val source = image()
        assertTrue(runCatching {
            editor(failingStore).save(song(), "New", "A", "B", Uri.fromFile(source))
        }.isFailure)
        assertTrue(source.exists())
        assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(metadata.getAll().isEmpty())
    }

    @Test fun laterEditsCollectOldUnreferencedPrivateCoversAfterAGracePeriod() = runBlocking {
        val editor = editor()
        val first = editor.save(song(), "First", "Artist", "Album", Uri.fromFile(image()))
        val firstCover = File(first.albumArtUri!!.path!!).also(createdFiles::add)
        assertTrue(firstCover.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1_000))

        val second = editor.save(first, "Second", "Artist", "Album", Uri.fromFile(image()))
        val secondCover = File(second.albumArtUri!!.path!!).also(createdFiles::add)
        assertTrue(firstCover.exists())
        assertTrue(secondCover.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1_000))

        val third = editor.save(second, "Third", "Artist", "Album", Uri.fromFile(image()))
        val thirdCover = File(third.albumArtUri!!.path!!).also(createdFiles::add)
        assertFalse(firstCover.exists())
        assertTrue(secondCover.exists())
        assertTrue(thirdCover.exists())
        assertEquals(third.albumArtUri.toString(), metadata.getAll().getValue(song().id).artworkPath)
    }

    private fun image(): File {
        val file = File(context.cacheDir, "editor-image-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(2200, 1100, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        createdFiles += file
        return file
    }

    private fun editor(store: SongMetadataStore = metadata) =
        LocalSongMetadataEditor(context, store, artworkDirectory)

    private fun song() = Song(
        id = 7, title = "Old", artist = "Artist", album = "Album", albumId = 0,
        durationMs = 9000, trackNumber = 1, dateAddedSeconds = 1,
        contentUri = Uri.parse("content://media/external_primary/audio/media/7"),
        albumArtUri = Uri.parse("content://media/external/audio/albumart/3"),
    )
}
