# Compressed `.rawv` Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add lossless compression to the `.rawv` capture container's per-frame
RAW payload, cutting on-device recording storage use with zero pixel data
loss and zero change to exported DNGs.

**Architecture:** A new `PackMode::CompressedPredictive` mode predicts each
sample from its same-color Bayer neighbors (MED/LOCO-I median predictor) and
Rice-codes the residuals. `.rawv` moves from fixed-stride to variable-stride
frame records (a new `payloadBytes` field per frame); `dng_writer.cpp` and
DNG export output are untouched — export decodes back to plain RAW16 first.

**Tech Stack:** C++ (`core/`, host-testable via doctest/ctest),
`app/src/main/cpp/capture.cpp` (JNI capture path), Kotlin
(`SettingsRepository`/`SettingsScreen`).

## Global Constraints

- No copy step, no native/JNI-boundary change beyond `core/` and
  `app/src/main/cpp/`.
- Lossless only — decoded output must be bit-exact against the original
  sensor samples. Enforced by round-trip fidelity tests, not just asserted.
- Must not become the capture bottleneck (currently storage-bandwidth-bound
  at ~2.1GB/s sustained) — on-device throughput verification required before
  this is considered done.
- `dng_writer.cpp` / DNG export output: UNTOUCHED. Compressed DNG export is
  explicitly out of scope for this plan (see spec).
- No unit tests outside `core/` (established project convention); the new
  codec is pure `core/` C++ and gets full host `ctest` coverage via doctest,
  matching `test_pack10.cpp`'s existing pattern (`TEST_CASE`/`CHECK`, one
  `#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN` per test file).
- Spec: `docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`.

---

### Task 1: `rawv.h` container format changes

**Files:**
- Modify: `core/include/rawcam/rawv.h`
- Modify: `core/tests/test_rawv_layout.cpp` (locate via
  `grep -n kFrameMetaSize core/tests/test_rawv_layout.cpp` — add assertions
  for the new fields alongside whatever existing `static_assert`/`CHECK`
  pattern is already there for `FileHeader`/`FrameMeta` sizes)

