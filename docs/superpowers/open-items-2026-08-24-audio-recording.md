# Audio Recording -- Outcome and Open Items

**Date:** 2026-08-24
**Spec:** `docs/superpowers/specs/2026-08-17-audio-recording-design.md` (commit `29977b1`)
**Plan:** `docs/superpowers/plans/2026-08-17-audio-recording.md` (commit `1f5b267`, 11 TDD tasks)
**Status:** Implemented, reviewed, merged to `main`, device-verified, and pushed to
`origin/main` on 2026-08-25. Open items below.

## What shipped

All 11 plan tasks were executed via subagent-driven development on branch
`audio-recording`, then merged locally into `main`. Pushed to `origin/main` on
2026-08-25 (`f1d6557`); the standing do-not-push-without-explicit-OK constraint on this
repo has been lifted by the user and `main` is now in sync with the remote.

New Kotlin package `app/src/main/java/com/shez/rawcam/audio/`:

- `AvSync.kt` -- pure clock/trim/drift math with no Android imports, so it is
  unit-testable on the JVM. Holds `planPrerollTrim` and the least-squares drift fit.
- `WavWriter.kt` -- streaming RIFF/WAVE 24-bit little-endian PCM writer with a
  602-byte BWF `bext` chunk (`HEADER_BYTES = 654`), sizes patched on finalize.
- `AudioRecorder.kt` -- owns `AudioRecord`, the read/write thread pair, the status
  bitfield, and the preroll trim.

`.rawv` bumped v4 -> v5. The audio block is **104 bytes** carved from `reserved[284]`,
leaving `reserved[180]`. The plan stated 184; that was a plan defect, caught during
implementation and re-verified against the `static_assert(sizeof(FileHeader) ==
kHeaderSize)` in `core/include/rawcam/rawv.h`.

`core/src/rawv_reader.cpp` no longer gates on strict `h.version != kVersion`. It now
accepts the range `kMinReadableVersion (4) .. kVersion (5)`. This was the trap called
out during planning: bumping the version without relaxing the gate would have made
every `.rawv` clip already on a user's device unreadable.

`app/src/main/cpp/capture.cpp` took exactly one edit -- a `setAudioInfo()` snapshot
read in `stop()`, before `finalize()`. The per-frame path (`writerLoop`,
`processImage`, `onImageAvailable`, `matchMeta`, `finishLoop`) is untouched, which was
verified in two separate review rounds. That path is what the compressed-codec
throughput rounds 3-5 fought for and it must stay off the audio critical path.

Ordering that matters, in `CameraController.kt`:

- Start: `audioRecorder.start(...)` runs **before** `NativeBridge.nativeStartRecording(...)`.
- Stop: quiesce -> `audioRecorder.stop()` -> `nativeSetAudioInfo(...)` -> `nativeStopRecording(...)`.

Failure rule from the spec is implemented as designed: **video always wins**. Every
audio failure mode sets a bit in the header's `audioStatus` bitfield and warns; none
of them stops or fails the take.

## Device verification (take A)

Xiaomi 14 Ultra (`24030PN60G`), clip `clip_20260824_152653`, 4096x3072 @ 24fps,
152 frames, 6.333 s.

This was an **independent** clap test, not a self-consistency check -- the audio
transient was measured against the visual moment of hand contact in the RAW frames,
with the two measured separately.

- Audio onset: silent at t=1.6000 s (1.1% of local peak), 9.3% at t=1.6050,
  100% at t=1.6100. Onset **t = 1.610 s**.
- Visual contact: f38 (t=1.583 s) is heavily motion-blurred with hands still closing;
  f39 (t=1.625 s) is sharp with hands together and stopped. Contact is therefore
  bracketed in (1.583, 1.625).
- Result: the audio transient lands near the midpoint of that one-frame window.
  **Measured |A/V offset| < ~21 ms**, inside the acceptance bar of one frame at
  24 fps (41.7 ms).

