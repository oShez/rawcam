# Tap-to-Meter Auto — Design Spec

**Date:** 2026-07-16
**Status:** Design approved, pending spec review → implementation plan
**Feature:** Tap a point on the preview to auto-meter the best ISO, shutter,
white balance (Kelvin), tint, and focus for that spot, then hold those values
as locked manual settings (Blackmagic-Camera-style "auto then lock").

## Goal

Give the fully-manual RawCam pipeline a one-shot auto path: the user taps a
point on the live preview; the app briefly runs the phone's hardware 3A
(auto-exposure, auto-focus, auto-white-balance) metered **at that point**,
reads back the converged values, snaps them into the existing manual controls,
and returns to full manual so nothing drifts while shooting. Re-tap to
re-meter; nudge any slider to override.

## Background / constraint

The app records `RAW_SENSOR` and drives every parameter manually:
`CameraController.applyManual()` sets `CONTROL_AE_MODE_OFF`,
`CONTROL_AF_MODE_OFF`, `CONTROL_AWB_MODE_OFF` and pushes slider-derived values
(`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `LENS_FOCUS_DISTANCE`,
`COLOR_CORRECTION_GAINS` via `gainsFor(kelvin, tint)`). "Auto" therefore means
**temporarily** re-enabling the hardware 3A on the preview stream, letting it
converge, reading the result, converting it back into the app's manual
parameter space, and restoring manual mode. We reuse the ISP's 3A rather than
reimplementing metering from RAW pixels — the hardware algorithms are far
better and the tap point drives them directly via metering rectangles.

## Global constraints

- One-shot only: metering converges once, then the pipeline is fully manual
  again. No continuous-auto mode in this feature.
- Tap-to-meter is available **only when not recording**. Metering flips the
  preview repeating request to auto; doing that mid-recording would disrupt the
  RAW stream and touch the session/repeating-request lifecycle, which is the
  most crash-prone area of this app. WB stays live-adjustable during recording
  exactly as today; re-metering waits until recording is stopped.
- Values land in the existing `RecordUiState` manual fields and existing chips.
  There is no separate "locked" state to persist — manual *is* locked.
- Kelvin result snaps to the nearest existing `KELVIN_STOPS` entry; tint snaps
  to the nearest `TINT_STOPS` entry; shutter to the nearest `shutterStops(fps)`
  entry; ISO to the nearest `isoStops` entry; focus to the nearest
  `focusStops` entry. Metered values must be representable by the existing
  discrete controls so the user can see and further adjust them.
- Convergence timeout: 1500 ms. On timeout, apply the best-so-far result if AE
  has at least reached a usable state; otherwise abort and emit a
  "Couldn't meter — try again" event, leaving current manual values untouched.

## Architecture

Three layers, mirroring the existing manual-WB feature's structure:

1. **`CameraController` (native camera layer)** gains a `meterAt(nx, ny,
   callback)` that runs the auto→read-back→restore sequence off the caller
   thread on the camera `HandlerThread`.
2. **`RecordViewModel`** gains `meterAt(nx, ny)` that calls the controller and
   applies the returned values through the existing `setIso/setShutter/
   setFocus/setKelvin/setTint` path (which already pushes to the controller and
   updates `RecordUiState`).
3. **`RecordScreen`** adds a tap gesture on the preview and a transient reticle
   overlay + "metering…" affordance.

### Component: `CameraController.meterAt`

**Interface:**
```kotlin
data class MeteredValues(
    val iso: Int,
    val exposureNs: Long,
    val focusDiopters: Float,
    val kelvin: Int,
    val tint: Int,
)

/**
 * Meter the hardware 3A at a normalized preview point (nx, ny in 0f..1f,
 * top-left origin, in the preview's displayed orientation). Runs the auto
 * convergence on the camera thread and posts the result (or null on failure)
 * to [onResult] on the camera thread. No-op returning null if recording,
 * if the device/session is not ready, or on convergence failure/timeout.
 */
fun meterAt(nx: Float, ny: Float, onResult: (MeteredValues?) -> Unit)
```

**Behavior:**
1. Guard: if `recording`, or `device`/`session`/`previewSurface` is null,
   post `null` and return.
2. Map `(nx, ny)` to a sensor-array `MeteringRectangle`:
   - Read `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (cached at open) for the active lens.
   - Convert the normalized point (accounting for the fixed landscape
     orientation and any preview aspect crop) to active-array pixel coords.
   - Build a metering rectangle ~10% of the array width/height centered on the
     point, clamped to the array, weight `METERING_WEIGHT_MAX`.
   - If mapping yields an empty/degenerate rectangle, fall back to a
     full-frame metering rectangle (whole active array) — this is the
     automatic "meter the whole scene" fallback.
3. Build a preview repeating request (preview surface only) with:
   `CONTROL_MODE_AUTO`, `CONTROL_AE_MODE_ON`, `CONTROL_AF_MODE_AUTO`,
   `CONTROL_AWB_MODE_AUTO`, `CONTROL_AE_REGIONS`/`CONTROL_AF_REGIONS` set to the
   rectangle, and `CONTROL_AF_TRIGGER_START` on a single one-shot capture to
   kick AF. Set this as the repeating request with a capture callback.
4. In the capture callback, watch `CONTROL_AE_STATE`, `CONTROL_AF_STATE`,
   `CONTROL_AWB_STATE` across results until all three are settled
   (`AE_STATE` in {CONVERGED, FLASH_REQUIRED, LOCKED}, `AF_STATE` in
   {FOCUSED_LOCKED, NOT_FOCUSED_LOCKED, PASSIVE_FOCUSED}, `AWB_STATE` in
   {CONVERGED, LOCKED}) or a 1500 ms deadline (tracked via `SystemClock`
   elapsed time on the camera handler).
5. On convergence (or timeout with a usable AE state), read from the
   `TotalCaptureResult`:
   - `iso` ← `SENSOR_SENSITIVITY`
   - `exposureNs` ← `SENSOR_EXPOSURE_TIME`
   - `focusDiopters` ← `LENS_FOCUS_DISTANCE`
   - `kelvin, tint` ← `gainsToKelvinTint(result.get(COLOR_CORRECTION_GAINS))`.
     Camera2 populates `COLOR_CORRECTION_GAINS` in AUTO-AWB results on this
     class of device; if absent, leave Kelvin/tint unchanged (null-guarded).
6. Restore manual: call the existing `applyManual` path so the pipeline returns
   to `AE_OFF/AF_OFF/AWB_OFF`. The ViewModel then overwrites the manual values
   from the metered result.
7. Post `MeteredValues(...)` to `onResult` (null on any failure).

### Component: gains → Kelvin/tint inversion (`gainsToKelvinTint`)

Pure function, inverse of the existing `gainsFor(kelvin, tint)`.

```kotlin
/**
 * Invert per-channel WB gains back into the nearest (kelvin, tint) the manual
 * controls can represent. gainsFor() maps kelvin -> a neutralizing R/B gain
 * pair with green as reference and tint as a green multiplier; this searches
 * KELVIN_STOPS for the entry whose gainsFor(kelvin, 0) best matches the
 * measured red-to-blue gain ratio (that ratio is monotonic in kelvin), then
 * recovers tint from the measured green gain relative to the reference and
 * snaps it to the nearest TINT_STOPS entry.
 */
fun gainsToKelvinTint(gains: RggbChannelVector): Pair<Int, Int>
```

Method:
- Let measured `gR, gG, gB` = red, avg-green, blue gains.
- Ratio `r = gR / gB`. `gainsFor(k,0)` produces a `gainR/gainB` monotonic in
  `k` over `KELVIN_STOPS`; pick the `k` in `KELVIN_STOPS` minimizing
  `abs(ln(gainR(k)/gainB(k)) - ln(r))`.
- Tint: `gainsFor` sets green gain = `tintFactor = (1 - tint/100)` (clamped
  0.3..2). Invert: `tint ≈ round((1 - gG_ref) * 100)` where `gG_ref` is the
  measured green gain normalized so the tint-0 reference maps to 1.0; snap to
  nearest `TINT_STOPS`.
- Both results are clamped into their stop lists so they are always
  representable.

Pure Kotlin (only depends on the `RggbChannelVector` type), so it is the one
piece with a straightforward unit test.

### Component: `RecordViewModel.meterAt`

```kotlin
fun meterAt(nx: Float, ny: Float) {
    val s = _uiState.value
    if (s.recording || s.metering || !s.previewReady) return
    _uiState.update { it.copy(metering = true, meterPoint = Offset(nx, ny)) }
    cameraOps.launch {
        controller.meterAt(nx, ny) { result ->
            // hop back to the VM scope to apply on the main/VM context
        }
    }
}
```
On a non-null result it applies `setIso / setShutter(nearest) / setFocus /
setKelvin / setTint` (each already snaps and pushes to the controller), then
clears `metering`. On null it clears `metering` and emits a "Couldn't meter —
try again" event. `RecordUiState` gains `val metering: Boolean = false` and
`val meterPoint: Offset? = null` (the latter drives the reticle).

### Component: `RecordScreen` gesture + reticle

- Add `Modifier.pointerInput(previewReady, recording) { detectTapGestures { off ->
  if (!recording) viewModel.meterAt(off.x / size.width, off.y / size.height) } }`
  on the preview container.
- Reticle overlay: when `meterPoint != null`, draw a small animated crosshair
  at that normalized point; while `metering`, show a subtle "metering…" label.
  Clear the reticle a short delay after metering completes.
- No new chip: the five existing chips visibly update to the metered values,
  which is the "locked" affordance.

## Data flow

```
tap (preview px) → normalize (0..1) → RecordViewModel.meterAt
  → CameraController.meterAt (camera thread)
      → build AE/AF/AWB-auto preview request with metering region at point
      → converge (capture-result 3A states, 1500ms deadline)
      → read SENSOR_SENSITIVITY / EXPOSURE_TIME / FOCUS_DISTANCE / GAINS
      → gainsToKelvinTint(gains)
      → restore applyManual()
      → onResult(MeteredValues)
  → RecordViewModel applies setIso/setShutter/setFocus/setKelvin/setTint
  → RecordUiState updates → chips reflect metered values (now locked/manual)
```

## Error handling

- Not ready / recording / no device → silent no-op (gesture does nothing; UI
  never enters `metering`).
- Convergence timeout with usable AE state → apply best-so-far result.
- Convergence failure (no usable AE state by deadline) → restore manual with
  prior values, emit "Couldn't meter — try again", clear `metering`.
- Any Camera2 exception during the auto request (`CameraAccessException`,
  closed session) → caught, logged via `Log.e`, restore manual, emit failure
  event. Metering must never leave the pipeline in auto or crash the session.
- Reticle state is purely cosmetic and self-clears; never blocks input.

## Testing

- **Host unit test (new):** `gainsToKelvinTint` round-trip — for every
  `(kelvin, tint)` in `KELVIN_STOPS × TINT_STOPS`, assert
  `gainsToKelvinTint(gainsFor(kelvin, tint))` returns the same pair (within one
  stop). Pure math; the one automatically-verifiable piece. (Note: no Kotlin
  test harness exists in this project today; this test requires adding one, or
  the inversion is instead verified by hand-computation during review — decide
  in the plan.)
- **On-device manual verification:** tap a bright vs dark area → exposure/ISO
  differ sensibly; tap near vs far object → focus differs; tap under warm vs
  cool light → Kelvin differs; confirm values land in chips and hold (no
  drift); confirm recording still works after metering; confirm metering is
  disabled during recording; confirm a failed meter leaves prior values intact
  and shows the toast.

## Out of scope (YAGNI)

- Continuous-auto mode / per-parameter auto toggles.
- Metering during recording.
- A separate "meter whole frame" button (full-frame is the automatic fallback
  when a tap can't map to a valid region).
- Persisting metered/locked state across restarts (no persistence layer exists;
  manual state already resets on relaunch by design).
- Flash / torch metering (`FLASH_REQUIRED` treated as converged; no flash
  fired — this is a video recorder).

## Known implementation risks

- Touches the preview **session/repeating-request lifecycle** — the most
  crash-prone area of the app (v1 history is teardown-ordering crash fixes).
  The meter sequence must serialize with `updateManual`/`startRecording` on the
  camera thread and must always restore manual on every exit path.
- Coordinate mapping (preview view space → sensor active array, through the
  fixed landscape orientation and preview crop) is fiddly and needs on-device
  validation that the tapped point maps to the metered region.
- `COLOR_CORRECTION_GAINS` availability in AUTO-AWB results is device-specific;
  the Pixel 7 Pro populates it, but the read must null-guard.
- Cannot be compiled or on-device tested until the host toolchain (currently
  wedged) and a stable device link are available; this spec + its plan are
  today's deliverable.
