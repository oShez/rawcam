# `rawv_codec` Round 4 Stage 2: Compute/Finish Frame Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overlap the per-frame merge+disk-write with the *next* frame's k-selection+dispatch, so the writer thread's serial per-frame critical path drops from today's ~69.5ms (k-selection + dispatch+wait + merge + disk write, all sequential) to roughly just k-selection+dispatch+wait (~51ms), by moving merge and disk write onto a new dedicated "Finish" thread.

**Architecture:** `ParallelFrameEncoder` gains a double-buffered async split of its existing `encode()`: `computeBands()` (k-selection + per-band predict+residual+Rice-pack, unchanged cost) returns a slot index; `mergeSlot()` (merge + returns bytes, unchanged cost) is now callable from a different thread than `computeBands()`. `Capture` gains a new Finish thread that pulls `{slot, raw copy, meta}` jobs off a small bounded queue fed by the writer thread (which now only computes bands and hands off, instead of blocking through merge+write), and does the merge + `RawvWriter::writeFrame()` call. This is the first of three staged follow-up rounds (pipeline → thread-count tuning → NEON), each independently checkpointed on-device.

**Tech Stack:** C++17, `std::thread`/`std::mutex`/`std::condition_variable` (no new dependencies), existing `doctest` host test framework, Android NDK (`AImageReader`/`AImage`).

## Global Constraints

- **No `.rawv` format change.** `decodeFrame()`, `rawv.h`, `kVersion` are untouched. This plan only changes `ParallelFrameEncoder`'s internal threading/buffering and `Capture`'s wiring.
- `ParallelFrameEncoder::encode()`'s existing public contract (signature, return value, byte-for-byte output) must not change — all 9 existing `rawv_codec` tests that call it must keep passing unmodified.
- Only the `PackMode::CompressedPredictive` capture path is affected. `Packed10`/`Packed12`/`Raw16` in `capture.cpp` keep their existing fully-synchronous behavior — those pack modes already show 0 dropped frames at this project's target resolution/fps (round 1 verification), so pipelining them is out of scope.
- `computeBands()` is only ever called from one thread (the writer/"Compute" thread) and `mergeSlot()` from one other thread (the new "Finish" thread) — never concurrently with themselves. This plan does not need to support concurrent callers of the same method.
- Per this project's established convention (rounds 1-4), work happens directly on `main`, no worktree.
- Build tooling: `core/build` via CMake+Ninja+MinGW, cmake/ctest at `C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\{cmake,ctest}.exe`. **The Bash tool's own sandbox silently breaks g++/native toolchain subprocess spawning** (exit 1, zero stderr, even on trivial files) — always verify builds/tests via the PowerShell tool, not Bash.
- `adb.exe` at `C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on PATH). `MSYS_NO_PATHCONV=1` needed for `/sdcard/...` remote paths in Bash, but not for local-path `adb install` calls. **Never `adb pull` a multi-GB clip just to read its header** — use `adb shell dd if=<path> bs=1 skip=20 count=4 | od -An -tu1` to read the 4-byte `packMode` field at header offset 20.
- The in-app toast after stopping a recording reports `"{written} frames, {dropped} dropped"` (confirmed this session) — written count first, not total-arrived.

---

### Task 1: `ParallelFrameEncoder` async `computeBands()`/`mergeSlot()` split

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h`
- Modify: `core/src/rawv_codec.cpp`
- Test: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: nothing new — this task only touches the existing `ParallelFrameEncoder` class.
- Produces (for Task 2):
  - `uint32_t ParallelFrameEncoder::computeBands(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth)` — runs k-selection + per-band predict+residual+Rice-pack, returns a slot index (0 or 1). Blocks if both slots already hold unmerged bands from prior calls.
  - `uint32_t ParallelFrameEncoder::mergeSlot(uint32_t slot, uint8_t* out, uint32_t outCapacity)` — merges the given slot's bands into `out` (same return contract as `encode()`: encoded byte count, or 0 on overflow/doesn't-fit). Always releases the slot. Safe to call from a different thread than the one that called `computeBands()`.
  - `ParallelFrameEncoder::encode()` keeps its exact existing signature and behavior (now implemented as `computeBands()` immediately followed by `mergeSlot()` on the same thread).

- [ ] **Step 1: Write the failing tests**

Add these three `TEST_CASE`s to the end of `core/tests/test_rawv_codec.cpp` (after the existing last test case, `"ParallelFrameEncoder handles last-band capacity correctly..."`):

