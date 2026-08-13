# Round 4 (NEON) — SIMD predict+zigzag — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vectorize the MED predict + residual + zigzag in the compressed-`.rawv`
encoder's hot loop with ARM NEON (host-tested via an `ARM_NEON_2_x86_SSE` shim), as a pure
bit-exact performance change, to cut per-frame Compute time at 4096×3072@24 fps.

**Architecture:** A new bit-exact vectorized primitive `computeInteriorResidualsRow`
fills a per-band scratch row of zigzag-encoded MED residuals (4 int32 lanes at a
time); `ParallelFrameEncoder::computeAndPackBand` calls it for interior samples
(x≥2, y≥2) and feeds the results into the unchanged `writeRice`. Edge columns,
edge rows, and the vector tail stay scalar via the existing `predictAt`. The
scalar `encodeFrame` is untouched and serves as the byte-for-byte oracle the host
`ctest` asserts against. On x86 the identical intrinsic code compiles/runs under
`ARM_NEON_2_x86_SSE` (SSE4.1), so the host bit-exact assertion genuinely guards the NEON path.

**Tech Stack:** C++17, ARM NEON (`arm_neon.h`) on-device, `NEON_2_SSE.h` (single
BSD-2-Clause header, from `intel/ARM_NEON_2_x86_SSE`) on host, doctest, CMake + Ninja.
Host toolchain: MSYS2 mingw64 g++.
Android: Gradle NDK arm64 build.

**Design spec:** `docs/superpowers/specs/2026-08-13-rawv-codec-round4-neon-predict-simd-design.md`
**Context:** `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`

## Global Constraints

- **Format frozen.** The `.rawv` bitstream format is unchanged. `ParallelFrameEncoder`
  output must equal scalar `encodeFrame` output **byte-for-byte**; `encode → decode
  == input` must hold losslessly. Any single-byte diff is a failure.
- **Integer-ops-only in the vector path.** No float/reciprocal intrinsics — that is
  what keeps SSE (`ARM_NEON_2_x86_SSE`) and NEON bit-identical. Only add/sub/min/max/shift/xor
  on `int32x4_t`.
- **int32 lanes.** Widen samples uint16→int32; 4 lanes per 128-bit register. `r<<1`
  reaches ±65532 (overflows int16), so int32 is mandatory.
- **Never change the k-selection sampling pattern.** Changing which pixels pass-1
  samples changes `k`, which changes every codeword. Pass-1 may only be vectorized
  if it produces the identical `sumAbs`.
- **Oracle stays scalar.** `encodeFrame` and `decodeFrame` are not modified.
- **`main` stays clean of throwaway instrumentation** (Task 0 / Task 4 device
  timing patches are reverted and saved as `.patch` files, never committed to `main`).
- **Host build:** MSYS2 mingw64 g++ + Ninja; host vector flag `-msse4.1`
  (`ARM_NEON_2_x86_SSE` needs SSE4.1). Configure: `cmake -S core -B core/build -G Ninja`.
  Build: `cmake --build core/build`. Test: `ctest --test-dir core/build --output-on-failure`.
- **`file(GLOB)` gotcha:** `core/CMakeLists.txt` globs `tests/*.cpp`. After ADDING a
  new test file you MUST re-run the configure step (`cmake -S core -B core/build`)
  before `cmake --build`, or the new test won't be picked up.
- **PowerShell caveats (this repo):** do NOT rewrite existing files via
  Get-Content+Set-Content (adds a BOM / mojibakes non-ASCII — use Edit or a byte-safe
  tool). Avoid double quotes inside `git commit -m` here-strings on PS 5.1 (breaks the
  commit) — use single quotes or `git commit -F -`.
- **Device:** `24030PN60G` (Xiaomi 14 Ultra, Snapdragon 8 Gen 3), 4096×3072@24 fps,
  compression ON, verify `packMode@20 == 3` and 14-bit `whiteLevel = 16383` before
  trusting any measurement; measure cool + unplugged; HyperOS drops the app's own
  logcat tags → instrument to a file via raw `open(O_CREAT|O_TRUNC|O_WRONLY, 0644)`
  (unqualified `open`, not `::open`, under Bionic `_FORTIFY_SOURCE=2`).

---

## Task ordering & device-gating

