# `rawv_codec` Round 4, Stage 1: Band-Parallel Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fuse predict+residual+Rice-pack into each row-band's worker (round 3's
`ParallelFrameEncoder` did predict+residual only, leaving the actual bottleneck —
the serial `writeRice` write pass, ~88.5% of `encode()`'s cost per on-device
profiling — untouched), with a cheap bit-exact merge producing output identical
to today's format. **This plan covers only stage 1 of the round 4 design**
(`docs/superpowers/specs/2026-08-05-rawv-codec-round4-pipeline-design.md`) — the
band-parallel write itself. Stage 2 (the Compute/Finish frame pipeline that
overlaps disk I/O and the next frame's compute) is a deliberately separate,
follow-up plan, scoped only after this stage's on-device checkpoint shows how
much it actually achieved — same staged-verification discipline round 3 used
(threading checkpoint before NEON).

**Architecture:** Each row-band's worker computes `predictAt`+`zigzagEncode` per
pixel and immediately packs the residual via its own local `BitWriter` into a
private per-band buffer — no shared intermediate residual buffer (round 3's
`residuals_` scratch buffer is removed). A new `mergeBitstreams()` function
concatenates the per-band local bitstreams into one bit-exact contiguous output,
using a new `BitWriter::totalBits()` accessor to know each band's exact
(unpadded) bit count. Output is bit-identical to what the unchanged serial
`encodeFrame()` free function already produces for the same input.

**Tech Stack:** C++17, doctest (host tests, `core/build` via CMake+Ninja+MinGW),
Gradle/CMake (Android `app/src/main/cpp/`), adb (on-device verification).

## Global Constraints

- Bitstream format MUST stay byte-for-byte identical to what `encodeFrame()`
  produces for the same input — this is a performance change, not a new codec.
  No `rawv.h` changes, no `kVersion` bump.
- `encodeFrame()`/`decodeFrame()`'s existing signatures and behavior do not
  change — they remain the correctness reference this round's rewrite is tested
  against, and `decodeFrame()` needs no changes since the output format doesn't
  change.
- `ParallelFrameEncoder`'s PUBLIC interface (constructor, `encode()` signature)
  does not change from round 3 — only its internals. This means
  `app/src/main/cpp/capture.cpp`'s round-3 wiring (`frameEncoder_->encode(...)`)
  needs no changes for this plan; the on-device checkpoint (Task 2) uses the
  existing call site unchanged.
- A band's local `BitWriter` must call `finishedBytes()` (flushing its trailing
  partial byte with the SAME zero-padding-in-low-bits behavior it already has)
  before `mergeBitstreams()` reads that band's buffer — the flush physically
  writes the real trailing bits into memory in the position `mergeBitstreams()`
  expects; only the *exact bit count* (captured via `totalBits()` **before**
  the flush, since `finishedBytes()` rounds up) tells the merge how many of
  those bits are real vs. zero-padding.
- Work directly on `main`, no worktree (this project's established all-session
  convention).
- cmake/ctest: `C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe`
  / `ctest.exe`, `core/build`.
- adb: `C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on
  PATH). `MSYS_NO_PATHCONV=1` needed for `/sdcard/...` remote paths in Bash, NOT
  for local-path `adb install` calls. Use `uiautomator dump`/screenshots for
  exact tap coordinates — never blind-retap. Confirm which code path a
  recording actually used via the file's own `packMode` header byte (offset 20,
  little-endian `uint32_t`, packed struct — value `3` = `CompressedPredictive`),
  not the live UI counter.

---

### Task 1: Band-parallel fused predict+pack `ParallelFrameEncoder`

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h`
- Modify: `core/src/rawv_codec.cpp`
- Modify: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: nothing new — reuses `predictAt`, `zigzagEncode`, `riceParamFor`,
  and the existing `BitWriter` class (extended with one new method, see below),
  all in `rawv_codec.cpp`'s anonymous namespace.
- Produces: `ParallelFrameEncoder`'s public interface is UNCHANGED from round 3
  (`explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0)`,
  `uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth, uint8_t* out, uint32_t outCapacity)`)
  — Task 2 (on-device checkpoint) needs no wiring changes as a result.

