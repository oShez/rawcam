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
        val (lenses, mainIndex) = finishLenses(applyTopology(built, back, notes), back)
        return DeviceProfile.Supported(lenses, mainIndex, notes)
    }

    /**
     * Derive [LensProfile.standalone] from topology and drop the logical
     * container from the lens list. Found on-device (Xiaomi 14 Ultra,
     * 2026-07-22): setPhysicalCameraId() rejects the session when the tag is
     * the opened camera's own id, or any id that is not a physical child of
     * the opened logical camera. So only children of the primary logical are
     * taggable; every other lens -- hidden probed ids, listed non-child ids,
     * and the no-logical fallback -- must open as its own CameraDevice.
     */
    private fun applyTopology(
        built: List<LensProfile>, back: List<CameraSnapshot>, notes: MutableList<ProfileNote>,
    ): List<LensProfile> {
        val primary = back.firstOrNull { it.physicalIds.isNotEmpty() }
        val children = primary?.physicalIds?.toSet() ?: emptySet()

        val retyped = built.map { lens ->
            val standalone = lens.cameraId !in children
            if (lens.standalone == standalone) lens else lens.copy(standalone = standalone)
        }

        // The logical container duplicates whichever child the HAL routes it to;
        // it is a lens of its own only when none of its children survived.
        if (primary != null && retyped.any { it.cameraId in children }) {
            val container = retyped.filter { it.cameraId == primary.cameraId }
            if (container.isNotEmpty()) {
                notes.removeAll { it.cameraId == primary.cameraId && it.accepted }
                notes += ProfileNote(
                    primary.cameraId, accepted = false,
                    message = "logical container; its physical children are the lenses",
                )
                return retyped.filterNot { it.cameraId == primary.cameraId }
            }
        }
        return retyped
    }

    /**
     * Dedupe by focal length (one sensor exposed under two ids -- keep the id
     * offering more sizes), sort widest-first, label, and choose the main lens.
     *
     * Main-lens selection must never fail, because isMain drives which lens the
     * app opens at launch: advertised focal length of the logical camera ->
     * nearest match; no focal data -> largest active array; still ambiguous ->
     * index 0. The old code fell through to 0 silently, which on a device
     * without focal lengths meant launching on the wrong lens.
     */
    private fun finishLenses(
        built: List<LensProfile>, sources: List<CameraSnapshot>,
    ): Pair<List<LensProfile>, Int> {
        val deduped = built
            .groupBy { it.focalMm }
            // A taggable child beats a standalone twin regardless of size count:
            // it is the id the previous enumerator exposed, so WB anchors and
            // session behaviour stay identical across the refactor.
            .map { (focal, group) ->
                if (focal == null) group
                else listOf(group.sortedWith(
                    compareBy<LensProfile> { it.standalone }
                        .thenByDescending { it.sizes.size }.thenBy { it.cameraId }).first())
            }
            .flatten()
            .sortedWith(compareByDescending<LensProfile> { it.fovMetric }.thenBy { it.cameraId })

        val logicalFocal = sources.firstOrNull { it.physicalIds.isNotEmpty() }
            ?.focalLengthsMm?.firstOrNull()
        val withFocal = deduped.indices.filter { deduped[it].focalMm != null }
        val withEquivFocal = withFocal.filter { deduped[it].equivFocalMm != null }
        val mainIndex = when {
            logicalFocal != null && withFocal.isNotEmpty() ->
                withFocal.minBy { Math.abs(deduped[it].focalMm!! - logicalFocal) }
            // No logical multi-camera to report an advertised main focal length --
            // this device exposes every lens as its own fully separate top-level
            // camera id. Prefer the focal length closest to a typical phone
            // main/wide camera (~26mm equivalent) rather than literally the widest
            // FOV: an ultra-wide sensor is common on these devices but is almost
            // never the intended launch default. Falls back to widest-FOV only if
            // no candidate has an equivalent focal length to compare (needs the
            // sensor's physical size, which isn't always reported).
            withEquivFocal.isNotEmpty() ->
                withEquivFocal.minBy { Math.abs(deduped[it].equivFocalMm!! - TYPICAL_MAIN_EQUIV_FOCAL_MM) }
            withFocal.isNotEmpty() ->
                withFocal.maxBy { deduped[it].fovMetric.toDouble() }
            else -> deduped.indices.maxBy {
                deduped[it].activeArray.width.toLong() * deduped[it].activeArray.height
            }
        }

        val labelled = deduped.mapIndexed { i, lens ->
            val label = lens.equivFocalMm?.let { String.format(java.util.Locale.US, "%.0fmm", it) }
                ?: lens.focalMm?.let { String.format(java.util.Locale.US, "%.1fmm", it) }
                ?: "LENS ${i + 1}"
            lens.copy(label = label, isMain = i == mainIndex)
        }
        return labelled to mainIndex
    }

    /** Full-frame diagonal, for the 35mm-equivalent crop-factor formula. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.27

    /** 35mm-equivalent focal length of a typical phone main/wide camera, used only
     * by [finishLenses]'s no-logical-camera main-lens fallback. */
    private const val TYPICAL_MAIN_EQUIV_FOCAL_MM = 26f

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

        // A HAL that omits CONTROL_ZOOM_RATIO_RANGE, or advertises an upper bound
        // at or below 1.0, gets "no zoom" -- the conservative default. Guessing a
        // range would let the RAW crop zoom past a preview that cannot follow,
        // which is precisely the failure zoom exists to prevent.
        //
        // zoomUsable is the single source of truth for BOTH the value and the
        // defaulted flag -- the flag is deliberately the negation of the
        // usability test, not an independently written `<= 1.0f` check. A HAL
        // can legally report NaN as the upper bound (Range<Float> orders via
        // boxed Float.compareTo, which ranks NaN above everything, so it still
        // passes Range's own lower <= upper check); for NaN, both `> 1.0f` and
        // `<= 1.0f` are false, so two separately written conditions would
        // silently stop reporting the substitution.
        val zoomUpper = cam.zoomRatioRange?.getOrNull(1)
        val zoomUsable = zoomUpper != null && zoomUpper > 1.0f
        val maxZoomRatio = if (zoomUsable) zoomUpper else 1.0f
        if (!zoomUsable) defaulted += SnapshotField.ZOOM_RANGE

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
                    label = sizeLabel(it.width, it.height),
                )
            },
            cfa = cfa, whiteLevel = white, blackLevel = black.toIntArray(),
            colorMatrix1 = cm1, colorMatrix2 = cm2,
            illuminant1 = cam.illuminant1, illuminant2 = cam.illuminant2,
            isoRange = iso ?: (DEFAULT_ISO_LOW..DEFAULT_ISO_HIGH),
            exposureRangeNs = exposure, minFocusDiopters = minFocus, activeArray = activeArray,
            maxZoomRatio = maxZoomRatio,
            oisModes = cam.oisModes?.toIntArray(), sensorOrientation = cam.sensorOrientation,
            standalone = cam.standalone, isMain = false, controlTier = tier, defaulted = defaulted,
        )
    }

    /**
     * The size's actual pixel dimensions, e.g. "4096x3072".
     *
     * This used to be an aspect nickname ("4:3", "16:9", "LOW", "1080p"), which
     * told the user the SHAPE of the frame but not what they were actually
     * getting -- and left two sizes of the same shape indistinguishable. RAW
     * resolution is the thing being chosen, so it is the thing shown.
     *
     * maxArea is no longer a parameter: it existed only to single out a binned
     * size as "LOW", and 1920x1080 already reads as smaller than 4096x3072
     * without needing to be told.
     */
    private fun sizeLabel(w: Int, h: Int): String = "${w}x$h"
}
