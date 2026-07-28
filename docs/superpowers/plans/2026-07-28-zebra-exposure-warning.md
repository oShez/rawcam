# Zebra Exposure Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional animated diagonal-stripe overlay over clipped highlights in RawCam's live preview, toggleable in Settings, active whenever the viewfinder is up.

**Architecture:** A third, optional low-resolution `YUV_420_888` `ImageReader` is added to the Camera2 session's outputs only when `Settings.zebraEnabled` is true. Its Y-plane *is* luminance, so a pure Kotlin function thresholds it into a coarse cell grid (run-length merged) with zero Android dependencies and full host test coverage. The mask is published as a `StateFlow` read inside a Compose `Canvas` draw lambda, so new masks invalidate the draw phase only — never a recomposition of `RecordScreen`.

**Tech Stack:** Kotlin, Camera2 (`ImageReader`, `SessionConfiguration`), Jetpack Compose (`Canvas`, `Brush.linearGradient` with `TileMode.Repeated`, `rememberInfiniteTransition`), Jetpack DataStore, JUnit4 on the JVM.

**Spec:** `docs/superpowers/specs/2026-07-26-zebra-exposure-warning-design.md`

## Global Constraints

- Fixed clip threshold: `Y == 255`, the maximum of the 8-bit Y plane. Not user-adjustable in this feature.
- Active whenever the viewfinder is live: idle preview **and** recording.
- Toggling the setting recreates the camera session (same cost class as switching lens/resolution today).
- Animated diagonal stripes, not a static tint.
- Graceful degradation, not a hard requirement: if no usable analysis size exists or the third output can't be added, the toggle stays visible and flippable and turning it on is a silent no-op. No new "unsupported" UI state.
- Default off, matching `gridEnabled` / `levelEnabled`.
- Zero change to the RAW capture/export pipeline. `core/` and `app/src/main/cpp/` MUST remain untouched — the C++ `ctest` suite (7/7) must still stand without being re-run against modified sources.
- Pure logic lives in `com.shez.rawcam.camera` with no `android.*` imports, mirroring `ShutterStops` and `LensDiscovery`.
- All Gradle commands run from PowerShell in `C:\Users\User\rawcam`. Gradle needs Java 21 via the user-level `~/.gradle/gradle.properties`.

## Deviations from the design spec (deliberate, adopted in this plan)

1. **The mask does NOT go into `RecordUiState`.** The spec said "published into `RecordUiState` (mirrors how `meterPoint`/grid/level state already flows)". `meterPoint` changes on a tap; a zebra mask changes ~15×/second. Putting it in `RecordUiState` would recompose all of `RecordScreen` — every chip, slider and rail — at analysis cadence. Instead `CameraController` owns a `StateFlow<ZebraMask?>` which the overlay reads *inside its `Canvas` draw lambda*, so a new mask invalidates the draw phase only.
2. **Analysis is throttled to ~15 fps** (`ZEBRA_MIN_INTERVAL_NS`). A visual exposure aid does not need every frame, and this halves the cost during recording, which is the only moment it competes with the ~376 MB/s capture hot path.
3. **The mask is run-length merged** into horizontal cell runs. Worst case for zebra is pointing at a bright sky — i.e. *every* cell flagged — so bounding draw calls at merge time (a pure, tested transform) keeps the fully-clipped frame at ~24 `drawRect` calls instead of 768.
4. **Analysis size is capped to preview area (≤ 1920×1080).** Camera2's mandatory stream-combination table guarantees `PRIV(preview) + YUV(preview) + RAW` on any device advertising the RAW capability — which is RawCam's hard requirement anyway. Staying inside the preview size class is what makes the third stream safe rather than merely likely.

## File Structure

**Create:**
- `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt` — `ZebraMask` data class + pure `threshold()` and `pickAnalysisSize()`. No `android.*` imports.
- `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt` — host tests for both pure functions.

