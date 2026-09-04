package com.melody.local.media

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.melody.local.data.MoveItemRecord
import com.melody.local.data.MoveItemStatus
import com.melody.local.data.MoveJournalStore
import com.melody.local.data.MoveOperationRecord
import com.melody.local.data.MoveOperationStatus
import com.melody.local.data.PlaylistStore
import com.melody.local.data.PlaylistSummary
import com.melody.local.data.SongMetadataOverride
import com.melody.local.data.SongMetadataStore
import com.melody.local.lyrics.LyricsStore
import com.melody.local.lyrics.ParsedLyrics
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongRelocationCoordinatorInstrumentedTest {
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
    private val cleanupUris = mutableListOf<Uri>()
    private val cleanupDirectories = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        cleanupUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        cleanupDirectories.asReversed().forEach { directory -> runCatching { directory.delete() } }
    }

    @Test
    fun realMediaStoreMovePreservesIdAndPlayableBytes() = runBlocking {
        val token = UUID.randomUUID().toString().take(8)
        val sourceFolder = "Music/音澜测试源-$token/"
        val destinationLeaf = "音澜测试汇总-$token"
        val expectedBytes = createWaveFixture()
        val sourceUri = createAudio(sourceFolder, "source-$token.wav", expectedBytes)
        cleanupUris += sourceUri

        val sourceId = ContentUris.parseId(sourceUri)
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val journal = InMemoryMoveJournalStore()
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        var step = coordinator.start(destinationLeaf) {}
        if (step is RelocationStep.AwaitingAuthorization) {
            step = coordinator.resume(authorizationGranted = true) {}
        }

        assertTrue(step is RelocationStep.Finished)
        val completed = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.moved)
        assertTrue(playlists.remaps.isEmpty())
        assertEquals(sourceId, ContentUris.parseId(sourceUri))

        val actualBytes = requireNotNull(context.contentResolver.openInputStream(sourceUri)).use {
            it.readBytes()
        }
        assertArrayEquals(expectedBytes, actualBytes)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            assertTrue(
                (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
            )
        } finally {
            extractor.release()
        }
        assertMediaStoreTarget(sourceUri, "Music/音澜/$destinationLeaf/", "source-$token.wav")
    }

    @Test
    fun legacyRecoveryDoesNotDeleteFileAfterDataWasAlreadyUpdated() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val sourceRelativePath = "Music/音澜恢复测试源-$token/"
        val destinationLeaf = "音澜恢复测试目标-$token"
        val targetRelativePath = "Music/音澜/$destinationLeaf/"
        val bytes = createWaveFixture()
        val sourceUri = createAudio(sourceRelativePath, "recover-$token.wav", bytes)
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val sourcePath = context.contentResolver.query(
            sourceUri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        } ?: error("source path missing")
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/$destinationLeaf",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val target = File(targetDirectory, "recover-$token.wav")
        assertTrue(File(sourcePath).renameTo(target))
        @Suppress("DEPRECATION")
        assertEquals(
            1,
            context.contentResolver.update(
                sourceUri,
                ContentValues().apply { put(MediaStore.Audio.Media.DATA, target.absolutePath) },
                null,
                null,
            ),
        )

        val operation = MoveOperationRecord(
            id = "legacy-crash-$token",
            targetRelativePath = targetRelativePath,
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = bytes.size.toLong(),
                        status = com.melody.local.data.MoveItemStatus.PREPARED,
                        destinationUri = target.toURI().toString(),
                        checksum = MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) },
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        val completed = (recovered as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.moved)
        assertTrue(target.isFile)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun legacyRecoveryRetainsVerifiedArtifactsWithoutTouchingUnrelatedTemporary() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜临时文件源-$token/",
            "owned-temp-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/音澜临时文件目标-$token",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val operationId = "owned-temp-operation-$token"
        val target = File(targetDirectory, "owned-temp-$token.wav").apply { writeBytes(bytes) }
        val checksum = sha256(bytes)
        File(targetDirectory, ".$operationId-$sourceId.yinlan-owned").writeText(
            "$operationId:$sourceId:$checksum"
        )
        val ownedTemporary = File(targetDirectory, ".$operationId-$sourceId.yinlan-moving")
            .apply { writeText("owned") }
        val unrelatedTemporary = File(targetDirectory, ".unrelated-$token.yinlan-moving")
            .apply { writeText("keep") }
        val operation = MoveOperationRecord(
            id = operationId,
            targetRelativePath = "Music/音澜/音澜临时文件目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operationId,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                        destinationUri = target.toUri().toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        assertTrue((recovered as RelocationStep.Finished).state is MediaOperationState.Failed)
        assertTrue(ownedTemporary.isFile)
        assertTrue(target.isFile)
        assertTrue(unrelatedTemporary.isFile)
        assertTrue(ownedTemporary.delete())
        assertTrue(target.delete())
        assertTrue(File(targetDirectory, ".$operationId-$sourceId.yinlan-owned").delete())
        assertTrue(unrelatedTemporary.delete())
    }

    @Test
    fun legacyPreparedRecoveryNeverDeletesAnUnownedTarget() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜未归属目标源-$token/",
            "unowned-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/音澜未归属目标-$token",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val target = File(targetDirectory, "unowned-$token.wav").apply { writeBytes(bytes) }
        val operation = MoveOperationRecord(
            id = "unowned-target-$token",
            targetRelativePath = "Music/音澜/音澜未归属目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                        destinationUri = target.toUri().toString(),
                        checksum = sha256(bytes),
                    )
                ),
            )
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val metadata = InMemoryMetadataStore()
        val lyrics = InMemoryLyricsStore()
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = metadata,
            lyrics = lyrics,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        assertTrue((recovered as RelocationStep.Finished).state is MediaOperationState.Failed)
        assertArrayEquals(bytes, target.readBytes())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertEquals(target.toUri().toString(), finalItem.destinationUri)
        assertTrue(playlists.remaps.isEmpty())
        assertTrue(metadata.remaps.isEmpty())
        assertTrue(lyrics.remaps.isEmpty())
    }

    @Test
    fun legacySidecarNeverAuthorizesOverwritingAnExistingChangedTarget() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val sourceBytes = createWaveFixture()
        val replacementBytes = sourceBytes.copyOf().apply { this[lastIndex] = 0x5a }
        val sourceUri = createAudio(
            "Music/音澜目标替换源-$token/",
            "replaced-$token.wav",
            sourceBytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/音澜目标替换-$token",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val operationId = "replaced-target-$token"
        val target = File(targetDirectory, "replaced-$token.wav").apply {
            writeBytes(replacementBytes)
        }
        val temporary = File(targetDirectory, ".$operationId-$sourceId.yinlan-moving").apply {
            writeBytes(sourceBytes)
        }
        val checksum = sha256(sourceBytes)
        val marker = File(targetDirectory, ".$operationId-$sourceId.yinlan-owned").apply {
            writeText("$operationId:$sourceId:$checksum")
        }
        val operation = MoveOperationRecord(
            id = operationId,
            targetRelativePath = "Music/音澜/音澜目标替换-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = sourceBytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                        destinationUri = target.toUri().toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        assertTrue((recovered as RelocationStep.Finished).state is MediaOperationState.Failed)
        assertArrayEquals(replacementBytes, target.readBytes())
        assertArrayEquals(sourceBytes, temporary.readBytes())
        assertEquals(target.toUri().toString(), journal.items(operation.id).single().destinationUri)
        assertTrue(target.delete())
        assertTrue(temporary.delete())
        assertTrue(marker.delete())
    }

    @Test
    fun legacyRecoveryKeepsAndPromotesVerifiedTemporaryWhenSourceIsGone() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜唯一临时副本源-$token/",
            "only-temp-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/音澜唯一临时副本-$token",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val operation = MoveOperationRecord(
            id = "only-temp-$token",
            targetRelativePath = "Music/音澜/音澜唯一临时副本-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val target = File(targetDirectory, "only-temp-$token.wav")
        val temporary = File(targetDirectory, ".${operation.id}-$sourceId.yinlan-moving")
            .apply { writeBytes(bytes) }
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                        destinationUri = target.toUri().toString(),
                        checksum = sha256(bytes),
                    )
                ),
            )
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        assertEquals(1, context.contentResolver.delete(sourceUri, null, null))
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        val completed = (recovered as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.moved)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(temporary.exists())
        assertFalse(File(targetDirectory, ".${operation.id}-$sourceId.yinlan-owned").exists())
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.COMMITTED, finalItem.status)
        val destinationId = requireNotNull(finalItem.newSongId)
        cleanupUris += ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            destinationId,
        )
        if (destinationId != sourceId) {
            assertEquals(destinationId, playlists.remaps[sourceId])
        }
    }

    @Test
    fun legacyCancelRestoresQuarantinedSourceBeforeTerminating() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜隔离取消源-$token/",
            "quarantine-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        @Suppress("DEPRECATION")
        val sourcePath = context.contentResolver.query(
            sourceUri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        } ?: error("source path missing")
        val sourceFile = File(sourcePath)
        @Suppress("DEPRECATION")
        val targetDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "音澜/音澜隔离取消目标-$token",
        ).apply { mkdirs() }
        cleanupDirectories += targetDirectory
        val operationId = "quarantine-cancel-$token"
        val target = File(targetDirectory, "quarantine-$token.wav").apply { writeBytes(bytes) }
        val temporary = File(targetDirectory, ".$operationId-$sourceId.yinlan-moving")
            .apply { writeBytes(bytes) }
        val checksum = sha256(bytes)
        val marker = File(targetDirectory, ".$operationId-$sourceId.yinlan-owned").apply {
            writeText("$operationId:$sourceId:$checksum")
        }
        val quarantine = sourceFile.parentFile!!.resolve(
            ".$operationId-$sourceId.yinlan-source-quarantine"
        )
        assertTrue(sourceFile.renameTo(quarantine))
        val operation = MoveOperationRecord(
            id = operationId,
            targetRelativePath = "Music/音澜/音澜隔离取消目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operationId,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = target.name,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        destinationUri = target.toUri().toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val cancelled = coordinator.cancel {}

        assertTrue(cancelled is MediaOperationState.Cancelled)
        assertEquals(MoveItemStatus.CANCELLED, journal.items(operationId).single().status)
        assertTrue(sourceFile.isFile)
        assertArrayEquals(bytes, sourceFile.readBytes())
        assertFalse(quarantine.exists())
        assertFalse(temporary.exists())
        assertFalse(marker.exists())
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(target.delete())
    }

    @Test
    fun recoveryDeletesOperationMarkedPendingRowCreatedBeforeUriWasJournaled() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val sourceUri = createAudio(
            "Music/音澜待恢复源-$token/",
            "pending-$token.wav",
            createWaveFixture(),
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "pending-crash-$token",
            targetRelativePath = "Music/音澜/音澜待恢复目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val item = MoveItemRecord(
            operationId = operation.id,
            oldSongId = sourceId,
            sourceUri = sourceUri.toString(),
            displayName = "pending-$token.wav",
            sourceSize = createWaveFixture().size.toLong(),
            status = com.melody.local.data.MoveItemStatus.PREPARED,
        )
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val orphan = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        pendingMoveDestinationDisplayName(operation.id, sourceId),
                    )
                    put(
                        MediaStore.Audio.Media.TITLE,
                        pendingMoveDestinationMarker(operation.id, sourceId),
                    )
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        pendingMoveDestinationRelativePath(
                            operation.targetRelativePath,
                            operation.id,
                            sourceId,
                        ),
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        val journal = InMemoryMoveJournalStore().apply { create(operation, listOf(item)) }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        var recovered = coordinator.recover {}
        if (recovered is RelocationStep.AwaitingAuthorization) {
            recovered = coordinator.resume(authorizationGranted = true) {}
        }

        assertTrue(recovered is RelocationStep.Finished)
        val orphanStillExists = context.contentResolver.query(
            orphan,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
        assertEquals(false, orphanStillExists)
    }

    @Test
    fun secondaryVolumeMoveCopiesDeletesAndRemapsToPrimary() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val secondaryVolume = findSecondaryVolumeOrSkip()
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = runCatching {
            createAudio(
                relativePath = "Music/音澜跨卷源-$token/",
                name = "cross-volume-$token.wav",
                bytes = bytes,
                volumeName = secondaryVolume,
            )
        }.getOrNull()
        enforceWritableSecondaryOrSkip(sourceUri)
        sourceUri ?: return@runBlocking
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val metadata = InMemoryMetadataStore()
        val lyrics = InMemoryLyricsStore()
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = metadata,
            lyrics = lyrics,
            journal = InMemoryMoveJournalStore(),
        )
        val targetLeaf = "音澜跨卷目标-$token"

        var step = coordinator.start(targetLeaf) {}
        while (step is RelocationStep.AwaitingAuthorization) {
            step = coordinator.resume(authorizationGranted = true) {}
        }

        assertTrue(step is RelocationStep.Finished)
        val state = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, state.summary.moved)
        val destinationId = playlists.remaps[sourceId] ?: sourceId
        val destinationUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            destinationId,
        )
        cleanupUris += destinationUri
        val sourceStillExists = context.contentResolver.query(
            sourceUri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
        assertEquals(false, sourceStillExists)
        val copiedBytes = requireNotNull(context.contentResolver.openInputStream(destinationUri)).use {
            it.readBytes()
        }
        assertArrayEquals(bytes, copiedBytes)
        if (destinationId != sourceId) {
            assertEquals(destinationId, metadata.remaps[sourceId])
            assertEquals(destinationId, lyrics.remaps[sourceId])
        }
        assertMediaStoreTarget(
            destinationUri,
            "Music/音澜/$targetLeaf/",
            "cross-volume-$token.wav",
        )
    }

    @Test
    fun recoveryUpgradesSyntheticSourceUriBeforeMoving() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜旧日志源-$token/",
            name = "legacy-uri-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val syntheticUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            sourceId,
        )
        val operation = MoveOperationRecord(
            id = "synthetic-uri-$token",
            targetRelativePath = "Music/音澜/音澜旧日志目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = syntheticUri.toString(),
                        displayName = "legacy-uri-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        var step = coordinator.recover {}
        while (step is RelocationStep.AwaitingAuthorization) {
            step = coordinator.resume(authorizationGranted = true) {}
        }

        assertTrue(step is RelocationStep.Finished)
        val storedSourceUri = journal.items(operation.id).single().sourceUri
        assertTrue(storedSourceUri.contains("/external_primary/"))
        assertFalse(isSyntheticExternalMediaUri(storedSourceUri))
        assertMediaStoreTarget(
            sourceUri,
            operation.targetRelativePath,
            "legacy-uri-$token.wav",
        )
    }

    @Test
    fun syntheticJournalCandidateWithWrongChecksumIsNeverAdoptedOrDeleted() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val sourceBytes = createWaveFixture()
        val expectedBytes = sourceBytes.copyOf().apply { this[lastIndex] = 1 }
        val sourceUri = createAudio(
            relativePath = "Music/音澜旧日志校验源-$token/",
            name = "wrong-source-$token.wav",
            bytes = sourceBytes,
        )
        val destinationUri = createAudio(
            relativePath = "Music/音澜/音澜旧日志校验目标-$token/",
            name = "verified-target-$token.wav",
            bytes = expectedBytes,
        )
        cleanupUris += sourceUri
        cleanupUris += destinationUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "synthetic-checksum-$token",
            targetRelativePath = "Music/音澜/音澜旧日志校验目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val syntheticSource = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            sourceId,
        ).toString()
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = syntheticSource,
                        displayName = "verified-target-$token.wav",
                        sourceSize = sourceBytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(destinationUri),
                        destinationUri = destinationUri.toString(),
                        checksum = sha256(expectedBytes),
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val step = coordinator.recover {}

        assertTrue(step is RelocationStep.Finished)
        val finalItem = journal.items(operation.id).single()
        assertEquals(syntheticSource, finalItem.sourceUri)
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertEquals(destinationUri.toString(), finalItem.destinationUri)
        assertArrayEquals(
            sourceBytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
    }

    @Test
    fun inaccessibleConcreteVolumeQueryPreventsBareIdSelectionAndAnyRemap() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜卷查询失败源-$token/",
            name = "partial-query-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val inaccessibleVolume = "yinlan-inaccessible-$token"
        val visibleVolumes = linkedSetOf(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
            inaccessibleVolume,
        )
        val mediaAccess = object : RelocationMediaAccess {
            override fun externalVolumeNames(context: Context): Set<String> = visibleVolumes

            override fun recentExternalVolumeNames(context: Context): Set<String> = visibleVolumes

            override fun query(
                resolver: ContentResolver,
                uri: Uri,
                projection: Array<String>,
            ): Cursor? = if (uri.pathSegments.firstOrNull() == inaccessibleVolume) {
                null
            } else {
                resolver.query(uri, projection, null, null, null)
            }
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val metadata = InMemoryMetadataStore()
        val lyrics = InMemoryLyricsStore()
        val journal = InMemoryMoveJournalStore()
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = metadata,
            lyrics = lyrics,
            journal = journal,
            mediaAccess = mediaAccess,
        )

        val step = coordinator.start("音澜卷查询失败目标-$token") {}

        assertTrue(step is RelocationStep.Finished)
        val completed = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.failed)
        val finalItem = journal.items(requireNotNull(journal.operationId)).single()
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertTrue(finalItem.error.orEmpty().contains("查询不可用"))
        assertTrue(playlists.remaps.isEmpty())
        assertTrue(metadata.remaps.isEmpty())
        assertTrue(lyrics.remaps.isEmpty())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
    }

    @Test
    fun nullCursorWhileCheckingSourcePreservesVerifiedDestinationAndJournal() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜源状态未知-$token/",
            name = "unknown-source-$token.wav",
            bytes = bytes,
        )
        val destinationUri = createAudio(
            relativePath = "Music/音澜/音澜源状态未知目标-$token/",
            name = "preserved-target-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        cleanupUris += destinationUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "unknown-source-$token",
            targetRelativePath = "Music/音澜/音澜源状态未知目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val checksum = sha256(bytes)
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "preserved-target-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(destinationUri),
                        destinationUri = destinationUri.toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val platformVolumes = MediaStore.getExternalVolumeNames(context)
        val mediaAccess = object : RelocationMediaAccess {
            override fun externalVolumeNames(context: Context): Set<String> = platformVolumes

            override fun recentExternalVolumeNames(context: Context): Set<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.getRecentExternalVolumeNames(context)
                } else {
                    platformVolumes
                }

            override fun query(
                resolver: ContentResolver,
                uri: Uri,
                projection: Array<String>,
            ): Cursor? = if (uri == sourceUri) {
                null
            } else {
                resolver.query(uri, projection, null, null, null)
            }
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
            mediaAccess = mediaAccess,
        )

        val step = coordinator.recover {}

        assertTrue(step is RelocationStep.Finished)
        assertTrue((step as RelocationStep.Finished).state is MediaOperationState.Failed)
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertEquals(destinationUri.toString(), finalItem.destinationUri)
        assertEquals(checksum, finalItem.checksum)
        assertTrue(playlists.remaps.isEmpty())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(destinationUri)).use {
                it.readBytes()
            },
        )
    }

    @Test
    fun startBeforeAsyncRecoveryClaimsCopiedJournalWithoutCreatingAnotherOperation() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜安全回滚源-$token/",
            name = "rollback-source-$token.wav",
            bytes = bytes,
        )
        val destinationUri = createAudio(
            relativePath = "Music/音澜/音澜安全回滚目标-$token/",
            name = "rollback-target-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        cleanupUris += destinationUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "verified-rollback-$token",
            targetRelativePath = "Music/音澜/音澜安全回滚目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val checksum = sha256(bytes)
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "rollback-target-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(destinationUri),
                        destinationUri = destinationUri.toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val started = coordinator.start("must-not-create-$token") {}

        assertTrue(started is RelocationStep.AwaitingAuthorization)
        assertEquals(1, journal.createCalls)
        assertEquals(operation.id, journal.operationId)
        assertEquals(destinationUri.toString(), journal.items(operation.id).single().destinationUri)

        // The delayed startup recovery coroutine may now arrive after the user-triggered start.
        // It must bind the same active journal and must not allocate another operation/target.
        val recovered = coordinator.recover {}
        assertTrue(recovered is RelocationStep.AwaitingAuthorization)
        assertEquals(1, journal.createCalls)
        assertEquals(operation.id, journal.operationId)
        assertEquals(destinationUri.toString(), journal.items(operation.id).single().destinationUri)

        val denied = coordinator.resume(authorizationGranted = false) {}
        assertTrue(denied is RelocationStep.Finished)
        val completed = (denied as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.cancelled)
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.CANCELLED, finalItem.status)
        assertEquals(destinationUri.toString(), finalItem.destinationUri)
        assertEquals(checksum, finalItem.checksum)
        assertTrue(playlists.remaps.isEmpty())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(destinationUri)).use {
                it.readBytes()
            },
        )
    }

    @Test
    fun cancelBeforeAsyncRecoveryClaimsAndTerminatesDurableOperation() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜启动取消源-$token/",
            name = "cancel-source-$token.wav",
            bytes = bytes,
        )
        val destinationUri = createAudio(
            relativePath = "Music/音澜/音澜启动取消目标-$token/",
            name = "cancel-target-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        cleanupUris += destinationUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "startup-cancel-$token",
            targetRelativePath = "Music/音澜/音澜启动取消目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val checksum = sha256(bytes)
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "cancel-target-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(destinationUri),
                        destinationUri = destinationUri.toString(),
                        checksum = checksum,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val cancelled = coordinator.cancel {}

        assertTrue(cancelled is MediaOperationState.Cancelled)
        assertEquals(MoveItemStatus.CANCELLED, journal.items(operation.id).single().status)
        assertNull(coordinator.recover {})
        assertEquals(1, journal.createCalls)
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(destinationUri)).use {
                it.readBytes()
            },
        )
    }

    @Test
    fun recoveryResumesDurableCancellationWithoutDeletingTheSource() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜取消恢复源-$token/",
            "cancel-recovery-source-$token.wav",
            bytes,
        )
        val targetPath = "Music/音澜/音澜取消恢复目标-$token/"
        val targetUri = createAudio(
            targetPath,
            "cancel-recovery-target-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        cleanupUris += targetUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "durable-cancel-$token",
            targetRelativePath = targetPath,
            status = MoveOperationStatus.CANCELLING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "cancel-recovery-target-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(targetUri),
                        destinationUri = targetUri.toString(),
                        checksum = sha256(bytes),
                    )
                ),
            )
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        assertTrue((recovered as RelocationStep.Finished).state is MediaOperationState.Cancelled)
        assertEquals(MoveItemStatus.CANCELLED, journal.items(operation.id).single().status)
        assertTrue(playlists.remaps.isEmpty())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(targetUri)).use { it.readBytes() },
        )
    }

    @Test
    fun recoveryHonoursDurablePerSongPermissionDenial() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            "Music/音澜逐项拒绝源-$token/",
            "item-cancel-source-$token.wav",
            bytes,
        )
        val targetPath = "Music/音澜/音澜逐项拒绝目标-$token/"
        val targetUri = createAudio(
            targetPath,
            "item-cancel-target-$token.wav",
            bytes,
        )
        cleanupUris += sourceUri
        cleanupUris += targetUri
        val sourceId = ContentUris.parseId(sourceUri)
        val operation = MoveOperationRecord(
            id = "durable-item-cancel-$token",
            targetRelativePath = targetPath,
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "item-cancel-target-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.CANCELLING,
                        newSongId = ContentUris.parseId(targetUri),
                        destinationUri = targetUri.toString(),
                        checksum = sha256(bytes),
                        error = "用户拒绝系统授权",
                    )
                ),
            )
        }
        val playlists = InMemoryPlaylistStore(listOf(sourceId))
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = playlists,
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val recovered = coordinator.recover {}

        assertTrue(recovered is RelocationStep.Finished)
        val completed = (recovered as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.cancelled)
        assertEquals(MoveItemStatus.CANCELLED, journal.items(operation.id).single().status)
        assertTrue(playlists.remaps.isEmpty())
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(targetUri)).use { it.readBytes() },
        )
    }

    @Test
    fun cancellationPublishesAndRetainsACompleteJournaledPendingDestination() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜待取消源-$token/",
            name = "pending-cancel-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val targetPath = "Music/音澜/音澜待取消目标-$token/"
        val targetName = "pending-cancel-$token.wav"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pendingUri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, targetName)
                    put(MediaStore.Audio.Media.TITLE, "Pending cancel")
                    put(MediaStore.Audio.Media.ARTIST, "Test artist")
                    put(MediaStore.Audio.Media.ALBUM, "Test album")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, targetPath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        cleanupUris += pendingUri
        requireNotNull(context.contentResolver.openOutputStream(pendingUri, "w")).use {
            it.write(bytes)
        }
        val operation = MoveOperationRecord(
            id = "pending-cancel-$token",
            targetRelativePath = targetPath,
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = targetName,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = ContentUris.parseId(pendingUri),
                        destinationUri = pendingUri.toString(),
                        checksum = sha256(bytes),
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val cancelled = coordinator.cancel {}

        assertTrue(cancelled is MediaOperationState.Cancelled)
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.CANCELLED, finalItem.status)
        assertEquals(pendingUri.toString(), finalItem.destinationUri)
        val pendingState = context.contentResolver.query(
            pendingUri,
            arrayOf(MediaStore.Audio.Media.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else null
        }
        assertEquals(0, pendingState)
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(pendingUri)).use { it.readBytes() },
        )
    }

    @Test
    fun cancellationDeletesOnlyAnIncompleteOperationOwnedPendingDestination() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜未完成取消源-$token/",
            name = "incomplete-cancel-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val targetPath = "Music/音澜/音澜未完成取消目标-$token/"
        val targetName = "incomplete-cancel-$token.wav"
        val operationId = "incomplete-cancel-$token"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pendingUri = requireNotNull(
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, targetName)
                    put(
                        MediaStore.Audio.Media.TITLE,
                        pendingMoveDestinationMarker(operationId, sourceId),
                    )
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, targetPath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            )
        )
        cleanupUris += pendingUri
        requireNotNull(context.contentResolver.openOutputStream(pendingUri, "w")).use {
            it.write(bytes, 0, bytes.size / 2)
        }
        val operation = MoveOperationRecord(
            id = operationId,
            targetRelativePath = targetPath,
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operationId,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = targetName,
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.PREPARED,
                        newSongId = ContentUris.parseId(pendingUri),
                        destinationUri = pendingUri.toString(),
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val cancelled = coordinator.cancel {}

        assertTrue(cancelled is MediaOperationState.Cancelled)
        val finalItem = journal.items(operationId).single()
        assertEquals(MoveItemStatus.CANCELLED, finalItem.status)
        assertNull(finalItem.destinationUri)
        val pendingStillExists = context.contentResolver.query(
            pendingUri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
        assertFalse(pendingStillExists)
        assertArrayEquals(
            bytes,
            requireNotNull(context.contentResolver.openInputStream(sourceUri)).use { it.readBytes() },
        )
    }

    @Test
    fun alteredDestinationAfterAuthorizationRequestNeverDeletesSource() = runBlocking {
        val waiting = createSecondaryMoveWaitingForDeleteAuthorization("altered")
        requireNotNull(context.contentResolver.openOutputStream(waiting.destinationUri, "w")).use {
            it.write(byteArrayOf(1, 2, 3, 4))
        }

        val step = waiting.coordinator.resume(authorizationGranted = true) {}

        assertTrue(step is RelocationStep.Finished)
        val completed = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.failed)
        val finalItem = waiting.journal.items(waiting.operationId).single()
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertNull(finalItem.destinationUri)
        assertNull(finalItem.checksum)
        val destinationStillExists = context.contentResolver.query(
            waiting.destinationUri,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
        assertFalse(destinationStillExists)
        assertArrayEquals(
            waiting.bytes,
            requireNotNull(context.contentResolver.openInputStream(waiting.sourceUri)).use {
                it.readBytes()
            },
        )
    }

    @Test
    fun deletedDestinationAfterAuthorizationRequestNeverDeletesSource() = runBlocking {
        val waiting = createSecondaryMoveWaitingForDeleteAuthorization("deleted")
        assertEquals(1, context.contentResolver.delete(waiting.destinationUri, null, null))

        val step = waiting.coordinator.resume(authorizationGranted = true) {}

        assertTrue(step is RelocationStep.Finished)
        val completed = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.failed)
        assertArrayEquals(
            waiting.bytes,
            requireNotNull(context.contentResolver.openInputStream(waiting.sourceUri)).use {
                it.readBytes()
            },
        )
    }

    @Test
    fun sourceDisappearingWhilePermissionUiIsOpenCommitsVerifiedTargetOnDenial() = runBlocking {
        val waiting = createSecondaryMoveWaitingForDeleteAuthorization("source-gone")
        assertEquals(1, context.contentResolver.delete(waiting.sourceUri, null, null))

        val step = waiting.coordinator.resume(authorizationGranted = false) {}

        assertTrue(step is RelocationStep.Finished)
        val completed = (step as RelocationStep.Finished).state as MediaOperationState.Completed
        assertEquals(1, completed.summary.moved)
        assertArrayEquals(
            waiting.bytes,
            requireNotNull(context.contentResolver.openInputStream(waiting.destinationUri)).use {
                it.readBytes()
            },
        )
        val finalItem = waiting.journal.items(waiting.operationId).single()
        assertEquals(MoveItemStatus.COMMITTED, finalItem.status)
        assertEquals(waiting.destinationUri.toString(), finalItem.destinationUri)
    }

    @Test
    fun cleanupFailureRetainsDestinationUriAndChecksumInJournal() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val token = UUID.randomUUID().toString().take(8)
        val bytes = createWaveFixture()
        val sourceUri = createAudio(
            relativePath = "Music/音澜清理失败源-$token/",
            name = "cleanup-$token.wav",
            bytes = bytes,
        )
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val checksum = sha256(bytes)
        val unavailableDestination =
            "content://com.melody.local.missing-provider/audio/$sourceId"
        val operation = MoveOperationRecord(
            id = "cleanup-failure-$token",
            targetRelativePath = "Music/音澜/音澜清理失败目标-$token/",
            status = MoveOperationStatus.MOVING,
        )
        val journal = InMemoryMoveJournalStore().apply {
            create(
                operation,
                listOf(
                    MoveItemRecord(
                        operationId = operation.id,
                        oldSongId = sourceId,
                        sourceUri = sourceUri.toString(),
                        displayName = "cleanup-$token.wav",
                        sourceSize = bytes.size.toLong(),
                        status = MoveItemStatus.COPIED,
                        newSongId = 9_999_999L,
                        destinationUri = unavailableDestination,
                        checksum = checksum,
                    )
                ),
            )
        }
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val step = coordinator.recover {}

        assertTrue(step is RelocationStep.Finished)
        val finalItem = journal.items(operation.id).single()
        assertEquals(MoveItemStatus.FAILED, finalItem.status)
        assertEquals(unavailableDestination, finalItem.destinationUri)
        assertEquals(checksum, finalItem.checksum)
        assertTrue(finalItem.error.orEmpty().contains("恢复信息已保留"))
        assertEquals(listOf(operation.id), journal.pendingOperations().map { it.id })
    }

    private suspend fun createSecondaryMoveWaitingForDeleteAuthorization(
        label: String,
    ): WaitingSecondaryMove {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val secondaryVolume = findSecondaryVolumeOrSkip()
        val token = "${label}-${UUID.randomUUID().toString().take(8)}"
        val bytes = createWaveFixture()
        val sourceUri = runCatching {
            createAudio(
                relativePath = "Music/音澜授权源-$token/",
                name = "authorization-$token.wav",
                bytes = bytes,
                volumeName = secondaryVolume,
            )
        }.getOrNull()
        enforceWritableSecondaryOrSkip(sourceUri)
        requireNotNull(sourceUri)
        cleanupUris += sourceUri
        val sourceId = ContentUris.parseId(sourceUri)
        val journal = InMemoryMoveJournalStore()
        val coordinator = MediaStoreSongRelocationCoordinator(
            context = context,
            playlists = InMemoryPlaylistStore(listOf(sourceId)),
            metadata = InMemoryMetadataStore(),
            lyrics = EmptyLyricsStore,
            journal = journal,
        )

        val step = coordinator.start("音澜授权目标-$token") {}

        assertTrue(
            "expected a delete authorization request after the verified cross-volume copy",
            step is RelocationStep.AwaitingAuthorization,
        )
        val copied = journal.items(requireNotNull(journal.operationId)).single()
        assertEquals(MoveItemStatus.COPIED, copied.status)
        val destinationUri = requireNotNull(copied.destinationUri).toUri()
        cleanupUris += destinationUri
        return WaitingSecondaryMove(
            coordinator = coordinator,
            journal = journal,
            operationId = copied.operationId,
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            bytes = bytes,
        )
    }

    private fun findSecondaryVolumeOrSkip(): String {
        val secondaryVolume = MediaStore.getExternalVolumeNames(context)
            .firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        if (secondaryVolume == null && secondaryVolumeRequiredByCi()) {
            fail("CI promised a writable secondary MediaStore volume, but none was mounted")
        }
        assumeTrue("emulator has no secondary shared-storage volume", secondaryVolume != null)
        return requireNotNull(secondaryVolume)
    }

    private fun enforceWritableSecondaryOrSkip(sourceUri: Uri?) {
        if (sourceUri == null && secondaryVolumeRequiredByCi()) {
            fail("CI mounted a secondary MediaStore volume, but it was not writable")
        }
        assumeTrue("secondary MediaStore volume is not writable", sourceUri != null)
    }

    private fun secondaryVolumeRequiredByCi(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("requireSecondaryVolume")
            .equals("true", ignoreCase = true)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class WaitingSecondaryMove(
        val coordinator: MediaStoreSongRelocationCoordinator,
        val journal: InMemoryMoveJournalStore,
        val operationId: String,
        val sourceUri: Uri,
        val destinationUri: Uri,
        val bytes: ByteArray,
    )

    private suspend fun createAudio(
        relativePath: String,
        name: String,
        bytes: ByteArray,
        volumeName: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(volumeName)
            val uri = requireNotNull(
                context.contentResolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.'))
                        put(MediaStore.Audio.Media.ARTIST, "Test artist")
                        put(MediaStore.Audio.Media.ALBUM, "Test album")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    },
                )
            )
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            check(
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1
            )
            return uri
        }

        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            relativePath.removePrefix("Music/"),
        ).apply { mkdirs() }
        cleanupDirectories += directory
        val file = File(directory, name).apply { writeBytes(bytes) }
        return suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/wav")) {
                    _, uri ->
                if (uri != null && continuation.isActive) continuation.resume(uri)
                else if (continuation.isActive) continuation.cancel(IllegalStateException("scan failed"))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertMediaStoreTarget(uri: Uri, expectedPath: String, expectedName: String) {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME)
        } else {
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME)
        }
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedName, cursor.getString(1))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                assertEquals(expectedPath, cursor.getString(0))
            } else {
                assertTrue(cursor.getString(0).replace('\\', '/').endsWith("/$expectedPath$expectedName"))
            }
        } ?: error("moved MediaStore row missing")
    }

    private fun createWaveFixture(): ByteArray {
        val samples = ByteArray(8_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples.size)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(8_000)
            putInt(8_000)
            putShort(1.toShort())
            putShort(8.toShort())
            put("data".toByteArray())
            putInt(samples.size)
        }.array()
        return ByteArrayOutputStream(header.size + samples.size).apply {
            write(header)
            write(samples)
        }.toByteArray()
    }
}

