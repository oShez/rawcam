# `rawv_codec` Real-Time Throughput Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `core/src/rawv_codec.cpp`'s `encodeFrame`/`decodeFrame` fast enough
that RawCam's `CompressedPredictive` capture mode meets the design spec's own
stated requirement — zero dropped frames at this project's usual
4096×3072@24fps recording class — which on-device verification on 2026-08-05
found it badly fails (~91% frame loss; see
`docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`).

**Architecture:** Replace the codec's per-INDIVIDUAL-BIT `BitWriter`/`BitReader`
(the profiled/reasoned bottleneck: ~100M+ branchy function calls per 12.6MP
frame) with a 64-bit-accumulator batched implementation that packs multiple
bits per call while producing the byte-for-byte IDENTICAL MSB-first Golomb-Rice
bitstream — a drop-in, format-preserving swap, not a new encoding scheme.
Secondarily, cut the redundant full-frame k-selection pass's cost via strided
sampling. Both changes are internal to `rawv_codec.cpp`'s anonymous namespace;
`encodeFrame`/`decodeFrame`'s public signatures (`rawv_codec.h`) do not change,
so nothing above this file (writer, reader, exporter, capture.cpp) needs
touching.

**Tech Stack:** C++17, doctest (host tests, `core/build` via CMake+Ninja+MinGW),
Gradle/CMake (Android `app/src/main/cpp/`), adb (on-device verification).

## Global Constraints

- Bitstream format MUST stay byte-for-byte identical to the current
  implementation — this is a performance refactor, not a new codec. No
  `rawv.h`/`rawv_codec.h` changes; `kVersion` stays 4.
- `encodeFrame`/`decodeFrame`'s public signatures (declared in
  `core/include/rawcam/rawv_codec.h`) do not change.
- Every existing test in `core/tests/test_rawv_codec.cpp` must continue to
  pass unmodified — they are the regression net for "did this refactor change
  behavior."
- Host tests only exercise small (≤64×64) synthetic frames — they cannot
  themselves prove real-time throughput on a phone's ARM64 core. Task 3's
  on-device re-verification is the actual acceptance gate, exactly like the
  original plan's Task 8.
- Work directly on `main`, no worktree (this project's established
  all-session convention).

---

### Task 1: Batch `BitWriter`/`BitReader` into an accumulator-based implementation

**Files:**
- Modify: `core/src/rawv_codec.cpp`
- Modify: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: nothing new — `BitWriter`/`BitReader` are anonymous-namespace-local
  to `rawv_codec.cpp`, not part of any public header.
- Produces: nothing new for later tasks in this plan — Task 2 modifies
  `encodeFrame`'s pass-1 loop, which doesn't depend on `BitWriter`/`BitReader`'s
  internals, only that `writeRice`/`readRice`'s public method signatures
  (`bool writeRice(uint32_t value, uint32_t k)`, `bool readRice(uint32_t k,
  uint32_t* value)`) are unchanged — they are.

This is a refactor, not new functionality: there's no natural "RED" state to
write a failing test against, since the current per-bit implementation is
already correct. Instead, Step 1 adds a regression-pinning test targeting the
specific edge case the new implementation introduces (a 32-bit chunk-drain
boundary the old per-bit loop has no equivalent of), confirms it passes under
today's code, then the refactor lands and all tests (old + new) must still
pass — proving the swap preserved behavior.

- [ ] **Step 1: Add a stress test for large Rice quotients, confirm it passes today**

Add to `core/tests/test_rawv_codec.cpp`, anywhere among the other
`TEST_CASE`s (e.g. right after "round-trips pseudo-random noise..."):

```cpp
TEST_CASE("round-trips a frame with one extreme residual spike (forces multi-chunk Rice quotients)") {
  // Flat content picks a small Rice k (near 0), then one pixel jumps to
  // maxVal -- its residual is large enough that q = residual >> k exceeds
  // 32, exercising the batched BitWriter/BitReader's chunk-draining loop
  // (Task 1 drains 32 bits at a time for large quotients), a boundary the
  // original per-bit implementation has no equivalent of.
  auto src = makeFrame(64, 64, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return (x == 40 && y == 40) ? maxVal : static_cast<uint16_t>(maxVal / 2);
  });
  CHECK(roundTrips(src, 64, 64, 16));
}
```

