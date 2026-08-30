# Selectable Record Bit Depth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user record at a chosen bit depth (Native/14/12/10/8) instead of always at sensor-native depth, reducing file size and sustained write bandwidth.

**Architecture:** Samples are reduced by a right-shift applied **on read inside the encode path only** (approach A in the spec) — never as a separate pass, which would add ~600 MB/s of memory traffic on a bandwidth-bound workload. The reduced depth is described entirely by the existing `FileHeader.whiteLevel` and `blackLevel[4]` fields, so `capture.cpp:189`'s `bitDepth = 32 - clz(whiteLevel)` derivation, the pack-mode selection at `capture.cpp:423`, `dng_writer`'s tags 50714/50717, and `preview.cpp`'s normalisation all follow automatically. No format change, no version bump.

**Tech Stack:** Kotlin + Jetpack Compose (app), C++17 (core + JNI), doctest (host core tests), JUnit4 (JVM tests), Gradle, CMake/ctest.

**Spec:** `docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md`

## Global Constraints

- **`whiteLevel` is TRUNCATED (`>> n`); samples are ROUNDED then CLAMPED; `blackLevel[4]` is ROUNDED.** These are three different rules on the same shift. Rounding `whiteLevel` 16383 by 2 gives 4096, which needs 13 bits, so `32 - clz(whiteLevel)` would run the codec one bit wider than the samples.
- Sample reduction is exactly: `min((x + (1 << (n-1))) >> n, newWhite)` where `newWhite = whiteLevel >> n`.
- Level reduction is exactly: `(level + (1 << (n-1))) >> n` for each of the four `blackLevel` entries.
- `n == 0` (Native) must be a true no-op on every path — **bit-identical AND cost-identical** to today. Native is the default, so a per-sample `if (shift == 0)` branch in the encoder's inner loop would tax every existing user for a feature they have not enabled, on the very path this work exists to speed up. The reduction is therefore selected by `template <bool Reduce>` and dispatched **once** per frame in `encodeFrame`, so `Reduce=false` compiles to exactly today's instructions. Bit-exactness alone is not sufficient evidence: Task 2 gates on a timing comparison as well.
- **No dithering.** It adds entropy and works against the purpose.
- **No new `PackMode` enum value.** There is no `Packed8`; 8-bit routes to `Packed10` and wins nothing on the uncompressed path. This is documented, not fixed.
- Depth is stored as **requested**; clamping to the lens's native depth happens only at capture. Lens switching never rewrites the stored value.
- Default is **Native (0)**. Do not change it in this plan.
- Host core tests: build and run from **PowerShell**, not the Bash tool — the Bash sandbox kills the compiler silently (exit 1, empty output).

---

### Task 1: Bit-depth reduction primitives in core

The three scaling rules, isolated and independently testable, before anything consumes them.

**Files:**
- Create: `core/include/rawcam/bit_depth.h`
- Create: `core/tests/test_bit_depth.cpp`
- Modify: `core/CMakeLists.txt` (register the new test source)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `uint32_t rawcam::reducedWhiteLevel(uint32_t whiteLevel, uint32_t shift)`
  - `uint32_t rawcam::reduceLevel(uint32_t level, uint32_t shift)`
  - `uint16_t rawcam::reduceSample(uint16_t sample, uint32_t shift, uint32_t newWhite)`
  - `uint32_t rawcam::shiftForDepth(uint32_t whiteLevel, uint32_t requestedDepth)` — returns `0` when `requestedDepth == 0` (Native) or when it meets/exceeds native depth (the clamp).

- [ ] **Step 1: Write the failing test**

Create `core/tests/test_bit_depth.cpp`:

```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/bit_depth.h"

using namespace rawcam;

TEST_CASE("whiteLevel is truncated so the derived bit depth stays correct") {
  // 14->12. Rounding would give 4096, which needs 13 bits: the codec would then
  // run one bit wider than the samples it is handed.
  CHECK(reducedWhiteLevel(16383, 2) == 4095);
  CHECK(32u - (uint32_t)__builtin_clz(reducedWhiteLevel(16383, 2)) == 12u);
  CHECK(reducedWhiteLevel(16383, 6) == 255);
  CHECK(32u - (uint32_t)__builtin_clz(reducedWhiteLevel(16383, 6)) == 8u);
}

TEST_CASE("black level is rounded, not truncated") {
  CHECK(reduceLevel(1024, 2) == 256);
  CHECK(reduceLevel(1023, 2) == 256);  // rounds up; truncation would give 255
  CHECK(reduceLevel(0, 2) == 0);
}

TEST_CASE("samples are rounded and clamped to the new white level") {
  const uint32_t nw = reducedWhiteLevel(16383, 2);  // 4095
  CHECK(reduceSample(0, 2, nw) == 0);
  CHECK(reduceSample(4, 2, nw) == 1);
  CHECK(reduceSample(6, 2, nw) == 2);      // rounds up
  CHECK(reduceSample(16383, 2, nw) == nw); // would be 4096 unclamped
}

TEST_CASE("a zero shift is an exact no-op") {
  CHECK(reducedWhiteLevel(16383, 0) == 16383);
  CHECK(reduceLevel(1024, 0) == 1024);
  for (uint32_t v = 0; v < 16384; v += 97) {
    CHECK(reduceSample((uint16_t)v, 0, 16383) == (uint16_t)v);
  }
}

TEST_CASE("shiftForDepth clamps to native and treats 0 as Native") {
  CHECK(shiftForDepth(16383, 0) == 0);   // Native
  CHECK(shiftForDepth(16383, 14) == 0);  // equals native
  CHECK(shiftForDepth(16383, 12) == 2);
  CHECK(shiftForDepth(16383, 8) == 6);
  CHECK(shiftForDepth(1023, 12) == 0);   // 10-bit lens cannot reach 12: clamp
  CHECK(shiftForDepth(1023, 8) == 2);
}
```

