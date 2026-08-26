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
}