**Modify:**
- `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` — `Settings.zebraEnabled` field, DataStore key, read + write.
- `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:232-240` — a `ToggleRow` in the existing `VIEWFINDER` section.
- `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` — analysis `ImageReader` + dedicated `HandlerThread`, conditional session output, mask `StateFlow`, teardown.
- `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — push `zebraEnabled` to the controller, re-key the preview on it, add the `ZebraOverlay` composable.

---

### Task 1: Pure clip-threshold function

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt`
- Test: `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `data class ZebraMask(val cols: Int, val rows: Int, val runs: List<ZebraMask.CellRun>)`
  - `data class ZebraMask.CellRun(val row: Int, val startCol: Int, val endColExclusive: Int)`
  - `ZebraMask.EMPTY: ZebraMask`
  - `ZebraAnalysis.threshold(y: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, cols: Int = GRID_COLS, rows: Int = GRID_ROWS): ZebraMask`
  - `ZebraAnalysis.CLIP_THRESHOLD: Int = 255`, `GRID_COLS: Int = 32`, `GRID_ROWS: Int = 24`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt`:

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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (PowerShell, from `C:\Users\User\rawcam`):
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: FAIL to compile — `Unresolved reference: ZebraAnalysis` / `ZebraMask`.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt`:

```kotlin
package com.shez.rawcam.camera

/**
 * A coarse grid of clipped-highlight cells, run-length merged along each row.
 *
 * Deliberately not per-pixel: zebra is a visual warning, not a measurement, and a
 * coarse grid keeps the overlay's per-frame draw-call count bounded. Runs exist
 * because the worst case for this feature -- pointing at a bright sky, where every
 * cell is flagged -- is also its most common real use; merging turns that from
 * [cols] x [rows] rects into one rect per row.
 *
 * [runs] is ordered by row, then by [CellRun.startCol]. An empty [runs] means
 * nothing is clipping.
 */
data class ZebraMask(
    val cols: Int,
    val rows: Int,
    val runs: List<CellRun>,
) {
    /** A horizontal span of flagged cells in one row: `[startCol, endColExclusive)`. */
    data class CellRun(val row: Int, val startCol: Int, val endColExclusive: Int)

    companion object {
        val EMPTY = ZebraMask(0, 0, emptyList())
    }
}

/**
 * Pure luminance analysis for the zebra overlay. No `android.*` dependency, so it
 * is exercised by real host tests rather than only on-device -- the same shape as
 * [ShutterStops] and [LensDiscovery].
 */
object ZebraAnalysis {

    /** A pixel counts as clipped only at the 8-bit Y plane's true maximum. */
    const val CLIP_THRESHOLD = 255

    const val GRID_COLS = 32
    const val GRID_ROWS = 24