- **Task 0** (on-device baseline measurement) is *device-gated*. Do it first **if the
  device is connected**; its numbers set NEON's expected ceiling and the before/after
  baseline. If the device is unavailable, proceed to Tasks 1–3 (all host-only, no
  device needed) and capture Task 0's baseline together with Task 4's device pass.
- **Tasks 1 → 2 → 3** are host-only and strictly ordered (each builds on the prior).
- **Task 4** (arm64 build + on-device verify + measure) is device-gated.
- **Task 5** is optional, entered only if Task 4's profile warrants it.

---

## Task 0: On-device baseline measurement (throwaway, device-gated)

Establishes NEON's ceiling before implementation and the before/after baseline.
Produces **no committed code** — a saved patch + numbers written into the open-items doc.

**Files:**
- Temporarily modify: `core/src/rawv_codec.cpp` (timing instrumentation)
- Save: `.superpowers/sdd/2026-08-13-rawv-codec-round4-neon-predict-simd/predict-vs-pack-instrumentation.patch`
- Update: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`

- [ ] **Step 1: Add timing instrumentation to `computeAndPackBand` and `computeBands`**

In `computeAndPackBand`, wrap the predict step and the `writeRice` pack step with
separate `std::chrono::steady_clock` accumulators (per-band, summed under `mu_`).
In `computeBands`, time the pass-1 k-scan, the dispatch+wait span, and (in
`mergeSlot`) the merge. Every ~50 frames, append one line to a file opened once via:

```cpp
// HyperOS drops the app's own logcat tags -- write to a file instead.
// Plain fopen("a") fails on this device's FUSE mount; raw open() with the
// clip-writer flags works. Unqualified open (not ::open) under _FORTIFY_SOURCE=2.
int fd = open("/data/data/com.shez.rawcam/files/clips/rawv_prof.log",
              O_CREAT | O_TRUNC | O_WRONLY, 0644);
```

Log: `predict_ms`, `pack_ms`, `pass1_ms`, `dispatchwait_ms`, `merge_ms` cumulative
averages, plus a one-time `ctor threadCount=N` line.

- [ ] **Step 2: Build the instrumented arm64 release APK**

Run the project's Android release build (`./gradlew :app:assembleRelease` or the
repo's documented command). Confirm it builds clean.

- [ ] **Step 3: Run on a cool, unplugged device**

`adb install -r` the APK. In Settings, confirm **"Compress recordings" is ON**.
Record ~35 s at 4096×3072@24 fps. Pull `rawv_prof.log` via adb. On the recorded
clip, verify `packMode@20 == 3` and `whiteLevel == 16383` before trusting numbers.

- [ ] **Step 4: Record the split into the open-items doc**

Add a dated subsection capturing predict:pack:pass1:dispatchwait:merge (ms). This is
NEON's ceiling (predict fraction of band-pack) and the pre-NEON baseline. Note device
temperature/charge state.

- [ ] **Step 5: Revert instrumentation, save the patch, leave `main` clean**

```bash
git diff core/src/rawv_codec.cpp > .superpowers/sdd/2026-08-13-rawv-codec-round4-neon-predict-simd/predict-vs-pack-instrumentation.patch
git checkout core/src/rawv_codec.cpp
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md .superpowers/sdd/2026-08-13-rawv-codec-round4-neon-predict-simd/predict-vs-pack-instrumentation.patch
git commit -m 'docs: round 4 NEON step 0 -- on-device predict-vs-pack baseline'
```

(End with the repo's `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.)

---

## Task 1: Vendor ARM_NEON_2_x86_SSE + CMake wiring + host build go/no-go

Proves the `arm_neon.h`-style code compiles and links under mingw g++ via
`ARM_NEON_2_x86_SSE` (`intel/ARM_NEON_2_x86_SSE`'s `NEON_2_SSE.h`, which implements the
`arm_neon.h` intrinsic surface in terms of x86 SSE — the NEON→SSE direction this
round needs). This is the Option-B go/no-go: if it can't build clean here, STOP
and escalate with the exact compiler/linker error (accept a documented
device-only-verification gap) rather than silently dropping to a hand-NEON +
scalar-host split. Note: `sse2neon` is the *opposite*-direction library (SSE→NEON,
for x86 source on ARM) and does NOT work here; `clang-cl` cannot help either
(`arm_neon.h` does not exist for x86 in any compiler).