Register it in `core/CMakeLists.txt` alongside the existing test sources (follow the pattern used for `test_pack10.cpp`).

- [ ] **Step 2: Run test to verify it fails**

Run from **PowerShell**:
```powershell
cd C:\Users\User\rawcam\core; cmake -S . -B build; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: FAIL — `rawcam/bit_depth.h` not found.

- [ ] **Step 3: Write minimal implementation**

Create `core/include/rawcam/bit_depth.h`:

```cpp
#pragma once
#include <cstdint>

namespace rawcam {

// See docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md.
// whiteLevel truncates, levels and samples round. The asymmetry is deliberate:
// capture.cpp derives bitDepth as 32 - clz(whiteLevel), so a rounded whiteLevel
// can carry into the next bit and desynchronise the codec from its own samples.

inline uint32_t reducedWhiteLevel(uint32_t whiteLevel, uint32_t shift) {
  return whiteLevel >> shift;
}

inline uint32_t reduceLevel(uint32_t level, uint32_t shift) {
  if (shift == 0) return level;
  return (level + (1u << (shift - 1))) >> shift;
}

inline uint16_t reduceSample(uint16_t sample, uint32_t shift, uint32_t newWhite) {
  if (shift == 0) return sample;
  uint32_t r = ((uint32_t)sample + (1u << (shift - 1))) >> shift;
  return (uint16_t)(r > newWhite ? newWhite : r);
}

// 0 means "no reduction": either Native was requested, or the request meets or
// exceeds what this sensor delivers (the per-lens clamp).
inline uint32_t shiftForDepth(uint32_t whiteLevel, uint32_t requestedDepth) {
  if (requestedDepth == 0 || whiteLevel == 0) return 0;
  uint32_t nativeDepth = 32u - (uint32_t)__builtin_clz(whiteLevel);
  if (requestedDepth >= nativeDepth) return 0;
  return nativeDepth - requestedDepth;
}

}  // namespace rawcam
```

- [ ] **Step 4: Run test to verify it passes**

Run from **PowerShell**:
```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: PASS, and every pre-existing core suite still passes.

- [ ] **Step 5: Commit**

```bash
git add core/include/rawcam/bit_depth.h core/tests/test_bit_depth.cpp core/CMakeLists.txt
git commit -m "feat(core): bit-depth reduction primitives"
```

---

### Task 2: Thread the shift through the compressed encoder

`predictAt` serves both encode and decode. Encode must see reduced neighbours; decode must not shift again, because its buffer already holds reduced values.

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h:24-29` (`encodeFrame` signature)
- Modify: `core/src/rawv_codec.cpp` (`predictAt` ~line 310, `computeBands` pass 1 ~line 540, the band workers' fused predict+residual+pack, `decodeFrame`)
- Test: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: `rawcam::reduceSample`, `rawcam::reducedWhiteLevel` from Task 1.
- Produces: `encodeFrame` gains two trailing parameters —
  `uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height, uint32_t rowStrideSamples, uint32_t bitDepth, uint8_t* out, uint32_t outCapacity, uint32_t shift = 0, uint32_t newWhite = 0)`.
  `decodeFrame` is **unchanged** — it already receives the reduced `bitDepth` and its stored samples are already reduced.

- [ ] **Step 1: Write the failing test**

Append to `core/tests/test_rawv_codec.cpp`:

```cpp
// A gradient plus small noise, NOT uniform random. Uniform 14-bit noise is
// near-incompressible: Rice-coded it can exceed 16 bits/sample, so encodeFrame
// would return 0 for insufficient outCapacity and these tests would fail on
// capacity rather than on the behaviour under test. This shape also exercises
// the q == 0 fast path that dominates real frames.
static std::vector<uint16_t> testFrame(uint32_t w, uint32_t h) {
  std::vector<uint16_t> v(w * h);
  for (uint32_t y = 0; y < h; y++)
    for (uint32_t x = 0; x < w; x++)
      v[y * w + x] = (uint16_t)(((x * 3 + y * 5) % 512) * 24 + ((x * 7919 + y) % 17));
  return v;
}

TEST_CASE("encoding with a shift round-trips to the reduced samples") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  const uint32_t shift = 2, newWhite = rawcam::reducedWhiteLevel(16383, shift);
  std::vector<uint16_t> expected(src.size());
  for (size_t i = 0; i < src.size(); i++)
    expected[i] = rawcam::reduceSample(src[i], shift, newWhite);

  std::vector<uint8_t> enc(src.size() * 4 + 4096);
  uint32_t n = rawcam::encodeFrame(src.data(), w, h, stride, 12, enc.data(),
                                   (uint32_t)enc.size(), shift, newWhite);
  REQUIRE(n > 0);

  std::vector<uint16_t> back(src.size());
  REQUIRE(rawcam::decodeFrame(enc.data(), n, back.data(), w, h, stride, 12));
  CHECK(back == expected);
}

