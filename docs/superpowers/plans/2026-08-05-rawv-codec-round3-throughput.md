# `rawv_codec` Round 3: Row-Band Threading + NEON Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get `core/src/rawv_codec.cpp`'s `encodeFrame` path fast enough on-device
that `CompressedPredictive` capture meets the design spec's real-time bar (0
dropped frames at 4096×3072@24fps) — round 2 (batched `BitWriter` + strided
k-sampling) improved frame loss from ~91% to ~75-79%, still far short.

**Architecture:** Add a new `ParallelFrameEncoder` class alongside the
existing (unchanged) `encodeFrame`/`decodeFrame` free functions. It owns a
persistent thread pool (created once per recording session, not per frame)
that splits each frame into row-bands and computes the predict+residual step
for each band in parallel into a shared scratch buffer — safe with zero
synchronization between bands, since the predictor only ever reads the
untouched input frame, never a result being built. A single serial pass then
streams that scratch buffer through the existing batched `BitWriter`. NEON
vectorizes the per-band predict+residual inner loop on top of that, gated on
`__aarch64__`, with the scalar version remaining as both the host-build
fallback and the correctness reference. No `.rawv` bitstream format change.

**Tech Stack:** C++17, doctest (host tests, `core/build` via CMake+Ninja+MinGW),
Gradle/CMake (Android `app/src/main/cpp/`), adb (on-device verification),
ARM NEON intrinsics (`arm_neon.h`, arm64-v8a only).

## Global Constraints

- Bitstream format MUST stay byte-for-byte identical to what `encodeFrame`
  already produces for the same input — this is a performance change, not a
  new codec. No `rawv.h` changes, no `kVersion` bump.
- `encodeFrame`/`decodeFrame`'s existing signatures and behavior do not
  change at all — they remain exactly as they are today, both as the
  fallback path for any future caller and as the correctness reference the
  new parallel path is tested against.
- `decodeFrame` is untouched by this plan (decode was never the measured
  bottleneck — capture/encode was).
- Host tests only exercise x86-64 (MinGW) — NEON code cannot be exercised or
  proven correct by host `ctest`. This is accepted, not solved: NEON
  correctness is proven only by on-device verification (Task 5), and every
  task keeps the host build green through the scalar fallback path.
- Work directly on `main`, no worktree (this project's established
  all-session convention).
- `core/CMakeLists.txt` already links `Threads::Threads`; no build file
  changes needed for `std::thread`. `app/src/main/cpp/CMakeLists.txt` needs
  no NEON-specific flags — arm64-v8a's default AArch64 target has NEON
  baseline, `arm_neon.h` works unconditionally on that ABI.
- cmake/ctest: `C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe`
  / `ctest.exe`, `core/build`.
- adb: `C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on
  PATH). `MSYS_NO_PATHCONV=1` needed for `/sdcard/...` remote paths in Bash,
  NOT for local-path `adb install` calls. Use `uiautomator dump` for exact
  tap coordinates — never blind-retap the record button. Confirm which code
  path a recording actually used via the file's own `packMode` header byte
  (offset 20, little-endian `uint32_t`, packed struct — value `3` =
  `CompressedPredictive`), not the live UI counter (round 2's false-negative
  lesson: a stale toggle state gave a false "0 dropped" result once).

---

### Task 1: `ParallelFrameEncoder` — persistent pool, row-band scalar compute, serial write

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h`
- Modify: `core/src/rawv_codec.cpp`
- Modify: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: nothing new — reuses the existing anonymous-namespace helpers in
  `rawv_codec.cpp` (`predictAt`, `zigzagEncode`, `riceParamFor`, `BitWriter`),
  which are visible throughout that translation unit.
