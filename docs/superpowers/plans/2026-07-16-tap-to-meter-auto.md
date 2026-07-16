# Tap-to-Meter Auto Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap a point on the live preview to auto-meter the best ISO, shutter, white balance (Kelvin), tint, and focus for that spot using the phone's hardware 3A, then hold those values as locked manual settings.

**Architecture:** Three layers mirroring the existing manual-WB feature. `CameraController` briefly flips the preview repeating request to auto (AE/AF/AWB) with a metering rectangle at the tapped point, waits for convergence, reads the converged result, inverts the AWB gains to Kelvin/tint, restores full manual, and returns the values. `RecordViewModel` applies them through the existing per-parameter setters (which snap and push). `RecordScreen` adds a tap gesture on the preview plus a transient reticle.

**Tech Stack:** Kotlin, Camera2 (`CameraCaptureSession`, `MeteringRectangle`, capture-result 3A state fields), Jetpack Compose (`pointerInput`/`detectTapGestures`). No C++/native changes — builds via `./gradlew assembleDebug`.

## Global Constraints

- One-shot only: converge once, then fully manual again. No continuous-auto mode.
- Tap-to-meter is disabled while recording (`recording == true`) and until `previewReady`.
- Metered values land in the existing `RecordUiState` manual fields and existing chips; manual == locked. No separate persisted lock state.
- Kelvin snaps to nearest `KELVIN_STOPS`; tint to nearest `TINT_STOPS`; shutter to nearest `shutterStops(fps)` index; ISO to nearest `isoStops`; focus to nearest `focusStops(...)`.
- Convergence timeout: 1500 ms via `SystemClock.elapsedRealtime()`. On timeout with a usable AE state, apply best-so-far; otherwise abort, restore manual with prior values, emit "Couldn't meter — try again".
- The meter sequence runs on the camera `HandlerThread`, serializes with `updateManual`/`startRecording`, and MUST restore manual (`applyManual`) on every exit path — success, timeout, failure, or exception. It must never leave the pipeline in auto or crash the session.
- Project convention: **no Kotlin/instrumented test harness exists**. Verification is `./gradlew assembleDebug` success + hand-computed math checks + on-device manual verification (identical to the manual-WB plan's convention). Do not introduce a test framework; the pure-math inversion is verified by hand-computed round-trip in the task.

---

## File Structure

- `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` — add `MeteredValues` data class, `gainsToKelvinTint()` (inverse of `gainsFor`), cache active-array size at open, add `meterAt()`.
- `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — add `metering`/`meterPoint` to `RecordUiState`, add `RecordViewModel.meterAt()` + snapping helpers, add the preview tap gesture and reticle overlay.

No new files (follows the established two-file structure of this feature area).

---

### Task 1: `gainsToKelvinTint` inversion + `MeteredValues`

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (add data class + function near `gainsFor`, ~line 544–552)

**Interfaces:**
- Consumes (existing): `gainsFor(kelvin: Int, tint: Int): RggbChannelVector` (private), the Kelvin candidate set `{2000,2700,3200,4000,5000,5600,6500,7500,9000,10000}` and tint set `-50..50 step 5` (these mirror `KELVIN_STOPS`/`TINT_STOPS` in `RecordScreen.kt:463-464`).
- Produces (used by Task 2 and Task 3): `data class MeteredValues(iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int)`; `fun gainsToKelvinTint(gains: RggbChannelVector): Pair<Int, Int>`.

- [ ] **Step 1: Add the data class and candidate constants**

At the top of the `CameraController` class body (near the other private constants), add:

```kotlin
data class MeteredValues(
    val iso: Int,
    val exposureNs: Long,
    val focusDiopters: Float,
    val kelvin: Int,
    val tint: Int,
)
```

In the `companion object` (alongside `TAG`), add the candidate lists (documented as mirroring the UI stop lists so a reviewer can cross-check):

```kotlin
// Mirror of RecordScreen.KELVIN_STOPS / TINT_STOPS. gainsToKelvinTint returns
// values from these sets so the metered result lands exactly on a slider tick.
private val KELVIN_CANDIDATES = intArrayOf(2000, 2700, 3200, 4000, 5000, 5600, 6500, 7500, 9000, 10000)
private val TINT_CANDIDATES = (-50..50 step 5).toList()
```

- [ ] **Step 2: Add `gainsToKelvinTint` (inverse of `gainsFor`)**

Directly after `gainsFor(...)` add. Requires `import kotlin.math.ln`, `import kotlin.math.abs`, `import kotlin.math.roundToInt` (add any missing):

```kotlin
/**
 * Inverse of gainsFor: map measured AWB per-channel gains back to the nearest
 * (kelvin, tint) representable by the manual controls. gainsFor maps kelvin to
 * a neutralizing red/blue gain pair (their ratio is monotonic in kelvin) with
 * green carrying the tint as tintFactor = (1 - tint/100). We pick the kelvin
 * candidate whose gainsFor(k, 0) red/blue ratio best matches the measured one,
 * then recover tint from the measured green gain relative to the red/blue
 * average (the neutral reference), snapped to the nearest tint candidate.
 */
fun gainsToKelvinTint(gains: RggbChannelVector): Pair<Int, Int> {
    val gR = gains.red.coerceAtLeast(1e-3f)
    val gG = ((gains.greenEven + gains.greenOdd) / 2f).coerceAtLeast(1e-3f)
    val gB = gains.blue.coerceAtLeast(1e-3f)
    val targetLogRatio = ln(gR / gB)
    var bestK = KELVIN_CANDIDATES.first()
    var bestErr = Float.MAX_VALUE
    for (k in KELVIN_CANDIDATES) {
        val g = gainsFor(k, 0)
        val err = abs(ln(g.red / g.blue) - targetLogRatio)
        if (err < bestErr) { bestErr = err; bestK = k }
    }
    val refGreen = (gR + gB) / 2f
    val tintFactor = (gG / refGreen).coerceIn(0.3f, 2f)
    val rawTint = ((1f - tintFactor) * 100f).roundToInt()
    val bestT = TINT_CANDIDATES.minByOrNull { abs(it - rawTint) } ?: 0
    return bestK to bestT
}
```

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL` (nothing calls `meterAt` yet; the new function/type are unused but compile).

- [ ] **Step 4: Hand-computed round-trip check (no Kotlin test harness exists — this is the verification)**

For at least these pairs, confirm on paper that `gainsToKelvinTint(gainsFor(k, t))` recovers `(k, t)`:
- `(5600, 0)` → red/blue ratio from `kelvinRgb(5600)`; the search must land on `5600`, and `tintFactor == 1.0` → tint `0`.
- `(3200, 0)` → warmer; red gain > blue gain → ratio matches the `3200` candidate.
- `(6500, +10)` → tint factor `0.9` → `rawTint ≈ 10` → nearest candidate `10`.
- `(6500, -20)` → tint factor `1.2` → `rawTint ≈ -20` → candidate `-20`.
Document the four computations in the task report. This is the same "verify the math by hand" bar the manual-WB tasks used.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m "feat: gainsToKelvinTint inversion + MeteredValues for tap-to-meter"
```

---

### Task 2: `CameraController.meterAt` — auto-converge, read back, restore manual

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (cache active-array size in the open/enumerate path; add `meterAt` near `updateManual`, ~line 241)

**Interfaces:**
- Consumes (existing): `@Volatile device`, `session`, `previewSurface`, `recording`; `cameraHandler`; `applyManual(builder, withFrameDuration)`; `gainsToKelvinTint(...)` and `MeteredValues` (Task 1); `cameraManager.getCameraCharacteristics(id)`; the active lens id; the manual fields `iso`, `exposureNs`, `focusDiopters`, `kelvin`, `tint`.
- Produces (used by Task 3): `fun meterAt(nx: Float, ny: Float, onResult: (MeteredValues?) -> Unit)`.

- [ ] **Step 1: Cache the active array size for the active lens**

Where the controller records the active lens characteristics at open (the enumerate/open path that already reads `isoRange` etc.), also store:

```kotlin
@Volatile private var activeArraySize: android.graphics.Rect? = null
```
Set it when the lens is selected/opened, from the `characteristics` already fetched for the active lens (do NOT add another `getCameraCharacteristics` IPC on a hot path):
```kotlin
activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
```

- [ ] **Step 2: Add `meterAt` and its helpers**

Add after `updateManual(...)`. Runs on the camera handler; restores manual on every path.

```kotlin
/**
 * One-shot hardware-3A meter at a normalized preview point (nx, ny in 0f..1f,
 * top-left origin, landscape preview orientation). Flips the preview to auto
 * with a metering region at the point, waits up to ~1500ms for AE/AF/AWB to
 * settle, reads back the values, restores full manual, and posts the result
 * (null on not-ready / recording / failure) to [onResult] on the camera thread.
 */
fun meterAt(nx: Float, ny: Float, onResult: (MeteredValues?) -> Unit) {
    cameraHandler.post {
        val dev = device
        val s = session
        val preview = previewSurface
        val arr = activeArraySize
        if (recording || dev == null || s == null || preview == null || arr == null) {
            onResult(null); return@post
        }
        try {
            val region = meteringRectFor(nx, ny, arr)
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }.build()

            val deadline = SystemClock.elapsedRealtime() + 1500L
            val done = java.util.concurrent.CountDownLatch(1)
            @Volatile var last: TotalCaptureResult? = null
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    last = result
                    if (settled(result) || SystemClock.elapsedRealtime() >= deadline) {
                        done.countDown()
                    }
                }
            }
            s.setRepeatingRequest(req, cb, cameraHandler)
            val trigger = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }.build()
            s.capture(trigger, null, cameraHandler)

            done.await(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
            val result = last
            val out = if (result != null && usableAe(result)) readMetered(result) else null
            restoreManualPreview()   // always restore before returning
            onResult(out)
        } catch (e: Exception) {
            Log.e(TAG, "meterAt failed", e)
            restoreManualPreview()
            onResult(null)
        }
    }
}

