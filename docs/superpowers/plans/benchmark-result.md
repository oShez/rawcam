# Storage Write Benchmark Result (Task 8)

**Date:** 2026-07-11
**Device:** Pixel (29171FDH300E2E), wireless adb, benchmark app `com.shez.rawcam`
**Method:** `NativeBridge.nativeBenchmarkWrite(<externalFilesDir>/bench.bin, 25_000_000, 240)` — 240 x 25 MB frames (~6 GB, roughly 10 s of 12 MP RAW16 @ 24 fps), plain `write()` loop through `rawcam::io::writeAll`, `fsync` inside the timed region, file deleted after each run.

## Measured sustained write throughput — clean re-run (authoritative)

After the review fixes (re-entrancy guard on the button, cleanup on the failure path) and the user freeing some storage (now 90% full, 23 GB free), three clean sequential runs with the phone idle and the app freshly launched:

| Run | MB/s (raw logcat value) |
|-----|-------------------------|
| 1   | 450.5 (`03:10:05 sustained=450 MB/s raw=450.4905998502116`) |
| 2   | 466.2 (`03:10:35 sustained=466 MB/s raw=466.249935499761`) |
| 3   | 355.7 (`03:11:08 sustained=356 MB/s raw=355.7408387918753`) |

Timestamps confirm strictly sequential, non-overlapping runs (~13-17 s of writing each, 30-33 s apart including scripting overhead). **Lowest: 355.7 MB/s. Highest: 466.2 MB/s.** Note the downward drift across back-to-back runs (SLC-cache depletion); longer recordings should expect the lower end.

## First measurement session (superseded, kept for the record)

Same build/method before the re-entrancy fix, phone in active use, storage 93% full, battery 38.7 C:

| Run | MB/s (raw logcat value) |
|-----|-------------------------|
| 1   | 39.5  (`sustained=39 MB/s raw=39.49558487819511`) |
| 2   | 207.5 (`sustained=208 MB/s raw=207.52059168984974`) |
| 3*  | 208.0 (`sustained=208 MB/s raw=207.9788083742372`) |
| 4   | 118.2 (`sustained=118 MB/s raw=118.17118260314045`) |

\* Four results were logged for three scripted button taps; runs 3 and 4 overlapped in time (an extra tap, most likely by the user who was actively using the phone). These numbers show what a busy, near-full device does to sustained writes — a realistic worst case worth remembering for the recording pipeline.

## Decision

**Gate (from spec):** lowest sustained >= ~700 MB/s -> `PackMode::Raw16`; below -> `PackMode::Packed10`.

**Result: `PackMode::Packed10`.** Clean-run lowest is 355.7 MB/s and even the best clean run (466.2 MB/s) is well below the 700 MB/s gate. Task 9 must pack the hot path with `pack10` (`frameSizeBytes = packed10Size(width*height)`, de-stride during packing).

## Caveat for later tasks

12 MP Packed10 @ 24 fps needs ~360 MB/s sustained. The clean-run range (355.7-466.2 MB/s) sits right at that requirement, and the drift across back-to-back runs plus the first session's numbers (39-208 MB/s under load, near-full storage) show real-world conditions can fall far below it. Full-res 24 fps recording will need headroom strategies — deep RAM buffering, free-space checks, and/or reduced resolution/framerate modes. This does not change the Task 8 gate outcome (Packed10 is the only viable choice of the two), but it must be revisited when the recording pipeline is integrated.
