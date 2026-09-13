# Timecode Auto-Sync and Audio Drift Correction -- Outcome and Open Items

**Date:** 2026-09-13
**Spec / plan:** none written. Executed from a handoff prompt as five dependency-ordered
pieces, strictly TDD, with mutation testing whenever a GREEN overshot its test.
**Status:** Implemented, reviewed twice (a read-through plus the `/code-review` agent, which
found the two most important defects), and pushed to `origin/main`. **Not verified on a real
take.** Everything below is backed by host tests and an arm64 build only.

## What shipped

### Timecode auto-sync

An exported DNG sequence and its sidecar WAV now carry the same take-start anchor, so an NLE
can link them on import instead of the operator dragging audio into place.

- **DNG side:** tag 51043 (TimeCode), SMPTE 12M packed BCD, frame N = anchor + N. Written
  through `addRaw()` because eight bytes do not fit a TIFF entry's value field.
- **WAV side:** the BWF `bext` chunk's TimeReference (sample frames since local midnight)
  and OriginationDate/Time. The chunk already existed; its *content* was wrong --
  origination came from the take's END and TimeReference was hardcoded 0.
- **`.rawv` header:** `startEpochNs` (int64) + `tzOffsetSec` (int32), carved out of
  `reserved[180]` -> `reserved[168]`. `kVersion` stays 5: `RawvReader::open` only
  range-checks the version and `headerSane()` never reads `reserved`, so every existing clip
  still opens. Zero is the "no anchor" sentinel.
- **One clock read, both sides:** `CameraController.startRecording()` reads the clock once
  and hands the same instant to `AudioRecorder` and `nativeStartRecording`. Two independent
  reads would differ by however long arming took; the shared value is the whole mechanism.

**Scope, binding:** this aligns the two halves *nominally*. It does not correct the known
sub-frame A/V residual and must not be described as doing so.

Commits: `f618415` packTimecode, `0658c88` header anchor, `c9d1cc3` nsSinceLocalMidnight,
`934d584` DNG tag 51043, `0aee89f` TakeAnchor, `86a4ca2` one clock read.

### Review fixes

| commit | finding |
|---|---|
| `25cf6e7` | The two sides shared the anchor but **rounded it differently**: SMPTE floors to a frame, TimeReference kept sub-second precision. 25 ms apart at 12:33:33.900 / 24 fps, 41.7 ms worst case. TimeReference is now `frame * sampleRate / fps`. |
| `2e7214c` | `bext` was written only at `close()`, which a wedged writer or a killed process never reaches -- those WAVs claimed TimeReference 0 while their DNGs carried real timecode. Now written at file creation. |
| `637a498` | A corrupt anchor reached the timecode math unchecked. `hasTakeAnchor()` treats it as no anchor rather than rejecting the clip. |
| `661b091` | Docs: the anchor is the arming instant, not frame 0. |
| `ad413bb` | `driftPpm` reported timestamp jitter as drift on short takes -- up to ~1500 ppm on a 2 s take with none present. Now "not measured" below 30 s, fitted with an intercept. |
| `be61bee` | Five audit findings: no DNG timecode when audio alignment was never verified; up-front `bext` marked `provisional=1`; `TakeAnchor` mirrors `hasTakeAnchor()`; WAV header flushed immediately; `@Volatile` on the new fields. |

### Drift correction

Export no longer byte-copies the sidecar. It rewrites it onto the clip's timebase:
resampled by the header's `audioDriftPpm`, and padded or cut to **exactly** the picture's
duration (`frameCount * sampleRate * fpsDen / fpsNum`), so sound and picture are a matched
pair in a timeline.

- `audio/DriftResampler.kt` -- streaming Catmull-Rom, pure.
- `audio/WavTimebase.kt` -- conforms our WAV, carries `bext` across byte-for-byte.
- `NativeBridge.nativeClipAudioTimebase` + `ExportService.conformAudio`, which falls back
  to a plain copy whenever the timebase is unknown or conforming fails.

**Sign:** positive `driftPpm` means the mic clock ran slow, so the take finishes early and
the audio **leads** -- it must be stretched by `1 + ppm/1e6`. `AvSync`'s kdoc said "lags"
until `8a0000c`; believing it doubles the drift instead of removing it.

Expected effect, **simulated** (80 ppm mic clock, 1 ms `getTimestamp` jitter):

| take | uncorrected | after correction (p50) |
|---|---|---|
| 30 s | 2.4 ms | 0.42 ms |
| 300 s | 24 ms | 0.14 ms |
| 1800 s | 144 ms | 0.05 ms |

Commits: `5d80768` resampler, `2a33996` export conform, `8a0000c` sign documentation.

## Verification

Host only, no device run of any kind:

- Core: ctest 14/14.
- Kotlin: 212 JUnit tests, 0 failures (count taken at the committed tree).
- `assembleDebug` plus the arm64 native build, confirmed by object timestamps.

Mutation testing, where a GREEN overshot its test: every mutant listed in the commit
messages is killed. It found real holes -- no DNG test used a non-zero zone offset, the first
file-level drift test survived both "ignore the drift" and "invert its sign", and the
mutation harness itself reported compile failures as survivors until it was fixed.

Commit messages from `25cf6e7` onward were rewritten on 2026-09-13 to correct wrong test
counts. The trees are byte-identical; only the messages and hashes changed.

## Open items

### 1. Resolve acceptance take -- the test that decides whether any of this works

Record one take and export it. Requirements, each chosen so a pre-existing effect cannot
masquerade as a defect in this work:

- **Zero dropped frames.** `frameIndex` is the written-frame count (`capture.cpp:153`), so
  a take with drops diverges after the drop regardless of timecode.
- **At least 30 s,** so drift is actually measured rather than reported as 0.
- **Claps at the head and the tail.** The head checks timecode linking; the tail checks
  drift. Drift correction cannot be verified from WAV byte counts -- that method's
  uncertainty is the same order as the effect.

### 2. The ~20 ms head-alignment residual

Untouched. It is a position error at sample 0; drift correction is a rate correction.

### 3. The anchor is the arming instant

Absolute timecode runs early by the arm-to-first-frame latency (hundreds of ms), so
matching against an external timecode source is off by that much. Relative sync is
unaffected. Correcting it to frame 0 is feasible -- `WavWriter` is constructed in
`flushFirstFrame()` at frame 0 -- and needs a boottime->epoch bridge plus a header patch at
finalize.

### 4. `bext` on untrustworthy takes

TimeReference is still stamped when `OVERRUNS` or `SUSPENDED` is set. Zeroing it would claim
midnight, which is worse. Only `ALIGNMENT_UNVERIFIED` suppresses the DNG timecode.

### 5. Interpolation quality

Cubic is below the noise floor under ~10 kHz and degrades above it. A windowed sinc drops in
behind the same `DriftResampler` interface if the highs prove to matter.

### 6. Still unverified from the audio-recording work

Carried from `open-items-2026-08-24-audio-recording.md`: the MONOTONIC clock-bridge branch
has never run on real hardware, and drift over a long take has never been observed on a
device.