private class InMemoryPlaylistStore(private val ids: List<Long>) : PlaylistStore {
    override val playlists: Flow<List<PlaylistSummary>> = flowOf(emptyList())
    val remaps = mutableMapOf<Long, Long>()
    override suspend fun create(name: String): Long = error("unused")
    override suspend fun rename(playlistId: Long, name: String) = error("unused")
    override suspend fun delete(playlistId: Long) = error("unused")
    override suspend fun addSong(playlistId: Long, songId: Long): Boolean = error("unused")
    override suspend fun removeSong(playlistId: Long, songId: Long) = error("unused")
    override fun observeSongIds(playlistId: Long): Flow<List<Long>> = flowOf(ids)
    override suspend fun getAllSongIds(): List<Long> = ids
    override suspend fun remapSongIds(remaps: Map<Long, Long>) {
        this.remaps += remaps
    }
}

private class InMemoryMetadataStore : SongMetadataStore {
    val remaps = mutableMapOf<Long, Long>()
    override suspend fun getAll(): Map<Long, SongMetadataOverride> = emptyMap()
    override suspend fun put(value: SongMetadataOverride) = Unit
    override suspend fun remap(oldSongId: Long, newSongId: Long) {
        remaps[oldSongId] = newSongId
    }
    override suspend fun delete(songId: Long) = Unit
}