    /**
     * Flags every grid cell containing at least one pixel at [CLIP_THRESHOLD].
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

        val flags = BooleanArray(cols * rows)
        for (py in 0 until height) {
            val cellRow = py * rows / height
            val rowBase = py * rowStride
            val flagBase = cellRow * cols
            for (px in 0 until width) {
                if ((y[rowBase + px * pixelStride].toInt() and 0xFF) >= CLIP_THRESHOLD) {
                    flags[flagBase + px * cols / width] = true
                }
            }
        }

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
        return ZebraMask(cols, rows, runs)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt
git commit -m "feat: pure clipped-highlight threshold for the zebra overlay"
```

---

### Task 2: Pure analysis-size selection

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt` (add one function)
- Test: `app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt` (add cases)

**Interfaces:**
- Consumes: `ZebraAnalysis` from Task 1; `SizeSpec(width, height, minFrameDurationNs)` — an existing type in `app/src/main/java/com/shez/rawcam/camera/CameraSnapshot.kt:8`.
- Produces: `ZebraAnalysis.pickAnalysisSize(candidates: List<SizeSpec>, targetAspect: Float): SizeSpec?` and `ZebraAnalysis.PREVIEW_AREA_CAP: Long`.

- [ ] **Step 1: Write the failing test**

Append these methods inside `ZebraAnalysisTest`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: FAIL to compile — `Unresolved reference: pickAnalysisSize`.

- [ ] **Step 3: Write the minimal implementation**

Add `import kotlin.math.abs` at the top of `app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt`, then add to the `ZebraAnalysis` object:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.camera.ZebraAnalysisTest"
```
Expected: PASS, 16 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/ZebraAnalysis.kt app/src/test/java/com/shez/rawcam/camera/ZebraAnalysisTest.kt
git commit -m "feat: analysis stream size selection for the zebra overlay"
```

---

### Task 3: Setting + Settings UI row

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` (4 edits)
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:237-240` (insert one row)

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces: `Settings.zebraEnabled: Boolean` (default `false`), persisted under the DataStore key `"zebraEnabled"`.

This task is deliberately self-contained: after it, the toggle persists across a force-stop but changes nothing visible. That is the reviewable deliverable.

- [ ] **Step 1: Add the field to the `Settings` data class**

In `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt`, immediately after the `levelEnabled` line (currently line 60):

```kotlin
    val levelEnabled: Boolean = false,
    val zebraEnabled: Boolean = false,
```

- [ ] **Step 2: Add the DataStore key**

After `KEY_LEVEL_ENABLED` (currently line 140):

```kotlin
    private val KEY_LEVEL_ENABLED = booleanPreferencesKey("levelEnabled")
    private val KEY_ZEBRA_ENABLED = booleanPreferencesKey("zebraEnabled")
```

- [ ] **Step 3: Add the read**

In `Preferences.toSettings()`, after the `levelEnabled` line (currently line 190):

```kotlin
            levelEnabled = this[KEY_LEVEL_ENABLED] ?: fallback.levelEnabled,
            zebraEnabled = this[KEY_ZEBRA_ENABLED] ?: fallback.zebraEnabled,
```

- [ ] **Step 4: Add the write**

In `update()`, after the `KEY_LEVEL_ENABLED` write (currently line 245):

```kotlin
            prefs[KEY_LEVEL_ENABLED] = next.levelEnabled
            prefs[KEY_ZEBRA_ENABLED] = next.zebraEnabled
```

- [ ] **Step 5: Add the Settings UI row**

In `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`, in the `VIEWFINDER` section, directly after the "Level" `ToggleRow` (currently lines 237-240):

```kotlin
            ToggleRow(
                title = "Level", subtitle = "Horizon indicator", checked = settings.levelEnabled,
                onChange = { v -> apply { it.copy(levelEnabled = v) } },
            )
            ToggleRow(
                title = "Zebras", subtitle = "Stripe the parts of the frame that are clipping to white",
                checked = settings.zebraEnabled,
                onChange = { v -> apply { it.copy(zebraEnabled = v) } },
            )
```

- [ ] **Step 6: Verify the build and the existing suite are green**

Run:
```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL; the full unit suite passes (48 pre-existing + 16 from Tasks 1-2 = 64 tests, 0 failures).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt
git commit -m "feat: zebraEnabled setting and its viewfinder toggle row"
```

---

### Task 4: Camera2 analysis stream

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: `ZebraAnalysis.threshold(...)`, `ZebraAnalysis.pickAnalysisSize(...)`, `ZebraMask`, `ZebraMask.EMPTY` (Tasks 1-2); `Settings.zebraEnabled` (Task 3).
- Produces:
  - `CameraController.zebraEnabled: Boolean` — a `@Volatile var` written by the caller, read at session-creation time.
  - `CameraController.zebraMask: StateFlow<ZebraMask?>` — null when zebra is off or unavailable.

This is the risky task. It touches the session path that the telephoto work (`e74ead8`) and the surface-release race fix (`c62d574`) both live in. Two rules: (a) do **not** alter the `activePhysicalId` / `activeCameraId` / `sessionTagId` three-way split; (b) add the analysis surface inside `createSession` itself, not at its four call sites, so no path can silently miss it.

- [ ] **Step 1: Add the imports**

At the top of `CameraController.kt`, alongside the existing `android.hardware.camera2.*` imports:

```kotlin
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

(`android.os.HandlerThread`, `android.os.Handler`, `android.view.Surface` and `android.util.Log` are already imported.)

- [ ] **Step 2: Add the fields**

Directly after the `rawSurface` declaration (currently line 213):

```kotlin
    @Volatile private var rawSurface: Surface? = null

    /**
     * Mirrors [Settings.zebraEnabled]; written by RecordViewModel's settings
     * collector (same pattern as [debugLogging]) and read by [createSession] when it
     * assembles the output list. Because the Settings screen is unreachable while
     * recording (its nav button is disabled), this can never flip mid-recording.
     */
    @Volatile var zebraEnabled: Boolean = false

