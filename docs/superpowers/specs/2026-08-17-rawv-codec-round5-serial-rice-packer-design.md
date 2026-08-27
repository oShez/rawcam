# Round 5 — Optimized serial Rice packer — Design Spec

**Status:** Implemented and shipped to `main` 2026-08-17 -- plan `6c23dba`, tier 1 `d06a85c` (Rice q=0 fast path), tier 2 `f2b5370` (per-row adaptive capacity check), both bit-exact. ACCEPTED/CLOSED by the user on the strength of a hot, AC-plugged on-device run landing 78-91% of frames against the codec's ~91%-loss history; the spec's 0-dropped bar was never formally confirmed and no cool re-measure is pending. See `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`.

**Context:** `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
**Predecessor:** `docs/superpowers/specs/2026-08-13-rawv-codec-round4-neon-predict-simd-design.md` (NEON, host-complete/merge-clean; device A/B thermally confounded; predict shown to be only ~8% of encode CPU)

---

## 1. Problem & scope

After five throughput rounds the compressed-`.rawv` encoder still misses its one
explicit acceptance bar: **no dropped frames at 4096×3072@24fps** (41.6 ms/frame
budget) on the Xiaomi 14 Ultra (`24030PN60G`, Snapdragon 8 Gen 3). Round-4 NEON
vectorized the MED predict but a 2026-08-17 host feasibility spike proved the predict
is only ~8% of encode CPU and its serial sibling — the **Golomb-Rice bit-append in
`BitWriter::writeRice` — is the dominant cost (~84% of encode CPU, and 94–98% of
`writeRice`'s own time is the serial accumulator/byte-drain).**

**Scope:** make the serial Rice pack faster, **byte-for-byte lossless and bit-exact**,
to bring the per-frame compute stage under 41.6 ms with thermal headroom.

**Explicit non-goals:**
- **No format change.** The `.rawv` bitstream is frozen; output must equal today's
  scalar `encodeFrame` **byte-for-byte**.
- **No lossy truncation.** 12-bit LSB truncation remains a separate, later, additive
  lever — out of scope here.
- **No SIMD of the writer.** The spike showed the vectorizable part (`q = value>>k`)
  is ~2% of `writeRice` and a NEON-compute prototype was *slower* (extra memory pass).
  This round is a pure **serial** optimization.

## 2. Measure-first evidence (2026-08-17 host spike, throwaway)

Single-thread, full 4096×3072 frame, realistic residuals (k=9, **97.7% of samples
have q=0**), every packer verified **byte-identical** to the current `writeRice`:

| variant | vs current `writeRice` |
|---|---|
| compute `q>>k` only (the vectorizable part) | ~15% of cost |
| **serial bit-append only** | **94–98% of cost** (the floor) |
| **q=0 fast path, safety checks kept** (Tier 1) | **1.44×** |
| q=0 fast path + per-append check dropped + inlined (Tier 2) | **1.64×** |
| bulk 32-bit drain | 1.51× — *slower than Tier 2*, rejected |
| NEON-compute `q` + serial append | 0.73× — *slower*, rejected |

**Interpretation:** the 97.7% common case (q=0) currently costs **two** `writeBits`
calls (a 1-bit unary terminator + a k-bit remainder) where **one** `(k+1)`-bit append
is exact. Collapsing that is the entire realistic win; SIMD and bulk-drain are dead ends.

## 3. Architecture — two bit-exact tiers

Both tiers change only *how* bits are appended, never *which* bits. The scalar
`encodeFrame` remains the byte-for-byte oracle; the existing host matrix that asserts
`ParallelFrameEncoder == encodeFrame` guards both tiers automatically.

### Tier 1 — q=0 fast path in `BitWriter::writeRice` (shared, near-zero risk)

For q=0, `value < 2^k`, so the codeword is exactly the `(k+1)`-bit field whose top bit
is the unary terminator `0` followed by the k remainder bits — i.e. `value` itself as a
`(k+1)`-bit field. Replace the two-call path with one **checked** append:

```cpp
bool writeRice(uint32_t value, uint32_t k) {
  uint32_t q = value >> k;
  if (q == 0) return writeBits(value, k + 1);   // one checked append (97.7% of pixels)
  while (q >= 32) { if (!writeBits(0xFFFFFFFFu, 32)) return false; q -= 32; }
  if (!writeBits((((1u << q) - 1u) << 1), q + 1)) return false;
  if (k > 0 && !writeBits(value, k)) return false;
  return true;
}
```

- Lives in the shared `BitWriter` (anonymous namespace), so scalar `encodeFrame` and
  `ParallelFrameEncoder` change identically → their equality is preserved and, because
  the fast path is bit-exact, `encodeFrame`'s absolute output is **unchanged**.
- All capacity/overflow checks retained (`writeBits` still bounds every write).
- `k ≤ 20` (riceParamFor cap) ⇒ `k+1 ≤ 21 ≤ 32`, valid for `writeBits`.
- Measured **1.44×** on pack, byte-identical.

### Tier 2 — per-row adaptive capacity check in `computeAndPackBand` (parallel hot path)

The remaining ~0.2× comes from removing the *per-append* capacity branch. It cannot be
dropped outright — it is the overflow-detection mechanism (`writeRice` returning false
sets `jobOverflowed_`). Instead, **hoist it to once per row**:

- Precompute a true worst-case per-row byte bound from the frame's fixed `k` and
  `bitDepth`: `maxCodewordBits = (2^(bitDepth+1-k)) + 1 + k` (since zigzag `value <
  2^(bitDepth+1)` ⇒ `q < 2^(bitDepth+1-k)`); `worstRowBytes = ceil(width * maxCodewordBits / 8)`.
  Clamp the exponent at 0 (k ≥ bitDepth+1 ⇒ q always 0).
- Before packing each row: if `remainingCapacity >= worstRowBytes`, pack the row with an
  **unchecked** fast `put` loop (the Tier-1 fast path, minus the per-append bound); else
  pack the row with the **checked** `writeRice` (Tier 1), which sets `jobOverflowed_`
  exactly as today on overrun.
- Both paths emit identical bytes ⇒ bit-exact. Overflow semantics unchanged.
- For realistic footage (k≈6–9), `worstRowBytes` is a few tens of KB and every row takes
  the fast path. For pathological small k (near-lossless frames, tiny output) the checked
  path runs — but those frames are append-cheap anyway, so no meaningful loss.
- Scalar `encodeFrame` is untouched by Tier 2 (keeps checked `writeRice`); it still
  produces identical bytes, remaining a valid oracle.
- Measured **1.64×** on pack (Tier 1 + Tier 2 together).

### Rejected (measured)
- **Bulk 32-bit drain** — per-`put` masking overhead exceeds savings (1.51× < 1.64×).
- **SIMD / NEON compute of the writer** — 0.73×, the vectorizable slice is ~2%.

## 4. Exact behavior & bit-exactness constraints

- Output of `ParallelFrameEncoder::encode` and scalar `encodeFrame` must be **byte-for-
  byte identical to the current implementation** and to each other; `encode → decode ==
  input` losslessly. Any single-byte diff is a failure.
- `decodeFrame` is **not** modified.
- `riceParamFor` / k-selection / pass-1 sampling are **not** modified (k determines every
  codeword; unchanged).
- Fast-path algebra (q=0 ⇒ `writeBits(value, k+1)`) verified byte-identical in the spike
  across k and content; `k=0` degenerates correctly (fast path only when `value==0` ⇒
  single `0` bit, matching today).

## 5. Edge cases

- `width < 2`, `height < 2`, degenerate 1×N / N×1 frames: packing loop unchanged; fast
  path is per-sample and independent of geometry.
- `k = 0`: fast path fires only for `value == 0` (one `0` bit) — identical to current.
- `k = 20` (max): `k+1 = 21` bits, within `writeBits`' 32-bit field.
- Large quotient `q ≥ 32`: unchanged overflow drain loop (checked).
- Buffer overflow / incompressible frame: Tier 1 keeps per-append checks; Tier 2's
  checked fallback row preserves `jobOverflowed_` exactly. A worst-case (all-max /
  salt-pepper) frame that forces the fallback path must still set overflow identically.
- Multi-band splits and thread counts {1,2,5}: each band owns its BitWriter/buffer; no
  cross-band state; fast path is per-band-local.

## 6. Host testability & test matrix

- The existing `encodeFrame`-equality matrix in `core/tests/test_rawv_codec.cpp` runs the
  `ParallelFrameEncoder` path against the scalar oracle — it guards both tiers for free.
- Add a focused fast-path test: for k ∈ {0,1,6,9,13,20} and content generators {all-min,
  all-max/saturation, diagonal gradient, salt-and-pepper} across widths {1,2,3,8,9,33,64,
  257} and thread counts {1,2,5}: assert `ParallelFrameEncoder.encode == encodeFrame`
  byte-for-byte AND `decodeFrame(encode) == input`.
- Add a Tier-2 overflow test: a deliberately incompressible frame + an undersized output
  buffer, asserting the encoder reports overflow identically to the pre-change behavior
  (both fast-path-eligible and fallback rows).
- Gate: `ctest --test-dir core/build --output-on-failure` fully green.

## 7. On-device protocol (the real acceptance gate)

Host bit-exactness is necessary but not sufficient — the bar is measured, not modeled.
When the `24030PN60G` is connected + unlocked:
- Build arm64 release (`JAVA_HOME=<Android Studio jbr>`, `:app:assembleRelease`).
- Confirm Settings → "Compress recordings" ON; record ~35 s at 4096×3072@24fps; verify
  `packMode@20 == 3` and 14-bit `whiteLevel == 16383` before trusting anything.
- **Read the app's own frames-written / frames-dropped counter — the landing rate — on
  the clean (non-instrumented) build.** State plainly whether 0-dropped is met.
- Optionally re-apply the round-4 phase-profiling patch to confirm the `pack_ms` drop
  matches the ~1.4–1.6× host prediction (file-based logging — HyperOS drops the app's own
  logcat tags).
- Measure cool + unplugged to avoid the thermal confound that muddied prior checkpoints.

## 8. Acceptance criteria

- **Bit-exact gate (host, hard):** full `ctest` green; `ParallelFrameEncoder == encodeFrame`
  byte-for-byte across the matrix; round-trip lossless.
- **Measured pack speedup:** host bench and/or on-device `pack_ms` shows the expected
  ~1.4–1.6× on the pack step.
- **0-dropped, reported not gated:** on-device landing rate stated honestly. If Tier 1+2
  still misses, the round documents the residual gap and hands off to the next lever
  (12-bit truncation) — it does not silently declare success.

## 9. Sequencing

1. **Tier 1** (host, TDD): q=0 fast path in `BitWriter::writeRice` + focused test; full
   suite green; commit.
2. **Tier 2** (host, TDD): per-row adaptive check in `computeAndPackBand` + overflow test;
   full suite green; commit.
3. **arm64 build + on-device verify/measure** (device-gated): lossless round-trip on
   hardware, landing rate, pack_ms drop; write results into the open-items doc; update
   memory. `main` stays clean of throwaway instrumentation (saved as a `.patch`).
4. **Whole-branch review** before merge (task reviews per tier + final adversarial pass),
   matching prior rounds.

## 10. Self-review

- **Placeholders:** none — every tier has concrete code/behavior and measured numbers.
- **Consistency:** Tier 1 shared (oracle+parallel identical) vs Tier 2 parallel-only is
  intentional and stated; both bit-exact, so the oracle stays valid. No contradiction.
- **Scope:** single component (the Rice packer) + its one call site; fits one plan.
- **Ambiguity:** the "unchecked fast put" is defined precisely relative to Tier 1's
  checked path (same bits, no per-append bound); worst-case row bound derivation given
  explicitly so the fallback trigger is unambiguous.
