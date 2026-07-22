package com.shez.rawcam.camera

/**
 * Filters the app's fixed shutter stop table down to what a sensor can actually
 * honour. Before this existed, RawCam offered every stop on every device and let
 * the HAL silently clamp out-of-range requests -- the UI then displayed a
 * shutter speed the sensor was not using.
 */
object ShutterStops {
    fun available(all: List<Long>, range: LongRange?): List<Long> {
        if (range == null) return all
        val inRange = all.filter { it in range }
        if (inRange.isNotEmpty()) return inRange
        // Never return an empty list: a picker with no options is unusable. Keep
        // the nearest stop and let the HAL clamp it.
        return listOfNotNull(all.minByOrNull {
            minOf(Math.abs(it - range.first), Math.abs(it - range.last))
        })
    }
}