private fun settled(r: TotalCaptureResult): Boolean {
    val ae = r.get(CaptureResult.CONTROL_AE_STATE)
    val af = r.get(CaptureResult.CONTROL_AF_STATE)
    val awb = r.get(CaptureResult.CONTROL_AWB_STATE)
    val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
    val afOk = af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
        af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
        af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
    val awbOk = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
        awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
    return aeOk && afOk && awbOk
}

private fun usableAe(r: TotalCaptureResult): Boolean {
    val ae = r.get(CaptureResult.CONTROL_AE_STATE)
    return ae == null || ae != CaptureResult.CONTROL_AE_STATE_INACTIVE
}

private fun readMetered(r: TotalCaptureResult): MeteredValues {
    val isoOut = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso
    val expOut = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
    val focusOut = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
    val gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
    val (k, t) = if (gains != null) gainsToKelvinTint(gains) else (kelvin to tint)
    return MeteredValues(isoOut, expOut, focusOut, k, t)
}

/** Re-arm the manual preview repeating request with the current manual fields. */
private fun restoreManualPreview() {
    val s = session ?: return
    val dev = device ?: return
    val preview = previewSurface ?: return
    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(preview)
        applyManual(this, withFrameDuration = false)
    }.build()
    s.setRepeatingRequest(req, null, cameraHandler)
}