- [ ] **Step 1: Add `BitWriter::totalBits()`**

In `core/src/rawv_codec.cpp`'s `BitWriter` class, add this public method (after
`finishedBytes()`):

```cpp
  // Exact number of REAL bits written so far, excluding the zero-padding
  // finishedBytes() adds to byte-align a trailing partial byte. Must be
  // called BEFORE finishedBytes() -- finishedBytes() advances bytePos_ and
  // resets accBits_, so calling this after would return an inflated,
  // byte-rounded count instead of the true bit count. Used by
  // mergeBitstreams() to merge multiple independently-packed local
  // bitstreams without including any of their individual trailing padding.
  uint64_t totalBits() const {
    return static_cast<uint64_t>(bytePos_) * 8 + accBits_;
  }
```

- [ ] **Step 2: Add `appendBits()` and `mergeBitstreams()`**

In `core/src/rawv_codec.cpp`'s anonymous namespace, after the `BitWriter` class
(and before `BitReader`), add:

```cpp
// Appends `bitCount` real bits from a byte-aligned, MSB-first packed local
// buffer onto `bw` -- byte-at-a-time (not bit-at-a-time) since this may need
// to move a whole band's worth of already-packed bits. `src` must have at
// least ceil(bitCount/8) valid bytes (guaranteed by BitWriter::finishedBytes()
// having flushed the source before this is called).
inline bool appendBits(BitWriter& bw, const uint8_t* src, uint64_t bitCount) {
  uint64_t fullBytes = bitCount / 8;
  uint32_t trailingBits = static_cast<uint32_t>(bitCount % 8);
  for (uint64_t i = 0; i < fullBytes; i++) {
    if (!bw.writeBits(src[i], 8)) return false;
  }
  if (trailingBits > 0) {
    uint32_t lastBits = src[fullBytes] >> (8 - trailingBits);
    if (!bw.writeBits(lastBits, trailingBits)) return false;
  }
  return true;
}

// Concatenates `bandCount` independently-packed local bitstreams (each
// produced by a per-band BitWriter, flushed via finishedBytes() with its
// REAL bit count captured beforehand via totalBits()) into one bit-exact
// contiguous stream written into `out`. Produces byte-for-byte identical
// output to what a single BitWriter packing the same sequence of
// writeRice() calls in raster order would have produced -- a Rice
// codeword's bits depend only on its own value and k, never on prior
// accumulator state, so only the byte OFFSET at which a band's bits land
// differs between "packed alone" (byte-aligned start) and "packed as a
// continuation of the previous band" (usually mid-byte), which this
// corrects band-by-band via appendBits(). Returns the merged byte count
// (matching BitWriter::finishedBytes()'s contract), or 0 if it doesn't fit
// outCapacity -- caller falls back to storing the frame uncompressed, same
// as encodeFrame()'s existing contract.
uint32_t mergeBitstreams(const uint8_t* const* bandBufs, const uint64_t* bandBits,
                          uint32_t bandCount, uint8_t* out, uint32_t outCapacity) {
  BitWriter bw(out, outCapacity);
  for (uint32_t b = 0; b < bandCount; b++) {
    if (!appendBits(bw, bandBufs[b], bandBits[b])) return 0;
  }
  return bw.finishedBytes();
}
```

- [ ] **Step 3: Replace `ParallelFrameEncoder`'s declaration in `rawv_codec.h`**

Replace the existing `class ParallelFrameEncoder { ... };` block (the whole
class, from round 3) with:

