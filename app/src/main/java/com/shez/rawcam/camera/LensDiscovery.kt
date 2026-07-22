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

    /** Full-frame diagonal, for the 35mm-equivalent crop-factor formula. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.27

    /** Fallback when a sensor exposes no colour calibration: identity. A DNG
     * with an identity ColorMatrix1 still opens and still grades; the lens is
     * flagged uncalibrated in the compatibility report rather than dropped. */
    private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private const val DEFAULT_ISO_LOW = 50
    private const val DEFAULT_ISO_HIGH = 800

    /** Null when a HARD requirement is missing; a note records why. Every SOFT
     * field falls back to a documented default and is recorded in [defaulted]. */
    private fun buildLens(cam: CameraSnapshot, notes: MutableList<ProfileNote>): LensProfile? {
        fun reject(why: String): LensProfile? {
            notes += ProfileNote(cam.cameraId, accepted = false, message = why)
            return null
        }
        if (!cam.capabilities.contains(CAP_RAW)) return reject("no RAW capability")
        val validSizes = cam.rawSizes.filter { it.width > 0 && it.height > 0 }
        if (validSizes.isEmpty()) return reject("no RAW output sizes")
        val cfa = cam.cfa?.takeIf { it in 0..3 } ?: return reject("no usable CFA")
        val white = cam.whiteLevel?.takeIf { it > 0 } ?: return reject("no white level")
        val black = cam.blackLevel?.takeIf { it.size == 4 } ?: return reject("no 4-entry black level")

        val defaulted = mutableSetOf<SnapshotField>()

        val focal = cam.focalLengthsMm?.firstOrNull()?.takeIf { it > 0f }
        if (focal == null) defaulted += SnapshotField.FOCAL_LENGTH

        val physSize = cam.physicalSizeMm?.takeIf { it.size == 2 && it[0] > 0f && it[1] > 0f }
        if (physSize == null) defaulted += SnapshotField.PHYSICAL_SIZE

        // 35mm equivalent = real focal * (full-frame diagonal / this sensor's own
        // measured diagonal). Requires BOTH inputs; null otherwise, and the label
        // falls back to an ordinal (assigned in finishLenses).
        val equivFocal = if (focal != null && physSize != null) {
            val diag = Math.sqrt((physSize[0] * physSize[0] + physSize[1] * physSize[1]).toDouble())
            if (diag > 0) (focal * (FULL_FRAME_DIAGONAL_MM / diag)).toFloat() else null
        } else null

        val cm1 = cam.colorTransform1?.takeIf { it.size == 9 }?.toFloatArray()
            ?: IDENTITY_MATRIX.copyOf().also { defaulted += SnapshotField.COLOR_TRANSFORM1 }
        val cm2 = cam.colorTransform2?.takeIf { it.size == 9 }?.toFloatArray()
        if (cm2 == null) defaulted += SnapshotField.COLOR_TRANSFORM2
        if (cam.illuminant1 == null) defaulted += SnapshotField.ILLUMINANTS

        // A malformed range (wrong arity, inverted, non-positive) is treated as
        // absent rather than coerced -- guessing a sensitivity range would put
        // wrong numbers on a slider the user trusts.
        val iso = cam.isoRange?.takeIf { it.size == 2 && it[0] > 0 && it[1] >= it[0] }
            ?.let { it[0]..it[1] }
        if (iso == null) defaulted += SnapshotField.ISO_RANGE

        val exposure = cam.exposureRangeNs?.takeIf { it.size == 2 && it[0] > 0 && it[1] >= it[0] }
            ?.let { it[0]..it[1] }
        if (exposure == null) defaulted += SnapshotField.EXPOSURE_RANGE

        val largest = validSizes.maxBy { it.width.toLong() * it.height }
        val activeArray = cam.activeArray?.takeIf { it.width > 0 && it.height > 0 }
            ?: RectSpec(0, 0, largest.width, largest.height).also { defaulted += SnapshotField.ACTIVE_ARRAY }

        val minFocus = cam.minFocusDiopters?.takeIf { it >= 0f }
            ?: 0f.also { defaulted += SnapshotField.MIN_FOCUS }
        if (cam.oisModes == null) defaulted += SnapshotField.OIS_MODES

        // FULL requires the MANUAL_SENSOR capability AND a usable ISO range: a
        // manual ISO slider with no real bounds would be a lie. A missing
        // exposure range does NOT demote -- it only means the shutter stop table
        // cannot be intersected (see Task 9).
        val tier = if (cam.capabilities.contains(CAP_MANUAL_SENSOR) && iso != null)
            ControlTier.FULL else ControlTier.AUTO_ONLY

        val maxArea = largest.width.toLong() * largest.height
        notes += ProfileNote(
            cam.cameraId, accepted = true,
            message = if (defaulted.isEmpty()) "accepted"
            else "accepted; defaulted " + defaulted.joinToString(", ") { it.name.lowercase() },
        )
        return LensProfile(
            cameraId = cam.cameraId, label = "", focalMm = focal, equivFocalMm = equivFocal,
            fovMetric = if (physSize != null && focal != null) physSize[0] / focal else 0f,
            sizes = validSizes.sortedByDescending { it.width.toLong() * it.height }.map {
                LensSizeProfile(
                    it.width, it.height,
                    maxFps = if (it.minFrameDurationNs > 0)
                        (1e9 / it.minFrameDurationNs).toInt().coerceIn(1, 240) else 30,
                    label = sizeLabel(it.width, it.height, maxArea),
                )
            },
            cfa = cfa, whiteLevel = white, blackLevel = black.toIntArray(),
            colorMatrix1 = cm1, colorMatrix2 = cm2,
            illuminant1 = cam.illuminant1, illuminant2 = cam.illuminant2,
            isoRange = iso ?: (DEFAULT_ISO_LOW..DEFAULT_ISO_HIGH),
            exposureRangeNs = exposure, minFocusDiopters = minFocus, activeArray = activeArray,
            oisModes = cam.oisModes?.toIntArray(), sensorOrientation = cam.sensorOrientation,
            standalone = cam.standalone, isMain = false, controlTier = tier, defaulted = defaulted,
        )
    }

    /** "4:3" / "16:9" for full-area sizes, "LOW" for binned. Moved verbatim from
     * CameraController.sizeLabel so behaviour on existing devices is unchanged. */
    private fun sizeLabel(w: Int, h: Int, maxArea: Long): String {
        if (w.toLong() * h < maxArea / 2) return "LOW"
        val aspect = w.toFloat() / h
        return when {
            Math.abs(aspect - 4f / 3f) < 0.05f -> "4:3"
            Math.abs(aspect - 16f / 9f) < 0.1f -> "16:9"
            else -> "${h}p"
        }
    }
}
