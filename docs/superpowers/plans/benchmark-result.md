# Storage Write Benchmark Result (Task 8)

**Date:** 2026-07-11
**Device:** Pixel (29171FDH300E2E), wireless adb, benchmark app `com.shez.rawcam`
**Method:** `NativeBridge.nativeBenchmarkWrite(<externalFilesDir>/bench.bin, 25_000_000, 240)` — 240 x 25 MB frames (~6 GB, roughly 10 s of 12 MP RAW16 @ 24 fps), plain `write()` loop through `rawcam::io::writeAll`, `fsync` inside the timed region, file deleted after each run.

## Measured sustained write throughput

| Run | MB/s (raw logcat value) |
|-----|-------------------------|
| 1   | 39.5  (`sustained=39 MB/s raw=39.49558487819511`) |
| 2   | 207.5 (`sustained=208 MB/s raw=207.52059168984974`) |
| 3*  | 208.0 (`sustained=208 MB/s raw=207.9788083742372`) |
| 4   | 118.2 (`sustained=118 MB/s raw=118.17118260314045`) |

\* Four results were logged for three scripted button taps; runs 3 and 4 overlapped in time (an extra tap, most likely by the user who was actively using the phone during the benchmark). All four are real device measurements.

**Lowest: 39.5 MB/s. Highest: 208 MB/s.**

Conditions worth noting: internal storage was 93% full (18 GB free of 229 GB), battery temp 38.7 C, and the phone was in active use (Instagram foregrounded mid-run once). These depress and destabilize sustained writes — but they are also realistic recording conditions.

## Decision

**Gate (from spec):** lowest sustained >= ~700 MB/s -> `PackMode::Raw16`; below -> `PackMode::Packed10`.

**Result: `PackMode::Packed10`.** Even the best run (208 MB/s) is ~3.4x below the gate; the lowest (39.5 MB/s) is ~18x below. Task 9 must pack the hot path with `pack10` (`frameSizeBytes = packed10Size(width*height)`, de-stride during packing).

## Caveat for later tasks

12 MP Packed10 @ 24 fps still needs ~360 MB/s sustained, which the measured range (39-208 MB/s) does not reliably deliver on this device in its current state (93% full). Full-res 24 fps recording may additionally require lower resolution/framerate modes, a deeper RAM buffer, or freeing device storage. This does not change the Task 8 gate outcome (Packed10 is the only viable choice of the two), but it should be revisited when the recording pipeline is integrated.