TEST_CASE("a shift genuinely shrinks the encoded frame") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  std::vector<uint8_t> a(src.size() * 4 + 4096), b(src.size() * 4 + 4096);
  uint32_t full = rawcam::encodeFrame(src.data(), w, h, stride, 14, a.data(), (uint32_t)a.size());
  uint32_t red = rawcam::encodeFrame(src.data(), w, h, stride, 12, b.data(), (uint32_t)b.size(),
                                     2, rawcam::reducedWhiteLevel(16383, 2));
  REQUIRE(full > 0);
  REQUIRE(red > 0);
  CHECK(red < full);
}

TEST_CASE("shift 0 is bit-identical to the pre-existing encoder") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  std::vector<uint8_t> a(src.size() * 4 + 4096), b(src.size() * 4 + 4096);
  uint32_t x = rawcam::encodeFrame(src.data(), w, h, stride, 14, a.data(), (uint32_t)a.size());
  uint32_t y = rawcam::encodeFrame(src.data(), w, h, stride, 14, b.data(), (uint32_t)b.size(), 0, 16383);
  REQUIRE(x > 0);
  CHECK(x == y);
  CHECK(std::equal(a.begin(), a.begin() + x, b.begin()));
}

// Native is the DEFAULT. Bit-exactness above proves correctness; this proves we
// did not tax every existing user with a per-sample branch for a feature they
// have not enabled. Generous threshold: this catches a structural regression
// (a branch in the inner loop), not small machine noise.
TEST_CASE("shift 0 costs no more than the pre-existing encoder") {
  const uint32_t w = 512, h = 256, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);
  std::vector<uint8_t> out(src.size() * 4 + 4096);

  auto timeIt = [&](bool withShiftArgs) {
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < 20; i++) {
      if (withShiftArgs)
        rawcam::encodeFrame(src.data(), w, h, stride, 14, out.data(), (uint32_t)out.size(), 0, 16383);
      else
        rawcam::encodeFrame(src.data(), w, h, stride, 14, out.data(), (uint32_t)out.size());
    }
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  };

  timeIt(false);  // warm caches and clocks; discard
  const double bare = timeIt(false);
  const double zeroShift = timeIt(true);
  CHECK(zeroShift < bare * 1.15);
}
```

`test_rawv_codec.cpp` already includes `<chrono>`; keep that include.

- [ ] **Step 2: Run test to verify it fails**

Run from **PowerShell**:
```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: FAIL — `encodeFrame` takes 7 arguments, not 9.

- [ ] **Step 3: Write minimal implementation**

In `core/src/rawv_codec.cpp`:

**Template on the reduction, do not branch per sample.** A runtime `shift == 0`
check inside `predictAt` runs up to three times per pixel in the hottest loop in
the codec, and Native — which pays it for nothing — is the default. Make the
decision once, at frame level, and let the compiler erase it:

1. Give `predictAt` a compile-time reduction parameter. With `Reduce=false` the
   `at()` lambda folds away to exactly today's load:

```cpp
template <bool Reduce>
inline int32_t predictAt(const uint16_t* plane, uint32_t x, uint32_t y,
                         uint32_t rowStrideSamples, uint32_t bitDepth,
                         uint32_t shift, uint32_t newWhite) {
  bool hasLeft = x >= 2;
  bool hasUp = y >= 2;
  if (!hasLeft && !hasUp) return 1 << (bitDepth - 1);
  auto at = [&](uint32_t yy, uint32_t xx) -> int32_t {
    uint16_t s = plane[yy * rowStrideSamples + xx];
    if constexpr (Reduce) return reduceSample(s, shift, newWhite);
    else return s;
  };
  if (!hasLeft) return at(y - 2, x);
  if (!hasUp) return at(y, x - 2);
  return medPredict(at(y, x - 2), at(y - 2, x), at(y - 2, x - 2));
}
```

2. `decodeFrame` calls `predictAt<false>(..., 0, 0)`. Its buffer already holds
   reduced values; reducing again would corrupt reconstruction.

3. Give the encode-side helpers the same `template <bool Reduce>` treatment —
   `computeBands` pass 1, the band workers' fused predict+residual+pack loop, and
   `computeInteriorResidualsRow`. In each, the actual sample is reduced under the
   same `if constexpr`, so `k` selection reflects the reduced data:

```cpp
uint16_t s = raw16[y * rowStrideSamples + x];
int32_t actual = Reduce ? reduceSample(s, shift, newWhite) : (int32_t)s;
int32_t predicted = predictAt<Reduce>(raw16, x, y, rowStrideSamples, bitDepth, shift, newWhite);
```

4. `encodeFrame` dispatches **once**, on entry:

```cpp
return (shift == 0) ? encodeFrameImpl<false>(raw16, width, height, rowStrideSamples,
                                             bitDepth, out, outCapacity, 0, 0)
                    : encodeFrameImpl<true>(raw16, width, height, rowStrideSamples,
                                            bitDepth, out, outCapacity, shift, newWhite);
```

5. Store `shift`/`newWhite` on the encoder alongside `jobBitDepth_`
   (`jobShift_`, `jobNewWhite_`) so the band workers can reach them.

