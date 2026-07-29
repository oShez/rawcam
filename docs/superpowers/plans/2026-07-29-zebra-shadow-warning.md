# Zebra Shadow Warning + Highlight Restyle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second, independently-toggleable zebra warning for crushed shadows (blue stripes), and restyle the existing highlight-clip warning from solid white to a red/white candy-stripe — both at a finer 7dp pitch than today's 14dp.

**Architecture:** `ZebraAnalysis.threshold()` already scans the YUV Y-plane once per analysis frame; it gains a second comparison (`Y == 0`) alongside the existing `Y == 255` and returns two independently run-length-merged cell lists instead of one. `CameraController` and `Settings` each split their single zebra flag into two independent ones; the analysis stream is created whenever *either* is on. `RecordScreen`'s `ZebraOverlay` draws each run list with its own colored `Brush` only when its flag is set.

**Tech Stack:** Kotlin, Jetpack Compose (`Canvas`, `Brush.linearGradient` with `TileMode.Repeated`), Jetpack DataStore, JUnit4 on the JVM.

**Spec:** `docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md`

## Global Constraints

- Shadow clip threshold: `Y == 0`, the minimum of the 8-bit Y plane. Not user-adjustable, same as the highlight threshold.
- Two independent Settings toggles (`zebraHighlightEnabled`, `zebraShadowEnabled`) — not a shared one.
- Stripe pitch changes from 14dp to 7dp for **both** warnings.
- Highlight stripes: opaque red/white candy-stripe, 0.85 alpha each band (was solid white).
- Shadow stripes: opaque blue alternating with fully transparent, 0.85 alpha.
- Same animated diagonal convention and 900ms phase period as today, unchanged.
- Both toggles default off. `zebraHighlightEnabled` migrates from the existing `zebraEnabled` DataStore key so a device that already has zebra on keeps it on.
- Graceful degradation unchanged: a device with no usable analysis stream silently no-ops for both warnings, same as today.
- Zero change to the RAW capture/export pipeline. `core/` and `app/src/main/cpp/` MUST remain untouched.
- All Gradle commands run from PowerShell in `C:\Users\User\rawcam`.

## Deviations from the design spec (deliberate, adopted in this plan)

1. **Highlight red reuses `RawCamColors.Accent` (`#E5484D`) instead of the spec's placeholder `#E6392F`.** The spec explicitly flagged its hex values as "adjustable in review without affecting anything else in this spec." `RawCamColors.Accent` is the app's existing red (record button, error color) and is close enough to the reference screenshots' red to read as the same convention — reusing it avoids introducing a second, near-duplicate red into the palette. Blue (`#3385FF`) has no existing theme equivalent, so it stays a new zebra-only constant, as the spec described.
2. **The per-row run-length merge is extracted into a private `mergeRuns()` helper** in `ZebraAnalysis.kt`, called once for highlight flags and once for shadow flags. The spec described the *behavior* (two independently merged run lists) but not this structure; sharing the merge logic rather than duplicating the loop is the natural DRY choice once there are two flag arrays instead of one.

## File Structure

**Modify:**
- `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt` — `ZebraMask` splits `runs` into `highlightRuns`/`shadowRuns`; `threshold()` computes both; `CLIP_THRESHOLD` renamed to `HIGHLIGHT_CLIP_THRESHOLD`, new `SHADOW_CLIP_THRESHOLD`.
- `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt` — extended for the shadow threshold and highlight/shadow independence.
- `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` — `Settings.zebraEnabled` splits into `zebraHighlightEnabled` (migrates from the old key) and `zebraShadowEnabled`.
- `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:241-245` — one `ToggleRow` becomes two.
- `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` — `zebraEnabled` field splits; the analysis-stream gate becomes an OR of both.
- `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — settings-collector wiring, session-recreation key, overlay composition site, and `ZebraOverlay` itself all updated for two independent, differently-colored warnings.

---

### Task 1: Two-threshold luminance analysis

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt`

