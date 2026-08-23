package com.shez.rawcam.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsDefaultsTest {

    @Test
    fun `audio defaults are off, system default input, unity gain`() {
        val s = Settings()
        assertEquals(false, s.recordAudio)
        assertEquals("", s.audioInputKey)
        assertEquals(0f, s.audioGainDb, 0.0001f)
    }

    // A previous "gain clamp bounds match the spec" test here asserted only
    // Float.coerceIn's own documented behavior against literal -20f/30f/6f --
    // it would still pass if SettingsRepository's actual clamp at
    // `updated.audioGainDb.coerceIn(-20f, 30f)` were deleted entirely, so it
    // was coverage in appearance only. Removed rather than kept as a test that
    // reads as verifying something real when it verifies nothing.
}
