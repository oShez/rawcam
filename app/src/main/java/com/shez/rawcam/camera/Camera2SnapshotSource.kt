package com.shez.rawcam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * The ONLY place in the app that calls CameraCharacteristics.get(). Converts
 * Camera2's object graph into plain [CameraSnapshot] data so [LensDiscovery]
 * can stay pure and unit-testable.
 *
 * Every read is individually null-tolerant: a HAL may omit any key, and Android
 * redacts most of them for an app without CAMERA permission. Nothing here throws
 * on a missing key -- absence is passed through as null and classified
 * downstream.
 *
 * MUST be called off the main thread: getCameraCharacteristics is binder IPC,
 * once per camera id.
 */
class Camera2SnapshotSource(private val cameraManager: CameraManager) {

    fun capture(): SnapshotSet {
        val listed = try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList unavailable", e)
            emptyList()
        }

        val children = listed.flatMap { id ->
            try {
                cameraManager.getCameraCharacteristics(id).physicalCameraIds.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val known = (listed + children).toSet()
        val snapshots = mutableListOf<CameraSnapshot>()
        for (id in known) {
            snapshotOf(id, standalone = false)?.let { snapshots += it }
        }
        snapshots += probeHidden(known)

        return SnapshotSet(model = Build.MODEL, sdkInt = Build.VERSION.SDK_INT, cameras = snapshots)
    }

    /**
     * Probes ids the OS hides from both cameraIdList and every logical camera's
     * physicalCameraIds -- observed on MIUI/HyperOS (Xiaomi 14 Ultra ids "4"/"5",
     * the 3.2x telephoto and 5x periscope), which report full RAW support and
     * open successfully as standalone CameraDevices.
     *
     * Widened from the previous 0..15 to 0..31 because Samsung is known to use
     * higher ids. Bounded by a wall-clock budget so a slow vendor HAL cannot
     * stall app launch: a device that answers slowly gets fewer probes, never a
     * frozen startup.
     *
     * KNOWN LIMITATION: only decimal ids are reachable. A vendor using
     * non-numeric ids is undiscoverable by any scan and needs a Spec C quirks
     * entry.
     */
    private fun probeHidden(exclude: Set<String>): List<CameraSnapshot> {
        val found = mutableListOf<CameraSnapshot>()
        val deadline = SystemClock.elapsedRealtime() + PROBE_BUDGET_MS
        for (i in 0 until HIDDEN_PROBE_RANGE) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "hidden-lens probe budget exhausted at id $i")
                break
            }
            val id = i.toString()
            if (id in exclude) continue
            snapshotOf(id, standalone = true)?.let { found += it }
        }
        return found
    }

    /** Null only when the id does not exist or is hard-blocked by the HAL. */
    private fun snapshotOf(id: String, standalone: Boolean): CameraSnapshot? {
        val ch = try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            // Nonexistent id, or a genuine "system only device" refusal (this
            // device's ids 7/8). Expected during probing; not an error.
            Log.d(TAG, "camera $id unavailable", e)
            return null
        }
        fun <T> read(key: CameraCharacteristics.Key<T>): T? = try {
            ch.get(key)
        } catch (e: Exception) {
            null
        }

        val map = read(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { s ->
            SizeSpec(s.width, s.height,
                minFrameDurationNs = try {
                    map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
                } catch (e: Exception) { 0L })
        } ?: emptyList()

        val physSize = read(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val black = read(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { p -> IntArray(4).also { p.copyTo(it, 0) }.toList() }
        // Row-major 3x3: index i -> row i/3, column i%3 (getElement takes column, row).
        fun matrix(k: CameraCharacteristics.Key<ColorSpaceTransform>) =
            read(k)?.let { t -> List(9) { i -> t.getElement(i % 3, i / 3).toFloat() } }
        val iso = read(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = read(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val arr = read(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val zoomRange = read(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

        return CameraSnapshot(
            cameraId = id,
            facing = read(CameraCharacteristics.LENS_FACING),
            capabilities = read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList(),
            hardwareLevel = read(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            physicalIds = try { ch.physicalCameraIds.toList() } catch (e: Exception) { emptyList() },
            rawSizes = rawSizes,
            cfa = read(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            whiteLevel = read(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            blackLevel = black,
            focalLengthsMm = read(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList(),
            physicalSizeMm = physSize?.let { listOf(it.width, it.height) },
            colorTransform1 = matrix(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            colorTransform2 = matrix(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            illuminant1 = read(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt(),
            illuminant2 = read(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            isoRange = iso?.let { listOf(it.lower, it.upper) },
            exposureRangeNs = exp?.let { listOf(it.lower, it.upper) },
            activeArray = arr?.let { RectSpec(it.left, it.top, it.right, it.bottom) },
            zoomRatioRange = zoomRange?.let { listOf(it.lower, it.upper) },
            minFocusDiopters = read(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            oisModes = read(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList(),
            sensorOrientation = read(CameraCharacteristics.SENSOR_ORIENTATION),
            standalone = standalone,
        )
    }

    private companion object {
        const val TAG = "Camera2SnapshotSource"
        const val HIDDEN_PROBE_RANGE = 32
        const val PROBE_BUDGET_MS = 400L
    }
}
