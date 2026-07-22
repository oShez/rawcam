# Universal Camera Support (Spec A: Enumeration + Capability Model) — Design

Date: 2026-07-22. Status: approved direction.

Scope decisions made during brainstorming: never-crash is the floor, maximum
extraction is the ambition; real RAW lenses only (no logical-zoom pseudo-lenses,
no front camera); RAW-without-MANUAL_SENSOR is supported via an explicit control
tier with honest UI; this spec stays fully offline (the INTERNET permission and
auto-updating quirks land in Spec C).

## Goal

Make RawCam launch, enumerate correctly, and record on any Android phone —
never crashing on unexpected hardware, and surfacing every RAW lens the device
genuinely has rather than only those shaped like a Pixel 7 Pro or Xiaomi 14
Ultra. Establish a pure, fixture-testable enumeration layer so device support
can be verified against phones the developer does not own.

## Non-goals (out of scope — separate specs)

- **Spec B — orientation correctness.** `SENSOR_ORIENTATION != 90` breaks
  preview, tap-to-meter mapping, the horizon level, and the exported DNG's
  Orientation tag. This spec *captures* `sensorOrientation` into the data model
  but changes no orientation behavior.
- **Spec C — quirks database + network.** INTERNET permission, auto-updating
  quirks JSON, opt-in report upload, landing-page copy change.
- **Spec D — sustained-throughput adaptation.** Knowing before recording whether
  a device can hold 4K RAW instead of discovering it via dropped frames.

Also out: front-facing cameras, logical-camera zoom-ratio pseudo-lenses,
compression, audio, playback, histogram.

## Background — defects this addresses

Found by reading the current enumeration path end to end:

| # | Location | Defect |
|---|---|---|
| 1 | `CameraController.kt:354` | `cameraIdList.first { BACK && RAW }` throws `NoSuchElementException` → process crash on any phone with no RAW-capable back camera. Same crash shape as the July permission bug. |
| 2 | `CameraController.kt:1009` `buildLensCandidate` | Requires RAW **and** MANUAL_SENSOR **and** ~8 non-null characteristics, else `return null`. Drops lenses for missing fields that are only needed for *labels*. |
| 3 | `CameraController.kt:1091` `probeHiddenLenses` | Scans ids `0..15` only. |
| 4 | manifest / `meterAt` / `HorizonLevel` | Assumes `SENSOR_ORIENTATION == 90`. **Deferred to Spec B.** |
| 5 | `dng_writer.cpp:147` | `addShort(274, 1)` — Orientation hardcoded. **Deferred to Spec B.** |
| 6 | `AndroidManifest.xml` | `uses-feature camera.raw required="true"` blocks install on non-RAW devices, so they cannot even reach an explanatory screen. |
| 7 | `capture.h` `kQueueCap` | No sustained-throughput model. **Deferred to Spec D.** |
| 8 | (absent) | `SENSOR_INFO_EXPOSURE_TIME_RANGE` is never read anywhere; shutter is clamped against fps only, so the HAL silently overrides unsupported requests. |

This spec fixes 1, 2, 3, 6, 8.

## Architecture

Separate "what is this device" from "drive the camera":

```
CameraManager ──[Camera2SnapshotSource]──> List<CameraSnapshot> ──[LensDiscovery]──> DeviceProfile
              (only Android-coupled code)     (plain data, JSON-able)   (pure fn)     (sealed result)
                                                      │                                     │
                                                      ▼                                     ▼
                                            fixtures/*.json                        CameraController
                                          (JVM golden tests)                    (sessions, threading, WB)
```

Three new files under `app/src/main/java/com/shez/rawcam/camera/`:

- **`CameraSnapshot.kt`** — plain data classes plus JSON read/write. No
  `android.hardware.*` imports. Serializable so a real device's characteristics
  become a test fixture.
- **`Camera2SnapshotSource.kt`** — the *only* place `CameraCharacteristics.get()`
  is called. Builds snapshots, tolerates every key being absent, performs the
  widened id probe.
