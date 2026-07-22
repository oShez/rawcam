package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDiscoverySoftFieldsTest {

    private fun onlyLens(cam: CameraSnapshot): LensProfile =
        (LensDiscovery.discover(listOf(cam)) as DeviceProfile.Supported).lenses.single()

    @Test
    fun `missing colour matrix keeps the lens and flags it defaulted`() {
        val lens = onlyLens(rawLens("0").copy(colorTransform1 = null))
        assertTrue(SnapshotField.COLOR_TRANSFORM1 in lens.defaulted)
        assertEquals(9, lens.colorMatrix1.size)
    }

    @Test
    fun `real colour matrix is passed through untouched and not flagged`() {
        val m = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val lens = onlyLens(rawLens("0").copy(colorTransform1 = m))
        assertTrue(SnapshotField.COLOR_TRANSFORM1 !in lens.defaulted)
        assertEquals(1f, lens.colorMatrix1[0], 0f)
    }

    @Test
    fun `missing physical size keeps the lens, drops the 35mm equivalent`() {
        val lens = onlyLens(rawLens("0").copy(physicalSizeMm = null))
        assertTrue(SnapshotField.PHYSICAL_SIZE in lens.defaulted)
        assertEquals(null, lens.equivFocalMm)
    }

    @Test
    fun `missing ISO range demotes the lens to AUTO_ONLY`() {
        val lens = onlyLens(rawLens("0").copy(isoRange = null))
        assertEquals(ControlTier.AUTO_ONLY, lens.controlTier)
        assertTrue(SnapshotField.ISO_RANGE in lens.defaulted)
    }

    @Test
    fun `missing MANUAL_SENSOR capability is AUTO_ONLY but still records`() {
        val lens = onlyLens(rawLens("0").copy(capabilities = listOf(3)))
        assertEquals(ControlTier.AUTO_ONLY, lens.controlTier)
    }

    @Test
    fun `missing exposure range does NOT demote the tier`() {
        val lens = onlyLens(rawLens("0").copy(exposureRangeNs = null))
        assertEquals(ControlTier.FULL, lens.controlTier)
        assertEquals(null, lens.exposureRangeNs)
    }

    @Test
    fun `present exposure range is exposed as a LongRange`() {
        val lens = onlyLens(rawLens("0").copy(exposureRangeNs = listOf(1000L, 500_000_000L)))
        assertEquals(1000L..500_000_000L, lens.exposureRangeNs)
    }

    @Test
    fun `missing active array falls back to the largest RAW size`() {
        val lens = onlyLens(rawLens("0").copy(activeArray = null))
        assertEquals(4096, lens.activeArray.width)
        assertNotNull(lens.activeArray)
    }
}