- Produces: `rawcam::ParallelFrameEncoder`, consumed by Task 2
  (`capture.cpp`) and Task 4 (adds the NEON path inside it):
  - `explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0)`
    — `threadCount == 0` auto-picks `min(hardware_concurrency(), 4)`; a
    nonzero value forces exactly that many worker threads (used by this
    task's own test to force a deterministic multi-band split regardless of
    the host machine's actual core count).
  - `uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth, uint8_t* out, uint32_t outCapacity)`
    — same return contract as `encodeFrame`: encoded size, or 0 if it
    wouldn't fit `outCapacity`.

- [ ] **Step 1: Add the class declaration to `rawv_codec.h`**

Replace the full contents of `core/include/rawcam/rawv_codec.h` with:

```cpp
#pragma once
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace rawcam {

// Lossless compression for one frame of RAW16 Bayer samples (row-strided,
// `rowStrideSamples` SAMPLES per row -- i.e. rowStrideBytes / 2). Predicts
// each sample from its same-color neighbors (2 samples away in each axis --
// always same CFA color regardless of RGGB/GRBG/GBRG/BGGR arrangement, so no
// CFA parameter is needed) using the MED/LOCO-I median predictor, then
// Rice-codes the signed residuals with one Golomb-Rice parameter for the
// whole frame. `bitDepth` (10/12/16) only affects the fixed baseline used to
// predict the first two rows/columns, which have no same-color neighbor yet.
//
// Returns the encoded size in bytes on success, written into `out`
// (caller-owned, must be at least `outCapacity` bytes). Returns 0 if the
// encoded output would not fit in `outCapacity` -- caller should fall back
// to storing the frame uncompressed. Never allocates, never throws.
uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height,
                      uint32_t rowStrideSamples, uint32_t bitDepth,
                      uint8_t* out, uint32_t outCapacity);

// Inverse of encodeFrame. `out` must have room for height * rowStrideSamples
// samples. Returns false if `compressed` is malformed/truncated -- caller
// should treat this as a corrupt-frame read error.
bool decodeFrame(const uint8_t* compressed, uint32_t compressedSize,
                  uint16_t* out, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, uint32_t bitDepth);

// Parallel drop-in replacement for encodeFrame(), for real-time capture
// throughput (round 3, see
// docs/superpowers/specs/2026-08-05-rawv-codec-round3-throughput-design.md).
// Splits the frame into row-bands processed by a persistent pool of worker
// threads (created once at construction, not per encode() call -- thread
// creation cost is too high to pay every frame at real-time rates),
// producing the SAME bitstream encodeFrame() would for the same input: same
// predictor, same k-selection, same Golomb-Rice coding, just computed with
// the predict+residual step parallelized across cores instead of done
// serially. width/height are fixed for the life of the encoder (matches one
// recording session, which has one fixed resolution).
class ParallelFrameEncoder {
 public:
  // threadCount: 0 (default) auto-picks min(hardware_concurrency(), 4); a
  // nonzero value forces exactly that many worker threads -- used by tests
  // to force a deterministic multi-band split regardless of the host
  // machine's actual core count (hardware_concurrency() could report 1 in
  // some CI/sandbox environments, which would silently collapse the encoder
  // to a single band and defeat the point of a "spans multiple bands" test).
  explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0);
  ~ParallelFrameEncoder();
  ParallelFrameEncoder(const ParallelFrameEncoder&) = delete;
  ParallelFrameEncoder& operator=(const ParallelFrameEncoder&) = delete;

  // Same contract as encodeFrame(): returns encoded size in bytes, or 0 if
  // it would not fit in outCapacity (caller falls back to uncompressed).
  // width/height are fixed at construction; rowStrideSamples/bitDepth may
  // vary per call (they don't, in practice, within one recording session,
  // but nothing here assumes that).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

 private:
  void workerLoop(uint32_t bandIndex);
  void computeBand(uint32_t bandStart, uint32_t bandEnd);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;
  std::vector<uint32_t> residuals_;  // width_*height_, zigzag(residual) per pixel, raster order

  // Current job, set by encode() before waking workers. Only ever touched
  // while mu_ is held by the sole caller of encode() (this project's single
  // dedicated writer thread) between generation bumps -- workers only read
  // these after observing a new generation_, which happens-after encode()'s
  // write under the same mutex.
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by encode() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};

}  // namespace rawcam
```

- [ ] **Step 2: Add the class implementation to `rawv_codec.cpp`**

At the top of `core/src/rawv_codec.cpp`, add to the includes:

```cpp
#include <condition_variable>
#include <mutex>
#include <thread>
```

(keep the existing `<algorithm>`, `<cstdlib>`, `<cstring>`).

After the closing `}  // namespace` of the anonymous namespace (i.e. right
before the existing `uint32_t encodeFrame(...)` definition), add:

```cpp
ParallelFrameEncoder::ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount)
    : width_(width), height_(height) {
  if (threadCount > 0) {
    threadCount_ = threadCount;
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    threadCount_ = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
  }
  residuals_.resize(static_cast<size_t>(width_) * height_);
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
    lock.unlock();

    // bandRows*threadCount_ <= height_ by construction (floor division), so
    // every non-last band's [bandStart,bandEnd) stays within [0,height_];
    // the last band absorbs any remainder rows up to height_ exactly.
    uint32_t bandRows = height_ / threadCount_;
    uint32_t bandStart = bandIndex * bandRows;
    uint32_t bandEnd = (bandIndex + 1 == threadCount_) ? height_ : bandStart + bandRows;
    computeBand(bandStart, bandEnd);

    lock.lock();
    if (--pending_ == 0) cvDone_.notify_one();
  }
}

void ParallelFrameEncoder::computeBand(uint32_t bandStart, uint32_t bandEnd) {
  for (uint32_t y = bandStart; y < bandEnd; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      int32_t actual = jobRaw16_[y * jobRowStrideSamples_ + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
    }
  }
}

uint32_t ParallelFrameEncoder::encode(const uint16_t* raw16, uint32_t rowStrideSamples,
                                       uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width_ == 0 || height_ == 0) return 0;

  // Pass 1: same strided-sample k-selection as encodeFrame() -- unchanged,
  // already cheap after round 2's fix.
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

  // Pass 2, stage 1: dispatch the row-band predict+residual compute to the
  // persistent worker pool and wait for it to finish.
  {
    std::lock_guard<std::mutex> lock(mu_);
    jobRaw16_ = raw16;
    jobRowStrideSamples_ = rowStrideSamples;
    jobBitDepth_ = bitDepth;
    pending_ = threadCount_;
    generation_++;
  }
  cvStart_.notify_all();
  {
    std::unique_lock<std::mutex> lock(mu_);
    cvDone_.wait(lock, [&] { return pending_ == 0; });
  }

  // Pass 2, stage 2: serial batched write over the precomputed residuals --
  // no predictor arithmetic left here, just BitWriter::writeRice calls, in
  // the same raster order encodeFrame() would produce them in.
  std::memset(out, 0, outCapacity);
  out[0] = static_cast<uint8_t>(k);
  BitWriter bw(out + 1, outCapacity - 1);
  for (uint32_t y = 0; y < height_; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      if (!bw.writeRice(residuals_[y * width_ + x], k)) return 0;
    }
  }
  return 1 + bw.finishedBytes();
}
```

- [ ] **Step 3: Add the host equivalence test**

Add `#include <algorithm>` to `core/tests/test_rawv_codec.cpp`'s includes
(needed for `std::equal`), then add this test case anywhere among the
existing `TEST_CASE`s:

```cpp
TEST_CASE("ParallelFrameEncoder produces byte-identical output to encodeFrame (round 3 threading)") {
  // 512x512 with threadCount forced to 4 guarantees a real multi-band split
  // regardless of this host machine's actual core count -- if the row-band
  // split or scratch-buffer merge logic has an off-by-one, this frame is
  // large enough and the split forced enough to surface it (a tiny frame,
  // or an unforced threadCount on a 1-core CI host, could accidentally pass
  // with a broken split by collapsing to the single-band case).
  auto src = makeFrame(512, 512, 14, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 31 + y * 17) ^ 0x5A) % (maxVal + 1));
  });
  std::vector<uint8_t> serial(static_cast<size_t>(512) * 512 * 2 + 64);
  uint32_t serialN = encodeFrame(src.data(), 512, 512, 512, 14, serial.data(),
                                  static_cast<uint32_t>(serial.size()));
  REQUIRE(serialN > 0);

  ParallelFrameEncoder parallel(512, 512, /*threadCount=*/4);
  std::vector<uint8_t> parallelOut(static_cast<size_t>(512) * 512 * 2 + 64);
  uint32_t parallelN = parallel.encode(src.data(), 512, 14, parallelOut.data(),
                                        static_cast<uint32_t>(parallelOut.size()));
  REQUIRE(parallelN == serialN);
  CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));

  // Also confirm the parallel path's own output round-trips correctly
  // through decodeFrame -- implied by byte-identity above, but checked
  // directly since it's the actual contract the app relies on.
  std::vector<uint16_t> decoded(src.size());
  REQUIRE(decodeFrame(parallelOut.data(), parallelN, decoded.data(), 512, 512, 512, 14));
  CHECK(decoded == src);
}
```

- [ ] **Step 4: Build and run the full `rawv_codec` suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the new test.

- [ ] **Step 5: Run the full host suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 9 suites pass (8 pre-existing + this one — no new test binary
was added, this test lives in the existing `test_rawv_codec` executable).

- [ ] **Step 6: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "perf: add ParallelFrameEncoder (row-band-parallel encode, persistent thread pool)"
```

---

### Task 2: Wire `ParallelFrameEncoder` into `Capture`

**Files:**
- Modify: `app/src/main/cpp/capture.h`
- Modify: `app/src/main/cpp/capture.cpp`

**Interfaces:**
- Consumes: `rawcam::ParallelFrameEncoder` from Task 1
  (`core/include/rawcam/rawv_codec.h`), specifically the constructor
  `ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0)`
  and `uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth, uint8_t* out, uint32_t outCapacity)`.
- Produces: nothing new for later tasks — this is app-level wiring only.

No new host test: `app/src/main/cpp/` has no host test coverage by this
project's established convention (native Android capture code, exercised via
on-device verification instead — Task 3).

- [ ] **Step 1: Add the member and include to `capture.h`**

In `app/src/main/cpp/capture.h`, change:
```cpp
#include "rawcam/rawv.h"
#include "rawcam/rawv_writer.h"
```
to:
```cpp
#include "rawcam/rawv.h"
#include "rawcam/rawv_codec.h"
#include "rawcam/rawv_writer.h"
```

Then change:
```cpp
  std::vector<uint8_t> packBuf_;  // preallocated Packed10/Packed12 scratch buffer
  std::vector<uint8_t> compressBuf_;  // preallocated CompressedPredictive scratch buffer
};
```
to:
```cpp
  std::vector<uint8_t> packBuf_;  // preallocated Packed10/Packed12 scratch buffer
  std::vector<uint8_t> compressBuf_;  // preallocated CompressedPredictive scratch buffer
  // Owns the persistent thread pool used by CompressedPredictive's parallel
  // encode path (round 3 throughput fix) -- constructed in start() once
  // packMode is known, destroyed in stop(). Null when compressRecordings is
  // off for this session.
  std::unique_ptr<ParallelFrameEncoder> frameEncoder_;
};
```

- [ ] **Step 2: Construct `frameEncoder_` in `start()`**

In `app/src/main/cpp/capture.cpp`'s `Capture::start()`, change:
```cpp
  width_ = width;
  height_ = height;
  rowStride_ = 0;
  writerInitialized_ = false;
  writeFailed_ = false;
  writer_.reset();
