# Clip Preview -- Outcome and Open Items

**Date:** 2026-08-27
**Spec:** `docs/superpowers/specs/2026-08-26-clip-preview-design.md`
**Plan:** `docs/superpowers/plans/2026-08-26-clip-preview.md` (10 tasks)
**Status:** Implemented, merged to `main`, device-verified, and pushed to
`origin/main` on 2026-08-27 (`800f1b6`). Open items below.

## What shipped

All 10 plan tasks executed on branch `feat/clip-preview`, fast-forward merged into
`main` (12 commits, `1ea8207..27dc01a`, branch deleted), then a follow-up scrub fix
(`800f1b6`). Local and remote are in sync.

New C++ in `core/`:

- `preview.h` / `preview.cpp` -- `developRaw16` (unpack -> black level -> channel
  gains -> 2x2 CFA bin -> sRGB gamma), `downscaleTo` (box average to a bounding
  box, never upscales), and `developFrame` (reads a frame through `RawvReader`,
  unpacks per the clip's pack mode, develops, downscales).
- `test_preview.cpp` -- 18 doctest cases.

New Kotlin package `app/src/main/java/com/shez/rawcam/preview/`:

- `ProxyStore.kt` -- store layout and the sampling contract, deliberately free of
  Android lifecycle so the arithmetic is JVM-unit-testable.
- `PreviewService.kt` -- foreground service, single-thread executor, renders JPEG
  proxies and a progress notification.

Plus `ClipViewerScreen.kt` (playback + scrub), thumbnails and pending state in
`ClipsScreen.kt`, four JNI entry points, and a `Screen.ClipViewer` route.

**Sampling contract:** `stride = max(5, ceil(frameCount / 1200))`, indices
`0, stride, 2*stride, ...` while `index < frameCount`. Above the cap the stride
RISES so the count stays bounded -- the sampled range still spans the whole take,
it just gets coarser. It never truncates the clip. Proxies are 1024x768 max, JPEG
quality 80, in `cacheDir/proxies/<clipName>/%06d.jpg` beside an `index.json` of
`{stride, sourceFrames, proxyCount, complete}`.

**Deferral from the capture path** comes from only ever enqueuing generation on a
successful record-stop (`RecordScreen.kt`). `PreviewService` does NOT check
`recording || busy` itself. If a manual "generate preview" action is ever added to
the Clips screen, that action must gate on recording state itself.

## Two plan defects, corrected during implementation

Both in Task 3, both verified against `core/src/exporter.cpp:24-45` before coding:

1. The plan's `decodeFrame(...)` call had the wrong argument order and passed
   `whiteLevel` where the real signature wants a bit depth. Real signature:
   `decodeFrame(compressed, compressedSize, out, width, height, rowStrideSamples, bitDepth)`,
   with `bitDepth = 32 - __builtin_clz(whiteLevel)`.
2. A `CompressedPredictive` frame decodes into the ORIGINAL, possibly
   stride-padded layout, so the target must be `frameSizeBytes / 2` samples with
   `rowStrideSamples = rowStrideBytes / 2` -- not `pixelCount` at stride `width`.

## A real bug found by looking at the output (`ef1d2db`)

The first generated proxy came out strongly green-cast. Two defects behind it:

- **`FileHeader.asShotNeutral` is dead.** `app/src/main/cpp/capture.cpp:384`
  hardcodes it to `0,0,0` and never revisits it. The live per-frame AWB estimate
  is `FrameMeta.wbNeutral`, which `core/src/dng_writer.cpp:167` already emits as
  DNG `AsShotNeutral` (tag 50728). `developFrame` now prefers the frame's own
  value and keeps the header field only as a fallback.
- **`AsShotNeutral` is a divisor, not a gain.** It is the camera-space coordinate
  of a neutral subject, so the correcting gain is its RECIPROCAL; multiplying by
  it deepens the cast. `developRaw16`'s parameter is now named `channelGains` to
  say what it is, and `developFrame` inverts, normalising against green so red and
  blue are lifted rather than green pulled down (which preserves exposure).

Evidence, one clip, same subject and lighting, differing only in whether the source
frame carried a `wbNeutral`: frame 0 (none) rendered R/G 0.53, B/G 0.46; frame 25
(`0.466, 1.0, 0.465` -> gains `2.15, 1.0, 2.15`) rendered R/G 1.01, B/G 0.85.

Note this is a *white balance* only -- no colour matrix is applied, by the spec's
design, so hues are approximate.

## Device verification (Xiaomi 14 Ultra, 24030PN60G)

All six items of the plan's Task 10 matrix:

1. **Compressed clip** -- `packMode` read by parsing the `FileHeader` struct at
   offset 20 (NOT by eyeballing adjacent words; the `3` at offset 24 is CFA) reads
   `3`. The Rice decode path renders a sharp, correctly framed image.
2. **Uncompressed clip** (`packMode` 0), same lens -- matches the compressed one in
   framing and balance: R/G 1.01 vs 1.00, B/G 0.85 vs 0.88.
3. **Second lens (12mm)** -- 593- and 168-frame clips, correct geometry, no CFA or
   stride artefacts. Preview orientation matches the live viewfinder.
4. **Viewer opened mid-generation** -- played immediately and advanced f=50 -> f=90
   while frames were still landing, without blocking.
5. **Deleting a clip** -- its proxy directory was pruned on the next Clips load, the
   other four untouched.
6. **Recording during generation** -- 125 frames, `DROPPED` 0, with proxies
   115 -> 120 written across the same window.

**Throughput: a steady 2.2 proxies/s** (11 per 5s) on 4096x3072 14-bit compressed
material. At that rate the 1200-proxy cap is ~9 minutes of generation for a maximal
take -- the number to revisit if long takes need to become viewable sooner.

## Scrub smoothness and play head (`800f1b6`)

Reported after the merge: scrubbing was jittery under a finger. Root cause was that
`ClipViewerScreen` decoded a 1024x768 proxy JPEG *inside composition*
(`remember(index) { BitmapFactory.decodeFile(..) }`), so every pointer event during
a drag blocked the very frame it was meant to draw and allocated a fresh ~3MB
bitmap -- the faster the finger moved, the worse it got. Decoding now runs on
`Dispatchers.IO` behind `snapshotFlow` + `collectLatest` (a decode the finger has
already moved past is dropped, not queued), backed by a 64MB `LruCache` (~21
frames). The bar itself is pure layout, so it tracks the finger at full frame rate
even when the image is a frame behind it.

Two lesser contributors that also read as jitter: `detectHorizontalDragGestures`
imposes touch slop, so the opening few pixels of every drag did nothing and the
position then jumped to catch up (now handled via `awaitEachGesture`, which also
makes a plain tap seek); and the seek truncated instead of rounding.

Measured with `dumpsys gfxinfo` across identical scripted drags, old build then new
build back to back, same clip and coordinates:

|                    | old        | new        |
| ------------------ | ---------- | ---------- |
| janky frames       | 28 (4.17%) | 14 (1.89%) |
| 90th percentile    | 27ms       | 22ms       |
| 95th percentile    | 29ms       | 24ms       |
| frames (same time) | 672        | 740        |

Play head: a 3dp jade line the full height of the track, centred on the position
and clamped at both ends so it never half-clips. Track raised 36dp -> 48dp (it was
under the minimum touch target). `ClipViewerScrubTest` pins the seek arithmetic in
7 JVM cases; two of them fail against the old truncating expression.

## Open items

1. **Early frames of every take carry zeroed `FrameMeta`.** Pre-existing and
   capture-side, not introduced here: the startup race documented in
   `CameraController.onCaptureCompleted`'s own comment leaves the first ~5+ frames
   with `iso` / `exposureNs` / `focusDistance` / `wbNeutral` all zero. Two
   consequences: those frames' previews render green-cast, and because the Clips
   poster is proxy ordinal 0 = source frame 0, **every poster is the
   worst-balanced frame in its clip**. A one-line mitigation is to pick a later
   ordinal as the poster. It also means those frames' exported DNGs get
   `AsShotNeutral 1,1,1` via `dng_writer`'s fallback -- so this affects export,
   not just preview.
2. **Scrub decode could go further.** The LRU holds ~21 frames; a longer clip has
   more, so a full-width drag still re-decodes. Decoding at half resolution while
   the finger is down (full resolution on release) would cut decode time and
   allocation roughly fourfold. Not built: the cold-vs-warm-cache measurement that
   would confirm decode is still the dominant remaining cost was cut short.
3. **The viewer assumes 24fps** when deriving its playback interval. Every clip
   this app records is 24 or 30fps and the visible consequence of guessing wrong is
   playback 25% fast, not a correctness bug. Reading the real rate via
   `nativeClipInfo`, or extending `ProxyIndex` to carry fps, is a one-line upgrade.
4. **`downscaleTo` does not guard against `out` aliasing `src`.** Calling
   `downscaleTo(img, w, h, &img)` would clobber the source via `out->rgba.assign`.
   No caller does this and no test exercises it.
5. **~10GB of test clips** left in the `.debug` app's clips directory on the
   Xiaomi. Clearable from the app's own CLIPS screen.

## Device-drive notes worth keeping

- **The record button moves when recording.** Idle centre ~(2214,738),
  recording/stop centre ~(2214,510) at 2400x1080. A stop tap at the idle coordinate
  silently misses and the take keeps rolling -- this cost two runaway multi-GB takes
  before it was spotted.
- `run-as` works only on the `.debug` build; the release APK is not debuggable, so
  `cacheDir` is unreachable there.
- Pull binaries with `adb exec-out`, never `adb shell cat` (shell mode mangles them
  with CRLF).
- 'Compress recordings' had reverted to OFF again. Verify `packMode` by parsing the
  header struct before trusting any encoder-related measurement.
- For UI smoothness, `dumpsys gfxinfo <pkg> reset` + a scripted `input swipe` +
  reading back the jank percentiles turns "feels smoother" into a number -- but the
  OLD build has to be measured BEFORE the new one is installed.
