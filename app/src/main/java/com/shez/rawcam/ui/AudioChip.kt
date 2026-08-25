package com.shez.rawcam.ui

import com.shez.rawcam.settings.Settings
import kotlin.math.roundToInt

/**
 * Label for the capture screen's AUDIO chip -- the only always-visible sign of
 * whether the next take will carry sound. Kept pure (and out of RecordScreen's
 * Compose body) so the state table below is unit-testable; the chip's failure
 * tint is a separate concern, driven off live AudioStatus bits at the call site.
 *
 * Unity gain reads as a bare "AUDIO" rather than "AUDIO 0dB": the chip sits in a
 * horizontally scrolling row next to five other parameters, so a value that says
 * nothing costs width that ISO and shutter need. A gain that merely ROUNDS to
 * zero collapses the same way -- "+0dB" would claim a trim this label has no
 * room to spell out.
 */
fun audioChipLabel(settings: Settings): String {
    if (!settings.recordAudio) return "AUDIO OFF"
    val db = settings.audioGainDb.roundToInt()
    return when {
        db == 0 -> "AUDIO"
        db > 0 -> "AUDIO +${db}dB"
        else -> "AUDIO ${db}dB"
    }
}
