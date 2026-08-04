# Compressed `.rawv` Capture — open items after on-device verification

Date: 2026-08-05. Plan: `docs/superpowers/plans/2026-08-04-compressed-rawv-capture.md`.
Spec: `docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`.

## What's done

All 7 code tasks from the plan are committed, host-tested (ctest 8/8 green
throughout) and app-built (`assembleDebug`/`assembleRelease` both green):

| Commit | Task | What it does |
|---|---|---|
| `2fb7ec5` | 1 | `rawv.h`: `PackMode::CompressedPredictive`, `FrameMeta.payloadBytes`/`compressed` |
| `f3d396d` | 2 | `rawv_codec.h/.cpp`: MED/LOCO-I predictor + Golomb-Rice coder |
| `1546ea0` | 3 | `RawvWriter::writeFrame` takes an explicit payload length |
| `ae337af` | 4 | `RawvReader` variable-stride offset table |
| `e0bf612` | 5 | `exporter.cpp` decodes `CompressedPredictive` frames before writing DNGs |
| `8468932` | 6 | `capture.cpp` encode branch + `compressRecordings` threaded end-to-end |
| `3f9f5f0` | 7 | "Compress recordings" Settings toggle |

Two real gaps found and fixed beyond the plan's literal text during
implementation (both necessary, not polish): `headerSane()` had no
`CompressedPredictive` case (would have rejected every compressed file at
`open()`); neither Task 6 nor Task 7's file list included `NativeBridge.kt`/
`CameraController.kt`, though the `compressRecordings` boolean can't reach
native code without threading through both — done in Task 6 with a
byte-for-byte-preserving default.

## On-device verification — 2026-08-05 (device model `24030PN60G`)

Sensor on this device: 4096×3072, 14-bit (`whiteLevel=16383`) — exceeds even
Packed12's 12-bit ceiling, so this device's *uncompressed* baseline is
already Raw16 (the least space-efficient format), making compression more
valuable here than on a 10/12-bit sensor, if it worked.

- [x] **Settings toggle renders and persists.** "Compress recordings" shows
  in the RECORDING section with the correct subtitle, defaults ON, toggled
  OFF and back ON correctly, held across screen navigation.
- [x] **Compression is byte-correct on real sensor data.** Manually parsed
  the first `FrameMeta` of a compression-ON recording:
  `compressed=1`, `payloadBytes=14,494,570` against a 25,165,824-byte
  ceiling — a genuine **~42% size reduction** on real, noisy sensor output,
  within the spec's own "~20-50%" target range. The codec is not broken.
- [ ] **BLOCKING: real-time throughput at this project's usual recording
  class (4096×3072@24fps).** The design spec states explicitly: *"the
  predictor+entropy-coder's added CPU cost per frame must not push
  recording into dropped frames at this project's usual 4096x3072@24fps
  class of recording. On-device throughput verification is required before
  this is considered done."* **This constraint fails.** Three separate
  compression-ON recordings all showed catastrophic frame loss:
  - Clip 1: 38s elapsed, 85 written / 824 dropped (~91% loss)
  - Clip 3 (after an accidental restart): 29s elapsed, 64 written / 628
    dropped (~91% loss)
  - A same-session **compression-OFF control clip, same resolution/fps**:
    31s elapsed, 739 written / **0 dropped** — proves the device/resolution
    itself is not the bottleneck; the regression is specific to the new
    encode path.

  Root cause (reasoned from the implementation, not profiled with
  systrace/perfetto — that would be the natural next step if pursuing a
  fix): `rawv_codec.cpp`'s `encodeFrame()` runs two full scalar passes over
  every one of a 12.6-million-sample frame, and its `BitWriter::writeRice()`
  issues one function call per *individual bit* of the Golomb-Rice
  codeword (not a batched/word-at-a-time writer). At an average of roughly
  8 bits/pixel for this device's real residual entropy (consistent with the
  ~42% observed reduction), that's on the order of 100M+ branchy per-bit
  calls per frame — far beyond the ~41.6ms real-time budget at 24fps. This
  is an architectural throughput problem, not a small bug: the two-pass
  structure and per-bit `BitWriter` were fine for the host tests' 64×64
  synthetic frames (sub-millisecond) but were never exercised at real
  camera resolution before this session, since host tests can't do that and
  this is native `app/src/main/cpp/` code with no host coverage by
  convention.
- [x] **No crash.** `adb logcat -b crash -d` was empty across all four test
  recordings (three compression-ON, one control) — the app degrades to
  dropped frames rather than crashing under this load, which is the
  correct failure mode even though the underlying throughput problem is
  real.
- [x] **Toggling between separate recordings works mechanically** (both the
  compression-ON and compression-OFF clips recorded, stopped, and left
  valid files on disk) — the toggle plumbing itself is not in question,
  only the encode path's speed.

### Not run this session (blocked by the throughput finding above)

Continuing the rest of the plan's Task 8 checklist (DNG pixel-diff export
verification, old `kVersion==3` file compatibility, toggling mid-session)
was deprioritized once the throughput blocker was confirmed and isolated —
those checks are about correctness/compatibility, which is secondary to
"does this feature work at all at practical settings." Worth resuming once
a fix lands.

### Test artifacts left on-device

Four clips recorded during this session remain in the app's Clips list
under app-private external storage (`/sdcard/Android/data/com.shez.rawcam/files/clips/`,
~30GB total — device has 362GB free, not a shortage): three compression-ON
(`clip_20260805_051902`, `_052015`, `_052103`) and one compression-OFF
control (`clip_20260805_052622`, 27GB — this device's Raw16-forced baseline
is large). Left for the user to delete via the app's own CLIPS screen at
their convenience (the freshly-reinstalled release build isn't debuggable,
so `adb run-as` can't delete app-private external storage directly).

## Conclusion

**Not ready to ship as-is.** The codec itself is correct (host-tested
round-trips, and now confirmed correct on real sensor data with a real
compression ratio in-spec), and the whole plumbing chain (writer, reader,
exporter, capture, Settings) is implemented and builds clean. But the
design spec's own explicit, named acceptance bar — no dropped frames at
4096×3072@24fps — fails outright, with ~91% frame loss reproduced three
times and cleanly isolated (via a same-session control recording) to the
new encode path rather than the device or an unrelated regression.

This needs either a real performance pass on `rawv_codec.cpp` (batched bit
writing instead of per-bit calls, avoiding the redundant two-pass scan, and/or
NEON-accelerating the predictor) before this can ship with the toggle
defaulting ON as currently shipped, or a decision to ship it OFF-by-default /
resolution-gated as a known-slow opt-in while a real fix is scoped
separately. Recommend treating "optimize the codec for real-time throughput"
as its own follow-up plan rather than folding it into this one, since it's a
different kind of work (profiling + low-level optimization) than the
plumbing this plan covered.
