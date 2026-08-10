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

## Root cause found — 2026-08-05, direct on-device profiling

Per `superpowers:systematic-debugging`: added temporary `std::chrono`-based
timing instrumentation around `ParallelFrameEncoder::encode()`'s three
phases (k-selection scan, parallel dispatch+wait, serial `writeRice`
write) and around `capture.cpp`'s encode-vs-disk-write split and queue
backlog, logged via `__android_log_print`. Built, installed, recorded a
short diagnostic clip (same device/resolution/fps), pulled logcat, then
reverted the instrumentation (not committed — throwaway evidence-gathering
code only). **615 frame samples captured, all consistent:**

| Phase | Avg | % of encode() total |
|---|---|---|
| k-selection scan | 3.83ms | 1.8% |
| dispatch+wait (the threaded predict+residual step) | 21.15ms | 9.7% |
| **serial `writeRice` write** | **192.44ms** | **88.5%** |
| **`encode()` total** | **217.43ms** | — |
| disk write (`RawvWriter::writeFrame`) | 9.38ms | (separate from encode()) |

Queue backlog was saturated (7/8 slots full) in 606 of 615 samples —
consistent with `encode()` averaging ~217ms against a ~41.6ms arrival
budget (~5.2x over budget), which lines up almost exactly with the
observed ~19-22% frame-landing rate (1/5.2 ≈ 19%).

**Root cause: the serial Golomb-Rice bit-packing loop (`BitWriter::writeRice`,
called 12.6M times per frame) is the actual bottleneck — not the
predict+residual step round 3 parallelized.** That step (`dispatch_wait`)
averages only 21ms, a small fraction of the total; threading it correctly
sped it up, but it was never the dominant cost, which is why round 3's
on-device result was flat versus round 2. Round 2's own assumption that
the batched write pass was "already cheap" (stated when scoping that
round) was never verified at real camera resolution — only at host tests'
64×64 synthetic frames — and turns out to be wrong: at 12.6M calls/frame,
even the batched per-call overhead (branchy `while (q >= 32)` loop,
multiple `writeBits` calls, non-trivial per-pixel work) adds up to ~192ms.

**This reframes Task 4 (NEON).** NEON vectorizes the predict+residual step
— the ~21ms slice, not the ~192ms one. Proceeding to Task 4 as originally
scoped would very likely reproduce round 3's flat result a second time.
Any further optimization work needs to target the write pass itself, not
the predict step: either speeding up `writeRice`/`writeBits` per-call
overhead in place (format-preserving, no bitstream change), or accepting a
bitstream format change to parallelize the write across bands too (each
band writing its own byte-aligned sub-bitstream with a small per-band
offset table — the design consciously avoided this in round 3 to keep
`kVersion` unchanged, but the profiling data now shows the write pass is
where parallelism would actually pay off, not the predict step).

## Round 4 stage 1 checkpoint — band-parallel write, 2026-08-05

Plan: `docs/superpowers/plans/2026-08-05-rawv-codec-round4-band-parallel-write.md`,
design: `docs/superpowers/specs/2026-08-05-rawv-codec-round4-pipeline-design.md`
(this checkpoint covers stage 1 only — band-parallel write — stage 2, the
Compute/Finish pipeline, is a deliberately separate follow-up). Task 1:
`ParallelFrameEncoder` rewritten to fuse predict+residual+Rice-pack per
row-band (each band packs directly into its own local buffer via its own
`BitWriter`, no shared `residuals_` buffer), with a new bit-exact
`mergeBitstreams()` concatenating the per-band bitstreams into output
identical to the unchanged serial `encodeFrame()`. Host-tested (8/8 suites,
14 `rawv_codec` test cases including bit-identity, merge-boundary-phase
coverage, and a genuine per-band overflow test), task-reviewed with one fix
round (two plan-mandated bugs found and fixed: the original overflow test
didn't actually trigger overflow due to a flawed k-selection assumption,
and the last-band buffer capacity formula under-provisioned for
`height % threadCount >= 2` — both traced to authoring mistakes in the plan
itself, fixed and re-review-verified clean).

Re-ran the same on-device check (same device, same 4096×3072@24fps,
compression confirmed genuinely ON via the recorded file's own header —
`packMode=3`):

