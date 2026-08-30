package com.shez.rawcam.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordBitDepthTest {
    @Test fun defaultsToNative() {
        assertEquals(0, Settings().recordBitDepth)
    }

    @Test fun theOfferedDepthsAreNativeAndTheFourRealOnes() {
        // No 16: no sensor here delivers it. Raw16 is a container, not a precision.
        assertEquals(listOf(0, 14, 12, 10, 8), RECORD_BIT_DEPTHS)
    }

    @Test fun everyOfferedDepthSurvivesCoercion() {
        for (d in RECORD_BIT_DEPTHS) {
            assertEquals(d, Settings(recordBitDepth = d).coerced().recordBitDepth)
        }
    }

    @Test fun anUnknownDepthFallsBackToNative() {
        assertEquals(0, Settings(recordBitDepth = 11).coerced().recordBitDepth)
        assertEquals(0, Settings(recordBitDepth = 16).coerced().recordBitDepth)
        assertEquals(0, Settings(recordBitDepth = -1).coerced().recordBitDepth)
    }
}
