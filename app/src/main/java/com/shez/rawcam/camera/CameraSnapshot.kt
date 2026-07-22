package com.shez.rawcam.camera

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One RAW output size and its minimum frame duration (ns; 0 = unknown). */
@Serializable
data class SizeSpec(val width: Int, val height: Int, val minFrameDurationNs: Long = 0)

/** Sensor pixel bounds, mirroring android.graphics.Rect without importing it. */
@Serializable
data class RectSpec(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A plain-data capture of one camera's CameraCharacteristics. Deliberately free
 * of android.* types so [LensDiscovery] stays a pure function and any real
 * device's characteristics can be committed as a JSON test fixture.
 *
 * Every field beyond [cameraId] is nullable or defaulted: a HAL may omit any
 * key, and an app without CAMERA permission sees most of them redacted to null.
 * Absence is data, never an error.
 */
@Serializable
data class CameraSnapshot(
    val cameraId: String,
    /** CameraMetadata.LENS_FACING_* ; 1 == BACK. Null when redacted. */
    val facing: Int? = null,
    /** REQUEST_AVAILABLE_CAPABILITIES; 3 == RAW, 1 == MANUAL_SENSOR. */
    val capabilities: List<Int> = emptyList(),
    val hardwareLevel: Int? = null,
    /** Physical children if this is a logical multi-camera; empty otherwise. */
    val physicalIds: List<String> = emptyList(),
    val rawSizes: List<SizeSpec> = emptyList(),
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: RGGB=0 GRBG=1 GBRG=2 BGGR=3. */
    val cfa: Int? = null,
    val whiteLevel: Int? = null,
    val blackLevel: List<Int>? = null,
    val focalLengthsMm: List<Float>? = null,
    val physicalSizeMm: List<Float>? = null,
    /** Row-major 3x3, CIE XYZ -> sensor space. */
    val colorTransform1: List<Float>? = null,
    val colorTransform2: List<Float>? = null,
    val illuminant1: Int? = null,
    val illuminant2: Int? = null,
    val isoRange: List<Int>? = null,
    /** SENSOR_INFO_EXPOSURE_TIME_RANGE in ns, [min, max]. */
    val exposureRangeNs: List<Long>? = null,
    val activeArray: RectSpec? = null,
    val minFocusDiopters: Float? = null,
    val oisModes: List<Int>? = null,
    /** Captured for Spec B (orientation correctness); unused by this spec. */
    val sensorOrientation: Int? = null,
    /** True when this id is NOT a physical child of the primary logical camera
     * and must be opened as its own top-level CameraDevice. Set by the probe. */
    val standalone: Boolean = false,
)

/** A whole device's snapshot: what a fixture file contains. */
@Serializable
data class SnapshotSet(
    val model: String,
    val sdkInt: Int,
    val cameras: List<CameraSnapshot>,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(text: String): SnapshotSet = JSON.decodeFromString(serializer(), text)
    }
}
