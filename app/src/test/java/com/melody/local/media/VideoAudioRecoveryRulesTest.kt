package com.melody.local.media

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAudioRecoveryRulesTest {
    @Test
    fun legacyPlacementNeverReplacesAFileThatWonTheNameRace() {
        val directory = Files.createTempDirectory("yinlan-video-no-replace").toFile()
        val staging = directory.resolve(".worker.yinlan-pending")
        val target = directory.resolve("Song.m4a")
        val stagedBytes = "worker output".toByteArray()
        val racingBytes = "racing file".toByteArray()
        try {
            staging.writeBytes(stagedBytes)
            target.writeBytes(racingBytes)

            assertFalse(moveLegacyFileNoReplace(staging, target))
            assertArrayEquals(racingBytes, target.readBytes())
            assertArrayEquals(stagedBytes, staging.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyCleanupOwnershipRequiresBothSizeAndSha256() {
        val file = Files.createTempFile("yinlan-video-owned", ".m4a").toFile()
        val ownedBytes = "verified worker output".toByteArray()
        try {
            file.writeBytes(ownedBytes)
            val checksum = MessageDigest.getInstance("SHA-256")
                .digest(ownedBytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

            assertTrue(legacyFileMatchesExpectedContent(file, ownedBytes.size.toLong(), checksum))
            assertFalse(legacyFileMatchesExpectedContent(file, ownedBytes.size.toLong() + 1L, checksum))
            assertFalse(legacyFileMatchesExpectedContent(file, ownedBytes.size.toLong(), "0".repeat(64)))
            file.writeBytes(ByteArray(ownedBytes.size) { index -> (index + 1).toByte() })
            assertFalse(legacyFileMatchesExpectedContent(file, ownedBytes.size.toLong(), checksum))
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyReceiptRowMustStillPointAtTheJournaledTarget() {
        val directory = Files.createTempDirectory("yinlan-video-row-path").toFile()
        val expected = directory.resolve("expected.m4a")
        val reused = directory.resolve("reused-id.m4a")
        try {
            assertTrue(
                legacyMediaRowMatchesExpectedPath(
                    rowId = 42L,
                    rowPath = expected.absolutePath,
                    expectedSongId = 42L,
                    expectedPath = expected.absolutePath,
                )
            )
            assertFalse(
                legacyMediaRowMatchesExpectedPath(
                    rowId = 42L,
                    rowPath = reused.absolutePath,
                    expectedSongId = 42L,
                    expectedPath = expected.absolutePath,
                )
            )
            assertFalse(
                legacyMediaRowMatchesExpectedPath(
                    rowId = 43L,
                    rowPath = expected.absolutePath,
                    expectedSongId = 42L,
                    expectedPath = expected.absolutePath,
                )
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun modernOutputVerificationRejectsShortOrCorruptedCopies() {
        val expected = "complete media output".toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(expected)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertTrue(
            inputMatchesExpectedContent(
                ByteArrayInputStream(expected),
                expected.size.toLong(),
                checksum,
            )
        )
        assertFalse(
            inputMatchesExpectedContent(
                ByteArrayInputStream(expected.copyOf(expected.size - 1)),
                expected.size.toLong(),
                checksum,
            )
        )
        val corrupted = expected.copyOf().apply { this[lastIndex] = (last() + 1).toByte() }
        assertFalse(
            inputMatchesExpectedContent(
                ByteArrayInputStream(corrupted),
                expected.size.toLong(),
                checksum,
            )
        )
    }

    @Test
    fun publishedReceiptIsAdoptedOnlyForTheExplicitlyCancelledWorker() {
        val oldWorker = UUID.randomUUID()
        val currentWorker = UUID.randomUUID()

        assertTrue(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = oldWorker,
                currentWorkerId = currentWorker,
                isPublishedReceipt = true,
                hasSameRequestFingerprint = true,
                cancelRequestedWorkerIds = setOf(oldWorker),
            )
        )
        assertFalse(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = oldWorker,
                currentWorkerId = currentWorker,
                isPublishedReceipt = true,
                hasSameRequestFingerprint = true,
                cancelRequestedWorkerIds = emptySet(),
            )
        )
        assertFalse(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = oldWorker,
                currentWorkerId = currentWorker,
                isPublishedReceipt = true,
                hasSameRequestFingerprint = false,
                cancelRequestedWorkerIds = setOf(oldWorker),
            )
        )
        assertTrue(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = oldWorker,
                currentWorkerId = currentWorker,
                isPublishedReceipt = false,
                hasSameRequestFingerprint = true,
                cancelRequestedWorkerIds = emptySet(),
            )
        )
    }

    @Test
    fun laterCancellationCannotHideAnEarlierLatePublishedReceipt() {
        val cancelledA = UUID.randomUUID()
        val cancelledB = UUID.randomUUID()
        val retryC = UUID.randomUUID()
        val unresolved = setOf(cancelledA, cancelledB)

        assertTrue(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = cancelledA,
                currentWorkerId = retryC,
                isPublishedReceipt = true,
                hasSameRequestFingerprint = true,
                cancelRequestedWorkerIds = unresolved,
            )
        )
        assertFalse(
            shouldAdoptRecoveredVideoImport(
                sourceWorkerId = cancelledB,
                currentWorkerId = retryC,
                isPublishedReceipt = true,
                hasSameRequestFingerprint = false,
                cancelRequestedWorkerIds = unresolved,
            )
        )
    }

    @Test
    fun unrelatedPublishedReceiptIsSkippedBeforeItsArtifactIsInspected() {
        assertFalse(
            shouldInspectRecoveredVideoImport(
                isPublishedReceipt = true,
                hasSameRequestFingerprint = false,
            )
        )
        assertTrue(
            shouldInspectRecoveredVideoImport(
                isPublishedReceipt = true,
                hasSameRequestFingerprint = true,
            )
        )
        assertTrue(
            shouldInspectRecoveredVideoImport(
                isPublishedReceipt = false,
                hasSameRequestFingerprint = false,
            )
        )
    }

    @Test
    fun primaryBareIdOwnershipRequiresEveryVolumeToBeReadableAndCollisionFree() {
        val primary = "external_primary"

        assertTrue(
            isUniquePrimaryVideoSongId(
                primaryVolume = primary,
                insertedVolume = primary,
                volumeLookups = linkedMapOf(
                    primary to VideoSongIdLookup.PRESENT,
                    "0123-4567" to VideoSongIdLookup.ABSENT,
                ),
            )
        )
        assertFalse(
            isUniquePrimaryVideoSongId(
                primaryVolume = primary,
                insertedVolume = primary,
                volumeLookups = linkedMapOf(
                    primary to VideoSongIdLookup.PRESENT,
                    "0123-4567" to VideoSongIdLookup.PRESENT,
                ),
            )
        )
        assertFalse(
            isUniquePrimaryVideoSongId(
                primaryVolume = primary,
                insertedVolume = primary,
                volumeLookups = linkedMapOf(
                    primary to VideoSongIdLookup.PRESENT,
                    "0123-4567" to VideoSongIdLookup.INACCESSIBLE,
                ),
            )
        )
        assertFalse(
            isUniquePrimaryVideoSongId(
                primaryVolume = primary,
                insertedVolume = "0123-4567",
                volumeLookups = mapOf(primary to VideoSongIdLookup.PRESENT),
            )
        )
    }
}
