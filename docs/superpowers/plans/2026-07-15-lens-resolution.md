# Lens & Resolution Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick which back lens records (0.6×/1×/5×) and at what RAW resolution (4:3/16:9/LOW), with per-mode fps options up to 60.

**Architecture:** `CameraController` enumerates the logical back camera's physical lenses once at init into a `List<LensInfo>` and keeps the selected mode in `activePhysicalId` + a recomputed `rawSpec`; every session tags its `OutputConfiguration`s with the selected physical id. `selectMode` only updates state — the actual camera reopen rides the existing surface-recreation path: the ViewModel updates `lensIndex`/`sizeIndex` in `RecordUiState`, the Record screen keys its SurfaceView on that mode, so a mode change destroys/recreates the surface → `surfaceCreated` → `openCamera`, exactly like returning from background. Session-configure failure on a non-default mode reverts to main lens full-res via the same path.

**Tech Stack:** Kotlin, Camera2 multi-camera (`physicalCameraIds` + `OutputConfiguration.setPhysicalCameraId`, both API 28; minSdk is 33), Jetpack Compose. Native core, `.rawv` container, exporter: untouched.

**Spec:** `docs/superpowers/specs/2026-07-15-lens-resolution-design.md`

## Global Constraints

- No lens/resolution/fps changes while `recording || busy` — enforce in the ViewModel and disable the controls in the UI.
- Per-lens black level / color matrix / CFA / white level / ISO range flow into `rawSpec` so the `.rawv` header and DNGs stay color-correct per lens. `RawSpec` fields do not change.
- Native core untouched. All probed RAW widths/heights divide by 4 (Packed10 row math holds).
- Build: run `.\gradlew assembleDebug --console=plain` from `C:\Users\User\rawcam` in PowerShell 5.1 (no `&&`; chain with `;`).
- Commit messages via single-quoted here-strings (`@'...'@`, closing `'@` at column 0); never put double quotes inside.
- Branch: `ui-polish` (continue on it).
- Reuse `RawCamColors` theme constants; monospace for numeric labels.

## Device ground truth (Pixel 7 Pro, probed 2026-07-15)

Logical back camera "0", physical ids `[2 5 4 6 3]`, all RAW + MANUAL_SENSOR:

| Lens | Keep id | Dupe id | RAW sizes |
|---|---|---|---|
| Ultrawide 1.95 mm | 3 | — | 4032×3016, 4032×2272, 2016×1508 |
| Main 6.81 mm | 2 | 5 (fewer sizes) | 4080×3072, 4080×2288, 2032×1536 |
| Tele 19.0 mm | 4 | 6 (fewer sizes) | 4032×3024 |

Expected labels after FOV sort: `0.6×`, `1×`, `5×` (ultrawide first). Expected size labels: `4:3`, `16:9`, `LOW` (main/ultrawide), `4:3` only (tele).

---

### Task 1: CameraController — lens enumeration, mode state, physical-stream plumbing

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: nothing new (Camera2 framework only).
- Produces (used by Tasks 2–3):
  - `data class LensSize(width: Int, height: Int, maxFps: Int, label: String)`
  - `data class LensInfo(physicalId: String?, label: String, focalMm: Float, fovMetric: Float, sizes: List<LensSize>, cfa: Int, whiteLevel: Int, blackLevel: IntArray, colorMatrix1: FloatArray, isoRange: ClosedRange<Int>, minFocusDiopters: Float)`
  - `val lenses: List<LensInfo>` — FOV-sorted, widest first, deduped, labeled.
  - `val defaultLensIndex: Int` — index of the `1×` lens.
  - `var rawSpec: RawSpec` (public read, `private set`) — snapshot of the selected mode; same fields as today.
  - `fun selectMode(lensIndex: Int, sizeIndex: Int): Boolean` — updates mode state only; `false` while recording or on bad indices. Does NOT touch the session.
  - `fun openAndPreview(previewSurface: Surface, onFailed: () -> Unit = {}, onReady: () -> Unit)` — new middle parameter; existing trailing-lambda call sites still compile.

- [ ] **Step 1: Add imports**

After the existing imports in `CameraController.kt` add:

```kotlin
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
```

- [ ] **Step 2: Add data classes and mode-state fields**

Directly below the existing `data class RawSpec(...)` add:

```kotlin
    /** One selectable RAW output size of a lens. [label] is what the UI shows. */
    data class LensSize(val width: Int, val height: Int, val maxFps: Int, val label: String)

    /**
     * One selectable back lens. [physicalId] is null only for the single-lens
     * fallback (open the logical camera untagged — today's behavior). [fovMetric]
     * is sensorPhysicalWidth / focalLength: proportional to field of view, used
     * for sorting (widest first) and zoom labels.
     */
    data class LensInfo(
        val physicalId: String?, val label: String, val focalMm: Float,
        val fovMetric: Float, val sizes: List<LensSize>,
        val cfa: Int, val whiteLevel: Int, val blackLevel: IntArray,
        val colorMatrix1: FloatArray, val isoRange: ClosedRange<Int>,
        val minFocusDiopters: Float,
    )
```

Then replace the field declarations

```kotlin
    /** Sensor/RAW capabilities, queried once from CameraCharacteristics at init. */
    val rawSpec: RawSpec
```

with:

```kotlin
    /** Selectable back lenses, widest first. At least one entry. */
    val lenses: List<LensInfo>

    /** Index in [lenses] of the main (1×) lens — the revert target on mode failure. */
    val defaultLensIndex: Int

    /** Snapshot of the selected lens+size mode. Replaced atomically by [selectMode]. */
    @Volatile var rawSpec: RawSpec
        private set

    /** Physical camera id every session's OutputConfigurations are tagged with. */
    @Volatile private var activePhysicalId: String? = null
```

- [ ] **Step 3: Replace the init block**

Replace the entire existing `init { ... }` block (the one that queries `SCALER_STREAM_CONFIGURATION_MAP` and builds `rawSpec` from the max RAW size) with:

```kotlin
    init {
        cameraId = cameraManager.cameraIdList.first { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        }
        lenses = enumerateLenses(cameraManager.getCameraCharacteristics(cameraId))
        defaultLensIndex = lenses.indexOfFirst { it.label == "1×" }.coerceAtLeast(0)
        activePhysicalId = lenses[defaultLensIndex].physicalId
        rawSpec = specFor(lenses[defaultLensIndex], 0)
    }
```

- [ ] **Step 4: Add the enumeration/helper functions**

Add these private functions in the `// --- internals ---` section (above `createSession`):

```kotlin
    /**
     * Enumerates the logical camera's physical lenses: dedupes sensors exposed
     * under two ids (same focal length -> keep the id with the most RAW sizes),
     * sorts widest-first, and labels each with its zoom factor relative to the
     * main lens (the focal length the logical camera itself advertises).
     * Falls back to a single logical-camera entry when nothing enumerates.
     */
    private fun enumerateLenses(logicalCh: CameraCharacteristics): List<LensInfo> {
        val candidates = logicalCh.physicalCameraIds.mapNotNull { id ->
            try {
                buildLensCandidate(id, cameraManager.getCameraCharacteristics(id))
            } catch (e: Exception) {
                Log.w(TAG, "skipping physical camera $id", e)
                null
            }
        }
        val deduped = candidates
            .groupBy { it.focalMm }
            .map { (_, group) -> group.maxBy { it.sizes.size } }
            .sortedByDescending { it.fovMetric }
            .ifEmpty { listOfNotNull(buildLensCandidate(null, logicalCh)) }
        check(deduped.isNotEmpty()) { "no RAW-capable back lens" }
        val logicalFocal =
            logicalCh.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val mainIdx = if (logicalFocal == null) 0
        else deduped.indices.minBy { abs(deduped[it].focalMm - logicalFocal) }
        val mainFov = deduped[mainIdx].fovMetric
        return deduped.map { lens ->
            val zoom = mainFov / lens.fovMetric
            val label =
                if (zoom < 0.95f) "%.1f×".format(Locale.US, zoom) else "${zoom.roundToInt()}×"
            lens.copy(label = label)
        }
    }

    /** Null when [ch] lacks RAW, manual-sensor support, or any required key. */
    private fun buildLensCandidate(physicalId: String?, ch: CameraCharacteristics): LensInfo? {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val focal =
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: return null
        val physSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        val whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        val blackPattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return null
        val xform = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
        val sensRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val sorted = rawSizes.sortedByDescending { it.width.toLong() * it.height }
        val maxArea = sorted.first().width.toLong() * sorted.first().height
        val sizes = sorted.map { s ->
            val minDur = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
            LensSize(
                width = s.width, height = s.height,
                maxFps = if (minDur > 0) (1e9 / minDur).toInt() else 30,
                label = sizeLabel(s.width, s.height, maxArea),
            )
        }
        return LensInfo(
            physicalId = physicalId,
            label = "", // filled by enumerateLenses once the main lens is known
            focalMm = focal,
            fovMetric = physSize.width / focal,
            sizes = sizes,
            cfa = cfa,
            whiteLevel = whiteLevel,
            blackLevel = IntArray(4).also { blackPattern.copyTo(it, 0) },
            // Row-major 3x3: index i -> row i/3, column i%3 (getElement takes column, row).
            colorMatrix1 = FloatArray(9) { i -> xform.getElement(i % 3, i / 3).toFloat() },
            isoRange = sensRange.lower..sensRange.upper,
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )
    }

    /** "4:3" / "16:9" for full-area sizes, "LOW" for binned (under half the max area). */
    private fun sizeLabel(w: Int, h: Int, maxArea: Long): String {
        if (w.toLong() * h < maxArea / 2) return "LOW"
        val aspect = w.toFloat() / h
        return when {
            abs(aspect - 4f / 3f) < 0.05f -> "4:3"
            abs(aspect - 16f / 9f) < 0.1f -> "16:9"
            else -> "${h}p"
        }
    }

    private fun specFor(lens: LensInfo, sizeIndex: Int): RawSpec {
        val size = lens.sizes[sizeIndex]
        return RawSpec(
            width = size.width, height = size.height, cfa = lens.cfa,
            whiteLevel = lens.whiteLevel, blackLevel = lens.blackLevel,
            colorMatrix1 = lens.colorMatrix1, isoRange = lens.isoRange,
            maxFps = size.maxFps, minFocusDiopters = lens.minFocusDiopters,
            deviceName = Build.MODEL,
        )
    }
```