```cpp
TEST_CASE("ParallelFrameEncoder computeBands()+mergeSlot() produce byte-identical output to encode(), called sequentially across 3 frames") {
  // Pins that the async split behaves identically to the old synchronous
  // encode() when used the simplest way: compute, then immediately merge,
  // one frame at a time, never overlapping two in-flight frames. This is
  // exactly the pattern encode() itself now uses internally.
  const uint32_t width = 64, height = 64;
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  std::vector<uint8_t> split(static_cast<size_t>(width) * height * 2 + 64);

  auto checkFrame = [&](const std::vector<uint16_t>& src) {
    uint32_t serialN = encodeFrame(src.data(), width, height, width, 16, serial.data(),
                                    static_cast<uint32_t>(serial.size()));
    REQUIRE(serialN > 0);
    uint32_t slot = enc.computeBands(src.data(), width, 16);
    uint32_t splitN = enc.mergeSlot(slot, split.data(), static_cast<uint32_t>(split.size()));
    REQUIRE(splitN == serialN);
    CHECK(std::equal(serial.begin(), serial.begin() + serialN, split.begin()));
  };

  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  }));
  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 7 + y * 19) ^ 0x22) % (maxVal + 1));
  }));
  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 31 + y * 3) ^ 0x33) % (maxVal + 1));
  }));
}

TEST_CASE("ParallelFrameEncoder computeBands() allows 2 outstanding unmerged slots before blocking") {
  // The pipeline design relies on double-buffering: Compute can finish frame
  // N+1's bands while Finish hasn't yet merged frame N's. Two back-to-back
  // computeBands() calls with NEITHER merged yet must both return promptly
  // (not deadlock), using two distinct slots.
  const uint32_t width = 64, height = 64;
  auto frame = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);

  uint32_t slot0 = enc.computeBands(frame.data(), width, 16);
  uint32_t slot1 = enc.computeBands(frame.data(), width, 16);
  CHECK(slot0 != slot1);

  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  CHECK(enc.mergeSlot(slot0, out.data(), static_cast<uint32_t>(out.size())) > 0);
  CHECK(enc.mergeSlot(slot1, out.data(), static_cast<uint32_t>(out.size())) > 0);
}

TEST_CASE("ParallelFrameEncoder computeBands() blocks when both slots are busy, unblocks after mergeSlot()") {
  // Backpressure: a 3rd computeBands() call with both prior slots still
  // unmerged must BLOCK (not silently drop or corrupt) until mergeSlot()
  // frees one. Run the 3rd call on a background thread with a bounded
  // std::future wait so a broken implementation fails this test instead of
  // hanging it forever.
  const uint32_t width = 64, height = 64;
  auto frame = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);

  uint32_t slot0 = enc.computeBands(frame.data(), width, 16);
  uint32_t slot1 = enc.computeBands(frame.data(), width, 16);

  auto fut = std::async(std::launch::async,
                         [&] { return enc.computeBands(frame.data(), width, 16); });
  CHECK(fut.wait_for(std::chrono::milliseconds(200)) == std::future_status::timeout);

  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  CHECK(enc.mergeSlot(slot0, out.data(), static_cast<uint32_t>(out.size())) > 0);

  CHECK(fut.wait_for(std::chrono::milliseconds(1000)) == std::future_status::ready);
  uint32_t slot2 = fut.get();
  CHECK(slot2 == slot0);  // the freed slot gets reused

  CHECK(enc.mergeSlot(slot1, out.data(), static_cast<uint32_t>(out.size())) > 0);
  CHECK(enc.mergeSlot(slot2, out.data(), static_cast<uint32_t>(out.size())) > 0);
}
```

Also add `#include <chrono>` and `#include <future>` to the top of `core/tests/test_rawv_codec.cpp`, alongside the existing `#include <algorithm>` / `#include <cstdlib>` / `#include <vector>`.

- [ ] **Step 2: Run the tests to verify they fail**

Run (via PowerShell, not Bash — see Global Constraints):
```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build C:\Users\User\rawcam\core\build
```
Expected: **build FAILS** with compile errors — `computeBands`/`mergeSlot` are not yet members of `ParallelFrameEncoder`.

- [ ] **Step 3: Modify `core/include/rawcam/rawv_codec.h`**

Replace the entire `ParallelFrameEncoder` class body with:

```cpp
// Parallel drop-in replacement for encodeFrame(), for real-time capture
// throughput. Round 4 stage 1: fuses predict+residual+Rice-pack into each
// row-band's worker. Round 4 stage 2: splits encode() into computeBands()
// (k-selection + per-band pack, the expensive ~48ms/frame step) and
// mergeSlot() (merge, ~12ms/frame) so a caller can pipeline them across
// frames -- computeBands() for frame N+1 can run while a DIFFERENT thread
// calls mergeSlot() for frame N. See
// docs/superpowers/specs/2026-08-05-rawv-codec-round4-pipeline-design.md.
// width/height are fixed for the life of the encoder (matches one recording
// session, which has one fixed resolution).
class ParallelFrameEncoder {
 public:
  // threadCount: 0 (default) auto-picks min(hardware_concurrency(), 4); a
  // nonzero value forces exactly that many worker threads -- used by tests
  // to force a deterministic multi-band split regardless of the host
  // machine's actual core count (hardware_concurrency() can report 1 in a
  // CI/sandboxed environment, which would silently collapse every band
  // test down to a single band and defeat the point of testing the merge).
  explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0);
  ~ParallelFrameEncoder();
  ParallelFrameEncoder(const ParallelFrameEncoder&) = delete;
  ParallelFrameEncoder& operator=(const ParallelFrameEncoder&) = delete;

  // Same contract as before: returns encoded size in bytes, or 0 if it would
  // not fit in outCapacity (caller falls back to uncompressed). Implemented
  // as computeBands() immediately followed by mergeSlot() on the same
  // thread -- kept for callers that don't need pipelining (all existing host
  // tests use this).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

  // Async split of encode() for pipelining: computeBands() does k-selection +
  // per-band predict+residual+Rice-pack (the CPU-heavy step) and returns a
  // SLOT index; mergeSlot() does the merge into a final contiguous bitstream
  // for that slot, and is safe to call from a DIFFERENT thread than
  // computeBands() -- this is what lets a dedicated "Finish" thread
  // merge+write frame N while computeBands() already starts on frame N+1's
  // k-selection+dispatch. See this file's history and the design doc's
  // "Pipelining" section.
  //
  // Exactly kSlotCount (2) frames' worth of computed-but-not-yet-merged band
  // buffers can be outstanding at once. If both slots are already holding
  // unmerged bands, a third computeBands() call BLOCKS until mergeSlot() is
  // called for one of them -- this is the pipeline's backpressure (mirrors
  // this project's existing bounded-queue drop-when-full pattern one level
  // up, at the camera capture callback).
  //
  // Only ever call computeBands() from one thread and mergeSlot() from one
  // (possibly different) thread -- neither is safe to call concurrently with
  // itself.
  uint32_t computeBands(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth);

  // Merges the given slot's bands (from a prior computeBands() call) into
  // `out`, same return contract as encode(). ALWAYS releases the slot before
  // returning, whether it succeeds, fails to fit outCapacity, or that slot's
  // Compute already overflowed. The caller MUST call this exactly once for
  // every computeBands() call -- skipping it leaks a slot and eventually
  // deadlocks every future computeBands() call waiting for backpressure to
  // clear.
  uint32_t mergeSlot(uint32_t slot, uint8_t* out, uint32_t outCapacity);

 private:
  static constexpr uint32_t kSlotCount = 2;

  void workerLoop(uint32_t bandIndex);
  // Computes predict+residual+Rice-pack for this band directly into this
  // slot's local buffer -- fused, no shared residual buffer.
  void computeAndPackBand(uint32_t bandIndex, uint32_t bandStart, uint32_t bandEnd, uint32_t slot);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;

  // Per-slot (double-buffered), per-band local pack buffers and each band's
  // exact bit count after computeBands() -- read by mergeSlot() once that
  // slot's workers have finished. bandPtrs_ is per-slot merge scratch,
  // rebuilt (not reallocated) at the top of each mergeSlot() call.
  std::vector<std::vector<uint8_t>> bandBufs_[kSlotCount];
  std::vector<uint64_t> bandBits_[kSlotCount];
  std::vector<const uint8_t*> bandPtrs_[kSlotCount];
  // Set by computeBands() (Compute thread), read by mergeSlot() (possibly
  // the Finish thread). Safe without their own lock: the Compute->Finish
  // handoff in Capture always goes through a mutex lock/unlock (the finish
  // job queue's mutex) between computeBands() returning and mergeSlot()
  // being called for that slot, which establishes happens-before per the
  // C++ memory model even though these fields aren't directly guarded by
  // that mutex.
  uint32_t slotK_[kSlotCount] = {0, 0};
  bool slotOverflowed_[kSlotCount] = {false, false};

  // Slot availability -- a separate mutex from the worker-dispatch mu_ below
  // so waiting for a free slot never contends with the hot per-frame
  // dispatch path. slotBusy_[s] is true from the moment computeBands()
  // claims slot s until mergeSlot() releases it.
  std::mutex slotMu_;
  std::condition_variable slotCv_;
  bool slotBusy_[kSlotCount] = {false, false};
  uint32_t nextSlot_ = 0;

  // Current job, set by computeBands() before waking workers -- only touched
  // while mu_ is held by the sole in-flight computeBands() caller; workers
  // only read after observing a new generation_, which happens-after
  // computeBands()'s write under the same mutex (same reasoning as round 3's
  // original dispatch design).
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;
  uint32_t jobK_ = 0;
  uint32_t jobSlot_ = 0;
  bool jobOverflowed_ = false;  // true if any band's local buffer couldn't hold its content

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by computeBands() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};
```

