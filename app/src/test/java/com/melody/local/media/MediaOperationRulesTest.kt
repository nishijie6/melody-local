package com.melody.local.media

import com.melody.local.data.MoveItemStatus
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
            MoveRecoveryAction.CLEAN_TARGET_AND_RETRY,
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
    fun safeTitleFallbackAndExtensionRemovalAreDeterministic() {
        assertEquals("concert.final", defaultVideoTitle("concert.final.mp4"))
        assertEquals("视频提取歌曲", defaultVideoTitle(""))
    }
}