- **1437 written / 2003 dropped** over ~2:06 elapsed (~58.2% loss, 41.8%
  landing) — a real, meaningful improvement over round 3's ~78.0% loss
  (22.0% landing): loss roughly halved, landing rate very nearly doubled.

**Still not ready to ship** — the 0-dropped bar isn't met — but this is the
first round since round 2 to show genuine, substantial improvement rather
than a flat result. Consistent with the design doc's own expectation:
band-parallel write alone was never expected to close the whole gap by
itself, since k-selection (3.83ms) + dispatch+wait (21.15ms) + disk write
(9.38ms) — none of which this stage touches — already summed to ~34.4ms of
the ~41.6ms budget on their own (round 3's profiling). Stage 2 (the
Compute/Finish pipeline, overlapping disk I/O and the next frame's compute
with the current frame's finish work) is designed specifically to address
that remaining fixed overhead, and per the design doc's own staged-
verification recommendation, should now be scoped as its own follow-up plan
informed by this real number rather than planned speculatively beforehand.

**Caveat on the 34.4ms "fixed overhead" figure above:** that number is
round 3's profiling, taken before this round's `mergeBitstreams()` step
existed. Round 4 replaced round 3's single serial `writeRice` write pass
with per-band fused predict+residual+pack (inside dispatch+wait) plus a new
serial merge pass concatenating the per-band bitstreams -- a cost that
simply didn't exist when the 34.4ms figure was measured. So "34.4ms fixed
overhead + X ms for pack" is not quite right for round 4; it should really
be "34.4ms fixed overhead + pack (now inside dispatch+wait) + merge (new)".
A post-hoc review of round 4 (2026-08-05) found the initial merge
implementation was a serial byte-at-a-time loop (one `writeBits()` call per
byte moved across all bands, on the order of ~12-15M calls/frame at this
project's usual 4096x3072 resolution -- roughly the same order of magnitude
as the original per-bit `writeRice` bottleneck this whole investigation
started from) and rewrote it to a bulk memcpy / word-at-a-time merge
instead (see `mergeBitstreams()` / `appendBits()` in `rawv_codec.cpp`). The
merge should be meaningfully
cheaper post-optimization than the original byte-at-a-time version would
have been, but this has **not been re-measured on-device** -- the reasoning
above is inference from the implementation, not a fresh profiling run.

**Does band-parallel write hit the design's ~3-4x pack-step target?**
Back-of-envelope, yes, roughly, though this is an estimate derived from
landing rates, not a direct on-device phase measurement of round 4 the way
round 3's 34.4ms breakdown was. Treating landing rate as budget/actual-
frame-time (the same relationship round 3's own profiling data confirmed:
~217ms actual / ~41.6ms budget ≈ 5.2x over, 1/5.2 ≈ 19% landing, matching
the ~19-22% observed): round 3's 22.0% landing implies ~41.6/0.22 ≈ 189ms
actual per frame; round 4's 41.8% landing implies ~41.6/0.418 ≈ 99ms actual
per frame. Netting out the ~34.4ms fixed-overhead trio (k-selection +
dispatch/wait's non-pack portion + disk write) from round 4's ~99ms leaves
roughly **~65ms for the fused pack+merge step, versus round 3's ~192ms
serial `writeRice` write** -- close to a 3x reduction, at the low end of
the ~3-4x band-parallelism target rather than solidly inside it. Two
reasons this is a rough estimate rather than a confirmed number: (1) it's
derived from landing-rate arithmetic, not a direct phase-by-phase profiling
run of round 4 itself; (2) the 34.4ms fixed-overhead figure being netted
out is itself round 3's, and dispatch+wait's cost profile changed in round
4 (each band now does pack work too, not just predict+residual), so the
true fixed-overhead split for round 4 is not actually known yet.

**Recommendation before scoping stage 2:** re-run on-device phase profiling
(the same kind of temporary `std::chrono`/`__android_log_print`
instrumentation used to find the original root cause, see "Root cause
found" above) to get a clean, current measurement of pack-time vs.
merge-time vs. the fixed-overhead trio, now that the merge optimization
above may have changed the balance. Stage 2's design should be informed by
real round-4 numbers, not round 3's phase breakdown extrapolated through
estimates as done here.

## Round 4 stage 1 re-profiling — 2026-08-05, post-merge-optimization

Per the previous section's recommendation and the user's explicit "re-profile
first" choice before scoping stage 2: added temporary `std::chrono`-based
timing instrumentation around `ParallelFrameEncoder::encode()`'s current
phases (k-selection scan, dispatch+wait -- now doing fused predict+residual+
Rice-pack per band, `mergeBitstreams()`) and around `capture.cpp`'s disk
write, logged via `__android_log_print`. Built, installed, recorded a ~40s
diagnostic clip (same device, same 4096x3072@24fps, compression confirmed ON
via the in-app toast reporting "1091 frames, 719 dropped" -- consistent with
compression being active, not a toggle mixup), pulled logcat, computed
averages, then reverted the instrumentation (not committed -- throwaway
evidence-gathering code only, same convention as the original profiling run).
**1091 frame samples captured, all consistent:**

| Phase | Avg | % of (encode()+diskWrite) |
|---|---|---|
| k-selection scan | 2.79ms | 4.0% |
| dispatch+wait (fused predict+residual+pack per band) | 48.44ms | 69.7% |
| merge (`mergeBitstreams()`, post-optimization) | 12.04ms | 17.3% |
| **`encode()` total** | **63.27ms** | 91.0% |
| disk write (`RawvWriter::writeFrame`, separate from `encode()`) | 6.26ms | 9.0% |
| **encode() + disk write (serial, single writer thread)** | **69.53ms** | 100% |

Real-time budget: ~41.6ms/frame at 24fps. 69.53ms actual / 41.6ms budget ≈
1.67x over -- predicted landing rate 1/1.67 ≈ 59.8%, closely matching this
diagnostic clip's own observed landing rate (1091 written / (1091+719)
arrived ≈ 60.3%), confirming the same budget-vs-actual relationship
established in the original profiling run.

**This diagnostic clip's ~60% landing is notably better than the formal
round 4 stage 1 checkpoint's ~41.8%** (same device/resolution/fps, compression
confirmed ON both times). The most likely explanation: this run was a short
~40s diagnostic clip vs. the checkpoint's ~2:06 sustained recording, and a
within-run first-half/second-half comparison of this diagnostic's own samples
shows mild upward drift (first half avg total 59.7ms, second half 66.8ms) --
consistent with thermal throttling that would compound further over a longer
sustained recording. Scene content (this diagnostic pointed at a static
blurry indoor scene, not necessarily representative of the checkpoint's
content) may also contribute via k-selection picking a different Rice
parameter. Treat this run's numbers as the current *phase proportions*
(reliable) more than an exact absolute landing-rate prediction (optimistic
vs. a longer sustained recording).

**What this confirms about stage 2's design:**
- The merge optimization (memcpy+word-at-a-time, commit `bc577bf`) worked:
  12.04ms is far below the ~192ms per-frame cost the *entire* write pass used
  to take pre-round-4, and also below the rough ~65ms "pack+merge" estimate
  the stage 1 checkpoint inferred from landing-rate arithmetic alone.
- **Dispatch+wait (48.44ms, fused predict+residual+pack per band) is now
  overwhelmingly the dominant cost (69.7%)** -- not k-selection, not disk
  write, not merge. This differs from the "34.4ms fixed-overhead trio" framing
  used at the end of round 3 and carried into the stage 1 checkpoint's
  estimate: that framing no longer applies as-is, since round 4 moved the
  actual Rice-packing work *into* the per-band dispatch step (it used to be a
  separate serial pass). k-selection (2.79ms) and disk write (6.26ms) really
  are now small, leaving dispatch+merge (60.48ms, 87% of the total) as
  virtually the whole remaining cost.
- This reframes what stage 2 (the Compute/Finish pipeline) can realistically
  buy: overlapping disk write (6.26ms) and next-frame dispatch with the
  current frame's finish work only hides ~6-18ms per frame (disk write alone,
  or disk write + merge if merge moves to the Finish thread) behind the next
  frame's Compute -- valuable, but dispatch+wait's 48.44ms is Compute-stage
  work that pipelining across frames does NOT shrink by itself, since the
  worker pool is the same shared resource being pipelined into, not new
  capacity. Stage 2 alone, as designed, would not be expected to close the
  gap to 0-dropped; it would need to be paired with either speeding up
  dispatch+wait itself (the ~192ms-style bottleneck from round 3 has moved,
  not disappeared -- it now lives inside the per-band worker loop) or
  reserving a core for the Finish thread (e.g. dropping the dispatch pool
  from 4 workers to 3 on a 4-core device) so Finish's own CPU time doesn't
  contend with the same cores dispatch+wait needs, trading some per-band
  parallelism for pipeline overlap instead of getting both for free.

