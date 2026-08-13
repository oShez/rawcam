# Round 4 (NEON) design — SIMD predict+zigzag for the compressed-.rawv encoder

**Date:** 2026-08-13
**Status:** Design, pending user review → writing-plans
**Effort:** Round 4, final approved throughput round (NEON). Follows stage 1
(band-parallel write), stage 2 (Compute/Finish pipeline), stage 3
(thread-topology tuning).
**Files in play:** `core/src/rawv_codec.cpp`, `core/include/rawcam/rawv_codec.h`,
host CMake test target, new `third_party/neon2sse/NEON_2_SSE.h`.
**Context:** `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
(read the 2026-08-11 phase-profiling A/B section for the measured baseline).

---

## 1. Goal & scope

Cut per-frame **predict + zigzag** compute in the RawCam compressed-`.rawv`
encoder using ARM NEON SIMD, as a **pure, bit-exact performance change** — the
lossless bitstream format stays byte-for-byte identical. The objective is a
lower per-frame Compute time on the `24030PN60G` (Xiaomi 14 Ultra, Snapdragon
8 Gen 3) at 4096×3072@24 fps, narrowing the ~19.4% frame loss measured after
round 4 stage 2.

**In scope**

- Vectorize the MED/LOCO-I predict + residual + zigzag in
  `ParallelFrameEncoder::computeAndPackBand`.
- Measured-in: optionally vectorize the serial **pass-1 k-selection scan**
  (it is on the single-threaded critical path — see §2 on why that matters),
  provided it reproduces the identical `sumAbs`.

**Out of scope this round (deliberately deferred)**

- 12-bit truncation / any lossy path. Decided as a *separate* round 5 once we
  have NEON's real on-device number. (Scope decision made during brainstorming:
  a lossy path cannot be bit-exact-verified against the current output, so
  coupling it to a mechanical SIMD change would muddy the correctness bar.)
- Any format, metadata (white/black level), CinemaDNG-export, or settings-UI
  change.
- Any change to the k-selection **sampling pattern**. Changing which pixels
  pass-1 samples changes `k`, which changes every Rice codeword, which breaks
  bit-exactness. Pass-1 may be vectorized only if it yields the identical
  `sumAbs`.

## 2. Governing risk & the measure-first stance

NEON can vectorize **predict + zigzag** (pure integer arithmetic). It cannot
meaningfully vectorize **`writeRice`** — variable-length Golomb-Rice codewords
packed into a serial 64-bit bit accumulator are inherently sequential. So
NEON's payoff ceiling equals whatever fraction of band-pack time is
predict+zigzag versus Rice packing. **That split is currently unmeasured.**

Separately, the open-items doc attributes the sublinear 4→5 worker scaling
(~5-6%) to a memory-bandwidth ceiling. That is *plausible but not
established*: a fixed serial tail — pass-1 k-selection (~2.79 ms,
single-threaded) plus the merge (~12 ms, single-threaded) — produces the exact
same diminishing-returns curve via Amdahl, with no bandwidth ceiling required.
The two hypotheses imply different highest-leverage NEON targets:

- **Predict-bound / load-bound:** vectorizing the band-pack predict wins big.
- **Rice-pack-bound (serial):** NEON barely moves band-pack; 12-bit (fewer bits
  to pack) is the real lever, and this round should expect a modest result.
- **Amdahl / serial-tail-bound:** the pass-1 scan and merge matter as much as
  the parallel band-pack; vectorizing pass-1 (on the critical path) pays off.

**Design consequence:** the round *opens* with a cheap throwaway on-device
measurement that separates these (predict-vs-pack split + pass-1/band-pack/merge
three-way split), so we know NEON's ceiling **before** sinking the full
implementation, and have a concrete baseline to compare the finished build
against. See §7.

## 3. Architecture — what changes, what stays fixed

**New primitive** (`rawv_codec.cpp`, anonymous namespace): a vectorized
interior predictor+zigzag. For a contiguous run of interior samples
(x ≥ 2, y ≥ 2) it produces the identical `uint32_t z` sequence that
`zigzagEncode(actual − predictAt(...))` produces sample-by-sample scalar-side.

**`computeAndPackBand`** calls the primitive for each row's interior span and
feeds the resulting `z` values into the **unchanged** `writeRice` / `BitWriter`.
Edge columns (x < 2), edge rows (y < 2), and the vector tail stay scalar via the
existing `predictAt` — so those paths are identical-to-oracle by construction.

**Unchanged:** `writeRice`, `BitWriter`, `appendBits`, `mergeBitstreams`, the
bitstream format, `decodeFrame`, and the k-selection math (`riceParamFor`).

**`encodeFrame` stays fully scalar** and serves as the **bit-exact oracle** the
host test asserts `ParallelFrameEncoder` against. (This equality is already an
existing invariant of the codec — the band-merge is documented to produce
output byte-identical to a single raster-order `BitWriter`. The NEON change
must preserve it; the host test that enforces it becomes the NEON guard.)

## 4. The vectorized math (why it is exact)

All arithmetic is **int32, integer-ops-only** — the rule that keeps the SSE and
NEON code paths bit-identical (float/reciprocal intrinsics differ between the
two ISAs; we use none). Per lane, exactly reproducing `medPredict` +
`zigzagEncode`:

```
left   = plane[y*stride + (x-2 ..)]        // unaligned load, offset -2 in x
up     = plane[(y-2)*stride + (x ..)]
upleft = plane[(y-2)*stride + (x-2 ..)]
actual = plane[y*stride + (x ..)]