**Files:**
- Create: `core/third_party/neon2sse/NEON_2_SSE.h` (vendored single BSD-2-Clause header)
- Modify: `core/CMakeLists.txt`
- Modify: `core/src/rawv_codec.cpp:1-16` (include guard block)
- Create: `core/tests/test_rawv_simd_spike.cpp`

**Interfaces:**
- Produces: the macro `RAWV_HAVE_NEON` (defined when either `arm_neon.h` or
  `NEON_2_SSE.h` is in scope) and a compile-time SIMD availability guarantee on host.

- [ ] **Step 1: Vendor NEON_2_SSE.h (byte-safe download, no PowerShell rewrite)**

Fetch the single-header release into `core/third_party/neon2sse/NEON_2_SSE.h`. Use a
byte-preserving download (do NOT pipe through Set-Content):

```bash
mkdir -p core/third_party/neon2sse
curl -L -o core/third_party/neon2sse/NEON_2_SSE.h \
  https://raw.githubusercontent.com/intel/ARM_NEON_2_x86_SSE/master/NEON_2_SSE.h
```

Verify it's the real header (contains `_NEON2SSE_INLINE`) and is not HTML/error text.

- [ ] **Step 2: Add the include guard block to `rawv_codec.cpp`**

Just after the existing top-of-file `#include` lines (the existing `#ifdef __ANDROID__`
sched/pthread block stays), ADD:

```cpp
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
  #include <arm_neon.h>
  #define RAWV_HAVE_NEON 1
#elif defined(RAWV_USE_NEON2SSE)
  #include "NEON_2_SSE.h"
  #define RAWV_HAVE_NEON 1
#endif
```

- [ ] **Step 3: Wire CMake — host-only ARM_NEON_2_x86_SSE include + define + flag**

In `core/CMakeLists.txt`, after `target_include_directories(rawcam_core PUBLIC include)`:

```cmake
# Host (non-Android) builds get the ARM_NEON_2_x86_SSE shim so the SIMD path in
# rawv_codec.cpp compiles and runs (as SSE4.1) under ctest -- the byte-for-byte
# bit-exact assertion then genuinely guards the on-device NEON path. On Android,
# arm_neon.h is used directly (auto-detected via __ARM_NEON); no shim, no flag.
if(NOT ANDROID)
  target_include_directories(rawcam_core PRIVATE third_party/neon2sse)
  target_compile_definitions(rawcam_core PRIVATE RAWV_USE_NEON2SSE)
  target_compile_options(rawcam_core PRIVATE -msse4.1)
endif()
```

`-msse4.1` covers every op this round uses (min/max epi32, cvtepu16, arithmetic
shift). If `NEON_2_SSE.h` itself fails to *compile* under `-msse4.1` on this
toolchain (some releases reference SSE4.2 intrinsics in inline code), bump this
one flag to `-msse4.2` and note it in the report — it only enables more
instructions and does not change any integer result, so bit-exactness is
unaffected. Do not add any other flags.

- [ ] **Step 4: Write the go/no-go spike test**

`core/tests/test_rawv_simd_spike.cpp` — a self-contained doctest that includes the
same guard and checks the ops we rely on are bit-exact on this toolchain:

```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include <cstdint>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
  #include <arm_neon.h>
  #define RAWV_HAVE_NEON 1
#elif defined(RAWV_USE_NEON2SSE)
  #include "NEON_2_SSE.h"
  #define RAWV_HAVE_NEON 1
#endif

TEST_CASE("ARM_NEON_2_x86_SSE/NEON integer ops are bit-exact for our predictor kernel") {
#if RAWV_HAVE_NEON
  // left/up/upleft/actual chosen so clamp engages both bounds and one lane
  // yields a negative residual (exercises the arithmetic shift in zigzag).
  int32_t L[4] = {100, 5000, 16383, 0};
  int32_t U[4] = {120, 4000, 16000, 4};
  int32_t UL[4] = {110, 4500, 15000, 2};
  int32_t A[4] = {130, 3000, 16383, 1};
  int32x4_t l = vld1q_s32(L), u = vld1q_s32(U), ul = vld1q_s32(UL), a = vld1q_s32(A);
  int32x4_t linear = vsubq_s32(vaddq_s32(l, u), ul);
  int32x4_t pred = vmaxq_s32(vminq_s32(l, u), vminq_s32(linear, vmaxq_s32(l, u)));
  int32x4_t r = vsubq_s32(a, pred);
  int32x4_t z = veorq_s32(vshlq_n_s32(r, 1), vshrq_n_s32(r, 31));
  uint32_t got[4];
  vst1q_u32(got, vreinterpretq_u32_s32(z));
  for (int i = 0; i < 4; i++) {
    int32_t lo = L[i] < U[i] ? L[i] : U[i];
    int32_t hi = L[i] < U[i] ? U[i] : L[i];
    int32_t lin = L[i] + U[i] - UL[i];
    int32_t p = lin < lo ? lo : (lin > hi ? hi : lin);
    int32_t rr = A[i] - p;
    uint32_t want = (static_cast<uint32_t>(rr) << 1) ^ static_cast<uint32_t>(rr >> 31);
    CHECK(got[i] == want);
  }
#else
  MESSAGE("RAWV_HAVE_NEON not defined -- SIMD path unavailable on this build");
  CHECK(false);  // on host this MUST be defined; failing here is the go/no-go signal
#endif
}
```

- [ ] **Step 5: Re-configure (new test file), build, run**

Run: `cmake -S core -B core/build -G Ninja && cmake --build core/build && ctest --test-dir core/build -R test_rawv_simd_spike --output-on-failure`
Expected: PASS. If it fails to COMPILE/LINK, this is the escalation point (see task intro).

- [ ] **Step 6: Confirm the whole suite still builds & passes**

Run: `ctest --test-dir core/build --output-on-failure`
Expected: all existing tests still PASS (the guard block and CMake change must not
have altered scalar behavior).

- [ ] **Step 7: Commit**

```bash
git add core/third_party/neon2sse/NEON_2_SSE.h core/CMakeLists.txt core/src/rawv_codec.cpp core/tests/test_rawv_simd_spike.cpp
git commit -m 'build: vendor ARM_NEON_2_x86_SSE + host SIMD wiring, bit-exact op spike (round 4 NEON)'
```

---

## Task 2: Vectorized `computeInteriorResidualsRow` primitive (host, TDD)

The bit-exact SIMD kernel, tested directly against the scalar predictor before it's
wired into the encoder.

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h` (declare the free function)
- Modify: `core/src/rawv_codec.cpp` (implement, anonymous-namespace helper + exported fn)
- Modify: `core/tests/test_rawv_codec.cpp` (unit tests)

**Interfaces:**
- Produces:
  ```cpp
  // Fills zOut[x] for x in [xStart, xEnd) (requires xStart >= 2 and y >= 2, so
  // every sample has same-color left/up/upleft neighbors) with the zigzag-encoded
  // MED/LOCO-I residual for row y of `plane` (rowStrideSamples samples per row).
  // Bit-identical to zigzagEncode(plane[y*s+x] - medPredict(plane[y*s+x-2],
  // plane[(y-2)*s+x], plane[(y-2)*s+x-2])). NEON when RAWV_HAVE_NEON, scalar
  // otherwise; both produce identical bytes. Exposed so host tests can assert the
  // vectorized path against the scalar predictor directly.
  void computeInteriorResidualsRow(const uint16_t* plane, uint32_t y,
                                   uint32_t rowStrideSamples, uint32_t xStart,
                                   uint32_t xEnd, uint32_t* zOut);
  ```
- Consumes: existing anonymous-namespace `medPredict`, `zigzagEncode` (for the scalar
  tail/fallback), `predictAt` (test oracle reference re-implemented in the test).

- [ ] **Step 1: Write the failing unit test**

Add to `core/tests/test_rawv_codec.cpp`. Because `predictAt`/`zigzagEncode`/`medPredict`
live in the .cpp's anonymous namespace (not visible to the test), define local
reference helpers at file scope and assert against them:

```cpp
static int32_t medRef(int32_t l, int32_t u, int32_t ul) {
  int32_t lin = l + u - ul, lo = std::min(l, u), hi = std::max(l, u);
  return std::max(lo, std::min(lin, hi));
}
static uint32_t zzRef(int32_t v) {
  return (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31);
}

