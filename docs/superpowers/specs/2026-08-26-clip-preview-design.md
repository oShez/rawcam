# Clip preview: pre-rendered proxy frames

**Date:** 2026-08-26
**Status:** approved design, not yet planned
**Supersedes:** the on-demand decode design first committed at this path
(270e767), which decoded frames live while the user scrubbed.

## Problem

The Clips screen is text only. A row shows a filename, a duration, a size and
three buttons, so the only way to find out what is in a take is to export it to
DNG and open it somewhere else. With takes that run to tens of gigabytes, that
is an expensive way to answer "which one was the good one?".

## Approach

Decode roughly every fifth frame of a clip **once, in the background, after
recording**, and write each one as a small JPEG. The viewer then flips through
those JPEGs in order. Nothing is developed while the user is looking at it.

This is what makes the feature safe. Rice decoding is sequential across 12.6 M
samples and cannot be subsampled, so a design that decodes during a drag stakes
its usability on a per-frame latency nobody has measured. Pre-rendering removes
that bet: the job takes as long as it takes, off the interaction path, and
viewing is then just loading small JPEGs.

Playback is deliberately choppy. Every fifth frame of 24 fps material is 4.8 fps
-- enough to see what happened in a take, which is the entire goal.

## Goals

- Every clip shows a poster thumbnail in its Clips row.
- Tapping a clip opens a viewer that plays through the proxy frames in order and
  can be scrubbed by hand.
- A clip whose proxies are still being generated says so, with progress, rather
  than appearing broken or empty.
- Works for every pack mode -- Raw16, Packed10, Packed12, CompressedPredictive
  -- and every lens, since geometry and CFA order come from the clip's header.

## Non-goals

- Real-time playback at capture frame rate.
- Colour accuracy. This answers "which take is this", not "is this graded".
  DNG export remains the path to real colour.
- Audio during preview playback.
- Editing, trimming, or exporting a frame from the viewer.

## Sampling and cost

Default stride 5, i.e. 20% of frames, 4.8 fps at 24 fps capture.

| Take | Frames | Proxies at stride 5 | Approx. disk |
|---|---|---|---|
| 6 s | 152 | 30 | ~4 MB |
| 40 s | 960 | 192 | ~23 MB |
| 10 min | 14,400 | 2,880 | ~350 MB |

At 1024x768, JPEG quality 80, a proxy is roughly 120 KB.

350 MB for one long take is too much, so **the stride grows to hold the proxy
count at or below 1200 per clip**:

```
stride = max(5, ceil(frameCount / 1200))
indices = 0, stride, 2*stride, ... while index < frameCount
```

Under about 4 minutes nothing changes and the stride stays 5; beyond that the
preview gets progressively coarser instead of progressively more expensive. A
10-minute take samples every 12th frame and costs ~145 MB.

**Sampling always spans the whole clip.** The cap raises the stride; it never
truncates the range. A 14,400-frame take yields 1200 proxies reaching frame
14,388, not the first 1200 frames. The last sampled index is always within one
stride of the end.

Generation itself walks those indices in chronological order, so an interrupted
run leaves a set covering the clip from its start up to wherever it stopped --
which is what the viewer plays while the rest arrives.

## Architecture

### Develop pipeline (new, native)

Still required -- turning a Bayer payload into RGB is the same work regardless of
when it runs. `core/include/rawcam/preview.h`, `core/src/preview.cpp`:

```cpp
// Develops frame `index` into `out` as RGBA8, downscaled to fit within
// maxW x maxH while preserving aspect. Returns false if the frame cannot be
// read or the header is unusable (whiteLevel == 0 on a packed/compressed clip).
bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH,
                  std::vector<uint8_t>& out, uint32_t* outW, uint32_t* outH);
```

Stages: `readFrame` -> unpack by mode (`unpack10` / `unpack12` / `decodeFrame`,
exactly as `exportFrame()` does, **including the `meta.compressed == 0`
stored-fallback path**, which is plain RAW16 and must not reach the Rice
decoder) -> subtract `blackLevel[quad]`, clamp at zero, scale to `whiteLevel` ->
multiply by `asShotNeutral` -> 2x2 CFA bin -> sRGB gamma -> box downscale.

2x2 binning rather than an interpolating demosaic: each Bayer quad becomes one
RGB pixel, R and B taken directly and the two greens averaged, 4096x3072 to
2048x1536 in one pass. At proxy size the output is downscaled again anyway, so
interpolation would cost more and show nothing.

`RawvReader` already random-accesses frames -- `offsets_` is built at `open()`
by chaining `FrameMeta.payloadBytes`, which is what makes variable-stride
compressed frames addressable. Seeking is solved; only development is new.

