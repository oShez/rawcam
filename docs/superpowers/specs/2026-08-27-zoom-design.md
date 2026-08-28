# Zoom: true sensor crop

**Date:** 2026-08-27
**Status:** Design approved, not yet implemented.
**Revision:** 2026-08-28 -- revised after a senior-dev review of the first draft.
Changes are noted inline where they overturn something the first draft asserted.

## Problem

RawCam has no zoom. The only way to change framing is to switch physical
lenses, which is discrete: on the 14 Ultra you get 12mm, 23mm, 75mm and 120mm
and nothing in between. Framing a shot means walking, or shooting wide and
cropping in post at whatever resolution survives.

The obvious fix -- set a zoom ratio on the capture request -- does not work on
its own. Android applies `CONTROL_ZOOM_RATIO` and `SCALER_CROP_REGION` to
processed streams only. `RAW_SENSOR` output is specified as always covering the
full active array, with the crop region reported as metadata. Setting the key
alone would zoom the viewfinder while the recorded file stayed wide: a preview
that lies about the take.

## Approach

Zoom is a **crop of the sensor readout**, applied in two places that are kept
in agreement:

1. `CONTROL_ZOOM_RATIO` on the repeating request zooms the preview (and the
   zebra analysis stream) by ratio `r`.
2. The native capture path writes only the matching centred sub-rectangle of
   each RAW frame to the `.rawv` file.

Because the crop is a real sensor crop, zoom is lossless in the only sense that
matters for RAW: no pixel is ever upscaled or interpolated. What you give up is
pixel count, and you give it up honestly -- 2x on the main camera records
2048x1536 and the UI says so.

Two consequences fall out of this:

- Fewer pixels per frame means less encoder work. **Bounded claim:** the camera
  still reads out the full sensor and DMAs full-size frames into the 12
  `AImageReader` buffers, so 4x zoom is emphatically *not* 16x of headroom.
  What shrinks is the per-frame read-predict-pack cost in
  `ParallelFrameEncoder`, which is the stage round 5 measured as the binding
  one. Given round 4's finding that the pipeline is memory-bandwidth-bound, the
  gain should be treated as real but sublinear and unquantified until measured.
- `FileHeader` already carries per-file `width`, `height`, `rowStrideBytes` and
  `frameSizeBytes`, so a cropped clip needs **no format version bump**. A v5
  reader opens a zoomed clip today.

## Goals

- Zoom is set while framing and locked for the duration of a take.
- The preview shows exactly the frame that gets recorded.
- Fixed ladder -- 1x, 1.4x, 2x, 2.8x, 4x -- identical on every device and lens,
  clamped only where the device cannot preview that far (see "Clamping").
- Driven by pinch on the viewfinder, snapping to those stops, with the current
  stop and its recorded resolution shown on the params rail.
- At 1x the capture path is byte-for-byte what ships today.
- Works for every pack mode and every lens, as the rest of the pipeline does.

## Non-goals

- Zooming during a take. See "Why zoom is locked while recording" below.
- Continuous (unsnapped) zoom ratios.
- Optical zoom: crossing between physical lenses as part of one zoom gesture.
  Lens choice stays the separate control it is today.
- Upscaling a crop back to full resolution. A 4x clip is 1024x768 and stays so.
- Recording the zoom as reversible metadata over a full-sensor frame. The crop
  is baked in; that is the decision that buys the encoder headroom.

## Why zoom is locked while recording

`.rawv` has one `frameSizeBytes` and one `width`/`height` for the whole file.
Every frame in a file must be the same size, so a crop that changed mid-take
could not be written as smaller frames. The alternatives -- record at the
widest crop used and store a per-frame rect, or record full-sensor and log a
zoom curve -- both keep every frame at full size and so surrender the encoder
saving entirely, in exchange for a mid-shot push-in. Locking at record start is
the option that costs nothing and needs no format change.

## The zoom ladder

A pure function of the **selected RAW size** for the current lens -- not the
lens's maximum size, since a lens may offer several and the ladder must follow
whichever is active.

Given `fullW x fullH` and nominal ratio `r`:

```
cropW    = floor(fullW / r)  rounded DOWN to a multiple of 4
ratio    = fullW / cropW                    (the ACTUAL ratio)
cropH    = floor(fullH / ratio) rounded DOWN to a multiple of 2
originX  = ((fullW - cropW) / 2) rounded DOWN to even
originY  = ((fullH - cropH) / 2) rounded DOWN to even
```

The rounding is what keeps the crop legal, and each rule earns its place:

- **`originX`, `originY` even.** The Bayer phase is fixed by the origin's
  parity. An odd origin shifts the CFA by one pixel while `FileHeader.cfa`
  still claims the sensor's pattern, so the clip decodes cleanly into wrong
  colour -- green and magenta footage, no error anywhere.
- **`cropW` a multiple of 4.** `capture.cpp:375` gates Packed10 on
  `width % 4 == 0` and Packed12 on `width % 2 == 0`, falling back to Raw16
  otherwise, because `pack10`/`pack12` step in whole groups per row with no
  remainder handling and `RawvReader::headerSane` rejects a non-divisible pixel
  count outright. A crop width that broke the gate would still record a valid
  clip, silently 1.6x larger. Note this rule protects the **uncompressed**
  modes only: `CompressedPredictive` -- the default -- works per pixel and is
  exempt from the group-size gates.
- **`cropH` even.** Keeps whole CFA rows.

A stop is dropped from the ladder if alignment leaves `cropW < 4` or
`cropH < 2`. On real sensors no stop is ever dropped; the guard exists so a
degenerate or unusual RAW size cannot produce a nonsense rectangle.

On the 14 Ultra main camera (4096x3072) the ladder is:

| Stop | Recorded    | Actual ratio | Origin      |
|------|-------------|--------------|-------------|
| 1x   | 4096 x 3072 | 1.0000       | 0, 0        |
| 1.4x | 2924 x 2192 | 1.4008       | 586, 440    |
| 2x   | 2048 x 1536 | 2.0000       | 1024, 768   |
| 2.8x | 1460 x 1094 | 2.8055       | 1318, 988   |
| 4x   | 1024 x 768  | 4.0000       | 1536, 1152  |

Note 2.8x: the centred origin lands on 989 and is rounded down to 988. That is
the rule doing its job.

Preview must be driven by the **actual** ratio, not the nominal one. Sending
1.4 while the file is cropped 1.4008 would put preview and file a few pixels
out of agreement.

### Clamping

*(New in revision 2 -- the first draft had no clamp, which reintroduced the
exact failure this design exists to prevent.)*

Two device conditions must clamp the ladder, because in both cases the preview
would stop agreeing with the file:

1. **`CONTROL_ZOOM_RATIO_RANGE.upper` below a stop.** If the device will not
   preview past, say, 3.2x, then at the 4x stop the preview holds at 3.2x while
   the RAW crop goes to 4x -- a preview that lies about the file, arrived at
   from the other direction. Every stop whose actual ratio exceeds the
   advertised upper bound is **removed from the ladder**, not silently capped.
2. **`activeArray` was defaulted.** `LensDiscovery.kt:221-222` falls back to the
   largest RAW size when `SENSOR_INFO_ACTIVE_ARRAY_SIZE` is absent, recording
   `SnapshotField.ACTIVE_ARRAY`. Crop-region reasoning on a guessed rectangle is
   not trustworthy, so when that field was defaulted the ladder collapses to
   **1x only** and the rail control is disabled.

Both conditions are per lens, so the ladder is rebuilt on lens change.

### Plumbing the zoom range

`Camera2SnapshotSource` reads no zoom characteristic today. Clamping rule 1
needs `CONTROL_ZOOM_RATIO_RANGE` to reach the ladder through the same
discipline every other characteristic uses:

- `Camera2SnapshotSource` reads `CONTROL_ZOOM_RATIO_RANGE` alongside
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (`Camera2SnapshotSource.kt:114`).
- `LensDiscovery` substitutes `1.0..1.0` when it is absent -- the conservative
  default, which disables zoom rather than assuming it -- and records
  `SnapshotField.ZOOM_RANGE`, a new entry in the enum in `DeviceProfile.kt`.

Falling back to "no zoom" rather than "assume 4x" is the choice that fails
visibly (the control is greyed) instead of silently (the preview drifts from
the file).

## Capture-side crop

`Capture::start()` currently uses its `width`/`height` arguments for three
different jobs: the `AImageReader` size, the pack and encode dimensions, and
the header. The change splits the reader size from the output size.

- `AImageReader_new(fullW, fullH, ...)` is unchanged. The camera always
  delivers full sensor.
- `width_` / `height_` become the cropped dimensions. New `cropX_` / `cropY_`
  carry the origin.
- `ParallelFrameEncoder` is already constructed from `width_` / `height_`
  (`capture.cpp:396`) and so sizes itself correctly with no change.

### `frameSizeBytes` is not uniform across pack modes

*(Correction -- the first draft claimed `hdr.frameSizeBytes` was "already
derived from `width_`/`height_`, so those lines need no edit." That is true for
only two of the four modes.)*