**Interfaces:**
- Produces: `PackMode::CompressedPredictive` (value `3`), `FrameMeta.payloadBytes`
  (`uint32_t`, actual on-disk bytes for this frame's payload),
  `FrameMeta.compressed` (`uint32_t`, `0` = stored/fallback, `1` = compressed).
  `kVersion` becomes `4`. Every later task in this plan depends on these
  three names/types exactly.

- [ ] **Step 1: Update `rawv.h`**

```cpp
constexpr uint32_t kVersion = 4;
```

```cpp
enum class PackMode : uint32_t { Raw16 = 0, Packed10 = 1, Packed12 = 2, CompressedPredictive = 3 };
```

```cpp
struct FrameMeta {
  uint64_t timestampNs;      // sensor timestamp
  uint64_t frameIndex;
  uint32_t iso;
  uint32_t _pad;
  uint64_t exposureNs;
  float    focusDistance;    // diopters
  float    wbNeutral[3];     // AsShotNeutral estimate from AWB
  uint32_t droppedSoFar;
  // Actual on-disk bytes of this frame's payload. For Raw16/Packed10/Packed12
  // this always equals FileHeader.frameSizeBytes (fixed stride, unchanged
  // behavior). For CompressedPredictive, FileHeader.frameSizeBytes is only an
  // allocation ceiling -- this field is the real per-frame stride, and the
  // reader MUST use it (not frameSizeBytes) to find the next record.
  uint32_t payloadBytes;
  // 0 = payload is stored uncompressed (the compressor's fallback path for a
  // frame that wouldn't shrink); 1 = payload is CompressedPredictive-encoded.
  // Always 0 for Raw16/Packed10/Packed12 frames.
  uint32_t compressed;
  uint8_t  reserved[4];
};
```

(`reserved` shrinks from `[12]` to `[4]` — the struct's total size must stay
`kFrameMetaSize` == 64 bytes; the `static_assert` already in the file catches
any miscount.)

- [ ] **Step 2: Update `test_rawv_layout.cpp`**

Add (adjust to match whatever framework/assertions the existing file already
uses for `FrameMeta`):

```cpp
TEST_CASE("FrameMeta stays 64 bytes after adding payloadBytes/compressed") {
  CHECK(sizeof(FrameMeta) == kFrameMetaSize);
}

TEST_CASE("PackMode::CompressedPredictive has value 3") {
  CHECK(static_cast<uint32_t>(PackMode::CompressedPredictive) == 3u);
}
```

- [ ] **Step 3: Build and run host tests**

Run (PowerShell, from `core/build`):
```
cmake --build . && ctest --output-on-failure
```
Expected: all existing suites still pass (this task only adds fields/an enum
value — no existing behavior changes), new layout assertions pass.

- [ ] **Step 4: Commit**

```bash
git add core/include/rawcam/rawv.h core/tests/test_rawv_layout.cpp
git commit -m "feat: add CompressedPredictive pack mode and per-frame payload size to rawv.h"
```

---

### Task 2: `rawv_codec` — predictor + Rice coder

**Files:**
- Create: `core/include/rawcam/rawv_codec.h`
- Create: `core/src/rawv_codec.cpp`
- Create: `core/tests/test_rawv_codec.cpp`
- Modify: `core/CMakeLists.txt` (add `rawv_codec.cpp` to the library sources
  and `test_rawv_codec.cpp` to the test executables — locate the existing
  `pack10.cpp`/`test_pack10.cpp` entries via
  `grep -n pack10 core/CMakeLists.txt` and add the new file next to them,
  following the exact same pattern)

**Interfaces:**
- Consumes: nothing from other tasks (pure, standalone).
- Produces: `encodeFrame(const uint16_t*, uint32_t width, uint32_t height, uint32_t rowStrideSamples, uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) -> uint32_t`
  and `decodeFrame(const uint8_t*, uint32_t compressedSize, uint16_t* out, uint32_t width, uint32_t height, uint32_t rowStrideSamples, uint32_t bitDepth) -> bool`,
  both `namespace rawcam`. Task 5 (exporter) and Task 6 (capture.cpp) call
  these exact signatures. `rowStrideSamples` is stride in SAMPLES (2-byte
  units), not bytes — callers passing a byte stride must divide by 2 first.

- [ ] **Step 1: Write `core/include/rawcam/rawv_codec.h`**

```cpp
#pragma once
#include <cstdint>

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

}  // namespace rawcam
```

- [ ] **Step 2: Write `core/src/rawv_codec.cpp`**

```cpp
#include "rawcam/rawv_codec.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace rawcam {
namespace {

// MSB-first bit writer over a caller-owned, pre-zeroed buffer. write*()
// returns false (and stops writing) once `capacity` would be exceeded --
// encodeFrame uses that as its "won't fit" signal.
class BitWriter {
 public:
  BitWriter(uint8_t* buf, uint32_t capacity) : buf_(buf), capacity_(capacity) {}

  bool writeBit(uint32_t bit) {
    if (bytePos_ >= capacity_) return false;
    buf_[bytePos_] |= static_cast<uint8_t>((bit & 1u) << (7 - bitPos_));
    if (++bitPos_ == 8) { bitPos_ = 0; bytePos_++; }
    return true;
  }

  // `q` one-bits, a zero bit, then `k` bits of `value`'s low bits MSB-first --
  // the standard Golomb-Rice codeword shape.
  bool writeRice(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    for (uint32_t i = 0; i < q; i++) if (!writeBit(1)) return false;
    if (!writeBit(0)) return false;
    for (uint32_t i = 0; i < k; i++) {
      if (!writeBit((value >> (k - 1 - i)) & 1u)) return false;
    }
    return true;
  }

  uint32_t finishedBytes() const { return bitPos_ == 0 ? bytePos_ : bytePos_ + 1; }

 private:
  uint8_t* buf_;
  uint32_t capacity_;
  uint32_t bytePos_ = 0;
  uint32_t bitPos_ = 0;
};

class BitReader {
 public:
  BitReader(const uint8_t* buf, uint32_t size) : buf_(buf), size_(size) {}

  bool readBit(uint32_t* bit) {
    if (bytePos_ >= size_) return false;
    *bit = (buf_[bytePos_] >> (7 - bitPos_)) & 1u;
    if (++bitPos_ == 8) { bitPos_ = 0; bytePos_++; }
    return true;
  }

  bool readRice(uint32_t k, uint32_t* value) {
    uint32_t q = 0, bit = 0;
    while (true) {
      if (!readBit(&bit)) return false;
      if (bit == 0) break;
      if (++q > (1u << 24)) return false;  // corrupt-stream guard
    }
    uint32_t remainder = 0;
    for (uint32_t i = 0; i < k; i++) {
      if (!readBit(&bit)) return false;
      remainder = (remainder << 1) | bit;
    }
    *value = (q << k) + remainder;
    return true;
  }

 private:
  const uint8_t* buf_;
  uint32_t size_;
  uint32_t bytePos_ = 0;
  uint32_t bitPos_ = 0;
};

inline uint32_t zigzagEncode(int32_t v) {
  return (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31);
}

inline int32_t zigzagDecode(uint32_t v) {
  return static_cast<int32_t>(v >> 1) ^ -static_cast<int32_t>(v & 1);
}

// MED/LOCO-I predictor: median of (left, up, left+up-upleft). Always within
// [min(left,up), max(left,up)] by construction -- no separate clamp needed.
inline int32_t medPredict(int32_t left, int32_t up, int32_t upleft) {
  int32_t linear = left + up - upleft;
  int32_t lo = std::min(left, up), hi = std::max(left, up);
  return std::clamp(linear, lo, hi);
}

// `plane` is either the original frame (encode) or the buffer being filled
// in raster order (decode) -- valid either way since left/up/upleft are
// always earlier in raster scan order than (x, y).
inline int32_t predictAt(const uint16_t* plane, uint32_t x, uint32_t y,
                          uint32_t rowStrideSamples, uint32_t bitDepth) {
  bool hasLeft = x >= 2;
  bool hasUp = y >= 2;
  if (!hasLeft && !hasUp) return 1 << (bitDepth - 1);
  if (!hasLeft) return plane[(y - 2) * rowStrideSamples + x];
  if (!hasUp) return plane[y * rowStrideSamples + (x - 2)];
  int32_t left = plane[y * rowStrideSamples + (x - 2)];
  int32_t up = plane[(y - 2) * rowStrideSamples + x];
  int32_t upleft = plane[(y - 2) * rowStrideSamples + (x - 2)];
  return medPredict(left, up, upleft);
}

// Smallest k such that (count << k) >= sumAbs -- k=0 for a perfectly-
// predicted (all-zero-residual) frame, the common case for flat content.
uint32_t riceParamFor(uint64_t sumAbs, uint64_t count) {
  uint32_t k = 0;
  while (k < 20 && (count << k) < sumAbs) k++;
  return k;
}

}  // namespace

uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height,
                      uint32_t rowStrideSamples, uint32_t bitDepth,
                      uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width == 0 || height == 0) return 0;

  // Pass 1: sum of absolute residuals, to pick one Rice parameter for the
  // whole frame. Recomputing predictAt() in pass 2 is cheap integer
  // arithmetic -- far cheaper than holding a full-frame residual buffer
  // (width*height*4 bytes) alive just to avoid a second pass.
  uint64_t sumAbs = 0;
  uint64_t count = static_cast<uint64_t>(width) * height;
  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
    }
  }
  uint32_t k = riceParamFor(sumAbs, count);

  std::memset(out, 0, outCapacity);
  out[0] = static_cast<uint8_t>(k);
  BitWriter bw(out + 1, outCapacity - 1);

  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      uint32_t z = zigzagEncode(actual - predicted);
      if (!bw.writeRice(z, k)) return 0;  // wouldn't fit -- caller falls back
    }
  }
  return 1 + bw.finishedBytes();
}

bool decodeFrame(const uint8_t* compressed, uint32_t compressedSize,
                  uint16_t* out, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, uint32_t bitDepth) {
  if (compressedSize < 1 || width == 0 || height == 0) return false;
  uint32_t k = compressed[0];
  BitReader br(compressed + 1, compressedSize - 1);

  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      uint32_t z = 0;
      if (!br.readRice(k, &z)) return false;
      int32_t residual = zigzagDecode(z);
      int32_t predicted = predictAt(out, x, y, rowStrideSamples, bitDepth);
      out[y * rowStrideSamples + x] = static_cast<uint16_t>(predicted + residual);
    }
  }
  return true;
}

}  // namespace rawcam
```

- [ ] **Step 3: Write `core/tests/test_rawv_codec.cpp`**

```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_codec.h"
#include <cstdlib>
#include <vector>

using namespace rawcam;

namespace {
std::vector<uint16_t> makeFrame(uint32_t width, uint32_t height, uint32_t bitDepth,
                                 uint16_t (*gen)(uint32_t x, uint32_t y, uint32_t maxVal)) {
  uint16_t maxVal = static_cast<uint16_t>((1u << bitDepth) - 1);
  std::vector<uint16_t> buf(static_cast<size_t>(width) * height);
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      buf[y * width + x] = gen(x, y, maxVal);
  return buf;
}

bool roundTrips(const std::vector<uint16_t>& src, uint32_t width, uint32_t height, uint32_t bitDepth) {
  std::vector<uint8_t> compressed(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = encodeFrame(src.data(), width, height, width, bitDepth,
                            compressed.data(), static_cast<uint32_t>(compressed.size()));
  if (n == 0) return false;
  std::vector<uint16_t> out(src.size());
  if (!decodeFrame(compressed.data(), n, out.data(), width, height, width, bitDepth)) return false;
  return out == src;
}
}  // namespace

TEST_CASE("round-trips a flat (all-same-value) 16-bit frame") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return static_cast<uint16_t>(maxVal / 2); });
  CHECK(roundTrips(src, 64, 64, 16));
}

TEST_CASE("round-trips a smooth gradient at 12-bit depth") {
  auto src = makeFrame(64, 64, 12, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x + y) * 7) % (maxVal + 1));
  });
  CHECK(roundTrips(src, 64, 64, 12));
}

TEST_CASE("round-trips pseudo-random noise at 10-bit depth (exercises worst-case residuals)") {
  std::srand(12345);
  auto src = makeFrame(64, 64, 10, [](uint32_t, uint32_t, uint16_t maxVal) {
    return static_cast<uint16_t>(std::rand() % (maxVal + 1));
  });
  CHECK(roundTrips(src, 64, 64, 10));
}

TEST_CASE("round-trips a single-row and single-column frame (edge-only prediction)") {
  auto row = makeFrame(64, 1, 16, [](uint32_t x, uint32_t, uint16_t) { return static_cast<uint16_t>(x * 37 % 65536); });
  CHECK(roundTrips(row, 64, 1, 16));
  auto col = makeFrame(1, 64, 16, [](uint32_t, uint32_t y, uint16_t) { return static_cast<uint16_t>(y * 37 % 65536); });
  CHECK(roundTrips(col, 1, 64, 16));
}

TEST_CASE("encodeFrame returns 0 (caller falls back) when outCapacity is too small") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return maxVal; });
  std::vector<uint8_t> tiny(4);
  uint32_t n = encodeFrame(src.data(), 64, 64, 64, 16, tiny.data(), static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);
}

TEST_CASE("handles rowStrideSamples wider than width (padded rows)") {
  const uint32_t width = 32, height = 32, stride = 40;  // stride > width
  std::vector<uint16_t> src(static_cast<size_t>(stride) * height, 0);
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      src[y * stride + x] = static_cast<uint16_t>((x * 13 + y * 29) % 4096);
  std::vector<uint8_t> compressed(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = encodeFrame(src.data(), width, height, stride, 12, compressed.data(), static_cast<uint32_t>(compressed.size()));
  REQUIRE(n > 0);
  std::vector<uint16_t> out(src.size(), 0);
  REQUIRE(decodeFrame(compressed.data(), n, out.data(), width, height, stride, 12));
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      CHECK(out[y * stride + x] == src[y * stride + x]);
}
```

- [ ] **Step 4: Add to `core/CMakeLists.txt`**

Find the existing `pack10.cpp`/`test_pack10` entries (`grep -n pack10
core/CMakeLists.txt`) and add `rawv_codec.cpp` to the same library-sources
list, and a `test_rawv_codec` test executable following the exact same
pattern as the `test_pack10` entry (same doctest include dirs, same
`add_test(...)` call shape).

- [ ] **Step 5: Build and run**

Run (PowerShell, from `core/build`):
```
cmake --build . && ctest --output-on-failure -R rawv_codec
```
Expected: all `test_rawv_codec` cases PASS. If any round-trip test fails,
this is a real bug in the predictor/Rice-coder above — do not proceed to
later tasks until every case here passes.

- [ ] **Step 6: Run full suite to confirm no regressions**

```
ctest --output-on-failure
```
Expected: all prior suites (pack10, pack12, rawv_writer, rawv_reader,
dng_writer, export, rawv_layout) still pass unchanged — this task only adds
new files.

- [ ] **Step 7: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp core/CMakeLists.txt
git commit -m "feat: add rawv_codec (MED predictor + Golomb-Rice lossless compression)"
```

---

### Task 3: `RawvWriter::writeFrame` explicit payload length

**Files:**
- Modify: `core/include/rawcam/rawv_writer.h`
- Modify: `core/src/rawv_writer.cpp`
- Modify: `core/tests/test_rawv_writer.cpp`

**Interfaces:**
- Consumes: `FrameMeta.payloadBytes` (Task 1).
- Produces: `RawvWriter::writeFrame(const FrameMeta&, const uint8_t* payload, uint32_t payloadBytes) -> bool`
  — Task 6 (capture.cpp) calls this exact new signature.

- [ ] **Step 1: Update `rawv_writer.h`**

```cpp
bool writeFrame(const FrameMeta& meta, const uint8_t* payload, uint32_t payloadBytes);
```

- [ ] **Step 2: Update `rawv_writer.cpp`**

Locate the current `writeFrame` implementation
(`grep -n "writeFrame" core/src/rawv_writer.cpp`). It currently writes
`hdr_.frameSizeBytes` bytes of `payload` unconditionally after the
`FrameMeta`. Change it to write exactly `payloadBytes` bytes instead (the
parameter, not `hdr_.frameSizeBytes`), and assert the caller set
`meta.payloadBytes == payloadBytes` (defensive — catches a caller bug where
the two disagree):

```cpp
bool RawvWriter::writeFrame(const FrameMeta& meta, const uint8_t* payload, uint32_t payloadBytes) {
  if (finalized_ || fd_ < 0) return false;
  if (meta.payloadBytes != payloadBytes) return false;  // caller bug guard
  if (!writeAll(fd_, &meta, sizeof(meta))) return false;
  if (payloadBytes > 0 && !writeAll(fd_, payload, payloadBytes)) return false;
  frames_++;
  return true;
}
```

(Keep whatever the existing `writeAll` helper/error-handling call shape
already is in this file — only the payload-length source changes, from
`hdr_.frameSizeBytes` to the new parameter.)

- [ ] **Step 3: Update `test_rawv_writer.cpp` call sites**

Every existing `writer->writeFrame(meta, payload)` call in this file needs a
third argument. For the existing Packed10/Packed12/Raw16-style test fixtures
in this file, pass the same fixed size they were already implicitly using
(`hdr.frameSizeBytes` or the test's local packed-buffer size — whichever the
existing test already computes).

Add one new case exercising a variable-length frame:

```cpp
TEST_CASE("writeFrame accepts a payload shorter than frameSizeBytes (compressed case)") {
  FileHeader hdr{};
  hdr.magic = kMagic; hdr.version = kVersion; hdr.frameSizeBytes = 1000;  // ceiling
  auto w = RawvWriter::create("test_variable_frame.rawv", hdr);
  REQUIRE(w != nullptr);
  FrameMeta meta{};
  meta.frameIndex = 0;
  meta.payloadBytes = 250;  // actual compressed size, well under the 1000 ceiling
  meta.compressed = 1;
  std::vector<uint8_t> payload(250, 0xAB);
  CHECK(w->writeFrame(meta, payload.data(), 250));
  CHECK(w->finalize());
}
```

- [ ] **Step 4: Build and run**

```
cmake --build . && ctest --output-on-failure -R rawv_writer
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add core/include/rawcam/rawv_writer.h core/src/rawv_writer.cpp core/tests/test_rawv_writer.cpp
git commit -m "feat: RawvWriter::writeFrame takes an explicit payload length"
```

---

### Task 4: `RawvReader` variable-stride offset table

**Files:**
- Modify: `core/include/rawcam/rawv_reader.h`
- Modify: `core/src/rawv_reader.cpp`
- Modify: `core/tests/test_rawv_reader.cpp`

**Interfaces:**
- Consumes: `FrameMeta.payloadBytes` (Task 1).
- Produces: `RawvReader::open()`/`readFrame(index, meta, payload)` keep their
  existing public signatures (no caller elsewhere needs to change) — only the
  internal offset-tracking changes.

This is the highest-risk task in this plan (per the design spec) — today's
`readFrame(index, ...)` computes each frame's byte offset arithmetically from
a constant stride (`kHeaderSize + index * (kFrameMetaSize +
hdr_.frameSizeBytes)`), which is only valid when every frame is the same
size. That assumption breaks the moment any file contains
`CompressedPredictive` frames.

- [ ] **Step 1: Read the current implementation first**

Read `core/src/rawv_reader.cpp` in full before changing anything — this task
needs to fit the new logic into whatever `open()`/`readFrame()` and the
existing "recover by scan when frameCount==0" path already look like, not
guess at it.

- [ ] **Step 2: Add an offset table, built once at `open()`**

Add a private member: `std::vector<uint64_t> offsets_;` (one entry per
frame — the absolute byte offset of that frame's `FrameMeta`, `offsets_[0]`
always `kHeaderSize`).

Replace whatever arithmetic `open()` currently uses to determine
`frameCount()` (both the "trust the header's `frameCount` field" fast path
and the "`frameCount == 0`, recover by scan" fallback) with ONE unified
sequential walk that works for both fixed- and variable-stride files:

```cpp
// Sequentially walks the file from kHeaderSize, reading only each frame's
// FrameMeta (kFrameMetaSize bytes -- cheap, not the full payload) and using
// payloadBytes to skip to the next record. Stops at EOF or at the first
// record that doesn't fit in the remaining file size (truncated/corrupt
// tail -- same "stop cleanly, don't fail the whole open" behavior the old
// scan-recovery path already had, now handling variable stride too).
std::vector<uint64_t> scanOffsets(int fd, uint64_t fileSize) {
  std::vector<uint64_t> offsets;
  uint64_t pos = kHeaderSize;
  while (pos + kFrameMetaSize <= fileSize) {
    FrameMeta meta{};
    if (::pread(fd, &meta, sizeof(meta), static_cast<off_t>(pos)) != static_cast<ssize_t>(sizeof(meta))) break;
    uint64_t recordEnd = pos + kFrameMetaSize + meta.payloadBytes;
    if (recordEnd > fileSize) break;  // truncated tail -- stop here, don't include it
    offsets.push_back(pos);
    pos = recordEnd;
  }
  return offsets;
}
```

(Match this to whatever file-size-query/`pread` helper the existing file
already uses instead of raw POSIX calls, if it wraps them — e.g. if
`file_io.h` already exposes a `readAt`/`fileSize` helper, use that instead
of `::pread` directly, for consistency with the rest of this file.)

Call `scanOffsets()` unconditionally in `open()` (replacing both the old
trust-the-header and scan-recovery paths — this one path is now correct and
sufficient for every case: a healthy finalized file, an unfinalized/crashed
file with `frameCount==0`, and any mix of compressed/stored frames). Set
`count_ = offsets_.size()`.

- [ ] **Step 3: Update `readFrame`**

```cpp
bool RawvReader::readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload) {
  if (index >= offsets_.size()) return false;
  uint64_t pos = offsets_[index];
  if (::pread(fd_, meta, sizeof(*meta), static_cast<off_t>(pos)) != static_cast<ssize_t>(sizeof(*meta))) return false;
  if (meta->payloadBytes > 0 &&
      ::pread(fd_, payload, meta->payloadBytes, static_cast<off_t>(pos + kFrameMetaSize)) != static_cast<ssize_t>(meta->payloadBytes)) {
    return false;
  }
  return true;
}
```

(Again, match actual I/O calls to whatever this file already uses.) Note
this changes the CONTRACT of `payload`'s buffer size: callers must size it
for the WORST CASE (`hdr_.frameSizeBytes`, the ceiling), not assume it's
always exactly that many bytes — `meta->payloadBytes` (now populated by this
call) tells them how many of those bytes are actually valid. Grep for every
existing caller of `readFrame` (`exporter.cpp` is one — Task 5 updates it)
to confirm none of them assume `payload` is fully populated to
`frameSizeBytes`.

- [ ] **Step 4: Update `test_rawv_reader.cpp`**

Add cases for: a file with all fixed-stride frames (existing behavior,
confirm unchanged), a file mixing compressed (`compressed=1`,
`payloadBytes` < ceiling) and stored-fallback (`compressed=0`,
`payloadBytes` == ceiling) frames read back correctly via `readFrame` at
each index, and a truncated file (write 2 complete frames plus a partial
third `FrameMeta`) where `frameCount()` correctly reports 2, not 3 or a
crash.

- [ ] **Step 5: Build and run**

```
cmake --build . && ctest --output-on-failure -R rawv_reader
```
Expected: all pass, including the new variable-stride and truncation cases.

- [ ] **Step 6: Run full suite**

```
ctest --output-on-failure
```
Expected: all suites pass — `rawv_writer`'s tests from Task 3 exercise the
writer side of this same variable-stride contract, so a mismatch between
writer and reader would surface here if one exists.

- [ ] **Step 7: Commit**

```bash
git add core/include/rawcam/rawv_reader.h core/src/rawv_reader.cpp core/tests/test_rawv_reader.cpp
git commit -m "fix: RawvReader builds a variable-stride offset table instead of assuming fixed stride"
```

---

### Task 5: `exporter.cpp` decode branch

**Files:**
- Modify: `core/src/exporter.cpp`
- Modify: `core/tests/test_export.cpp`

**Interfaces:**
- Consumes: `decodeFrame()` (Task 2), `RawvReader::readFrame` now populating
  `meta.payloadBytes`/`meta.compressed` (Task 4).
- Produces: nothing new for later tasks — this is a leaf integration point.
  `dng_writer.cpp`'s `writeDng()` still receives a plain RAW16 buffer exactly
  as it always has.

- [ ] **Step 1: Read the current per-frame export loop**

Read `core/src/exporter.cpp` in full — locate the existing branch that
unpacks `Packed10`/`Packed12` (`grep -n "PackMode::" core/src/exporter.cpp`)
before adding a third branch next to it.

- [ ] **Step 2: Add the `CompressedPredictive` branch**

Next to the existing `case PackMode::Packed10:` / `case PackMode::Packed12:`
unpack calls (which presumably unpack into a reused RAW16 scratch buffer
before handing it to `writeDng()`), add:

```cpp
case PackMode::CompressedPredictive: {
  uint32_t rowStrideSamples = hdr.rowStrideBytes / 2;
  uint32_t bitDepth = 32 - __builtin_clz(hdr.whiteLevel);  // same bit-depth
                                                            // derivation capture.cpp
                                                            // already uses to pick
                                                            // Packed10 vs Packed12
  if (!decodeFrame(payload.data(), meta.payloadBytes, raw16Buf.data(),
                    hdr.width, hdr.height, rowStrideSamples, bitDepth)) {
    // Corrupt/truncated compressed frame -- treat exactly like any other
    // per-frame export failure this loop already handles (skip/report,
    // matching whatever the existing Packed10/Packed12 error path does).
    return exportFrameFailed(meta.frameIndex);
  }
  break;
}
```

(Fit the exact variable names — `payload`, `raw16Buf`, `exportFrameFailed`,
whatever the existing per-frame loop actually calls its buffers/error path —
to what Step 1's read reveals; the shape above is the logic, not literal
copy-paste.) Note `meta.compressed == 0` frames (the stored-fallback case)
need NO decode at all — they're already plain RAW16/Packed10/Packed12 bytes,
matching `meta.compressed` against the pack mode's normal fixed-format
handling, same as today.

- [ ] **Step 3: Update `test_export.cpp`**

Add a round-trip case: write a small synthetic `.rawv` file with one
`CompressedPredictive` frame (reuse `encodeFrame` directly, same pattern as
`test_rawv_codec.cpp`, to build the compressed payload), export it, and
confirm the resulting DNG's pixel data matches the original uncompressed
source exactly (same assertion style `test_dng_writer.cpp` likely already
uses for its own round-trip checks — match that pattern).

- [ ] **Step 4: Build and run**

```
cmake --build . && ctest --output-on-failure
```
Expected: all pass, including the new export round-trip case.

- [ ] **Step 5: Commit**

```bash
git add core/src/exporter.cpp core/tests/test_export.cpp
git commit -m "feat: exporter decodes CompressedPredictive frames before writing DNGs"
```

---

### Task 6: `capture.cpp` encode branch + Settings-gated selection

**Files:**
- Modify: `app/src/main/cpp/capture.cpp`
- Modify: `app/src/main/cpp/capture.h` (if the recording-start entry point's
  signature needs a new parameter — see Step 1)
- Modify: `app/src/main/cpp/jni_bridge.cpp` (JNI glue for the same new
  parameter)

**Interfaces:**
- Consumes: `encodeFrame()` (Task 2), `RawvWriter::writeFrame(meta, payload,
  payloadBytes)` (Task 3).
- Produces: a `compressRecordings: Boolean` parameter threaded from Kotlin
  down to native — Task 7 (Settings UI) reads/writes the Kotlin-side setting
  that this parameter is sourced from.

No host test for this file (native JNI capture path — code-reviewed and
on-device-verified only, matching this project's established convention for
everything in `app/src/main/cpp/`).

- [ ] **Step 1: Find the existing recording-start call chain**

Run `grep -n "nativeStartRecording\|nativeRecordStart\|StartRecording"
app/src/main/cpp/jni_bridge.cpp app/src/main/cpp/capture.h
app/src/main/java/com/shez/rawcam/**/*.kt` to find the exact existing
signature (Kotlin `external fun`, JNI glue function, and the C++ entry point
it calls into) that currently passes fps/resolution/etc. down to
`capture.cpp` at recording start.

- [ ] **Step 2: Thread a `compressRecordings` bool through all three layers**

Add one new parameter, consistently named, at each layer: the Kotlin
`external fun` declaration, the JNI glue function's argument list and its
`env->CallBooleanMethod`/direct-value pass-through, and the C++ entry
point's signature. Store it on whatever the existing per-recording state
object already is (alongside the existing fps/resolution fields).

- [ ] **Step 3: Update the pack-mode selection at recording start**

Locate the existing pack-mode selection (`capture.cpp:90-103`, the
`hdr.frameSizeBytes = ...` block that already picks Packed10 vs Packed12 vs
Raw16 from the lens's white level). When `compressRecordings` is true, set
`hdr.packMode = PackMode::CompressedPredictive` and size `hdr.frameSizeBytes`
to what Raw16 would have needed (`rowStride_ * height_` — the guaranteed-safe
ceiling, exactly today's existing Raw16-branch calculation), and resize a
reused scratch buffer (`compressBuf_`, new member, same reuse pattern as the
existing `packBuf_`) to that same ceiling size. When `compressRecordings` is
false, behavior is byte-for-byte unchanged from today.

- [ ] **Step 4: Update the per-frame write (`capture.cpp:130-142` area)**

Where the existing code currently does (paraphrased) "pack into `packBuf_`
then `writer_->writeFrame(meta, packBuf_.data())`" for Packed10/Packed12, or
"`writer_->writeFrame(meta, data)`" directly for Raw16, add a
`CompressedPredictive` branch:

```cpp
if (hdr.packMode == PackMode::CompressedPredictive) {
  uint32_t rowStrideSamples = rowStride_ / 2;
  uint32_t bitDepth = /* same bit-depth value already derived above for the
                          Packed10-vs-Packed12 choice */;
  uint32_t n = encodeFrame(reinterpret_cast<const uint16_t*>(data), width_, height_,
                            rowStrideSamples, bitDepth,
                            compressBuf_.data(), (uint32_t)compressBuf_.size());
  if (n > 0) {
    meta.payloadBytes = n;
    meta.compressed = 1;
    ok = writer_->writeFrame(meta, compressBuf_.data(), n);
  } else {
    // Encode didn't fit the ceiling (pathological content) -- fall back to
    // storing this one frame as plain Raw16, exactly like the existing
    // Raw16 branch, flagged as uncompressed.
    meta.payloadBytes = hdr.frameSizeBytes;
    meta.compressed = 0;
    ok = writer_->writeFrame(meta, data, hdr.frameSizeBytes);
  }
} else {
  // existing Packed10/Packed12/Raw16 logic, unchanged, but now also setting
  // meta.payloadBytes = hdr.frameSizeBytes and meta.compressed = 0
  // explicitly (Task 3 made these required fields on every writeFrame call).
}
```

Every existing (non-compressed) call site must now also set
`meta.payloadBytes = hdr.frameSizeBytes` and `meta.compressed = 0` before
calling `writeFrame`, since Task 3's `writeFrame` asserts
`meta.payloadBytes == payloadBytes`.

- [ ] **Step 5: Build the app**

Run (PowerShell): `.\gradlew.bat assembleDebug`
Expected: builds clean, including the native arm64 rebuild.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/capture.cpp app/src/main/cpp/capture.h app/src/main/cpp/jni_bridge.cpp
git commit -m "feat: capture.cpp writes CompressedPredictive frames when enabled"
```