## Round 4 stage 2 checkpoint — Compute/Finish pipeline, 2026-08-06

Plan: `docs/superpowers/plans/2026-08-05-rawv-codec-round4-compute-finish-pipeline.md`
(first of three staged follow-ups approved by the user after the re-profiling
above: pipeline → thread-count tuning → NEON, each independently
checkpointed). Task 1: `ParallelFrameEncoder::encode()` split into async
`computeBands()`/`mergeSlot()` with double-buffered slots — host-tested (18/18
`rawv_codec` cases, including a bounded-wait backpressure test proving a 3rd
`computeBands()` call genuinely blocks until a slot frees), task-reviewed
clean (one Minor documentation note, deferred, no fix needed). Task 2: new
dedicated Finish thread in `Capture` merges+writes each frame while the
writer/Compute thread already starts the next frame's k-selection+dispatch;
fixed the `AImage` lifetime risk by copying the raw plane into an owned
buffer before `AImage_delete()`, unconditionally, for every
`CompressedPredictive` frame. (Task 2's implementer subagent hit a session
API limit partway through — after the code, build, and commit were already
done — so the controller completed the remaining steps: verified the diff
against the brief line-by-line, re-confirmed the Android release build, and
ran this on-device checkpoint directly.)

Re-ran the same on-device check (same device, same 4096×3072@24fps,
compression confirmed genuinely ON via the recorded file's own header —
`packMode=3`):