private fun meteringRectFor(nx: Float, ny: Float, arr: android.graphics.Rect): MeteringRectangle {
    // Preview is locked landscape and fills the active array; map normalized
    // (nx, ny) directly into active-array pixels. A ~10% box is the metered area.
    val cx = (arr.left + nx.coerceIn(0f, 1f) * arr.width()).toInt()
    val cy = (arr.top + ny.coerceIn(0f, 1f) * arr.height()).toInt()
    val halfW = (arr.width() * 0.05f).toInt().coerceAtLeast(1)
    val halfH = (arr.height() * 0.05f).toInt().coerceAtLeast(1)
    val left = (cx - halfW).coerceIn(arr.left, arr.right - 1)
    val top = (cy - halfH).coerceIn(arr.top, arr.bottom - 1)
    val right = (cx + halfW).coerceIn(left + 1, arr.right)
    val bottom = (cy + halfH).coerceIn(top + 1, arr.bottom)
    return MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
}
```

Add imports as needed: `android.hardware.camera2.params.MeteringRectangle`, `android.os.SystemClock`, `android.hardware.camera2.TotalCaptureResult`, `android.hardware.camera2.CaptureResult`, `android.hardware.camera2.CameraMetadata`, `android.hardware.camera2.CameraCharacteristics`.

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: On-device smoke (metering path only)**

Install (`adb install -r`), open; using Task 3's gesture (do Tasks 2+3 in sequence, verify together at Task 3), confirm no crash when metering runs and that the preview returns to its manual look afterward (no lingering auto-exposure drift). Full behavioral verification is in Task 3.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m "feat: CameraController.meterAt one-shot 3A meter with manual restore"
```