- [ ] **Step 4: Modify `core/src/rawv_codec.cpp`**

Replace the `ParallelFrameEncoder` constructor through `encode()` (everything from `ParallelFrameEncoder::ParallelFrameEncoder` down to the closing brace of the old `encode()`, i.e. from the constructor through the merge-and-return at the end of `encode()`) with:

```cpp
ParallelFrameEncoder::ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount)
    : width_(width), height_(height) {
  if (threadCount > 0) {
    threadCount_ = threadCount;
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    threadCount_ = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
  }
  // Per-band local pack buffer capacity -- unchanged sizing from round 4
  // stage 1, just allocated twice now (once per slot) for double-buffering.
  uint32_t floorBandRows = height_ / threadCount_;
  uint32_t maxBandRows = floorBandRows + threadCount_ - 1;
  uint32_t bandCapacity = maxBandRows * width_ * 2 * 2 + 64;
  for (uint32_t s = 0; s < kSlotCount; s++) {
    bandBufs_[s].resize(threadCount_);
    for (auto& buf : bandBufs_[s]) buf.resize(bandCapacity);
    bandBits_[s].resize(threadCount_, 0);
    bandPtrs_[s].resize(threadCount_);
  }

  workers_.reserve(threadCount_);
  for (uint32_t i = 0; i < threadCount_; i++) {
    workers_.emplace_back([this, i] { workerLoop(i); });
  }
}

ParallelFrameEncoder::~ParallelFrameEncoder() {
  {
    std::lock_guard<std::mutex> lock(mu_);
    stopping_ = true;
  }
  cvStart_.notify_all();
  for (auto& t : workers_) t.join();
}

void ParallelFrameEncoder::workerLoop(uint32_t bandIndex) {
  uint64_t seenGeneration = 0;
  for (;;) {
    std::unique_lock<std::mutex> lock(mu_);
    cvStart_.wait(lock, [&] { return generation_ != seenGeneration || stopping_; });
    if (stopping_) return;
    seenGeneration = generation_;
    uint32_t slot = jobSlot_;
    lock.unlock();

    // bandRows*threadCount_ <= height_ by construction (floor division), so
    // every non-last band's [bandStart,bandEnd) stays within [0,height_];
    // the last band absorbs any remainder rows up to height_ exactly.
    uint32_t bandRows = height_ / threadCount_;
    uint32_t bandStart = bandIndex * bandRows;
    uint32_t bandEnd = (bandIndex + 1 == threadCount_) ? height_ : bandStart + bandRows;
    computeAndPackBand(bandIndex, bandStart, bandEnd, slot);

    lock.lock();
    if (--pending_ == 0) cvDone_.notify_one();
  }
}

void ParallelFrameEncoder::computeAndPackBand(uint32_t bandIndex, uint32_t bandStart,
                                               uint32_t bandEnd, uint32_t slot) {
  BitWriter bw(bandBufs_[slot][bandIndex].data(),
               static_cast<uint32_t>(bandBufs_[slot][bandIndex].size()));
  bool ok = true;
  for (uint32_t y = bandStart; y < bandEnd && ok; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      int32_t actual = jobRaw16_[y * jobRowStrideSamples_ + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      uint32_t z = zigzagEncode(actual - predicted);
      if (!bw.writeRice(z, jobK_)) { ok = false; break; }
    }
  }
  // Capture the exact bit count BEFORE finishedBytes() -- see this file's
  // Global Constraints on why the order matters.
  uint64_t bits = ok ? bw.totalBits() : 0;
  if (ok) bw.finishedBytes();
  std::lock_guard<std::mutex> lock(mu_);
  bandBits_[slot][bandIndex] = bits;
  if (!ok) jobOverflowed_ = true;
}

uint32_t ParallelFrameEncoder::computeBands(const uint16_t* raw16, uint32_t rowStrideSamples,
                                             uint32_t bitDepth) {
  // Claim a free slot -- blocks here if both are still holding a previous
  // computeBands() call's unmerged bands (the pipeline's backpressure).
  uint32_t slot;
  {
    std::unique_lock<std::mutex> lock(slotMu_);
    slot = nextSlot_;
    slotCv_.wait(lock, [&] { return !slotBusy_[slot]; });
    slotBusy_[slot] = true;
    nextSlot_ = (nextSlot_ + 1) % kSlotCount;
  }

  // Pass 1: same strided-sample k-selection as before -- unchanged, already
  // cheap (avg 2.79ms on-device per round 4 stage 2's re-profiling,
  // docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md).
  constexpr uint32_t kSampleStride = 4;
  uint64_t sumAbs = 0;
  uint64_t count = 0;
  for (uint32_t y = 0; y < height_; y += kSampleStride) {
    for (uint32_t x = 0; x < width_; x += kSampleStride) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
      count++;
    }
  }
  uint32_t k = riceParamFor(sumAbs, count);

  // Dispatch: each band's worker computes predict+residual+Rice-pack
  // directly into this slot's local buffers -- fused, no shared residual
  // buffer.
  {
    std::lock_guard<std::mutex> lock(mu_);
    jobRaw16_ = raw16;
    jobRowStrideSamples_ = rowStrideSamples;
    jobBitDepth_ = bitDepth;
    jobK_ = k;
    jobSlot_ = slot;
    jobOverflowed_ = false;
    pending_ = threadCount_;
    generation_++;
  }
  cvStart_.notify_all();
  {
    std::unique_lock<std::mutex> lock(mu_);
    cvDone_.wait(lock, [&] { return pending_ == 0; });
  }

  slotK_[slot] = k;
  slotOverflowed_[slot] = jobOverflowed_;
  return slot;
}

uint32_t ParallelFrameEncoder::mergeSlot(uint32_t slot, uint8_t* out, uint32_t outCapacity) {
  uint32_t result = 0;
  if (!slotOverflowed_[slot] && outCapacity >= 1) {
    // Merge: concatenate this slot's per-band local bitstreams into one
    // bit-exact contiguous stream, same header convention as before
    // (leading k byte).
    out[0] = static_cast<uint8_t>(slotK_[slot]);
    for (uint32_t i = 0; i < threadCount_; i++) bandPtrs_[slot][i] = bandBufs_[slot][i].data();
    uint32_t merged = mergeBitstreams(bandPtrs_[slot].data(), bandBits_[slot].data(), threadCount_,
                                       out + 1, outCapacity - 1);
    result = (merged == 0) ? 0 : 1 + merged;
  }
  // Always release the slot -- see the header doc comment: the caller must
  // call this exactly once per computeBands() call, or a slot leaks and
  // every future computeBands() call eventually deadlocks.
  {
    std::lock_guard<std::mutex> lock(slotMu_);
    slotBusy_[slot] = false;
  }
  slotCv_.notify_all();
  return result;
}

uint32_t ParallelFrameEncoder::encode(const uint16_t* raw16, uint32_t rowStrideSamples,
                                       uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width_ == 0 || height_ == 0) return 0;
  uint32_t slot = computeBands(raw16, rowStrideSamples, bitDepth);
  return mergeSlot(slot, out, outCapacity);
}
```

