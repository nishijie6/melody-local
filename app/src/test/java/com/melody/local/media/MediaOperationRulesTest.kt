package com.melody.local.media

import com.melody.local.data.MoveItemStatus
import com.melody.local.data.MoveOperationRecord
import com.melody.local.data.MoveOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOperationRulesTest {
    @Test
    fun aacIsPassedThroughAndOtherDecodableAudioIsTranscoded() {
        assertEquals(AudioExportStrategy.PASSTHROUGH_AAC, audioExportStrategy("audio/mp4a-latm"))
        assertEquals(AudioExportStrategy.PASSTHROUGH_AAC, audioExportStrategy("audio/AAC"))
        assertEquals(AudioExportStrategy.TRANSCODE_TO_AAC, audioExportStrategy("audio/opus"))
        assertEquals(AudioExportStrategy.TRANSCODE_TO_AAC, audioExportStrategy(null))
    }

    @Test
    fun folderValidationTrimsSafeNamesAndRejectsTraversalOrWindowsSeparators() {
        assertEquals("歌单汇总", validateDestinationFolder("  歌单汇总  "))
        listOf("", ".", "..", "a/b", "a\\b", "bad:", "bad.").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                validateDestinationFolder(invalid)
            }
        }
    }

    @Test
    fun caseInsensitiveNameConflictsReceiveStableNumericSuffixes() {
        assertEquals(
            "Song (4).m4a",
            uniqueDisplayName(
                "Song.m4a",
                listOf("song.M4A", "Song (2).m4a", "SONG (3).M4A"),
            ),
        )
        assertEquals("A_B_.m4a", "${safeAudioFileBase("A/B?")}.m4a")
    }

    @Test
    fun playlistSongIdsAreDeduplicatedWithoutChangingFirstSeenOrder() {
        assertEquals(listOf(7L, 2L, 9L), deduplicateSongIds(listOf(7L, 2L, 7L, 9L, 2L)))
    }

    @Test
    fun multiplePendingOperationsAreRecoveredOldestFirstWithStableIdTieBreak() {
        val newer = MoveOperationRecord(
            id = "newer",
            targetRelativePath = "Music/音澜/newer/",
            status = MoveOperationStatus.MOVING,
            createdAt = 20L,
        )
        val sameTimeB = newer.copy(id = "b", createdAt = 10L)
        val sameTimeA = newer.copy(id = "a", createdAt = 10L)

        assertEquals(
            listOf("a", "b", "newer"),
            orderPendingMoveOperations(listOf(newer, sameTimeB, sameTimeA)).map { it.id },
        )
    }

    @Test
    fun routeUsesInPlaceMoveOnlyForPrimaryMediaStoreVolume() {
        assertEquals(SongRelocationRoute.LEGACY_FILE_MOVE, relocationRoute(28, null))
        assertEquals(
            SongRelocationRoute.IN_PLACE_MEDIASTORE,
            relocationRoute(29, "external_primary"),
        )
        assertEquals(
            SongRelocationRoute.COPY_VERIFY_DELETE,
            relocationRoute(36, "0123-4567"),
        )
        assertEquals(
            SongRelocationRoute.COPY_VERIFY_DELETE,
            relocationRoute(36, "external"),
        )
    }

    @Test
    fun copyMustMatchBothSizeAndSha256BeforeSourceCanBeDeleted() {
        val hash = byteArrayOf(1, 2, 3)
        assertTrue(copyVerificationPassed(100, 100, hash, hash.copyOf()))
        assertFalse(copyVerificationPassed(100, 99, hash, hash.copyOf()))
        assertFalse(copyVerificationPassed(100, 100, hash, byteArrayOf(1, 2, 4)))
        assertFalse(copyVerificationPassed(0, 0, hash, hash.copyOf()))
    }

    @Test
    fun recoveryCleansPreDeleteCopiesAndCommitsPostDeleteMappings() {
        assertEquals(
            MoveRecoveryAction.RETAIN_TARGET_FOR_RECOVERY,
            moveRecoveryAction(MoveItemStatus.COPIED, sourceExists = true),
        )
        assertEquals(
            MoveRecoveryAction.COMMIT_REMAP,
            moveRecoveryAction(MoveItemStatus.COPIED, sourceExists = false),
        )
        assertEquals(
            MoveRecoveryAction.COMMIT_REMAP,
            moveRecoveryAction(MoveItemStatus.SOURCE_DELETED, sourceExists = false),
        )
        assertEquals(
            MoveRecoveryAction.CONTINUE,
            moveRecoveryAction(MoveItemStatus.COMMITTED, sourceExists = false),
        )
    }

    @Test
    fun legacyPreparedRecoveryNeverDeletesTheOnlyMovedFile() {
        assertEquals(
            LegacyPreparedRecoveryAction.COMMIT_EXISTING_ROW,
            legacyPreparedRecoveryAction(
                mediaRowPointsAtDestination = true,
                sourcePresent = true,
                destinationVerified = true,
            ),
        )
        assertEquals(
            LegacyPreparedRecoveryAction.COMMIT_VERIFIED_DESTINATION,
            legacyPreparedRecoveryAction(
                mediaRowPointsAtDestination = false,
                sourcePresent = false,
                destinationVerified = true,
            ),
        )
        assertEquals(
            LegacyPreparedRecoveryAction.RETAIN_VERIFIED_TARGET,
            legacyPreparedRecoveryAction(
                mediaRowPointsAtDestination = false,
                sourcePresent = true,
                destinationVerified = true,
            ),
        )
        assertEquals(
            LegacyPreparedRecoveryAction.FAIL_WITHOUT_DELETING_TARGET,
            legacyPreparedRecoveryAction(
                mediaRowPointsAtDestination = true,
                sourcePresent = true,
                destinationVerified = false,
            ),
        )
        assertEquals(
            LegacyPreparedRecoveryAction.FAIL_WITHOUT_DELETING_TARGET,
            legacyPreparedRecoveryAction(
                mediaRowPointsAtDestination = false,
                sourcePresent = false,
                destinationVerified = false,
            ),
        )
    }

    @Test
    fun pendingMediaStoreMarkerIsStableAndScopedToOneJournalItem() {
        assertEquals(
            "yinlan-pending-move:operation-7:42",
            pendingMoveDestinationMarker("operation-7", 42L),
        )
        assertFalse(
            pendingMoveDestinationMarker("operation-7", 42L) ==
                pendingMoveDestinationMarker("operation-7", 43L)
        )
        assertEquals(
            "yinlan-pending-move-operation-7-42.tmp",
            pendingMoveDestinationDisplayName("operation-7", 42L),
        )
        assertFalse(
            pendingMoveDestinationDisplayName("operation-7", 42L) ==
                pendingMoveDestinationDisplayName("operation-7", 43L)
        )
    }

    @Test
    fun syntheticMergedMediaUriIsNeverTreatedAsAMutableVolumeUri() {
        assertTrue(
            isSyntheticExternalMediaUri("content://media/external/audio/media/42")
        )
        assertTrue(
            isSyntheticExternalMediaUri("content://10@media/external/audio/media/42?foo=bar")
        )
        assertFalse(
            isSyntheticExternalMediaUri("content://media/external_primary/audio/media/42")
        )
        assertFalse(
            isSyntheticExternalMediaUri("content://media/0123-4567/audio/media/42")
        )
    }

    @Test
    fun mediaStorePublicationRequiresExactlyOneUpdatedRow() {
        assertTrue(mediaStorePublishSucceeded(1))
        assertFalse(mediaStorePublishSucceeded(0))
        assertFalse(mediaStorePublishSucceeded(2))
    }

    @Test
    fun bareIdResolutionFailsClosedWhenAnyConcreteVolumeQueryIsInaccessible() {
        assertEquals(
            listOf("primary", "secondary"),
            completeMediaRowQuery(
                listOf(
                    MediaRowQueryResult.Found("primary"),
                    MediaRowQueryResult.Absent,
                    MediaRowQueryResult.Found("secondary"),
                )
            ),
        )
        assertEquals(
            null,
            completeMediaRowQuery(
                listOf(
                    MediaRowQueryResult.Found("coincidentally-visible-row"),
                    MediaRowQueryResult.Inaccessible,
                )
            ),
        )
    }

    @Test
    fun pendingDeletionGateHashesPrivateTargetThenSourceAndShortCircuits() {
        val calls = mutableListOf<String>()
        assertEquals(
            DeletionIntegrityResult.VERIFIED,
            pendingDeletionIntegrityResult(
                destinationMatches = { calls += "destination"; true },
                sourceMatches = { calls += "source"; true },
            ),
        )
        assertEquals(listOf("destination", "source"), calls)

        calls.clear()
        assertEquals(
            DeletionIntegrityResult.DESTINATION_CHANGED,
            pendingDeletionIntegrityResult(
                destinationMatches = { calls += "destination"; false },
                sourceMatches = { calls += "source"; true },
            ),
        )
        assertEquals(listOf("destination"), calls)
    }

    @Test
    fun destinationIdMustResolveOnlyToTheExactCreatedUriBeforeRemap() {
        val destination = "content://media/external_primary/audio/media/17"
        assertTrue(concreteDestinationIdentityIsUnique(destination, listOf(destination)))
        assertFalse(
            concreteDestinationIdentityIsUnique(
                destination,
                listOf(destination, "content://media/0123-4567/audio/media/17"),
            )
        )
        assertFalse(
            concreteDestinationIdentityIsUnique(
                destination,
                listOf("content://media/0123-4567/audio/media/17"),
            )
        )
        assertFalse(concreteDestinationIdentityIsUnique(destination, emptyList()))
    }

    @Test
    fun safeTitleFallbackAndExtensionRemovalAreDeterministic() {
        assertEquals("concert.final", defaultVideoTitle("concert.final.mp4"))
        assertEquals("视频提取歌曲", defaultVideoTitle(""))
    }
}
