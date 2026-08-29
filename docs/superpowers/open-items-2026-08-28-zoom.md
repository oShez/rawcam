# Zoom (true sensor crop) — open items after on-device verification

Date: 2026-08-29. Plan: `docs/superpowers/plans/2026-08-28-zoom.md`.
Spec: `docs/superpowers/specs/2026-08-27-zoom-design.md`.

Merged to `main` as a 14-commit fast-forward (`f2411b3..980675d`) and pushed.

## What's done

| Commit | Task | What it does |
|---|---|---|
| `3dba779` | 1 | CFA-aligned zoom ladder (`ZoomLadder`, `ZoomStop`) |
| `a0cecf3` | 1 | Round the top stop up so it cannot exceed the hard 4x cap |
| `c156a14` | 1 | Pin exact numbers where the cap clamp fires |
| `19872fa` | 2 | Plumb `CONTROL_ZOOM_RATIO_RANGE` into `LensProfile` |
| `90b087f` | 2 | Stop a NaN zoom upper bound from silently defaulting |
| `26f5cd6` | 3 | `cropBase16`/`cropPlane16` in `rawcam_core` + `test_crop` |
| `a74403e` | 4 | Write only the cropped sub-rectangle in the capture path |
| `8cf909f` | 5 | Drive preview zoom; pass the crop at record start |
| `3718f01` | 6 | Persist the selected zoom stop |
| `cbae0f2` | 7 | ZOOM rail row, picker and pinch gesture |
| `33f7084` | — | Close the two deferred minors from the task 1 review |
| `235614d` | — | Size a recorded frame from the crop, not the full sensor |
| `684d89c` | — | Publish the ladder on the launch path, not only on mode change |
| `980675d` | — | Map tap-to-meter through the zoom crop |

Tests at merge: JVM 152/152; host `core` 12 suites / 93 cases / 0 failures,
run on the merged `main` rather than only on the branch.

## Verified on device (Xiaomi 14 Ultra, 24030PN60G)

- **RAW is crop-exempt.** The gating spike forced `CONTROL_ZOOM_RATIO = 2.0` and
  the delivered RAW was still 4096x3072, with `SCALER_CROP_REGION` the full
  active array. The capture result echoed `zoomRatio=2.0`, so the HAL accepted
  the request rather than ignoring it — without that echo the test could not
  have distinguished "RAW ignores zoom" from "zoom was never applied".
- **2x geometry exact.** `width 2048, height 1536, rowStrideBytes 4096,
  packMode 3, frameSizeBytes 6291456` — stride is `cropW*2`, not the camera's
  8192, so the de-stride path fired; size is exactly a quarter of the 1x
  25165824.
- **1x unchanged.** A 1x take reproduces the pre-change header exactly.
- **Colour is correct.** Exported 2x DNGs open clean in Resolve — no green or
  magenta cast, so the even-origin Bayer rule holds on real footage. This
  failure mode raises no error anywhere in the pipeline.
- **Preview zooms** (confirmed by eye).

  **Correction (2026-08-29):** an earlier version of this file claimed the
  viewfinder's content does not appear in `screencap` because it is a
  SurfaceView, and that no automated check was therefore possible. That was
  **wrong**. `screencap` does capture the camera image; the black viewfinder in
  the earlier screenshots was a dark room with focus set to 21cm, not a capture
  limitation. Preview framing *can* be checked from a screenshot, and the
  viewfinder rect can additionally be read straight out of a `uiautomator dump`
  as the SurfaceView's bounds.
- Five user-driven takes all recorded 2048x1536.

## Design decisions worth keeping

- **`frameSizeBytes` is conditional, not uniform.** For `Raw16` and
  `CompressedPredictive` it is `cropped_ ? width_*2*height_ :
  rowStride_*height_`. At 1x the payload is the delivered buffer written
  verbatim *including stride padding*, so its size must stay the camera's
  stride times height; making it unconditional would silently corrupt every 1x
  Raw16 clip on any device that pads its stride.
- **The compressed path gets its crop free.** `predictAt` guards on band-local
  x and y, so encoding over a cropped base pointer at the camera's stride is
  bit-identical to encoding the de-strided crop as a standalone frame.
  `test_crop` pins this, with the thread count forced so the guard is actually
  exercised at a band boundary.
- **The cap clamp is not confined to the top stop.** Flooring `cropW` bites
  proportionally harder on small sensors: at `fullW` 17..22 the 2.8x rung
  floors to `cropW=4` and would realize 4.25x. The guard tests the actual
  ratio, never the nominal.
- **Tap-to-meter maps through the crop as normalized fractions of the RAW
  spec**, never by adding `cropX` to the active-array rect — those are
  different coordinate spaces, and adding them breaks 1x wherever the spec size
  and active array differ.

## Three bugs the plan did not anticipate

All three were invisible to the test suite and surfaced only on hardware.

1. **The ZOOM row was invisible at startup.** The ladder was published from
   `coerceToMode`, but the launch path builds its own ui state and never calls
   it, so `zoomStops` stayed empty and the row hid itself. The feature would
   have shipped missing, with a green suite.
2. **`frameRecordBytes` sized frames from the full sensor**, so at 4x the
   free-space check demanded ~16x too much space (refusing to record) and
   time-left read ~16x short.
3. **This HAL uses pre-zoom coordinates for 3A**, contrary to the
   `CONTROL_ZOOM_RATIO` documentation the spec relied on. Symptom: focus fine
   at centre, failing at the frame edges when zoomed. Isolated by the 1x
   control, where both mappings are arithmetically identical.

## Open items

1. **Edge focus at 2x/4x is UNCONFIRMED.** `980675d` was merged without the
   confirming observation. If edge taps still miss — or if 1x edge focus has
   regressed — the HAL is post-zoom after all and that commit should be
   reverted. It is a single-commit revert.
2. **Landing rates were never captured under zoom.** Nothing is known about
   dropped frames or any encoder saving at 2x/4x. The spec deliberately leaves
   the encoder saving unquantified; this does not change that, but it means
   even the observation is missing.
3. **The padded-stride case is unverified by measurement.** The Xiaomi's stride
   is unpadded (8192 == 4096*2), so both branches of the conditional above
   compute the same value there. The guard rests on a code-reading argument.
4. **`capture.cpp` has no test coverage at all.** It is unreachable from
   `core/tests`, which is precisely why the crop arithmetic was put in `core`.
   The 1x byte-identity guarantee lives in `test_crop`'s pin, not in any test
   of the capture path itself.
5. **`CameraController` has no unit tests** anywhere in the project (Camera2
   glue needing real hardware), so `setZoomIndex`'s three behaviours and the
   ladder rebuild are covered by compilation plus manual device use only.
6. **Release builds run R8 minification**, which the debug builds used for all
   device testing do not. Anything that misbehaves only in release should
   suspect that first.