TEST_CASE("computeInteriorResidualsRow matches scalar predictor+zigzag (vectorized path)") {
  // Widths chosen to exercise the 4-lane body AND a scalar tail of every size
  // 0..3: interior length = width-2, so widths 6,7,8,9 give tails 0,1,2,3.
  const uint32_t widths[] = {6, 7, 8, 9, 33, 64, 100};
  for (uint32_t width : widths) {
    const uint32_t height = 8, bitDepth = 14;
    std::srand(9876 + width);
    auto src = makeFrame(width, height, bitDepth, [](uint32_t, uint32_t, uint16_t maxVal) {
      return static_cast<uint16_t>(std::rand() % (maxVal + 1));
    });
    for (uint32_t y = 2; y < height; y++) {  // interior rows only (y >= 2)
      std::vector<uint32_t> got(width, 0xDEADBEEFu);
      computeInteriorResidualsRow(src.data(), y, width, 2, width, got.data());
      for (uint32_t x = 2; x < width; x++) {
        int32_t p = medRef(src[y * width + x - 2], src[(y - 2) * width + x], src[(y - 2) * width + x - 2]);
        uint32_t want = zzRef(static_cast<int32_t>(src[y * width + x]) - p);
        CHECK(got[x] == want);
      }
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmake --build core/build && core/build/test_rawv_codec.exe -tc="computeInteriorResidualsRow*"`
Expected: FAIL — `computeInteriorResidualsRow` undefined (link error).

- [ ] **Step 3: Declare the function in the header**

Add the declaration block (from Interfaces) to `core/include/rawcam/rawv_codec.h`,
after the `encodeFrame`/`decodeFrame` declarations.

- [ ] **Step 4: Implement the primitive in `rawv_codec.cpp`**

Add the load helper in the anonymous namespace:

```cpp
#if RAWV_HAVE_NEON
// Loads 4 consecutive uint16 samples and zero-extends to int32x4. Valid on both
// arm_neon.h and ARM_NEON_2_x86_SSE (integer-only, bit-exact on both).
static inline int32x4_t loadU16x4AsS32(const uint16_t* p) {
  return vreinterpretq_s32_u32(vmovl_u16(vld1_u16(p)));
}
#endif
```

and the exported function at `namespace rawcam` scope:

```cpp
void computeInteriorResidualsRow(const uint16_t* plane, uint32_t y,
                                 uint32_t rowStrideSamples, uint32_t xStart,
                                 uint32_t xEnd, uint32_t* zOut) {
  const uint16_t* cur = plane + static_cast<size_t>(y) * rowStrideSamples;
  const uint16_t* up = plane + static_cast<size_t>(y - 2) * rowStrideSamples;
  uint32_t x = xStart;
#if RAWV_HAVE_NEON
  for (; x + 4 <= xEnd; x += 4) {
    int32x4_t a = loadU16x4AsS32(cur + x);
    int32x4_t l = loadU16x4AsS32(cur + x - 2);
    int32x4_t u = loadU16x4AsS32(up + x);
    int32x4_t ul = loadU16x4AsS32(up + x - 2);
    int32x4_t linear = vsubq_s32(vaddq_s32(l, u), ul);
    int32x4_t pred = vmaxq_s32(vminq_s32(l, u), vminq_s32(linear, vmaxq_s32(l, u)));
    int32x4_t r = vsubq_s32(a, pred);
    int32x4_t z = veorq_s32(vshlq_n_s32(r, 1), vshrq_n_s32(r, 31));
    vst1q_u32(zOut + x, vreinterpretq_u32_s32(z));
  }
#endif
  for (; x < xEnd; x++) {
    zOut[x] = zigzagEncode(static_cast<int32_t>(cur[x]) -
                           medPredict(cur[x - 2], up[x], up[x - 2]));
  }
}
```

`vmaxq_s32(vminq_s32(l,u), vminq_s32(linear, vmaxq_s32(l,u)))` is exactly
`std::clamp(linear, min(l,u), max(l,u))` = `medPredict`. `vshlq_n_s32(r,1)` shifts the
raw bits (matches scalar `uint32_t(r)<<1`); `vshrq_n_s32(r,31)` is an arithmetic shift
(matches scalar `int32_t r >> 31` sign fill). All integer ops → SSE-exact under ARM_NEON_2_x86_SSE.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cmake --build core/build && core/build/test_rawv_codec.exe -tc="computeInteriorResidualsRow*"`
Expected: PASS for all widths (4-lane body + tails 0/1/2/3).

- [ ] **Step 6: Full suite green**

Run: `ctest --test-dir core/build --output-on-failure`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m 'feat: bit-exact NEON computeInteriorResidualsRow primitive (round 4)'
```

---

## Task 3: Wire the primitive into `computeAndPackBand` + full bit-exact matrix (host, TDD)

Replace the fused per-sample predict in the band packer with: fill a per-band scratch
row via the primitive (interior) + scalar edges, then drain it through the unchanged
`writeRice`. Add the expanded correctness matrix.

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h` (add `zScratch_` member)
- Modify: `core/src/rawv_codec.cpp` (constructor init + `computeAndPackBand` rewrite)
- Modify: `core/tests/test_rawv_codec.cpp` (expanded matrix test)

**Interfaces:**
- Consumes: `computeInteriorResidualsRow` (Task 2), existing `predictAt`,
  `zigzagEncode`, `BitWriter`, `writeRice`.
- Produces: no new public API; `ParallelFrameEncoder::encode` output unchanged
  (byte-for-byte) but now computed via the SIMD path on interior samples.

- [ ] **Step 1: Write the failing expanded-matrix test**

Add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("ParallelFrameEncoder SIMD path is byte-identical to encodeFrame across the full matrix") {
  struct Case { uint32_t width, height, bitDepth; };
  // Includes tiny widths (1-9: interior empty or a pure tail) and non-lane-multiple
  // widths, heights that exercise y<2 rows and multi-band splits.
  const Case cases[] = {
    {1, 8, 14}, {2, 8, 14}, {3, 8, 12}, {5, 7, 10}, {8, 8, 16}, {9, 9, 14},
    {33, 40, 12}, {64, 64, 16}, {100, 130, 14}, {257, 64, 10},
  };
  const uint32_t threadCounts[] = {1, 2, 5};
  using Gen = uint16_t (*)(uint32_t, uint32_t, uint16_t);
  const Gen gens[] = {
    [](uint32_t, uint32_t, uint16_t) -> uint16_t { return 0; },                        // all-min
    [](uint32_t, uint32_t, uint16_t m) -> uint16_t { return m; },                      // all-max/saturation
    [](uint32_t x, uint32_t y, uint16_t m) -> uint16_t { return static_cast<uint16_t>((x + y) % (m + 1)); }, // diag gradient
    [](uint32_t x, uint32_t y, uint16_t m) -> uint16_t { return ((x ^ y) & 1) ? m : static_cast<uint16_t>(0); }, // salt-and-pepper
  };
  for (const auto& c : cases) {
    for (Gen gen : gens) {
      auto src = makeFrame(c.width, c.height, c.bitDepth, gen);
      std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
      uint32_t sn = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                                serial.data(), static_cast<uint32_t>(serial.size()));
      REQUIRE(sn > 0);
      for (uint32_t tc : threadCounts) {
        ParallelFrameEncoder enc(c.width, c.height, tc);
        std::vector<uint8_t> par(static_cast<size_t>(c.width) * c.height * 2 + 64);
        uint32_t pn = enc.encode(src.data(), c.width, c.bitDepth, par.data(),
                                 static_cast<uint32_t>(par.size()));
        REQUIRE(pn == sn);
        CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
        std::vector<uint16_t> dec(src.size());
        REQUIRE(decodeFrame(par.data(), pn, dec.data(), c.width, c.height, c.width, c.bitDepth));
        CHECK(dec == src);
      }
    }
  }
}
```

- [ ] **Step 2: Run to verify it passes with the CURRENT scalar band packer**

Run: `cmake --build core/build && core/build/test_rawv_codec.exe -tc="*full matrix*"`
Expected: PASS (the matrix must hold against the existing scalar `computeAndPackBand`
too — this pins the oracle equivalence BEFORE the rewrite, so a later failure is
unambiguously the SIMD change). If any case fails now, fix the test, not the encoder.

- [ ] **Step 3: Add the `zScratch_` member to the header**

In `core/include/rawcam/rawv_codec.h`, in the private members near `bandBufs_`:

```cpp
  // Per-band scratch holding one row's zigzag-encoded residuals: filled by the
  // (NEON) predict+zigzag pass in computeAndPackBand, then drained into the Rice
  // packer. One vector per band index (each worker only ever touches its own
  // band, and generations never overlap on a band), width_ entries each. Round 4.
  std::vector<std::vector<uint32_t>> zScratch_;
```

- [ ] **Step 4: Initialize `zScratch_` in the constructor**

In `ParallelFrameEncoder::ParallelFrameEncoder`, alongside the `bandBufs_` sizing loop
(after `threadCount_` is finalized):

```cpp
  zScratch_.resize(threadCount_);
  for (auto& v : zScratch_) v.resize(width_);
```

- [ ] **Step 5: Rewrite `computeAndPackBand`'s inner loop**

Replace the double `for` loop body (`rawv_codec.cpp:432-439`) with fill-then-pack
(keep the surrounding `BitWriter bw(...)` and the `bits`/`finishedBytes()`/lock/
`jobOverflowed_` epilogue exactly as-is):

```cpp
  uint32_t* z = zScratch_[bandIndex].data();
  for (uint32_t y = bandStart; y < bandEnd && ok; y++) {
    const uint16_t* row = jobRaw16_ + static_cast<size_t>(y) * jobRowStrideSamples_;
    // Fill z[0..width_) for this row.
    uint32_t edgeCols = std::min(2u, width_);  // x = 0,1 have no same-color left
    if (y < 2) {
      // No same-color row above -> whole row uses the scalar edge predictor.
      for (uint32_t x = 0; x < width_; x++)
        z[x] = zigzagEncode(static_cast<int32_t>(row[x]) -
                            predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_));
    } else {
      for (uint32_t x = 0; x < edgeCols; x++)
        z[x] = zigzagEncode(static_cast<int32_t>(row[x]) -
                            predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_));
      // Interior x >= 2, y >= 2: vectorized (scalar fallback inside if no NEON).
      computeInteriorResidualsRow(jobRaw16_, y, jobRowStrideSamples_, 2, width_, z);
    }
    // Pack the row (unchanged Rice/BitWriter path -> bit-exact by construction).
    for (uint32_t x = 0; x < width_; x++) {
      if (!bw.writeRice(z[x], jobK_)) { ok = false; break; }
    }
  }