- [ ] **Step 5: Add selectMode**

Add this public function after `openAndPreview`:

```kotlin
    /**
     * Selects which lens+size the NEXT session uses. Refused while recording.
     * Deliberately does not touch the live session: the caller re-keys the
     * preview SurfaceView on the mode, and the resulting surfaceCreated ->
     * openCamera -> openAndPreview reopens the camera with the new
     * [activePhysicalId] and [rawSpec] — the same proven path as returning
     * from background.
     */
    fun selectMode(lensIndex: Int, sizeIndex: Int): Boolean {
        if (recording) return false
        val lens = lenses.getOrNull(lensIndex) ?: return false
        if (sizeIndex !in lens.sizes.indices) return false
        activePhysicalId = lens.physicalId
        rawSpec = specFor(lens, sizeIndex)
        return true
    }
```

- [ ] **Step 6: Tag session outputs with the physical id; propagate open failure**

In `createSession`, replace

```kotlin
                surfaces.map { OutputConfiguration(it) },
```

with:

```kotlin
                surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        activePhysicalId?.let { setPhysicalCameraId(it) }
                    }
                },
```

In `openAndPreview`, change the signature and wire failures through (replace the whole function):

```kotlin
    /**
     * Opens the camera and starts a preview-only repeating request (AWB auto).
     * [onFailed] fires when the device errors out or the preview session cannot
     * be configured — e.g. an unsupported lens/size mode.
     */
    @SuppressLint("MissingPermission")
    fun openAndPreview(previewSurface: Surface, onFailed: () -> Unit = {}, onReady: () -> Unit) {
        this.previewSurface = previewSurface
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(listOf(previewSurface), forRecording = false, onFailed = onFailed) {
                    onReady()
                }
            }

            override fun onDisconnected(cam: CameraDevice) {
                Log.w(TAG, "camera disconnected")
                cam.close()
                device = null
            }

            override fun onError(cam: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error")
                cam.close()
                device = null
                onFailed()
            }
        }, cameraHandler)
    }
```

`startRecording`, `stopRecording`, `updateManual`, `applyManual`, `close` need no changes — they already read `rawSpec` / go through `createSession`.