---

### Task 3: ViewModel apply + preview tap gesture + reticle

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (`RecordUiState`, `RecordViewModel.meterAt` + snap helpers, preview `pointerInput` + reticle)

**Interfaces:**
- Consumes: `CameraController.meterAt(nx, ny) { MeteredValues? }` and `MeteredValues` (Task 2); existing setters `setIso`, `setShutterIndex`, `setFocus`, `setKelvin`, `setTint` (`RecordScreen.kt:225-248`); stop lists `isoStops`, `shutterStops(fps)`, `focusStops(...)`; existing `_events` `MutableSharedFlow<String>`; `cameraOps` scope; `viewModelScope`.
- Produces: `RecordUiState.metering: Boolean`, `RecordUiState.meterPoint: Offset?`; `RecordViewModel.meterAt(nx: Float, ny: Float)`.

- [ ] **Step 1: Extend `RecordUiState`**

After `val tint: Int = 0,` add:
```kotlin
    val metering: Boolean = false,
    val meterPoint: androidx.compose.ui.geometry.Offset? = null,
```

- [ ] **Step 2: Add `meterAt` + snap helpers to `RecordViewModel`**

After `setTint(...)` add. Snap each metered value onto the existing discrete stops so the chips show clean values:

```kotlin
fun meterAt(nx: Float, ny: Float) {
    val s = _uiState.value
    if (s.recording || s.metering || !s.previewReady) return
    _uiState.update { it.copy(metering = true, meterPoint = Offset(nx, ny)) }
    cameraOps.launch {
        controller.meterAt(nx, ny) { m ->
            viewModelScope.launch {
                if (m != null) {
                    setIso(nearestIso(m.iso))
                    setShutterIndex(nearestShutterIndex(m.exposureNs))
                    setFocus(m.focusDiopters)
                    setKelvin(m.kelvin)
                    setTint(m.tint)
                } else {
                    _events.tryEmit("Couldn't meter — try again")
                }
                _uiState.update { it.copy(metering = false) }
                delay(600)   // leave the reticle briefly, then clear it
                _uiState.update { it.copy(meterPoint = null) }
            }
        }
    }
}

private fun nearestIso(iso: Int): Int =
    isoStops.minByOrNull { kotlin.math.abs(it - iso) } ?: iso

private fun nearestShutterIndex(exposureNs: Long): Int {
    val stops = shutterStops(_uiState.value.fps)      // denominators, e.g. 48 => 1/48s
    val target = if (exposureNs > 0) (1_000_000_000.0 / exposureNs) else stops.first().toDouble()
    var best = 0
    var bestErr = Double.MAX_VALUE
    stops.forEachIndexed { i, denom ->
        val err = kotlin.math.abs(denom - target)
        if (err < bestErr) { bestErr = err; best = i }
    }
    return best
}
```
Add imports: `androidx.compose.ui.geometry.Offset`, `kotlinx.coroutines.delay` (if not already present).

