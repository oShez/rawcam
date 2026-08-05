# `rawv_codec` Round 4: Band-Parallel Write + Frame Pipeline

**Date:** 2026-08-05
**Status:** Design approved, not yet planned/implemented.
**Predecessors:**
- Round 2 (`docs/superpowers/plans/2026-08-05-rawv-codec-throughput.md`): batched `BitWriter`/`BitReader` + strided k-sampling. ~91%→~75-79% frame loss.
- Round 3 (`docs/superpowers/specs/2026-08-05-rawv-codec-round3-throughput-design.md` /
  `docs/superpowers/plans/2026-08-05-rawv-codec-round3-throughput.md`): row-band-parallel
  predict+residual via `ParallelFrameEncoder` (Tasks 1-2, committed, host-tested, task-reviewed
  clean). On-device: ~78.0% loss — statistically flat vs. round 2. Tasks 4-5 (NEON,
  final verification) never implemented — abandoned once profiling (below) showed
  they'd have targeted the wrong phase.
- Full history: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
  (read the "Root cause found" section for the profiling data this design is built on).

## Problem

Round 3's on-device checkpoint showed threading the predict+residual step produced
no measurable improvement. Direct on-device profiling (615 frame samples, temporary
instrumentation, not committed) found why: that step was never the bottleneck.

