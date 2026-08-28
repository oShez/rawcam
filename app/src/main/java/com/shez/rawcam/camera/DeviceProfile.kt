package com.shez.rawcam.camera

/** Which soft field was substituted with a default; surfaced in the report. */
enum class SnapshotField {
    FOCAL_LENGTH, PHYSICAL_SIZE, COLOR_TRANSFORM1, COLOR_TRANSFORM2,
    ILLUMINANTS, ISO_RANGE, EXPOSURE_RANGE, ACTIVE_ARRAY, MIN_FOCUS, OIS_MODES,
    ZOOM_RANGE,
}

/** How much manual control a lens actually offers. */
enum class ControlTier { FULL, AUTO_ONLY }

enum class UnsupportedReason {
    NO_BACK_CAMERA, NO_RAW_CAPABILITY, NO_USABLE_RAW_SIZES, PERMISSION_REDACTED,
}

/** One accept/reject decision with its reason. Feeds logcat, the in-app
 * compatibility report, and (Spec C) the upload payload. */
data class ProfileNote(val cameraId: String, val accepted: Boolean, val message: String)

/** One selectable RAW output size of a lens. */
data class LensSizeProfile(val width: Int, val height: Int, val maxFps: Int, val label: String)

/**
 * A back-facing lens RawCam can record from. Replaces CameraController.LensInfo.
 * [cameraId] keeps the exact meaning LensInfo.physicalId had: the per-lens WB
 * identity key. [standalone] means it must be opened as its own top-level
 * CameraDevice rather than tagged onto the primary logical camera.
 */
data class LensProfile(
    val cameraId: String,
    val label: String,
    val focalMm: Float?,
    val equivFocalMm: Float?,
    val fovMetric: Float,
    val sizes: List<LensSizeProfile>,
    val cfa: Int,
    val whiteLevel: Int,
    val blackLevel: IntArray,
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray?,
    val illuminant1: Int?,
    val illuminant2: Int?,
    val isoRange: IntRange,
    val exposureRangeNs: LongRange?,
    val minFocusDiopters: Float,
    val activeArray: RectSpec,
    /** CONTROL_ZOOM_RATIO_RANGE upper bound. 1.0 means the lens offers no zoom --
     *  either the HAL omitted the key or it advertised a nonsense range. Feeds
     *  ZoomLadder.build's maxRatio, which DROPS stops past this rather than
     *  capping them: a capped preview over an uncapped RAW crop is a preview
     *  that lies about the file. */
    val maxZoomRatio: Float,
    val oisModes: IntArray?,
    val sensorOrientation: Int?,
    val standalone: Boolean,
    val isMain: Boolean,
    val controlTier: ControlTier,
    val defaulted: Set<SnapshotField>,
) {
    // Arrays break generated data-class equality (identity comparison); compare
    // by identity fields only, since golden tests compare LensProfile instances.
    override fun equals(other: Any?): Boolean =
        other is LensProfile && other.cameraId == cameraId && other.label == label &&
            other.controlTier == controlTier && other.defaulted == defaulted &&
            other.standalone == standalone && other.isMain == isMain
    override fun hashCode(): Int = cameraId.hashCode() * 31 + label.hashCode()
}

sealed interface DeviceProfile {
    val notes: List<ProfileNote>

    data class Supported(
        val lenses: List<LensProfile>,
        val mainIndex: Int,
        override val notes: List<ProfileNote>,
    ) : DeviceProfile

    data class Unsupported(
        val reason: UnsupportedReason,
        val detail: String,
        override val notes: List<ProfileNote>,
    ) : DeviceProfile
}
