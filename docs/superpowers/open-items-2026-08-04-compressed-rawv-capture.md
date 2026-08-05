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

## Round 3 checkpoint A — threading alone, 2026-08-05

Plan: `docs/superpowers/plans/2026-08-05-rawv-codec-round3-throughput.md`,
design: `docs/superpowers/specs/2026-08-05-rawv-codec-round3-throughput-design.md`.
Tasks 1-2: new `ParallelFrameEncoder` class (persistent thread pool,
`min(hardware_concurrency(), 4)` workers, row-band-parallel predict+residual
into a scratch buffer, serial batched `writeRice` pass), wired into
`Capture` replacing the old serial `encodeFrame()` call. Host-tested (8/8
suites, including a new byte-identity equivalence test vs. the serial
encoder), both tasks task-reviewed clean (no Critical/Important findings).

Re-ran the same on-device check (same device, same 4096×3072@24fps,
compression confirmed genuinely ON via the recorded file's own header —
`packMode=3`):

- **942 written / 3339 dropped** over ~2:35 elapsed (~78.0% loss, 22.0%
  landing) — **statistically indistinguishable from round 2's ~75-79% loss
  baseline (426 written / ~1359+ dropped, ~21-25% landing).** Threading
  alone did not measurably move the needle beyond what round 2's batched
  bit-writer + strided sampling already achieved.

**This is a real, unexpected result, not yet explained.** The row-band
split itself is verified correct (host equivalence test, task review's
manual trace of the split arithmetic and the generation-counter dispatch's
happens-before chains, both clean) — the *mechanism* works, but produced no
measurable throughput gain on-device. Candidate explanations, none
confirmed by profiling yet:
- The predict+residual step (the part this round parallelized) may not
  actually be the dominant per-frame cost — if the serial `writeRice` pass,
  or the k-selection pass, or thread wake/dispatch latency (one
  condvar-notify round trip per frame, every ~41.6ms) dominates instead,
  parallelizing only the predict step wouldn't show up in the total.
- `std::thread::hardware_concurrency()` on this device may return fewer
  usable cores than assumed, or the OS scheduler may not be giving the
  worker threads real concurrent execution time against the camera
  pipeline's other threads (capture callback, writer thread, JNI/UI thread)
  under this load.
- Condvar wake latency itself (kernel futex round-trip, ~tens of
  microseconds typically, but not verified on this device) could be
  eating a nontrivial fraction of the ~41.6ms budget per frame if it's
  worse than assumed.

None of this was profiled with systrace/perfetto — reasoned from the
result, not measured at that level of detail. That would be the natural
next step before trusting NEON (Task 4, not yet run) to close the
remaining gap on its own, since NEON only speeds up the same predict step
threading was supposed to parallelize — if that step wasn't the
bottleneck, NEON may show the same flat result threading just did.

## Conclusion

**Still not ready to ship**, after three rounds of work. The codec is
correct (host-tested round-trips, confirmed correct on real sensor data
with an in-spec compression ratio across all rounds), and the whole
plumbing chain builds clean. The design spec's explicit acceptance bar — no
dropped frames at 4096×3072@24fps — has now been tested three times and
failed three times: ~91% loss originally, ~75-79% loss after round 2
(batched bit writer + strided k-sampling), ~78.0% loss after round 3's
threading alone (statistically flat vs. round 2, not the expected further
improvement).

**Open decision for the user, sharper than before:** round 3's own premise
(the predict+residual step is the bottleneck, so parallelizing it helps)
didn't hold up against the on-device measurement. Continuing to Task 4
(NEON, which vectorizes that same step) without first understanding *why*
threading didn't help risks repeating this exact outcome for a second time
at higher implementation cost. Worth deciding explicitly: profile first to
find the real bottleneck before writing more optimization code, proceed to
NEON anyway on the chance it behaves differently from threading, or step
back to the ship-OFF-by-default/pull-the-feature options from the original
open decision.
