package com.melody.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongTest {

    @Test
    fun formatsZeroNegativeMinutesAndHours() {
        assertEquals("0:00", 0L.asDuration())
        assertEquals("0:00", (-1L).asDuration())
        assertEquals("1:05", 65_000L.asDuration())
        assertEquals("1:01:01", 3_661_000L.asDuration())
    }

    @Test
    fun validatesAndNormalizesPlaybackRanges() {
        assertEquals(
            SongPlaybackRange(30_000L, 210_000L),
            SongPlaybackRangeConstraints.requireValid(240_000L, 30_000L, 210_000L),
        )
        assertEquals(
            SongPlaybackRange(30_000L, 240_000L),
            SongPlaybackRangeConstraints.requireValid(240_000L, 30_000L, null),
        )
        assertEquals(
            SongPlaybackRange(9_000L, 10_000L),
            SongPlaybackRangeConstraints.normalize(10_000L, 20_000L, 30_000L),
        )
    }

    @Test
    fun rejectsEmptyOrOutOfBoundsPlaybackRanges() {
        assertEquals(false, SongPlaybackRangeConstraints.isValid(60_000L, -1L, null))
        assertEquals(false, SongPlaybackRangeConstraints.isValid(60_000L, 59_500L, null))
        assertEquals(false, SongPlaybackRangeConstraints.isValid(60_000L, 10_000L, 61_000L))
        assertEquals(true, SongPlaybackRangeConstraints.isValid(500L, 0L, null))
    }

    @Test
    fun playbackRangeReportsDurationAndUsesNullForPhysicalEnd() {
        val trimmed = SongPlaybackRange(30_000L, 210_000L)
        val throughPhysicalEnd = SongPlaybackRange(30_000L, 240_000L)

        assertEquals(180_000L, trimmed.durationMs)
        assertEquals(210_000L, trimmed.persistedEndMs(240_000L))
        assertEquals(null, throughPhysicalEnd.persistedEndMs(240_000L))
    }
}