    /** Optional low-res YUV analysis stream feeding the zebra overlay. Created and
     * torn down only from the camera thread (inside [createSession] / [close]). */
    private var zebraReader: ImageReader? = null
    @Volatile private var zebraSurface: Surface? = null
    private var zebraThread: HandlerThread? = null
    private var zebraHandler: Handler? = null

    /** Reused across frames so the analysis path allocates nothing steady-state --
     * same reasoning as the capture queue's ring buffer. Analysis-thread-only. */
    private var zebraBuffer: ByteArray = ByteArray(0)
    private var lastZebraNs = 0L

    private val _zebraMask = MutableStateFlow<ZebraMask?>(null)

    /** Latest clipped-highlight mask, or null when zebra is off or unavailable on
     * this device. Updated at most every [ZEBRA_MIN_INTERVAL_NS] from the analysis
     * thread; consumers must read it in a way that does not force a full
     * recomposition (see RecordScreen's ZebraOverlay). */
    val zebraMask: StateFlow<ZebraMask?> = _zebraMask.asStateFlow()
```

- [ ] **Step 3: Add the throttle constant**

In the `companion object` at the bottom of the class, alongside `SESSION_TIMEOUT_S`:

```kotlin
        /** ~15 analyses/second. A visual exposure aid does not need every frame, and
         * halving the work matters most while recording, where it shares the device
         * with the capture hot path. */
        private const val ZEBRA_MIN_INTERVAL_NS = 66_000_000L
```

- [ ] **Step 4: Add the analysis plumbing**

Add these three private members immediately before `private fun createSession(` (currently line 1017):

```kotlin
    /**
     * Reads the Y plane (which *is* luminance -- the reason this stream is YUV and
     * not a second RAW one), thresholds it, and publishes the mask.
     *
     * `acquireLatestImage` deliberately discards backlog: if analysis falls behind,
     * the right answer is the newest frame, not a queued stale one. Every failure is
     * logged and swallowed -- an exception escaping here would kill the analysis
     * thread, and a preview aid must never be able to do that.
     */
    private val zebraListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "zebra acquire failed", e); null
        } ?: return@OnImageAvailableListener
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastZebraNs >= ZEBRA_MIN_INTERVAL_NS) {
                lastZebraNs = now
                val plane = img.planes[0]
                val buf = plane.buffer
                val n = buf.remaining()
                if (zebraBuffer.size < n) zebraBuffer = ByteArray(n)
                buf.get(zebraBuffer, 0, n)
                _zebraMask.value = ZebraAnalysis.threshold(
                    zebraBuffer, img.width, img.height, plane.rowStride, plane.pixelStride,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "zebra analysis failed", e)
        } finally {
            try { img.close() } catch (e: Exception) { Log.w(TAG, "zebra image close failed", e) }
        }
    }

    /**
     * Returns the analysis surface for the session about to be created, building the
     * reader (and its thread) on first use and rebuilding it whenever the active
     * lens's chosen size changes. Camera-thread only.
     *
     * Returns null when the device advertises no usable YUV size -- the session is
     * then created without a third output and zebra is a silent no-op, per the
     * spec's graceful-degradation rule.
     */
    private fun ensureZebraSurface(): Surface? {
        val spec = rawSpec
        val sizes = try {
            cameraManager.getCameraCharacteristics(activeCameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { SizeSpec(it.width, it.height) }
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "zebra: YUV size query failed", e); emptyList()
        }
        val pick = ZebraAnalysis.pickAnalysisSize(
            sizes, spec.width.toFloat() / spec.height,
        ) ?: run {
            Log.w(TAG, "zebra: no usable YUV size on camera $activeCameraId")
            releaseZebra()
            return null
        }
        val existing = zebraReader
        if (existing != null && existing.width == pick.width && existing.height == pick.height) {
            return zebraSurface
        }
        releaseZebra()
        return try {
            val thread = zebraThread ?: HandlerThread("camera-zebra").apply { start() }
                .also { zebraThread = it; zebraHandler = Handler(it.looper) }
            val reader = ImageReader.newInstance(pick.width, pick.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener(zebraListener, zebraHandler ?: Handler(thread.looper))
            zebraReader = reader
            zebraSurface = reader.surface
            Log.i(TAG, "zebra: analysis stream ${pick.width}x${pick.height}")
            zebraSurface
        } catch (e: Exception) {
            Log.e(TAG, "zebra: reader creation failed", e)
            releaseZebra()
            null
        }
    }

    /** Tears the analysis stream down and clears the published mask. Camera-thread
     * only. The HandlerThread itself is left running for reuse and quit in [close]. */
    private fun releaseZebra() {
        try { zebraReader?.close() } catch (e: Exception) { Log.w(TAG, "zebra reader close failed", e) }
        zebraReader = null
        zebraSurface = null
        _zebraMask.value = null
    }
```

- [ ] **Step 5: Wire the surface into `createSession`**

In `createSession`, append the analysis surface centrally. Change:

```kotlin
        val dev = device ?: run { onFailed(); return }
        val generation = ++sessionGeneration
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map { s ->
```

to:

```kotlin
        val dev = device ?: run { onFailed(); return }
        val generation = ++sessionGeneration
        // Appended here rather than at createSession's four call sites so no path --
        // preview open, recording start, or either failure-recovery reopen -- can
        // silently omit it. Tagged with sessionTagId alongside the others below,
        // which a standalone lens correctly leaves null.
        val zebra = if (zebraEnabled) ensureZebraSurface() else { releaseZebra(); null }
        val allSurfaces = if (zebra != null) surfaces + zebra else surfaces
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                allSurfaces.map { s ->
```

- [ ] **Step 6: Add the repeating-request targets**

A surface configured into the session but never targeted receives no frames. In `setRepeatingPreview`:

```kotlin
    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            zebraSurface?.let { addTarget(it) }
            if (manualSet) applyManual(this, withFrameDuration = false)
        }.build()
        s.setRepeatingRequest(req, null, cameraHandler)
    }
```

And in `setRepeatingRecord`:

```kotlin
    private fun setRepeatingRecord(s: CameraCaptureSession) {
        val dev = device ?: return
        val raw = rawSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface ?: return)
            addTarget(raw)
            zebraSurface?.let { addTarget(it) }
            applyManual(this, withFrameDuration = true)
        }.build()
        s.setRepeatingRequest(req, captureCallback, cameraHandler)
    }