| Mode                   | Line | Current expression                | Cropped expression |
|------------------------|------|-----------------------------------|--------------------|
| `Packed10`             | :96  | `packed10Size(width_ * height_)`  | unchanged -- already width-derived |
| `Packed12`             | :100 | `packed12Size(width_ * height_)`  | unchanged -- already width-derived |
| `Raw16`                | :104 | `rowStride_ * height_`            | **`cropW * 2 * cropH`** |
| `CompressedPredictive` | :110 | `rowStride_ * height_`            | **`cropW * 2 * cropH`** |

The two stride-derived modes must change, because `rowStride_` is the camera's
full-frame stride and has nothing to do with the crop. For
`CompressedPredictive`, `frameSizeBytes` is only the allocation ceiling -- the
guaranteed-safe upper bound for a frame that does not compress at all -- so it
becomes the cropped Raw16 size, `cropW * 2 * cropH`, and `compressBuf_` is
sized to that.

`hdr.rowStrideBytes` becomes `cropW * 2` in every mode, since what is written
is de-strided.

### The compressed path gets the crop for free

The compressed path hands the encoder a base pointer plus `rowStrideSamples`
and lets it walk `width_ x height_` rows (`capture.cpp:167`). So for that path
the crop is a base-pointer offset with the **camera's stride left alone**, and
the encoder reads the sub-rectangle at no cost:

```cpp
const uint8_t* base = data + (size_t)cropY_ * rowStride_ + (size_t)cropX_ * 2;
```

The distinction to hold onto: the *encoder input* keeps the camera's stride;
the *header and output* describe the de-strided cropped frame.

### Two paths need real copy work

A cropped rectangle is not contiguous, so:

- **Raw16** copies whole strided rows today. Cropped, it must de-stride: a
  per-row copy of `cropW * 2` bytes from `base + row * rowStride_`.
- **The compressed fallback** `job.rawCopy.assign(data, data + frameSizeBytes)`
  must likewise become a per-row copy of the cropped rect.

Setting `rowStrideBytes` to the de-strided `cropW * 2` keeps the decode side
uniform: `exporter.cpp` and the proxy renderer already read geometry from the
header, so they need no change at all.

**At 1x, no crop branch is taken.** No offset, no de-stride, no new work: the
path is byte-identical to what is on `main` and device-verified today. That is
deliberate -- it keeps the regression surface of this feature confined to
clips that actually use it, and it is what the host test in "Testing" pins.

### JNI signature change

*(Not stated in the first draft.)*

`Capture::start()` and the `NativeBridge` declaration that fronts it gain the
full reader dimensions in addition to the output ones, plus the crop origin:

```
start(path, fullW, fullH, cropX, cropY, cropW, cropH, ...)
```

At 1x the Kotlin side passes `cropX = cropY = 0`, `cropW = fullW`,
`cropH = fullH`, and the native side takes the existing uncropped branch.

## Preview zoom

`CONTROL_ZOOM_RATIO` on both the preview and record repeating requests, set to
the stop's actual ratio.

**The `SCALER_CROP_REGION` fallback is dropped.** *(The first draft specified
one.)* `CONTROL_ZOOM_RATIO` is API 30 and the app's `minSdk` is 33
(`app/build.gradle`), so the fallback is unreachable code. The complexity
budget goes to the clamp instead.

**1x sends `1.0f` explicitly** rather than omitting the key, so a stale ratio
cannot survive a session rebuild.

This zooms the preview and the zebra YUV stream together, which is correct:
zebras should read the framing being shot, not a wider frame that will not be
in the file.

## Integration points

These are the places zoom leaks into existing behaviour. Each is easy to miss
and fails quietly.

- **Tap-to-focus / metering.** `meteringRectFor` (`CameraController.kt:889`)
  maps a normalized tap directly into active-array pixels. Per the
  `CONTROL_ZOOM_RATIO` documentation, when that key is set the coordinate
  system for `SCALER_CROP_REGION` and the AE/AWB/AF regions becomes the
  *post-zoom* field of view, still spanning the full active-array rectangle --
  which would mean `meteringRectFor` needs **no change**, since the preview a
  tap lands on and the metering coordinate space zoom together.
  *(The first draft asserted the opposite, that the ratio had to be folded in
  by hand.)* This is exactly the kind of semantics a vendor HAL gets wrong, so
  it is a **device check, not an assumption**: tap a small bright object at 4x
  and confirm exposure converges on it and not on the frame centre. If it lands
  wrong, the correction is to map the tap through the crop rect --
  `cx = cropX + nx * cropW` in active-array pixels -- and the fix belongs
  behind the same measured-behaviour flag as the RAW crop-exemption check.