6. Add `#include "rawcam/bit_depth.h"` and `#include <chrono>` is already present
   in the test file.

> **`computeInteriorResidualsRow` is public API** (`rawv_codec.h:50`) and is
> consumed by `core/tests/test_rawv_simd_spike.cpp`. Templating it changes that
> call site — give it a non-template forwarding overload with the old signature
> (delegating to `<false>`) so the spike test compiles unchanged.

> Every read of `raw16` in the **encode** path must go through the `Reduce`
> branch. A missed site does not fail loudly — it silently corrupts output. Grep
> `raw16[` in the file and confirm each hit is either reduced or on the decode
> path.

- [ ] **Step 4: Run test to verify it passes**

Run from **PowerShell**:
```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: PASS, including every pre-existing `test_rawv_codec.cpp` case (they call the 7-arg form and must still be bit-exact via the defaulted parameters).

- [ ] **Step 5: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "feat(core): reduce samples on read in the compressed encoder"
```

---

### Task 3: Reduce samples on the uncompressed packed paths

**Files:**
- Modify: `core/include/rawcam/pack10.h:11,17`
- Modify: `core/src/pack10.cpp`
- Test: `core/tests/test_pack10.cpp`, `core/tests/test_pack12.cpp`

**Interfaces:**
- Consumes: `rawcam::reduceSample` from Task 1.
- Produces:
  - `void pack10(const uint16_t* src, size_t count, uint8_t* dst, uint32_t shift = 0, uint32_t newWhite = 0)`
  - `void pack12(const uint16_t* src, size_t count, uint8_t* dst, uint32_t shift = 0, uint32_t newWhite = 0)`
  - `unpack10`/`unpack12` unchanged.

- [ ] **Step 1: Write the failing test**

Append to `core/tests/test_pack10.cpp`:

```cpp
TEST_CASE("pack10 reduces samples before packing") {
  std::vector<uint16_t> src = {4095, 2048, 7, 4};
  const uint32_t shift = 2, newWhite = 1023;
  std::vector<uint8_t> packed(rawcam::packed10Size(src.size()));
  rawcam::pack10(src.data(), src.size(), packed.data(), shift, newWhite);

  std::vector<uint16_t> back(src.size());
  rawcam::unpack10(packed.data(), src.size(), back.data());
  for (size_t i = 0; i < src.size(); i++)
    CHECK(back[i] == rawcam::reduceSample(src[i], shift, newWhite));
}

TEST_CASE("pack10 with shift 0 is unchanged") {
  std::vector<uint16_t> src = {1023, 512, 3, 0};
  std::vector<uint8_t> a(rawcam::packed10Size(src.size())), b(a.size());
  rawcam::pack10(src.data(), src.size(), a.data());
  rawcam::pack10(src.data(), src.size(), b.data(), 0, 1023);
  CHECK(a == b);
}
```

Add the mirror-image pair to `core/tests/test_pack12.cpp` using `pack12`, `unpack12`, `packed12Size`, `src = {16383, 8192, 7, 4}`, `shift = 2`, `newWhite = 4095`.

- [ ] **Step 2: Run test to verify it fails**

```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: FAIL — `pack10` takes 3 arguments.

- [ ] **Step 3: Write minimal implementation**

In `core/src/pack10.cpp`, add the two defaulted parameters to `pack10` and `pack12` and route every source read through `reduceSample(src[i], shift, newWhite)`. Add `#include "rawcam/bit_depth.h"`. Leave `unpack10`/`unpack12` untouched — stored samples are already reduced.

- [ ] **Step 4: Run test to verify it passes**

```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: PASS, all suites.

- [ ] **Step 5: Commit**

```bash
git add core/include/rawcam/pack10.h core/src/pack10.cpp core/tests/test_pack10.cpp core/tests/test_pack12.cpp
git commit -m "feat(core): reduce samples on the packed uncompressed paths"
```

---

### Task 4: Carry the requested depth through JNI into the header

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/NativeBridge.kt:16`
- Modify: `app/src/main/cpp/jni_bridge.cpp:28`
- Modify: `app/src/main/cpp/capture.h` (the `start` declaration)
- Modify: `app/src/main/cpp/capture.cpp:357-362` (`start`), `:427-428` (header fields), and the encode/pack call sites at `:185-189` and around `:245`

**Interfaces:**
- Consumes: `rawcam::shiftForDepth`, `reducedWhiteLevel`, `reduceLevel` (Task 1); the new `encodeFrame` (Task 2); the new `pack10`/`pack12` (Task 3).
- Produces: `Capture::start(...)` gains a trailing `int32_t requestedBitDepth`; `NativeBridge.nativeStartRecording(...)` gains a trailing `requestedBitDepth: Int`.

`capture.cpp` has no host harness, so the risky arithmetic — black-level scaling
and pack-mode selection — is **extracted into core as a pure function** and tested
there. That gives this task a genuine failing test instead of an on-device check
three tasks later.

- [ ] **Step 1: Write the failing test**

Append to `core/tests/test_bit_depth.cpp`:

```cpp
#include "rawcam/rawv.h"

TEST_CASE("applyBitDepth scales the header consistently and keeps the derived depth right") {
  FileHeader h{};
  h.whiteLevel = 16383;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 1024;

  uint32_t shift = applyBitDepth(h, 12);
  CHECK(shift == 2);
  CHECK(h.whiteLevel == 4095);                                 // truncated, NOT 4096
  CHECK(32u - (uint32_t)__builtin_clz(h.whiteLevel) == 12u);   // what capture.cpp:189 derives
  for (int i = 0; i < 4; i++) CHECK(h.blackLevel[i] == 256);   // rounded
  CHECK(h.blackLevel[0] < h.whiteLevel);
  // capture.cpp:423 selects the pack mode from whiteLevel: 4095 <= 0xFFF => Packed12.
  CHECK(h.whiteLevel <= 0xFFFu);
  CHECK(h.whiteLevel > 0x3FFu);
}

TEST_CASE("applyBitDepth leaves the header untouched for Native") {
  FileHeader h{};
  h.whiteLevel = 16383;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 1024;

  CHECK(applyBitDepth(h, 0) == 0);
  CHECK(h.whiteLevel == 16383);
  for (int i = 0; i < 4; i++) CHECK(h.blackLevel[i] == 1024);
}

TEST_CASE("applyBitDepth clamps a request the sensor cannot reach") {
  FileHeader h{};
  h.whiteLevel = 1023;                       // 10-bit ultra-wide
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;

  CHECK(applyBitDepth(h, 12) == 0);          // cannot synthesise precision
  CHECK(h.whiteLevel == 1023);
  CHECK(applyBitDepth(h, 8) == 2);
  CHECK(h.whiteLevel == 255);
  for (int i = 0; i < 4; i++) CHECK(h.blackLevel[i] == 16);
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: FAIL — `applyBitDepth` is not declared.

- [ ] **Step 3: Implement `applyBitDepth` in core**

Add to `core/include/rawcam/bit_depth.h` (it already has `<cstdint>`; add `#include "rawcam/rawv.h"`):

```cpp
// Scales a header in place for `requestedDepth` and returns the sample shift the
// encoder must apply. whiteLevel truncates; blackLevel rounds. Returns 0 (and
// changes nothing) for Native or for a request the sensor cannot reach.
inline uint32_t applyBitDepth(FileHeader& h, uint32_t requestedDepth) {
  uint32_t shift = shiftForDepth(h.whiteLevel, requestedDepth);
  if (shift == 0) return 0;
  h.whiteLevel = reducedWhiteLevel(h.whiteLevel, shift);
  for (int i = 0; i < 4; i++) h.blackLevel[i] = reduceLevel(h.blackLevel[i], shift);
  return shift;
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
cd C:\Users\User\rawcam\core; cmake --build build; ctest --test-dir build --output-on-failure
```
Expected: PASS, all core suites.

- [ ] **Step 5: Wire it through JNI and capture**

1. `capture.h` — **declare the two new members** (they are referenced below and
   exist nowhere yet):

```cpp
uint32_t sampleShift_ = 0;
uint32_t newWhite_ = 0;
```

2. `capture.h` / `capture.cpp:357` — add `int32_t requestedBitDepth` as the final
   parameter of `Capture::start`.
3. `jni_bridge.cpp:28` — accept the extra `jint` and forward it.
4. `NativeBridge.kt:16` — add `requestedBitDepth: Int` as the final parameter of
   `nativeStartRecording`.
5. **`CameraController.kt:648-656` passes a fixed positional argument list ending
   `spec.deviceName, compressRecordings`. Adding a parameter breaks it, so update
   it in THIS task** — append `/* requestedBitDepth = */ 0` after
   `compressRecordings`. Task 6 replaces the literal with the real setting. Without
   this step the build fails on arity before anything else can be checked.
6. In `Capture::start`, after the header template is populated with the sensor's
   own `whiteLevel`/`blackLevel` and **before** the `PackMode` selection at `:423`:

```cpp
sampleShift_ = rawcam::applyBitDepth(hdr, (uint32_t)requestedBitDepth);
newWhite_ = hdr.whiteLevel;
```

   The `PackMode` selection at `:423` and the `bitDepth` derivation at `:189` both
   read `whiteLevel` and now see the reduced value automatically. **They must run
   after `applyBitDepth`, not before.**
7. Pass `sampleShift_` / `newWhite_` into `encodeFrame` and into `pack10`/`pack12`
   at their call sites.
8. Add `#include "rawcam/bit_depth.h"`.

- [ ] **Step 6: Verify the wiring compiles and the app builds**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. A missed call site surfaces here as an arity error.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/NativeBridge.kt app/src/main/cpp/jni_bridge.cpp app/src/main/cpp/capture.h app/src/main/cpp/capture.cpp core/tests/test_bit_depth.cpp
git commit -m "feat(capture): carry requested bit depth into the clip header"
```

---

### Task 5: Persist the setting

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt:54` (field), `:146` (key), `:206` (read), `:255` (coerce), `:269` (write)
- Test: `app/src/test/java/com/shez/rawcam/settings/RecordBitDepthTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `Settings.recordBitDepth: Int` (0 = Native), and `RECORD_BIT_DEPTHS: List<Int> = listOf(0, 14, 12, 10, 8)` as a top-level `val` in `SettingsRepository.kt`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/settings/RecordBitDepthTest.kt`:

```kotlin
package com.shez.rawcam.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordBitDepthTest {
    @Test fun defaultsToNative() {
        assertEquals(0, Settings().recordBitDepth)
    }

    @Test fun theOfferedDepthsAreNativeAndTheFourRealOnes() {
        // No 16: no sensor here delivers it. Raw16 is a container, not a precision.
        assertEquals(listOf(0, 14, 12, 10, 8), RECORD_BIT_DEPTHS)
    }

    @Test fun everyOfferedDepthSurvivesCoercion() {
        for (d in RECORD_BIT_DEPTHS) {
            assertEquals(d, Settings(recordBitDepth = d).coerced().recordBitDepth)
        }
    }

    @Test fun anUnknownDepthFallsBackToNative() {
        assertEquals(0, Settings(recordBitDepth = 11).coerced().recordBitDepth)
        assertEquals(0, Settings(recordBitDepth = 16).coerced().recordBitDepth)
        assertEquals(0, Settings(recordBitDepth = -1).coerced().recordBitDepth)
    }
}
```

If `SettingsRepository` has no public `coerced()`, extract the existing coercion at `:250-256` into `internal fun Settings.coerced(): Settings` and have the write path call it, so the rule is testable rather than buried in a lambda.

- [ ] **Step 2: Run test to verify it fails**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.settings.RecordBitDepthTest"
```
Expected: FAIL — unresolved reference `recordBitDepth`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// SettingsRepository.kt, top level
/** Offered record depths; 0 = Native (follow the sensor). No 16: no sensor here
 *  delivers it -- Raw16 is a container format, not a precision. */
val RECORD_BIT_DEPTHS = listOf(0, 14, 12, 10, 8)
```

Add `val recordBitDepth: Int = 0,` to `Settings`, `private val KEY_RECORD_BIT_DEPTH = intPreferencesKey("recordBitDepth")`, the read at `:206`, the write at `:269`, and in the coercion:

```kotlin
recordBitDepth = if (updated.recordBitDepth in RECORD_BIT_DEPTHS) updated.recordBitDepth else 0,
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:testDebugUnitTest
```
Expected: PASS, and the full JVM suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt app/src/test/java/com/shez/rawcam/settings/RecordBitDepthTest.kt
git commit -m "feat(settings): persist the requested record bit depth"
```

---

### Task 6: Clamp per lens, and keep the time-left readout honest

The requested value is stored; the *effective* value is clamped at capture. `captureRateKey` must gain depth as an axis or a 14-bit measurement will mispredict a 12-bit take.

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (`frameRecordBytes` ~line 1294, `captureRateKey` ~line 1321)
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt:652` (pass the requested depth through)
- Test: `app/src/test/java/com/shez/rawcam/ui/RecordBitDepthClampTest.kt` (create)

**Interfaces:**
- Consumes: `RECORD_BIT_DEPTHS`, `Settings.recordBitDepth` (Task 5).
- Produces: `internal fun effectiveBitDepth(whiteLevel: Int, requested: Int): Int` in `RecordScreen.kt` — returns the depth actually recorded (native depth when `requested == 0` or exceeds native).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/ui/RecordBitDepthClampTest.kt`:

```kotlin
package com.shez.rawcam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordBitDepthClampTest {
    // Measured 2026-08-30: main cam whiteLevel 16383 (14-bit), ultra-wide 1023 (10-bit).
    private val main = 16383
    private val ultraWide = 1023

    @Test fun nativeResolvesToTheSensorsOwnDepth() {
        assertEquals(14, effectiveBitDepth(main, 0))
        assertEquals(10, effectiveBitDepth(ultraWide, 0))
    }

    @Test fun aRequestBelowNativeIsHonoured() {
        assertEquals(12, effectiveBitDepth(main, 12))
        assertEquals(8, effectiveBitDepth(ultraWide, 8))
    }

    @Test fun aRequestAboveNativeClampsDownToNative() {
        assertEquals(10, effectiveBitDepth(ultraWide, 12))
        assertEquals(10, effectiveBitDepth(ultraWide, 14))
    }

    @Test fun depthIsAnAxisOfTheCaptureRateKey() {
        // Without this, a rate measured at 14-bit mispredicts a 12-bit take and
        // re-breaks the time-left readout.
        val a = captureRateKey(null, RAW_SPEC_FOR_TEST, true, null, 14)
        val b = captureRateKey(null, RAW_SPEC_FOR_TEST, true, null, 12)
        assertNotEquals(a, b)
    }
}
```

`RawSpec` has 13 fields (`CameraController.kt:64-74`). Declare the literal at the top of the test file, mirroring the main camera:

```kotlin
private val RAW_SPEC_FOR_TEST = CameraController.RawSpec(
    width = 4096, height = 3072, cfa = 0, whiteLevel = 16383,
    blackLevel = intArrayOf(1024, 1024, 1024, 1024),
    colorMatrix1 = FloatArray(9), isoRange = 50..6400, maxFps = 30,
    minFocusDiopters = 0f, deviceName = "24030PN60G",
    illuminant1 = 21, illuminant2 = 0, colorMatrix2 = FloatArray(9),
)
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:testDebugUnitTest --tests "com.shez.rawcam.ui.RecordBitDepthClampTest"
```
Expected: FAIL — unresolved reference `effectiveBitDepth`; `captureRateKey` takes 4 arguments.

- [ ] **Step 3: Write minimal implementation**

In `RecordScreen.kt`:

```kotlin
/** The depth actually recorded: Native (0) and any request at or above what the
 *  sensor delivers both resolve to the sensor's own depth. Precision that never
 *  left the sensor cannot be synthesised. */
