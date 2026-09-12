package com.melody.local.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackQueueSourceTest {
    @Test
    fun factoriesCreateNormalizedValidSources() {
        assertEquals(
            PlaybackQueueSource(
                type = PlaybackQueueSourceType.LIBRARY,
                name = "全部歌曲",
            ),
            PlaybackQueueSource.library(" "),
        )
        assertEquals(
            PlaybackQueueSource(
                type = PlaybackQueueSourceType.PLAYLIST,
                name = "歌单",
                playlistId = 42L,
            ),
            PlaybackQueueSource.playlist(42L, " "),
        )
    }

    @Test
    fun constructorRejectsMismatchedTypeAndPlaylistIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueSource(PlaybackQueueSourceType.PLAYLIST, "无编号歌单")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueSource(PlaybackQueueSourceType.LIBRARY, "曲库", playlistId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueSource.playlist(0L, "无效歌单")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueueSource(PlaybackQueueSourceType.LIBRARY, " ")
        }
    }
}