```

`computeInteriorResidualsRow` writes only indices `[2, width_)`, and `edgeCols` fills
`[0, min(2,width_))` — together they cover every x for any width including 1 and 2.

- [ ] **Step 6: Run the matrix + full suite**

Run: `cmake --build core/build && ctest --test-dir core/build --output-on-failure`
Expected: ALL PASS — the new matrix, every pre-existing `encodeFrame`-equality test
(these now run the SIMD path via ARM_NEON_2_x86_SSE and are the primary NEON guard), and all
round-trip/overflow/backpressure tests.

- [ ] **Step 7: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m 'feat: NEON predict+zigzag in computeAndPackBand (bit-exact, round 4)'
```

---

## Task 4: arm64 build + on-device round-trip + measurement (device-gated)

Confirm bit-exactness on real hardware and measure the actual compute win vs Task 0's
baseline.

**Files:**
- Temporarily re-apply: Task 0's instrumentation patch (for the after-measurement)
- Update: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
- Update: `C:\Users\User\.claude\projects\C--Users-User\memory\rawcam-project.md` (+ MEMORY.md line)

- [ ] **Step 1: Build arm64 debug + release**

Run the Android build (`./gradlew :app:assembleDebug :app:assembleRelease` or the repo
command). Confirm both build clean — the `#if defined(__ARM_NEON)` branch compiles
`arm_neon.h` intrinsics with no ARM_NEON_2_x86_SSE involved.