internal fun effectiveBitDepth(whiteLevel: Int, requested: Int): Int {
    if (whiteLevel <= 0) return 0
    val native = 32 - Integer.numberOfLeadingZeros(whiteLevel)
    return if (requested == 0 || requested >= native) native else requested
}
```

Add a trailing `bitDepth: Int` parameter to `captureRateKey` and include it in the key string. Change `frameRecordBytes` to take the effective depth and select the packing from it rather than from `spec.whiteLevel`:

```kotlin
private fun frameRecordBytes(spec: CameraController.RawSpec, zoom: ZoomStop?, bitDepth: Int): Long {
    val w = zoom?.cropW ?: spec.width
    val h = zoom?.cropH ?: spec.height
    val pixels = w.toLong() * h
    val payload = when {
        bitDepth <= 10 && w % 4 == 0 -> (pixels / 4) * 5
        bitDepth <= 12 && w % 2 == 0 -> (pixels / 2) * 3
        else -> pixels * 2
    }
    return payload + 64
}
```

Update both call sites (`remainingLabel`, `startRecordingInternal`) to pass `effectiveBitDepth(spec.whiteLevel, state.settings.recordBitDepth)`. In `CameraController.kt:652`, pass `requestedBitDepth` into `nativeStartRecording`.

- [ ] **Step 4: Run test to verify it passes**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:testDebugUnitTest
```
Expected: PASS, whole JVM suite green — including `RemainingTimeTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt app/src/main/java/com/shez/rawcam/camera/CameraController.kt app/src/test/java/com/shez/rawcam/ui/RecordBitDepthClampTest.kt
git commit -m "feat(record): clamp bit depth per lens and key capture rates by it"
```

---

