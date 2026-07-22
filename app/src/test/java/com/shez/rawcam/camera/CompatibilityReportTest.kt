package com.shez.rawcam.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityReportTest {

    @Test
    fun `supported report names every lens and its tier`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0"), rawLens("2").copy(
            focalLengthsMm = listOf(2.2f))))
        val text = CompatibilityReport.render(profile, "Test Device", 34)
        assertTrue(text.contains("SUPPORTED"))
        assertTrue(text.contains("Test Device"))
        assertTrue(text.contains("FULL"))
    }

    @Test
    fun `unsupported report states the reason and the enumeration log`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0").copy(capabilities = listOf(1))))
        val text = CompatibilityReport.render(profile, "Cheap Phone", 33)
        assertTrue(text.contains("NOT SUPPORTED"))
        assertTrue(text.contains("NO_RAW_CAPABILITY"))
    }

    @Test
    fun `defaulted fields are called out explicitly`() {
        val profile = LensDiscovery.discover(listOf(rawLens("0").copy(colorTransform1 = null)))
        val text = CompatibilityReport.render(profile, "Odd Phone", 34)
        assertTrue(text.contains("DEFAULTED"))
        assertTrue(text.contains("COLOR_TRANSFORM1"))
    }
}