```cpp
// Parallel drop-in replacement for encodeFrame(), for real-time capture
// throughput. Round 4: fuses predict+residual+Rice-pack into each row-band's
// worker (round 3 only parallelized predict+residual, leaving the actual
// bottleneck -- the serial write pass, ~88.5% of encode() cost per on-device
// profiling, 2026-08-05 -- untouched). Each band packs directly into its own
// local buffer; a cheap serial merge (see mergeBitstreams() in
// rawv_codec.cpp) concatenates them into one bit-exact stream, identical to
// what encodeFrame() produces for the same input. See
// docs/superpowers/specs/2026-08-05-rawv-codec-round4-pipeline-design.md.
// width/height are fixed for the life of the encoder (matches one recording
// session, which has one fixed resolution).
class ParallelFrameEncoder {
 public:
  // threadCount: 0 (default) auto-picks min(hardware_concurrency(), 4); a
  // nonzero value forces exactly that many worker threads -- used by tests
  // to force a deterministic multi-band split regardless of the host
  // machine's actual core count.
  explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0);
  ~ParallelFrameEncoder();
  ParallelFrameEncoder(const ParallelFrameEncoder&) = delete;
  ParallelFrameEncoder& operator=(const ParallelFrameEncoder&) = delete;

  // Same contract as encodeFrame(): returns encoded size in bytes, or 0 if
  // it would not fit in outCapacity (caller falls back to uncompressed).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

 private:
  void workerLoop(uint32_t bandIndex);
  // Computes predict+residual+Rice-pack for this band directly into its
  // local buffer -- fused, no shared residual buffer.
  void computeAndPackBand(uint32_t bandIndex, uint32_t bandStart, uint32_t bandEnd);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;

  // Per-band local pack buffers (sized once at construction) and each
  // band's exact bit count after the last encode() call -- read by
  // encode()'s merge step once all workers finish.
  std::vector<std::vector<uint8_t>> bandBufs_;
  std::vector<uint64_t> bandBits_;

  // Current job, set by encode() before waking workers -- see round 3's
  // original comment on this pattern: only touched while mu_ is held by
  // the sole caller of encode(), workers only read after observing a new
  // generation_, which happens-after encode()'s write under the same mutex.
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;
  uint32_t jobK_ = 0;
  bool jobOverflowed_ = false;  // true if any band's local buffer couldn't hold its content

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by encode() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};
```

- [ ] **Step 4: Replace `ParallelFrameEncoder`'s implementation in `rawv_codec.cpp`**

Replace the entire existing implementation (from `ParallelFrameEncoder::ParallelFrameEncoder`
through the closing brace of `ParallelFrameEncoder::encode`) with:

```cpp
ParallelFrameEncoder::ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount)
    : width_(width), height_(height) {
  if (threadCount > 0) {
    threadCount_ = threadCount;
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    threadCount_ = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
  }
  // Per-band local pack buffer capacity: worst-case Raw16 bytes for this
  // band's share of rows (width_*2 bytes/row), doubled as headroom for
  // uneven noise distribution across bands -- real sensor content rarely
  // needs more than its proportional share, but a band covering an
  // unusually noisy region legitimately could exceed a tight
  // 1/threadCount_ split. A uniform ceiling (sized off the largest
  // possible band, rows rounded up) comfortably covers every band,
  // including the last one, which can absorb a few extra remainder rows.
  uint32_t rowsPerBand = (height_ + threadCount_ - 1) / threadCount_;
  uint32_t bandCapacity = rowsPerBand * width_ * 2 * 2 + 64;
  bandBufs_.resize(threadCount_);
  for (auto& buf : bandBufs_) buf.resize(bandCapacity);
  bandBits_.resize(threadCount_, 0);

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
    computeAndPackBand(bandIndex, bandStart, bandEnd);

    lock.lock();
    if (--pending_ == 0) cvDone_.notify_one();
  }
}

void ParallelFrameEncoder::computeAndPackBand(uint32_t bandIndex, uint32_t bandStart,
                                               uint32_t bandEnd) {
  BitWriter bw(bandBufs_[bandIndex].data(), static_cast<uint32_t>(bandBufs_[bandIndex].size()));
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
  bandBits_[bandIndex] = bits;
  if (!ok) jobOverflowed_ = true;
}

uint32_t ParallelFrameEncoder::encode(const uint16_t* raw16, uint32_t rowStrideSamples,
                                       uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width_ == 0 || height_ == 0) return 0;

  // Pass 1: same strided-sample k-selection as before -- unchanged, already
  // cheap (avg 3.83ms on-device per round 3's profiling).
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
  // directly into its own local buffer -- fused, no shared residual buffer.
  {
    std::lock_guard<std::mutex> lock(mu_);
    jobRaw16_ = raw16;
    jobRowStrideSamples_ = rowStrideSamples;
    jobBitDepth_ = bitDepth;
    jobK_ = k;
    jobOverflowed_ = false;
    pending_ = threadCount_;
    generation_++;
  }
  cvStart_.notify_all();
  {
    std::unique_lock<std::mutex> lock(mu_);
    cvDone_.wait(lock, [&] { return pending_ == 0; });
  }
  if (jobOverflowed_) return 0;

  // Merge: concatenate the per-band local bitstreams into one bit-exact
  // contiguous stream, same header convention as before (leading k byte).
  out[0] = static_cast<uint8_t>(k);
  std::vector<const uint8_t*> bandPtrs(threadCount_);
  for (uint32_t i = 0; i < threadCount_; i++) bandPtrs[i] = bandBufs_[i].data();
  uint32_t merged = mergeBitstreams(bandPtrs.data(), bandBits_.data(), threadCount_,
                                     out + 1, outCapacity - 1);
  if (merged == 0) return 0;
  return 1 + merged;
}
```

