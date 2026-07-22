package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensOrderingTest {

    private fun lensWith(id: String, focalMm: Float, physW: Float = 9.8f) =
        rawLens(id).copy(focalLengthsMm = listOf(focalMm), physicalSizeMm = listOf(physW, 7.3f))

    private fun supported(vararg cams: CameraSnapshot) =
        LensDiscovery.discover(cams.toList()) as DeviceProfile.Supported

    @Test
    fun `lenses sort widest first`() {
        val r = supported(lensWith("4", 12.28f), lensWith("0", 6.9f), lensWith("3", 2.2f))
        assertEquals(listOf("3", "0", "4"), r.lenses.map { it.cameraId })
    }

    @Test
    fun `duplicate focal lengths dedupe, keeping the id with more sizes`() {
        val few = lensWith("0", 6.9f)
        val many = lensWith("7", 6.9f).copy(
            rawSizes = listOf(SizeSpec(4096, 3072, 41_666_666L), SizeSpec(2048, 1536, 20_000_000L)),
        )
        val r = supported(few, many)
        assertEquals(listOf("7"), r.lenses.map { it.cameraId })
    }

    @Test
    fun `labels use the 35mm equivalent`() {
        val r = supported(lensWith("0", 6.9f))
        assertTrue(r.lenses.single().label.endsWith("mm"))
    }

    @Test
    fun `lenses without focal data get a 1-based ordinal label over the sorted list`() {
        val noFocal = rawLens("0").copy(focalLengthsMm = null, physicalSizeMm = null)
        val r = supported(noFocal)
        assertEquals("LENS 1", r.lenses.single().label)
    }

    @Test
    fun `exactly one lens is main and mainIndex points at it`() {
        val r = supported(lensWith("3", 2.2f), lensWith("0", 6.9f), lensWith("4", 12.28f))
        assertEquals(1, r.lenses.count { it.isMain })
        assertTrue(r.lenses[r.mainIndex].isMain)
    }

    @Test
    fun `with no focal data at all mainIndex still resolves to a real lens`() {
        val a = rawLens("0").copy(focalLengthsMm = null, physicalSizeMm = null)
        val b = rawLens("2").copy(focalLengthsMm = null, physicalSizeMm = null,
            rawSizes = listOf(SizeSpec(2048, 1536, 41_666_666L)))
        val r = supported(a, b)
        assertTrue(r.mainIndex in r.lenses.indices)
        assertEquals(1, r.lenses.count { it.isMain })
    }
}