### JNI surface

```kotlin
external fun nativeOpenClip(path: String): Long        // 0 on failure
external fun nativeClipFrameCount(handle: Long): Long
external fun nativeDecodeFrame(handle: Long, index: Long, maxW: Int, maxH: Int): IntArray?
external fun nativeCloseClip(handle: Long)
```

The handle owns a `RawvReader` so the offset index is built once per clip, not
once per frame. The generator opens a clip, loops strided indices, closes.
JPEG encoding stays in Kotlin via `Bitmap.compress` -- Android's encoder is
already there, and the per-frame `IntArray` copy is irrelevant inside a
background job.

### Proxy store

```
cacheDir/proxies/<clipName>/000000.jpg, 000001.jpg, ...
cacheDir/proxies/<clipName>/index.json   {stride, sourceFrames, proxyCount, complete}
```

Files are numbered by proxy ordinal, not source frame; `stride` maps back when
the viewer shows a frame number. `complete` distinguishes "finished" from
"interrupted", and a partial set is resumable by counting files already present.

`cacheDir` (decided), so the OS can reclaim it under storage pressure and
nothing is corrupted by its loss -- proxies regenerate. The accepted cost is
that reclaiming a long take's proxies means minutes of regeneration; `filesDir`
would avoid that but needs its own management UI, which this does not build.

### Generation service

`export/PreviewService.kt`, modelled on the existing `ExportService`: a started
service with a progress notification, queueing one clip at a time.

**It must not run while recording.** Developing frames is CPU-heavy and this
project has spent five optimisation rounds defending the capture path's landing
rate; a proxy job competing with an active take would undo that. The service
defers while `recording || busy` and resumes at stop.

Triggered when a recording stops, and on demand from the Clips screen for clips
that predate the feature or whose cache was reclaimed.

### UI

- `ClipsScreen.kt`: thumbnail column per row -- proxy 0, or a pending/progress
  chip while generating. Row becomes tappable.
- `ui/ClipViewerScreen.kt` (new): the frame, a play/pause control at 4.8 fps, a
  scrub bar, and a frame counter showing the source frame number
  (`proxyIndex * stride`).
- Pending state: viewer shows "Preparing preview -- 42 / 192" and starts playing
  what exists as it arrives, rather than blocking until complete.
- `MainActivity.kt`: a `Screen.ClipViewer` route, gated by the same `locked`
  rule that already blocks navigation while recording.

## Error handling

- Unreadable or truncated clip: `nativeOpenClip` returns 0, generation records
  the failure, the row shows "Preview unavailable". Must survive a clip whose
  recording was interrupted -- `frameCount == 0` already means "recover by scan"
  elsewhere in this codebase.
- `developFrame` returns false rather than emitting garbage when the header
  cannot support development (`whiteLevel == 0` on a packed or compressed clip).
- Generation interrupted by process death: `complete` stays false, the next run
  resumes from the highest numbered file present.
- Deleting a clip deletes its proxy directory; an orphaned directory is removed
  on the next Clips load.
- A full disk during generation fails that clip only, leaving the partial set.

## Testing

Host `ctest`, following `test_pack10` / `test_dng_writer`:

- `core/tests/test_preview.cpp`: synthetic Bayer in, known RGBA out. All four
  CFA orders; black-level subtraction including a clamp-at-zero case;
  `asShotNeutral` application; downscale geometry including odd dimensions.
- Equivalence: a Packed12 clip and a CompressedPredictive clip of identical
  pixels must develop to identical RGB.
- Stride selection: stride stays 5 below the cap threshold; a 14,400-frame clip
  yields <= 1200 proxies whose last sampled index is within one stride of the
  final frame, proving the cap raises stride rather than truncating the range.

On device: a clip per lens, compressed and uncompressed; generation while a
second recording is started (must defer); viewer opened mid-generation (must
show progress and play what exists).

## Files

| File | Change |
|---|---|
| `core/include/rawcam/preview.h` | new |
| `core/src/preview.cpp` | new |
| `core/tests/test_preview.cpp` | new |
| `core/CMakeLists.txt` | add sources and test |
| `app/src/main/cpp/jni_bridge.cpp` | four externs |
| `NativeBridge.kt` | four declarations |
| `export/PreviewService.kt` | new -- background generation |
| `ui/ClipProxies.kt` | new -- store layout, index.json, resume/cleanup |
| `ui/ClipViewerScreen.kt` | new -- playback and scrub |
| `ui/ClipsScreen.kt` | thumbnail column, pending chip, tap target |
| `MainActivity.kt` | viewer route |
| `ui/RecordScreen.kt` | enqueue generation on stop |