- [ ] **Step 5: Run the full test suite to verify all tests pass**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build C:\Users\User\rawcam\core\build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir C:\Users\User\rawcam\core\build --output-on-failure
```
Expected: `test_rawv_codec` passes with **all** cases green — the 14 pre-existing cases (unchanged, proving `encode()`'s behavior is preserved) plus the 3 new ones from Step 1. All 8 host suites report 100% passed.

- [ ] **Step 6: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "feat: split ParallelFrameEncoder::encode() into async computeBands()/mergeSlot()

Round 4 stage 2 (Compute/Finish pipeline), Task 1. computeBands() does
k-selection + per-band predict+residual+pack (the ~48ms/frame step);
mergeSlot() does the merge (~12ms/frame) and is safe to call from a
different thread. Double-buffered (2 slots) so one frame's bands can be
computed while a different thread merges the previous frame's. encode()
is now computeBands()+mergeSlot() on the same thread -- unchanged
behavior, all existing tests pass unmodified.

No .rawv format change."
```

---

### Task 2: Wire `Capture`'s Finish thread, fix `AImage` lifetime, on-device checkpoint

**Files:**
- Modify: `app/src/main/cpp/capture.h`
- Modify: `app/src/main/cpp/capture.cpp`
- Modify: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`

**Interfaces:**
- Consumes: `ParallelFrameEncoder::computeBands()` / `ParallelFrameEncoder::mergeSlot()` from Task 1 (exact signatures above).
- Produces: nothing further consumes this — it's the pipeline's integration point.

**Correctness risk this task must handle:** today, when compression fails for a frame (`n == 0`), `capture.cpp` falls back to writing the *original* raw sensor bytes — a pointer into the `AImage`, valid only until `AImage_delete()`. With `mergeSlot()` now running on a separate Finish thread, by the time Finish learns a frame needs the fallback (merge didn't fit), the writer/Compute thread has likely already moved on to a *later* `AImage` and recycled this one. **Fix:** the writer/Compute thread copies the raw plane into an owned buffer *before* calling `AImage_delete()`, for every `CompressedPredictive` frame, regardless of whether it ends up compressed or falls back — this keeps `AImage` recycling exactly as fast as today, decoupled from Finish's pace.

- [ ] **Step 1: Modify `app/src/main/cpp/capture.h`**

Add `#include <deque>` to the includes at the top (alongside the existing `<array>`, `<atomic>`, etc.).

Change the `writeFailed_` member from `bool` to `std::atomic<bool>` — it's now set by the Finish thread and read by the Compute/writer thread:

```cpp
  bool writeFailed_ = false;  // set on first write failure; later frames drop, disk untouched
```
becomes:
```cpp
  // Set on first write failure (by the Finish thread, for CompressedPredictive
  // recordings); read by the writer/Compute thread at the top of every
  // processImage() call to stop attempting further writes once one fails.
  // Atomic because Finish and Compute are different threads once the
  // pipeline (round 4 stage 2) is active.
  std::atomic<bool> writeFailed_{false};
```

Add a `FinishJob` struct and the Finish-thread members, right after the existing `frameEncoder_` member and before the closing brace of the class:

```cpp
  // Owns the persistent thread pool used by CompressedPredictive's parallel
  // encode path (round 3 throughput fix) -- constructed in start() once
  // packMode is known, destroyed in stop(). Null when compressRecordings is
  // off for this session.
  std::unique_ptr<ParallelFrameEncoder> frameEncoder_;

  // --- Round 4 stage 2: Compute/Finish pipeline (CompressedPredictive only) --

  // One frame's worth of work handed from the writer/Compute thread to the
  // Finish thread. rawCopy is an OWNED copy of this frame's raw16 plane
  // bytes, made before AImage_delete() -- see this task's "AImage lifetime"
  // doc comment on finishLoop() below for why every frame needs this, not
  // just ones already known to need the fallback.
  struct FinishJob {
    uint32_t slot = 0;
    bool hasSlot = false;  // false when whiteLevel==0 or frameEncoder_ is null -- go straight to rawCopy
    FrameMeta metaBase{};  // frameIndex/droppedSoFar are filled in by Finish, not set here
    std::vector<uint8_t> rawCopy;
  };

  void finishLoop();

  static constexpr size_t kFinishQueueCap = 2;
  std::mutex finishMutex_;
  std::condition_variable finishCv_;
  std::deque<FinishJob> finishQueue_;
  std::thread finishThread_;
  std::atomic<bool> finishStopping_{false};
```

- [ ] **Step 2: Modify `app/src/main/cpp/capture.cpp` — the `CompressedPredictive` branch of `processImage()`**

Replace the entire `else if (mode == PackMode::CompressedPredictive) { ... }` block (currently calling `frameEncoder_->encode()` synchronously, then `writer_->writeFrame()`, then falling through to the shared tail) with:

```cpp
  } else if (mode == PackMode::CompressedPredictive) {
    // whiteLevel == 0 would make __builtin_clz(0) undefined below (mirrors
    // the same guard exporter.cpp applies before deriving bitDepth) -- fall
    // back to storing this one frame uncompressed, same as an encode that
    // doesn't fit the ceiling.
    FinishJob job;
    job.metaBase = meta;  // frameIndex/droppedSoFar filled in by Finish -- see finishLoop()
    if (headerTemplate_.whiteLevel != 0 && frameEncoder_) {
      const uint32_t rowStrideSamples = (uint32_t)rowStride_ / 2;
      // MUST match exporter.cpp's decode-side bitDepth derivation exactly --
      // a mismatch would corrupt the first two rows/columns of every frame.
      const uint32_t bitDepth = 32 - __builtin_clz(headerTemplate_.whiteLevel);
      job.slot = frameEncoder_->computeBands(reinterpret_cast<const uint16_t*>(data),
                                              rowStrideSamples, bitDepth);
      job.hasSlot = true;
    }

    // AImage lifetime fix (round 4 stage 2): copy the raw plane into an
    // OWNED buffer before AImage_delete(), for every frame -- not just ones
    // already known to need the uncompressed fallback. Whether this frame's
    // compression fits is only known once the Finish thread calls
    // mergeSlot(), which happens after this AImage would otherwise have been
    // recycled. This keeps AImage recycling exactly as fast as today, not
    // entangled with Finish's pace: a stalled Finish stage must never starve
    // the camera's own buffer pool.
    job.rawCopy.assign(data, data + headerTemplate_.frameSizeBytes);

    AImage_delete(image);

    {
      std::unique_lock<std::mutex> lock(finishMutex_);
      finishCv_.wait(lock, [this] {
        return finishQueue_.size() < kFinishQueueCap || finishStopping_.load();
      });
      if (finishStopping_.load()) return;  // tearing down; drop this last job
      finishQueue_.push_back(std::move(job));
    }
    finishCv_.notify_all();
    return;  // the Finish thread now owns disk write + written_/dropped_ for this frame
  } else {
```