**Interfaces:**
- Consumes: nothing new (first task).
- Produces:
  - `data class ZebraMask(val cols: Int, val rows: Int, val highlightRuns: List<ZebraMask.CellRun>, val shadowRuns: List<ZebraMask.CellRun>)`
  - `ZebraMask.EMPTY: ZebraMask` (now `ZebraMask(0, 0, emptyList(), emptyList())`)
  - `ZebraAnalysis.threshold(y, width, height, rowStride, pixelStride, cols = GRID_COLS, rows = GRID_ROWS): ZebraMask` — same signature as today, unchanged for callers.
  - `ZebraAnalysis.HIGHLIGHT_CLIP_THRESHOLD: Int = 255` (renamed from `CLIP_THRESHOLD`), `ZebraAnalysis.SHADOW_CLIP_THRESHOLD: Int = 0` (new).
  - `ZebraAnalysis.pickAnalysisSize(...)` and `ZebraAnalysis.PREVIEW_AREA_CAP` — unchanged, untouched by this task.

- [ ] **Step 1: Write the failing tests**

Replace the full contents of `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt` with:

```kotlin
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

    // ---- Highlight (Y == 255) ----

    @Test
    fun `mid-gray yields no highlight or shadow runs`() {
        val y = plane(64, 48, 128)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(8, mask.cols)
        assertEquals(6, mask.rows)
        assertTrue(mask.highlightRuns.isEmpty())
        assertTrue(mask.shadowRuns.isEmpty())
    }

    @Test
    fun `254 is below highlight threshold and never flags`() {
        val y = plane(64, 48, 254)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertTrue(mask.highlightRuns.isEmpty())
    }

    @Test
    fun `all clipped merges each row into one full-width highlight run`() {
        val y = plane(64, 48, 255)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(6, mask.highlightRuns.size)
        assertEquals(List(6) { ZebraMask.CellRun(it, 0, 8) }, mask.highlightRuns.sortedBy { it.row })
        assertTrue(mask.shadowRuns.isEmpty())
    }

    @Test
    fun `a single clipped pixel flags exactly its own highlight cell`() {
        // 64x48 into an 8x6 grid => each cell is 8x8 source pixels.
        // Pixel (x=17, y=9) lands in cell (col = 17*8/64 = 2, row = 9*6/48 = 1).
        val y = plane(64, 48, 128) { it[9 * 64 + 17] = 255 }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(listOf(ZebraMask.CellRun(1, 2, 3)), mask.highlightRuns)
        assertTrue(mask.shadowRuns.isEmpty())
    }

    @Test
    fun `adjacent flagged highlight cells merge, a gap splits the run`() {
        // Cells 0, 1 and 3 of row 0. Cell N spans source x in [N*8, N*8+8).
        val y = plane(64, 48, 128) {
            it[0 * 64 + 0] = 255    // col 0
            it[0 * 64 + 8] = 255    // col 1
            it[0 * 64 + 24] = 255   // col 3
        }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(
            listOf(ZebraMask.CellRun(0, 0, 2), ZebraMask.CellRun(0, 3, 4)),
            mask.highlightRuns,
        )
    }

    // ---- Shadow (Y == 0) ----

    @Test
    fun `all black merges each row into one full-width shadow run`() {
        val y = plane(64, 48, 0)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(6, mask.shadowRuns.size)
        assertEquals(List(6) { ZebraMask.CellRun(it, 0, 8) }, mask.shadowRuns.sortedBy { it.row })
        assertTrue(mask.highlightRuns.isEmpty())
    }

    @Test
    fun `1 is above shadow threshold and never flags`() {
        val y = plane(64, 48, 1)
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertTrue(mask.shadowRuns.isEmpty())
    }

    @Test
    fun `a single crushed pixel flags exactly its own shadow cell`() {
        val y = plane(64, 48, 128) { it[9 * 64 + 17] = 0 }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(listOf(ZebraMask.CellRun(1, 2, 3)), mask.shadowRuns)
        assertTrue(mask.highlightRuns.isEmpty())
    }

    @Test
    fun `adjacent flagged shadow cells merge, a gap splits the run`() {
        val y = plane(64, 48, 128) {
            it[0 * 64 + 0] = 0    // col 0
            it[0 * 64 + 8] = 0    // col 1
            it[0 * 64 + 24] = 0   // col 3
        }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(
            listOf(ZebraMask.CellRun(0, 0, 2), ZebraMask.CellRun(0, 3, 4)),
            mask.shadowRuns,
        )
    }

    // ---- Independence ----

    @Test
    fun `highlight and shadow flag independently in the same frame`() {
        // Pixel (2,2) -> cell (0,0) crushed; pixel (40,20) -> cell (5,2) clipped;
        // everything else mid-gray and unflagged.
        val y = plane(64, 48, 128) {
            it[2 * 64 + 2] = 0
            it[20 * 64 + 40] = 255
        }
        val mask = ZebraAnalysis.threshold(y, 64, 48, 64, 1, cols = 8, rows = 6)
        assertEquals(listOf(ZebraMask.CellRun(0, 0, 1)), mask.shadowRuns)
        assertEquals(listOf(ZebraMask.CellRun(2, 5, 6)), mask.highlightRuns)
    }

    // ---- Shared plumbing (stride, degenerate input, grid granularity) ----

    @Test
    fun `row stride padding is skipped, not read as pixels`() {
        // rowStride 72 > width 64: the 8 padding bytes per row are 255 and must be ignored.
        val width = 64; val height = 6; val stride = 72
        val buf = ByteArray(stride * height)
        for (r in 0 until height) {
            for (c in 0 until stride) {
                buf[r * stride + c] = if (c >= width) 255.toByte() else 128
            }
        }
        val mask = ZebraAnalysis.threshold(buf, width, height, stride, 1, cols = 8, rows = 6)
        assertTrue("padding must not flag any highlight cell", mask.highlightRuns.isEmpty())
        assertTrue("padding must not flag any shadow cell", mask.shadowRuns.isEmpty())
    }

    @Test
    fun `pixel stride greater than one reads only luma samples`() {
        // pixelStride 2: every other byte is an interleaved non-luma sample set to 255.
        val width = 8; val height = 2; val pixelStride = 2; val rowStride = width * pixelStride
        val buf = ByteArray(rowStride * height)
        for (i in buf.indices) buf[i] = if (i % 2 == 1) 255.toByte() else 128
        val mask = ZebraAnalysis.threshold(buf, width, height, rowStride, pixelStride, cols = 4, rows = 2)
        assertTrue("interleaved samples must not be read as luma", mask.highlightRuns.isEmpty())
        assertTrue(mask.shadowRuns.isEmpty())
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
        assertTrue(mask.highlightRuns.all { it.row in 0 until 24 })
        assertTrue(mask.highlightRuns.all { it.startCol >= 0 && it.endColExclusive <= 32 })
    }

    // ---- pickAnalysisSize (unchanged behavior; kept for regression) ----

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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (PowerShell, from `C:\Users\User\rawcam`):
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: FAIL to compile — `Unresolved reference: highlightRuns` / `shadowRuns` (the source still has the old single `runs` field).

- [ ] **Step 3: Write the minimal implementation**

Replace the full contents of `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt` with:

```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: PASS, 21 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt
git commit -m "feat: split zebra threshold into independent highlight and shadow masks"
```

---

### Task 2: Independent Settings toggles

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` (4 edits)
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:241-245`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `Settings.zebraHighlightEnabled: Boolean` (default `false`, DataStore key `"zebraHighlightEnabled"`, migrating from the old `"zebraEnabled"` key) and `Settings.zebraShadowEnabled: Boolean` (default `false`, DataStore key `"zebraShadowEnabled"`, no migration).

This task is deliberately self-contained, same as the original zebra toggle's task: after it, both toggles persist across a force-stop but change nothing visible yet. That is the reviewable deliverable. No unit test exists for `SettingsRepository` today (it needs a live `Context`), so this task is verified by a full build instead of a test run — consistent with how the rest of this file is covered.

- [ ] **Step 1: Split the `Settings` data class field**

In `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt`, replace:

```kotlin
    val zebraEnabled: Boolean = false,