---

### Task 7: Settings toggle

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt`
  (or wherever `Settings` data class + DataStore keys live — locate via
  `grep -n "deleteAfterExport\|autoExport" app/src/main/java/com/shez/rawcam/settings/*.kt`,
  since those are existing boolean toggles in the same Clips/Export section
  to follow the pattern of)
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (wherever
  the existing recording-start native call is invoked from Kotlin, to pass
  the new `compressRecordings` value through to Task 6's new parameter)

**Interfaces:**
- Consumes: the new native parameter from Task 6.
- Produces: `Settings.compressRecordings: Boolean` (default `true`),
  following whichever exact `Settings` data class shape/DataStore key
  pattern this project's existing boolean toggles already use.

- [ ] **Step 1: Add the setting**

Add `compressRecordings: Boolean = true` to the `Settings` data class and its
DataStore key, mirroring exactly how the nearest existing boolean toggle
(e.g. `deleteAfterExport` or `autoExport`) is declared, read, and persisted
in `SettingsRepository.kt`.

- [ ] **Step 2: Add the Settings row**

In `SettingsScreen.kt`'s Clips/Export section, add a toggle row for
"Compress recordings" using the exact same `ActionRow`/switch composable
pattern as the nearest existing boolean toggle in that section.

- [ ] **Step 3: Wire it into the recording-start call**

At the call site found in Task 6 Step 1 (Kotlin side), pass
`settings.compressRecordings` as the new argument to the native recording-
start call.

- [ ] **Step 4: Build the app**

Run: `.\gradlew.bat assembleDebug`
Expected: builds clean.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: add Compress recordings toggle to Settings"
```

---

### Task 8: On-device verification

Not a code task — run through the spec's on-device checklist on the Xiaomi
14 Ultra (or current test device) before considering this feature done:

- [ ] Record with compression ON and OFF at this project's usual recording
  resolution/fps; confirm 0 dropped frames in both cases (compare toast/log
  drop counts).
- [ ] Confirm the `.rawv` file with compression ON is meaningfully smaller
  than an equivalent OFF recording of the same scene; note the REAL ratio
  achieved (not the spec's ~20-50% ballpark) in the open-items doc.
- [ ] Export a compressed recording; pull the resulting DNGs and diff their
  raw pixel data (not just visual inspection) against exporting an
  equivalent uncompressed recording of the same content.
- [ ] Toggle compression off mid-session (between two separate recordings,
  not live during one), confirm both recordings play/export correctly.
- [ ] Confirm a `.rawv` file recorded by the app BEFORE this change (a
  `kVersion == 3` file, if one is still on-device) still opens and exports
  correctly.
- [ ] Crash-buffer sweep (`adb logcat -b crash -d`) clean across the whole
  session.

Write findings to `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
following this project's established open-items-doc format, and update the
spec's status line accordingly.
