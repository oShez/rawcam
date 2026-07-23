package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenFixtureTest {

    private fun rawOrNull(name: String): String? =
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")?.bufferedReader()?.readText()

    private fun load(name: String): SnapshotSet =
        SnapshotSet.fromJson(
            checkNotNull(rawOrNull(name)) { "fixture not found: $name" }
        )

    private fun profileOf(name: String) = LensDiscovery.discover(load(name).cameras)

    @Test
    fun `xiaomi 14 ultra yields four lenses with the main at 23mm`() {
        val r = profileOf("xiaomi-14-ultra.json") as DeviceProfile.Supported
        assertEquals(4, r.lenses.size)
        assertEquals(listOf("12mm", "23mm", "74mm", "117mm"), r.lenses.map { it.label })
        assertEquals("23mm", r.lenses[r.mainIndex].label)
        assertTrue(r.lenses.any { it.standalone })
    }

    @Test
    fun `pixel 7 pro yields three lenses with the main at 24mm`() {
        // Real fixture captured 2026-07-23 via Settings -> Dump characteristics on-device.
        // The plan's original guess of "two lenses" predated ground truth: this Pixel 7 Pro
        // has three back-facing physical cameras (ultrawide/main/telephoto), all FULL control.
        val r = profileOf("pixel-7-pro.json") as DeviceProfile.Supported
        assertEquals(3, r.lenses.size)
        assertEquals(listOf("13mm", "24mm", "117mm"), r.lenses.map { it.label })
        assertEquals("24mm", r.lenses[r.mainIndex].label)
        assertTrue(r.lenses.all { it.controlTier == ControlTier.FULL })
    }

    @Test
    fun `galaxy s10 plus back sensor is GRBG and supported`() {
        val r = profileOf("galaxy-s10plus-fv5.json") as DeviceProfile.Supported
        assertEquals(1, r.lenses.single().cfa)
    }

    @Test
    fun `a device with no RAW is unsupported`() {
        assertEquals(UnsupportedReason.NO_RAW_CAPABILITY,
            (profileOf("no-raw.json") as DeviceProfile.Unsupported).reason)
    }

    @Test
    fun `RAW without manual sensor still records, as AUTO_ONLY`() {
        val r = profileOf("raw-without-manual.json") as DeviceProfile.Supported
        assertEquals(ControlTier.AUTO_ONLY, r.lenses.single().controlTier)
    }

    @Test
    fun `every shape fixture resolves without throwing`() {
        listOf("orientation-270.json", "missing-physical-size.json", "missing-color-matrix.json",
            "samsung-high-ids.json", "permission-redacted.json", "single-lens-legacy.json",
            "zero-raw-sizes.json", "absurd-values.json").forEach { name ->
            val r = profileOf(name)
            assertTrue("$name produced neither result",
                r is DeviceProfile.Supported || r is DeviceProfile.Unsupported)
        }
    }
}
