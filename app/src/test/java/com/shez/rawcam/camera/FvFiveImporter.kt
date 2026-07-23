package com.shez.rawcam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Converts a Camera FV-5 device-database document into [CameraSnapshot]s.
 *
 * Their shape is {sdkLevel: {cameraId: {apiNumber, cameraDirection, cameraId,
 * cameraOrientation, capabilities: [{name, value}]}}}. Every field lives in the
 * `capabilities` array under its AOSP key name -- NOT as a flat top-level key
 * (the top-level `cameraDirection`/`cameraOrientation` are unreliable, often -1
 * or 0 regardless of the real value; ignore them). `value` is heterogeneous: a
 * raw JSON primitive, or an object tagged with `type` (NamedInteger carries the
 * int under `v`; List carries items -- themselves either raw or NamedInteger;
 * IntegerRange/LongRange carry min/max; FloatSize carries w/h), or -- for
 * BlackLevelPattern and ColorSpaceTransform -- a stringified Java toString()
 * that has to be regex-parsed. Verified against the public Galaxy S10+ sample
 * (samsung_sm-g975f_beyond2.json).
 *
 * physicalCameraIds IS populated here for logical multi-cameras (the
 * wide+ultrawide+tele logical id lists its physical children), so this data
 * can exercise real topology, not just per-sensor field handling.
 *
 * Test-source-set only. A fixture-building tool, never shipped code.
 */
object FvFiveImporter {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

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
        val caps = (o["capabilities"] as? JsonArray).orEmpty()
        val byName = caps.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            name to obj["value"]
        }.toMap()
        fun value(k: String) = byName[k]

        return CameraSnapshot(
            cameraId = id,
            facing = int(value("android.lens.facing")),
            capabilities = intList(value("android.request.availableCapabilities")) ?: emptyList(),
            physicalIds = stringList(value("physicalCameraIds")) ?: emptyList(),
            rawSizes = emptyList(), // stream config not parsed; field coverage only
            cfa = int(value("android.sensor.info.colorFilterArrangement")),
            whiteLevel = int(value("android.sensor.info.whiteLevel")),
            blackLevel = blackLevelPattern(value("android.sensor.blackLevelPattern")),
            focalLengthsMm = floatList(value("android.lens.info.availableFocalLengths")),
            physicalSizeMm = floatSize(value("android.sensor.info.physicalSize")),
            colorTransform1 = colorSpaceTransform(value("android.sensor.colorTransform1")),
            colorTransform2 = colorSpaceTransform(value("android.sensor.colorTransform2")),
            illuminant1 = int(value("android.sensor.referenceIlluminant1")),
            illuminant2 = int(value("android.sensor.referenceIlluminant2")),
            isoRange = intMinMax(value("android.sensor.info.sensitivityRange")),
            exposureRangeNs = longMinMax(value("android.sensor.info.exposureTimeRange")),
            minFocusDiopters = float(value("android.lens.info.minimumFocusDistance")),
            oisModes = intList(value("android.lens.info.availableOpticalStabilization")),
            sensorOrientation = int(value("android.sensor.orientation")),
        )
    }

    /** Unwraps a raw int or a NamedInteger-wrapped one; both forms occur in this corpus. */
    private fun int(v: JsonElement?): Int? = when (v) {
        is JsonPrimitive -> v.intOrNull
        is JsonObject -> v["v"]?.jsonPrimitive?.intOrNull
        else -> null
    }

    private fun float(v: JsonElement?): Float? = when (v) {
        is JsonPrimitive -> v.floatOrNull
        is JsonObject -> v["v"]?.jsonPrimitive?.floatOrNull
        else -> null
    }

    private fun items(v: JsonElement?): List<JsonElement>? =
        (v as? JsonObject)?.get("items")?.jsonArray?.toList()

    private fun intList(v: JsonElement?): List<Int>? = items(v)?.mapNotNull(::int)

    private fun floatList(v: JsonElement?): List<Float>? = items(v)?.mapNotNull(::float)

    private fun stringList(v: JsonElement?): List<String>? =
        items(v)?.mapNotNull { (it as? JsonPrimitive)?.content }

    private fun intMinMax(v: JsonElement?): List<Int>? {
        val o = v as? JsonObject ?: return null
        val min = o["min"]?.jsonPrimitive?.intOrNull ?: return null
        val max = o["max"]?.jsonPrimitive?.intOrNull ?: return null
        return listOf(min, max)
    }

    private fun longMinMax(v: JsonElement?): List<Long>? {
        val o = v as? JsonObject ?: return null
        val min = o["min"]?.jsonPrimitive?.longOrNull ?: return null
        val max = o["max"]?.jsonPrimitive?.longOrNull ?: return null
        return listOf(min, max)
    }

    private fun floatSize(v: JsonElement?): List<Float>? {
        val o = v as? JsonObject ?: return null
        val w = o["w"]?.jsonPrimitive?.floatOrNull ?: return null
        val h = o["h"]?.jsonPrimitive?.floatOrNull ?: return null
        return listOf(w, h)
    }

    /** "BlackLevelPattern([0, 0], [0, 0])" -> the 4 ints in the 2x2 block, row-major. */
    private fun blackLevelPattern(v: JsonElement?): List<Int>? {
        val s = (v as? JsonPrimitive)?.content ?: return null
        val nums = Regex("-?\\d+").findAll(s).map { it.value.toInt() }.toList()
        return nums.ifEmpty { null }
    }

    /** "ColorSpaceTransform([805/1024, -163/1024, ...], ...)" -> 9 floats, row-major. */
    private fun colorSpaceTransform(v: JsonElement?): List<Float>? {
        val s = (v as? JsonPrimitive)?.content ?: return null
        val fractions = Regex("(-?\\d+)/(-?\\d+)").findAll(s)
            .map { it.groupValues[1].toFloat() / it.groupValues[2].toFloat() }
            .toList()
        return fractions.ifEmpty { null }
    }
}