- **`LensDiscovery.kt`** — pure function `discover(List<CameraSnapshot>):
  DeviceProfile`. Zero Android dependencies, therefore runnable in a plain JVM
  unit test.

`CameraController` keeps what it is good at — session generation, the camera
thread, per-lens WB anchors (`anchorState`/`presetCurves`), standalone-device
crossing in `openAndPreview` — and stops owning enumeration.
`enumerateLenses`/`buildLensCandidate`/`probeHiddenLenses` (~250 lines) move out.
`initialize()` becomes: snapshots → `discover()` → apply.

`applySelectedLens` and `specFor` stay in `CameraController`, retargeted from
`LensInfo` to `LensProfile`. **The per-lens WB identity key (`activePhysicalId`)
and the `activeCameraId`/`sessionTagId` three-way split must survive this
refactor unchanged** — that split is load-bearing for telephoto support and was
hard-won (commit `e74ead8`).

## Data model

```kotlin
sealed interface DeviceProfile {
  data class Supported(
    val lenses: List<LensProfile>, val mainIndex: Int, val notes: List<ProfileNote>,
  ) : DeviceProfile
  data class Unsupported(
    val reason: UnsupportedReason, val detail: String, val notes: List<ProfileNote>,
  ) : DeviceProfile
}

enum class UnsupportedReason {
  NO_BACK_CAMERA, NO_RAW_CAPABILITY, NO_USABLE_RAW_SIZES, PERMISSION_REDACTED,
}
```

`Unsupported` is a *state*, never an exception — this is defect 1's fix.
`PERMISSION_REDACTED` specifically covers Android blanking characteristics for an
app without CAMERA permission, the exact condition that used to crash the
process before `ensureCameraInitialized()`.

**`ProfileNote`** records every accept/reject decision with its reason
(`"id 4: accepted, standalone, colorMatrix1 defaulted"`, `"id 6: rejected, no RAW
output sizes"`). One structure serving three consumers: logcat diagnostics, the
in-app compatibility report, and (Spec C) the upload payload.

**`LensProfile`** replaces `LensInfo`, carrying its existing fields plus:

- `controlTier: ControlTier` — `FULL` (MANUAL_SENSOR capability **and** a usable
  ISO range) or `AUTO_ONLY` (RAW records; 3A stays automatic). A missing
  *exposure* range does **not** demote the tier: it only means the shutter stop
  table cannot be intersected, and today's fps-only clamping applies.
- `defaulted: Set<SnapshotField>` — which soft fields were substituted, so UI and
  DNG export can be honest about what is real

### Hard vs soft requirements

The core coverage win. Today every field below is hard (`?: return null`).

| Tier | Fields | Behavior when missing |
|---|---|---|
| **Hard** | RAW output sizes (non-empty), CFA, white level, black level | Reject the lens — a valid DNG is genuinely impossible |
| **Soft** | focal length, physical size, colorTransform1/2, illuminants, ISO range, exposure range, active array, min focus, OIS modes | Accept with a documented default + a `ProfileNote`; degrade labels/color, never the recording |

Soft-field defaults:

- **focal length absent** → no `equivFocalMm`; label falls back to a 1-based
  ordinal over the final sorted lens list (`LENS 1`, `LENS 2`, …); sort position
  falls back to declaration order
- **physical size absent** → no 35mm-equivalent; label uses physical focal length
  if present, else ordinal
- **colorTransform1 absent** → generic sRGB-derived fallback matrix, lens flagged
  uncalibrated in the report; DNG still exports and still opens
- **ISO range absent** → lens becomes `AUTO_ONLY` (cannot offer manual ISO honestly)
- **exposure range absent** → today's fps-only clamping, unchanged
- **active array absent** → fall back to the largest RAW size as array bounds
  (`meterAt` maps fractionally, so this degrades gracefully)
- **min focus absent** → `0f` (infinity-only focus)

