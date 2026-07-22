package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterStopsTest {

    private val stops = listOf(2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L, 33_000_000L)

    @Test
    fun `a null range leaves the table untouched`() {
        assertEquals(stops, ShutterStops.available(stops, null))
    }

    @Test
    fun `stops outside the sensor range are dropped`() {
        assertEquals(listOf(4_000_000L, 8_000_000L), ShutterStops.available(stops, 3_000_000L..10_000_000L))
    }

    @Test
    fun `a range excluding every stop keeps the single closest one`() {
        assertEquals(listOf(2_000_000L), ShutterStops.available(stops, 100L..1_000L))
    }
}
