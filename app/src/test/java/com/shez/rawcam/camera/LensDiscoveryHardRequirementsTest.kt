package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAW=3 and MANUAL_SENSOR=1 in REQUEST_AVAILABLE_CAPABILITIES. */
private const val CAP_MANUAL = 1
private const val CAP_RAW = 3

/** Shared builder for a healthy back RAW lens; other test classes reuse it. */
fun rawLens(
    id: String,
    cfa: Int? = 0,
    white: Int? = 1023,
    black: List<Int>? = listOf(64, 64, 64, 64),
    sizes: List<SizeSpec> = listOf(SizeSpec(4096, 3072, 41_666_666L)),
) = CameraSnapshot(
    cameraId = id, facing = 1, capabilities = listOf(CAP_MANUAL, CAP_RAW),
    rawSizes = sizes, cfa = cfa, whiteLevel = white, blackLevel = black,
    focalLengthsMm = listOf(6.9f), physicalSizeMm = listOf(9.8f, 7.3f),
    isoRange = listOf(50, 3200), activeArray = RectSpec(0, 0, 4096, 3072),
)

class LensDiscoveryHardRequirementsTest {

    @Test
    fun `no cameras at all is unsupported, not a crash`() {
        val result = LensDiscovery.discover(emptyList())
        assertEquals(UnsupportedReason.NO_BACK_CAMERA, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `back camera without RAW capability is unsupported`() {
        val cam = rawLens("0").copy(capabilities = listOf(CAP_MANUAL))
        val result = LensDiscovery.discover(listOf(cam))
        assertEquals(UnsupportedReason.NO_RAW_CAPABILITY, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `RAW capability but zero RAW sizes is unsupported`() {
        val result = LensDiscovery.discover(listOf(rawLens("0", sizes = emptyList())))
        assertEquals(UnsupportedReason.NO_USABLE_RAW_SIZES, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `all-null characteristics reads as permission redacted`() {
        val redacted = CameraSnapshot(cameraId = "0", facing = null, capabilities = emptyList())
        val result = LensDiscovery.discover(listOf(redacted))
        assertEquals(UnsupportedReason.PERMISSION_REDACTED, (result as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `missing CFA rejects that lens but keeps a valid sibling`() {
        val result = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2", cfa = null)))
        val ok = result as DeviceProfile.Supported
        assertEquals(listOf("0"), ok.lenses.map { it.cameraId })
        assertTrue(ok.notes.any { it.cameraId == "2" && !it.accepted && it.message.contains("CFA") })
    }

    @Test
    fun `missing black level rejects that lens`() {
        val result = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2", black = null)))
        assertEquals(listOf("0"), (result as DeviceProfile.Supported).lenses.map { it.cameraId })
    }

    @Test
    fun `front cameras are ignored entirely`() {
        val front = rawLens("1").copy(facing = 0)
        val result = LensDiscovery.discover(listOf(rawLens("0"), front))
        assertEquals(listOf("0"), (result as DeviceProfile.Supported).lenses.map { it.cameraId })
    }
}
