# Manual White Balance & Slider UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fully manual white balance (Kelvin + tint) and fix the two concrete slider complaints (no scale reference, hard to land on exact values) across ISO/shutter/focus/the new WB controls.

**Architecture:** `CameraController.applyManual` gains Kelvin/tint → `COLOR_CORRECTION_GAINS`/`COLOR_CORRECTION_TRANSFORM` (processed-stream/metadata only — RAW pixel data is untouched), replacing the hardcoded AWB-auto lines. The ViewModel carries `kelvin`/`tint` state exactly like `iso`/`focusDiopters`. The UI gets one new shared `TickedSlider` component (endpoint labels + discrete snapping) used everywhere a bare `Slider` was used before, plus a 6th "WB" chip, plus a horizontally scrollable chip row so 6 chips fit.

**Tech Stack:** Kotlin, Camera2 manual color correction (`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, `RggbChannelVector`, `ColorSpaceTransform` — all API 21+, well within this app's minSdk 33), Jetpack Compose `Slider` with `steps`.

**Spec:** `docs/superpowers/specs/2026-07-16-manual-wb-slider-ux-design.md`

## Global Constraints

- No native core, container format, or exporter changes — this entire feature is Kotlin-side (`CameraController` + `RecordScreen.kt`).
- WB is always-adjustable, never locked by the recording state — matches ISO/shutter/focus, unlike LENS/RES/FPS which stay locked while `recording || busy`.
- Default state: 5600K, tint 0 (occupies the slot AWB-auto used to).
- `COLOR_CORRECTION_GAINS`/`COLOR_CORRECTION_TRANSFORM` affect only processed streams and capture metadata, never `RAW_SENSOR` — no risk to raw pixel fidelity.
- Build: `.\gradlew assembleDebug --console=plain` from `C:\Users\User\rawcam` in PowerShell 5.1 (no `&&`; chain with `;`). No Kotlin unit/instrumented tests exist in this project (confirmed: `app/src/test` and `app/src/androidTest` are both empty) — verification is build success + on-device checks, matching every prior plan in this codebase.
- Commit messages via single-quoted here-strings (`@'...'@`, closing `'@` at column 0); never put double quotes inside.
- Branch: work directly on `main` (small, low-risk, following the same pattern used after the last two features merged) unless told otherwise at execution time.

## Deviation from spec (documented, not silent)

The spec describes TINT as "continuous, integer resolution across −50..50, with major tick labels only at multiples of 10" — a continuous slider with a custom tick overlay independent of drag resolution. A true custom overlay would need to reach into Material3 `Slider`'s internal track-padding geometry (not part of its public API, fragile across Compose versions) for a purely cosmetic difference. Task 3 instead makes TINT discrete at every 5 units (21 stops, -50..50), reusing the exact same `TickedSlider` component as every other parameter: same visible tick marks, same snapping, same endpoint labels, no custom drawing code. This still fully satisfies both root complaints the spec exists to fix (scale reference, landing on exact values) and is more consistent with the rest of the UI, at the cost of slightly coarser fine-tuning than a literal continuous slider would give.

---

### Task 1: CameraController — manual white balance

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: nothing new (Camera2 framework only).
- Produces (used by Task 2):
  - `fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int)` (signature change — two new trailing params)
  - `fun startRecording(path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int): Boolean` (signature change — two new trailing params)

- [ ] **Step 1: Add imports**

Add to the existing import block in `CameraController.kt`:

```kotlin
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow
```

- [ ] **Step 2: Add kelvin/tint fields**

In the field block alongside the other manual-value fields, directly below `@Volatile private var focusDiopters = 0f`, add:

```kotlin
    @Volatile private var kelvin = 5600
    @Volatile private var tint = 0
```

- [ ] **Step 3: Add the Kelvin→RGB and gains helper functions**

Add these private functions in the `// --- internals ---` section, directly above `private fun applyManual`:

```kotlin
    /**
     * Approximate blackbody color temperature -> RGB (0..255), the well-known
     * Tanner Helland curve fit (public domain, valid 1000K-40000K; our UI
     * range is 2000K-10000K, safely inside it). This is "the color of light
     * at this temperature" -- gainsFor inverts it to get the gains that
     * cancel that cast.
     */
    private fun kelvinRgb(kelvinValue: Int): Triple<Float, Float, Float> {
        val temp = kelvinValue / 100.0
        val red = if (temp <= 66.0) 255.0
        else (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val green = if (temp <= 66.0) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }
        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }
        return Triple(red.toFloat(), green.toFloat(), blue.toFloat())
    }

    /**
     * Kelvin/tint -> per-channel gains that cancel the color temperature's
     * cast, normalized so green stays the reference channel (gain 1 before
     * tint). Tint biases green relative to red/blue: positive = more
     * magenta (green reduced), negative = more green (green raised).
     */
    private fun gainsFor(kelvinValue: Int, tintValue: Int): RggbChannelVector {
        val (r, g, b) = kelvinRgb(kelvinValue)
        val gRef = g.coerceAtLeast(1f)
        val gainR = gRef / r.coerceAtLeast(1f)
        val gainB = gRef / b.coerceAtLeast(1f)
        val tintFactor = (1.0 - tintValue / 100.0).toFloat().coerceIn(0.3f, 2f)
        val gainG = tintFactor
        return RggbChannelVector(gainR, gainG, gainG, gainB)
    }
```

- [ ] **Step 4: Add the identity color transform constant**

In the `companion object`, add alongside the existing constants:

```kotlin
        // Identity 3x3 (row-major rationals num/den): color correction here is
        // gains-only, no cross-channel matrix warp.
        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            )
        )
```

- [ ] **Step 5: Wire WB into applyManual**

Replace:

```kotlin
    private fun applyManual(b: CaptureRequest.Builder, withFrameDuration: Boolean) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        if (withFrameDuration) {
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / recordFps)
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
    }
```

with:

```kotlin
    private fun applyManual(b: CaptureRequest.Builder, withFrameDuration: Boolean) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        if (withFrameDuration) {
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / recordFps)
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsFor(kelvin, tint))
        b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
    }
```

- [ ] **Step 6: Remove the hardcoded AWB-auto lines**

`applyManual` now owns AWB mode. Remove the now-redundant/conflicting hardcoded line from `setRepeatingPreview`:

```kotlin
    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (manualSet) applyManual(this, withFrameDuration = false)
        }.build()
        s.setRepeatingRequest(req, null, cameraHandler)
    }
```

becomes:

```kotlin
    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            if (manualSet) applyManual(this, withFrameDuration = false)
        }.build()
        s.setRepeatingRequest(req, null, cameraHandler)
    }
```

And in `setRepeatingRecord`, remove the same hardcoded line:

```kotlin
    private fun setRepeatingRecord(s: CameraCaptureSession) {
        val dev = device ?: return
        val raw = rawSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface ?: return)
            addTarget(raw)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            applyManual(this, withFrameDuration = true)
        }.build()
        s.setRepeatingRequest(req, captureCallback, cameraHandler)
    }
```

becomes:

```kotlin
    private fun setRepeatingRecord(s: CameraCaptureSession) {
        val dev = device ?: return
        val raw = rawSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface ?: return)
            addTarget(raw)
            applyManual(this, withFrameDuration = true)
        }.build()
        s.setRepeatingRequest(req, captureCallback, cameraHandler)
    }
```

(Before `manualSet` first becomes `true`, the preview briefly runs on template defaults for AWB too — exactly the same pre-existing behavior AE/AF/sensor params already have during that same narrow window, nothing new here.)

- [ ] **Step 7: Update updateManual's signature**

Replace:

```kotlin
    /** Applies new manual values live, during preview or recording. */
    fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float) {
        this.iso = iso.coerceIn(rawSpec.isoRange)
        this.exposureNs = if (recording) clampExposure(exposureNs, recordFps) else exposureNs
        this.focusDiopters = focusDiopters
        manualSet = true
```

with:

```kotlin
    /** Applies new manual values live, during preview or recording. */
    fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int) {
        this.iso = iso.coerceIn(rawSpec.isoRange)
        this.exposureNs = if (recording) clampExposure(exposureNs, recordFps) else exposureNs
        this.focusDiopters = focusDiopters
        this.kelvin = kelvin
        this.tint = tint
        manualSet = true
```

- [ ] **Step 8: Update startRecording's signature**

Replace:

```kotlin
    fun startRecording(
        path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float,
    ): Boolean {
```

with:

```kotlin
    fun startRecording(
        path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float,
        kelvin: Int, tint: Int,
    ): Boolean {
```

And directly below `this.focusDiopters = focusDiopters` (inside `startRecording`, right before `manualSet = true`), add:

```kotlin
        this.kelvin = kelvin
        this.tint = tint
```

- [ ] **Step 9: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: build FAILS — `RecordScreen.kt` still calls the old 3-arg/5-arg signatures. This is expected; Task 2 fixes the call sites. Confirm the failure is exactly two "no value passed for parameter" errors in `RecordScreen.kt` (for `updateManual` and `startRecording`), not something else.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m @'
feat: manual white balance (Kelvin/tint) in CameraController

applyManual now sets AWB off with COLOR_CORRECTION_GAINS computed from a
Kelvin/tint pair (Tanner Helland CCT approximation, gains-only, identity
transform). Color correction only affects processed streams and capture
metadata -- RAW_SENSOR output and the existing per-frame WB metadata
capture are unaffected.
'@
```

---

### Task 2: RecordViewModel — kelvin/tint state

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (ViewModel + `RecordUiState`)

**Interfaces:**
- Consumes (Task 1): `controller.updateManual(iso, exposureNs, focusDiopters, kelvin, tint)`, `controller.startRecording(path, fps, iso, exposureNs, focusDiopters, kelvin, tint): Boolean`.
- Produces (used by Task 3):
  - `RecordUiState.kelvin: Int` (default 5600), `RecordUiState.tint: Int` (default 0)
  - `fun setKelvin(k: Int)`, `fun setTint(t: Int)`

- [ ] **Step 1: Extend RecordUiState**

In `data class RecordUiState`, after `val sizeIndex: Int = 0,` add:

```kotlin
    val kelvin: Int = 5600,
    val tint: Int = 0,
```

- [ ] **Step 2: Add setKelvin/setTint**

Directly after `fun setFocus(diopters: Float) { ... }`, add:

```kotlin
    fun setKelvin(k: Int) {
        _uiState.update { it.copy(kelvin = k) }
        pushManual()
    }

    fun setTint(t: Int) {
        _uiState.update { it.copy(tint = t) }
        pushManual()
    }
```

- [ ] **Step 3: Wire kelvin/tint into pushManual**

Replace:

```kotlin
    private fun pushManual() {
        val s = _uiState.value
        if (!s.previewReady) return
        controller.updateManual(s.iso, exposureNsFor(s), s.focusDiopters)
    }
```

with:

```kotlin
    private fun pushManual() {
        val s = _uiState.value
        if (!s.previewReady) return
        controller.updateManual(s.iso, exposureNsFor(s), s.focusDiopters, s.kelvin, s.tint)
    }
```

- [ ] **Step 4: Wire kelvin/tint into startRecordingInternal**

Replace:

```kotlin
                val ok = controller.startRecording(path, s.fps, s.iso, exposureNs, s.focusDiopters)
```

with:

```kotlin
                val ok = controller.startRecording(
                    path, s.fps, s.iso, exposureNs, s.focusDiopters, s.kelvin, s.tint,
                )
```

- [ ] **Step 5: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL` (all `CameraController` call sites are now satisfied; the UI in `RecordScreen` composable doesn't reference kelvin/tint yet, which is fine — Task 3 adds that).

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m @'
feat: kelvin/tint state in RecordViewModel

setKelvin/setTint follow the same shape as setIso/setFocus and push
through the same manual-update and start-recording paths.
'@
```

---

### Task 3: RecordScreen UI — TickedSlider, discrete stops, WB chip, scrollable chip row

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (composable section)

**Interfaces:**
- Consumes (Task 2): `state.kelvin`, `state.tint`, `viewModel.setKelvin/setTint`.
- Produces: UI only — new composables `TickedSlider<T>`, `ParamLabel`; new helpers `isoStops`, `focusStops`, `focusLabel`; new constants `NICE_ISO_STOPS`, `NICE_FOCUS_METERS`, `KELVIN_STOPS`, `TINT_STOPS`.

- [ ] **Step 1: Add the scrollable-row imports**

Add to the imports:

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
```

(`height` is not already imported in this file — only `fillMaxHeight`/`padding`/`size`/`width` are — and the new WB panel's `Spacer(Modifier.height(8.dp))` in Step 5 needs it.)

- [ ] **Step 2: Add stop-list constants and helpers**

Directly below the existing `remainingLabel` function, add:

```kotlin
private val NICE_ISO_STOPS =
    listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)

/** Standard full-stop ISO values within [range], with the range's true endpoints spliced in
 *  so the slider's ends always reach what the lens actually supports. */
private fun isoStops(range: ClosedRange<Int>): List<Int> =
    (NICE_ISO_STOPS.filter { it in range } + range.start + range.endInclusive)
        .distinct().sorted()

private val NICE_FOCUS_METERS = listOf(10f, 5f, 3f, 2f, 1f, 0.5f, 0.3f)

/** Friendly focus-distance stops (infinity first) converted to diopters, clamped so the
 *  macro-end stop is whatever the lens actually supports rather than a fixed distance. */
private fun focusStops(minFocusDiopters: Float): List<Float> {
    if (minFocusDiopters <= 0f) return listOf(0f)
    val within = NICE_FOCUS_METERS.map { 1f / it }.filter { it < minFocusDiopters }
    return (listOf(0f) + within + minFocusDiopters).distinct().sorted()
}

private fun focusLabel(diopters: Float): String {
    if (diopters <= 0f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) "%.0fm".format(meters) else "%.0fcm".format(meters * 100f)
}

private val KELVIN_STOPS = listOf(2000, 2700, 3200, 4000, 5000, 5600, 6500, 7500, 9000, 10000)
private val TINT_STOPS = (-50..50 step 5).toList()
```

- [ ] **Step 3: Extend the Param enum**

Replace `private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS }` with:

```kotlin
private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS, WB }
```

- [ ] **Step 4: Hoist the shutter denom, add WB variables**

In `RecordScreen`, replace:

```kotlin
    val shutterStops = viewModel.shutterStops(state.fps)
    val modeEnabled = !state.recording && !state.busy
```

with:

```kotlin
    val shutterStops = viewModel.shutterStops(state.fps)
    val shutterDenom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
    val modeEnabled = !state.recording && !state.busy
```

- [ ] **Step 5: Replace the expanded panel's title+content block**

Replace this whole block:

```kotlin
                expanded?.let { param ->
                    Surface(
                        color = Color(0xD90A0B0D), shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RawCamColors.Outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                when (param) {
                                    Param.LENS -> "LENS"; Param.RES -> "RESOLUTION"
                                    Param.ISO -> "ISO"; Param.SHUTTER -> "SHUTTER"; Param.FOCUS -> "FOCUS"
                                },
                                color = RawCamColors.Muted, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            )
                            when (param) {
                                Param.LENS -> OptionPills(
                                    labels = lenses.map { it.label },
                                    selectedIndex = state.lensIndex,
                                    enabled = modeEnabled,
                                    onSelect = { viewModel.setLens(it) },
                                )
                                Param.RES -> OptionPills(
                                    labels = sizes.map { it.label },
                                    selectedIndex = state.sizeIndex,
                                    enabled = modeEnabled,
                                    onSelect = { viewModel.setResolution(it) },
                                )
                                Param.ISO -> Slider(
                                    value = tFromIso(state.iso, spec.isoRange),
                                    onValueChange = { t -> viewModel.setIso(isoFromT(t, spec.isoRange)) },
                                )
                                Param.SHUTTER -> Slider(
                                    value = state.shutterIndex
                                        .coerceIn(0, (shutterStops.size - 1).coerceAtLeast(0)).toFloat(),
                                    onValueChange = { v -> viewModel.setShutterIndex(v.roundToInt()) },
                                    valueRange = 0f..(shutterStops.size - 1).coerceAtLeast(0).toFloat(),
                                    steps = (shutterStops.size - 2).coerceAtLeast(0),
                                )
                                Param.FOCUS -> {
                                    val maxFocus = spec.minFocusDiopters.coerceAtLeast(0.01f)
                                    Slider(
                                        value = state.focusDiopters.coerceIn(0f, maxFocus),
                                        onValueChange = { viewModel.setFocus(it) },
                                        valueRange = 0f..maxFocus,
                                        enabled = spec.minFocusDiopters > 0f,
                                    )
                                }
                            }
                        }
                    }
                }
```

with:

```kotlin
                expanded?.let { param ->
                    Surface(
                        color = Color(0xD90A0B0D), shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RawCamColors.Outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            when (param) {
                                Param.LENS -> {
                                    ParamLabel("LENS")
                                    OptionPills(
                                        labels = lenses.map { it.label },
                                        selectedIndex = state.lensIndex,
                                        enabled = modeEnabled,
                                        onSelect = { viewModel.setLens(it) },
                                    )
                                }
                                Param.RES -> {
                                    ParamLabel("RESOLUTION")
                                    OptionPills(
                                        labels = sizes.map { it.label },
                                        selectedIndex = state.sizeIndex,
                                        enabled = modeEnabled,
                                        onSelect = { viewModel.setResolution(it) },
                                    )
                                }
                                Param.ISO -> {
                                    ParamLabel("ISO")
                                    TickedSlider(
                                        stops = isoStops(spec.isoRange),
                                        selected = state.iso,
                                        labelFor = { "$it" },
                                        onSelect = { viewModel.setIso(it) },
                                    )
                                }
                                Param.SHUTTER -> {
                                    ParamLabel("SHUTTER")
                                    TickedSlider(
                                        stops = shutterStops,
                                        selected = shutterDenom,
                                        labelFor = { "1/$it" },
                                        onSelect = { viewModel.setShutterIndex(shutterStops.indexOf(it)) },
                                    )
                                }
                                Param.FOCUS -> {
                                    ParamLabel("FOCUS")
                                    TickedSlider(
                                        stops = focusStops(spec.minFocusDiopters),
                                        selected = state.focusDiopters,
                                        labelFor = ::focusLabel,
                                        enabled = spec.minFocusDiopters > 0f,
                                        onSelect = { viewModel.setFocus(it) },
                                    )
                                }
                                Param.WB -> {
                                    ParamLabel("WHITE BALANCE")
                                    TickedSlider(
                                        stops = KELVIN_STOPS,
                                        selected = state.kelvin,
                                        labelFor = { "${it}K" },
                                        onSelect = { viewModel.setKelvin(it) },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TickedSlider(
                                        stops = TINT_STOPS,
                                        selected = state.tint,
                                        labelFor = { if (it > 0) "+$it" else "$it" },
                                        onSelect = { viewModel.setTint(it) },
                                    )
                                }
                            }
                        }
                    }
                }
```

- [ ] **Step 6: Make the chip row scrollable and add the WB chip**

Replace:

```kotlin
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val denom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
                    val focusLabel =
                        if (state.focusDiopters <= 0f) "ƒ ∞" else "ƒ %.1fD".format(state.focusDiopters)
                    ParamChip(lens.label, expanded == Param.LENS, enabled = modeEnabled) {
                        expanded = if (expanded == Param.LENS) null else Param.LENS
                    }
                    ParamChip(size.label, expanded == Param.RES, enabled = modeEnabled) {
                        expanded = if (expanded == Param.RES) null else Param.RES
                    }
                    ParamChip("ISO ${state.iso}", expanded == Param.ISO) {
                        expanded = if (expanded == Param.ISO) null else Param.ISO
                    }
                    ParamChip("1/$denom", expanded == Param.SHUTTER) {
                        expanded = if (expanded == Param.SHUTTER) null else Param.SHUTTER
                    }
                    ParamChip(focusLabel, expanded == Param.FOCUS) {
                        expanded = if (expanded == Param.FOCUS) null else Param.FOCUS
                    }
                }
```

with:

```kotlin
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParamChip(lens.label, expanded == Param.LENS, enabled = modeEnabled) {
                        expanded = if (expanded == Param.LENS) null else Param.LENS
                    }
                    ParamChip(size.label, expanded == Param.RES, enabled = modeEnabled) {
                        expanded = if (expanded == Param.RES) null else Param.RES
                    }
                    ParamChip("ISO ${state.iso}", expanded == Param.ISO) {
                        expanded = if (expanded == Param.ISO) null else Param.ISO
                    }
                    ParamChip("1/$shutterDenom", expanded == Param.SHUTTER) {
                        expanded = if (expanded == Param.SHUTTER) null else Param.SHUTTER
                    }
                    ParamChip("ƒ ${focusLabel(state.focusDiopters)}", expanded == Param.FOCUS) {
                        expanded = if (expanded == Param.FOCUS) null else Param.FOCUS
                    }
                    ParamChip("${state.kelvin}K", expanded == Param.WB) {
                        expanded = if (expanded == Param.WB) null else Param.WB
                    }
                }
```

- [ ] **Step 7: Add ParamLabel and TickedSlider composables**

Below the existing `ParamChip` composable, add:

```kotlin
@Composable
private fun ParamLabel(text: String) {
    Text(text, color = RawCamColors.Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
}

/**
 * Discrete slider fixing the two concrete complaints about the old bare sliders:
 * endpoint labels give a scale reference, and snapping to [stops] makes it
 * possible to land on exact values. [stops] must have at least 2 entries.
 */
@Composable
private fun <T> TickedSlider(
    stops: List<T>, selected: T, labelFor: (T) -> String,
    enabled: Boolean = true, onSelect: (T) -> Unit,
) {
    val index = stops.indexOf(selected).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            labelFor(stops.first()), color = RawCamColors.Muted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { v -> onSelect(stops[v.roundToInt().coerceIn(0, stops.size - 1)]) },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            labelFor(stops.last()), color = RawCamColors.Muted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
```

- [ ] **Step 8: Remove the now-unused ISO log-scale helpers**

`tFromIso`/`isoFromT` are no longer called anywhere (ISO now uses `isoStops`/`TickedSlider`). Delete both functions:

```kotlin
/** ISO slider position (0..1) <-> value, log scale across [range]. */
private fun tFromIso(iso: Int, range: ClosedRange<Int>): Float {
    val lo = range.start.toDouble()
    val hi = range.endInclusive.toDouble()
    if (hi <= lo) return 0f
    val t = ln(iso.toDouble().coerceIn(lo, hi) / lo) / ln(hi / lo)
    return t.toFloat().coerceIn(0f, 1f)
}

private fun isoFromT(t: Float, range: ClosedRange<Int>): Int {
    val lo = range.start.toDouble()
    val hi = range.endInclusive.toDouble()
    if (hi <= lo) return range.start
    val iso = lo * (hi / lo).pow(t.toDouble())
    return iso.roundToInt().coerceIn(range.start, range.endInclusive)
}
```

Check afterward whether `kotlin.math.ln` and `kotlin.math.pow` are still used elsewhere in the file (they are not, once these two functions are gone) and remove those two now-unused imports too.

- [ ] **Step 9: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m @'
feat: TickedSlider component, WB chip, scrollable chip row

ISO/SHUTTER/FOCUS/KELVIN/TINT all move to the shared discrete TickedSlider
(endpoint labels + snapping to meaningful stops). WHITE BALANCE joins the
chip row as a 6th chip, which now scrolls horizontally to fit. FOCUS chip
switches from raw diopters to a friendly distance label.
'@
```

---

### Task 4: On-device verification

**Files:** none (verification only; fix-up commits as needed).

Preconditions: Pixel 7 Pro connected over wireless adb (`$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`; reconnect/toggle Wireless debugging if offline; disconnect any stale duplicate serial before installing). Keep test clips short given limited free space; delete them via the UI when done.

- [ ] **Step 1: Install and launch**

```powershell
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.shez.rawcam/.MainActivity
```

Screenshot: chip row now reads `1× · 4:3 · ISO · SHUTTER · ƒ ... · 5600K` (six chips, scrollable if it overflows the visible width).

- [ ] **Step 2: WB preview response**

Tap the `5600K` chip: panel shows two labeled sliders, KELVIN (endpoint labels `2000K` / `10000K`, tick marks) then TINT (endpoint labels `-50` / `+50`). Drag KELVIN toward 2000K and screenshot: preview should visibly warm (shift orange). Drag toward 10000K: preview should visibly cool (shift blue). Return to 5600K. Drag TINT to +50 and back to -50: screenshot each, confirming a visible green/magenta shift.

- [ ] **Step 3: ISO/FOCUS slider redesign**

Tap the ISO chip: confirm endpoint labels show the lens's true min/max ISO and dragging snaps to full-stop values (100/200/400/... ), not arbitrary numbers. Tap the FOCUS chip: confirm the chip itself now reads a distance (e.g. `ƒ ∞` or `ƒ 5m`) rather than raw diopters, and the slider's stops are the friendly distance list.

- [ ] **Step 4: Record at a non-default WB and export**

Set Kelvin to 3200K (tungsten-ish), tint to +10, record ~3s, stop. Confirm 0 dropped. Export the clip, pull one DNG, and check its white-balance-related metadata reflects the manual pick (not a neutral/daylight default) with a stdlib-only Python check or rawpy (`camera_whitebalance` should differ noticeably from the ultrawide/1×/tele clips recorded earlier this session, which were all at the 5600K default). Confirm the DNG still opens and debayers to a sane (non-garbage) image.

- [ ] **Step 5: Regression + cleanup**

- Confirm LENS/RES chips still lock during recording while the WB chip stays tappable and responsive mid-recording.
- Confirm switching lenses (0.6×/1×/5×) still works and each still previews correctly with the new chip row layout.
- Delete the test clip(s) created in this task via the Clips screen.
- Commit any fixes made during verification:

```powershell
git add -A
git commit -m @'
fix: device-verification fixes for manual WB and slider UX
'@
```

(Skip the commit if nothing changed.)

---

## Self-review notes

- Spec coverage: `applyManual`/gains/transform (T1 S3-S5), AWB-auto removal (T1 S6), `updateManual`/`startRecording` signatures (T1 S7-S8), ViewModel state + wiring (T2), `TickedSlider` + endpoint labels + snapping applied to ISO/SHUTTER/FOCUS/KELVIN/TINT (T3 S2, S5, S7), friendly focus distance label (T3 S2, S6), WB chip + scrollable row (T3 S6), on-device WB/preview/export verification (T4). The one deviation (TINT discrete-every-5 instead of continuous-with-custom-ticks) is called out explicitly above, not silently substituted.
- Placeholder scan: none found — every step has complete code.
- Type consistency: `TickedSlider<T>(stops: List<T>, selected: T, labelFor: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit)` used identically for `Int` (ISO/SHUTTER/KELVIN/TINT) and `Float` (FOCUS) call sites in T3 S5; `updateManual`/`startRecording` signatures match between T1 (definition) and T2 (call sites) exactly, including argument order.