```

with:

```kotlin
    val zebraHighlightEnabled: Boolean = false,
    val zebraShadowEnabled: Boolean = false,
```

- [ ] **Step 2: Split the DataStore key, keeping the old one for migration**

Replace:

```kotlin
    private val KEY_ZEBRA_ENABLED = booleanPreferencesKey("zebraEnabled")
```

with:

```kotlin
    // Migration-only: no longer written, read as zebraHighlightEnabled's fallback so a
    // device that already had zebra on keeps it on after the highlight/shadow split.
    private val KEY_ZEBRA_ENABLED = booleanPreferencesKey("zebraEnabled")
    private val KEY_ZEBRA_HIGHLIGHT_ENABLED = booleanPreferencesKey("zebraHighlightEnabled")
    private val KEY_ZEBRA_SHADOW_ENABLED = booleanPreferencesKey("zebraShadowEnabled")
```

- [ ] **Step 3: Split the read, with migration fallback**

In `Preferences.toSettings()`, replace:

```kotlin
            zebraEnabled = this[KEY_ZEBRA_ENABLED] ?: fallback.zebraEnabled,
```

with:

```kotlin
            zebraHighlightEnabled = this[KEY_ZEBRA_HIGHLIGHT_ENABLED]
                ?: this[KEY_ZEBRA_ENABLED] ?: fallback.zebraHighlightEnabled,
            zebraShadowEnabled = this[KEY_ZEBRA_SHADOW_ENABLED] ?: fallback.zebraShadowEnabled,
