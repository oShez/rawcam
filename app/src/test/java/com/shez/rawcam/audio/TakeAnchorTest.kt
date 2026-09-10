package com.shez.rawcam.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeAnchorTest {

    /** Epoch nanoseconds at [h]:[m]:[s] UTC on epoch day 20000 (2024-10-04). */
    private fun epochNsAt(h: Int, m: Int, s: Int): Long =
        20_000L * 86_400_000_000_000L + (h * 3600L + m * 60L + s) * 1_000_000_000L

    @Test
    fun `time reference counts sample frames since local midnight`() {
        // BWF's TimeReference is the take's start expressed as sample frames
        // since midnight. It is the audio half of the shared anchor: the DNG
        // sequence carries the same instant as SMPTE timecode, so an NLE can
        // line the two up on import.
        assertEquals(
            45_213L * 48_000L, // 12:33:33 is 45213 s into the day, at 48 kHz
            TakeAnchor.timeReferenceSamples(epochNsAt(12, 33, 33), 0, 48_000),
        )
    }

    @Test
    fun `a sub-second start is not rounded down to the whole second`() {
        // Recording rarely begins exactly on a second. Half a second dropped
        // here is 12 frames out at 24 fps -- far past the point where nominal
        // alignment is any use.
        assertEquals(
            45_213L * 48_000L + 24_000L,
            TakeAnchor.timeReferenceSamples(epochNsAt(12, 33, 33) + 500_000_000L, 0, 48_000),
        )
    }

    @Test
    fun `origination date is the local calendar day of the take`() {
        // Epoch day 20000 is 2024-10-04. The date matters as much as the time:
        // it is half of what a reader has to place the take on a timeline.
        assertEquals("2024-10-04", TakeAnchor.originationDate(epochNsAt(12, 33, 33), 0))
    }

    @Test
    fun `origination time is the local time of day of the take`() {
        assertEquals("12:33:33", TakeAnchor.originationTime(epochNsAt(12, 33, 33), 0))
    }

    @Test
    fun `a west-of-UTC offset can put the take on the previous local day`() {
        // 00:30 UTC on 2024-10-04, shot in New York (UTC-4 in October), is
        // 20:30 on 2024-10-03. The date, the time and the frame count all move
        // together -- and this is the case a truncating remainder gets wrong.
        val anchor = epochNsAt(0, 30, 0)
        assertEquals("2024-10-03", TakeAnchor.originationDate(anchor, -4 * 3600))
        assertEquals("20:30:00", TakeAnchor.originationTime(anchor, -4 * 3600))
        assertEquals(
            (20L * 3600 + 30 * 60) * 48_000L,
            TakeAnchor.timeReferenceSamples(anchor, -4 * 3600, 48_000),
        )
    }

    @Test
    fun `an offset that is not a whole hour is honoured`() {
        // India is UTC+5:30 and Nepal UTC+5:45; rounding the offset to hours
        // would put the take half an hour or more from the operator's clock.
        assertEquals("06:00:00", TakeAnchor.originationTime(epochNsAt(0, 30, 0), 5 * 3600 + 1800))
        assertEquals("06:15:00", TakeAnchor.originationTime(epochNsAt(0, 30, 0), 5 * 3600 + 2700))
    }
    @Test
    fun `the frame count and the clock strings agree when the local day goes negative`() {
        // Not a plausible capture time -- a device clock does not report 1970 --
        // but it is the one input that separates a floored remainder from a
        // truncated one, and this class derives the strings and the frame count
        // by two different routes (java.time and a manual modulo). They have to
        // land on the same instant, and they have to match nsSinceLocalMidnight()
        // in core/include/rawcam/timecode.h, which is tested against this same
        // case. A take whose two halves disagree is the one outcome this
        // feature must not produce.
        val twoAmUtcOnEpochDay = 2L * 3600 * 1_000_000_000L
        assertEquals("21:00:00", TakeAnchor.originationTime(twoAmUtcOnEpochDay, -5 * 3600))
        assertEquals(
            21L * 3600 * 48_000L,
            TakeAnchor.timeReferenceSamples(twoAmUtcOnEpochDay, -5 * 3600, 48_000),
        )
    }
}