- **`captureRateKey`.** Keyed on lens, geometry and compression today. Zoom
  changes geometry, so it must join the key; otherwise frame-budget estimates
  get read from another crop's bucket and mislead.
- **Persistence.** Zoom stop persists per lens, alongside `lensIndex` and
  `sizeIndex`, and is validated against the current ladder on restore the same
  way those are -- a stop index that no longer exists, or that clamping has
  removed, falls back to 1x.
- **Recording lock.** The rail control and the pinch gesture are both inert
  while recording.
- **Size change.** Selecting a different RAW size rebuilds the ladder; the
  current stop is re-resolved against it.

## Testing

**JVM (`ZoomLadderTest`)** -- the ladder is a pure function, so its invariants
are cheap to pin down exhaustively:

- `originX`, `originY` even; `cropW % 4 == 0`; `cropH % 2 == 0`, for every stop
  over a sweep of sensor sizes including odd and prime-ish dimensions.
- The crop rectangle is always within bounds.
- 1x is exactly the full frame at origin 0,0.
- Ratios are strictly increasing; the ladder never exceeds 4x.
- Degenerate sizes drop stops rather than emitting a bad rectangle.
- A `CONTROL_ZOOM_RATIO_RANGE.upper` below a stop removes that stop and every
  stop above it.
- A defaulted `ACTIVE_ARRAY` collapses the ladder to 1x alone.

**Native (ctest)** -- these run on the device per standing project practice
(host MSYS2 gcc compiles nothing; build for arm64 and run over adb):

- **The 1x regression pin.** A synthetic frame put through the crop path with
  `cropX = cropY = 0, cropW = fullW, cropH = fullH` must produce output
  byte-identical to the uncropped path, for all four pack modes. *(This
  replaces the first draft's on-device "a 1x clip is byte-identical to one
  recorded before the change", which is unfalsifiable -- you cannot record the
  same live scene twice and get identical bytes, because sensor noise,
  timestamps, AWB and frame counts all differ. The guarantee is real, but it
  can only be pinned against a synthetic frame.)*
- Cropped Raw16, Packed10, Packed12 and CompressedPredictive round-trips, each
  bit-exact against a reference produced by cropping a full frame in the test
  itself. This is the check that would catch a stride or origin error in the
  copy loops.

**On device:**

1. A 1x clip has unchanged header geometry and a landing rate no worse than the
   pre-change baseline. *(Degraded from "byte-identical" -- see above. The
   byte-level guarantee lives in ctest.)*
2. A 2x clip opens in Resolve with correct colour -- this is the CFA phase
   check, and the only thing that proves the origin parity rule holds end to
   end.
3. Preview framing at 2x matches the recorded frame.
4. Tap-to-focus at 4x converges on the tapped object, per the metering note
   above.
5. `packMode@20 == 3` verified by reading the `FileHeader` struct -- not by
   eyeballing adjacent u32s -- before trusting any measurement, per standing
   project practice.

## Risks

- **RAW may not be crop-exempt on every device.** The whole design rests on the
  camera delivering full-sensor RAW while the zoom ratio is set. If some device
  crops RAW too, we would double-crop and the file would not match the preview.
  This is checked on device **before any implementation work**: record at 2x on
  the 14 Ultra and confirm the delivered RAW is still 4096x3072. If it is not,
  the crop must be conditioned on measured device behaviour and this spec needs
  revisiting.
- **Fixed 4x cap on small and binned sizes.** `LensDiscovery.kt:262` labels RAW
  sizes `"4:3"` / `"16:9"` / `"LOW"`, and the `LOW` binned modes are
  selectable. Since the ladder is per selected size, 4x on an already-binned
  size can land near or below VGA. The uniform cap is a deliberate decision and
  is kept: the rail's live resolution readout is what makes the cost visible at
  the moment of framing rather than discovered in post. If that proves too
  sharp an edge in use, clamping the ladder by size class is a change to the
  ladder function alone and touches nothing else in this design.
- **Aspect drift from rounding.** Alignment moves the crop's aspect by up to
  about 0.1% from the sensor's -- worst at 2.8x, where 1460x1094 is 1.3346
  against the sensor's 1.3333. That is roughly one pixel of height at these
  sizes, and preferable to an unaligned rectangle.
- **The encoder saving is unquantified.** See the bounded claim under
  "Approach". Zoom should not be counted on as a lever against the frame
  budget until a zoomed take has been measured.