- [ ] **Step 7: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (RecordScreen still compiles: `openAndPreview`'s new parameter is defaulted and mid-signature, existing trailing-lambda call sites are unaffected; `rawSpec` is still readable.)

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt
git commit -m @'
feat: enumerate physical lenses and per-lens RAW modes in CameraController

selectMode picks lens+size into rawSpec; sessions tag OutputConfigurations
with the selected physical camera id; openAndPreview reports failures.
'@
```

---

### Task 2: RecordViewModel — mode state, fps options, failure revert

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (ViewModel + `RecordUiState` live at the top of this file)

**Interfaces:**
- Consumes (Task 1): `controller.lenses`, `controller.defaultLensIndex`, `controller.selectMode(lensIndex, sizeIndex): Boolean`, `controller.rawSpec` (var), `controller.openAndPreview(surface, onFailed, onReady)`.
- Produces (used by Task 3):
  - `RecordUiState.lensIndex: Int`, `RecordUiState.sizeIndex: Int`
  - `fun setLens(index: Int)` / `fun setResolution(index: Int)` — no-ops while `recording || busy`.
  - `fun fpsOptions(): List<Int>` — `FPS_OPTIONS` filtered by the current mode's `maxFps`, never empty.
  - `RecordViewModel.FPS_OPTIONS == listOf(24, 30, 48, 60)`

- [ ] **Step 1: Extend RecordUiState**

In `data class RecordUiState`, after `val fps: Int = 24,` add:

```kotlin
    val lensIndex: Int = 0,
    val sizeIndex: Int = 0,
```

- [ ] **Step 2: Initialize mode state**

Replace the `_uiState` construction

```kotlin
    private val _uiState = MutableStateFlow(
        RecordUiState(iso = controller.rawSpec.isoRange.start, fps = initialFps, shutterIndex = 0)
    )
```

with:

```kotlin
    private val _uiState = MutableStateFlow(
        RecordUiState(
            iso = controller.rawSpec.isoRange.start, fps = initialFps, shutterIndex = 0,
            lensIndex = controller.defaultLensIndex, sizeIndex = 0,
        )
    )
```

- [ ] **Step 3: Update FPS_OPTIONS and add fpsOptions()**

In the companion object replace `val FPS_OPTIONS = listOf(24, 30)` with:

```kotlin
        val FPS_OPTIONS = listOf(24, 30, 48, 60)
```

Below `shutterStops` add:

```kotlin
    /** FPS choices valid for the selected lens/size mode. Never empty. */
    fun fpsOptions(): List<Int> =
        FPS_OPTIONS.filter { it <= controller.rawSpec.maxFps }
            .ifEmpty { listOf(controller.rawSpec.maxFps) }
```

- [ ] **Step 4: Add setLens/setResolution/setMode**

After `setFps` add:

```kotlin
    /** Lens change resets the size to the new lens's full resolution. */
    fun setLens(index: Int) = setMode(index, 0)

    fun setResolution(index: Int) = setMode(_uiState.value.lensIndex, index)

    private fun setMode(lensIndex: Int, sizeIndex: Int) {
        val s = _uiState.value
        if (s.recording || s.busy) return
        if (lensIndex == s.lensIndex && sizeIndex == s.sizeIndex) return
        if (!controller.selectMode(lensIndex, sizeIndex)) return
        _uiState.update { coerceToMode(it.copy(lensIndex = lensIndex, sizeIndex = sizeIndex)) }
        // The lensIndex/sizeIndex change re-keys the preview SurfaceView; its
        // surfaceCreated calls openCamera, which reopens on the new mode.
    }

    /** Clamps fps and shutter to what the (just-selected) mode supports. */
    private fun coerceToMode(state: RecordUiState): RecordUiState {
        val opts = fpsOptions()
        val fps = if (state.fps in opts) state.fps
                  else (opts.lastOrNull { it <= state.fps } ?: opts.first())
        val stops = shutterStops(fps)
        return state.copy(
            fps = fps,
            shutterIndex = state.shutterIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
            previewReady = false,
        )
    }
```

- [ ] **Step 5: Wire the failure revert into openCamera**

Replace the `openCamera` function body with:

```kotlin
    fun openCamera(surface: android.view.Surface) {
        _uiState.update { it.copy(previewReady = false) }
        cameraOps.launch {
            try {
                controller.openAndPreview(surface, onFailed = { handleModeFailure() }) {
                    _uiState.update { it.copy(previewReady = true) }
                    pushManual()
                }
            } catch (e: Exception) {
                Log.e(TAG, "openAndPreview failed", e)
                _events.tryEmit("Camera open failed")
            }
        }
    }

    /**
     * Preview session failed to configure. If a non-default lens/size mode is
     * selected, revert to main lens full-res — the state change re-keys the
     * SurfaceView, which reopens the camera on the safe mode. If the default
     * mode itself failed, just report it (today's behavior).
     */
    private fun handleModeFailure() {
        val s = _uiState.value
        if (s.lensIndex == controller.defaultLensIndex && s.sizeIndex == 0) {
            _events.tryEmit("Camera open failed")
            return
        }
        controller.selectMode(controller.defaultLensIndex, 0)
        _uiState.update {
            coerceToMode(it.copy(lensIndex = controller.defaultLensIndex, sizeIndex = 0))
        }
        _events.tryEmit("Mode not supported — reverted to main lens")
    }
```

(Keep the existing kdoc above `openCamera`. `handleModeFailure` runs on the camera thread — `MutableStateFlow.update` and `tryEmit` are thread-safe.)

- [ ] **Step 6: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. The UI still uses `RecordViewModel.FPS_OPTIONS.filter { it <= spec.maxFps }` at this point — that still compiles; Task 3 switches it to `fpsOptions()`.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m @'
feat: lens/resolution mode state in RecordViewModel

setLens/setResolution drive controller.selectMode and coerce fps and
shutter to the new mode; session-configure failure on a non-default mode
reverts to main lens full-res. FPS options extended to 24/30/48/60,
filtered per mode.
'@
```

---

### Task 3: RecordScreen UI — lens/resolution chips, option pills, mode-keyed preview

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (composable section, below the ViewModel)

**Interfaces:**
- Consumes (Tasks 1–2): `viewModel.controller.lenses` (`LensInfo.label`, `LensInfo.sizes`, `LensSize.label`), `state.lensIndex`, `state.sizeIndex`, `viewModel.setLens/setResolution/fpsOptions`.
- Produces: UI only — new private composable `OptionPills(labels, selectedIndex, enabled, onSelect)`; `ParamChip` gains `enabled: Boolean = true`.

- [ ] **Step 1: Add the key import**

Add to the imports:

```kotlin
import androidx.compose.runtime.key
```

- [ ] **Step 2: Extend the Param enum**

Replace `private enum class Param { ISO, SHUTTER, FOCUS }` with:

```kotlin
private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS }
```

- [ ] **Step 3: Read mode state and key the preview on it**

In `RecordScreen`, replace

```kotlin
    val spec = viewModel.controller.rawSpec
    val shutterStops = viewModel.shutterStops(state.fps)
```

with:

```kotlin
    val spec = viewModel.controller.rawSpec
    val lenses = viewModel.controller.lenses
    val lens = lenses.getOrElse(state.lensIndex) { lenses[0] }
    val sizes = lens.sizes
    val size = sizes.getOrElse(state.sizeIndex) { sizes[0] }
    val shutterStops = viewModel.shutterStops(state.fps)
    val modeEnabled = !state.recording && !state.busy
```

Then wrap the preview `AndroidView(...)` call in a `key` block so a mode change recreates the SurfaceView (destroying the old surface and firing `surfaceCreated` on the new one, which reopens the camera on the new mode):

```kotlin
        // Keyed on the selected mode: changing lens or resolution recreates the
        // SurfaceView, and the fresh surfaceCreated -> openCamera reopens the
        // camera with the new physical lens and spec (same path as returning
        // from background).
        key(state.lensIndex, state.sizeIndex) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(spec.width.toFloat() / spec.height.toFloat()),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.openCamera(holder.surface)
                            }
                            override fun surfaceChanged(
                                holder: SurfaceHolder, format: Int, width: Int, height: Int,
                            ) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {}
                        })
                    }
                },
            )
        }
```

(The AndroidView body is unchanged — only the `key(...) { }` wrapper is new.)

- [ ] **Step 4: Use per-mode fps options in FpsToggle**

Replace

```kotlin
                FpsToggle(
                    options = RecordViewModel.FPS_OPTIONS.filter { it <= spec.maxFps },
```

with:

```kotlin
                FpsToggle(
                    options = viewModel.fpsOptions(),
```

- [ ] **Step 5: Extend the expanded panel with LENS/RES pill rows**

In the expanded-slider Surface, replace the title `Text(when (param) { ... })` and the `when (param)` slider block with:

```kotlin
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
```

- [ ] **Step 6: Add the lens/resolution chips to the chip row**

Replace the chip `Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ... }` block with (spacing tightened to 8.dp so five chips fit the 400.dp column):

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

- [ ] **Step 7: Add OptionPills and extend ParamChip**

Below the `FpsToggle` composable add:

```kotlin
/** Horizontal pill selector for the LENS / RESOLUTION panels (FpsToggle, by index). */
@Composable
private fun OptionPills(
    labels: List<String>, selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp))
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .clickable(enabled = enabled) { onSelect(i) }
                    .background(if (on) RawCamColors.SurfaceVariant else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    color = if (on) RawCamColors.OnSurface else RawCamColors.Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
```

Replace the `ParamChip` composable with (adds `enabled`, tightens padding to 12.dp so five chips fit):

```kotlin
@Composable
private fun ParamChip(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = Color(0xB80A0B0D),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (active) RawCamColors.Accent else RawCamColors.Outline),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text, color = RawCamColors.OnSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
```

- [ ] **Step 8: Build**

Run: `.\gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m @'
feat: lens and resolution chips on the record screen

New LENS/RES chips expand to option pill rows; the preview SurfaceView is
keyed on the selected mode so changing it reopens the camera cleanly.
Chips disabled while recording.
'@
```

---

### Task 4: On-device verification matrix

**Files:** none (verification only; fix-up commits as needed).

Preconditions: Pixel 7 Pro connected over wireless adb (`$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`; if offline, ask the user to toggle Wireless debugging and reconnect; disconnect stale mDNS serials if `adb devices` shows two). Phone has ~13 GB free — keep test clips ≤3 s and delete them at the end.

- [ ] **Step 1: Install and launch**

```powershell
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.shez.rawcam/.MainActivity
```

Screenshot (`adb shell screencap -p /data/local/tmp/scr.png`, pull, downscale <2000px, Read): chip row shows five chips — `1×`, `4:3`, ISO, shutter, focus.

- [ ] **Step 2: Lens switching**

Tap the lens chip (coordinates from the screenshot), screenshot: LENS panel with `0.6×  1×  5×` pills. Tap `0.6×`, wait ~2 s, screenshot: noticeably wider framing, preview still 4:3, no crash. Repeat for `5×` (narrow framing). Check `adb logcat -d -s CameraController:*` for session failures.

- [ ] **Step 3: Record on each lens**

For each of `0.6×`, `1×`, `5×`: select lens, tap shutter, wait ~3 s, tap again. Then open CLIPS and screenshot: three new clips with metadata lines reading `4032×3016`, `4080×3072`, `4032×3024` respectively, plausible frame counts (~72 at 24 fps), 0-drop expectation checked on the record screen before stop.

- [ ] **Step 4: Resolution + high fps on the main lens**

Select `1×`, tap the resolution chip: pills `4:3  16:9  LOW`. Record ~3 s at `16:9` → clip shows `4080×2288`. Select `LOW` → fps toggle should now offer 48/60 (per-mode maxFps; if the HAL caps RAW fps lower, note what it offers instead). Select the highest offered fps, record ~3 s → clip shows `2032×1536` and the dropped counter stays 0 during recording.

- [ ] **Step 5: Export sanity on a non-main lens**

In CLIPS, Export the `0.6×` clip; wait for "Exported · N DNGs". Pull one DNG:

```powershell
& $adb shell ls /sdcard/Android/data/com.shez.rawcam/files/exports/
& $adb pull <exports>/<clip>/<frame>.dng $env:TEMP\uw.dng
```

Verify with a stdlib-only Python TIFF check (script in the scratchpad): parse the IFD, assert ImageWidth=4032, ImageLength=3016, and that CFAPattern/BlackLevel tags are present. If `rawpy` is importable, also decode and confirm a non-black postprocessed thumbnail.

- [ ] **Step 6: Regression + cleanup**

- Toggle to Clips and back mid-preview: camera reopens (existing invariant).
- Start recording: lens/res chips render dimmed and don't respond.
- Delete the test clips via the UI (user data caution: only the clips just created).
- Commit any fixes made during verification:

```powershell
git add -A
git commit -m @'
fix: device-verification fixes for lens/resolution modes
'@
```

(Skip the commit if nothing changed.)

---

## Self-review notes

- Spec coverage: enumeration/dedupe/labels (T1 S2–S4), selectMode + rawSpec recompute (T1 S5), physical-id tagging (T1 S6), failure revert (T2 S5), state + fps filtering (T2), chips/pills/keyed SurfaceView (T3), per-lens header correctness + test matrix (T4). Spec's "recreates the session" clause is implemented via the SurfaceView re-key path rather than an in-place session swap — deliberate: it reuses the proven surfaceCreated→openCamera reopen and avoids racing a session recreation against the surface teardown the re-key causes.
- Native/exporter untouched: confirmed — no tasks touch cpp/ or ExportService.
- Type consistency: `LensSize.label`/`LensInfo.label` (T1) match T3 usage; `fpsOptions()` (T2) matches T3 call; `selectMode(Int, Int): Boolean` consistent across T1/T2.