## Behavior

**Unsupported device.** App launches and shows a dedicated screen in the existing
flat bordered/accent-fill language (same as the camera-permission gate), stating
the real reason plus a **Copy device report** action. Requires flipping the
manifest to `android:required="false"` on `camera.raw` (defect 6) — otherwise the
affected devices cannot install far enough to see it.

**AUTO_ONLY lens.** Selectable and recordable. ISO/shutter/focus sliders render
disabled with a short reason; WB falls back to AWB. `RecordUiState` carries
`controlTier` so `RecordScreen` disables rather than silently ignoring input.
Must compose correctly with the existing per-parameter lock feature (commit
`e7b0faf`): a param that is unsupported is *disabled*, not *locked* — two
distinct visual states.

**Main-lens selection never fails.** Chain: logical camera's advertised focal
length → nearest match; if focal lengths absent → largest active array; if that
absent → index 0. Today a missing focal length silently yields index 0, which
with `isMain` is a genuine wrong-lens-on-launch bug rather than a cosmetic one.

**Exposure range** (defect 8). Read `SENSOR_INFO_EXPOSURE_TIME_RANGE` into the
snapshot and intersect it with the app's shutter stop table so the UI only offers
shutter speeds the sensor can honor. Where absent, behavior is unchanged.

**Widened id probe** (defect 3). Probe set = `cameraIdList` ∪ every listed
camera's `physicalCameraIds` ∪ a bounded numeric scan to **31**, under a
wall-clock budget of ~400 ms so a slow vendor HAL cannot stall launch. Runs off
the main thread as today.

Known limitation, stated rather than papered over: **non-numeric hidden ids are
undiscoverable by any scan.** If a vendor uses them, only a Spec C quirks entry
can reach them. See Open Questions.

**Compatibility report.** New Settings entry: device identity, each accepted lens
with its tier and defaulted fields, and every rejected id with its reason.
Exported via the OS share sheet — **no INTERNET permission in this spec.**

## Verification

This project has **no Kotlin/JVM test infrastructure today** — no `test/` or
`androidTest/` directory and no test dependencies. Every test to date is C++
doctest or manual on-device. This spec stands JVM testing up from scratch:
`app/src/test/java/`, a JUnit dependency, and the first Kotlin tests in the
project's history. Written test-first, per this project's TDD convention.

### Fixture corpus, in three waves

1. **Hand-authored shape fixtures (~12), unblocked.** The failure *shapes* that
   matter: no-RAW device, RAW-without-MANUAL_SENSOR, sensor orientation 270,
   missing physical size, missing color matrix, Samsung-style high ids,
   permission-redacted, single-lens legacy, zero RAW sizes, out-of-range values.
2. **Real device dumps, unblocked.** A debug-only "Dump characteristics" action
   writes snapshot JSON. Yields Xiaomi 14 Ultra (4 lenses) and Pixel 7 Pro (2).
   Plus the **free Camera FV-5 Galaxy S10+ sample**, which is a real device and
   contributes **GRBG** CFA diversity that neither owned phone provides.
3. **Camera FV-5 licensed corpus — supplementary breadth, see below.**

**Governing rule: waves 1 and 2 must stand alone.** Every correctness claim this
spec makes — topology discovery, hard/soft field handling, tier assignment, the
never-throws invariant — must be fully covered by the hand-authored shape
fixtures and the owned-device dumps. Wave 3 adds breadth to the fuzz suite and
nothing else. If the licence never lands, is withdrawn, is priced badly, or the
vendor disappears (as nearly happened with Camera2Probe, whose operator is in
liquidation), the test suite must remain complete and green with wave 3 absent.
No coverage target, no CI gate, and no golden test may depend on it.

### The FV-5 importer