private open class InMemoryLyricsStore : LyricsStore {
    val remaps = mutableMapOf<Long, Long>()
    override suspend fun load(songId: Long): ParsedLyrics? = null
    override suspend fun import(songId: Long, uri: Uri): ParsedLyrics = error("unused")
    override suspend fun delete(songId: Long) = Unit
    override suspend fun remap(oldSongId: Long, newSongId: Long) {
        remaps[oldSongId] = newSongId
    }
}

private object EmptyLyricsStore : InMemoryLyricsStore()

private class InMemoryMoveJournalStore : MoveJournalStore {
    private var operation: MoveOperationRecord? = null
    private val records = linkedMapOf<Long, MoveItemRecord>()
    var createCalls: Int = 0
        private set
    val operationId: String?
        get() = operation?.id

    override suspend fun create(operation: MoveOperationRecord, items: List<MoveItemRecord>) {
        createCalls++
        this.operation = operation
        records.clear()
        items.forEach { records[it.oldSongId] = it }
    }

    override suspend fun pendingOperations(): List<MoveOperationRecord> = operation
        ?.takeIf {
            it.status != MoveOperationStatus.COMPLETED &&
                it.status != MoveOperationStatus.CANCELLED &&
                it.status != MoveOperationStatus.FAILED
        }
        ?.let(::listOf)
        .orEmpty()

    override suspend fun items(operationId: String): List<MoveItemRecord> =
        records.values.filter { it.operationId == operationId }

    override suspend fun updateOperation(operationId: String, status: MoveOperationStatus) {
        operation = operation?.takeIf { it.id == operationId }?.copy(status = status)
    }

    override suspend fun updateItem(item: MoveItemRecord) {
        records[item.oldSongId] = item
    }

    override suspend fun delete(operationId: String) {
        if (operation?.id == operationId) operation = null
        records.values.removeAll { it.operationId == operationId }
    }
}