```
to:
```cpp
  width_ = width;
  height_ = height;
  rowStride_ = 0;
  writerInitialized_ = false;
  writeFailed_ = false;
  writer_.reset();
  frameEncoder_.reset();
```

Then, right after `headerTemplate_ = hdr;` (which follows the `hdr.packMode = ...`
ternary chain that resolves `compressRecordings` into `PackMode::CompressedPredictive`
or not), add:
```cpp
  headerTemplate_ = hdr;

  if (hdr.packMode == (uint32_t)PackMode::CompressedPredictive) {
    frameEncoder_ = std::make_unique<ParallelFrameEncoder>((uint32_t)width_, (uint32_t)height_);
  }
```

- [ ] **Step 3: Destroy `frameEncoder_` in `stop()`**

In `Capture::stop()`, right after:
```cpp
  stopping_.store(true);
  queueCv_.notify_all();
  if (writerThread_.joinable()) writerThread_.join();
```
add:
```cpp
  // Safe to reset only after the writer thread has joined -- it may still
  // be mid-call to frameEncoder_->encode() while draining the queue.
  frameEncoder_.reset();
```

- [ ] **Step 4: Replace the `encodeFrame` call site in `processImage`**

In `Capture::processImage`'s `CompressedPredictive` branch, change:
```cpp
    uint32_t n = 0;
    if (headerTemplate_.whiteLevel != 0) {
      const uint32_t rowStrideSamples = (uint32_t)rowStride_ / 2;
      // MUST match exporter.cpp's decode-side bitDepth derivation exactly --
      // a mismatch would corrupt the first two rows/columns of every frame.
      const uint32_t bitDepth = 32 - __builtin_clz(headerTemplate_.whiteLevel);
      n = encodeFrame(reinterpret_cast<const uint16_t*>(data), (uint32_t)width_,
                       (uint32_t)height_, rowStrideSamples, bitDepth, compressBuf_.data(),
                       (uint32_t)compressBuf_.size());
    }
