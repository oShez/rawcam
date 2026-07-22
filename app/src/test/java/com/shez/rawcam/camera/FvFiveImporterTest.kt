package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FvFiveImporterTest {

    private fun sampleOrNull(): String? =
        javaClass.classLoader?.getResourceAsStream("fv5/samsung_sm-g975f_beyond2.json")
            ?.bufferedReader()?.readText()

    @Test
    fun `imports the free sample into snapshots`() {
        val raw = sampleOrNull()
        // Skips cleanly when the corpus is absent -- the suite must stay green
        // without any licensed or downloaded data. See the spec's governing rule.
        assumeTrue("FV-5 sample not present; skipping", raw != null)
        val set = FvFiveImporter.import(raw!!)
        assertTrue(set.cameras.isNotEmpty())
        val back = set.cameras.first { it.facing == 1 }
        assertEquals(1023, back.whiteLevel)
        assertEquals(1, back.cfa) // GRBG
        assertEquals(90, back.sensorOrientation)
    }

    @Test
    fun `imported snapshots resolve without throwing`() {
        val raw = sampleOrNull()
        assumeTrue("FV-5 sample not present; skipping", raw != null)
        val r = LensDiscovery.discover(FvFiveImporter.import(raw!!).cameras)
        assertTrue(r is DeviceProfile.Supported || r is DeviceProfile.Unsupported)
    }
}