**Important:** this changes the branch's structure from an `if (mode == A || mode == B) {...} else if (mode == C) {...} else {...}` where all three used to fall through to a shared tail (`if (!ok) {...} else {...} AImage_delete(image);`) — the `CompressedPredictive` branch now `return`s early (it already called `AImage_delete()` and handles its own counters in `finishLoop()`), so make sure the `else if (mode == PackMode::CompressedPredictive)` block's closing brace is followed directly by the pre-existing `} else { ... }` (the plain `Raw16` fallback branch) and the pre-existing shared tail (`if (!ok) {...} ... AImage_delete(image); }`) **unchanged** — that tail still runs for `Packed10`/`Packed12`/`Raw16`, exactly as before.

- [ ] **Step 3: Add `Capture::finishLoop()` to `app/src/main/cpp/capture.cpp`**

Add this new method right after `Capture::writerLoop()` (before the `// --- Lifecycle ---` comment):

```cpp
// --- Finish thread (round 4 stage 2): merges + writes CompressedPredictive
// frames, overlapped with the writer/Compute thread already computing the
// NEXT frame's bands. ---------------------------------------------------

void Capture::finishLoop() {
  for (;;) {
    FinishJob job;
    {
      std::unique_lock<std::mutex> lock(finishMutex_);
      finishCv_.wait(lock, [this] { return !finishQueue_.empty() || finishStopping_.load(); });
      if (finishQueue_.empty()) {
        if (finishStopping_.load()) break;
        continue;
      }
      job = std::move(finishQueue_.front());
      finishQueue_.pop_front();
    }
    finishCv_.notify_all();  // wake the writer/Compute thread if it was waiting for queue space

    // Same early-exit as processImage(): once a write has failed, stop
    // pounding the disk, but still drain (and silently drop) queued jobs so
    // the queue empties and this loop can exit cleanly on stop().
    if (writeFailed_.load()) {
      dropped_.fetch_add(1, std::memory_order_relaxed);
      continue;
    }

    // frameIndex must be assigned here, not at Compute time -- only Finish
    // knows the true sequential write order (Compute may already be working
    // on frame N+2's bands while this is frame N's write).
    FrameMeta meta = job.metaBase;
    meta.frameIndex = writer_->framesWritten();
    meta.droppedSoFar = (uint32_t)dropped_.load();

    uint32_t n = 0;
    if (job.hasSlot) {
      n = frameEncoder_->mergeSlot(job.slot, compressBuf_.data(), (uint32_t)compressBuf_.size());
    }

    bool ok;
    if (n > 0) {
      meta.payloadBytes = n;
      meta.compressed = 1;
      ok = writer_->writeFrame(meta, compressBuf_.data(), n);
    } else {
      // Compute already knew this would overflow (job.hasSlot == false), or
      // mergeSlot() found the merged output didn't fit outCapacity -- either
      // way, fall back to the raw copy made before this frame's AImage was
      // recycled.
      compressedFallbacks_.fetch_add(1, std::memory_order_relaxed);
      meta.payloadBytes = headerTemplate_.frameSizeBytes;
      meta.compressed = 0;
      ok = writer_->writeFrame(meta, job.rawCopy.data(), headerTemplate_.frameSizeBytes);
    }

    if (!ok) {
      // Partial-write failure (e.g. ENOSPC mid-recording): the frame was not
      // durably written, so count it as dropped and stop writing for good.
      writeFailed_.store(true);
      dropped_.fetch_add(1, std::memory_order_relaxed);
    } else {
      written_.fetch_add(1, std::memory_order_relaxed);
    }
  }
}
```

- [ ] **Step 4: Wire the Finish thread's lifecycle into `start()` and `stop()`**

