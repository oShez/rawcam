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

    /** A HAL that omits CONTROL_ZOOM_RATIO_RANGE must yield maxZoomRatio 1.0
     *  (no zoom) and say so via ZOOM_RANGE -- never an optimistic guess, which
     *  would let the RAW crop outrun a preview that cannot follow it. */
    @Test
    fun `missing zoom range defaults to no zoom and is flagged`() {
        val lens = onlyLens(rawLens("0").copy(zoomRatioRange = null))
        assertEquals(1.0f, lens.maxZoomRatio, 1e-6f)
        assertTrue(SnapshotField.ZOOM_RANGE in lens.defaulted)
    }

    @Test
    fun `present zoom range is carried through unflagged`() {
        val lens = onlyLens(rawLens("0").copy(zoomRatioRange = listOf(1.0f, 8.0f)))
        assertEquals(8.0f, lens.maxZoomRatio, 1e-6f)
        assertTrue(SnapshotField.ZOOM_RANGE !in lens.defaulted)
    }

    /** A HAL reporting a nonsense upper bound below 1.0 must be treated as
     *  "no zoom", not propagated into the ladder where it would drop even 1x. */
    @Test
    fun `nonsense zoom range is treated as no zoom`() {
        val lens = onlyLens(rawLens("0").copy(zoomRatioRange = listOf(1.0f, 0.5f)))
        assertEquals(1.0f, lens.maxZoomRatio, 1e-6f)
        assertTrue(SnapshotField.ZOOM_RANGE in lens.defaulted)
    }

    /** A HAL can legally report NaN as the upper bound: Range<Float> orders via
     *  boxed Float.compareTo, which ranks NaN above everything, so a Range with
     *  a NaN upper still passes Range's own lower <= upper check. Both `> 1.0f`
     *  and `<= 1.0f` are false for NaN, so a naively mirrored `<=` flag check
     *  would silently stop reporting the substitution even though the value
     *  correctly falls back to 1.0 -- assert BOTH, since the value alone was
     *  already correct before this fix. */
    @Test
    fun `NaN zoom range upper bound is treated as no zoom and is flagged`() {
        val lens = onlyLens(rawLens("0").copy(zoomRatioRange = listOf(1.0f, Float.NaN)))
        assertEquals(1.0f, lens.maxZoomRatio, 1e-6f)
        assertTrue(SnapshotField.ZOOM_RANGE in lens.defaulted)
    }
}
