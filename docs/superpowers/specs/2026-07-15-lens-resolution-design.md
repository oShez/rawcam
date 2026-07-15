# RawCam lens & resolution selection — design

Date: 2026-07-15
Status: approved by user ("Approve (Recommended)")

## Goal

Let the user pick which back lens records and at what RAW resolution, like MotionCam
Pro / Blackmagic Camera. First item of the "more camera settings" request; manual WB
is a separate follow-up spec.

## Device facts (probed via dumpsys media.camera on the Pixel 7 Pro, 2026-07-15)

Logical back camera "0" exposes physical cameras, all with RAW + MANUAL_SENSOR:

| Lens | Physical id | RAW_SENSOR sizes |
|---|---|---|
| Main wide, 6.81 mm | 2 (5 = duplicate, fewer sizes) | 4080×3072, 4080×2288, 2032×1536 |
| Ultrawide, 1.95 mm | 3 | 4032×3016, 4032×2272, 2016×1508 |
| Tele 5×, 19.0 mm | 4 (6 = duplicate) | 4032×3024 |

Physical streams are reached by opening logical "0" and tagging each
`OutputConfiguration` with `setPhysicalCameraId(id)` — the official multi-camera
mechanism (hidden physical ids cannot be opened directly by third-party apps).

## CameraController changes

- **LensInfo enumeration (init)**: from logical 0's `getPhysicalCameraIds()`, read
  each physical id's characteristics: focal length, `SENSOR_INFO_PHYSICAL_SIZE`,
  RAW_SENSOR output sizes, sensitivity range, min focus distance, black level
  pattern, color transform, CFA, white level. Dedupe sensors that appear twice
  (same focal length + pixel array): keep the id with the most RAW sizes. Sort by
  field of view (widest first). Label = zoom factor relative to the main lens
  computed from FOV (`(physWidth/focal)` ratio), formatted like `0.6×`, `1×`, `5×`.
  Devices where enumeration yields nothing fall back to a single LensInfo built
  from the logical camera itself (today's behavior).
- **Mode state**: `selectMode(lensIndex, sizeIndex)` sets the active physical id +
  RAW size, recomputes `rawSpec` (all per-lens fields + chosen size + that size's
  max fps from `getOutputMinFrameDuration`), and — if the camera is open —
  recreates the session on the new lens (preview-only; refused while recording).
  `rawSpec` stays a val-like snapshot accessor; the UI re-reads it from state
  changes.
- **Session plumbing**: `createSession` wraps every surface in
  `OutputConfiguration(surface).apply { physicalId?.let(::setPhysicalCameraId) }`.
  Recording start passes the selected size to `nativeStartRecording` (the header
  and Packed10 math already take arbitrary w/h; all probed sizes divide by 4).
- **Failure handling**: if a session on a physical lens fails to configure, emit
  the existing failure path (snackbar via events) and automatically re-select the
  main lens at full resolution.

## ViewModel / state

- `RecordUiState` gains `lensIndex: Int`, `sizeIndex: Int` (and the UI reads lens
  labels/sizes/maxFps through the controller's LensInfo list).
- `setLens(i)` / `setResolution(i)` — no-ops while recording; call
  `controller.selectMode`, coerce fps to the new mode's options, re-push manual
  values, and reset `shutterIndex` bounds (existing fps-change logic reused).
- `FPS_OPTIONS` becomes `[24, 30, 48, 60]`, still filtered by the selected mode's
  `maxFps` — binned modes may unlock 48/60; full-res stays as today. The
  free-space/"space remaining" math already derives from spec dims + fps.

## UI (RecordScreen)

- Chip row gains two chips before ISO: **lens** (`1×`) and **resolution** (`4:3`,
  `16:9`, `LOW` — labels derived from the size's aspect/area, only those the lens
  offers). Expanding a chip shows a row of option pills (FpsToggle-style), not a
  slider. Both chips disabled while recording.
- The preview letterbox already uses `spec.width/height`, so aspect follows the
  selected mode automatically; the SurfaceView is keyed on the selected mode so a
  mode change recreates the surface → existing `surfaceCreated` → `openCamera`
  path reopens cleanly.

## Invariants preserved

- No lens/resolution/fps changes while `recording || busy`.
- Per-lens black level / color matrix / CFA flow into the `.rawv` header and out
  to DNGs, so every lens exports color-correct CinemaDNG.
- Native core, container format, exporter: untouched.

## Testing

- Host ctest: unaffected (no core changes).
- On-device: for each lens — record ~3 s, stop, export, pull one DNG, verify
  dimensions match the mode and rawpy decodes with the right CFA/black level.
  16:9 and LOW modes on the main lens; fps 48 or 60 at LOW if unlocked, checking
  the drop counter stays at 0.