In `Capture::start()`, immediately after the existing block:
```cpp
  if (hdr.packMode == (uint32_t)PackMode::CompressedPredictive) {
    frameEncoder_ = std::make_unique<ParallelFrameEncoder>((uint32_t)width_, (uint32_t)height_);
  }
```
add:
```cpp
  if (hdr.packMode == (uint32_t)PackMode::CompressedPredictive) {
    finishStopping_.store(false);
    {
      std::lock_guard<std::mutex> lock(finishMutex_);
      finishQueue_.clear();
    }
    finishThread_ = std::thread(&Capture::finishLoop, this);
  }
```
(so the combined block reads: construct `frameEncoder_`, then reset Finish's stopping flag/queue and start `finishThread_`, both gated on the same `CompressedPredictive` check.)

In `Capture::stop()`, the existing code is:
```cpp
  stopping_.store(true);
  queueCv_.notify_all();
  if (writerThread_.joinable()) writerThread_.join();

  // Safe to reset only after the writer thread has joined -- it may still
  // be mid-call to frameEncoder_->encode() while draining the queue.
  frameEncoder_.reset();
```
Change it to:
```cpp
  stopping_.store(true);
  queueCv_.notify_all();
  if (writerThread_.joinable()) writerThread_.join();

  // Drain any remaining Compute->Finish handoff work before tearing down --
  // finishLoop() keeps processing until ITS OWN queue is empty (same
  // drain-then-exit pattern as writerThread_ above), only then exits.
  if (finishThread_.joinable()) {
    finishStopping_.store(true);
    finishCv_.notify_all();
    finishThread_.join();
  }

  // Safe to reset only after BOTH threads have joined -- the Finish thread
  // may still be mid-call to frameEncoder_->mergeSlot() while draining its
  // queue.
  frameEncoder_.reset();
```

- [ ] **Step 5: Build and verify (host + Android)**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build C:\Users\User\rawcam\core\build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir C:\Users\User\rawcam\core\build --output-on-failure
```
Expected: unaffected — `capture.cpp` isn't part of the host build (native Android-only code, no host coverage by this project's convention). This just confirms Task 1's changes are still green.

Then, from the project root:
```
.\gradlew.bat assembleRelease
```
Expected: `BUILD SUCCESSFUL` — this is the real correctness gate for this task's C++ changes (compile errors, obvious type mismatches).

- [ ] **Step 6: Commit the code changes**

```bash
git add app/src/main/cpp/capture.h app/src/main/cpp/capture.cpp
git commit -m "feat: pipeline CompressedPredictive merge+write onto a Finish thread

Round 4 stage 2, Task 2. The writer thread now only calls
frameEncoder_->computeBands() (k-selection+dispatch) and hands off to a
new Finish thread for mergeSlot()+writeFrame() -- so the next frame's
Compute can start while the current frame's merge+disk-write is still in
flight. Fixes the AImage lifetime risk this creates: the raw plane is
copied into an owned buffer before AImage_delete(), for every
CompressedPredictive frame, so the uncompressed fallback path (now
decided asynchronously by Finish) never reads freed camera memory.

No .rawv format change. Packed10/Packed12/Raw16 capture paths unchanged."
```

- [ ] **Step 7: On-device checkpoint**

Ask the user to connect the physical device (model `24030PN60G`) via USB if it isn't already.

Install the release APK:
```bash
ADB="/c/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$ADB install -r /c/Users/User/rawcam/app/build/outputs/apk/release/app-release.apk
```

Wake the device and dismiss the keyguard if needed (screen may have locked between steps):
```bash
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell wm dismiss-keyguard
```

Launch the app, confirm "Compress recordings" is ON in Settings (RECORDING section — scroll down, it's below "Thermal auto-stop"), return to the camera screen, and start a recording. Use `uiautomator dump` if unsure of exact tap coordinates rather than blind-retapping. Let it record for **at least 30 seconds** (use a background `sleep` command — e.g. `sleep 35 && echo done` with `run_in_background: true` — rather than a foreground sleep, which this environment blocks).

Stop the recording. Read the in-app toast (`"{written} frames, {dropped} dropped"`) and screenshot it for the record. Then confirm the recorded clip's `packMode` is genuinely `3` (`CompressedPredictive`) via a small byte-range read of its header — **never a full pull**:
```bash
CLIP=$($ADB shell ls -t /sdcard/Android/data/com.shez.rawcam/files/clips/ | head -1)
$ADB shell dd if=/sdcard/Android/data/com.shez.rawcam/files/clips/$CLIP/*.rawv bs=1 skip=20 count=4 2>/dev/null | od -An -tu1
```
(Byte offset 20 is `packMode` in `FileHeader`, a little-endian `uint32_t` — the 4 bytes should read `3 0 0 0`.)

Record the result in `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`, appending a new section titled `## Round 4 stage 2 checkpoint — Compute/Finish pipeline, 2026-08-05` (adjust the date if it's run on a later day) directly before the existing `## Conclusion` section. Include:
- The written/dropped counts and elapsed time, and the resulting loss percentage.
- Explicit comparison against round 4 stage 1's checkpoint (~41.8% landing) and this session's diagnostic re-profiling (~60.3% landing on a short clip) — call out whether this is a genuine, meaningful improvement, roughly flat, or worse, and state plainly which.
- Whether the result is consistent with this plan's expected-outcome framing (predicted ~78-81% landing, derived from the pre-implementation phase breakdown minus the newly-added raw-copy cost, which was not directly measured before this checkpoint) — if the actual result differs substantially, say so plainly rather than rationalizing it to fit the prediction.
- A **plain statement of whether the 0-dropped-frames bar is met** (expected: no — dispatch+wait alone, ~48ms, still exceeds the 41.6ms budget; this round's own success criterion is a genuine, measured improvement in landing rate, not 0-dropped, since that's explicitly deferred to the thread-count and NEON rounds that follow per the user's approved staging).
- Whether `compressedFallbacks()` shows any meaningful fallback rate on real footage, if convenient to check (not required — this counter isn't wired into the UI, would need a temporary logcat print to read; skip if it adds meaningfully to this task's time).

Commit the doc update:
```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
git commit -m "docs: round 4 stage 2 on-device checkpoint -- Compute/Finish pipeline"
```