Build and run just this suite to confirm it passes under the CURRENT (pre-refactor)
implementation:

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the new test (it's establishing a baseline, not
testing new behavior yet).

- [ ] **Step 2: Replace `BitWriter` and `BitReader` with batched implementations**

In `core/src/rawv_codec.cpp`, replace the entire `BitWriter` class definition
(from `class BitWriter {` through its closing `};`) with:

```cpp
// MSB-first bit writer over a caller-owned, pre-zeroed buffer, using a
// 64-bit accumulator so multi-bit fields (the Rice remainder, and runs of
// quotient one-bits) are packed with one shift+OR instead of one function
// call per bit. The per-bit version was the throughput bottleneck at real
// camera resolution (~91% dropped frames at 4096x3072@24fps on-device,
// 2026-08-05) -- see docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md.
// Produces the byte-for-byte IDENTICAL bitstream the per-bit version did;
// this is a performance refactor, not a new format.
class BitWriter {
 public:
  BitWriter(uint8_t* buf, uint32_t capacity) : buf_(buf), capacity_(capacity) {}

  // Writes the low `nbits` bits of `bits` (nbits in [0,32]), most
  // significant of those bits first, immediately after any bits already
  // written. Returns false (and stops writing) once `capacity` would be
  // exceeded.
  bool writeBits(uint32_t bits, uint32_t nbits) {
    if (nbits == 0) return true;
    acc_ = (acc_ << nbits) | static_cast<uint64_t>(bits & maskFor(nbits));
    accBits_ += nbits;
    while (accBits_ >= 8) {
      if (bytePos_ >= capacity_) return false;
      accBits_ -= 8;
      buf_[bytePos_++] = static_cast<uint8_t>(acc_ >> accBits_);
    }
    return true;
  }

  // `q` one-bits, a zero bit, then `k` bits of `value`'s low bits -- the
  // standard Golomb-Rice codeword shape, batched into at most 3 writeBits
  // calls total per pixel (vs. up to q+1+k individual writeBit calls in the
  // per-bit version).
  bool writeRice(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    while (q >= 32) {
      if (!writeBits(0xFFFFFFFFu, 32)) return false;
      q -= 32;
    }
    // q one-bits followed by a terminating zero bit, as one (q+1)-bit field.
    uint32_t qval = (q == 0) ? 0u : (((1u << q) - 1u) << 1);
    if (!writeBits(qval, q + 1)) return false;
    if (k > 0 && !writeBits(value, k)) return false;
    return true;
  }

  // Flushes any partial byte (zero-padded, matching the pre-zeroed-buffer
  // padding semantics the per-bit version relied on) and returns the total
  // bytes written. Not const: the flush is a real write, deferred from
  // writeBits() until now since fewer than 8 bits may still be pending.
  uint32_t finishedBytes() {
    if (accBits_ > 0 && bytePos_ < capacity_) {
      buf_[bytePos_++] = static_cast<uint8_t>(acc_ << (8 - accBits_));
      accBits_ = 0;
    }
    return bytePos_;
  }

 private:
  static uint32_t maskFor(uint32_t nbits) {
    return nbits >= 32 ? 0xFFFFFFFFu : ((1u << nbits) - 1u);
  }
  uint8_t* buf_;
  uint32_t capacity_;
  uint32_t bytePos_ = 0;
  uint64_t acc_ = 0;
  uint32_t accBits_ = 0;
};
```

Replace the entire `BitReader` class definition with:

```cpp
// Matches BitWriter's accumulator approach on the read side. The Rice
// remainder (up to 19 bits, see riceParamFor's k<20 cap) is read in one
// batched call; the unary quotient is still read one bit at a time since
// its expected length is ~1 bit (riceParamFor picks k so that's true for
// any well-behaved frame) -- batching that too would add real complexity
// for a part that's already cheap on average. Decode speed was not the
// throughput blocker Task 8 found (capture/encode was); this still speeds
// up export of compressed clips via the now-batched remainder reads.
class BitReader {
 public:
  BitReader(const uint8_t* buf, uint32_t size) : buf_(buf), size_(size) {}

  // Reads `nbits` bits (nbits in [0,32]) MSB-first into the low bits of
  // *out. Returns false if not enough bits remain in the buffer.
  bool readBits(uint32_t nbits, uint32_t* out) {
    if (nbits == 0) { *out = 0; return true; }
    while (accBits_ < nbits) {
      if (bytePos_ >= size_) return false;
      acc_ = (acc_ << 8) | buf_[bytePos_++];
      accBits_ += 8;
    }
    accBits_ -= nbits;
    *out = static_cast<uint32_t>((acc_ >> accBits_) & maskFor(nbits));
    return true;
  }

  bool readRice(uint32_t k, uint32_t* value) {
    uint32_t q = 0, bit = 0;
    while (true) {
      if (!readBits(1, &bit)) return false;
      if (bit == 0) break;
      if (++q > (1u << 24)) return false;  // corrupt-stream guard
    }
    uint32_t remainder = 0;
    if (k > 0 && !readBits(k, &remainder)) return false;
    *value = (q << k) + remainder;
    return true;
  }

 private:
  static uint32_t maskFor(uint32_t nbits) {
    return nbits >= 32 ? 0xFFFFFFFFu : ((1u << nbits) - 1u);
  }
  const uint8_t* buf_;
  uint32_t size_;
  uint64_t acc_ = 0;
  uint32_t accBits_ = 0;
  uint32_t bytePos_ = 0;
};
```

No other code in `rawv_codec.cpp` needs to change: `encodeFrame`'s
`bw.writeRice(z, k)` call and `1 + bw.finishedBytes()` return, and
`decodeFrame`'s `br.readRice(k, &z)` call, already use exactly these two
methods' existing signatures.

- [ ] **Step 3: Build and run the full `rawv_codec` suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the Step 1 stress test and the pre-existing
tests (flat frame, gradient, noise, edge rows/columns, too-small-capacity,
padded stride).

- [ ] **Step 4: Run the full host suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 8 suites pass — `test_rawv_writer`/`test_rawv_reader`/`test_export`
exercise `.rawv` files containing `CompressedPredictive` frames end-to-end, so
any bitstream-format regression from this refactor would surface here too.

- [ ] **Step 5: Commit**

```bash
git add core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "perf: batch rawv_codec's BitWriter/BitReader instead of per-bit calls"
```

---

### Task 2: Sample instead of scanning every pixel to pick the Rice parameter

**Files:**
- Modify: `core/src/rawv_codec.cpp`
- Modify: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new for Task 3 — `encodeFrame`'s public behavior
  (round-trip correctness) is unchanged; only how `k` gets chosen changes,
  which is purely a compression-efficiency knob, not a correctness one (the
  decoder decodes whatever `k` byte was actually written, regardless of how
  the encoder picked it).

`encodeFrame`'s pass 1 (summing `|actual - predicted|` over every pixel to
pick `k` via `riceParamFor`) currently costs as much CPU as the real encode
pass despite doing no bit I/O at all — it's a second full 12.6M-pixel scan.
Sampling every 4th pixel in both dimensions (1/16th of pixels) cuts that scan
~16-fold while still producing a statistically solid `k` for real sensor
noise, which doesn't vary pixel-to-pixel in a way uniform sampling would miss.

- [ ] **Step 1: Add a round-trip test at dimensions not divisible by the sample stride**