```

- [ ] **Step 4: Split the write**

In `update()`, replace:

```kotlin
            prefs[KEY_ZEBRA_ENABLED] = next.zebraEnabled
```

with:

```kotlin
            prefs[KEY_ZEBRA_HIGHLIGHT_ENABLED] = next.zebraHighlightEnabled
            prefs[KEY_ZEBRA_SHADOW_ENABLED] = next.zebraShadowEnabled
```

- [ ] **Step 5: Split the Settings UI row**

In `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`, replace:

```kotlin
            ToggleRow(
                title = "Zebras", subtitle = "Stripe the parts of the frame that are clipping to white",
                checked = settings.zebraEnabled,
                onChange = { v -> apply { it.copy(zebraEnabled = v) } },
            )
```

with:

```kotlin
            ToggleRow(
                title = "Highlight zebra", subtitle = "Stripe blown highlights (clipping to white)",
                checked = settings.zebraHighlightEnabled,
                onChange = { v -> apply { it.copy(zebraHighlightEnabled = v) } },
            )
            ToggleRow(
                title = "Shadow zebra", subtitle = "Stripe crushed shadows (clipping to black)",
                checked = settings.zebraShadowEnabled,
                onChange = { v -> apply { it.copy(zebraShadowEnabled = v) } },
            )
```

- [ ] **Step 6: Verify the build**

Run:
```
.\gradlew.bat :app:assembleDebug
```
Expected: fails with `Unresolved reference: zebraEnabled` confined to `CameraController.kt` and `RecordScreen.kt` only (Tasks 3-4 fix those). Confirm no other errors before moving on.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt
git commit -m "feat: split zebra setting into independent highlight and shadow toggles"
```

---

### Task 3: CameraController plumbing

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: `Settings.zebraHighlightEnabled` / `Settings.zebraShadowEnabled` (Task 2, by name only).
- Produces: `CameraController.zebraHighlightEnabled: Boolean` and `CameraController.zebraShadowEnabled: Boolean` (both `@Volatile`, default `false`), replacing `zebraEnabled`. `zebraMask: StateFlow<ZebraMask?>` keeps its existing name/type.

- [ ] **Step 1: Split the controller flag**

Replace:

```kotlin
    /**
     * Mirrors [Settings.zebraEnabled]; written by RecordViewModel's settings
     * collector (same pattern as [debugLogging]) and read by [createSession] when it
     * assembles the output list. Because the Settings screen is unreachable while
     * recording (its nav button is disabled), this can never flip mid-recording.
     */
    @Volatile var zebraEnabled: Boolean = false
```

with:

```kotlin
    /**
     * Mirror [Settings.zebraHighlightEnabled] and [Settings.zebraShadowEnabled];
     * written by RecordViewModel's settings collector (same pattern as
     * [debugLogging]) and read by [createSession] when it assembles the output
     * list. Because the Settings screen is unreachable while recording (its nav
     * button is disabled), neither can flip mid-recording.
     */
    @Volatile var zebraHighlightEnabled: Boolean = false
    @Volatile var zebraShadowEnabled: Boolean = false
```

- [ ] **Step 2: Update the analysis-stream gate**

Replace:

```kotlin
        val zebra = if (withZebra && zebraEnabled) ensureZebraSurface() else { releaseZebra(); null }
```

with:

```kotlin
        val zebra = if (withZebra && (zebraHighlightEnabled || zebraShadowEnabled)) {
            ensureZebraSurface()
        } else {
            releaseZebra()
            null
        }
```

- [ ] **Step 3: Verify the build**

