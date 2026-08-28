package com.shez.rawcam.camera

/**
 * One rung of the zoom ladder: a centred, CFA-aligned sub-rectangle of the
 * selected RAW size, plus the ratio the PREVIEW must be driven at.
 *
 * [ratio] is the ACTUAL ratio the rectangle realizes (`fullW / cropW`), not the
 * nominal label. Alignment rounds cropW down, so the two differ slightly at some
 * stops -- 2.8x on a 4096-wide sensor is really 2.8055x. Sending [nominal] to
 * CONTROL_ZOOM_RATIO instead would put the preview a few pixels out of agreement
 * with the file, which is the exact failure this whole feature is built to avoid.
 */
data class ZoomStop(
    val nominal: Float,
    val ratio: Float,
    val cropX: Int,
    val cropY: Int,
    val cropW: Int,
    val cropH: Int,
) {
    /** "1x" / "1.4x" -- the rail's chip label. */
    val label: String get() =
        if (nominal == nominal.toInt().toFloat()) "${nominal.toInt()}x" else "${nominal}x"
}

/**
 * Builds the zoom ladder for one RAW size. A pure function of its arguments --
 * no android.* types, no device access -- so its invariants are pinned by plain
 * JVM tests.
 *
 * The alignment rules are not cosmetic:
 *  - cropX/cropY EVEN keeps the Bayer phase. An odd origin shifts the CFA by one
 *    pixel while FileHeader.cfa still claims the sensor's pattern, so the clip
 *    decodes cleanly into wrong colour -- green and magenta footage, no error
 *    anywhere.
 *  - cropW a multiple of 4 keeps capture.cpp's Packed10 gate (`width % 4 == 0`)
 *    satisfied. Breaking it still records a valid clip, silently ~1.6x larger.
 *  - cropH even keeps whole CFA rows.
 */
object ZoomLadder {

    val NOMINAL = floatArrayOf(1.0f, 1.4f, 2.0f, 2.8f, 4.0f)

    private fun floorTo(v: Int, multiple: Int) = v - (v % multiple)

    /**
     * @param maxRatio the device's CONTROL_ZOOM_RATIO_RANGE upper bound for this
     *   lens. Stops whose ACTUAL ratio exceeds it are dropped rather than capped:
     *   a capped preview paired with an uncapped crop is a preview that lies
     *   about the file.
     * @param activeArrayDefaulted true when SENSOR_INFO_ACTIVE_ARRAY_SIZE was
     *   absent and LensDiscovery substituted a guess. Crop reasoning on a guessed
     *   rectangle is not trustworthy, so zoom collapses to 1x.
     * @return at least one stop (always 1x), ordered by increasing ratio.
     */
    fun build(
        fullW: Int,
        fullH: Int,
        maxRatio: Float,
        activeArrayDefaulted: Boolean,
    ): List<ZoomStop> {
        val full = ZoomStop(1.0f, 1.0f, 0, 0, fullW, fullH)
        if (activeArrayDefaulted) return listOf(full)

        val out = ArrayList<ZoomStop>(NOMINAL.size)
        for (nominal in NOMINAL) {
            if (nominal == 1.0f) {
                out += full
                continue
            }
            var cropW = floorTo((fullW / nominal).toInt(), 4)
            if (cropW < 4) continue
            var ratio = fullW.toFloat() / cropW.toFloat()
            // RULING R5: rounding cropW DOWN always pushes the ACTUAL ratio ABOVE
            // nominal, so the top stop can silently overshoot the spec's hard 4x
            // product cap -- invisible on a 4096-wide sensor purely because
            // 4096/4=1024 is already a multiple of 4. Round-down stays the general
            // rule (it is what produces the spec's committed 4096x3072 table), but
            // when the actual ratio would break the 4x cap, round cropW UP one
            // step instead: cropW+4 is always enough, since cropW+4 > fullW/4
            // implies fullW/(cropW+4) < 4x.
            if (ratio > 4.0f) {
                val roundedUp = cropW + 4
                if (roundedUp > fullW) continue
                cropW = roundedUp
                ratio = fullW.toFloat() / cropW.toFloat()
            }
            if (ratio > maxRatio) continue
            // RULING R1(a): flooring cropW to a multiple of 4 can make two
            // different nominal stops land on the same actual ratio (e.g. on an
            // 8x4 sensor, nominal 1.4 and 2.0 both floor to cropW=4, ratio=2.0).
            // The spec invariant is that ratios strictly increase, so a stop
            // that repeats the previously accepted stop's (FINAL, post-R5) ratio
            // is skipped.
            if (ratio == out.last().ratio) continue
            val cropH = floorTo((fullH / ratio).toInt(), 2)
            if (cropH < 2) continue
            val cropX = floorTo((fullW - cropW) / 2, 2)
            val cropY = floorTo((fullH - cropH) / 2, 2)
            out += ZoomStop(nominal, ratio, cropX, cropY, cropW, cropH)
        }
        return if (out.isEmpty()) listOf(full) else out
    }
}
