package com.melody.local.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.melody.local.data.PlaylistDatabase
import com.melody.local.data.RoomSongMetadataStore
import com.melody.local.data.SongMetadataOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class VideoAudioImportWorkerTest {
    @get:Rule
    val storagePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }.toTypedArray()
    )

    private lateinit var context: Context
    private val publishedUris = mutableListOf<Uri>()
    private val publishedSongIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("video_audio_completed_imports", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("video_audio_legacy_pending", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("video_audio_import", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() = runBlocking {
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        publishedUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        val overrides = metadata.getAll()
        publishedSongIds.forEach { songId ->
            overrides[songId]?.artworkPath?.let(Uri::parse)?.path?.let(::File)?.delete()
            metadata.delete(songId)
        }
        context.getSharedPreferences("video_audio_completed_imports", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("video_audio_legacy_pending", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("video_audio_import", Context.MODE_PRIVATE)
            .edit().clear().commit()
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
        assertEquals(
            ExportResult.CONVERSION_PROCESS_TRANSMUXED,
            result.outputData.getInt(
                WorkManagerVideoAudioExtractor.KEY_AUDIO_CONVERSION_PROCESS,
                ExportResult.CONVERSION_PROCESS_NA,
            ),
        )

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
    fun opusAudioIsTranscodedToAacAndReportsConversionProcess() = runBlocking {
        val source = copyAsset("video_with_opus_short.webm")
        val result = buildWorker(source, "Opus transcode", extractArtwork = false).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        result as ListenableWorker.Result.Success
        val outputUri = Uri.parse(
            requireNotNull(result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        )
        val songId = result.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += outputUri
        publishedSongIds += songId
        assertEquals(
            ExportResult.CONVERSION_PROCESS_TRANSCODED,
            result.outputData.getInt(
                WorkManagerVideoAudioExtractor.KEY_AUDIO_CONVERSION_PROCESS,
                ExportResult.CONVERSION_PROCESS_NA,
            ),
        )

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, outputUri, null)
            val audioMimes = buildList {
                repeat(extractor.trackCount) { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.takeIf { it.startsWith("audio/") }
                        ?.let(::add)
                }
            }
            assertEquals(listOf(MimeTypes.AUDIO_AAC), audioMimes)
        } finally {
            extractor.release()
        }
        source.delete()
    }

    @Test
    fun unavailableVideoFrameKeepsMetadataWithoutCreatingPrivateArtwork() = runBlocking {
        // A real audio-only MP4 exercises MediaMetadataRetriever's no-frame result while keeping
        // the export path valid. The UI then uses its normal gradient/music-note placeholder.
        val source = copyAsset("audio_only_aac.m4a")
        val result = buildWorker(source, "Artwork fallback", extractArtwork = true).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        result as ListenableWorker.Result.Success
        val outputUri = Uri.parse(
            requireNotNull(result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        )
        val songId = result.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += outputUri
        publishedSongIds += songId

        val override = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
            .getAll()[songId]
        assertNotNull(override)
        assertNull(override?.artworkPath)
        assertFalse(File(context.filesDir, "artwork/$songId.jpg").exists())
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

    @Test
    fun publishedArtifactCleanupFinishesFromACancelledCoroutine() = runBlocking {
        val songId = Long.MAX_VALUE - (System.nanoTime() and 0xFFFFF)
        val workId = UUID.randomUUID()
        val artwork = File(context.filesDir, "artwork/$songId-video-$workId.jpg")
        artwork.parentFile?.mkdirs()
        artwork.writeBytes(byteArrayOf(1, 2, 3))
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        metadata.put(
            SongMetadataOverride(
                songId = songId,
                title = "Cancelled cleanup",
                artist = "Test artist",
                album = "Test album",
                artworkPath = artwork.toUri().toString(),
            )
        )
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val outputUri = android.content.ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId,
        )
        assertTrue(
            completionPreferences.edit().putString(
                "worker:$workId",
                JSONObject()
                    .put("workerId", workId.toString())
                    .put("state", "ALLOCATED")
                    .put("outputUri", outputUri.toString())
                    .put("songId", songId)
                    .put("audioConversionProcess", ExportResult.CONVERSION_PROCESS_TRANSMUXED)
                    .put("createdAtMillis", System.currentTimeMillis())
                    .put("metadataWrittenByWorker", true)
                    .put("artworkUri", artwork.toUri().toString())
                    .put("metadataTitle", "Cancelled cleanup")
                    .put("metadataArtist", "Test artist")
                    .put("metadataAlbum", "Test album")
                    .put("artifactOwnerWorkerId", workId.toString())
                    .put("metadataWriteIntent", true)
                    .put("previousMetadataExisted", false)
                    .toString(),
            ).commit()
        )
        val worker = buildWorker(
            File(context.cacheDir, "unused-cleanup-source.mp4"),
            "Cancelled cleanup",
            extractArtwork = true,
            workId = workId,
        )

        try {
            val cancelledOwner = launch {
                try {
                    awaitCancellation()
                } finally {
                    worker.cleanupPublishedImport(
                        publishedUri = null,
                        publishedSongId = songId,
                        savedArtworkUri = artwork.toUri(),
                    )
                }
            }
            yield()
            cancelledOwner.cancelAndJoin()

            assertFalse(metadata.getAll().containsKey(songId))
            assertFalse(artwork.exists())
            assertFalse(completionPreferences.contains("worker:$workId"))
        } finally {
            metadata.delete(songId)
            artwork.delete()
        }
    }

    @Test
    fun completedPublicationIsReusedByTheSameWorkerIdWithoutASecondExport() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val workId = UUID.randomUUID()
        val title = "Idempotent ${workId.toString().take(8)}"
        val first = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = workId,
        ).doWork()

        assertTrue(first is ListenableWorker.Result.Success)
        first as ListenableWorker.Result.Success
        val firstUri = requireNotNull(
            first.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI)
        )
        val firstSongId = first.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += Uri.parse(firstUri)
        publishedSongIds += firstSongId

        // Simulate process death after MediaStore publication but before the final completion
        // stage could be committed. The source is then removed to prove the replay never reads or
        // transforms it again.
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val completionKey = "worker:$workId"
        val preparedRecord = JSONObject(requireNotNull(completionPreferences.getString(completionKey, null)))
            .put("state", "PREPARED")
            .toString()
        assertTrue(completionPreferences.edit().putString(completionKey, preparedRecord).commit())
        assertTrue(source.delete())

        val replay = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = workId,
        ).doWork()

        assertTrue(replay is ListenableWorker.Result.Success)
        replay as ListenableWorker.Result.Success
        assertEquals(firstUri, replay.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        assertEquals(
            firstSongId,
            replay.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L),
        )
        assertEquals(
            first.outputData.getInt(
                WorkManagerVideoAudioExtractor.KEY_AUDIO_CONVERSION_PROCESS,
                ExportResult.CONVERSION_PROCESS_NA,
            ),
            replay.outputData.getInt(
                WorkManagerVideoAudioExtractor.KEY_AUDIO_CONVERSION_PROCESS,
                ExportResult.CONVERSION_PROCESS_NA,
            ),
        )
        val matchingRows = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.TITLE} = ?",
            arrayOf(title),
            null,
        )?.use { it.count } ?: 0
        assertEquals(1, matchingRows)
    }

    @Test
    fun revokedDisplayNameQueryReturnsAnExplicitFailure() = runBlocking {
        val source = File(context.cacheDir, "revoked-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val throwingFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun queryDisplayName(uri: Uri): String? {
                    throw SecurityException("persisted document grant was revoked")
                }
            }
        }

        try {
            val result = buildWorker(
                source = source,
                title = "Revoked document",
                extractArtwork = false,
                workerFactory = throwingFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            result as ListenableWorker.Result.Failure
            assertEquals(
                "无法访问所选视频，请重新选择",
                result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR),
            )
            assertNull(result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        } finally {
            source.delete()
        }
    }

    @Test
    fun videoCleanupDoesNotDeletePlaylistRelocationPendingRows() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/"
        val relocationTitle = "yinlan-pending-move:test-operation:42"
        val relocationUri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "relocation-${System.nanoTime()}.m4a")
                    put(MediaStore.Audio.Media.TITLE, relocationTitle)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        val source = copyAsset("video_without_audio.mp4")

        try {
            val result = buildWorker(source, "Cleanup isolation", extractArtwork = false).doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
            val remaining = context.contentResolver.query(
                relocationUri,
                arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getString(0) == relocationTitle &&
                    cursor.getInt(1) == 1
            } == true
            assertTrue(remaining)
        } finally {
            runCatching { context.contentResolver.delete(relocationUri, null, null) }
            source.delete()
        }
    }

    @Test
    fun legacyNoReplaceAndRecoveryNeverDeletesARacingTarget() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/视频提取",
        ).apply { mkdirs() }
        val ownerId = UUID.randomUUID()
        val staging = File(directory, ".$ownerId.yinlan-pending")
        val target = File(directory, "race-${System.nanoTime()}.m4a")
        val ownedBytes = "worker-owned-output".toByteArray()
        val preferences = context.getSharedPreferences(
            "video_audio_legacy_pending",
            Context.MODE_PRIVATE,
        )
        val source = copyAsset("video_without_audio.mp4")

        try {
            staging.writeBytes(ownedBytes)
            // Identical bytes deliberately defeat size/digest as an ownership discriminator. The
            // still-present worker temp proves no-replace never installed the target.
            target.writeBytes(ownedBytes)
            assertFalse(moveLegacyFileNoReplace(staging, target))
            assertTrue(staging.exists())
            assertTrue(target.readBytes().contentEquals(ownedBytes))

            val recovery = JSONObject()
                .put("workerId", ownerId.toString())
                .put("stage", "STAGING_VERIFIED")
                .put("temporaryPath", staging.absolutePath)
                .put("targetPath", target.absolutePath)
                .put("expectedSize", ownedBytes.size)
                .put("expectedSha256", sha256Hex(ownedBytes))
                .toString()
            assertTrue(preferences.edit().putString("record_v2", recovery).commit())

            val result = buildWorker(source, "Legacy race cleanup", extractArtwork = false).doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
            assertTrue(target.readBytes().contentEquals(ownedBytes))
            assertFalse(staging.exists())
            assertNull(preferences.getString("record_v2", null))
        } finally {
            preferences.edit().clear().commit()
            staging.delete()
            target.delete()
            source.delete()
        }
    }

    @Test
    fun legacyConditionalDeleteFailurePreservesRowFileAndJournal() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/视频提取",
        ).apply { mkdirs() }
        val workId = UUID.randomUUID()
        val temporary = File(directory, ".$workId.yinlan-pending")
        val target = File(directory, "conditional-${System.nanoTime()}.m4a")
        val bytes = "owned legacy media".toByteArray()
        target.writeBytes(bytes)
        @Suppress("DEPRECATION")
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DATA, target.absolutePath)
                    put(MediaStore.Audio.Media.DISPLAY_NAME, target.name)
                    put(MediaStore.Audio.Media.TITLE, "Conditional legacy delete")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                },
            )
        )
        val songId = android.content.ContentUris.parseId(uri)
        val preferences = context.getSharedPreferences(
            "video_audio_legacy_pending",
            Context.MODE_PRIVATE,
        )
        val recovery = JSONObject()
            .put("workerId", workId.toString())
            .put("stage", "MEDIA_INDEXED")
            .put("temporaryPath", temporary.absolutePath)
            .put("targetPath", target.absolutePath)
            .put("outputUri", uri.toString())
            .put("songId", songId)
            .put("expectedSize", bytes.size)
            .put("expectedSha256", sha256Hex(bytes))
            .toString()
        assertTrue(preferences.edit().putString("record_v2", recovery).commit())
        var conditionalPath: String? = null
        val deleteFailureFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun deleteLegacyVideoMediaStoreRow(
                    uri: Uri,
                    expectedPath: String,
                ): Int {
                    conditionalPath = expectedPath
                    return 0
                }
            }
        }
        val source = copyAsset("video_without_audio.mp4")

        try {
            val result = buildWorker(
                source = source,
                title = "Legacy conditional cleanup",
                extractArtwork = false,
                workId = workId,
                workerFactory = deleteFailureFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(target.absolutePath, conditionalPath)
            assertTrue(target.readBytes().contentEquals(bytes))
            val rowStillExists = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertTrue(rowStillExists)
            assertEquals(recovery, preferences.getString("record_v2", null))
        } finally {
            preferences.edit().clear().commit()
            runCatching { context.contentResolver.delete(uri, null, null) }
            temporary.delete()
            target.delete()
            source.delete()
        }
    }

    @Test
    fun legacyCancellationWaitsForDelayedScannerAndLeavesNoLateOrphan() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val source = copyAsset("video_with_aac.mp4")
        val scanRequest = CompletableDeferred<Pair<File, (Uri?) -> Unit>>()
        val delayedScannerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun requestLegacyMediaScan(file: File, callback: (Uri?) -> Unit) {
                    scanRequest.complete(file to callback)
                }
            }
        }
        val worker = buildWorker(
            source = source,
            title = "Delayed legacy cancel ${System.nanoTime()}",
            extractArtwork = false,
            workerFactory = delayedScannerFactory,
        )
        val task = async(Dispatchers.Default) { worker.doWork() }
        var indexedUri: Uri? = null

        try {
            val (target, callback) = withTimeout(60_000L) { scanRequest.await() }
            assertTrue(target.isFile)
            val marker = context.getSharedPreferences(
                "video_audio_legacy_pending",
                Context.MODE_PRIVATE,
            ).getString("record_v2", null)
            assertEquals("TARGET_READY", JSONObject(requireNotNull(marker)).getString("stage"))

            task.cancel()
            delay(100L)
            assertFalse(task.isCompleted)
            @Suppress("DEPRECATION")
            val inserted = requireNotNull(
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DATA, target.absolutePath)
                        put(MediaStore.Audio.Media.DISPLAY_NAME, target.name)
                        put(MediaStore.Audio.Media.TITLE, "Delayed legacy cancel")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    },
                )
            )
            indexedUri = inserted
            callback(inserted)
            task.join()

            assertFalse(target.exists())
            val rowStillExists = context.contentResolver.query(
                inserted,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
            assertNull(
                context.getSharedPreferences("video_audio_legacy_pending", Context.MODE_PRIVATE)
                    .getString("record_v2", null)
            )
        } finally {
            task.cancelAndJoin()
            indexedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            source.delete()
        }
    }

    @Test
    fun legacyTargetReadyRestartRescansBeforeDeletingTheDurableTarget() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/视频提取",
        ).apply { mkdirs() }
        val interruptedId = UUID.randomUUID()
        val temporary = File(directory, ".$interruptedId.yinlan-pending")
        val target = File(directory, "restart-scan-${System.nanoTime()}.m4a")
        val bytes = "durable target awaiting scanner".toByteArray()
        target.writeBytes(bytes)
        val marker = JSONObject()
            .put("workerId", interruptedId.toString())
            .put("stage", "TARGET_READY")
            .put("temporaryPath", temporary.absolutePath)
            .put("targetPath", target.absolutePath)
            .put("expectedSize", bytes.size)
            .put("expectedSha256", sha256Hex(bytes))
            .toString()
        val markerPreferences = context.getSharedPreferences(
            "video_audio_legacy_pending",
            Context.MODE_PRIVATE,
        )
        assertTrue(markerPreferences.edit().putString("record_v2", marker).commit())
        val scanRequest = CompletableDeferred<Pair<File, (Uri?) -> Unit>>()
        val delayedScannerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun requestLegacyMediaScan(file: File, callback: (Uri?) -> Unit) {
                    scanRequest.complete(file to callback)
                }
            }
        }
        val source = copyAsset("video_without_audio.mp4")
        val worker = buildWorker(
            source = source,
            title = "Restart delayed scanner",
            extractArtwork = false,
            workerFactory = delayedScannerFactory,
        )
        val task = async(Dispatchers.Default) { worker.doWork() }
        var indexedUri: Uri? = null

        try {
            val (rescannedTarget, callback) = withTimeout(30_000L) { scanRequest.await() }
            assertEquals(target.canonicalFile, rescannedTarget.canonicalFile)
            assertTrue(target.exists())
            @Suppress("DEPRECATION")
            val inserted = requireNotNull(
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DATA, target.absolutePath)
                        put(MediaStore.Audio.Media.DISPLAY_NAME, target.name)
                        put(MediaStore.Audio.Media.TITLE, "Restart delayed scanner")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    },
                )
            )
            indexedUri = inserted
            callback(inserted)
            val result = withTimeout(30_000L) { task.await() }

            assertTrue(result is ListenableWorker.Result.Failure)
            assertFalse(target.exists())
            val rowStillExists = context.contentResolver.query(
                inserted,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
            assertNull(markerPreferences.getString("record_v2", null))
        } finally {
            task.cancelAndJoin()
            indexedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            markerPreferences.edit().clear().commit()
            temporary.delete()
            target.delete()
            source.delete()
        }
    }

    @Test
    fun legacyCancelledScanTimesOutThenLateCallbackRemainsRecoverable() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val source = copyAsset("video_with_aac.mp4")
        val scanRequest = CompletableDeferred<Pair<File, (Uri?) -> Unit>>()
        val timeoutScannerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun legacyMediaScanTimeoutMillis(): Long = 200L

                override fun requestLegacyMediaScan(file: File, callback: (Uri?) -> Unit) {
                    scanRequest.complete(file to callback)
                }
            }
        }
        val worker = buildWorker(
            source = source,
            title = "Timed out legacy scan ${System.nanoTime()}",
            extractArtwork = false,
            workerFactory = timeoutScannerFactory,
        )
        val task = async(Dispatchers.Default) { worker.doWork() }
        val markerPreferences = context.getSharedPreferences(
            "video_audio_legacy_pending",
            Context.MODE_PRIVATE,
        )
        var indexedUri: Uri? = null
        var target: File? = null
        val cleanupSource = copyAsset("video_without_audio.mp4")

        try {
            val (requestedTarget, callback) = withTimeout(60_000L) { scanRequest.await() }
            target = requestedTarget
            task.cancel()
            withTimeout(5_000L) { task.join() }

            assertTrue(requestedTarget.isFile)
            assertEquals(
                "TARGET_READY",
                JSONObject(
                    requireNotNull(markerPreferences.getString("record_v2", null))
                ).getString("stage"),
            )
            @Suppress("DEPRECATION")
            val inserted = requireNotNull(
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DATA, requestedTarget.absolutePath)
                        put(MediaStore.Audio.Media.DISPLAY_NAME, requestedTarget.name)
                        put(MediaStore.Audio.Media.TITLE, "Late scanner callback")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    },
                )
            )
            indexedUri = inserted
            callback(inserted)
            assertEquals(
                "MEDIA_INDEXED",
                JSONObject(
                    requireNotNull(markerPreferences.getString("record_v2", null))
                ).getString("stage"),
            )
            assertTrue(requestedTarget.exists())

            val cleanup = buildWorker(
                source = cleanupSource,
                title = "Cleanup late scanner",
                extractArtwork = false,
            ).doWork()
            assertTrue(cleanup is ListenableWorker.Result.Failure)
            assertFalse(requestedTarget.exists())
            assertNull(markerPreferences.getString("record_v2", null))
            val rowStillExists = context.contentResolver.query(
                inserted,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
        } finally {
            task.cancelAndJoin()
            indexedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            markerPreferences.edit().clear().commit()
            target?.delete()
            source.delete()
            cleanupSource.delete()
        }
    }

    @Test
    fun legacyFinalizeRefusesScannerUriWhoseDataPathChanged() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val source = copyAsset("video_with_aac.mp4")
        val workId = UUID.randomUUID()
        var finalUpdateCalled = false
        var outputUri: Uri? = null
        var songId: Long? = null
        var target: File? = null
        val bindingRaceFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override suspend fun beforeFinalizePublishedAudio() {
                    val marker = JSONObject(
                        requireNotNull(
                            context.getSharedPreferences(
                                "video_audio_legacy_pending",
                                Context.MODE_PRIVATE,
                            ).getString("record_v2", null)
                        )
                    )
                    val uri = Uri.parse(marker.getString("outputUri"))
                    val originalTarget = File(marker.getString("targetPath"))
                    outputUri = uri
                    songId = marker.getLong("songId")
                    target = originalTarget
                    @Suppress("DEPRECATION")
                    assertEquals(
                        1,
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply {
                                put(
                                    MediaStore.Audio.Media.DATA,
                                    File(originalTarget.parentFile, "reused-${System.nanoTime()}.m4a")
                                        .absolutePath,
                                )
                            },
                            null,
                            null,
                        )
                    )
                }

                override fun updateLegacyVideoMediaStoreRow(
                    uri: Uri,
                    values: ContentValues,
                    expectedPath: String,
                ): Int {
                    finalUpdateCalled = true
                    return super.updateLegacyVideoMediaStoreRow(uri, values, expectedPath)
                }
            }
        }

        try {
            val result = buildWorker(
                source = source,
                title = "Legacy DATA binding",
                extractArtwork = false,
                workId = workId,
                workerFactory = bindingRaceFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            assertFalse(finalUpdateCalled)
            val receipt = JSONObject(
                requireNotNull(
                    context.getSharedPreferences(
                        "video_audio_completed_imports",
                        Context.MODE_PRIVATE,
                    ).getString("worker:$workId", null)
                )
            )
            assertEquals("PREPARED", receipt.getString("state"))
            assertTrue(target?.isFile == true)
        } finally {
            outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            songId?.let {
                RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
                    .delete(it)
            }
            target?.delete()
            context.getSharedPreferences("video_audio_completed_imports", Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("video_audio_legacy_pending", Context.MODE_PRIVATE)
                .edit().clear().commit()
            source.delete()
        }
    }

    @Test
    fun publishedReceiptCommitFailureFailsForwardAndNextWorkerAdoptsOutput() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val firstWorkId = UUID.randomUUID()
        val secondWorkId = UUID.randomUUID()
        val title = "Commit fail-forward ${firstWorkId.toString().take(8)}"
        val failPublishedCommitFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun commitCompletedImportReceipt(key: String, encoded: String): Boolean {
                    return if (JSONObject(encoded).getString("state") == "PUBLISHED") {
                        false
                    } else {
                        super.commitCompletedImportReceipt(key, encoded)
                    }
                }
            }
        }

        val first = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = firstWorkId,
            workerFactory = failPublishedCommitFactory,
        ).doWork()
        assertTrue(first is ListenableWorker.Result.Success)
        first as ListenableWorker.Result.Success
        val outputUri = Uri.parse(
            requireNotNull(first.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        )
        val songId = first.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += outputUri
        publishedSongIds += songId
        val preferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        assertEquals(
            "PREPARED",
            JSONObject(requireNotNull(preferences.getString("worker:$firstWorkId", null)))
                .getString("state"),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = context.contentResolver.query(
                outputUri,
                arrayOf(MediaStore.Audio.Media.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 } ?: true
            assertFalse(pending)
        }
        assertTrue(source.delete())

        val adopted = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = secondWorkId,
        ).doWork()
        assertTrue(adopted is ListenableWorker.Result.Success)
        adopted as ListenableWorker.Result.Success
        assertEquals(outputUri.toString(), adopted.outputData.getString(
            WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI
        ))
        assertEquals(songId, adopted.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L))
        val matchingRows = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.TITLE} = ?",
            arrayOf(title),
            null,
        )?.use { it.count } ?: 0
        assertEquals(1, matchingRows)
    }

    @Test
    fun cancellationAfterPreparedPublishesOnceAndRetryAdoptsTheVerifiedReceipt() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val firstWorkId = UUID.randomUUID()
        val interveningWorkId = UUID.randomUUID()
        val secondWorkId = UUID.randomUUID()
        val title = "Prepared cancel ${firstWorkId.toString().take(8)}"
        val preparedReached = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val blockingFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override suspend fun beforeFinalizePublishedAudio() {
                    preparedReached.complete(Unit)
                    releasePublication.await()
                }
            }
        }
        val firstWorker = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = firstWorkId,
            workerFactory = blockingFactory,
        )
        val firstTask = async(Dispatchers.Default) { firstWorker.doWork() }
        val workPreferences = context.getSharedPreferences(
            "video_audio_import",
            Context.MODE_PRIVATE,
        )
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )

        try {
            withTimeout(60_000L) { preparedReached.await() }
            val prepared = JSONObject(
                requireNotNull(completionPreferences.getString("worker:$firstWorkId", null))
            )
            assertEquals("PREPARED", prepared.getString("state"))
            assertTrue(
                workPreferences.edit()
                    .putBoolean(videoImportCancelRequestKey(firstWorkId), true)
                    .commit()
            )

            firstTask.cancel()
            releasePublication.countDown()
            firstTask.join()

            val reconciled = JSONObject(
                requireNotNull(completionPreferences.getString("worker:$firstWorkId", null))
            )
            assertEquals("PUBLISHED", reconciled.getString("state"))
            val firstUri = Uri.parse(reconciled.getString("outputUri"))
            val firstSongId = reconciled.getLong("songId")
            publishedUris += firstUri
            publishedSongIds += firstSongId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = context.contentResolver.query(
                    firstUri,
                    arrayOf(MediaStore.Audio.Media.IS_PENDING),
                    null,
                    null,
                    null,
                )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 } ?: true
                assertFalse(pending)
            }

            assertTrue(source.delete())
            val intervening = buildWorker(
                source = source,
                title = "$title unrelated request",
                extractArtwork = false,
                workId = interveningWorkId,
            ).doWork()
            assertTrue(intervening is ListenableWorker.Result.Failure)
            // A different request may finish A's public row but must not consume A's unresolved
            // identity. C still needs it to adopt A rather than exporting "(2)".
            assertTrue(workPreferences.contains(videoImportCancelRequestKey(firstWorkId)))
            assertTrue(
                workPreferences.edit()
                    .putBoolean(videoImportCancelRequestKey(interveningWorkId), true)
                    .commit()
            )
            val retry = buildWorker(
                source = source,
                title = title,
                extractArtwork = false,
                workId = secondWorkId,
            ).doWork()

            assertTrue(retry is ListenableWorker.Result.Success)
            retry as ListenableWorker.Result.Success
            assertEquals(
                firstUri.toString(),
                retry.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI),
            )
            assertEquals(
                firstSongId,
                retry.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L),
            )
            val matchingRows = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.TITLE} = ?",
                arrayOf(title),
                null,
            )?.use { it.count } ?: 0
            assertEquals(1, matchingRows)
            // Direct worker execution has not passed through the UI-facing currentState()
            // reconciliation yet, so both independent cancellation keys remain durable.
            assertTrue(workPreferences.contains(videoImportCancelRequestKey(firstWorkId)))
            assertTrue(workPreferences.contains(videoImportCancelRequestKey(interveningWorkId)))
        } finally {
            releasePublication.countDown()
            firstTask.cancelAndJoin()
            source.delete()
        }
    }

    @Test
    fun failedPendingDeleteKeepsMediaMetadataArtworkAndReceipt() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val workId = UUID.randomUUID()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "delete-failure-${System.nanoTime()}.m4a")
                    put(MediaStore.Audio.Media.TITLE, "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$workId")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/",
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3)) }
        val songId = android.content.ContentUris.parseId(uri)
        val artwork = File(context.filesDir, "artwork/delete-failure-$songId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        metadata.put(
            SongMetadataOverride(
                songId = songId,
                title = "Delete failure",
                artist = "Test artist",
                album = "Test album",
                artworkPath = artwork.toUri().toString(),
            )
        )
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val receipt = JSONObject()
            .put("workerId", workId.toString())
            .put("state", "ALLOCATED")
            .put("outputUri", uri.toString())
            .put("songId", songId)
            .put("audioConversionProcess", ExportResult.CONVERSION_PROCESS_TRANSMUXED)
            .put("createdAtMillis", System.currentTimeMillis())
            .put("requestFingerprint", "a".repeat(64))
            .toString()
        assertTrue(completionPreferences.edit().putString("worker:$workId", receipt).commit())
        val deleteFailureFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun deletePendingVideoMediaStoreRow(
                    uri: Uri,
                    expectedTitle: String,
                ): Int = 0
            }
        }
        val worker = buildWorker(
            source = File(context.cacheDir, "unused-delete-failure.mp4"),
            title = "Delete failure",
            extractArtwork = false,
            workId = workId,
            workerFactory = deleteFailureFactory,
        )

        try {
            worker.cleanupPublishedImport(uri, songId, artwork.toUri())

            val rowStillExists = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertTrue(rowStillExists)
            assertTrue(metadata.getAll().containsKey(songId))
            assertTrue(artwork.exists())
            assertEquals(receipt, completionPreferences.getString("worker:$workId", null))
        } finally {
            runCatching { context.contentResolver.delete(uri, null, null) }
            metadata.delete(songId)
            artwork.delete()
            completionPreferences.edit().clear().commit()
        }
    }

    @Test
    fun pendingCleanupCannotDeleteARowPublishedAfterItsQuery() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val workId = UUID.randomUUID()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "publish-race-${System.nanoTime()}.m4a")
                    put(MediaStore.Audio.Media.TITLE, "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$workId")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/",
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3)) }
        val songId = android.content.ContentUris.parseId(uri)
        val artwork = File(context.filesDir, "artwork/publish-race-$songId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        metadata.put(
            SongMetadataOverride(
                songId = songId,
                title = "Published race",
                artist = "Test artist",
                album = "Test album",
                artworkPath = artwork.toUri().toString(),
            )
        )
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val receiptKey = "worker:$workId"
        val allocatedReceipt = JSONObject()
            .put("workerId", workId.toString())
            .put("state", "ALLOCATED")
            .put("outputUri", uri.toString())
            .put("songId", songId)
            .put("audioConversionProcess", ExportResult.CONVERSION_PROCESS_TRANSMUXED)
            .put("createdAtMillis", System.currentTimeMillis())
            .put("requestFingerprint", "c".repeat(64))
        assertTrue(
            completionPreferences.edit()
                .putString(receiptKey, allocatedReceipt.toString())
                .commit()
        )
        val publishedTitle = "Published while cleanup waited"
        val publishRaceFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun deletePendingVideoMediaStoreRow(
                    uri: Uri,
                    expectedTitle: String,
                ): Int {
                    assertTrue(
                        completionPreferences.edit()
                            .putString(
                                receiptKey,
                                JSONObject(allocatedReceipt.toString())
                                    .put("state", "PREPARED")
                                    .toString(),
                            )
                            .commit()
                    )
                    assertEquals(
                        1,
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.Audio.Media.TITLE, publishedTitle)
                                put(MediaStore.Audio.Media.IS_PENDING, 0)
                            },
                            null,
                            null,
                        )
                    )
                    return super.deletePendingVideoMediaStoreRow(uri, expectedTitle)
                }
            }
        }
        val worker = buildWorker(
            source = File(context.cacheDir, "unused-publish-race.mp4"),
            title = "Published race",
            extractArtwork = false,
            workId = workId,
            workerFactory = publishRaceFactory,
        )

        try {
            worker.cleanupPublishedImport(uri, songId, artwork.toUri())

            val state = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getInt(1) else null
            }
            assertEquals(publishedTitle to 0, state)
            assertTrue(metadata.getAll().containsKey(songId))
            assertTrue(artwork.exists())
            assertEquals(
                "PREPARED",
                JSONObject(requireNotNull(completionPreferences.getString(receiptKey, null)))
                    .getString("state"),
            )
        } finally {
            runCatching { context.contentResolver.delete(uri, null, null) }
            metadata.delete(songId)
            artwork.delete()
            completionPreferences.edit().clear().commit()
        }
    }

    @Test
    fun shortModernMediaStoreWriteFailsAndRemovesThePendingRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val source = copyAsset("video_with_aac.mp4")
        var shortWriteUri: Uri? = null
        val shortWriteFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override suspend fun copyModernAudioOutput(source: File, uri: Uri) {
                    shortWriteUri = uri
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        output.write(0)
                    } ?: error("test could not open pending output")
                }
            }
        }

        try {
            val result = buildWorker(
                source = source,
                title = "Short write ${System.nanoTime()}",
                extractArtwork = false,
                workerFactory = shortWriteFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            result as ListenableWorker.Result.Failure
            assertEquals(
                "系统音乐库中的音频写入不完整",
                result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR),
            )
            val uri = requireNotNull(shortWriteUri)
            val rowStillExists = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
        } finally {
            shortWriteUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            source.delete()
        }
    }

    @Test
    fun secondaryVolumeIdCollisionRemovesOnlyTheNewPendingRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertModernVolumeLookupFailurePreservesExistingMetadata(
            secondaryLookup = VideoSongIdLookup.PRESENT,
            expectedError = "多个存储卷存在相同歌曲编号，未写入自定义元数据",
        )
    }

    @Test
    fun inaccessibleSecondaryVolumeRemovesOnlyTheNewPendingRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertModernVolumeLookupFailurePreservesExistingMetadata(
            secondaryLookup = VideoSongIdLookup.INACCESSIBLE,
            expectedError = "无法确认所有存储卷中的歌曲编号，未写入自定义元数据",
        )
    }

    @Test
    fun cancellationAfterModernInsertImmediatelyCleansTheAllocatedRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val source = copyAsset("video_with_aac.mp4")
        val copyStarted = CompletableDeferred<Uri>()
        val releaseCopy = CountDownLatch(1)
        var allocatedUri: Uri? = null
        val blockingCopyFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override suspend fun copyModernAudioOutput(source: File, uri: Uri) {
                    allocatedUri = uri
                    copyStarted.complete(uri)
                    releaseCopy.await()
                    super.copyModernAudioOutput(source, uri)
                }
            }
        }
        val worker = buildWorker(
            source = source,
            title = "Cancel after insert ${System.nanoTime()}",
            extractArtwork = false,
            workerFactory = blockingCopyFactory,
        )
        val task = async(Dispatchers.Default) { worker.doWork() }

        try {
            val allocatedUri = withTimeout(30_000L) { copyStarted.await() }
            task.cancel()
            releaseCopy.countDown()
            task.join()

            val rowStillExists = context.contentResolver.query(
                allocatedUri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
        } finally {
            releaseCopy.countDown()
            task.cancelAndJoin()
            allocatedUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            source.delete()
        }
    }

    @Test
    fun completedReceiptRejectsATruncatedOutputBeforeReplay() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val workId = UUID.randomUUID()
        val title = "Receipt integrity ${workId.toString().take(8)}"
        val first = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = workId,
        ).doWork()
        assertTrue(first is ListenableWorker.Result.Success)
        first as ListenableWorker.Result.Success
        val uri = Uri.parse(
            requireNotNull(first.outputData.getString(WorkManagerVideoAudioExtractor.KEY_OUTPUT_URI))
        )
        val songId = first.outputData.getLong(WorkManagerVideoAudioExtractor.KEY_SONG_ID, -1L)
        publishedUris += uri
        publishedSongIds += songId
        val preferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val key = "worker:$workId"
        val prepared = JSONObject(requireNotNull(preferences.getString(key, null)))
            .put("state", "PREPARED")
        assertTrue(prepared.getLong("expectedSize") > 1L)
        assertEquals(64, prepared.getString("expectedSha256").length)
        assertTrue(preferences.edit().putString(key, prepared.toString()).commit())
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(0) }
            ?: error("test could not truncate published output")
        assertTrue(source.delete())

        val replay = buildWorker(
            source = source,
            title = title,
            extractArtwork = false,
            workId = workId,
        ).doWork()

        assertTrue(replay is ListenableWorker.Result.Failure)
        replay as ListenableWorker.Result.Failure
        assertEquals(
            "已导出的歌曲内容校验失败，已保留恢复记录",
            replay.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR),
        )
        assertEquals("PREPARED", JSONObject(requireNotNull(preferences.getString(key, null)))
            .getString("state"))
    }

    @Test
    fun metadataIntentRestoresPreviousOverrideWhenDaoCommitPrecedesFailure() = runBlocking {
        val source = copyAsset("video_with_aac.mp4")
        val workId = UUID.randomUUID()
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        var ownedSongId: Long? = null
        var previous: SongMetadataOverride? = null
        var previousArtwork: File? = null
        val crashAfterCommitFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override suspend fun beforeMetadataOwnershipIntent(songId: Long) {
                    val artwork = File(
                        context.filesDir,
                        "artwork/previous-metadata-$songId.jpg",
                    ).apply {
                        parentFile?.mkdirs()
                        writeBytes(byteArrayOf(3, 1, 4, 1, 5))
                    }
                    val old = SongMetadataOverride(
                        songId = songId,
                        title = "Previous title",
                        artist = "Previous artist",
                        album = "Previous album",
                        artworkPath = artwork.toUri().toString(),
                    )
                    metadata.put(old)
                    ownedSongId = songId
                    previous = old
                    previousArtwork = artwork
                }

                override suspend fun afterMetadataOverrideCommitted() {
                    throw java.io.IOException("simulated crash after DAO commit")
                }
            }
        }

        try {
            val result = buildWorker(
                source = source,
                title = "Replacement metadata",
                extractArtwork = false,
                workId = workId,
                workerFactory = crashAfterCommitFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            result as ListenableWorker.Result.Failure
            assertEquals(
                "simulated crash after DAO commit",
                result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR),
            )
            val capturedSongId = requireNotNull(ownedSongId)
            assertEquals(previous, metadata.getAll()[capturedSongId])
            assertTrue(previousArtwork?.isFile == true)
            assertNull(
                context.getSharedPreferences("video_audio_completed_imports", Context.MODE_PRIVATE)
                    .getString("worker:$workId", null)
            )
        } finally {
            ownedSongId?.let { metadata.delete(it) }
            previousArtwork?.delete()
            source.delete()
        }
    }

    @Test
    fun allocatedPendingReceiptIsFullyRecoveredBeforeTheNextImport() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val interruptedWorkId = UUID.randomUUID()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "allocated-${System.nanoTime()}.m4a")
                    put(
                        MediaStore.Audio.Media.TITLE,
                        "$VIDEO_IMPORT_PENDING_TITLE_PREFIX$interruptedWorkId",
                    )
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/音澜/视频提取/",
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3)) }
        val songId = android.content.ContentUris.parseId(uri)
        val artwork = File(
            context.filesDir,
            "artwork/$songId-video-$interruptedWorkId.jpg",
        ).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        metadata.put(
            SongMetadataOverride(
                songId = songId,
                title = "Interrupted allocation",
                artist = "Test artist",
                album = "Test album",
                artworkPath = artwork.toUri().toString(),
            )
        )
        val completionPreferences = context.getSharedPreferences(
            "video_audio_completed_imports",
            Context.MODE_PRIVATE,
        )
        val receipt = JSONObject()
            .put("workerId", interruptedWorkId.toString())
            .put("state", "ALLOCATED")
            .put("outputUri", uri.toString())
            .put("songId", songId)
            .put("audioConversionProcess", ExportResult.CONVERSION_PROCESS_TRANSMUXED)
            .put("createdAtMillis", System.currentTimeMillis())
            .put("requestFingerprint", "b".repeat(64))
            .put("metadataWrittenByWorker", true)
            .put("artworkUri", artwork.toUri().toString())
            .put("metadataTitle", "Interrupted allocation")
            .put("metadataArtist", "Test artist")
            .put("metadataAlbum", "Test album")
            .toString()
        assertTrue(
            completionPreferences.edit()
                .putString("worker:$interruptedWorkId", receipt)
                .commit()
        )
        val source = copyAsset("video_without_audio.mp4")

        try {
            val result = buildWorker(
                source = source,
                title = "Recover allocation",
                extractArtwork = false,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            val rowStillExists = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(rowStillExists)
            assertFalse(metadata.getAll().containsKey(songId))
            assertFalse(artwork.exists())
            assertNull(completionPreferences.getString("worker:$interruptedWorkId", null))
        } finally {
            runCatching { context.contentResolver.delete(uri, null, null) }
            metadata.delete(songId)
            artwork.delete()
            completionPreferences.edit().clear().commit()
            source.delete()
        }
    }

    private suspend fun assertModernVolumeLookupFailurePreservesExistingMetadata(
        secondaryLookup: VideoSongIdLookup,
        expectedError: String,
    ) {
        val source = copyAsset("video_with_aac.mp4")
        val metadata = RoomSongMetadataStore(PlaylistDatabase.getInstance(context).songStateDao())
        val workId = UUID.randomUUID()
        var insertedSongId: Long? = null
        var existingOverride: SongMetadataOverride? = null
        var existingArtwork: File? = null
        val lookupFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : VideoAudioImportWorker(appContext, workerParameters) {
                override fun currentModernAudioVolumes(): Set<String> = linkedSetOf(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    "test-secondary",
                )

                override fun queryModernAudioSongId(
                    volumeName: String,
                    songId: Long,
                ): VideoSongIdLookup {
                    insertedSongId = songId
                    if (existingOverride == null) {
                        val artwork = File(
                            context.filesDir,
                            "artwork/cross-volume-existing-$songId.jpg",
                        ).apply {
                            parentFile?.mkdirs()
                            writeBytes(byteArrayOf(9, 8, 7))
                        }
                        existingArtwork = artwork
                        val override = SongMetadataOverride(
                            songId = songId,
                            title = "Existing secondary metadata",
                            artist = "Existing artist",
                            album = "Existing album",
                            artworkPath = artwork.toUri().toString(),
                        )
                        runBlocking { metadata.put(override) }
                        existingOverride = override
                    }
                    return if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        VideoSongIdLookup.PRESENT
                    } else {
                        secondaryLookup
                    }
                }
            }
        }

        try {
            val result = buildWorker(
                source = source,
                title = "Volume collision ${System.nanoTime()}",
                extractArtwork = false,
                workId = workId,
                workerFactory = lookupFactory,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            result as ListenableWorker.Result.Failure
            assertEquals(
                expectedError,
                result.outputData.getString(WorkManagerVideoAudioExtractor.KEY_ERROR),
            )
            val songId = requireNotNull(insertedSongId)
            val primaryUri = android.content.ContentUris.withAppendedId(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                songId,
            )
            val primaryStillExists = context.contentResolver.query(
                primaryUri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            assertFalse(primaryStillExists)
            assertEquals(existingOverride, metadata.getAll()[songId])
            assertTrue(existingArtwork?.isFile == true)
            assertNull(
                context.getSharedPreferences("video_audio_completed_imports", Context.MODE_PRIVATE)
                    .getString("worker:$workId", null)
            )
        } finally {
            insertedSongId?.let { metadata.delete(it) }
            existingArtwork?.delete()
            source.delete()
        }
    }

    private fun buildWorker(
        source: File,
        title: String,
        extractArtwork: Boolean,
        workId: UUID = UUID.randomUUID(),
        workerFactory: WorkerFactory? = null,
    ): VideoAudioImportWorker {
        val builder = TestListenableWorkerBuilder<VideoAudioImportWorker>(context)
            .setId(workId)
            .setInputData(
                workDataOf(
                    WorkManagerVideoAudioExtractor.KEY_SOURCE_URI to source.toURI().toString(),
                    WorkManagerVideoAudioExtractor.KEY_TITLE to title,
                    WorkManagerVideoAudioExtractor.KEY_ARTIST to "Test artist",
                    WorkManagerVideoAudioExtractor.KEY_ALBUM to "Test album",
                    WorkManagerVideoAudioExtractor.KEY_EXTRACT_ARTWORK to extractArtwork,
                )
            )
        workerFactory?.let(builder::setWorkerFactory)
        return builder.build()
    }

    private fun copyAsset(name: String): File {
        val target = File(context.cacheDir, "${System.nanoTime()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