- [ ] **Step 2: On-device lossless round-trip check**

Install the release APK on the `24030PN60G`. Confirm "Compress recordings" ON. Record
a short clip. Verify `packMode@20 == 3`, `whiteLevel == 16383`. Pull the clip and
decode it (host `decodeFrame` on the pulled `.rawv`, or the in-app export path) and
confirm it decodes without error and matches expected content — the on-hardware
confirmation of the host bit-exact result.

- [ ] **Step 3: Re-apply Task 0 instrumentation, measure the NEON build**

`git apply .superpowers/sdd/2026-08-13-rawv-codec-round4-neon-predict-simd/predict-vs-pack-instrumentation.patch`,
rebuild release, run on the **cool, unplugged** device, pull `rawv_prof.log`. Capture
predict:pack:pass1:dispatchwait:merge and the landing rate.

- [ ] **Step 4: Record before/after and revert instrumentation**

Write the comparison (Task 0 baseline vs NEON) into the open-items doc: the predict-ms
drop, the band-pack total, whether dispatch+wait now fits the 41.6 ms budget, and the
landing rate. State plainly whether the 0-dropped bar is met (measured, not gated).
Then `git checkout core/src/rawv_codec.cpp` to keep `main` clean; restore the shipped
non-instrumented APK to the device.

