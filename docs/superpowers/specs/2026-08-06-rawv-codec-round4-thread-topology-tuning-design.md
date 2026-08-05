# Compressed `.rawv` Capture — Round 4 Stage 3: Thread-Topology Tuning

Date: 2026-08-06.
Parent effort: `docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`,
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md` (full
throughput-saga history — read it first for context this doc assumes).

## Status

Design approved by user 2026-08-06. Not yet implemented.

## Problem

Round 4 stage 2 (Compute/Finish pipeline, shipped `main` commit `8425811`)
brought frame loss down to ~19.4% (80.6% landing) at this project's usual
4096×3072@24fps recording class, but the spec's own acceptance bar (0
dropped frames) is still not met. The last on-device profiling run (stage 2
checkpoint, 2026-08-06) found `dispatch+wait` — `ParallelFrameEncoder`'s
per-band fused predict+residual+Rice-pack step, run across its worker pool —
now dominates at ~48.44ms/frame (69.7% of total cost), against a ~41.6ms
real-time budget. This is the last of the three staged follow-ups the user
approved after that profiling run (pipeline → **thread-count tuning** →
NEON), and the only one of the three that can add real additional
parallelism to the still-dominant dispatch+wait phase rather than
restructuring around it.

`ParallelFrameEncoder`'s worker pool (`core/src/rawv_codec.cpp:285-291`) is
currently capped at `min(hardware_concurrency(), 4)` when constructed with
its default `threadCount=0` (which is how `capture.cpp:389` constructs it —
no explicit count passed). The test device (`24030PN60G`, Snapdragon 8 Gen
3) has 8 cores in a big.LITTLE arrangement: 1 prime + 5 performance + 2
efficiency. The flat cap of 4 leaves real headroom on this chip unused for
the phase that now matters most.

## Goal

Raise the effective worker-pool size using real core topology (not a flat
numeric guess), so `dispatch+wait` gets more genuine parallelism on
capable cores, while never scheduling workers onto the slow efficiency
cluster and never regressing below today's behavior on any device
(including ones this project hasn't profiled).

## Design

### Two-piece split (matches this project's established pure-function
convention, e.g. `LensDiscovery.discover()`)

1. **`selectWorkerCores(const std::vector<long>& maxFreqKhzPerCore) ->
   std::vector<int>`** — a pure function, added to `rawv_codec.h`/`.cpp`,
   fully host-testable via the existing `test_rawv_codec.cpp` doctest
   suite. Takes one max-frequency reading per core (`-1` sentinel for
   "unreadable"); returns the indices of every core that is NOT in the
   lowest-frequency cluster.

   Algorithm:
   - Collect distinct frequency values across entries `>= 0`.
   - If any entry is `-1` (unreadable), or fewer than 2 distinct
     frequencies exist (uniform topology) → return an empty vector. This
     is the "no confident split" signal — callers must not guess when
     topology can't be read cleanly (matches the project's established
     "don't guess on a HAL-adjacent unknown" pattern from the Camera2
     topology work).
   - Otherwise, sort distinct frequencies descending; the lowest is the
     "efficiency" cluster. Return the indices of all cores whose frequency
     is NOT in that lowest cluster (this correctly keeps BOTH the prime
     and performance clusters on a 3-cluster chip like this device's, not
     just the single highest one).

2. **A thin, Android-only sysfs reader** (new small function in
   `rawv_codec.cpp`, guarded appropriately or living where `capture.cpp`
   can call it) — reads `/sys/devices/system/cpu/cpu<N>/cpufreq/cpuinfo_max_freq`
   for each core `0..hardware_concurrency()-1`, builds the frequency
   vector (`-1` for any core whose file is missing/unreadable/non-numeric),
   and calls `selectWorkerCores()`. This is the only new platform-specific,
   host-uncoverable code in this round — kept to the smallest possible
   wrapper around the pure function, consistent with this project's
   existing convention for `app/src/main/cpp/`-only code.

### Sizing logic in `ParallelFrameEncoder`

Only applies when the caller uses the default `threadCount=0` overload
(preserves every existing test call site, which passes an explicit count
like `4` or `8` and is therefore unaffected by this change):

- `defaultCap = min(hardware_concurrency(), 4)` — today's exact existing
  behavior, computed unconditionally as the floor.
- `clusterCores = selectWorkerCores(readMaxFreqPerCore())`.
- **If `clusterCores` is empty** (detection failed or topology is
  uniform): `threadCount_ = defaultCap`, and no affinity call is made at
  all. Byte-for-byte identical to today's behavior on any device where
  this can't be read confidently.
- **Else**: `clusterBased = max(1, clusterCores.size() - 1)` (the
  cluster-size-minus-1 margin, leaving one big/mid core free for the
  unpinned writer/Finish/camera threads); `threadCount_ = max(clusterBased,
  defaultCap)` (regression floor — topology-aware sizing can only add
  workers relative to today's baseline, never remove them, even on a
  hypothetical future device where the "correct" topology-aware count
  would be smaller). After the worker threads are spawned, call
  `sched_setaffinity` on each worker's tid with **one shared mask**
  containing every index in `clusterCores` (not 1:1 pinning — any worker
  may run on any selected core; the band-parallel work is already
  symmetric, and there's no evidence core-index numbering carries any
  more specific meaning than cluster membership on this or any other
  device this project has profiled).

On the test device this resolves to: 3 distinct frequency clusters
detected (prime, performance, efficiency) → `clusterCores` = the 6
prime+performance core indices → `clusterBased = 5` → `threadCount_ =
max(5, 4) = 5` workers, affinity-masked to those 6 cores, never touching
the 2 efficiency cores.

### Error handling

- Unreadable/missing sysfs file for any single core → that reading is
  `-1` → the whole detection is treated as failed (empty `clusterCores`),
  not partially trusted. One bad reading invalidates the set, since a
  partial topology read could misclassify a real performance core as
  "unreadable" and silently shrink the eligible set for the wrong reason.
- `sched_setaffinity` itself failing on a given worker's tid (e.g. blocked
  by some vendor ROM's SELinux policy) is logged and otherwise ignored —
  that thread keeps running unpinned rather than crashing the encoder or
  retrying. This is strictly best-effort on top of an already-working,
  unpinned baseline; a device where affinity-setting is blocked simply
  gets today's scheduling behavior with a bigger (still floor-respecting)
  thread count.
- No change to `computeBands()`/`mergeSlot()`, the Finish thread, or the
  bit-exact merge output — this round only changes worker-pool size and
  which cores workers may run on.

### Testing

**Host-testable (added to `test_rawv_codec.cpp`):**
- `selectWorkerCores()`: uniform frequencies (all equal) → empty; 2-cluster
  split; 3-cluster split (mirrors this device's real prime/performance/
  efficiency shape) → returns prime+performance indices only; any `-1`
  present → empty; single-core input; empty input.
- `ParallelFrameEncoder`'s sizing math, exercised as pure logic fed
  synthetic `clusterCores` results (not real syscalls): cluster-based
  count above the floor, cluster-based count below the floor (proves the
  regression-floor path), empty-cluster fallback path matches
  `defaultCap` exactly.
- Existing byte-identity/equivalence tests (serial vs. parallel encoder
  output) must still pass with the new default sizing — proves the
  affinity/thread-count change doesn't alter encoded bytes, only timing.

**Not host-testable (Android-only, no host coverage by this project's
established convention, same as all of `app/src/main/cpp/`):**
- The real sysfs reader and the real `sched_setaffinity` calls — verified
  only by the on-device checkpoint.

**On-device checkpoint** (same methodology as every prior round): same
device (`24030PN60G`), same 4096×3072@24fps recording class, compression
confirmed genuinely ON via the recorded file's own `packMode` header (not
a live counter, per the round-2 false-negative lesson), landing rate
compared against stage 2's checkpoint baseline (80.6% landing / 19.4%
loss). Success is a further measured landing-rate improvement, not
necessarily reaching 0-dropped in one round — matches this effort's
established staged, profiling-informed pattern.

## Non-goals for this round

- No pinning/reservation for the Finish thread, writer thread, or camera
  callback thread — deliberately scoped out to keep this round's change
  isolated and its on-device result attributable to worker-pool topology
  alone. Can be revisited in a later round if the checkpoint still shows
  contention.
- No NEON/vectorization work — that's the next staged round (Task 4 in
  the original plan numbering), targeting the per-band predict+residual
  arithmetic itself rather than how many bands run concurrently.
- No change to the bitstream format, `kVersion`, or merge logic.
