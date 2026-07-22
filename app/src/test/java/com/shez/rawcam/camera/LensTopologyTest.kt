package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Topology rules found the hard way on-device (Xiaomi 14 Ultra, 2026-07-22):
 * tagging a session with the id of the camera being opened -- or with any id
 * that is not a physical child of the opened logical camera -- fails session
 * configuration. So [LensProfile.standalone] must be derived from topology
 * relative to the primary logical camera, and the logical container itself must
 * not appear as a lens when its children do.
 */
class LensTopologyTest {

    /** Xiaomi-shaped device: logical 0 (children 2,3), hidden standalone 4,
     * and a listed-but-not-child extra 6. */
    private fun xiaomiShaped(): List<CameraSnapshot> {
        val logical = rawLens("0").copy(physicalIds = listOf("2", "3"))
        val wide = rawLens("2")
        val ultra = rawLens("3").copy(focalLengthsMm = listOf(2.2f))
        val hiddenTele = rawLens("4").copy(focalLengthsMm = listOf(12.28f), standalone = true)
        val listedExtra = rawLens("6").copy(focalLengthsMm = listOf(5.0f))
        return listOf(logical, wide, ultra, hiddenTele, listedExtra)
    }

    private fun supported(cams: List<CameraSnapshot>) =
        LensDiscovery.discover(cams) as DeviceProfile.Supported

    @Test
    fun `logical container is not a lens when its children survive`() {
        val lenses = supported(xiaomiShaped()).lenses
        assertFalse(lenses.any { it.cameraId == "0" })
        assertTrue(lenses.any { it.cameraId == "2" })
    }

    @Test
    fun `children of the primary logical are taggable, everything else standalone`() {
        val lenses = supported(xiaomiShaped()).lenses.associateBy { it.cameraId }
        assertFalse(lenses.getValue("2").standalone)
        assertFalse(lenses.getValue("3").standalone)
        assertTrue(lenses.getValue("4").standalone)
        assertTrue(lenses.getValue("6").standalone)
    }

    @Test
    fun `logical container with no surviving children stays as a standalone lens`() {
        val logical = rawLens("0").copy(physicalIds = listOf("2"))
        val deadChild = rawLens("2", cfa = null)
        val r = supported(listOf(logical, deadChild))
        val lens = r.lenses.single()
        assertEquals("0", lens.cameraId)
        assertTrue(lens.standalone)
    }

    @Test
    fun `single camera with no logical parent is standalone`() {
        val lens = supported(listOf(rawLens("0"))).lenses.single()
        assertTrue(lens.standalone)
    }

    @Test
    fun `dedupe prefers a taggable child over a standalone twin with more sizes`() {
        val logical = rawLens("0").copy(physicalIds = listOf("2"))
        val child = rawLens("2")
        val twin = rawLens("6").copy(
            rawSizes = listOf(SizeSpec(4096, 3072, 41_666_666L), SizeSpec(2048, 1536, 20_000_000L)),
        )
        val lenses = supported(listOf(logical, child, twin)).lenses
        assertEquals(listOf("2"), lenses.map { it.cameraId })
    }

    @Test
    fun `container exclusion notes why`() {
        val notes = supported(xiaomiShaped()).notes
        assertTrue(notes.any { it.cameraId == "0" && !it.accepted && it.message.contains("container") })
    }
}