- [ ] **Step 5: Add host tests**

Add these test cases to `core/tests/test_rawv_codec.cpp` (the existing
`#include <algorithm>` from round 3 is still needed for `std::equal`, keep it):

```cpp
TEST_CASE("ParallelFrameEncoder (round 4: band-parallel write) produces byte-identical output to encodeFrame") {
  // 512x512 with threadCount forced to 4 guarantees a real multi-band split
  // and exercises the merge step across real band boundaries.
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

  std::vector<uint16_t> decoded(src.size());
  REQUIRE(decodeFrame(parallelOut.data(), parallelN, decoded.data(), 512, 512, 512, 14));
  CHECK(decoded == src);
}

TEST_CASE("ParallelFrameEncoder byte-identical output across varied dimensions and content (merge boundary coverage)") {
  // Different width/height/content per case produces different per-band bit
  // counts and therefore different sub-byte phase offsets at each band
  // boundary -- a merge bug at a specific phase would very likely surface
  // as a mismatch in at least one of these varied cases.
  struct Case { uint32_t width, height, bitDepth; };
  const Case cases[] = {
    {64, 64, 16}, {63, 65, 12}, {200, 300, 14}, {129, 129, 10}, {257, 64, 16},
  };
  for (const auto& c : cases) {
    auto src = makeFrame(c.width, c.height, c.bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
      return static_cast<uint16_t>(((x * 13 + y * 29 + x * y) ^ 0x33) % (maxVal + 1));
    });
    std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t serialN = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                                    serial.data(), static_cast<uint32_t>(serial.size()));
    REQUIRE(serialN > 0);

    ParallelFrameEncoder parallel(c.width, c.height, /*threadCount=*/4);
    std::vector<uint8_t> parallelOut(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t parallelN = parallel.encode(src.data(), c.width, c.bitDepth, parallelOut.data(),
                                          static_cast<uint32_t>(parallelOut.size()));
    REQUIRE(parallelN == serialN);
    CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));
  }
}

TEST_CASE("ParallelFrameEncoder returns 0 (caller falls back) when the merged output doesn't fit outCapacity") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return maxVal; });
  ParallelFrameEncoder enc(64, 64, /*threadCount=*/4);
  std::vector<uint8_t> tiny(4);
  uint32_t n = enc.encode(src.data(), 64, 16, tiny.data(), static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);
}

TEST_CASE("ParallelFrameEncoder fails the whole frame (not a partial/corrupt result) when one band's content overflows its local buffer") {
  // k-selection only samples (x%4==0, y%4==0) positions -- make every
  // SAMPLED position flat (maxVal/2) so k-selection picks k=0, while every
  // UNSAMPLED position in band 0 (rows [0, height/threadCount)) alternates
  // between 0 and maxVal. With k=0, each such pixel's residual is close to
  // +-maxVal, so its Rice codeword's unary quotient is huge (tens of
  // thousands of one-bits, draining BitWriter's 32-bit-chunk loop many
  // times) -- massively overflowing any reasonably-sized per-band buffer by
  // a margin large enough that no precise capacity arithmetic is needed to
  // guarantee this triggers the overflow path.
  const uint32_t width = 16, height = 16;
  auto src = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    if (x % 4 == 0 && y % 4 == 0) return static_cast<uint16_t>(maxVal / 2);
    if (y < 4) return (x % 2 == 0) ? static_cast<uint16_t>(0) : maxVal;
    return static_cast<uint16_t>(maxVal / 2);
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = enc.encode(src.data(), width, 16, out.data(), static_cast<uint32_t>(out.size()));
  CHECK(n == 0);
}
```

