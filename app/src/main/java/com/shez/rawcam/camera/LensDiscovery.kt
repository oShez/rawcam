package com.shez.rawcam.camera

/**
 * Pure enumeration: plain snapshots in, a DeviceProfile out. Contains no
 * android.* imports by design, so every branch is reachable from a JVM unit test
 * using a JSON fixture captured from a real phone.
 *
 * CONTRACT: this function never throws, for any input. Absence of a field is
 * data, not an error. Enforced by LensDiscoveryFuzzTest.
 */
object LensDiscovery {

    private const val CAP_MANUAL_SENSOR = 1
    private const val CAP_RAW = 3
    private const val FACING_BACK = 1

    fun discover(cameras: List<CameraSnapshot>): DeviceProfile {
        val notes = mutableListOf<ProfileNote>()

        if (cameras.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_BACK_CAMERA, "No cameras reported by the system.", notes,
            )
        }

        // Every characteristic blank across every camera is the signature of an
        // app without CAMERA permission: Android redacts the keys rather than
        // failing the query. Distinguishing this from genuinely poor hardware
        // matters, because the fix (grant permission) is entirely different.
        if (cameras.all { it.facing == null && it.capabilities.isEmpty() && it.rawSizes.isEmpty() }) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.PERMISSION_REDACTED,
                "Camera details are hidden until camera permission is granted.", notes,
            )
        }

        val back = cameras.filter { it.facing == FACING_BACK }
        if (back.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_BACK_CAMERA, "This device reports no back-facing camera.", notes,
            )
        }

        if (back.none { it.capabilities.contains(CAP_RAW) }) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_RAW_CAPABILITY,
                "This phone's cameras don't provide RAW capture, which RawCam requires.", notes,
            )
        }

        val built = back.mapNotNull { buildLens(it, notes) }
        if (built.isEmpty()) {
            return DeviceProfile.Unsupported(
                UnsupportedReason.NO_USABLE_RAW_SIZES,
                "RAW is advertised but no camera offers a usable RAW image size.", notes,
            )
        }
        return DeviceProfile.Supported(built, mainIndex = 0, notes = notes)
    }

    /** Null when a HARD requirement is missing; a note records why. */
    private fun buildLens(cam: CameraSnapshot, notes: MutableList<ProfileNote>): LensProfile? {
        fun reject(why: String): LensProfile? {
            notes += ProfileNote(cam.cameraId, accepted = false, message = why)
            return null
        }
        if (!cam.capabilities.contains(CAP_RAW)) return reject("no RAW capability")
        if (cam.rawSizes.isEmpty()) return reject("no RAW output sizes")
        val cfa = cam.cfa ?: return reject("no CFA (colour filter arrangement)")
        val white = cam.whiteLevel ?: return reject("no white level")
        val black = cam.blackLevel?.takeIf { it.size == 4 } ?: return reject("no 4-entry black level")

        notes += ProfileNote(cam.cameraId, accepted = true, message = "accepted")
        return LensProfile(
            cameraId = cam.cameraId, label = cam.cameraId, focalMm = cam.focalLengthsMm?.firstOrNull(),
            equivFocalMm = null, fovMetric = 0f,
            sizes = cam.rawSizes.map {
                LensSizeProfile(it.width, it.height,
                    maxFps = if (it.minFrameDurationNs > 0) (1e9 / it.minFrameDurationNs).toInt() else 30,
                    label = "")
            },
            cfa = cfa, whiteLevel = white, blackLevel = black.toIntArray(),
            colorMatrix1 = FloatArray(9), colorMatrix2 = null,
            illuminant1 = cam.illuminant1, illuminant2 = cam.illuminant2,
            isoRange = 50..800, exposureRangeNs = null, minFocusDiopters = 0f,
            activeArray = cam.activeArray ?: RectSpec(0, 0, cam.rawSizes[0].width, cam.rawSizes[0].height),
            oisModes = cam.oisModes?.toIntArray(), sensorOrientation = cam.sensorOrientation,
            standalone = cam.standalone, isMain = false,
            controlTier = if (cam.capabilities.contains(CAP_MANUAL_SENSOR)) ControlTier.FULL else ControlTier.AUTO_ONLY,
            defaulted = emptySet(),
        )
    }
}
