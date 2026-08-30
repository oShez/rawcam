package com.shez.rawcam.ui

import com.shez.rawcam.camera.CameraController
import com.shez.rawcam.camera.ControlTier
import com.shez.rawcam.camera.LensProfile
import com.shez.rawcam.camera.RectSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private val RAW_SPEC_FOR_TEST = CameraController.RawSpec(
    width = 4096, height = 3072, cfa = 0, whiteLevel = 16383,
    blackLevel = intArrayOf(1024, 1024, 1024, 1024),
    colorMatrix1 = FloatArray(9), isoRange = 50..6400, maxFps = 30,
    minFocusDiopters = 0f, deviceName = "24030PN60G",
    illuminant1 = 21, illuminant2 = 0, colorMatrix2 = FloatArray(9),
)

// Ruling 7: captureRateKey reads only lens.cameraId, so this fixture exists purely
// to make the lens argument non-null -- the other 24 fields are valid but not
// meant to be realistic.
private val LENS_FOR_TEST = LensProfile(
    cameraId = "0", label = "", focalMm = null, equivFocalMm = null, fovMetric = 0f,
    sizes = emptyList(), cfa = 0, whiteLevel = 16383, blackLevel = IntArray(4),
    colorMatrix1 = FloatArray(9), colorMatrix2 = null, illuminant1 = null, illuminant2 = null,
    isoRange = 50..6400, exposureRangeNs = null, minFocusDiopters = 0f,
    activeArray = RectSpec(0, 0, 4096, 3072), maxZoomRatio = 1f, oisModes = null,
    sensorOrientation = null, standalone = false, isMain = true,
    controlTier = ControlTier.FULL, defaulted = emptySet(),
)

class RecordBitDepthClampTest {
    // Measured 2026-08-30: main cam whiteLevel 16383 (14-bit), ultra-wide 1023 (10-bit).
    private val main = 16383
    private val ultraWide = 1023

    @Test fun nativeResolvesToTheSensorsOwnDepth() {
        assertEquals(14, effectiveBitDepth(main, 0))
        assertEquals(10, effectiveBitDepth(ultraWide, 0))
    }

    @Test fun aRequestBelowNativeIsHonoured() {
        assertEquals(12, effectiveBitDepth(main, 12))
        assertEquals(8, effectiveBitDepth(ultraWide, 8))
    }

    @Test fun aRequestAboveNativeClampsDownToNative() {
        assertEquals(10, effectiveBitDepth(ultraWide, 12))
        assertEquals(10, effectiveBitDepth(ultraWide, 14))
    }

    @Test fun depthIsAnAxisOfTheCaptureRateKey() {
        // Without this, a rate measured at 14-bit mispredicts a 12-bit take and
        // re-breaks the time-left readout.
        val a = captureRateKey(LENS_FOR_TEST, RAW_SPEC_FOR_TEST, true, null, 14)
        val b = captureRateKey(LENS_FOR_TEST, RAW_SPEC_FOR_TEST, true, null, 12)
        assertNotEquals(a, b)
    }
}
