package com.melody.local.media

import android.Manifest
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.RoomSongMetadataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoAudioImportWorkerTest {
    private lateinit var context: Context
    private val publishedUris = mutableListOf<Uri>()
    private val publishedSongIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val permissions = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        if (permissions.isNotEmpty()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .adoptShellPermissionIdentity(*permissions.toTypedArray())
        }
    }

    @After
    fun tearDown() = runBlocking {
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        publishedUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        publishedSongIds.forEach { metadata.delete(it) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    @Test
    fun transformerCreatesAudioOnlyM4aAndStoresFirstFrameArtwork() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val result = buildWorker(source, "Instrumentation video", extractArtwork = true).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        result as ListenableWorker.Result.Success
        val outputUri = Uri.parse(
            requireNotNull(result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        )
        val songId = result.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += outputUri
        publishedSongIds += songId

        val extractor = MediaExtractor()
        var hasAudio = false
        var hasVideo = false
        try {
            extractor.setDataSource(context, outputUri, null)
            repeat(extractor.trackCount) { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                hasAudio = hasAudio || mime.startsWith("audio/")
                hasVideo = hasVideo || mime.startsWith("video/")
            }
        } finally {
            extractor.release()
        }
        assertTrue(hasAudio)
        assertFalse(hasVideo)

        val override = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
            .getAll()[songId]
        assertNotNull(override)
        assertTrue(override?.artworkPath?.let(Uri::parse)?.path?.let(::File)?.isFile == true)
        source.delete()
    }

    @Test
    fun videoWithoutAudioFailsWithoutPublishingAnOutput() = runBlocking {
        val source = copyAsset("video_without_audio.mp4")
        val result = buildWorker(source, "No audio", extractArtwork = false).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        result as ListenableWorker.Result.Failure
        assertTrue(
            result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR)
                ?.contains("没有可解码的音轨") == true
        )
        assertTrue(result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI) == null)
        source.delete()
    }

    @Test
    fun cancellationStopsTransformerAndLeavesNoPublishedOrTemporaryAudio() = runBlocking {
        val source = copyAsset("video_with_opus.webm")
        val worker = buildWorker(source, "Cancellation fixture", extractArtwork = false)
        val task = async(Dispatchers.Default) { worker.doWork() }
        val temporaryDirectory = File(context.cacheDir, "video-audio-import")
        withTimeout(10_000L) {
            while (task.isActive && temporaryDirectory.listFiles()?.none { it.extension == "m4a" } != false) {
                delay(20L)
            }
        }
        assertTrue(task.isActive)
        task.cancelAndJoin()
        delay(200L)

        assertTrue(temporaryDirectory.listFiles().isNullOrEmpty())
        val count = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.TITLE} = ?",
            arrayOf("Cancellation fixture"),
            null,
        )?.use { it.count } ?: 0
        assertTrue(count == 0)
        source.delete()
    }

    private fun buildWorker(
        source: File,
        title: String,
        extractArtwork: Boolean,
    ): VideoAudioImportWorker = TestListenableWorkerBuilder<VideoAudioImportWorker>(context)
        .setInputData(
            workDataOf(
                WorkManagerVideoAudioExtractor.KEY_SOURCE_URI to source.toURI().toString(),
                WorkManagerVideoAudioExtractor.KEY_TITLE to title,
                WorkManagerVideoAudioExtractor.KEY_ARTIST to "Test artist",
                WorkManagerVideoAudioExtractor.KEY_ALBUM to "Test album",
                WorkManagerVideoAudioExtractor.KEY_EXTRACT_ARTWORK to extractArtwork,
            )
        )
        .build()

    private fun copyAsset(name: String): File {
        val target = File(context.cacheDir, "${System.nanoTime()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }
}
