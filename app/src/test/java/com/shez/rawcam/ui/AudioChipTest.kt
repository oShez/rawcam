package com.shez.rawcam.ui

import com.shez.rawcam.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChipTest {

    private fun settings(recordAudio: Boolean, gainDb: Float) =
        Settings(recordAudio = recordAudio, audioGainDb = gainDb)

    @Test
    fun `audio off reads OFF regardless of the saved gain`() {
        assertEquals("AUDIO OFF", audioChipLabel(settings(false, 0f)))
        // Gain survives in Settings while audio is off; the chip must not imply
        // it is doing anything.
        assertEquals("AUDIO OFF", audioChipLabel(settings(false, 12f)))
    }

    @Test
    fun `unity gain is bare AUDIO, not a redundant 0dB`() {
        assertEquals("AUDIO", audioChipLabel(settings(true, 0f)))
    }

    @Test
    fun `boost carries an explicit plus sign`() {
        assertEquals("AUDIO +6dB", audioChipLabel(settings(true, 6f)))
        assertEquals("AUDIO +30dB", audioChipLabel(settings(true, 30f)))
    }

    @Test
    fun `trim carries a minus sign`() {
        assertEquals("AUDIO -12dB", audioChipLabel(settings(true, -12f)))
        assertEquals("AUDIO -20dB", audioChipLabel(settings(true, -20f)))
    }

    @Test
    fun `a fractional gain rounds rather than printing a decimal into the chip`() {
        // SettingsRepository coerces into -20..30 but does not snap to the eight
        // stops, so a value off-stop must still render as a short chip label.
        assertEquals("AUDIO +6dB", audioChipLabel(settings(true, 5.6f)))
        assertEquals("AUDIO -6dB", audioChipLabel(settings(true, -5.6f)))
    }

    @Test
    fun `a gain that rounds to zero collapses to bare AUDIO`() {
        // Otherwise the chip would read "AUDIO +0dB", which claims a trim the
        // rounded display cannot show.
        assertEquals("AUDIO", audioChipLabel(settings(true, 0.4f)))
        assertEquals("AUDIO", audioChipLabel(settings(true, -0.4f)))
    }
}