The [Camera FV-5 device database](https://www.camerafv5.com/devices/licensing/)
covers **29,374 devices / 2,605 manufacturers**, one JSON per device, weekly
updated. Verified against their free Galaxy S10+ sample:

- **Every field `CameraSnapshot` needs is present**, under AOSP key names —
  including `android.sensor.orientation` and
  `android.sensor.info.exposureTimeRange` (defects 4 and 8).
- Structure is `{sdkLevel: {cameraId: {...}}}`.
- **`physicalCameraIds` is empty and only back+front cameras appear.** The S10+'s
  ultrawide and telephoto are absent. **The dataset carries no logical-multi-camera
  topology at all.**

Consequence, stated plainly: the corpus **cannot test lens discovery**, which is
this spec's core. It is valuable only for *field-level* robustness — thousands of
real sensors' worth of strange-but-real values driven through the fuzz suite.
Topology coverage comes from wave 1 and stays there.

Deliverable: `FvFiveImporter` converting their per-device JSON into
`CameraSnapshot` fixtures, plus the fuzz suite wired to consume the corpus **when
present and to skip cleanly when absent**. The importer is built and tested
against the free Galaxy S10+ sample, so it is proven without the licence; a
licensed corpus is data it happens to consume, never a build or test
prerequisite. Treat the vendor as untrusted infrastructure: single supplier,
undisclosed pricing, unknown redistribution terms, and a sibling product in the
same niche already being wound up.

**Two contingencies the implementation must handle:**

- **The purchase is the user's to transact** (email `contact@fgae.de`). Spec A
  must complete without it. The importer ships and is tested against the free
  sample; the licensed corpus is an input it consumes when it arrives.
- **Redistribution terms decide storage.** If the license permits committing
  derived fixtures, they live in the repo. If it does not, the importer writes
  into a **gitignored** directory and local runs regenerate from the licensed
  source. Design for both; choose when terms are known.

### Test layers

1. **Golden tests** — each fixture → expected `DeviceProfile` (lens count, tiers,
   notes). Regression-proof against future enumeration changes.
2. **Never-throws invariant (the most important test)** — fuzz `discover()` with
   randomly nulled and corrupted fields across thousands of permutations; assert
   it always returns `Supported` or `Unsupported` and **never throws**. This is
   what actually enforces the never-crash floor, and it is cheap.
3. **On-device regression** — Xiaomi 14 Ultra must still enumerate all 4 lenses
   (12/23/74/117mm) with the telephoto standalone path intact; Pixel 7 Pro its 2.
   Record + export on both, output comparable to today.
4. **C++ `ctest` 7/7 stays green** — untouched by this spec, but proven.

Note the asymmetry: layers 1–2 verify logic against any device on earth; only
layer 3 verifies *behavior*, and it is limited to two phones. No amount of
fixture coverage substitutes for that, and this spec does not pretend otherwise.

## Open questions

- **Non-numeric camera ids.** The FV-5 sample lists ids `semt0`/`semt1` rather
  than `"0"`/`"1"`. Unresolved whether Samsung genuinely exposes such ids or FV-5
  renames them internally. If the former, the bounded numeric scan has a real
  blind spot that only Spec C's quirks table can close. Resolve by inspecting a
  second Samsung sample or a real device before relying on the scan.
- **Generic fallback color matrix.** Which exact matrix to substitute when
  `colorTransform1` is absent — sRGB-derived is the proposal; worth confirming
  against what LibRaw/Resolve do with an uncalibrated DNG.

## Risks

- **Refactor risk is the main one.** `CameraController` holds threading and
  lifecycle invariants that took several dedicated bug-fix sessions to get right
  (per-lens WB anchoring `796a712`, rawSpec staleness `0ba7eaa`, standalone lens
  crossing `e74ead8`). Moving enumeration out must not disturb them. Mitigation:
  enumeration moves as a pure extraction with no behavior change on the two owned
  devices, verified by on-device regression *before* any new capability lands.
- **Soft-field defaulting could mask genuinely broken lenses**, surfacing a lens
  that enumerates but cannot record. Mitigation: hard requirements stay strict,
  and every default is recorded in the report rather than applied silently.