```
to:
```cpp
    uint32_t n = 0;
    if (headerTemplate_.whiteLevel != 0 && frameEncoder_) {
      const uint32_t rowStrideSamples = (uint32_t)rowStride_ / 2;
      // MUST match exporter.cpp's decode-side bitDepth derivation exactly --
      // a mismatch would corrupt the first two rows/columns of every frame.
      const uint32_t bitDepth = 32 - __builtin_clz(headerTemplate_.whiteLevel);
      n = frameEncoder_->encode(reinterpret_cast<const uint16_t*>(data), rowStrideSamples,
                                 bitDepth, compressBuf_.data(), (uint32_t)compressBuf_.size());
    }
```
(`&& frameEncoder_` is defensive: `frameEncoder_` is always non-null whenever
`packMode == CompressedPredictive` per Step 2, but if that invariant were
ever violated, this falls through to the existing uncompressed-fallback path
below instead of dereferencing null.)

- [ ] **Step 5: Confirm the host suite is still green**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 9 suites pass (this task doesn't touch `core/`, but confirms
nothing upstream broke before moving to the Android build).

- [ ] **Step 6: Build the Android app**

```
.\gradlew.bat assembleDebug
```
Expected: BUILD SUCCESSFUL. This confirms `capture.cpp`/`capture.h` compile
against the new `ParallelFrameEncoder` API.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/cpp/capture.h app/src/main/cpp/capture.cpp
git commit -m "perf: wire ParallelFrameEncoder into Capture's CompressedPredictive path"
```

