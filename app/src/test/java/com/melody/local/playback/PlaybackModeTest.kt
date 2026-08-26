package com.melody.local.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeTest {

    @Test
    fun playbackModesUseExpectedRepeatPolicies() {
        assertEquals(QueueRepeat.NONE, repeatForMode(PlaybackMode.SEQUENTIAL))
        assertEquals(QueueRepeat.ALL, repeatForMode(PlaybackMode.LOOP))
        assertEquals(QueueRepeat.ALL, repeatForMode(PlaybackMode.RANDOM))
        assertEquals(QueueRepeat.ONE, repeatForMode(PlaybackMode.SINGLE))
        assertEquals(QueueRepeat.ALL, repeatForMode(PlaybackMode.REVERSE))
    }

    @Test
    fun reverseShuffleOrderTraversesFromHighToLowIndexes() {
        val order = ReverseShuffleOrder(4)

        assertEquals(3, order.firstIndex)
        assertEquals(0, order.lastIndex)
        assertEquals(2, order.getNextIndex(3))
        assertEquals(0, order.getNextIndex(1))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(0))
        assertEquals(1, order.getPreviousIndex(0))
        assertEquals(3, order.getPreviousIndex(2))
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(3))
    }

    @Test
    fun emptyReverseOrderHasNoFirstOrLastItem() {
        val order = ReverseShuffleOrder(0)

        assertEquals(0, order.length)
        assertEquals(C.INDEX_UNSET, order.firstIndex)
        assertEquals(C.INDEX_UNSET, order.lastIndex)
    }

    @Test
    fun reverseOrderTracksPlaylistInsertionsRemovalsAndClear() {
        val order = ReverseShuffleOrder(4)

        assertEquals(6, order.cloneAndInsert(2, 2).length)
        assertEquals(2, order.cloneAndRemove(1, 3).length)
        assertEquals(0, order.cloneAndClear().length)
    }
}
