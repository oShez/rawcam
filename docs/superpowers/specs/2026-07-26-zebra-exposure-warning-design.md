# Zebra Exposure Warning — Design Spec

**Date:** 2026-07-26
**Status:** Design approved, pending spec review → implementation plan
**Feature:** An optional animated diagonal-stripe overlay over clipped
highlights in the live preview, toggleable in Settings, active whenever the
viewfinder is up (idle preview and while recording) — a standard cine-camera
exposure aid.

## Goal

Give the user a live, at-a-glance warning of where the image is clipping to
white, the same way a real cine camera's zebra pattern works, so they can
correct exposure before (or during) a take instead of discovering blown
highlights after pulling DNGs into Resolve.

## Background / constraint

RawCam's live preview is a raw hardware pass-through: `CameraController`
targets the `SurfaceView`'s `Surface` directly with the Camera2 repeating
request, and the app never sees the delivered pixels (this is why the
existing grid and horizon-level overlays are purely synthetic — geometric
lines and a device-sensor angle, not image content). Zebra stripes are
fundamentally different: they need to know *which pixels* are clipped, which
requires a stream the app can actually read.

**Honest scope note:** this monitors the *processed preview* stream (post-ISP,
debayered and tone-mapped), not raw sensor data. That's how zebra works on
every real camera, including RAW-shooting cinema cameras — it warns where
highlights clip in the rendered image. The RAW recording itself is written
from the unprocessed sensor stream exactly as today and is completely
unaffected by this feature either way.

**GPU was considered and declined.** The analysis buffer is deliberately
low-resolution (see Components below), so the threshold pass is a trivial
single scan over a small luma plane — well under a millisecond on any target
device, negligible against a 30fps frame budget. The stripe *compositing*
itself is already GPU-accelerated regardless, since Compose's `Canvas` draws
through Skia's hardware pipeline either way. An AGSL shader would only move
the cheap part to GPU while adding real integration risk (no shader code
exists anywhere in this codebase yet; sampling a live external YUV buffer
through a Compose `RenderEffect` is unproven territory here) for no measured
win — the same call this project already made on the much heavier DNG export
path, which was found storage-bound and left on CPU.

## Global constraints

- Fixed clip threshold: Y == 255, the maximum value of the 8-bit Y plane
  Camera2's `YUV_420_888` format provides (i.e. genuinely clipped, not "near"
  clipped). Not user-adjustable in this feature — a future settings row can
  add that later without changing the architecture.
- Active whenever the viewfinder is live: idle preview **and** recording.
  Toggling the setting recreates the camera session (same cost class as
  switching lens/resolution today, which already recreates it) rather than
  keeping an always-open, often-idle third stream reserved against the
  device's stream-count budget.
- Animated diagonal stripes (not a static tint), matching standard cine-camera
  zebra conventions.
- Graceful degradation, not a hard requirement: on a device with no usable
  analysis stream size or that can't support a third concurrent output, the
  Settings toggle stays visible and flippable everywhere; turning it on
  silently has no visible effect. No new "unsupported" UI state — this matches
  `ShutterStops.available()`'s "never fail outright, never return empty"
  philosophy and avoids growing `CompatibilityReport`'s scope for a
  non-essential visual aid.
- Default off, same as `gridEnabled`/`levelEnabled`.

## Architecture

A third, optional Camera2 output — a low-resolution `YUV_420_888`
`ImageReader` — added to the session's `OutputConfiguration` list **only**
when `Settings.zebraEnabled` is true, for both the preview-only session
(`createSession(listOf(previewSurface), forRecording = false)`) and the
recording session (`createSession(listOf(preview, raw), forRecording =
true)`). The Y-plane of a YUV frame *is* luminance directly — no RGB
conversion needed, which is the whole reason YUV (not a second RAW/Bayer
stream) is the right source here.