```

- [ ] **Step 7: Tear down in `close()`**

Find `close()` (it quits `cameraThread` and `meterCallbackThread`) and add the zebra teardown alongside, before the camera thread is quit:

```kotlin
        releaseZebra()
        zebraThread?.quitSafely()
        zebraThread = null
        zebraHandler = null
```

- [ ] **Step 8: Verify the build and the full suite**

Run:
```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL; 64 tests, 0 failures. `core/` and `app/src/main/cpp/` are untouched, so the C++ `ctest` result stands.

- [ ] **Step 9: Confirm the C++ side really is untouched**

Run:
```
git status --short core app/src/main/cpp
```
Expected: no output. If anything is listed, revert it — this feature must not touch the native layer.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m "feat: optional YUV analysis stream feeding the zebra mask"
```

---

### Task 5: Overlay rendering and session recreation

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`

**Interfaces:**
- Consumes: `CameraController.zebraEnabled`, `CameraController.zebraMask` (Task 4); `ZebraMask` (Task 1); `Settings.zebraEnabled` (Task 3).
- Produces: the visible feature. Nothing downstream depends on it.

- [ ] **Step 1: Add the imports**

At the top of `RecordScreen.kt`, alongside the existing Compose imports:

```kotlin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import com.shez.rawcam.camera.ZebraMask
import kotlinx.coroutines.flow.StateFlow
```