Run:
```
.\gradlew.bat :app:assembleDebug
```
Expected: `CameraController.kt` compiles clean; remaining failures confined to `RecordScreen.kt` (Task 4's job). Confirm that before moving on.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m "feat: gate the zebra analysis stream on either highlight or shadow toggle"
```

---

### Task 4: RecordScreen wiring and dual-color overlay

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`

**Interfaces:**
- Consumes: `CameraController.zebraHighlightEnabled` / `zebraShadowEnabled` (Task 3), `Settings.zebraHighlightEnabled` / `zebraShadowEnabled` (Task 2), `ZebraMask.highlightRuns` / `shadowRuns` (Task 1).
- Produces: `ZebraOverlay(maskFlow: StateFlow<ZebraMask?>, highlightEnabled: Boolean, shadowEnabled: Boolean, modifier: Modifier = Modifier)` (private, `RecordScreen.kt`-local, same visibility as today).

- [ ] **Step 1: Push both settings flags to the controller**

Replace:

```kotlin
                controller.zebraEnabled = s.zebraEnabled
```

with:

```kotlin
                controller.zebraHighlightEnabled = s.zebraHighlightEnabled
                controller.zebraShadowEnabled = s.zebraShadowEnabled
```

- [ ] **Step 2: Re-key session recreation on both flags**

Replace:

```kotlin
            // zebraEnabled joins the key because it changes the session's output
            // list: recreating the SurfaceView drives surfaceCreated -> openCamera ->
            // openAndPreview, which rebuilds the session with (or without) the
            // analysis stream. Same proven path as a lens or resolution switch. Safe
            // to do unconditionally because the Settings screen is disabled while
            // recording, so this key cannot change mid-take.
            key(state.lensIndex, state.sizeIndex, state.settings.zebraEnabled) {
```

with:

```kotlin
            // zebraHighlightEnabled/zebraShadowEnabled join the key because either
            // one changes the session's output list: recreating the SurfaceView
            // drives surfaceCreated -> openCamera -> openAndPreview, which rebuilds
            // the session with (or without) the analysis stream. Same proven path as
            // a lens or resolution switch. Safe to do unconditionally because the
            // Settings screen is disabled while recording, so this key cannot change
            // mid-take.
            key(
                state.lensIndex, state.sizeIndex,
                state.settings.zebraHighlightEnabled, state.settings.zebraShadowEnabled,
            ) {
```

- [ ] **Step 3: Update the overlay composition site**

Replace:

```kotlin
            // Zebra stripes over clipped highlights. Same paint-layer-only reasoning
            // as the grid above: no pointerInput of its own, so tap-to-meter is
            // unaffected.
            if (state.settings.zebraEnabled) {
                ZebraOverlay(viewModel.zebraMask, Modifier.fillMaxSize())
            }
```

with:

```kotlin
            // Zebra stripes over clipped highlights and/or crushed shadows. Same
            // paint-layer-only reasoning as the grid above: no pointerInput of its
            // own, so tap-to-meter is unaffected.
            if (state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled) {
                ZebraOverlay(
                    viewModel.zebraMask,
                    state.settings.zebraHighlightEnabled,
                    state.settings.zebraShadowEnabled,
                    Modifier.fillMaxSize(),
                )
            }
```

- [ ] **Step 4: Restyle and extend the overlay composable**

Replace the whole `ZebraOverlay` function (kdoc included) with:

```kotlin
/**
 * Animated diagonal stripes over the cells [CameraController.zebraMask] flagged as
 * clipping: red/white over blown highlights, blue over crushed shadows, each drawn
 * only when its corresponding setting is on.
 *
 * Both the mask and the animation phase are read INSIDE the Canvas draw lambda, via
 * State objects that are never destructured in the composable body. That is the whole
 * point of the shape: a new mask ~15x/second (and a new phase every frame) invalidates
 * the draw phase only. Hoisting either read with `by` would recompose this composable
 * at that rate instead, and folding the mask into RecordUiState would recompose the
 * entire screen.
 */
@Composable
private fun ZebraOverlay(
    maskFlow: StateFlow<ZebraMask?>,
    highlightEnabled: Boolean,
    shadowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val mask = maskFlow.collectAsState()
    val transition = rememberInfiniteTransition(label = "zebra")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "zebraPhase",
    )
    Canvas(modifier) {
        val m = mask.value ?: return@Canvas
        if (m.cols <= 0 || m.rows <= 0) return@Canvas
        // 7dp candy-stripe period, half the originally-shipped 14dp bars -- a
        // tighter pitch reads more clearly as "zebra". Hard stops at the midpoint
        // make a stripe, not a gradient; a diagonal start->end vector plus
        // TileMode.Repeated tiles it across the whole layer, so stripes stay
        // continuous from one cell run to the next instead of restarting per rect.
        // Both warnings share one phase/diagonal so they animate in lockstep when
        // both are visible at once.
        val period = 7.dp.toPx()
        val shift = phase.value * period
        val start = Offset(shift, shift)
        val end = Offset(shift + period, shift + period)
        val cw = size.width / m.cols
        val ch = size.height / m.rows
        if (highlightEnabled && m.highlightRuns.isNotEmpty()) {
            val stripes = Brush.linearGradient(
                0.0f to ZebraHighlightColor.copy(alpha = 0.85f),
                0.5f to ZebraHighlightColor.copy(alpha = 0.85f),
                0.5f to Color.White.copy(alpha = 0.85f),
                1.0f to Color.White.copy(alpha = 0.85f),
                start = start,
                end = end,
                tileMode = TileMode.Repeated,
            )
            m.highlightRuns.forEach { run ->
                drawRect(
                    brush = stripes,
                    topLeft = Offset(run.startCol * cw, run.row * ch),
                    size = Size((run.endColExclusive - run.startCol) * cw, ch),
                )
            }
        }
        if (shadowEnabled && m.shadowRuns.isNotEmpty()) {
            // Blue alternating with fully transparent (not a second opaque color):
            // the gaps show the real, already-dark preview pixels through, unlike
            // the highlight brush's opaque white counter-stripe.
            val stripes = Brush.linearGradient(
                0.0f to ZebraShadowColor.copy(alpha = 0.85f),
                0.5f to ZebraShadowColor.copy(alpha = 0.85f),
                0.5f to Color.Transparent,
                1.0f to Color.Transparent,
                start = start,
                end = end,
                tileMode = TileMode.Repeated,
            )
            m.shadowRuns.forEach { run ->
                drawRect(
                    brush = stripes,
                    topLeft = Offset(run.startCol * cw, run.row * ch),
                    size = Size((run.endColExclusive - run.startCol) * cw, ch),
                )
            }
        }
    }
}

/** Reuses the app's existing red accent rather than a new one-off hex -- close
 * enough to the reference screenshots' red to read as the same convention. */
private val ZebraHighlightColor = RawCamColors.Accent

/** No existing theme color is blue; this is zebra-only. */
private val ZebraShadowColor = Color(0xFF3385FF)
```

- [ ] **Step 5: Verify both build variants and the full test suite**

Run:
```
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```
Expected: both variants BUILD SUCCESSFUL (release runs R8); 69 tests, 0 failures (64 pre-existing + 5 net-new from Task 1).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: independent shadow zebra warning, red/white highlight restyle"
```

---

### Task 5: On-device verification

**Files:**
- Modify: `docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md` (status line)
- Create: `docs/superpowers/open-items-2026-07-29-zebra-shadow.md` (only if something fails)

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: a verification record. No code interface.

This project's standing convention is that a feature is not done until it is verified on real hardware, and its hardest-won lesson is that **a visual fix must never be reported as done off a green build alone** — screenshot and confirm. That applies directly here, doubly so since this whole task is a visual restyle.

If no device is connected at execution time (`adb devices` is empty), stop after Task 4, mark this task as an owed item, and say so plainly rather than reporting the feature complete.

- [ ] **Step 1: Install on a real device**

Run (PowerShell; `adb` is not on PATH — use the SDK copy):
```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\gradlew.bat :app:installDebug
```
Expected: exactly one device listed; install succeeds.

- [ ] **Step 2: Verify both toggles persist independently**

Open Settings -> VIEWFINDER. Turn "Highlight zebra" on and "Shadow zebra" off, force-stop the app, relaunch, reopen Settings.
Expected: "Highlight zebra" is still on, "Shadow zebra" is still off. Repeat with the opposite combination to confirm the two persist independently, not coupled.

If a tap on a row does not register, do **not** re-estimate coordinates from a scaled screenshot — get exact bounds with `adb shell uiautomator dump`, the documented fix for this project's silent-tap failures.

- [ ] **Step 3: Verify the restyled highlight stripes**

With only "Highlight zebra" on, point the camera at a bright window or lamp and raise ISO/shutter until part of the frame is clearly blown.
Expected: a fine red/white candy-stripe (not solid white) appears over the blown region and nowhere else, visibly tighter-pitched than the previous 14dp bars, and disappears when exposure is pulled down.

Take a screenshot and zoom in to confirm the two-tone stripe pattern visually — a green build proves nothing here.

- [ ] **Step 4: Verify the new shadow stripes**

With only "Shadow zebra" on, point the camera at a deep-shadow area until part of the frame crushes to black.
Expected: a fine blue stripe appears over the crushed region and nowhere else, with the dark image visible through the gaps, and disappears when the shadow is lifted.

Take a screenshot and zoom in to confirm.

- [ ] **Step 5: Verify both warnings run together without interference**

Turn both toggles on. Frame a scene with both a blown highlight and a crushed shadow simultaneously.
Expected: red/white stripes over the blown area, blue stripes over the crushed area, each confined to its own region, both animating at the same apparent speed.

- [ ] **Step 6: Verify mask alignment (orientation) still holds for shadow**

Fill only **one corner** of the frame with a crushed shadow.
Expected: blue stripes appear in that same corner. The highlight-side orientation was already verified for the shipped feature; this confirms the shadow run list, which shares the same grid/pixel-mapping code path, maps correctly too. If it doesn't, don't guess the transform — determine it from what the corner test shows.

- [ ] **Step 7: Verify toggling either flag mid-preview doesn't destabilize the camera**

With the viewfinder up, toggle both flags off and on several times, independently and together, returning to Record each time.
Expected: preview recovers every time, no black frames left behind, no "Camera open failed" toast.

- [ ] **Step 8: Verify recording is not degraded with both warnings active**

Record a clip of at least 30 seconds with both toggles ON, at the device's full resolution and frame rate.
Expected: the completion toast reports **0 dropped**. Compare against a same-length clip with both OFF.

- [ ] **Step 9: Check the crash buffer**

Run:
```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -b crash -d
```
Expected: no `FATAL EXCEPTION` from `com.shez.rawcam`.

- [ ] **Step 10: Record the outcome**

Update the spec's status line at `docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md:4` from `Design approved, not yet implemented` to `Implemented and device-verified <date>` (or, if a step failed, to a one-line statement of what's outstanding, with detail in `docs/superpowers/open-items-2026-07-29-zebra-shadow.md`).

- [ ] **Step 11: Commit**

```bash
git add docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md
git commit -m "docs: zebra shadow warning device-verified"
```

---

## Self-Review

**Spec coverage.** Shadow threshold `Y == 0` -> Task 1. Two independent toggles, migration from the old key -> Task 2. 7dp pitch, red/white highlight, blue/transparent shadow, shared 900ms phase -> Task 4 Step 4. Analysis-stream gate as an OR of both flags -> Task 3 Step 2. Session-recreation key on both flags -> Task 4 Step 2. Draws each run list independently gated on its own flag -> Task 4 Step 4. Error handling (no change from the shipped feature) -> confirmed by Tasks 3/4 touching only the gate/wiring, not `ensureZebraSurface`/`releaseZebra`. Testing (extend `ZebraAnalysisTest`, on-device gate) -> Task 1 and Task 5. Out-of-scope items (adjustable thresholds/pitch, shared toggle, GPU/shader, RAW pipeline changes) are absent from every task, and `core/`/`cpp/` are untouched by all four code tasks.

**Placeholders.** None: every code step carries full function/section bodies, every run step carries the exact command and expected result, and Task 5's contingencies name the concrete next action rather than "handle appropriately."

**Type consistency.** `ZebraMask(cols, rows, highlightRuns, shadowRuns)` and `ZebraMask.CellRun(row, startCol, endColExclusive)` are defined in Task 1 and used with those exact names in Tasks 3 (`zebraMask: StateFlow<ZebraMask?>`, unchanged type) and 4. `ZebraAnalysis.threshold` keeps its Task-1-defined signature into Task 4. `zebraHighlightEnabled`/`zebraShadowEnabled` name the same pair on `Settings` (Task 2), `CameraController` (Task 3), and `RecordScreen`'s `state.settings.*` reads (Task 4). `ZebraOverlay`'s `highlightEnabled`/`shadowEnabled` parameters are defined and consumed within the same task (Task 4), so there's no cross-task drift to check.