Header read back from the clip:

| field | value |
|---|---|
| `version` | 5 |
| `audioPresent` | 1 |
| `audioSampleRate` / `audioChannels` / `audioBitsPerSample` | 48000 / 1 / 24 |
| `audioOffsetNs` | 554748285 (554.7 ms, pre-trim provenance) |
| `audioDriftPpm` | -24 |
| `audioTimestampSource` | 1 (REALTIME) |
| `audioStatus` | 0 -- no warning bits set at all |
| `audioSource` | 9 (UNPROCESSED) |
| `audioFileName` | `clip_20260824_152653.wav` |

`audioSource == 9` means `MediaRecorder.AudioSource.UNPROCESSED` was granted, so AGC,
noise suppression and echo cancellation are all off -- the production-audio path the
spec asked for. The WAV parsed clean: RIFF size 899965 and data size 899319 both as
expected, `bext` present and carrying the same offset/drift/status/source values.
File size is exactly `512 + 152 * 25165888`, so all 152 frames landed.

**Caveat worth keeping:** `audioTimestampSource == 1` means this camera reports
`SENSOR_TIMESTAMP` on `CLOCK_BOOTTIME` directly, so the clock-bridging branch for
`UNKNOWN`/`CLOCK_MONOTONIC` devices was never exercised. That is the fragile half of
the sync design and it remains **untested on real hardware**.

## Open items

### 1. Tail truncation -- last frame is silent

Audio runs 6.245 s; the last frame starts at 6.292 s. The final ~46 ms -- about one
frame, roughly one 4096-sample read chunk -- has no audio, so in an edit the last
frame is silent. Head alignment is unaffected; this is purely a tail issue, most
likely the final in-flight read chunk being dropped at stop. Minor, not fixed.

### 2. Take B (10-minute run) -- CANCELLED 2026-08-25 by the user

Take B was meant to exercise drift measurement over a long run and the compressed
capture path under thermal load. It was not recorded: the device did not have enough
free storage.

The root cause is **not** the disk, it is the recurring toggle trap. Take A recorded
with `packMode == 0` (Raw16, uncompressed) because the 'Compress recordings' setting
had reverted to OFF again -- the same failure documented on 2026-08-11 and 2026-08-17.
Uncompressed 4096x3072 at 24 fps is ~604 MB/s, so 10 minutes needs ~362 GB against
278 GB free. With compression ON it would be roughly a quarter of that and would fit.

**Before the next encoder or long-run measurement on this device, verify
`packMode == 3` by reading the `FileHeader` struct.** Do not eyeball adjacent `u32`s:
the `3` at byte 24 is the CFA pattern, not `packMode`.

**The user cancelled Take B on 2026-08-25: it will not be recorded.** Three things it
was the only planned way to measure therefore stay UNVERIFIED, and should be treated as
unknown rather than working:

- **Clock drift over a long run.** Take A was 152 frames (~6.3 s). The measured
  `driftPpm -24` comes from that short window; drift behaviour over ten minutes was
  never observed.
- **The compressed capture path under sustained thermal load,** with audio running
  alongside it.
- **The MONOTONIC clock-bridge branch.** This camera reports `tsSource=1 REALTIME`, so
  the bridge has still never executed on real hardware -- on any device or take.

The A/V sync bar that WAS met (|offset| < ~21 ms, inside one frame at 24 fps) was met on
a ~6 s uncompressed take only. Nothing here says it holds for a long compressed one.

### 3. Device cleanup

~226 MB of extracted frame dumps (`f35.bin` .. `f43.bin`, `hdr.bin`) from the take-A
analysis are still on the device and can be deleted.

## Deferred from the spec (unchanged)

Drift resampling, VU/PPM meter ballistics, matched DNG/BWF timecode, and live gain
adjustment during recording were all deferred in v1 and remain deferred. Drift is
measured and reported in ppm but is not corrected.