(`Canvas`, `Color`, `Offset`, `Size`, `Modifier`, `collectAsState` and `dp` are already imported.)

- [ ] **Step 2: Push the setting to the controller**

In `RecordViewModel`'s settings collector, next to the existing `controller.debugLogging` line (currently line 293):

```kotlin
                controller.debugLogging = s.debugLogging
                controller.zebraEnabled = s.zebraEnabled
```

- [ ] **Step 3: Expose the mask from the ViewModel**

Add to `RecordViewModel`, directly below the `val controller = CameraController(application)` line:

```kotlin
    /** Straight pass-through of the controller's mask -- deliberately NOT folded into
     * RecordUiState: it updates ~15x/second, and RecordUiState drives the whole
     * screen's recomposition. See RecordScreen's ZebraOverlay for how it is read. */
    val zebraMask: StateFlow<ZebraMask?> get() = controller.zebraMask
```

- [ ] **Step 4: Re-key the preview on the setting**

Toggling zebra changes the session's output list, so the session must be rebuilt. Reuse the proven lens/resolution path — recreating the `SurfaceView` drives `surfaceCreated` -> `openCamera` -> `openAndPreview`. Change the `key(...)` around the `AndroidView` (currently line 1320) from:

```kotlin
            key(state.lensIndex, state.sizeIndex) {
```

to:

```kotlin
            // zebraEnabled joins the key because it changes the session's output
            // list: recreating the SurfaceView drives surfaceCreated -> openCamera ->
            // openAndPreview, which rebuilds the session with (or without) the
            // analysis stream. Same proven path as a lens or resolution switch. Safe
            // to do unconditionally because the Settings screen is disabled while
            // recording, so this key cannot change mid-take.
            key(state.lensIndex, state.sizeIndex, state.settings.zebraEnabled) {
```

- [ ] **Step 5: Compose the overlay**

Directly after the rule-of-thirds grid block and before the `HorizonLevel` block (currently line 1358):

```kotlin
            // Zebra stripes over clipped highlights. Same paint-layer-only reasoning
            // as the grid above: no pointerInput of its own, so tap-to-meter is
            // unaffected.
            if (state.settings.zebraEnabled) {
                ZebraOverlay(viewModel.zebraMask, Modifier.fillMaxSize())
            }
```

- [ ] **Step 6: Add the overlay composable**

Add this near `HorizonLevel` at the bottom of `RecordScreen.kt`:

```kotlin
/**
 * Animated diagonal stripes over the cells [CameraController.zebraMask] flagged as
 * clipping.
 *
 * Both the mask and the animation phase are read INSIDE the Canvas draw lambda, via
 * State objects that are never destructured in the composable body. That is the whole
 * point of the shape: a new mask ~15x/second (and a new phase every frame) invalidates
 * the draw phase only. Hoisting either read with `by` would recompose this composable
 * at that rate instead, and folding the mask into RecordUiState would recompose the
 * entire screen.
 */
@Composable
private fun ZebraOverlay(maskFlow: StateFlow<ZebraMask?>, modifier: Modifier = Modifier) {
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
        if (m.cols <= 0 || m.rows <= 0 || m.runs.isEmpty()) return@Canvas
        val period = 14.dp.toPx()
        val shift = phase.value * period
        // Hard stops at the midpoint make a stripe, not a gradient; a diagonal
        // start->end vector plus TileMode.Repeated tiles it across the whole layer, so
        // stripes stay continuous from one cell run to the next instead of restarting
        // per rect.
        val stripes = Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.85f),
            0.5f to Color.White.copy(alpha = 0.85f),
            0.5f to Color.Transparent,
            1.0f to Color.Transparent,
            start = Offset(shift, shift),
            end = Offset(shift + period, shift + period),
            tileMode = TileMode.Repeated,
        )
        val cw = size.width / m.cols
        val ch = size.height / m.rows
        m.runs.forEach { run ->
            drawRect(
                brush = stripes,
                topLeft = Offset(run.startCol * cw, run.row * ch),
                size = Size((run.endColExclusive - run.startCol) * cw, ch),
            )
        }
    }
}
```

- [ ] **Step 7: Verify both build variants and the full suite**