- **1334 written / 321 dropped** over a ~40s clip (**80.6% landing, 19.4%
  loss**) — a large, genuine improvement.

**Comparison, same ~40s-clip methodology (apples-to-apples duration):**
this session's stage-1-code diagnostic (before this round's changes) showed
1091 written / 719 dropped over ~40s — 60.3% landing. This checkpoint's 80.6%
landing is a **~20 percentage point jump from pipelining alone**, matching
this round's own predicted ~78-81% (derived from removing merge+disk-write,
~18.3ms, from the per-frame serial critical path) almost exactly.

**Caveat on the "same duration" framing (raised by the final whole-plan
review):** the two clips' total arrivals don't actually match as closely as
"same ~40s" implies — the stage-1 diagnostic saw 1810 total arrivals, this
stage-2 checkpoint saw 1655, an ~8.6% difference despite both being ~40s
recordings. This doesn't undermine the landing-rate conclusion itself
(landing rate is a ratio, so duration differences mostly cancel out, and
both this checkpoint's 80.6% and the prior diagnostic's 60.3% independently
match the separately-measured phase-timing model to within ~1 percentage
point — strong evidence the improvement is real and attributable to the
pipeline, not an artifact of clip length). But the comparison's precision
was overstated; treat "~20pp improvement" as directionally solid, not as
precise to the decimal.

**Comparison against the longer, formal round 4 stage 1 checkpoint** (1437
written / 2003 dropped over ~2:06, 41.8% landing): also a large improvement,
though this checkpoint's shorter ~40s duration means it may not fully
reflect any thermal drift a longer sustained recording would show (the
stage-1 re-profiling diagnostic showed mild upward drift within its own
first-half/second-half comparison) — the same-duration comparison above is
the more reliable read on stage 2's own contribution.

**The 0-dropped-frames bar is still not met** — expected and consistent with
this round's own stated success criterion (a genuine, measured landing-rate
improvement, not 0-dropped). Dispatch+wait (~48.44ms per the last profiling
run) alone still exceeds the 41.6ms real-time budget, so some frame loss
remains even with merge and disk write fully hidden behind the next frame's
compute. Closing the rest of the gap is explicitly deferred to the two
staged follow-ups the user already approved: thread-count tuning, then
NEON — both aimed at dispatch+wait itself, the actual remaining bottleneck.

