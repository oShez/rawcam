# Clip preview: poster thumbnails and a scrubbable viewer

**Date:** 2026-08-26
**Status:** approved design, not yet planned

## Problem

The Clips screen is text only. A row shows a filename, a duration, a size and
three buttons, so the only way to find out what is in a take is to export it to
DNG and open it somewhere else. With takes that run to tens of gigabytes, that
is an expensive way to answer "which one was the good one?".

## Goals

- Every clip shows a poster thumbnail in its Clips row.
- Tapping a clip opens a viewer: one large developed frame, plus a scrub bar
  that moves through the take frame by frame.
- Works for every pack mode the app writes -- Raw16, Packed10, Packed12 and
  CompressedPredictive -- and for every lens, since geometry and CFA order come
  from the clip's own header.

## Non-goals

- Playback at frame rate. Scrubbing is driven by the finger, not a clock. No
  transport controls, no audio.
- Colour accuracy. This is a "which take is this" preview, not a grading view.
  DNG export remains the path to real colour.
- Editing, trimming, or exporting a single frame from the viewer.

## Why this is not a decoder project

`RawvReader` already provides random access:

```cpp
static std::unique_ptr<RawvReader> open(const std::string& path);
uint64_t frameCount() const;
bool readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload);
```

`offsets_` is built once at `open()` by scanning records via
`FrameMeta.payloadBytes`, which is what makes variable-stride
CompressedPredictive frames addressable at all. `exporter.cpp` already depends
on this. So seeking is solved; what is missing is only the step that turns a
Bayer payload into something displayable.

## Architecture

### Develop pipeline (new, native)

`core/include/rawcam/preview.h`, `core/src/preview.cpp`:

```cpp
// Develops frame `index` into `out` as RGBA8, downscaled to fit within
// maxW x maxH while preserving aspect. Returns false if the frame cannot be
// read or the header is unusable (whiteLevel == 0 on a packed/compressed clip).
bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH,
                  std::vector<uint8_t>& out, uint32_t* outW, uint32_t* outH);
```

Stages, in order:

1. `readFrame(index, &meta, payload)`.
2. Unpack to RAW16 by mode -- reuse `unpack10` / `unpack12` /
   `decodeFrame`, exactly as `exportFrame()` does, including the
   `meta.compressed == 0` stored-fallback path that must be treated as plain
   RAW16 rather than fed to the Rice decoder.
3. Subtract `blackLevel[quad]` per CFA quadrant, clamp at zero, scale to
   `whiteLevel`.
4. Multiply by `asShotNeutral` so the preview is roughly neutral rather than
   green.
5. Demosaic by 2x2 CFA binning: each Bayer quad becomes one RGB pixel, taking R
   and B directly and averaging the two greens. `cfa` from the header decides
   which corner is which. 4096x3072 becomes 2048x1536 in a single pass.
6. sRGB gamma.
7. Box downscale to fit maxW x maxH.

Binning rather than interpolating is deliberate: at a viewer width around 1000px
the output is downscaled by 2x again anyway, so an interpolating demosaic would
cost more and show nothing. It also sidesteps edge artefacts entirely.

### JNI surface

`jni_bridge.cpp` and `NativeBridge.kt`:

```kotlin
external fun nativeOpenClip(path: String): Long        // 0 on failure
external fun nativeClipFrameCount(handle: Long): Long
external fun nativeDecodeFrame(handle: Long, index: Long, maxW: Int, maxH: Int): IntArray?
external fun nativeCloseClip(handle: Long)
```

The handle owns a `RawvReader`. It exists because `open()` builds the offset
index by scanning the whole file: opening per frame would make every scrub step
pay for a full-file scan. Open once when the viewer is entered, close when it
leaves.

`nativeDecodeFrame` returns ARGB_8888 ints for `Bitmap.createBitmap(...)`. At
1024x768 that is 786 KB per call -- one copy that buys a much smaller JNI
surface than locking an Android `Bitmap` from native.

### Poster thumbnails

`ui/ClipThumbnails.kt`: JPEG at `cacheDir/thumbs/<clipName>.jpg`, roughly 320px
wide, developed from the clip's **middle** frame.