Run:
```
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```
Expected: both variants BUILD SUCCESSFUL (release runs R8); 64 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: animated zebra stripe overlay over clipped highlights"
```

---

### Task 6: On-device verification

**Files:**
- Modify: `docs/superpowers/specs/2026-07-26-zebra-exposure-warning-design.md` (status line)
- Create: `docs/superpowers/open-items-2026-07-28-zebra.md` (only if something fails)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: a verification record. No code interface.

This project's standing convention is that a feature is not done until it is verified on real hardware, and its hardest-won lesson is that **a visual fix must never be reported as done off a green build alone** — screenshot and confirm. That applies directly here.

No device was connected when this plan was written (`adb devices` was empty). If none is available at execution time, stop after Task 5, mark this task as an owed item, and say so plainly rather than reporting the feature complete.

- [ ] **Step 1: Install on a real device**

Run (PowerShell; `adb` is not on PATH — use the SDK copy):
```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\gradlew.bat :app:installDebug
```
Expected: exactly one device listed; install succeeds. If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a differently-signed build is already installed — uninstall first. To preserve existing clips instead, build `assembleRelease` with the same keystore and `adb install -r`.

- [ ] **Step 2: Verify the toggle persists**

Open Settings -> VIEWFINDER, turn "Zebras" on, force-stop the app, relaunch, reopen Settings.
Expected: the toggle is still on.

If a tap on the row does not register, do **not** re-estimate coordinates from a scaled screenshot — get exact bounds with `adb shell uiautomator dump`, the documented fix for this project's silent-tap failures.

- [ ] **Step 3: Verify stripes track genuinely blown highlights**

Point the camera at a bright window or a lamp and raise ISO/shutter until part of the frame is clearly blown.
Expected: animated diagonal stripes appear over the blown region and nowhere else, and they disappear when exposure is pulled down.

Take a screenshot and confirm visually — a green build proves nothing here.

- [ ] **Step 4: Verify mask alignment (orientation)**

Fill only **one corner** of the frame with a blown highlight.
Expected: stripes appear in **that same corner**.

If they appear mirrored, transposed, or 180°-rotated, the analysis buffer's orientation does not match the preview on this device. That is a real possibility: the app is locked to `android:screenOrientation="landscape"` and `sensorOrientation` is captured but never applied anywhere in production code (it is reserved for the unwritten Spec B). The fix is a coordinate transform when mapping runs to the Canvas rect, chosen from the device's `sensorOrientation`. Do not guess it — determine it from what the corner test actually shows, then re-run this step.

- [ ] **Step 5: Verify it survives a lens switch**

With zebra on, switch lenses via the LENS panel, then switch back.
Expected: preview stays live, stripes still track highlights on each lens, no crash. On a multi-lens device this also exercises `ensureZebraSurface`'s rebuild-on-size-change path.

- [ ] **Step 6: Verify toggling mid-preview doesn't destabilize the camera**

With the viewfinder up, toggle "Zebras" off and on several times, returning to Record each time.
Expected: preview recovers every time, no black frames left behind, no "Camera open failed" toast.

- [ ] **Step 7: Verify recording is not degraded**

Record a clip of at least 30 seconds with zebra ON, at the device's full resolution and frame rate.
Expected: the completion toast reports **0 dropped**. Compare against a same-length clip with zebra OFF.

This is the one result that can veto the feature's default behaviour: if the third stream costs dropped frames, the honest response is to report it and discuss restricting zebra to idle preview, not to ship it and hope.

- [ ] **Step 8: Check the crash buffer**

Run:
```
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -b crash -d
```
Expected: no `FATAL EXCEPTION` from `com.shez.rawcam`.

- [ ] **Step 9: Record the outcome**

Update the spec's status line at `docs/superpowers/specs/2026-07-26-zebra-exposure-warning-design.md:4` from `Design approved, pending spec review → implementation plan` to `Implemented and device-verified <date>` (or, if a step failed, to a one-line statement of exactly what is outstanding, with the detail written into `docs/superpowers/open-items-2026-07-28-zebra.md`).

Restore `screen_off_timeout` to `60000` if it was raised during testing.

- [ ] **Step 10: Commit**

```bash
git add docs/superpowers/specs/2026-07-26-zebra-exposure-warning-design.md
git commit -m "docs: zebra exposure warning device-verified"
```

---

## Self-Review

**Spec coverage.** Goal → Tasks 4-5. Architecture (third optional YUV `ImageReader`, both session types, Y-plane as luminance) → Task 4. Size selection (smallest, nearest aspect, nothing hardcoded) → Task 2. Fixed `Y == 255` threshold → Task 1. Active in idle preview and recording → Task 4 Step 6 targets both repeating requests. Toggle recreates the session → Task 5 Step 4. Animated diagonal stripes → Task 5 Step 6. Graceful degradation → Task 2 (`pickAnalysisSize` returns null) + Task 4 (`ensureZebraSurface` returns null, session built without it). Default off → Task 3. Components (`CameraController`, pure threshold function, `RecordScreen` Canvas, `SettingsRepository`/`SettingsScreen`) → Tasks 4, 1, 5, 3. Error handling (no usable size; malformed plane logged and skipped; toggle-recreate path) → Task 4 Steps 4-5 and Task 1's never-throws contract. Testing (host tests for the threshold, plus the on-device gate) → Tasks 1-2 and Task 6. Out-of-scope items (threshold slider, capture/export changes, GPU) are absent from every task, and the constraint that `core/`+`cpp/` stay untouched is enforced by Task 4 Step 9.

The spec's data-flow line placed the mask in `RecordUiState`; this plan deviates deliberately and says why, both at the top and in the `ZebraOverlay` kdoc.

**Placeholders.** None: every code step carries the real code, every run step carries the exact command and expected result, and Task 6's contingencies name the concrete next action rather than "handle appropriately".

**Type consistency.** `ZebraMask(cols, rows, runs)` and `ZebraMask.CellRun(row, startCol, endColExclusive)` are defined in Task 1 and used with those exact names in Tasks 4 and 5. `ZebraAnalysis.threshold` keeps one signature across Tasks 1, 2 and 4. `pickAnalysisSize(candidates, targetAspect)` returns `SizeSpec?`, the existing type from `CameraSnapshot.kt:8`, constructed in Task 4 as `SizeSpec(it.width, it.height)` against its `minFrameDurationNs = 0` default. `zebraEnabled` names the same thing on `Settings` (Task 3) and `CameraController` (Task 4). `zebraMask` is `StateFlow<ZebraMask?>` on the controller (Task 4), the ViewModel and `ZebraOverlay`'s parameter (Task 5). `releaseZebra()` and `ensureZebraSurface()` are each defined once and called under those names only.
