package com.shez.rawcam.camera

import kotlin.math.abs

/**
 * A coarse grid of clipped-highlight and crushed-shadow cells, each run-length merged
 * along its row.
 *
 * Deliberately not per-pixel: zebra is a visual warning, not a measurement, and a
 * coarse grid keeps the overlay's per-frame draw-call count bounded. Runs exist
 * because the worst case for this feature -- pointing at a bright sky, where every
 * cell is flagged -- is also its most common real use; merging turns that from
 * [cols] x [rows] rects into one rect per row.
 *
 * [highlightRuns] and [shadowRuns] are each ordered by row, then by
 * [CellRun.startCol]. An empty list means nothing is clipping in that direction.
 */
data class ZebraMask(
    val cols: Int,
    val rows: Int,
    val highlightRuns: List<CellRun>,
    val shadowRuns: List<CellRun>,
) {
    /** A horizontal span of flagged cells in one row: `[startCol, endColExclusive)`. */
    data class CellRun(val row: Int, val startCol: Int, val endColExclusive: Int)

    companion object {
        val EMPTY = ZebraMask(0, 0, emptyList(), emptyList())
    }
}

/**
 * Pure luminance analysis for the zebra overlay. No `android.*` dependency, so it
 * is exercised by real host tests rather than only on-device -- the same shape as
 * [ShutterStops] and [LensDiscovery].
 */
object ZebraAnalysis {

    /** A pixel counts as clipped only at the 8-bit Y plane's true maximum. */
    const val HIGHLIGHT_CLIP_THRESHOLD = 255

    /** A pixel counts as crushed only at the 8-bit Y plane's true minimum. */
    const val SHADOW_CLIP_THRESHOLD = 0

    const val GRID_COLS = 32
    const val GRID_ROWS = 24

    /**
     * Flags every grid cell containing at least one pixel at [HIGHLIGHT_CLIP_THRESHOLD]
     * (highlight) or at [SHADOW_CLIP_THRESHOLD] (shadow) -- independently, in the same
     * single pass over the plane.
     *
     * [rowStride]/[pixelStride] come straight from `Image.Plane` -- a YUV Y plane is
     * frequently padded ([rowStride] > [width]) and may be interleaved
     * ([pixelStride] > 1), so neither can be assumed away.
     *
     * Never throws: any degenerate geometry, or a plane shorter than the declared
     * dimensions require, returns [ZebraMask.EMPTY]. A malformed frame must degrade
     * to "no warning", never take down the camera callback thread.
     */
    fun threshold(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cols: Int = GRID_COLS,
        rows: Int = GRID_ROWS,
    ): ZebraMask {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return ZebraMask.EMPTY
        if (cols <= 0 || rows <= 0) return ZebraMask.EMPTY
        val needed = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 1L
        if (y.size < needed) return ZebraMask.EMPTY

        val highlightFlags = BooleanArray(cols * rows)
        val shadowFlags = BooleanArray(cols * rows)
        for (py in 0 until height) {
            val cellRow = py * rows / height
            val rowBase = py * rowStride
            val flagBase = cellRow * cols
            for (px in 0 until width) {
                val v = y[rowBase + px * pixelStride].toInt() and 0xFF
                val cell = flagBase + px * cols / width
                if (v >= HIGHLIGHT_CLIP_THRESHOLD) highlightFlags[cell] = true
                if (v <= SHADOW_CLIP_THRESHOLD) shadowFlags[cell] = true
            }
        }

        return ZebraMask(
            cols, rows,
            highlightRuns = mergeRuns(highlightFlags, cols, rows),
            shadowRuns = mergeRuns(shadowFlags, cols, rows),
        )
    }

    /** Run-length merges a flat [cols] x [rows] flag grid into per-row [ZebraMask.CellRun]s. */
    private fun mergeRuns(flags: BooleanArray, cols: Int, rows: Int): List<ZebraMask.CellRun> {
        val runs = ArrayList<ZebraMask.CellRun>()
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                if (flags[r * cols + c]) {
                    val start = c
                    while (c < cols && flags[r * cols + c]) c++
                    runs.add(ZebraMask.CellRun(r, start, c))
                } else {
                    c++
                }
            }
        }
        return runs
    }

    /**
     * Largest analysis area we will ask the camera for, in pixels (1920x1080).
     *
     * Camera2's mandatory stream-combination table guarantees
     * `PRIV(preview) + YUV(preview) + RAW(max)` on every device advertising the RAW
     * capability -- which RawCam already hard-requires. Staying inside the *preview*
     * size class is what makes the third output a guarantee rather than a gamble, so
     * an oversized YUV is only ever chosen when a device advertises nothing smaller.
     */
    const val PREVIEW_AREA_CAP = 1920L * 1080L

    /**
     * Picks the analysis stream size: closest aspect ratio to [targetAspect] first,
     * then smallest area. No resolution is hardcoded -- devices vary widely in what
     * they advertise for `YUV_420_888`.
     *
     * Returns null when nothing usable was advertised, which the caller treats as
     * "zebra silently does nothing on this device" rather than an error.
     */
    fun pickAnalysisSize(candidates: List<SizeSpec>, targetAspect: Float): SizeSpec? {
        val usable = candidates.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return null
        val area = { s: SizeSpec -> s.width.toLong() * s.height }
        val withinCap = usable.filter { area(it) <= PREVIEW_AREA_CAP }
        val pool = if (withinCap.isNotEmpty()) withinCap else usable
        if (targetAspect <= 0f || !targetAspect.isFinite()) return pool.minByOrNull(area)
        val err = { s: SizeSpec -> abs(s.width.toFloat() / s.height - targetAspect) }
        val best = pool.minOf(err)
        return pool.filter { err(it) <= best + 1e-3f }.minByOrNull(area)
    }
}