Middle, not first: the opening frames of a take are routinely dark or
motion-blurred -- the A/V clap test on 2026-08-24 had f38 blurred and f39 sharp.
Seeking to the middle costs one lookup in an index that already exists.

Generated when a recording stops, and lazily on first view for clips that
predate the feature. Production goes through the same JNI as the viewer --
`nativeDecodeFrame` at thumbnail size, then `Bitmap.compress(JPEG)` -- so there
is one develop path, not two.

`cacheDir` rather than a sidecar next to the `.rawv`, because a sidecar would
have to be threaded through the delete-pairing, share and export paths the way
the `.wav` sidecar is, and would clutter a directory the user browses. A cache
entry whose clip no longer exists is deleted on the next Clips load; the cache
is disposable by definition, so nothing else has to react to it going missing.

### UI

- `ClipsScreen.kt`: a thumbnail column on each row; the row becomes tappable.
- `ui/ClipViewerScreen.kt` (new): large frame, scrub bar, frame counter
  ("f=38 / 152"), back.
- `MainActivity.kt`: a `Screen.ClipViewer` entry, gated by the same `locked`
  rule that already blocks navigation while recording.

### Scrub concurrency

One decode job at a time, conflated. A drag produces touch events far faster
than frames can be developed, so requests collapse to the newest index: the
in-flight decode is left to finish (cancelling mid-Rice-decode buys nothing),
its result is discarded if a newer index has been requested since, and the last
decoded frame stays on screen meanwhile. No decode queue, no per-event job.

## Error handling

- `nativeOpenClip` returns 0 for an unreadable or truncated file; the viewer
  shows "Cannot read clip" and offers back. It must not crash on a clip whose
  recording was interrupted -- `frameCount == 0` in the header already means
  "recover by scan" elsewhere in the codebase.
- `developFrame` returns false rather than producing garbage when the header
  cannot support development (`whiteLevel == 0` on a packed or compressed clip).
- A missing or unwritable thumbnail cache degrades to no thumbnail, never to a
  failed list render.

## Testing

Host `ctest`, following `test_pack10` / `test_dng_writer`:

- `core/tests/test_preview.cpp`: synthetic Bayer input with known values ->
  expected RGBA out. Covers all four CFA orders, black-level subtraction
  including a clamp-at-zero case, `asShotNeutral` application, and downscale
  geometry (aspect preservation, odd dimensions).
- Each pack mode reaches the same RGB from equivalent input: a Packed12 clip and
  a CompressedPredictive clip of identical pixels must develop identically.

On-device: a clip from each lens, compressed and uncompressed, opened and
scrubbed end to end.

## Risk: per-frame decode latency

Unmeasured, and it decides whether the design survives. Rice decoding is
sequential across 12.6 M samples and cannot be subsampled -- there is no way to
decode "just a downscaled version" of a compressed frame.

- around 50-100 ms: scrubbing feels responsive with conflation. Design stands.
- around 400 ms or worse: scrubbing needs rethinking -- a decimated scrub that
  only lands on every Nth frame, or a proxy strip of small JPEGs written at
  record time.

**The first task in the implementation plan is measuring this**, on device,
using the existing `benchmark.cpp` harness, before any UI work begins. Binning
during the unpack pass rather than after it is the obvious first optimisation if
the number is marginal, since it cuts memory traffic without touching decode
cost.

## Files

| File | Change |
|---|---|
| `core/include/rawcam/preview.h` | new |
| `core/src/preview.cpp` | new |
| `core/tests/test_preview.cpp` | new |
| `core/CMakeLists.txt` | add sources and test |
| `app/src/main/cpp/jni_bridge.cpp` | four externs |
| `NativeBridge.kt` | four declarations |
| `ui/ClipViewerScreen.kt` | new |
| `ui/ClipThumbnails.kt` | new |
| `ui/ClipsScreen.kt` | thumbnail column, tap target |
| `MainActivity.kt` | viewer route |
| `ui/RecordScreen.kt` | generate poster on stop |
