# `rawv_codec` Round 3: Row-Band Threading + NEON Vectorization

**Date:** 2026-08-05
**Status:** Design approved, not yet planned/implemented.
**Predecessor:** `docs/superpowers/plans/2026-08-05-rawv-codec-throughput.md` (round 2:
batched `BitWriter`/`BitReader` + strided Rice-k sampling, commits `e31c46d`/
`e930cd1`). Full history: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`.

## Problem

`encodeFrame()` in `core/src/rawv_codec.cpp` still fails the design spec's
real-time bar (`docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`)
at this project's usual 4096×3072@24fps recording class:

- Round 1 (naive per-bit `BitWriter`): ~91% frame loss.
- Round 2 (batched `BitWriter`/`BitReader` + strided 1/16 k-sampling): ~75-79%
  frame loss. Real ~3x improvement, still far short of the 0-dropped bar.

Root cause, unchanged from round 2's analysis: `encodeFrame`'s main pass does
~12.6-25M scalar `predictAt`/`writeBits` calls per frame — too much serial
work for the ~41.6ms/frame real-time budget, even fully batched.

## Key facts established during design (grounding, not speculation)

- **`predictAt` has no cross-band dependency in encode.** It always reads from
  the pristine input `raw16` buffer — never from a result being built up —
  unlike `decodeFrame`, where `predictAt` reads the output buffer as it's
  filled and genuinely has a sequential dependency. Row-bands in `encodeFrame`
  therefore need **zero overlap handling**: any thread can read across a band
  boundary freely.
- **NEON is guaranteed present.** This project ships `arm64-v8a` only
  (`app/build.gradle.kts`, `minSdk 33`) — NEON/ASIMD is baseline on that ISA,
  no runtime feature detection needed.
- **The predictor's stride-2 access pattern (same-Bayer-channel prediction —
  `left`/`up` are 2 samples apart, not 1) maps directly onto NEON's `vld2q_u16`
  deinterleaving load.**
- **Host tests cannot exercise real NEON instructions** — `core/build` is
  MinGW/x86-64 on Windows. This is a genuine testability gap this project
  hasn't hit before; it's accepted, not solved, by this design (see Testing).
- **Threading has no such gap.** `std::thread`/mutex/condvar are portable;
  `core/CMakeLists.txt` already links `Threads::Threads`, and `exporter.cpp`
  already uses a `std::thread` pool pattern (per-export-session, not
  per-frame — round 3 needs a persistent pool instead, see below).
- **`encodeFrame` is called from a single dedicated writer thread**
  (`capture.cpp`'s `writerLoop`, pulling frames off a queue) — parallelizing
  *inside* one `encodeFrame` call doesn't conflict with that architecture.
- **The spec is lossless-only** — no quantization/downsampling/tiling as a way
  to cut per-frame work; only real parallelism or vectorization qualify.

## Architecture

### Stage 1: Parallel predict+residual compute

Split the frame into row-bands, one per worker thread. Each thread runs
`predictAt` + `zigzagEncode` over its band, writing results into a
persistent `uint16_t` scratch buffer (`width*height` samples) at the matching
offset. `uint16_t` suffices: zigzag(residual) tops out around 32766 for this
device's 14-bit sensor, well inside range. No synchronization between bands —
each thread only reads the untouched input and writes its own disjoint output
region.

Pass 1 (k-selection via strided sampling, round 2's fix) is unchanged — already
cheap.

### Stage 2: Serial batched write

One single-threaded pass streams the scratch buffer through the existing
batched `BitWriter::writeRice()` in raster order. This stays single-threaded
because Golomb-Rice bit-packing is inherently sequential (each codeword's bit
position depends on the last), but per-pixel work here is now just a buffer
read + `writeRice` call — no predictor arithmetic, no branchy clamp/min/max.

### Thread pool lifecycle

A **persistent** pool, sized `min(hardware_concurrency(), 4)`, created once in
`Capture::start()` and joined/destroyed in `Capture::stop()` — not spawned per
frame, to keep thread-creation cost off the per-frame budget. One
dispatch-and-barrier per frame via condition variable. The scratch residual
buffer is likewise allocated once at `start()` (sized to the session's
`width*height`) and reused every frame — no per-frame heap allocation.

### NEON vectorization (stacked inside each band worker)

Applied to the per-pixel `predictAt` + zigzag-encode inner loop within each
thread's row-band — the pure-arithmetic part with no I/O:

- `vld2q_u16` deinterleaving loads for the stride-2 same-channel `left`/`up`
  accesses (8 same-channel samples per load instead of one-by-one).
- `vminq_u16`/`vmaxq_u16` for the median-of-3 clamp, vector subtract for the
  residual, vector shift+xor for zigzag encode.
- A scalar remainder loop handles the tail when a band's width isn't a
  multiple of the vector width.
- `writeRice`'s serial stream stays scalar — variable-length bit-packing
  doesn't vectorize cleanly and round 2 already made it cheap.

**Build structure:** `#ifdef __aarch64__` selects the NEON path. The
band-worker scalar implementation (needed for Stage 1 regardless) becomes the
permanent host-build fallback *and* the reference implementation NEON is
checked against. No runtime CPU feature detection — arm64-v8a is the only ABI
shipped.