linear = left + up - upleft                // vaddq_s32 / vsubq_s32
lo     = min(left, up)                      // vminq_s32
hi     = max(left, up)                      // vmaxq_s32
pred   = max(lo, min(linear, hi))           // == std::clamp(linear, lo, hi)
r      = actual - pred                       // vsubq_s32
z      = (r << 1) ^ (r >> 31)                // vshlq_n_s32 / vshrq_n_s32 (arith) / veorq_s32
```

`min(left,up)` and `max(left,up)` are the clamp bounds; `max(lo, min(linear,hi))`
is exactly `std::clamp(linear, lo, hi)` because `lo ≤ hi` always. This matches
`medPredict`'s `std::clamp(left+up-upleft, min, max)` bit-for-bit.

**Type width.** Samples are uint16 (14-bit sensor values ≤ 16383). They are
widened uint16 → int32 for the arithmetic: `r` reaches ±16383 and `r << 1`
reaches ±65532, which overflows int16 — so **int32 is mandatory**, 4 lanes per
128-bit q-register (process 8 per iteration with two q-registers, tail handled
scalar). `vshrq_n_s32(r, 31)` is an *arithmetic* right shift producing the sign
mask, matching scalar `v >> 31` on a signed int32.

Every op above has an exact SSE4.1 equivalent under `ARM_NEON_2_x86_SSE`
(`_mm_min_epi32`, `_mm_max_epi32`, `_mm_add/sub_epi32`, `_mm_slli/srai_epi32`,
`_mm_xor_si128`). Therefore **host-SSE output == device-NEON output == scalar
output**, byte-for-byte.

## 5. Edge & tail handling

- **Rows y = 0, 1** (no same-color row two above): fully scalar via existing
  `predictAt` (2 of 3072 rows — negligible).
- **Columns x = 0, 1** inside interior rows (no same-color column two to the
  left): 2 scalar samples per row via `predictAt`.
- **Interior x ≥ 2, y ≥ 2:** vectorized, lanes of 4 (or 8).
- **Tail** where `(width − 2)` is not a whole lane multiple: scalar remainder.
  No assumption that width (4096) is a convenient multiple; correctness holds
  for tiny and odd widths (test matrix §6 includes 1–9 px).

Because every non-interior sample uses the *same* `predictAt` the oracle uses,
the only new logic under test is the interior vector primitive.

## 6. Correctness bar & test matrix (the hard gate)

**Bar:** `ParallelFrameEncoder`'s output must equal the scalar `encodeFrame`
output **byte-for-byte**, and `encode → decode == input` must hold losslessly.

Host `ctest`, `ParallelFrameEncoder.encode()` vs `encodeFrame()` byte-equality
across:

- **Content:** random noise (multiple fixed seeds), all-flat, horizontal /
  vertical / diagonal gradients, salt-and-pepper high-frequency edges, min/max
  saturation (0 and 16383 fields).
- **bitDepth:** 10, 12, 16.
- **Dimensions:** several widths including **non-lane-multiples** and **tiny
  (1–9 px)**; heights that exercise the y < 2 rows and produce multiple bands.
- **threadCount:** forced 1, 2, and 5 (deterministic band splits independent of
  host core count).
- Plus the existing round-trip `encode → decode == input` assertions on the same
  inputs.

On the host this suite runs the vector path **via `ARM_NEON_2_x86_SSE` (SSE)** and is the
primary NEON correctness guard. On device the same equality is confirmed by a
round-trip decode of a real recorded clip. Any single byte diff fails the gate.

## 7. On-device measurement protocol

Reuses the discipline established in the 2026-08-11 A/B (see open-items doc):

- **HyperOS drops the app's own logcat tags** → instrumentation writes each line
  to a file via a raw `open(O_CREAT|O_TRUNC|O_WRONLY, 0644)` (a plain
  `fopen(...,"a")` silently fails on this device's FUSE mount; use unqualified
  `open`, not `::open`, under Bionic `_FORTIFY_SOURCE=2`), pulled via adb.
- Always verify `packMode@20 == 3` on the recorded clip and 14-bit
  `whiteLevel = 16383` before trusting any encoder measurement (Settings →
  "Compress recordings" must be ON).
- Measure on a **cool, unplugged device** to avoid the thermal confound that
  masked stage 3's result.

**Step 0 (before implementing):** throwaway patch timing **predict-vs-pack**
inside `computeAndPackBand`, plus the **pass-1 / band-pack / merge** three-way
split. Run on device, record the split → this is NEON's ceiling and the
baseline. Revert; save the diff as a patch under the round's SDD workspace
(mirroring `phase-profiling-instrumentation.patch`), leaving `main` clean.

**After NEON lands:** same instrumentation on the NEON build; report the
predict:pack:merge:pass1 split before/after and the landing rate, cool-device
and like-for-like.

## 8. Acceptance criteria

**Gate — must all pass:**

- Host bit-exact across the full §6 matrix (vector path via `ARM_NEON_2_x86_SSE`).
- Device round-trip lossless on a real recorded `packMode=3` clip.
- arm64 `assembleDebug` and `assembleRelease` build clean; host `ctest` clean.
- Integer-ops-only rule respected (no float/reciprocal intrinsics).
- `main` left clean of throwaway instrumentation.

**Goal — measured and reported, not pass/fail:**

- Reduced on-device band-pack Compute vs the shipped build, with the
  predict:pack:merge:pass1 split documented before and after.

**The 0-dropped-frames bar is measured and reported but is NOT this round's
pass/fail gate.** NEON alone may be insufficient; that number is the input to
the round-5 12-bit-truncation decision.

## 9. Task sequencing (for writing-plans)

0. **Measurement spike** (throwaway): predict-vs-pack + pass-1/band-pack/merge
   instrumentation; run on device; record ceiling + baseline; revert; save patch.
1. **`ARM_NEON_2_x86_SSE` go/no-go spike:** vendor `third_party/neon2sse/NEON_2_SSE.h`; wire
   host CMake (`RAWV_USE_NEON2SSE`, `-msse4.1` or toolchain equivalent); prove
   one vector op compiles and runs bit-exact under the actual host SDK CMake
   toolchain. If it fails to build cleanly, escalate (clang-cl / documented
   device-only-verification gap) rather than silently dropping to hand-NEON +
   scalar-host (Option A).
2. **Vectorized `predictRowInterior` + zigzag primitive** (TDD): unit-test the
   primitive's `z` output against scalar `predictAt`+`zigzagEncode` over random
   and edge inputs, before wiring it in.
3. **Wire into `computeAndPackBand`** using Approach 2 (per-row `int32` residual
   scratch, cache-resident); run the full §6 host bit-exact + round-trip matrix.
4. **arm64 build + device round-trip + measure**; compare to Step-0 ceiling on a
   cool device; write the result into the open-items doc.
5. **(Optional, measured-in)** Approach 1 (chunked interleave, ~8-int stack
   scratch) if step-4's profile shows the per-row scratch as real traffic. Both
   approaches feed the identical `z` stream to `writeRice`, so this is a
   drop-in, still bit-exact.

## 10. Alternatives considered (brainstorming record)

- **Scope: NEON + 12-bit together** — rejected. Couples a lossy quality/format
  decision (metadata, export, UI, perceptual bar) to a mechanical bit-exact
  optimization; the lossy path can't be verified against current output. 12-bit
  deferred to round 5, decided with NEON's real number in hand.
- **SIMD style: hand NEON + scalar host fallback (Option A)** — rejected. Leaves
  the correctness-critical vector code untested on the one harness runnable
  under PowerShell (host CMake); the byte-for-byte bar would rest on device
  round-trips alone.
- **SIMD style: portable `std::experimental::simd` / `vector_size` (Option C)** —
  viable and lighter (no vendored header), but `std::simd` availability is
  spotty on the NDK/libc++ and codegen is less predictable than hand-NEON.
  Chosen against in favor of Option B (`arm_neon.h` intrinsics + `ARM_NEON_2_x86_SSE` host
  shim), which gives real hand-written NEON on-device *and* the identical code
  under the host bit-exact assertion.
- **Micro-structure: predict-only NEON (Approach 3)** — rejected; zigzag is two
  cheap vector ops, no reason to leave it scalar.
- **Micro-structure: chunked interleave first (Approach 1)** — deferred to a
  measured-in optimization; per-row scratch (Approach 2) is simpler to land and
  provably bit-exact, and the residual scratch stays L1/L2-resident.
