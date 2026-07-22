package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlTierTest {

    @Test
    fun `a lens with manual sensor and a real ISO range is FULL`() {
        val r = LensDiscovery.discover(listOf(rawLens("0"))) as DeviceProfile.Supported
        assertEquals(ControlTier.FULL, r.lenses.single().controlTier)
    }

    @Test
    fun `tiers are independent per lens on the same device`() {
        val full = rawLens("0")
        val auto = rawLens("2").copy(capabilities = listOf(3), focalLengthsMm = listOf(2.2f))
        val r = LensDiscovery.discover(listOf(full, auto)) as DeviceProfile.Supported
        assertEquals(ControlTier.AUTO_ONLY, r.lenses.first { it.cameraId == "2" }.controlTier)
        assertEquals(ControlTier.FULL, r.lenses.first { it.cameraId == "0" }.controlTier)
    }
}