- [ ] **Step 5: Update memory + commit docs**

Update `rawcam-project.md` (round 4 NEON shipped/measured; the round-5 12-bit decision
now has its number) and the `MEMORY.md` pointer line. Commit the doc updates:

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
git commit -m 'docs: round 4 NEON on-device result -- predict-ms drop, landing rate'
```

(Memory files live outside the repo — write them with the Write tool, not git.)

---

## Task 5 (OPTIONAL, measured-in): pass-1 SIMD and/or Approach-1 chunked interleave

Enter ONLY if Task 4's profile shows a remaining lever worth it:
- If **pass-1 k-scan** is a meaningful serial fraction → vectorize its predict+abs,
  asserting the produced `sumAbs` is **identical** (bit-exact k, host test) to the
  current strided scalar scan. Do NOT change the sampling pattern.
- If the **per-row `zScratch_` traffic** shows up (bandwidth) → replace Approach 2 with
  Approach 1 (compute 4–8 residuals into a stack array, pack them, repeat), which feeds
  the identical `z` stream to `writeRice` and stays bit-exact. Re-run the full Task 3
  matrix + device round-trip.

Skip and close the round if Task 4 already meets the goal or shows no further headroom.

---

## Self-Review

**Spec coverage:**
- §1 scope (vectorize band-pack predict+zigzag) → Tasks 2–3. Pass-1 (optional) → Task 5. ✓
- §2 measure-first (predict-vs-pack, pass1/band-pack/merge split) → Task 0 + Task 4. ✓
- §3 architecture (primitive + unchanged writeRice + scalar oracle) → Tasks 2–3. ✓
- §4 exact math (int32, clamp=med, zigzag shifts) → Task 2 Step 4 code. ✓
- §5 edges (y<2 rows, x<2 cols, tail) → Task 3 Step 5 (`edgeCols`, y<2 branch) + Task 2
  tail loop; tiny widths in Task 2 & 3 matrices. ✓
- §5/§6 ARM_NEON_2_x86_SSE host testability → Task 1 (wiring + spike), all equality tests. ✓
- §6 test matrix (content/bitDepth/dims/threadCount + round-trip) → Task 3 Step 1. ✓
- §7 on-device protocol (file logging, packMode/whiteLevel checks, cool device) →
  Tasks 0 & 4. ✓
- §8 acceptance (bit-exact gate, measured goal, 0-dropped reported-not-gated) → Task 3
  (gate), Task 4 (measure/report). ✓
- §9 sequencing → Tasks 0–5 map 1:1. ✓

**Placeholder scan:** No TBD/TODO; every code step has concrete code. Device build
commands are given as "the repo's documented Gradle command" because the exact Gradle
task name isn't in scope here — the implementer runs the same command prior rounds used
(`assembleRelease`); acceptable since it's an existing, unchanged build path.

**Type consistency:** `computeInteriorResidualsRow(const uint16_t*, uint32_t y,
uint32_t rowStrideSamples, uint32_t xStart, uint32_t xEnd, uint32_t* zOut)` — identical
signature in the header decl (Task 2 Interfaces/Step 3), the implementation (Step 4),
and the call site (Task 3 Step 5). `zScratch_` type `std::vector<std::vector<uint32_t>>`
consistent between header (Task 3 Step 3) and constructor init (Step 4). `RAWV_HAVE_NEON`
/ `RAWV_USE_NEON2SSE` macro names consistent across Tasks 1–3.