---

### Task 3: On-device checkpoint — threading alone

**Files:** none (not a code task).

**Interfaces:**
- Consumes: Tasks 1-2's `ParallelFrameEncoder`, already wired into
  `capture.cpp`'s existing `CompressedPredictive` branch.

This is Stage A's acceptance checkpoint — proving threading's own
contribution before NEON is added on top, so a final result that's still
short of the bar can be attributed to whichever stage under- or
over-delivered.

- [ ] **Step 1: Build release and install**

```
.\gradlew.bat assembleRelease
```
Then install via adb (uninstall/reinstall first if the previous install's
signature doesn't match, per this project's established recurring-install-
conflict fix):
```
"C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r <path to app-release.apk>
```

- [ ] **Step 2: Record with compression ON at 4096×3072@24fps**

Confirm "Compress recordings" is ON in Settings (RECORDING section) — use
`uiautomator dump` for exact tap coordinates if navigating UI, never
blind-retap. Record for at least 30 seconds. Read the written/dropped
counters (left sidebar while recording, or the post-stop toast).

- [ ] **Step 3: Confirm the recording actually used the compressed path**

List the clips directory to find the new clip's actual on-device filename,
then pull it and check the `packMode` header byte (offset 20, little-endian
`uint32_t`, value `3` = `CompressedPredictive`):

```bash
MSYS_NO_PATHCONV=1 "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell ls /sdcard/Android/data/com.shez.rawcam/files/clips/
MSYS_NO_PATHCONV=1 "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" pull /sdcard/Android/data/com.shez.rawcam/files/clips/<clip file found above> /tmp/checkpoint_a.rawv
python3 -c "
import struct
with open('/tmp/checkpoint_a.rawv', 'rb') as f:
    header = f.read(24)
print('packMode =', struct.unpack('<I', header[20:24])[0], '(3 = CompressedPredictive)')
"
```
(If `python3` isn't available, any tool that reads 4 little-endian bytes at
offset 20 works — e.g. a hex viewer.)

- [ ] **Step 4: Record the checkpoint numbers**

Add a new subsection to
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md` under
"Round 2 re-verification," titled "Round 3 checkpoint A — threading alone,"
recording: elapsed time, written/dropped counts, and the confirmed
`packMode` value. Compare against round 2's ~75-79% loss baseline and the
compression-OFF control's 0-dropped bar.

- [ ] **Step 5: Commit the documentation update**

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
git commit -m "docs: record round 3 checkpoint A (threading-only) on-device numbers"
```