Note on focus: `setFocus` takes raw diopters and the focus slider already snaps its display to `focusStops`, so passing `m.focusDiopters` raw is acceptable. If the reviewer prefers focus snapped too, replace with `setFocus(focusStops(controller.minFocusDiopters).minByOrNull { kotlin.math.abs(it - m.focusDiopters) } ?: m.focusDiopters)`.

- [ ] **Step 3: Add the tap gesture + reticle to the preview**

On the preview container `Box` that hosts the `SurfaceView` (the `pointerInput` lambda's `size` gives its pixel dimensions), add:

```kotlin
.pointerInput(state.previewReady, state.recording) {
    detectTapGestures { offset ->
        if (state.previewReady && !state.recording) {
            viewModel.meterAt(offset.x / size.width.toFloat(), offset.y / size.height.toFloat())
        }
    }
}
```
Overlay the reticle as a `Canvas` sibling in the same `Box`, drawn only when `state.meterPoint != null`:

```kotlin
state.meterPoint?.let { p ->
    Canvas(Modifier.fillMaxSize()) {
        val cx = p.x * size.width
        val cy = p.y * size.height
        val r = 36.dp.toPx()
        val c = if (state.metering) Color(0xFFE0E0E0) else Color(0xFF7CFF7C)
        drawRect(c, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(width = 3.dp.toPx()))
    }
}
```
Add imports: `androidx.compose.foundation.Canvas`, `androidx.compose.foundation.gestures.detectTapGestures`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.graphics.drawscope.Stroke`, `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.graphics.Color`.

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: On-device verification (Pixel over USB)**

- Tap a bright area, then a dark area → ISO/shutter chips change sensibly (dark → higher ISO / longer shutter).
- Tap a near object, then a far object → focus chip (distance) changes.
- Tap under warm vs cool light → Kelvin chip changes toward the light.
- After each tap, values land in the chips and **hold** (no drift); recording still works and produces a clip afterward.
- While recording, tapping does nothing (metering disabled).
- Tap a featureless wall / cover the lens to force a weak converge → either sensible values or the "Couldn't meter — try again" toast with prior values intact; no crash, preview stays live.
- Reticle appears at the tapped point (grey while metering, green briefly after) and clears.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: preview tap-to-meter gesture, reticle, and value apply"
```

---

## Self-Review

**Spec coverage:** Goal (tap → meter → snap into manual → lock) → Tasks 2+3. One-shot + not-during-recording → Task 2 guard + Task 3 gesture guard. Gains→Kelvin/tint inversion → Task 1. Snap to existing stops → Task 3 helpers + Task 1 candidate sets. Metering region at tap point + full-frame-ish fallback → `meteringRectFor` (coerces out-of-range input to a valid rect). Timeout/error handling + always-restore-manual → Task 2. Reticle/UI → Task 3. Host round-trip test intent → Task 1 Step 4 (hand-computed, per project convention). All spec sections map to a task.

**Placeholder scan:** No "TBD"/"add error handling"-style placeholders; every code step carries complete code. The focus raw-vs-snapped choice is a stated, defaulted decision, not a gap.

**Type consistency:** `MeteredValues(iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int)` and `meterAt(nx, ny, onResult)` are used identically across Tasks 2 and 3. `metering`/`meterPoint` names match between `RecordUiState`, the ViewModel, and the UI. Setter names match the verified current signatures in `RecordScreen.kt:225-248`.

**Cross-check for the executor (not a placeholder — confirm against the open files):** the exact declared forms of `isoStops`, `shutterStops(fps)`, and `focusStops(...)` (val vs fun, element type) and `controller.minFocusDiopters` if the snapped-focus option is taken, plus the exact preview `Box` to attach the gesture to. The snap helpers are written against their documented shapes (`isoStops` = list of Int ISO values; `shutterStops(fps)` = list of Int denominators); adjust element access if the declarations differ.
