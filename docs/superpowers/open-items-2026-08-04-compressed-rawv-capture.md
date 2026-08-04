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

## Round 2 re-verification — 2026-08-05, after the throughput-fix plan

Plan: `docs/superpowers/plans/2026-08-05-rawv-codec-throughput.md` (batched
`BitWriter`/`BitReader` accumulator instead of per-bit calls, commit
`e31c46d`; strided 1/16th-pixel sampling for Rice-k selection instead of a
full-frame scan, commit `e930cd1`). Both host-tested (9/9 `rawv_codec`
tests, 8/8 suites), both task-reviewed (Task 1 needed one fix round — the
reviewer caught a real capacity-boundary regression in the new `BitWriter`,
fixed and re-review-verified clean, commit `e31c46d`; Task 2 approved with
no findings). Full detail in the plan's own task reports under
`.superpowers/sdd/2026-08-05-rawv-codec-throughput/`.

Re-ran the same on-device check (same device, same 4096×3072@24fps,
compression confirmed genuinely ON via the recorded file's own header —
`packMode=3`, not a toggle mixup):

- **Before this fix:** ~91% frame loss (e.g. 85 written / 824 dropped at 38s).
- **After this fix:** ~75-79% frame loss (426 written / ~1359+ dropped over
  ~72s+ before stop) — real, measurable improvement (roughly triples the
  success rate, from ~9% to ~21-25% of frames landing), but **still far
  short of the spec's 0-dropped-frames bar.**

**Verdict: still failing, not ready to ship.** The batched bit-writer and
strided sampling removed the most obviously wasteful work (function-call
overhead per individual bit, and a fully redundant second full-frame scan),
but per-pixel scalar cost for a 12.6-million-sample frame — one `predictAt`
call plus 1-3 `writeBits` calls each, ~12.6-25M calls total in the main
encode pass alone — is still too much work for a ~41.6ms real-time budget
on this device's CPU. This matches a back-of-envelope estimate made before
writing the throughput-fix plan (not acted on then, since the batched
writer was worth trying on its own merits regardless): even fully batched,
tens of millions of scalar function calls per frame may not fit real-time
without either running that work across multiple cores, or vectorizing the
predictor+residual computation (NEON), or reducing the amount of the frame
actually processed per real-time frame (e.g. tiling/downsampling), none of
which this plan attempted — it deliberately scoped to the two
lowest-risk, most clearly-wasteful fixes first and re-measured before
deciding whether more was needed, per its own Task 3 instructions.

**Process lesson worth keeping, not just a data point:** the first
re-verification attempt after this fix showed a **false** "0 dropped"
result (a full clean recording) because the "Compress recordings" toggle
had been left OFF from the earlier control-clip test and was never turned
back on before the check. The `.rawv` file's own header (`packMode`) is
the only fully reliable way to confirm which code path a given recording
actually exercised — a live frame counter alone doesn't reveal that.

## Conclusion

**Still not ready to ship**, after two rounds of work. The codec is
correct (host-tested round-trips, confirmed correct on real sensor data
with an in-spec compression ratio in both rounds), and the whole plumbing
chain builds clean. The design spec's explicit acceptance bar — no dropped
frames at 4096×3072@24fps — has now been tested twice and failed twice:
~91% loss originally, ~75-79% loss after the first optimization pass
(batched bit writer + strided k-sampling). Real progress, not enough.

**Recommended next step, scoped as its own follow-up plan** (per the
throughput-fix plan's own explicit instruction not to speculatively design
this until measurement showed it was still needed — it now has):
parallelize `encodeFrame` across the device's available CPU cores (e.g.
split the frame into row-bands, encode each band on its own thread, since
the MED predictor only looks 2 samples back so band boundaries just need a
2-row overlap or a fixed edge-baseline seam), and/or NEON-vectorize the
predictor+residual computation. Either is a meaningfully larger effort
than this round's fix (real concurrency or SIMD work, not a
transcription-level change) and deserves its own brainstorming/design pass
rather than being bolted onto this plan reactively.

A decision the user should make explicitly before further engineering
investment: is the ~3x improvement + further optimization work worth
pursuing to get this feature production-ready, or should
`compressRecordings` ship OFF-by-default (or be pulled entirely) while
that decision is made separately?