- [ ] **Step 6: Build and run the full `rawv_codec` suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the four new tests (round 3's original
equivalence test is being replaced by Step 5's superset, not left duplicated —
if it's still present from round 3, remove it as part of this step so there's
one canonical equivalence test, not two testing the same thing under different
names).

- [ ] **Step 7: Run the full host suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 8 suites pass.

- [ ] **Step 8: Build the Android app**

```
.\gradlew.bat assembleRelease
```
Expected: BUILD SUCCESSFUL. `app/src/main/cpp/capture.cpp` needs no changes for
this task (per the Global Constraints, `ParallelFrameEncoder`'s public interface
is unchanged) — this step confirms the rewritten internals still link and run
correctly through the existing round-3 call site.

- [ ] **Step 9: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "perf: fuse predict+pack per band with bit-exact merge (round 4 stage 1: band-parallel write)"
```

---

### Task 2: On-device checkpoint

**Files:** none (not a code task). May update:
- `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`

**Interfaces:**
- Consumes: Task 1's rewritten `ParallelFrameEncoder`, already reachable through
  `capture.cpp`'s existing (round-3) `frameEncoder_->encode(...)` call site — no
  wiring changes needed.

This is an INTERMEDIATE checkpoint, not a final-ship verification — it measures
stage 1 (band-parallel write) alone, before stage 2 (the Compute/Finish
pipeline) is even planned. Do not add a DNG pixel-diff export check here; that
belongs in the later plan that finishes the full round 4 design, matching round
3's Task 5 pattern.

- [ ] **Step 1: Build release and install**

```
.\gradlew.bat assembleRelease
```
Then install via adb (uninstall/reinstall first if the previous install's
signature doesn't match):
```
"C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r <path to app-release.apk>
```

- [ ] **Step 2: Record with compression ON at 4096×3072@24fps**

Confirm "Compress recordings" is ON in Settings (RECORDING section) — use a
screenshot or `uiautomator dump` for exact tap coordinates, never blind-retap.
Record for at least 30 seconds. Read the written/dropped counters (left sidebar
while recording, or the post-stop toast).

- [ ] **Step 3: Confirm the recording actually used the new code path**

List the clips directory to find the new clip's actual on-device filename,
then check the `packMode` header byte (offset 20, little-endian `uint32_t`,
value `3` = `CompressedPredictive`) — read a small byte range directly via
`adb shell` rather than pulling the entire (potentially multi-GB) clip file:

```bash
MSYS_NO_PATHCONV=1 "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell ls -t /sdcard/Android/data/com.shez.rawcam/files/clips/
MSYS_NO_PATHCONV=1 "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell "dd if=/sdcard/Android/data/com.shez.rawcam/files/clips/<clip file found above> bs=1 skip=20 count=4 2>/dev/null | od -An -tu1"
```
The four bytes printed are the little-endian `packMode` value — confirm they
decode to `3` (e.g. `3 0 0 0`). (Avoid pulling the whole multi-GB clip just to
read its header — a prior session pulled a 14.6GB file for this check and it
took nearly two minutes for no benefit.)

- [ ] **Step 4: Record the checkpoint numbers**

Add a new subsection to
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`, after the
"Root cause found" section, titled "Round 4 stage 1 checkpoint — band-parallel
write," recording: elapsed time, written/dropped counts, and the confirmed
`packMode` value. Compare against round 3's ~78.0% loss baseline and the
0-dropped bar. Per the design doc's own risk note, if the improvement is less
than the ~3-4x band-parallelism was expected to deliver, say so explicitly —
that's the signal for whether stage 2 (pipelining) alone can close the
remaining gap, or whether per-call `writeBits`/`writeRice` optimization also
needs to be scoped into the follow-up plan.

- [ ] **Step 5: Commit the documentation update**

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
git commit -m "docs: record round 4 stage 1 checkpoint (band-parallel write) on-device numbers"
```
