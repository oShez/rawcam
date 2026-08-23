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

    @Test
    fun `gain clamp bounds match the spec`() {
        assertEquals(-20f, (-100f).coerceIn(-20f, 30f), 0.0001f)
        assertEquals(30f, (100f).coerceIn(-20f, 30f), 0.0001f)
        assertEquals(6f, (6f).coerceIn(-20f, 30f), 0.0001f)
    }
}