| Phase | Avg | % of `encode()` |
|---|---|---|
| k-selection scan | 3.83ms | 1.8% |
| dispatch+wait (round 3's threaded predict+residual) | 21.15ms | 9.7% |
| **serial `writeRice` write pass** | **192.44ms** | **88.5%** |
| `encode()` total | 217.43ms | — |
| disk write (`RawvWriter::writeFrame`, separate from `encode()`) | 9.38ms | — |

Real-time budget: ~41.6ms/frame at 24fps. The serial Golomb-Rice bit-packing loop
(`BitWriter::writeRice`, 12.6M calls/frame) is the actual bottleneck — round 2's
assumption that this pass was "already cheap" after batching was never verified at
real camera resolution, only at host tests' 64×64 synthetic frames.

**Budget arithmetic that shapes this design:** k-selection (3.83ms) + dispatch+wait
(21.15ms) + disk write (9.38ms) alone already sum to ~34.4ms — 82% of the entire
budget — running sequentially within one `processImage()` call today. Even a
hypothetical zero-cost write pass would leave only ~7.2ms of margin. Speeding up
the write pass alone, without addressing this fixed overhead, is very unlikely to
be sufficient.

## Approach

Two changes, designed together:

1. **Band-parallel write, fused with predict+residual, zero bitstream format
   change.** Each row-band's worker computes predict→residual→Rice-pack directly
   into its own local buffer (no shared intermediate residual buffer — round 3's
   `residuals_` scratch buffer is eliminated). A cheap serial merge step then
   concatenates the per-band local bitstreams into one output, bit-identical to
   what today's single-pass `encodeFrame()` produces.
2. **A 2-stage frame pipeline** (Compute ‖ Finish) so disk I/O and the *next*
   frame's Compute stage overlap, rather than running strictly sequentially
   within one `processImage()` call as today.

Together, these change the write pass's required speedup from an unachievable
~20-40x (if disk/dispatch stayed serial and had to be netted out) down to a much
more tractable ~5x (since pipelining hides disk I/o and dispatch+wait behind the
next frame's Compute stage) — realistically reachable from band-parallelism alone
(3-4x from up to 4 cores), without requiring per-call micro-optimization on top.

### Why not the alternatives

- **Pure format-preserving per-call speedup only** (no parallelism): the write
  pass would need to drop from 192ms to single-digit milliseconds, a 20-40x
  reduction. Removing branches/improving inlining might realistically achieve
  2-4x. Not enough on its own.
- **Banded payload format with byte-aligned per-band boundaries + an internal
  offset table** (no bit-level merge, simpler per-band encode): rejected in favor
  of the bit-exact merge design below because it's the first change that would
  require `decodeFrame()`/`exporter.cpp` to become format-aware (round 3 through
  round 4's Compute stage never touch decode), plus a small compression-ratio
  cost from per-band byte-padding waste. The bit-exact merge achieves the same
  parallelism with zero decoder impact and zero compression-ratio cost.

## Architecture

### Compute stage (per-band worker pool, reused from round 3, up to 4 threads)

1. k-selection: unchanged strided-sample scan (serial, ~3.8ms), produces `k`.
2. **Fused parallel predict+pack:** dispatch to the worker pool, one band each.
   Each worker, for its row range: computes `predictAt`+`zigzagEncode` per pixel
   (same as round 3) and immediately packs the residual via its own local
   `BitWriter` into a private per-band buffer — no synchronization needed between
   bands (same non-overlap guarantee established in round 3: `predictAt` only
   ever reads the untouched input). Output per band: a packed local buffer plus
   its exact bit count (not byte-rounded — needed for correct merging).

Per-band buffer capacity: `(bandRows / height) * frameSizeBytes * 2` — a
proportional share of the existing Raw16 ceiling, doubled as headroom for uneven
noise distribution across bands. On overflow (pathological content), the whole
frame's compress attempt fails — same existing contract as today: capture.cpp
falls back to storing that one frame uncompressed.

### Finish stage (new dedicated thread)

Given Compute's output for frame N:

3. **Merge:** walk the per-band buffers in row order, bit-shift-and-OR each into
   the final contiguous output buffer using each band's exact bit count (not its
   padded byte count) — producing output bit-identical to today's `encodeFrame()`,
   since a `writeRice` codeword's bits depend only on the residual value and `k`,
   never on prior accumulator state; only the byte-alignment *offset* at which a
   band's bits land differs between "packed locally from scratch" and "packed as
   a continuation of the global stream," which the merge corrects. Only the final
   band's output gets the real trailing zero-pad flush (matching today's
   single-accumulator behavior). Cost: O(output bytes) shift+OR, no branchy
   Rice-code logic — expected low milliseconds for ~5.5MB of output.
4. **Disk write:** `writer_->writeFrame(meta, mergedBuf, mergedSize)` — unchanged.

### Pipelining

While Finish processes frame N (steps 3-4), Compute can already start frame N+1
(steps 1-2) on the worker pool — Finish doesn't use those threads. Requires
double-buffering: two rotating sets of per-band pack buffers and the final merge
buffer, so frame N+1's Compute never writes into a slot Finish is still reading.

**Compute↔Finish handoff:** a small bounded queue (depth ~2), same mutex/condvar
pattern as the existing camera→writer queue, carrying `{FrameMeta, per-band
buffers+bit-counts, k}`.

**Backpressure:** if Finish falls behind (e.g. a disk stall), Compute blocks
waiting for a free buffer slot — the same systemic drop behavior this project
already has today (the camera capture callback drops frames once its own bounded
queue fills), just with one more stage feeding into it. No new drop policy.

### A correctness risk this design surfaced: `AImage` lifetime vs. the raw16 fallback

Today, when `encode()` fails (pathological content), `capture.cpp` falls back to
writing the *original* raw sensor bytes — a pointer into the `AImage`, valid only
until `AImage_delete()`. In the pipelined design, if Compute hands off to Finish
and immediately moves to the next frame's `AImage`, that pointer could be freed
before Finish's fallback write runs.

**Fix:** Compute copies the raw plane into its own owned buffer *before* the
`AImage` is recycled, regardless of whether the frame ends up compressed or falls
back. This keeps `AImage` recycling exactly as fast as today — not entangled with
Finish's pace, which matters because a stalled Finish stage must never starve the
camera's own buffer pool.

## Format impact

**None to the `.rawv` bitstream.** `decodeFrame()` is completely unchanged — the
merge produces the same single contiguous bitstream format `encodeFrame()`
already produces. No `kVersion` bump, no `rawv.h` changes. Every existing decode
test (round 1/2 era) keeps passing untouched, since decode was never touched.

## Testing

**Host-testable (the bulk of correctness):**
- Bit-identity regression: the new pipeline's merged output must be bit-identical
  to today's serial `encodeFrame()` on a synthetic multi-band-spanning frame.
- Merge boundary coverage: cases producing every possible bit-offset phase (0-7
  bits) at band boundaries — exactly where a shift-and-OR merge bug would hide.
- Overflow fallback: pathological per-band content still correctly fails the
  whole frame (returns 0), not a partial/corrupt result.
- Multi-frame sequencing: repeated calls through the double-buffered rotation,
  each checked bit-identical — exercises buffer-rotation logic without needing
  real on-device concurrency.
- Backward compat is structural, not just tested: no decode-path changes at all.

**Only provable on-device:** whether Finish genuinely overlaps with the next
Compute (the real pipelining gain), whether the `AImage`-copy fix prevents camera
buffer starvation under sustained load, and the final 0-dropped-frames bar.

**Recommended staged verification for the eventual plan** (mirroring round 3's
threading-then-NEON checkpoint split, which correctly isolated each piece's
contribution): land band-parallel Compute+merge first — host-verified
bit-identical, *without* the Finish-thread pipelining yet (Compute stage still
does merge+write itself, serially, same as today's structure but with a
band-parallel write) — with an intermediate on-device checkpoint, then add the
Finish-thread pipelining on top with a final checkpoint. This isolates "did
band-parallel packing help" from "did pipelining also help," rather than
conflating both again the way round 3's single-shot "threading + NEON" decision
risked (mitigated there by the checkpoint the user chose; the same discipline
applies here for an even larger change).

## Risks / open items carried forward

- The ~5x write-pass speedup target assumes band-parallelism realistically
  delivers 3-4x from up to 4 cores. If the intermediate checkpoint shows less
  (e.g. memory-bandwidth contention between bands, since all 4 workers now do
  real I-O-adjacent work — writing to 4 separate buffers — not just reading, as
  round 3's predict-only workers did), per-call `writeBits`/`writeRice`
  optimization becomes necessary on top, not optional.
- The Compute↔Finish double-buffering and backpressure design is reasoned from
  this project's existing bounded-queue conventions, not yet implemented or
  concurrency-tested. This is real new synchronization surface beyond round 3's
  single dispatch-wait-write flow, and deserves proportionally more careful
  review during implementation.
- Per-band buffer capacity (`2x` proportional share) is a starting estimate, not
  measured against real sensor noise distribution across bands. If the
  intermediate checkpoint shows frequent overflow-fallback triggering on real
  footage, this ratio needs revisiting.
