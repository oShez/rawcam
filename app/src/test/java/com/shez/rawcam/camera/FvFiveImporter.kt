package com.shez.rawcam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a Camera FV-5 device-database document into [CameraSnapshot]s.
 *
 * Their shape is {sdkLevel: {cameraId: {AOSP-key: value}}}. Verified against the
 * public Galaxy S10+ sample: every field CameraSnapshot needs is present under
 * AOSP key names, INCLUDING android.sensor.orientation and
 * android.sensor.info.exposureTimeRange.
 *
 * IMPORTANT LIMITATION: their dumps carry NO logical-multi-camera topology --
 * physicalCameraIds is empty and sub-lenses are absent entirely. This corpus can
 * therefore never test lens discovery, only per-sensor field handling. Topology
 * coverage comes from the hand-authored shape fixtures and stays there.
 *
 * Test-source-set only. A fixture-building tool, never shipped code.
 */
object FvFiveImporter {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** CFA and facing may arrive as names rather than ints depending on the
     * dump's processing level; map both forms rather than bending the model. */
    private val CFA_NAMES = mapOf("RGGB" to 0, "GRBG" to 1, "GBRG" to 2, "BGGR" to 3)
    private val FACING_NAMES = mapOf("FRONT" to 0, "BACK" to 1, "EXTERNAL" to 2)

    fun import(text: String): SnapshotSet {
        val root = JSON.parseToJsonElement(text).jsonObject
        // Highest SDK level present is the most representative of current behaviour.
        val sdkKey = root.keys.maxByOrNull { it.toIntOrNull() ?: -1 }
            ?: return SnapshotSet("unknown", 0, emptyList())
        val cameras = root[sdkKey]?.jsonObject ?: return SnapshotSet("unknown", 0, emptyList())
        return SnapshotSet(
            model = "fv5:$sdkKey",
            sdkInt = sdkKey.toIntOrNull() ?: 0,
            cameras = cameras.entries.mapNotNull { (id, node) ->
                runCatching { snapshot(id, node.jsonObject) }.getOrNull()
            },
        )
    }

    private fun snapshot(id: String, o: JsonObject): CameraSnapshot {
        fun str(k: String) = o[k]?.jsonPrimitive?.content
        fun int(k: String) = str(k)?.toIntOrNull()
        fun ints(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
        fun longs(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
        fun floats(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toFloatOrNull() }

        val cfaRaw = str("android.sensor.info.colorFilterArrangement")
        val facingRaw = str("android.lens.facing")

        return CameraSnapshot(
            cameraId = id,
            facing = facingRaw?.toIntOrNull() ?: FACING_NAMES[facingRaw?.uppercase()],
            capabilities = ints("android.request.availableCapabilities") ?: emptyList(),
            physicalIds = emptyList(), // never present in this corpus -- see kdoc
            rawSizes = emptyList(),    // stream config not parsed; field coverage only
            cfa = cfaRaw?.toIntOrNull() ?: CFA_NAMES[cfaRaw?.uppercase()],
            whiteLevel = int("android.sensor.info.whiteLevel"),
            blackLevel = ints("android.sensor.blackLevelPattern"),
            focalLengthsMm = floats("android.lens.info.availableFocalLengths"),
            physicalSizeMm = floats("android.sensor.info.physicalSize"),
            colorTransform1 = floats("android.sensor.colorTransform1"),
            colorTransform2 = floats("android.sensor.colorTransform2"),
            illuminant1 = int("android.sensor.referenceIlluminant1"),
            illuminant2 = int("android.sensor.referenceIlluminant2"),
            isoRange = ints("android.sensor.info.sensitivityRange"),
            exposureRangeNs = longs("android.sensor.info.exposureTimeRange"),
            minFocusDiopters = floats("android.lens.info.minimumFocusDistance")?.firstOrNull(),
            oisModes = ints("android.lens.info.availableOpticalStabilization"),
            sensorOrientation = int("android.sensor.orientation"),
        )
    }
}