### Task 7: The Settings picker, with per-lens entries disabled

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt:450-478` (`EnumRow` gains an enabled predicate), and the RECORDING section around `:209-220` (add the row)

**Interfaces:**
- Consumes: `RECORD_BIT_DEPTHS` (Task 5), `effectiveBitDepth` (Task 6).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Extend `EnumRow` with an enabled predicate**

`EnumRow` currently makes every option clickable. Add a defaulted predicate so existing call sites are untouched:

```kotlin
@Composable
private fun <T> EnumRow(
    title: String, subtitle: String?, options: List<Pair<T, String>>, selected: T,
    onSelect: (T) -> Unit, isEnabled: (T) -> Boolean = { true },
) {
```

and inside the `options.forEach` loop:

```kotlin
options.forEach { (value, label) ->
    val on = value == selected
    val enabled = isEnabled(value)
    Row(
        Modifier
            .then(if (enabled) Modifier.clickable { onSelect(value) } else Modifier)
            .background(if (on) RawCamColors.SurfaceVariant else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = when {
                !enabled -> RawCamColors.Outline
                on -> RawCamColors.OnSurface
                else -> RawCamColors.Muted
            },
            fontSize = 13.sp,
        )
    }
}
```

- [ ] **Step 2: Add the row to the RECORDING section**

Immediately after the existing "Max clip length" `EnumRow`:

```kotlin
EnumRow(
    title = "Record bit depth",
    subtitle = "Lower depth shrinks files and eases sustained writes. " +
        "8-bit only saves space with compression on.",
    options = listOf(0 to "Native", 14 to "14", 12 to "12", 10 to "10", 8 to "8"),
    selected = settings.recordBitDepth,
    onSelect = { v -> apply { it.copy(recordBitDepth = v) } },
    // Native is never disabled; an explicit depth above what this lens delivers is.
    isEnabled = { v -> v == 0 || v <= activeLensNativeDepth },
)
```

`SettingsScreen` already collects the record state at `SettingsScreen.kt:85`
(`val recordUiState by viewModel.uiState.collectAsState()`), so the active lens is
in scope and no plumbing is needed. Declare, next to the other derived values:

```kotlin
// 0 when no lens is known yet (enumeration still running): every explicit depth
// is then disabled and only Native is selectable, which is the safe default.
val activeLensNativeDepth = effectiveBitDepth(
    recordUiState.lenses.getOrNull(recordUiState.lensIndex)?.whiteLevel ?: 0, 0,
)
```

- [ ] **Step 3: Build and install**

```powershell
cd C:\Users\User\rawcam; .\gradlew.bat :app:installDebug
```
Expected: BUILD SUCCESSFUL, "Installed on 1 device."

- [ ] **Step 4: Verify visually on the device**

Open Settings on the phone and screenshot the RECORDING section:

```powershell
$adb="C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell screencap -p /data/local/tmp/settings.png
& $adb pull -a /data/local/tmp/settings.png .
```

Confirm: the row reads Native/14/12/10/8; on the **main** camera all are selectable; after switching to the **ultra-wide**, 14 and 12 render greyed and do not respond to taps. A green build is not evidence a Compose row renders correctly — look at it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt
git commit -m "feat(settings): record bit depth picker with per-lens disabling"
```

---

### Task 8: End-to-end device verification

No new code. This is where the feature is proved, and where the spec's claims are either confirmed or retracted.

**Files:** none modified. Findings appended to `docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md`.

- [ ] **Step 1: Record one 12-bit clip on the main camera and verify the header**

With compression ON and depth set to 12, record ~15s, then:

```powershell
$adb="C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$clip = & $adb shell "ls -t /sdcard/Android/data/com.shez.rawcam.debug/files/clips/*.rawv | head -1"
& $adb shell "dd if=$clip of=/data/local/tmp/h.bin bs=512 count=1"
& $adb pull -a /data/local/tmp/h.bin .
```

Parse with Python and confirm `packMode@20 == 3` and `whiteLevel@28 == 4095`.
**`whiteLevel` must be 4095, not 4096** — 4096 means it was rounded rather than
truncated, and the codec ran at 13 bits against 12-bit samples.

For black level, **do not assert a hardcoded number** — this sensor's native
`blackLevel` has not been measured. Read it from a **Native** clip's header first
(`blackLevel@32`, four `uint32_t`), then assert the 12-bit clip's value equals
`(native + 2) >> 2` for each of the four entries. That checks the rule rather than
a guess about the sensor.

- [ ] **Step 2: Confirm the file actually shrank**

Record a matched ~15s Native clip of the same static scene. Compare bytes per frame (`fileSize / frameCount@112`). The 12-bit clip must be materially smaller. If it is not, the shift is not reaching the encoder.

- [ ] **Step 3: Export a DNG and check its levels**

Export the 12-bit clip and confirm the DNG carries BlackLevel (tag 50714) and WhiteLevel (tag 50717) matching the header, and that the image has no colour cast or crushed blacks. A wrong `blackLevel` scale is visually plausible and quietly wrong — this step is the guard.

- [ ] **Step 4: Run the interleaved thermal A/B**

Per the spec's protocol, and **not** as a single sequential pair:
- Order **14, 12, 14, 12**, ~60s each, phone **unplugged**, from a cool start, equal cooldown between every take.
- Record SoC/battery temperature before each take; discard any pair whose starting temperatures differ materially.
- Capture the screen at ~1 Hz to read the on-screen `frames`/`dropped` counters.
- Compare drops **within matched elapsed windows** (0-10s, 10-20s, 20-30s...), never as a whole-take average — drops are zero early and steady later, so a whole-take number mostly measures take length.

- [ ] **Step 5: Record the result honestly and commit**

Append a "Result" section to the spec with the measured per-window drop rates for both arms. If the win is small, say so plainly and note that the 16:9 capture crop is the better next lever — the spec already predicts this is possible. **Do not change the default to 12-bit in this plan** regardless of outcome; that is a separate, evidence-backed decision.

```bash
git add docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md
git commit -m "docs: record the bit-depth A/B result"
```

---

## Self-Review

**Spec coverage.** Option list and no-16 rationale → Task 5. Per-lens disabling → Tasks 6, 7. Requested-vs-clamped persistence → Tasks 5, 6. Native default → Task 5. Three-way rounding asymmetry → Task 1, enforced in Task 4, verified on-device in Task 8 Step 1. Approach A shift-on-read → Tasks 2, 3. Header scaling → Task 4. No format change → Task 4 (no enum added). `frameRecordBytes` / `captureRateKey` → Task 6. Preview needs no change (verified in spec) → correctly absent. Stale `captureRates` → expected behaviour, noted in Task 6's test comment. A/B protocol → Task 8 Step 4. 8-bit uncompressed caveat → Task 7 subtitle copy.

**Placeholders.** None: every code step carries real code; every run step carries a real command and an expected result.

**Type consistency.** `shiftForDepth(whiteLevel, requestedDepth)` (C++, Task 1) and `effectiveBitDepth(whiteLevel, requested)` (Kotlin, Task 6) are deliberately different functions — the first yields a shift, the second a depth — and both derive native depth as `32 - clz(whiteLevel)`. `reduceSample(sample, shift, newWhite)` keeps that argument order in Tasks 1, 2, 3. `encodeFrame`'s two new parameters are `(shift, newWhite)` in that order, matching `pack10`/`pack12`.

**Known gap, deliberate.** `capture.cpp` still has no host harness, but the risky
part no longer depends on one: the header arithmetic lives in core as
`applyBitDepth` with a genuine failing test (Task 4 Steps 1-4). What remains
untested off-device is only the *wiring* — which parameter reaches which call —
and that fails loudly at compile time (Task 4 Step 6) or shows up immediately in
the header bytes on-device (Task 8 Step 1).

**Revision note (2026-08-30, after review).** Eight issues fixed: Task 4's build
step could not have succeeded (`CameraController.kt:648-656` passes a fixed
positional list, so the JNI signature change breaks it — now updated in Task 4
rather than Task 6); the encoder is templated on `Reduce` so Native, the default,
stays cost-identical and not merely bit-identical, with a timing gate to prove it;
`sampleShift_`/`newWhite_` are now explicitly declared; Task 4 gained a real
failing test via `applyBitDepth`; both placeholders are now concrete (`RawSpec`
has 13 fields; `SettingsScreen.kt:85` already has the lens in scope); Task 2's
test data is a gradient plus noise rather than near-incompressible uniform noise
that could have made `encodeFrame` fail on capacity instead of behaviour; Task 8
reads the sensor's real black level instead of assuming 1024; and
`computeInteriorResidualsRow` keeps a non-template overload so
`test_rawv_simd_spike.cpp` compiles unchanged.
