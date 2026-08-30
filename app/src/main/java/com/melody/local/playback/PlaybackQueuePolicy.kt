package com.melody.local.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

enum class PlaybackMode {
    SEQUENTIAL,
    LOOP,
    RANDOM,
    SINGLE,
    REVERSE,
}

internal enum class QueueRepeat {
    NONE,
    ALL,
    ONE,
}

internal fun repeatForMode(mode: PlaybackMode): QueueRepeat = when (mode) {
    PlaybackMode.LOOP, PlaybackMode.RANDOM, PlaybackMode.REVERSE -> QueueRepeat.ALL
    PlaybackMode.SINGLE -> QueueRepeat.ONE
    PlaybackMode.SEQUENTIAL -> QueueRepeat.NONE
}

@OptIn(markerClass = [UnstableApi::class])
internal class ReverseShuffleOrder(
    private val length: Int,
) : ShuffleOrder {
    override fun getLength(): Int = length

    override fun getNextIndex(index: Int): Int =
        if (index > 0) index - 1 else C.INDEX_UNSET

    override fun getPreviousIndex(index: Int): Int =
        if (index in 0 until length - 1) index + 1 else C.INDEX_UNSET

    override fun getLastIndex(): Int = if (length > 0) 0 else C.INDEX_UNSET

    override fun getFirstIndex(): Int = if (length > 0) length - 1 else C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
        ReverseShuffleOrder(length + insertionCount)

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
        ReverseShuffleOrder((length - (indexToExclusive - indexFrom)).coerceAtLeast(0))

    override fun cloneAndClear(): ShuffleOrder = ReverseShuffleOrder(0)
}