`compressedFallbacks()` was not checked this session (not wired into the
UI/logcat by default; would need temporary instrumentation to read, and the
checkpoint's own numbers already give a clear enough signal without it).

**Final whole-plan review** (most capable available model, full plan diff)
found one genuine Critical bug beyond what either task's individual review
caught: `finishLoop()`'s write-failure path discarded a queued frame without
releasing its `ParallelFrameEncoder` compute slot, which — since only 2
slots exist — would eventually deadlock the writer thread and hang
`Capture::stop()` forever after any disk write failure (e.g. `ENOSPC`
mid-recording). A second occurrence of the same root cause existed in a
currently-unreachable teardown path. Both fixed in one fix wave (commit
`6129a30`), independently re-verified ADDRESSED with no new breakage. Three
further Minor findings (per-frame ~25MB allocation churn from the raw-copy
fix, `frameEncoder_`'s pre-existing construction-before-fallible-calls
ordering, and the arrival-count caveat above) were deliberately parked, not
fixed — recorded with rulings in this plan's ledger
(`.superpowers/sdd/2026-08-05-rawv-codec-round4-compute-finish-pipeline/progress.md`),
none load-bearing for this plan's correctness.

## Round 4 stage 3 checkpoint — thread-topology tuning, 2026-08-06

Plan: `docs/superpowers/plans/2026-08-06-rawv-codec-round4-thread-topology-tuning.md`
(second of the three staged follow-ups: pipeline → **thread-count tuning** →
NEON). Code shipped on `main` as commits `59d49af` (pure `selectWorkerCores()`
big.LITTLE cluster detection), `0243f4f` (pure `workerThreadCount()` margin +
regression floor), `e97c8a8` (constructor wiring + Android-only `cpuinfo_max_freq`
sysfs read + `sched_setaffinity` worker pinning). All three task-reviewed clean;
final whole-branch review APPROVE FOR MERGE; host byte-identity preserved; arm64
`assembleDebug`/`assembleRelease` build clean. (The plan's original
`pthread_setaffinity_np` is glibc-only and absent from Android Bionic; it was
swapped during implementation to `pthread_gettid_np()` + `sched_setaffinity()`,
which is what the design spec itself specified — more spec-faithful than the plan.)

**Device topology actually read on-device** (validates the frequency-based design
over any hardcoded core-index guess): `cpuinfo_max_freq` = cpu0/1 @ 2.27 GHz
(efficiency, lowest cluster → excluded), cpu5/6 @ 2.96 GHz, cpu2/3/4 @ 3.15 GHz,
cpu7 @ 3.30 GHz (prime). `selectWorkerCores()` → `{2,3,4,5,6,7}` (6 non-efficiency
cores) → `workerThreadCount(6,4)` = **5 workers pinned to those 6 cores**, the two
efficiency cores untouched — exactly as designed. Note the spec/plan had *assumed*
efficiency = cpu6/7 and prime = cpu0; this chip is the reverse. Because selection
keys on frequency, not index, it still picked the right cluster — a hardcoded-index
approach would have pinned to the wrong cores here.

Same methodology as prior rounds (same device `24030PN60G`, same 4096×3072@24fps,
compression confirmed genuinely ON via each recorded file's own `packMode=3`
header, written frame count read from the finalized `frameCount` header field):

- **Run 1: 805 written / 218 dropped** (1023 arrivals, ~43s) — **78.7% landing**.
- **Run 2: 714 written / 230 dropped** (944 arrivals, ~40s) — **75.6% landing**.
- Both: `packMode=3`, 4096×3072, 24/1 fps, finalized `frameCount` matches the
  in-app toast, no native crash (`logcat -b crash` clean for `com.shez.rawcam`;
  the only tombstone in the session was an unrelated Instagram crash), and no
  `sched_setaffinity` failure logged (the best-effort pin applied without error;
  the code logs only on failure).

**Result: no measured landing-rate improvement over stage 2** (~77% avg vs stage
2's 80.6% — flat to marginally lower).

**Major confound — the comparison is NOT apples-to-apples.** Both stage-3 runs saw
a **stable arrival rate of ~24 frames/s** (1023/43 and 944/40), whereas the
stage-2 baseline saw **~41 frames/s** (1655 arrivals / ~40s). The whole
capture+encode pipeline delivered far fewer frames this session — i.e. it was
globally throttled: device at **~38 °C / 19 % battery / actively charging**, with
background-app notifications arriving mid-run. Under global throttling the camera's
delivery rate and the encoder's write rate scale down together, which roughly
preserves the landing *ratio* — consistent with the observed ~flat landing despite
a much lighter (and non-comparable) input load. Encoder write throughput was ~18
frames/s this session vs stage 2's ~33/s, but that gap is dominated by the
throttled state, not attributable to the code change.

**What can be concluded:**
- The topology detection + affinity pinning works correctly on real hardware (right
  cluster selected by frequency, applied without error, no crash, output still a
  valid `packMode=3` compressed clip).
- This checkpoint did **not** demonstrate the intended throughput gain, but neither
  can it cleanly *rule one out*: the device was throttled to ~24 fps arrivals
  (~half stage 2's ~41 fps), so the 5-worker pinned pool was never exercised under
  a comparable saturating load.
- Suggestive (not conclusive) read, echoing round 3's "threading alone was flat":
  adding a 5th worker + big-core affinity did not visibly help, consistent with
  dispatch+wait being **memory-bandwidth-bound rather than core-count-bound**. If
  that holds, NEON (the last approved round — it cuts the per-band *arithmetic*
  itself) is a better remaining lever than more parallelism.

**Recommended before a firm verdict / before scoping NEON:** re-run this checkpoint
on a **cool, unplugged device** (to restore the ~41 fps arrival load and remove
thermal throttling), or add temporary `std::chrono` dispatch+wait phase
instrumentation to measure per-frame compute time directly with 5 pinned workers
vs the old 4 unpinned. Either separates "topology tuning genuinely doesn't help"
from "this session was throttled." **The 0-dropped-frames bar remains unmet.**

### Phase-profiling follow-up — STAGED, blocked on device (2026-08-07)

User chose "add dispatch+wait phase profiling first" (over an immediate cool-device
re-run, proceeding to NEON, or reconsidering shipping). Temporary `std::chrono`
instrumentation was written into `core/src/rawv_codec.cpp`: it times the exact
dispatch+wait phase in `computeBands()` (the job-setup → `cvStart_.notify_all()` →
`cvDone_.wait()` span, excluding the slot-claim backpressure wait above it) and
logs a cumulative average every 50 frames under logcat tag `rawv_prof`, plus a
runtime A/B toggle via the Android debug property `debug.rawv.legacy` (`0` = shipped
5-workers-pinned config, `1` = legacy 4-workers-unpinned) read in the constructor.
Instrumented `assembleRelease` built successfully. **The `24030PN60G` then dropped
off USB before the APK could be installed, so neither A/B pass ran** — no per-frame
numbers were collected this session.

The instrumentation was reverted so `main` stays clean at `d3f1859`; the exact diff
is saved as `.superpowers/sdd/2026-08-06-rawv-codec-round4-thread-topology-tuning/phase-profiling-instrumentation.patch`.
**To resume:** reconnect + unlock the device, `git apply` that patch (or reuse the
already-built instrumented `app-release.apk` if still present), then for each config
`adb shell setprop debug.rawv.legacy 0|1` → force-stop → relaunch → record ~30s →
read the `rawv_prof` "dispatch+wait avg" line (and the `ctor threadCount=` line to
confirm 5 vs 4). A meaningfully lower per-frame dispatch+wait at 5-pinned ⇒
core-count-bound (topology tuning helps, was masked by throttling); ≈ equal ⇒
memory-bandwidth-bound (NEON is the better remaining lever than more parallelism).

### Phase-profiling A/B — RUN, result: pinning helps ~5-6%, near a bandwidth ceiling (2026-08-11)

The device (`24030PN60G` = **Xiaomi 14 Ultra**, Snapdragon 8 Gen 3, the same
hardware) reconnected and the A/B ran to completion. Main cam confirmed 14-bit
(`whiteLevel=16383`), `packMode=3`, 4096×3072@24fps, compression on.

**Two obstacles had to be cleared first (note for future on-device work):**
1. **HyperOS drops third-party apps' own logcat tags.** The `rawv_prof`
   `__android_log_print` lines never appeared (a shell-emitted `rawv_prof` line
   did, and the app's ART-runtime logs did — only the app's *own* tag was
   dropped). Worked around by having the instrumentation also write each line to
   a file, `…/files/clips/rawv_prof.log`, pulled via adb. Note: a plain
   `fopen(...,"a")` (`O_APPEND|O_WRONLY`) silently failed on this device's FUSE
   mount; a raw `open(O_CREAT|O_TRUNC|O_WRONLY,0644)` (the clip writer's flags)
   succeeded. Also, `::open` fails to compile under Bionic `_FORTIFY_SOURCE=2`
   (open is a fortify macro) — use unqualified `open`.
2. **Settings → "Compress recordings" was OFF**, so early runs recorded Raw16
   (`packMode=0`) with no encoder constructed and no profiling output at all.
   Had to toggle it back on (left on). Always verify `packMode@20 == 3` on the
   recorded clip before trusting an encoder measurement.

**Numbers (cumulative dispatch+wait average, same scene, back-to-back ~35s clips):**

| frames | A: 4 unpinned (`legacy=1`, ctor `threadCount=4 pinnedCores=0`) | B: 5 pinned (`legacy=0`, ctor `threadCount=5 pinnedCores=6`) |
|---|---|---|
| 50  | 45.66 ms | **42.78 ms** |
| 200 | 46.13 ms | 44.82 ms |
| 400 | 47.41 ms | 45.89 ms |
| 600 | 49.21 ms | 47.11 ms |

**B (the shipped 5-pinned config) is faster at every matched frame count by
~2-3 ms/frame (~5-6%)** — and B ran with a *thermal handicap* (immediately after
A, starting already saturated at ~90 °C on CPU2-4), so ~2-3 ms is a conservative
lower bound. The within-run upward drift (both sides) is live throttling.

**Interpretation — this resolves the "flat stage 3" question:**
- Stage 3 (topology tuning) is a **real gain**, not flat. The 2026-08-06 on-device
  checkpoint that showed ~no improvement was throttling + the noisy landing-rate
  ratio masking a genuine ~5-6% compute win. **Keep 5-pinned shipped.**
- But the gain is **strongly sublinear**: +25% workers (4→5) bought only ~5-6%,
  with diminishing returns already visible on the 5th worker. That is the
  signature of a **memory-bandwidth ceiling**, not core starvation — so adding
  more threads (6th worker, efficiency cores) will not close the gap. Both configs
  still exceed the 41.6 ms budget (B: 42.8 ms cold → 47 ms hot).
- **Decision the data supports:** the remaining levers must cut *work/data per
  sample*, not core count. **NEON** on the fused predict+residual+Rice inner loop
  is the right next round; **12-bit truncation** (drop 2 LSBs → ~2 fewer bits/sample
  for the Rice coder, est. ~20-25% smaller output *and* less encode work, lossy but
  near-visually-lossless at base ISO) stacks on top. More parallelism is a dead end.

Instrumentation reverted (main clean); device restored to the shipped
non-instrumented APK; test clips + `rawv_prof.log` deleted.

## Conclusion

**Still not ready to ship**, after five rounds of work (four throughput
rounds plus this stage-2 follow-up). The codec is
correct (host-tested round-trips, confirmed correct on real sensor data
with an in-spec compression ratio across all rounds), and the whole
plumbing chain builds clean. The design spec's explicit acceptance bar — no
dropped frames at 4096×3072@24fps — has now been tested five times and
failed five times: ~91% loss originally, ~75-79% loss after round 2
(batched bit writer + strided k-sampling), ~78.0% loss after round 3's
threading alone (statistically flat vs. round 2 — explained by the root-
cause profiling as threading the wrong phase), ~58.2% loss after round 4
stage 1's band-parallel write (the first genuine improvement since round 2),
and **~19.4% loss after round 4 stage 2's Compute/Finish pipeline** — by far
the largest single-round improvement of the whole effort (loss roughly
one-third of stage 1's, landing rate essentially doubled on a like-for-like
short-clip comparison), but still short of the 0-dropped bar.

**Open decision for the user, now with two consecutive rounds of real,
measured, on-device-confirmed improvement:** the pipeline closed most of the
remaining gap by overlapping merge+disk-write with the next frame's compute,
exactly as its own profiling-informed prediction expected. What's left is
dispatch+wait itself (~48.44ms, still over the 41.6ms budget alone) — the
two staged follow-ups the user already approved (thread-count tuning, then
NEON) target exactly that. Worth deciding explicitly whether to continue to
those, or whether 19.4% loss is close enough to reconsider the
ship-OFF-by-default option from the original open decision even before a
sixth round.