## Format impact

**None.** `encodeFrame`/`decodeFrame`'s public signatures and the `.rawv`
bitstream format are unchanged — this is purely an internal restructuring of
how `encodeFrame` computes its output, matching round 2's global constraint.
No `kVersion` bump, no decoder changes, no backward-compat concerns.

## Testing & verification (two stages within one round)

**Stage A — Threading:**
1. Host test: a synthetic frame large enough to span multiple row-bands (e.g.
   512×512) asserting the new parallel-band encode produces **byte-identical**
   output to the current serial encode. This is the regression net for a
   refactor of *how* the work happens, not what it produces.
2. Full host suite green (all 8 existing suites).
3. **On-device checkpoint:** build, install, record 30s+ at
   4096×3072@24fps with compression ON. Confirm the code path actually
   exercised via the recorded file's own `packMode` header byte (not the live
   UI counter — round 2's false-negative lesson). Record written/dropped
   counts in the open-items doc. This is the clean "threading alone" data
   point.

**Stage B — NEON (stacked on top of Stage A's code):**
4. Add the `__aarch64__`-gated NEON path inside the band worker; the scalar
   path remains the host-tested fallback/reference.
5. Full host suite still green — proves the surrounding logic didn't break;
   does **not** prove NEON arithmetic correctness (named limitation, not
   solved by this design).
6. **Final on-device verification:**
   - Same 30s+ 4096×3072@24fps compression-ON recording; written/dropped
     counts against the 0-dropped bar, same `packMode`-header confirmation.
   - **DNG pixel-diff export check** (the original plan's still-pending Task 8
     item): export the compressed recording to DNG and diff pixels against a
     compression-OFF take of the same content. This is the check that would
     catch a NEON arithmetic bug subtle enough to round-trip encode→decode
     internally consistently but still diverge from the true original sensor
     data.
7. Update `open-items-2026-08-04-compressed-rawv-capture.md` and the spec's
   status line with both checkpoints' numbers. Ship-ready if Stage B clears
   0-dropped; otherwise the two data points tell whoever scopes round 4
   exactly how much each piece contributed.

## Risks / open items carried forward

- Thread pool sizing (`min(hardware_concurrency(), 4)`) is a starting point,
  not device-profiled — if Stage A's checkpoint shows the gain is smaller than
  expected, revisiting the cap (or NUMA/big.LITTLE-aware core selection) is
  in scope before concluding threading itself doesn't help.
- NEON correctness rests entirely on the on-device round-trip + DNG diff
  checks (see testability gap above) — there is no finer-grained signal if
  something is subtly wrong. If Stage B's on-device check fails, the debugging
  path is comparing NEON-path output against the scalar reference path's
  output for the same input frame on-device (both compiled in, switchable),
  not a host-side bisection.
- If Stage A alone already clears the 0-dropped bar, Stage B (NEON) becomes
  optional polish rather than required — re-confirm with the user before
  spending the extra implementation/testing effort if that happens.