---

### Task 4: NEON-vectorize the per-band predict+residual loop

**Files:**
- Modify: `core/src/rawv_codec.cpp`

**Interfaces:**
- Consumes: `ParallelFrameEncoder::computeBand` from Task 1 (modifies its
  body only — the class's public interface from Task 1 is unchanged, so
  Task 2's `capture.cpp` wiring needs no changes here).
- Produces: nothing new for later tasks — Task 5 verifies this task's
  output on-device.

This task cannot add a host test that exercises the actual NEON path (host
`ctest` is x86-64/MinGW, no ARM). The verification available here is: (a)
the code compiles for both host (scalar-only, `__aarch64__` undefined) and
Android (NEON path included), and (b) the full host suite — including Task
1's equivalence test — still passes, proving the *surrounding* logic (row-
band dispatch, boundary handling, scratch buffer indexing) wasn't broken by
this change. It does NOT prove the NEON arithmetic itself is correct — that
is Task 5's job, on real hardware. This gap is a deliberate, named
limitation (see the design doc), not an oversight.

- [ ] **Step 1: Add the NEON include and vectorized predict-residual-zigzag helper**

Near the top of `core/src/rawv_codec.cpp`, after the existing includes, add:
```cpp
#ifdef __aarch64__
#include <arm_neon.h>
#endif
```

Inside the anonymous namespace, after `predictAt`'s definition (and before
`riceParamFor`), add:
```cpp
#ifdef __aarch64__
// Vectorized MED-predict + residual + zigzag for 4 consecutive same-row
// samples at (x..x+3, y). Only valid where every one of the 4 lanes has
// both a "left" (x>=2) and "up" (y>=2) neighbor -- the caller only invokes
// this for that interior region. The predictor's same-CFA-color neighbors
// are exactly 2 samples away in each axis (see predictAt's comment above),
// which is a constant -2 element offset, not an interleave -- so "left" and
// "upleft" are plain contiguous loads shifted 2 elements earlier from
// "actual"/"up" respectively, not a vld2-style deinterleave.
inline void predictResidualZigzag4(const uint16_t* plane, uint32_t rowOff, uint32_t upRowOff,
                                    uint32_t x, uint32_t* outResiduals) {
  int32x4_t actual = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(plane + rowOff + x)));
  int32x4_t left = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(plane + rowOff + x - 2)));
  int32x4_t up = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(plane + upRowOff + x)));
  int32x4_t upleft = vreinterpretq_s32_u32(vmovl_u16(vld1_u16(plane + upRowOff + x - 2)));

  // Same arithmetic as medPredict(): clamp(left+up-upleft, min(left,up), max(left,up)).
  int32x4_t linear = vsubq_s32(vaddq_s32(left, up), upleft);
  int32x4_t lo = vminq_s32(left, up);
  int32x4_t hi = vmaxq_s32(left, up);
  int32x4_t predicted = vminq_s32(vmaxq_s32(linear, lo), hi);

  // Same arithmetic as zigzagEncode(): (uint32_t)(v<<1) ^ (uint32_t)(v>>31).
  int32x4_t residual = vsubq_s32(actual, predicted);
  uint32x4_t z = veorq_u32(vreinterpretq_u32_s32(vshlq_n_s32(residual, 1)),
                            vreinterpretq_u32_s32(vshrq_n_s32(residual, 31)));
  vst1q_u32(outResiduals, z);
}
#endif
```

- [ ] **Step 2: Replace `ParallelFrameEncoder::computeBand`'s body**