Size selection: pick the smallest size Camera2 advertises for
`YUV_420_888` on the active physical camera whose aspect ratio is closest to
the current recording mode's aspect ratio (mirrors the existing
nearest-match, never-fail pattern used elsewhere for stops/sizes). No fixed
resolution is hardcoded — devices vary in what they advertise.

## Components

- **`CameraController`** — new `zebraReader: ImageReader?` /
  `zebraSurface: Surface?` fields, alongside the existing `previewSurface` /
  `rawSurface` pair. A new dedicated `HandlerThread` (matching the existing
  `meterCallbackThread` pattern — the camera thread itself must never block)
  owns the `ImageReader.OnImageAvailableListener`. Its callback reads the
  Y-plane, runs the threshold, and publishes the resulting clip mask.
  `createSession`'s output list gains the analysis surface conditionally on
  `Settings.zebraEnabled`, following the same shape as the recording-only
  `raw` surface being conditionally present today.
- **Threshold function** — a small, pure function (byte plane in, clip mask
  out) with **no Android API dependency**, so it gets real host/JVM test
  coverage rather than only being exercised on-device. Given the existing
  project convention of pure Kotlin logic living in `camera/` (e.g.
  `ShutterStops`, `LensDiscovery`), this is a Kotlin object in that package
  rather than native C++ — the workload is trivial and doesn't need the
  native layer's discipline the way the capture hot path does.
- **`RecordScreen.kt`** — a new `Canvas` layer in the same Box stack as the
  existing grid/level/reticle overlays (composed above the `SurfaceView`,
  outside the tap-gesture chain, purely a paint layer). Draws an animated
  diagonal-stripe `Brush`, composited through the current clip mask via
  `BlendMode` so stripes render only over flagged pixels, upscaled onto the
  same pillarboxed preview rect the other overlays already use.
  `rememberInfiniteTransition` drives the stripe animation phase at full UI
  frame rate, decoupled from the (likely slower) analysis cadence.
- **`SettingsRepository` / `SettingsScreen.kt`** — new `zebraEnabled: Boolean
  = false`, following the `gridEnabled`/`levelEnabled` precedent exactly:
  DataStore key, restore-on-launch, a toggle row in the existing overlays
  section.

## Data flow

Camera → YUV `ImageReader` (dedicated background thread) → per-frame Y-plane
threshold (pure function) → small clip mask → published into `RecordUiState`
(mirrors how `meterPoint`/grid/level state already flows) → Compose `Canvas`
redraws the masked region each frame; stripe phase animates independently of
new mask arrivals.

## Error handling

- No usable YUV size / stream-count limit exceeded when the setting is on:
  `zebraReader`/`zebraSurface` simply stay null, the session is created
  without the third output, and the overlay never has a mask to draw (silent
  no-op, per the Global Constraints decision above).
- Analysis callback failures (a malformed/short plane, an unexpected format)
  are logged and the frame is skipped — never crash the analysis thread,
  matching the existing `onImageAvailable`-style discipline of "the camera
  callback path degrades, it never takes the app down."
- Toggling the setting mid-session recreates the camera session on the
  camera thread, the same path already exercised by lens/resolution changes
  — no new failure mode introduced.

## Testing

- Host/JVM tests for the pure threshold function: given a synthetic luma
  plane, verify the clip mask flags exactly the pixels equal to 255 and none
  below it, plus edge cases (all-clipped, all-black, empty/zero-size plane).
- On-device verification is required before this is considered done, per
  this project's own established convention — I don't have a connected
  device this session, so this will be an **owed item**, tracked the same
  way Spec A tracked its device-verification gates. Specifically needs
  checking: stripes visually track genuinely blown highlights (point the
  camera at a bright window/light and confirm), the overlay behaves through
  a lens/resolution switch, toggling the setting on/off doesn't destabilize
  the preview, and it doesn't visibly cost frame drops during an actual
  recording.

## Out of scope (this feature)

- Adjustable threshold slider (flagged above as a natural future addition).
- Any change to the RAW capture/export pipeline — this is preview-overlay
  only.
- GPU/shader implementation (considered and declined above).