Add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("round-trips dimensions not evenly divisible by the k-sampling stride") {
  // 63x65 isn't a multiple of the 4x4 sampling stride Task 2 introduces --
  // this pins that the sampling loop's bounds never go out of range and
  // still produce a usable k (count is always >= 1 since (0,0) is always
  // sampled) regardless of width/height parity.
  auto src = makeFrame(63, 65, 12, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 17 + y * 5) ^ 0x2A) % (maxVal + 1));
  });
  CHECK(roundTrips(src, 63, 65, 12));
}
```

Build and run to confirm it passes under Task 1's already-landed code (still
full-scan k-selection at this point):

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the new test.

- [ ] **Step 2: Change pass 1 to sample a strided grid**

In `core/src/rawv_codec.cpp`'s `encodeFrame`, replace:

```cpp
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
```

with:

```cpp
  // Sample a strided grid (1/16th of pixels) instead of scanning every one
  // -- this pass does no bit I/O, so its only cost is the scan itself, and
  // real sensor noise doesn't vary pixel-to-pixel in a way uniform sampling
  // would miss. (0,0) is always included (x=0,y=0 satisfies any stride), so
  // count is always >= 1 for any non-empty frame -- no divide-by-zero risk
  // even though riceParamFor doesn't divide, just compares.
  constexpr uint32_t kSampleStride = 4;
  uint64_t sumAbs = 0;
  uint64_t count = 0;
  for (uint32_t y = 0; y < height; y += kSampleStride) {
    for (uint32_t x = 0; x < width; x += kSampleStride) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
      count++;
    }
  }
  uint32_t k = riceParamFor(sumAbs, count);
```

- [ ] **Step 3: Build and run the full `rawv_codec` suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe --build core/build
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure -R rawv_codec
```
Expected: all pass, including the Step 1 test. (This changes compression
ratio slightly, not correctness — no existing test asserts an exact `k` or
exact `payloadBytes`, only round-trip fidelity, so none should need updating.)

- [ ] **Step 4: Run the full host suite**

```
C:\Users\User\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ctest.exe --test-dir core/build --output-on-failure
```
Expected: all 8 suites pass.

- [ ] **Step 5: Commit**

```bash
git add core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "perf: sample a strided grid instead of scanning every pixel to pick Rice k"
```

---

### Task 3: On-device re-verification

**Files:** none (not a code task).

**Interfaces:**
- Consumes: Tasks 1-2's optimized `rawv_codec.cpp`, built into the app via
  `app/src/main/cpp/capture.cpp`'s existing `CompressedPredictive` branch
  (already implemented, Task 6 of the original plan — nothing to change
  there).

This is the actual acceptance gate — host tests at 64×64 cannot prove
real-time throughput on a phone's ARM64 core, exactly as the original plan's
Task 8 required for the first attempt.

- [ ] **Step 1: Build and install**

```
.\gradlew.bat assembleRelease
```
Then `adb install -r` the resulting `app-release.apk` (or uninstall/reinstall
if the previous install's signature doesn't match, per this project's
established recurring-install-conflict fix).

- [ ] **Step 2: Record with compression ON at this project's usual resolution/fps**

Confirm "Compress recordings" is ON in Settings (RECORDING section). Record
for at least 30 seconds at the device's active resolution/fps (this device:
4096×3072@24fps). Read the live written/dropped counters (left sidebar while
recording) or the post-stop toast.

Expected: 0 dropped frames, or close enough to 0 that it's clearly no longer
a systemic ~90%-loss failure — judge against this same device's
compression-OFF baseline (738-739 written / 0 dropped over ~31s in the
2026-08-05 control test) as the bar to match.

- [ ] **Step 3: Record findings**

Update `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
with the new written/dropped counts, and update
`docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md`'s
status line:
- If the throughput requirement is now met: mark the feature ready to ship,
  note the achieved frame-drop numbers.
- If it's improved but still failing: note the new (presumably much better,
  but possibly still nonzero) drop numbers, and that a further round (e.g.
  parallelizing the encode across threads, or NEON-vectorizing the
  predictor) would need its own follow-up plan — do not design that
  speculatively here; scope it only if this task's real measurement shows
  it's still needed.

- [ ] **Step 4: Commit the documentation update**

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md
git commit -m "docs: re-verify rawv_codec throughput after batched BitWriter/BitReader fix"
```
