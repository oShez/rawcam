package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSnapshotTest {

    private fun minimalSnapshot() = CameraSnapshot(
        cameraId = "0",
        facing = 1,
        capabilities = listOf(0, 1, 3),
        physicalIds = listOf("2", "3"),
        rawSizes = listOf(SizeSpec(4096, 3072, 41_666_666L)),
        cfa = 0,
        whiteLevel = 1023,
        blackLevel = listOf(64, 64, 64, 64),
    )

    @Test
    fun `round trips through json`() {
        val original = SnapshotSet(model = "Pixel 7 Pro", sdkInt = 34, cameras = listOf(minimalSnapshot()))
        val restored = SnapshotSet.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `absent optional fields decode as null`() {
        val restored = SnapshotSet.fromJson(
            """{"model":"X","sdkInt":34,"cameras":[{"cameraId":"0","facing":1}]}"""
        )
        val cam = restored.cameras.single()
        assertNull(cam.focalLengthsMm)
        assertNull(cam.colorTransform1)
        assertEquals(emptyList<SizeSpec>(), cam.rawSizes)
    }
}
