package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun `pixel 7 pro yields two lenses, both fully manual`() {
        // DEVIATION (pre-approved, see docs/superpowers/open-items-2026-07-22-spec-a.md):
        // the Pixel 7 Pro was not connected during Task 10, so its fixture was never
        // captured -- no pixel-7-pro.json was fabricated. Skip cleanly rather than
        // fail; un-skipping this test (by capturing the real fixture) is owed before
        // this branch merges to main.
        val raw = rawOrNull("pixel-7-pro.json")
        assumeTrue("pixel-7-pro.json fixture not present; skipping (owed before merge)", raw != null)
        val r = LensDiscovery.discover(SnapshotSet.fromJson(raw!!).cameras) as DeviceProfile.Supported
        assertEquals(2, r.lenses.size)
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
