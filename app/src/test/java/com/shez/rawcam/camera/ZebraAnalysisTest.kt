package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZebraAnalysisTest {

    /** Builds a tightly-packed (rowStride == width, pixelStride == 1) Y plane. */
    private fun plane(width: Int, height: Int, fill: Int, block: (IntArray) -> Unit = {}): ByteArray {
        val v = IntArray(width * height) { fill }
        block(v)
        return ByteArray(v.size) { i -> v[i].toByte() }
    }

    @Test
    fun `all black yields no runs`() {
        val y = plane(64, 48, 0)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(8, mask.cols)
        assertEquals(6, mask.rows)
        assertTrue(mask.runs.isEmpty())
    }

    @Test
    fun `254 is below threshold and never flags`() {
        val y = plane(64, 48, 254)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertTrue(mask.runs.isEmpty())
    }

    @Test
    fun `all clipped merges each row into one full-width run`() {
        val y = plane(64, 48, 255)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(6, mask.runs.size)
        assertEquals(List(6) { ZebraMask.CellRun(it, 0, 8) }, mask.runs.sortedBy { it.row })
    }

    @Test
    fun `a single clipped pixel flags exactly its own cell`() {
        // 64x48 into an 8x6 grid => each cell is 8x8 source pixels.
        // Pixel (x=17, y=9) lands in cell (col = 17*8/64 = 2, row = 9*6/48 = 1).
        val y = plane(64, 48, 0) { it[9 * 64 + 17] = 255 }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(listOf(ZebraMask.CellRun(1, 2, 3)), mask.runs)
    }

    @Test
    fun `adjacent flagged cells merge, a gap splits the run`() {
        // Cells 0, 1 and 3 of row 0. Cell N spans source x in [N*8, N*8+8).
        val y = plane(64, 48, 0) {
            it[0 * 64 + 0] = 255    // col 0
            it[0 * 64 + 8] = 255    // col 1
            it[0 * 64 + 24] = 255   // col 3
        }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(
            listOf(ZebraMask.CellRun(0, 0, 2), ZebraMask.CellRun(0, 3, 4)),
            mask.runs,
        )
    }

    @Test
    fun `row stride padding is skipped, not read as pixels`() {
        // rowStride 72 > width 64: the 8 padding bytes per row are 255 and must be ignored.
        val width = 64; val height = 6; val stride = 72
        val buf = ByteArray(stride * height)
        for (r in 0 until height) {
            for (c in 0 until stride) {
                buf[r * stride + c] = if (c >= width) 255.toByte() else 0
            }
        }
        val mask = ZebraAnalysis.threshold(buf, width, height, stride, 1, cols = 8, rows = 6)
        assertTrue("padding must not flag any cell", mask.runs.isEmpty())
    }

    @Test
    fun `pixel stride greater than one reads only luma samples`() {
        // pixelStride 2: every other byte is an interleaved non-luma sample set to 255.
        val width = 8; val height = 2; val pixelStride = 2; val rowStride = width * pixelStride
        val buf = ByteArray(rowStride * height)
        for (i in buf.indices) buf[i] = if (i % 2 == 1) 255.toByte() else 0
        val mask = ZebraAnalysis.threshold(buf, width, height, rowStride, pixelStride, cols = 4, rows = 2)
        assertTrue("interleaved samples must not be read as luma", mask.runs.isEmpty())
    }

    @Test
    fun `degenerate inputs return EMPTY instead of throwing`() {
        assertEquals(ZebraMask.EMPTY, ZebraAnalysis.threshold(ByteArray(0), 0, 0, 0, 1, 8, 6))
        assertEquals(ZebraMask.EMPTY, ZebraAnalysis.threshold(ByteArray(16), -4, 4, 4, 1, 8, 6))
        assertEquals(ZebraMask.EMPTY, ZebraAnalysis.threshold(ByteArray(16), 4, 4, 4, 0, 8, 6))
        assertEquals(ZebraMask.EMPTY, ZebraAnalysis.threshold(ByteArray(16), 4, 4, 4, 1, 0, 6))
        assertEquals(ZebraMask.EMPTY, ZebraAnalysis.threshold(ByteArray(16), 4, 4, 4, 1, 8, 0))
    }

    @Test
    fun `a short plane returns EMPTY instead of reading out of bounds`() {
        // Claims 64x48 but carries only half the bytes: a truncated/malformed frame.
        val mask = ZebraAnalysis.threshold(ByteArray(64 * 24), 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(ZebraMask.EMPTY, mask)
    }

    @Test
    fun `grid finer than the source never throws and stays in bounds`() {
        val y = plane(4, 4, 255)
        val mask = ZebraAnalysis.threshold(y, 4, 4, 4, 1, cols = 32, rows = 24)
        assertEquals(32, mask.cols)
        assertEquals(24, mask.rows)
        assertTrue(mask.runs.all { it.row in 0 until 24 })
        assertTrue(mask.runs.all { it.startCol >= 0 && it.endColExclusive <= 32 })
    }

    private fun sz(w: Int, h: Int) = SizeSpec(w, h)

    @Test
    fun `picks the smallest size at the closest aspect ratio`() {
        val candidates = listOf(sz(1920, 1080), sz(640, 480), sz(320, 240), sz(1280, 720))
        // 4:3 target -> the two 4:3 options win on aspect, then 320x240 wins on area.
        assertEquals(sz(320, 240), ZebraAnalysis.pickAnalysisSize(candidates, 4f / 3f))
    }

    @Test
    fun `aspect ratio beats raw smallness`() {
        // 176x144 (11:9) is the smallest by area, but 640x360 matches 16:9 exactly.
        val candidates = listOf(sz(176, 144), sz(640, 360), sz(1280, 720))
        assertEquals(sz(640, 360), ZebraAnalysis.pickAnalysisSize(candidates, 16f / 9f))
    }

    @Test
    fun `oversized candidates are rejected while any preview-class size exists`() {
        val candidates = listOf(sz(4000, 3000), sz(640, 480))
        assertEquals(sz(640, 480), ZebraAnalysis.pickAnalysisSize(candidates, 4f / 3f))
    }

    @Test
    fun `falls back to the smallest oversized size when nothing fits the cap`() {
        val candidates = listOf(sz(4000, 3000), sz(8000, 6000))
        assertEquals(sz(4000, 3000), ZebraAnalysis.pickAnalysisSize(candidates, 4f / 3f))
    }

    @Test
    fun `returns null when there is nothing usable`() {
        assertEquals(null, ZebraAnalysis.pickAnalysisSize(emptyList(), 4f / 3f))
        assertEquals(null, ZebraAnalysis.pickAnalysisSize(listOf(sz(0, 0), sz(-1, 4)), 4f / 3f))
    }

    @Test
    fun `a nonsense target aspect still returns the smallest usable size`() {
        val candidates = listOf(sz(640, 480), sz(320, 240))
        assertEquals(sz(320, 240), ZebraAnalysis.pickAnalysisSize(candidates, 0f))
        assertEquals(sz(320, 240), ZebraAnalysis.pickAnalysisSize(candidates, Float.NaN))
    }
}
