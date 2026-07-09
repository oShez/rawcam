# RawCam — RAW Video Capture for Pixel — Design

**Date:** 2026-07-09
**Status:** Approved design, pending implementation plan

## What we're building

An Android app that records RAW video — unprocessed Bayer sensor frames straight
off the camera image sensor, before any of the phone's image processing — and
exports it as CinemaDNG for grading in DaVinci Resolve. Functionally similar to
MotionCam Pro's core recording feature.

**Target device:** the user's Pixel (primary and only v1 target). Capabilities
are queried from its Camera2 stack; no cross-device support burden in v1.

**V1 success criteria:** record a 10–30 second clip of full-resolution RAW at
24/30 fps with locked manual exposure, export it to a CinemaDNG folder, open
and grade it in DaVinci Resolve. Frame count matches duration × fps (drops are
allowed but must be counted and reported honestly).

## Why this shape

One number drives the whole design: a ~12MP sensor in 16-bit RAW is ~24MB per
frame, so 24 fps is **~580MB/s**. That is:

- too much to buffer in RAM for more than a few seconds,
- too much to write as individual DNG files in real time (DNG creation is slow),
- within reach of modern Pixel UFS sequential write speeds (~700MB/s–1GB/s),
  but tight.

Therefore: **record into one fast sequential container file during capture;
convert to CinemaDNG afterwards as a separate export step.**

The user chose an **NDK/C++ core from day one** (over a Kotlin-only pipeline or
building on MotionCam's open-source core) for maximum performance headroom —
this is roughly MotionCam Pro's own architecture.

## Architecture

Two layers, one JNI boundary.

### Kotlin/Compose layer (UI + camera control)

- **Preview screen** — live viewfinder, record button, recording timer,
  dropped-frame counter.
- **Manual controls** — ISO and shutter-speed sliders with AE fully off
  (`CONTROL_AE_MODE_OFF`, `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`), manual
  focus slider (`LENS_FOCUS_DISTANCE`). White balance stays auto but is only
  *recorded* as metadata (`AsShotNeutral` starting point) — RAW defers WB to post.
- **Camera2 session manager** — opens the camera, configures two streams:
  a preview Surface for the screen and the RAW stream whose Surface comes from
  the native layer's `AImageReader`. Issues capture requests with locked manual
  values. Forwards each frame's `CaptureResult` metadata down via JNI.
- **Clips library + export screen** — list recordings, trigger CinemaDNG export
  with progress. Export runs as a foreground service so it survives backgrounding.

### C++ core (the hot path), via JNI

- **Capture module** — owns an `AImageReader` in `RAW16` format with a pool of
  ~8–16 hardware buffers. The native image-available callback acquires each
  frame with zero copies (holds the hardware buffer itself).
- **Lock-free frame queue** — hands acquired frames to the writer.
- **Writer thread** — streams frames sequentially into one preallocated
  container file using large aligned `pwrite` calls on a dedicated thread.
  (Note: `io_uring` is blocked for untrusted apps by Android security policy,
  so plain syscalls it is.)
- **Container format (`.rawv`)** — see below.
- **DNG exporter** — a minimal TIFF/DNG writer we control (no Adobe SDK
  dependency). Walks a `.rawv` and emits `clipname_000001.dng…` into a folder
  Resolve opens as a CinemaDNG sequence.

### Storage

Recordings and exports land in the app's external-files directory
(`Android/data/<pkg>/files/`), visible over USB/MTP for copying to the desktop.
No storage permissions needed.

## Data flow

### Recording (hot path)

1. Camera HAL fills a RAW16 hardware buffer → `AImageReader` callback fires on
   a native thread.
2. Callback acquires the `AImage` (no copy) and pushes it onto the lock-free queue.
3. Writer thread pops, writes the frame record to the container file, releases
   the buffer back to the pool.
4. In parallel, Kotlin receives each frame's `CaptureResult` and sends
   `{sensor timestamp, ISO, exposure time, focus distance, WB estimate}` down
   via JNI; the core matches it to the written frame by sensor timestamp and
   stores it in the frame index.

**Backpressure rule:** if all pool buffers are in flight (writer behind), the
callback releases the newest frame immediately — a counted drop. The camera
never stalls; recording never corrupts; the UI shows the drop count honestly.

### Performance budget & the benchmark-first rule

The first thing built on-device is a **benchmark mode**: the core writes
synthetic frames flat-out and reports sustained storage bandwidth on the actual
target Pixel. That measured number decides:

- **≥ ~700MB/s sustained** → v1 records RAW16 exactly as delivered (no CPU in
  the hot path).
- **Below that** → add 16→10-bit packing in the hot path (the sensor produces
  ~10 useful bits; packing cuts the rate to ~360MB/s at the cost of CPU).

Measure first; don't guess.

### Frame rate & exposure rules

- With AE off, frame rate is set via sensor frame duration.
- The UI offers only frame rates the sensor's RAW stream configuration actually
  supports (queried from `StreamConfigurationMap`), likely 24/30.
- The shutter slider is clamped to the frame interval (can't expose 1/24s at 30fps).

### Export path (not time-critical)

Foreground service reads the `.rawv`, emits one DNG per frame using header
metadata + per-frame index, reports progress to the UI.

## Container format (`.rawv`)

- **Header** — everything DNG needs later: CFA pattern, black/white levels,
  color matrices, sensor active array size, resolution, bit depth/packing mode,
  frame rate, device identity.
- **Frame records** — fixed size, appended sequentially: frame payload +
  per-frame metadata slot (timestamp, ISO, exposure, focus, WB estimate,
  drop-count-so-far).
- **Frame index** — written at finalize.

Fixed-size records are a deliberate crash-safety choice: a truncated file
(crash, battery pull) is recoverable by scanning, losing at most the final
partial frame.

## Error handling

- **Storage full** — pre-record free-space check shows max recordable duration;
  during recording, stop cleanly at a low-water mark and finalize.
- **Crash / battery pull mid-recording** — exporter rebuilds the index by
  scanning fixed-size records.
- **Camera disconnect / app backgrounded** — stop and finalize; never leave an
  unreadable file.
- **Thermal throttling** — subscribe to Android thermal status callbacks and
  surface a warning; sustained RAW capture runs hot.
- **Dropped frames** — always counted, shown live in the UI, and recorded in
  clip metadata so a compromised take is known before the edit.

## Testing

- **C++ core tests run on the desktop, not the phone** — the container
  writer/reader, index recovery from truncated files, and the DNG writer all
  compile for host and run as plain unit tests.
- **DNG correctness** — validated by desktop tools (exiftool / dcraw / rawpy)
  parsing exporter output; final authority is Resolve opening and grading a
  sequence.
- **Benchmark mode on-device** — doubles as the storage regression test.
- **End-to-end manual check per milestone** — record 10s on the Pixel, verify
  frame count ≈ duration × fps, export, open in Resolve, confirm it grades.

## Development environment notes

- Android SDK already installed at `AppData\Local\Android\Sdk` (platforms 34/35,
  build-tools); Kotlin/Compose Gradle caches present from prior projects.
  NDK + CMake must be added via SDK manager.
- The Pixel's USB connection is known to be flaky — set up **wireless adb**
  early; deploying and pulling multi-GB clips over a bad cable is not viable.

## Out of scope for v1 (explicitly deferred)

- Long/continuous recordings (requires compression + sustained-throughput work)
- Lossless RAW compression
- Audio recording
- On-device playback/develop of RAW footage
- Broad device support beyond the target Pixel
- Zebras, histograms, focus peaking, and the full cinema control surface
- White-balance control (RAW makes it a post decision)