Replace the current body:
```cpp
void ParallelFrameEncoder::computeBand(uint32_t bandStart, uint32_t bandEnd) {
  for (uint32_t y = bandStart; y < bandEnd; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      int32_t actual = jobRaw16_[y * jobRowStrideSamples_ + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
    }
  }
}
```
with:
```cpp
void ParallelFrameEncoder::computeBand(uint32_t bandStart, uint32_t bandEnd) {
  for (uint32_t y = bandStart; y < bandEnd; y++) {
    uint32_t rowOff = y * jobRowStrideSamples_;
    if (y < 2) {
      // Neither of the first two rows has an "up" neighbor -- scalar, whole row.
      for (uint32_t x = 0; x < width_; x++) {
        int32_t actual = jobRaw16_[rowOff + x];
        int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
        residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
      }
      continue;
    }
    uint32_t upRowOff = (y - 2) * jobRowStrideSamples_;
    // x in [0,2): no "left" neighbor -- scalar.
    for (uint32_t x = 0; x < 2 && x < width_; x++) {
      int32_t actual = jobRaw16_[rowOff + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
    }
    uint32_t x = 2;
#ifdef __aarch64__
    // Interior: both neighbors present for the whole 4-wide chunk.
    for (; x + 4 <= width_; x += 4) {
      predictResidualZigzag4(jobRaw16_, rowOff, upRowOff, x, &residuals_[y * width_ + x]);
    }
#endif
    for (; x < width_; x++) {  // remainder (or the whole interior on host, no __aarch64__)
      int32_t actual = jobRaw16_[rowOff + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
    }
  }
}
```
On host (no `__aarch64__`), the vectorized loop is compiled out, leaving
exactly the boundary-split scalar loops — same results as Task 1's simpler
whole-row loop, since `predictAt` already handles the `x<2`/`y<2` cases
internally the same way. This is why Task 1's equivalence test is expected
to still pass unmodified after this change.

- [ ] **Step 3: Build and run the full host suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 9 suites pass, including Task 1's `ParallelFrameEncoder`
equivalence test — on host this only proves the boundary-split scalar
restructuring is behavior-preserving, not that the NEON path (which host
doesn't compile) is correct.

- [ ] **Step 4: Build the Android app**

```
.\gradlew.bat assembleRelease
```
Expected: BUILD SUCCESSFUL — this is the first point the NEON path actually
compiles (host build never sees `__aarch64__`).

- [ ] **Step 5: Commit**

```bash
git add core/src/rawv_codec.cpp
git commit -m "perf: NEON-vectorize ParallelFrameEncoder's per-band predict+residual loop"
```

---

### Task 5: Final on-device verification

**Files:** none (not a code task). May update:
- `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
- `docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`

**Interfaces:**
- Consumes: Task 4's NEON-enabled `ParallelFrameEncoder`, already wired into
  `capture.cpp` since Task 2 (no wiring changes needed here).

- [ ] **Step 1: Install the Task 4 release build**

(Already built in Task 4 Step 4.) Install via adb, same as Task 3 Step 1.

- [ ] **Step 2: Record with compression ON at 4096×3072@24fps**

Same procedure as Task 3 Step 2: confirm "Compress recordings" is ON, record
30+ seconds, read written/dropped counters.

- [ ] **Step 3: Confirm the recording used the compressed path**

Same `packMode`-header check as Task 3 Step 3, on this new recording.

- [ ] **Step 4: DNG pixel-diff export check**

This is the original plan's still-pending Task 8 checklist item, now the
right time to run it since encode-path correctness is what round 3 puts at
risk. Record a compression-OFF take of the same static, well-lit scene (for
a clean noise-floor comparison), then export both recordings to DNG via the
app's export feature. Pull both DNG sequences and compare corresponding
frames' pixel data (not just visual inspection) using whatever RAW-capable
tool is available (a desktop RAW converter, or a Python script with a TIFF/
DNG reader). Two independent real-sensor captures of the same scene will
never be byte-identical (shot noise differs frame to frame) — the check is
for structural correctness: no systematic offset, no shifted/garbled
regions, differences consistent with plausible read/shot noise at the
recording's ISO, not gross corruption. This is a judgment call, same as
every other on-device check in this project's history (no unit test
substitutes for real hardware here).

- [ ] **Step 5: Record the final numbers and update status**

Add "Round 3 checkpoint B — threading + NEON" to
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`,
recording: elapsed time, written/dropped counts, `packMode` confirmation,
and the DNG pixel-diff check's outcome. Compare against checkpoint A
(threading alone) and the 0-dropped bar.

Update `docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`'s
status line:
- If 0 dropped (or close enough that it's clearly no longer a systemic
  failure): mark the feature ready to ship, with the achieved numbers.
- If still short: note the numbers from both checkpoints (what threading
  alone achieved vs. threading+NEON), which tells whoever scopes a further
  round exactly how much each piece contributed — don't speculatively design
  a round 4 here.

- [ ] **Step 6: Commit the documentation update**

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md
git commit -m "docs: re-verify rawv_codec throughput after round 3 (threading + NEON)"
```
