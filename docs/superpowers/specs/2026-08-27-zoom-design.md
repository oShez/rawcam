# Zoom: true sensor crop

**Date:** 2026-08-27
**Status:** Design approved, not yet implemented.

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

Two consequences fall out of this for free:

- Fewer pixels per frame means less encoder work, which is spare headroom
  against the frame-landing budget rather than a new cost.
- `FileHeader` already carries per-file `width`, `height`, `rowStrideBytes` and
  `frameSizeBytes`, so a cropped clip needs **no format version bump**. A v5
  reader opens a zoomed clip today.

## Goals

- Zoom is set while framing and locked for the duration of a take.
- The preview shows exactly the frame that gets recorded.
- Fixed ladder -- 1x, 1.4x, 2x, 2.8x, 4x -- identical on every device and lens.
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

## Capture-side crop

`Capture::start()` currently uses its `width`/`height` arguments for three
different jobs: the `AImageReader` size, the pack and encode dimensions, and
the header. The change splits the reader size from the output size.

- `AImageReader_new(fullW, fullH, ...)` is unchanged. The camera always
  delivers full sensor.
- `width_` / `height_` become the cropped dimensions. New `cropX_` / `cropY_`
  carry the origin.
- `hdr.width` / `hdr.height` / `hdr.frameSizeBytes` are already derived from
  `width_` / `height_`, so those lines need no edit.
- `ParallelFrameEncoder` is already constructed from `width_` / `height_`
  (`capture.cpp:396`) and so sizes itself correctly with no change.

The compressed path hands the encoder a base pointer plus `rowStrideSamples`
and lets it walk `width_ x height_` rows (`capture.cpp:167`). So for that path
the crop is a base-pointer offset with the stride left alone, and the encoder
reads the sub-rectangle for free:

```cpp
const uint8_t* base = data + (size_t)cropY_ * rowStride_ + (size_t)cropX_ * 2;
```

Two paths need real work, because a cropped rectangle is not contiguous:

- **Raw16** sets `frameSizeBytes = rowStride_ * height_` and copies whole
  strided rows. Cropped, it must de-stride: a per-row copy of `cropW * 2`
  bytes, with `hdr.rowStrideBytes = cropW * 2`.
- **The compressed fallback** `job.rawCopy.assign(data, data + frameSizeBytes)`
  must likewise become a per-row copy of the cropped rect.

Setting `rowStrideBytes` to the de-strided `cropW * 2` keeps the decode side
uniform: `exporter.cpp` and the proxy renderer already read geometry from the
header, so they need no change at all.

**At 1x, no crop branch is taken.** No offset, no de-stride, no new work: the
path is byte-identical to what is on `main` and device-verified today. That is
deliberate -- it keeps the regression surface of this feature confined to
clips that actually use it.

## Preview zoom

`CONTROL_ZOOM_RATIO` on both the preview and record repeating requests, set to
the stop's actual ratio. Where `CONTROL_ZOOM_RATIO_RANGE` is not advertised,
fall back to `SCALER_CROP_REGION` with the same rectangle in active-array
coordinates.

This zooms the preview and the zebra YUV stream together, which is correct:
zebras should read the framing being shot, not a wider frame that will not be
in the file.

## Integration points

These are the places zoom leaks into existing behaviour. Each is easy to miss
and fails quietly.

- **Tap-to-focus / metering.** With a zoom ratio set, `MeteringRectangle`
  coordinates are in the zoomed coordinate system. The existing tap-to-sensor
  mapping must fold the ratio in, or metering silently lands on the wrong part
  of the frame -- worse the further you zoom.
- **`captureRateKey`.** Keyed on lens, geometry and compression today. Zoom
  changes geometry, so it must join the key; otherwise frame-budget estimates
  get read from another crop's bucket and mislead.
- **Persistence.** Zoom stop persists per lens, alongside `lensIndex` and
  `sizeIndex`, and is validated against the current ladder on restore the same
  way those are -- a stop index that no longer exists falls back to 1x.
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

**Native (ctest)** -- cropped Raw16, Packed10, Packed12 and
CompressedPredictive round-trips, each bit-exact against a reference produced
by cropping a full frame in the test itself. This is the check that would catch
a stride or origin error in the copy loops.

**On device:**

1. A 1x clip is byte-identical to one recorded before the change.
2. A 2x clip opens in Resolve with correct colour -- this is the CFA phase
   check, and the only thing that proves the origin parity rule holds end to
   end.
3. Preview framing at 2x matches the recorded frame.
4. `packMode@20 == 3` verified from the `FileHeader` struct before trusting any
   measurement, per standing project practice.

## Risks

- **RAW may not be crop-exempt on every device.** The whole design rests on the
  camera delivering full-sensor RAW while the zoom ratio is set. If some device
  crops RAW too, we would double-crop and the file would not match the preview.
  This is checked on device **before any implementation work**: record at 2x on
  the 14 Ultra and confirm the delivered RAW is still 4096x3072. If it is not,
  the crop must be conditioned on measured device behaviour and this spec needs
  revisiting.
- **Fixed 4x cap on small sensors.** A 4x crop of a smaller lens lands near
  1000px wide. The cap is deliberate and uniform across devices by decision;
  the rail's live resolution readout is what keeps the cost visible instead of
  discovered in post.
- **Aspect drift from rounding.** Alignment moves the crop's aspect by up to
  about 0.1% from the sensor's -- worst at 2.8x, where 1460x1094 is 1.3346
  against the sensor's 1.3333. That is roughly one pixel of height at these
  sizes, and preferable to an unaligned rectangle.
