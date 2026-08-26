# Clip Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every recorded clip a poster thumbnail and a viewer that flips through pre-rendered proxy frames, so a take can be identified without exporting it to DNG.

**Architecture:** A new native develop pipeline in `core/` turns a Bayer frame into RGBA8 (unpack, black level, as-shot neutral, 2x2 CFA bin, gamma, box downscale). A background service decodes roughly every fifth frame of a finished clip through that pipeline and writes each as a JPEG under `cacheDir`. The viewer only loads JPEGs -- nothing is developed while the user is looking at it.

**Tech Stack:** C++17 in `core/` (doctest + ctest on host), JNI via `app/src/main/cpp/jni_bridge.cpp`, Kotlin + Jetpack Compose, Android `Service` modelled on the existing `ExportService`.

**Spec:** `docs/superpowers/specs/2026-08-26-clip-preview-design.md`

## Global Constraints

- Android `minSdk 33`, `targetSdk 35`, Kotlin `jvmTarget 17`, ABI `arm64-v8a` only, NDK `27.0.12077973`, CMake `3.22.1`.
- `core/` tests use **doctest**, one `TEST_CASE` per behaviour, `CHECK` for assertions. Every file in `core/tests/` is auto-registered as a ctest by the existing `foreach` in `core/CMakeLists.txt`; new library sources must be added to the `add_library(rawcam_core STATIC ...)` list at line 6.
- Host tests run from `core/build`: `cmake --build . && ctest --output-on-failure`.
- Proxy sampling: `stride = max(5, ceil(frameCount / 1200))`, indices `0, stride, 2*stride, ...` while `index < frameCount`. **The cap raises the stride; it never truncates the range.**
- Proxy image: fits within **1024x768**, JPEG **quality 80**.
- Proxy store: `cacheDir/proxies/<clipName>/%06d.jpg` plus `index.json` = `{stride, sourceFrames, proxyCount, complete}`.
- Generation **must not run while `recording || busy`**. The capture path's landing rate is the project's most defended property.
- A `CompressedPredictive` clip can contain frames with `meta.compressed == 0` (the compressor's stored fallback). Those are plain RAW16 and must **not** be fed to the Rice decoder.

---

## File Structure

| File | Responsibility |
|---|---|
| `core/include/rawcam/preview.h` | `PreviewImage`, `developRaw16`, `downscaleTo`, `developFrame` declarations |
| `core/src/preview.cpp` | The develop pipeline. No I/O beyond what `RawvReader` gives it. |
| `core/tests/test_preview.cpp` | Host tests: pixel math, CFA orders, pack-mode behaviour, downscale geometry |
| `app/src/main/cpp/jni_bridge.cpp` | Four new externs wrapping a `RawvReader` handle |
| `app/src/main/java/com/shez/rawcam/NativeBridge.kt` | Their Kotlin declarations |
| `app/src/main/java/com/shez/rawcam/preview/ProxyStore.kt` | Store layout, stride math, `index.json`, resume, cleanup. Pure logic, unit-testable. |
| `app/src/test/java/com/shez/rawcam/preview/ProxyStoreTest.kt` | JUnit tests for the stride/sampling contract |
| `app/src/main/java/com/shez/rawcam/preview/PreviewService.kt` | Background generation, notification |
| `app/src/main/java/com/shez/rawcam/ui/ClipViewerScreen.kt` | Playback + scrub + pending state |
| `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt` | Thumbnail column, pending chip, tap target |
| `app/src/main/java/com/shez/rawcam/MainActivity.kt` | `Screen.ClipViewer` route |
| `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` | Enqueue generation on stop |

`ProxyStore` is deliberately separate from `PreviewService`: the store is pure logic that unit tests can drive on the JVM, the service is Android lifecycle glue that they cannot.

---

### Task 1: Develop a RAW16 Bayer plane into binned RGBA

**Files:**
- Create: `core/include/rawcam/preview.h`
- Create: `core/src/preview.cpp`
- Create: `core/tests/test_preview.cpp`
- Modify: `core/CMakeLists.txt:6` (add `src/preview.cpp` to the `rawcam_core` source list)

**Interfaces:**
- Consumes: `rawcam::FileHeader`, `rawcam::Cfa` from `rawcam/rawv.h`
- Produces:
  ```cpp
  struct PreviewImage { std::vector<uint8_t> rgba; uint32_t width = 0; uint32_t height = 0; };
  bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                    uint32_t rowStrideSamples, Cfa cfa,
                    const uint32_t blackLevel[4], uint32_t whiteLevel,
                    const float asShotNeutral[3], PreviewImage* out);
  ```
  Output is `width/2 x height/2`, RGBA8, alpha always 255.

- [ ] **Step 1: Write the failing test**

Create `core/tests/test_preview.cpp`:

```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/preview.h"
#include <vector>

using namespace rawcam;

// One RGGB quad, black level 0, white level 1023, neutral gains.
// R=1023, both G=0, B=0 must develop to pure red at full scale.
TEST_CASE("developRaw16 bins one RGGB quad into one RGB pixel") {
  std::vector<uint16_t> raw = {1023, 0,
                               0,    0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.width == 1);
  CHECK(out.height == 1);
  CHECK(out.rgba.size() == 4);
  CHECK(out.rgba[0] == 255);  // R at full scale
  CHECK(out.rgba[1] == 0);    // G
  CHECK(out.rgba[2] == 0);    // B
  CHECK(out.rgba[3] == 255);  // A
}

TEST_CASE("developRaw16 honours CFA order") {
  // The same buffer read as BGGR must put the full-scale sample in blue.
  std::vector<uint16_t> raw = {1023, 0,
                               0,    0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::BGGR, black, 1023, neutral, &out));
  CHECK(out.rgba[0] == 0);
  CHECK(out.rgba[2] == 255);
}

TEST_CASE("developRaw16 averages the two greens") {
  std::vector<uint16_t> oneGreen = {0, 1023,
                                    0, 0};
  std::vector<uint16_t> twoGreens = {0,    1023,
                                     1023, 0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage a, b;
  developRaw16(oneGreen.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &a);
  developRaw16(twoGreens.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &b);
  CHECK(b.rgba[1] > a.rgba[1]);  // both greens lit beats one
}

TEST_CASE("developRaw16 subtracts black level and clamps at zero") {
  // A sample below its quadrant's black level must clamp to 0, not wrap.
  std::vector<uint16_t> raw = {10, 0,
                               0,  0};
  uint32_t black[4] = {64, 64, 64, 64};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.rgba[0] == 0);
}

TEST_CASE("developRaw16 applies as-shot neutral gains") {
  std::vector<uint16_t> raw = {512, 512,
                               512, 512};
  uint32_t black[4] = {0, 0, 0, 0};
  float flat[3] = {1.0f, 1.0f, 1.0f};
  float redUp[3] = {2.0f, 1.0f, 1.0f};
  PreviewImage a, b;
  developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, flat, &a);
  developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, redUp, &b);
  CHECK(b.rgba[0] > a.rgba[0]);
}

TEST_CASE("developRaw16 respects a row stride wider than the active width") {
  // 2 active columns but 4 samples per row on disk: padding must be skipped.
  std::vector<uint16_t> raw = {1023, 0, 999, 999,
                               0,    0, 999, 999};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 4, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.width == 1);
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[2] == 0);
}

TEST_CASE("developRaw16 rejects a zero white level") {
  std::vector<uint16_t> raw = {0, 0, 0, 0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 0, neutral, &out) == false);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd core/build && cmake --build . 2>&1 | tail -5`
Expected: FAIL to compile — `rawcam/preview.h` does not exist.

- [ ] **Step 3: Write the header**

Create `core/include/rawcam/preview.h`:

```cpp
#pragma once
#include <cstdint>
#include <vector>
#include "rawcam/rawv.h"

namespace rawcam {

// A developed preview frame: RGBA8, tightly packed, alpha always 255.
struct PreviewImage {
  std::vector<uint8_t> rgba;
  uint32_t width = 0;
  uint32_t height = 0;
};

// Develops an unpacked RAW16 Bayer plane into `out` at HALF dimensions, one RGB
// pixel per 2x2 CFA quad. `rowStrideSamples` is the plane's row pitch in uint16
// samples, which can exceed `width` -- the sensor delivers padded rows.
// Returns false if whiteLevel is 0 or the dimensions are unusable.
bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, Cfa cfa,
                  const uint32_t blackLevel[4], uint32_t whiteLevel,
                  const float asShotNeutral[3], PreviewImage* out);

}  // namespace rawcam
```

- [ ] **Step 4: Write the implementation**

Create `core/src/preview.cpp`:

```cpp
#include "rawcam/preview.h"
#include <algorithm>
#include <cmath>

namespace rawcam {
namespace {

// Which colour each position of a 2x2 quad carries, as indices into RGB.
// Order within a quad: top-left, top-right, bottom-left, bottom-right.
struct QuadLayout { int tl, tr, bl, br; };

QuadLayout layoutFor(Cfa cfa) {
  switch (cfa) {
    case Cfa::RGGB: return {0, 1, 1, 2};
    case Cfa::GRBG: return {1, 0, 2, 1};
    case Cfa::GBRG: return {1, 2, 0, 1};
    case Cfa::BGGR: return {2, 1, 1, 0};
  }
  return {0, 1, 1, 2};
}

// sRGB transfer function on a 0..1 linear value.
inline float srgbGamma(float v) {
  v = std::clamp(v, 0.0f, 1.0f);
  return v <= 0.0031308f ? v * 12.92f : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}

// Black-subtract and normalise one sample against its quadrant's black level.
inline float normalise(uint16_t sample, uint32_t black, float range) {
  const float v = (float)sample - (float)black;
  return v <= 0.0f ? 0.0f : v / range;
}

}  // namespace

bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, Cfa cfa,
                  const uint32_t blackLevel[4], uint32_t whiteLevel,
                  const float asShotNeutral[3], PreviewImage* out) {
  if (!raw16 || !out || whiteLevel == 0 || width < 2 || height < 2) return false;
  if (rowStrideSamples < width) return false;

  const uint32_t ow = width / 2, oh = height / 2;
  const QuadLayout q = layoutFor(cfa);
  out->width = ow;
  out->height = oh;
  out->rgba.assign((size_t)ow * oh * 4, 255);

  // blackLevel is indexed by position within the quad, in sensor order, which
  // is the same order as QuadLayout's fields.
  const float range[4] = {
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[0]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[1]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[2]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[3]),
  };

  for (uint32_t y = 0; y < oh; y++) {
    const uint16_t* r0 = raw16 + (size_t)(y * 2) * rowStrideSamples;
    const uint16_t* r1 = r0 + rowStrideSamples;
    uint8_t* dst = out->rgba.data() + (size_t)y * ow * 4;
    for (uint32_t x = 0; x < ow; x++) {
      const uint32_t sx = x * 2;
      float acc[3] = {0.0f, 0.0f, 0.0f};
      int count[3] = {0, 0, 0};
      acc[q.tl] += normalise(r0[sx],     blackLevel[0], range[0]); count[q.tl]++;
      acc[q.tr] += normalise(r0[sx + 1], blackLevel[1], range[1]); count[q.tr]++;
      acc[q.bl] += normalise(r1[sx],     blackLevel[2], range[2]); count[q.bl]++;
      acc[q.br] += normalise(r1[sx + 1], blackLevel[3], range[3]); count[q.br]++;
      for (int c = 0; c < 3; c++) {
        float v = count[c] > 1 ? acc[c] / (float)count[c] : acc[c];
        v *= (asShotNeutral[c] > 0.0f ? asShotNeutral[c] : 1.0f);
        dst[x * 4 + c] = (uint8_t)std::lround(std::clamp(srgbGamma(v), 0.0f, 1.0f) * 255.0f);
      }
      dst[x * 4 + 3] = 255;
    }
  }
  return true;
}

}  // namespace rawcam
```

- [ ] **Step 5: Add the source to CMake**

In `core/CMakeLists.txt`, add `src/preview.cpp` to the `add_library(rawcam_core STATIC ...)` list at line 6, keeping the existing ordering style.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd core/build && cmake --build . && ctest --output-on-failure -R test_preview`
Expected: PASS, 7 test cases.

- [ ] **Step 7: Run the whole suite to check nothing regressed**

Run: `cd core/build && ctest --output-on-failure`
Expected: every pre-existing test still passes.

- [ ] **Step 8: Commit**

```bash
git add core/include/rawcam/preview.h core/src/preview.cpp core/tests/test_preview.cpp core/CMakeLists.txt
git commit -m "feat(core): develop a RAW16 Bayer plane into binned RGBA"
```

---

### Task 2: Box downscale to fit a bounding box

**Files:**
- Modify: `core/include/rawcam/preview.h`
- Modify: `core/src/preview.cpp`
- Modify: `core/tests/test_preview.cpp`

**Interfaces:**
- Consumes: `PreviewImage`, from Task 1
- Produces: `bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out);` — preserves aspect, never upscales, integer box average.

- [ ] **Step 1: Write the failing test**

Append to `core/tests/test_preview.cpp`:

```cpp
static PreviewImage solid(uint32_t w, uint32_t h, uint8_t r, uint8_t g, uint8_t b) {
  PreviewImage p;
  p.width = w; p.height = h;
  p.rgba.assign((size_t)w * h * 4, 255);
  for (size_t i = 0; i < (size_t)w * h; i++) {
    p.rgba[i * 4 + 0] = r; p.rgba[i * 4 + 1] = g; p.rgba[i * 4 + 2] = b;
  }
  return p;
}

TEST_CASE("downscaleTo fits within the box and preserves aspect") {
  PreviewImage src = solid(2048, 1536, 10, 20, 30), out;
  CHECK(downscaleTo(src, 1024, 768, &out));
  CHECK(out.width == 1024);
  CHECK(out.height == 768);
}

TEST_CASE("downscaleTo is bounded by the tighter dimension") {
  // 4:3 source into a wide box: height binds, width lands below the max.
  PreviewImage src = solid(2048, 1536, 0, 0, 0), out;
  CHECK(downscaleTo(src, 4000, 768, &out));
  CHECK(out.height == 768);
  CHECK(out.width == 1024);
}

TEST_CASE("downscaleTo preserves a solid colour") {
  PreviewImage src = solid(400, 300, 10, 20, 30), out;
  CHECK(downscaleTo(src, 200, 150, &out));
  CHECK(out.rgba[0] == 10);
  CHECK(out.rgba[1] == 20);
  CHECK(out.rgba[2] == 30);
  CHECK(out.rgba[3] == 255);
}

TEST_CASE("downscaleTo never upscales") {
  PreviewImage src = solid(100, 75, 1, 2, 3), out;
  CHECK(downscaleTo(src, 1024, 768, &out));
  CHECK(out.width == 100);
  CHECK(out.height == 75);
}

TEST_CASE("downscaleTo handles odd dimensions without losing the last row") {
  PreviewImage src = solid(101, 77, 9, 9, 9), out;
  CHECK(downscaleTo(src, 50, 50, &out));
  CHECK(out.width >= 1);
  CHECK(out.height >= 1);
  CHECK(out.rgba.size() == (size_t)out.width * out.height * 4);
  CHECK(out.rgba[0] == 9);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd core/build && cmake --build . 2>&1 | tail -5`
Expected: FAIL to compile — `downscaleTo` not declared.

- [ ] **Step 3: Declare it**

Add to `core/include/rawcam/preview.h`, inside `namespace rawcam`:

```cpp
// Box-averages `src` down to fit within maxW x maxH, preserving aspect ratio.
// Never upscales: a source already inside the box is copied unchanged.
// Returns false on an empty source or a zero-sized box.
bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out);
```

- [ ] **Step 4: Implement it**

Add to `core/src/preview.cpp`, inside `namespace rawcam`:

```cpp
bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out) {
  if (!out || src.width == 0 || src.height == 0 || maxW == 0 || maxH == 0) return false;
  if (src.rgba.size() < (size_t)src.width * src.height * 4) return false;

  const double scale = std::min({1.0,
                                 (double)maxW / (double)src.width,
                                 (double)maxH / (double)src.height});
  const uint32_t ow = std::max(1u, (uint32_t)std::lround(src.width * scale));
  const uint32_t oh = std::max(1u, (uint32_t)std::lround(src.height * scale));

  out->width = ow;
  out->height = oh;
  out->rgba.assign((size_t)ow * oh * 4, 255);

  for (uint32_t y = 0; y < oh; y++) {
    // Half-open source span for this destination row. The last box always
    // reaches the final source row, so odd sizes lose nothing.
    const uint32_t y0 = (uint32_t)((uint64_t)y * src.height / oh);
    const uint32_t y1 = std::max(y0 + 1, (uint32_t)((uint64_t)(y + 1) * src.height / oh));
    for (uint32_t x = 0; x < ow; x++) {
      const uint32_t x0 = (uint32_t)((uint64_t)x * src.width / ow);
      const uint32_t x1 = std::max(x0 + 1, (uint32_t)((uint64_t)(x + 1) * src.width / ow));
      uint32_t sum[3] = {0, 0, 0};
      uint32_t n = 0;
      for (uint32_t sy = y0; sy < y1 && sy < src.height; sy++) {
        const uint8_t* row = src.rgba.data() + (size_t)sy * src.width * 4;
        for (uint32_t sx = x0; sx < x1 && sx < src.width; sx++) {
          sum[0] += row[sx * 4 + 0];
          sum[1] += row[sx * 4 + 1];
          sum[2] += row[sx * 4 + 2];
          n++;
        }
      }
      uint8_t* dst = out->rgba.data() + ((size_t)y * ow + x) * 4;
      for (int c = 0; c < 3; c++) dst[c] = n ? (uint8_t)(sum[c] / n) : 0;
      dst[3] = 255;
    }
  }
  return true;
}
```

- [ ] **Step 5: Run the tests**

Run: `cd core/build && cmake --build . && ctest --output-on-failure -R test_preview`
Expected: PASS, 12 test cases.

- [ ] **Step 6: Commit**

```bash
git add core/include/rawcam/preview.h core/src/preview.cpp core/tests/test_preview.cpp
git commit -m "feat(core): box downscale a preview image to fit a bounding box"
```

---

### Task 3: Develop a frame straight from a clip, in every pack mode

**Files:**
- Modify: `core/include/rawcam/preview.h`
- Modify: `core/src/preview.cpp`
- Modify: `core/tests/test_preview.cpp`

**Interfaces:**
- Consumes: `developRaw16` (Task 1), `downscaleTo` (Task 2), `RawvReader` from `rawcam/rawv_reader.h`, `unpack10`/`unpack12` from `rawcam/pack10.h`, `decodeFrame` from `rawcam/rawv_codec.h`
- Produces: `bool developFrame(RawvReader& reader, uint64_t index, uint32_t maxW, uint32_t maxH, PreviewImage* out);`

**Read first:** `core/src/exporter.cpp:20-45` (`exportFrame`). It already does the unpack-by-mode dance this task must mirror, including the stored-fallback branch. Do not invent a second way of doing it.

- [ ] **Step 1: Write the failing test**

Append to `core/tests/test_preview.cpp`. This builds real clips through `RawvWriter`, the way `core/tests/test_rawv_reader.cpp` does:

```cpp
#include "rawcam/rawv_writer.h"
#include "rawcam/rawv_reader.h"
#include <cstdio>

// A 4x2 RGGB frame whose first quad is full-scale red.
static std::vector<uint16_t> samplePixels(uint32_t white) {
  return { white, 0, 0, 0,
           0,     0, 0, 0 };
}

static FileHeader previewHeader(PackMode mode, uint32_t white, uint32_t frameBytes) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)mode;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = white;
  h.asShotNeutral[0] = 1.0f; h.asShotNeutral[1] = 1.0f; h.asShotNeutral[2] = 1.0f;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameBytes;
  return h;
}

static void writeOneFrameClip(const char* path, const FileHeader& h,
                              const std::vector<uint16_t>& pixels, uint32_t compressed) {
  auto w = RawvWriter::create(path, h);
  FrameMeta m{};
  m.frameIndex = 0;
  m.payloadBytes = (uint32_t)(pixels.size() * 2);
  m.compressed = compressed;
  w->writeFrame(m, reinterpret_cast<const uint8_t*>(pixels.data()), m.payloadBytes);
  w->finalize();
}

TEST_CASE("developFrame develops a Raw16 clip") {
  const char* path = "preview_raw16.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 1023, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out));
  CHECK(out.width == 2);
  CHECK(out.height == 1);
  CHECK(out.rgba[0] == 255);  // full-scale red in the first quad
  CHECK(out.rgba[2] == 0);
  std::remove(path);
}

TEST_CASE("developFrame refuses an out-of-range index") {
  const char* path = "preview_range.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 1023, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 99, 1024, 768, &out) == false);
  std::remove(path);
}

TEST_CASE("developFrame rejects a clip with a zero white level") {
  const char* path = "preview_nowhite.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 0, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out) == false);
  std::remove(path);
}

TEST_CASE("a stored-fallback frame in a compressed clip develops as plain RAW16") {
  // packMode says CompressedPredictive but meta.compressed == 0: the payload is
  // raw, and feeding it to the Rice decoder would produce garbage.
  const char* path = "preview_fallback.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::CompressedPredictive, 1023, 64),
                    samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out));
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[2] == 0);
  std::remove(path);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd core/build && cmake --build . 2>&1 | tail -5`
Expected: FAIL to compile — `developFrame` not declared.

- [ ] **Step 3: Declare it**

Add to `core/include/rawcam/preview.h`, inside `namespace rawcam`:

```cpp
class RawvReader;

// Reads frame `index` from `reader`, unpacks it according to the clip's pack
// mode, develops it, and downscales the result to fit maxW x maxH.
// Returns false if the frame cannot be read, the index is out of range, or the
// header cannot support development (whiteLevel == 0).
bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH, PreviewImage* out);
```

The forward declaration keeps `rawv_reader.h` out of the public header; include it from `preview.cpp` instead.

- [ ] **Step 4: Implement it**

Add to `core/src/preview.cpp`, with `#include "rawcam/rawv_reader.h"`, `#include "rawcam/pack10.h"` and `#include "rawcam/rawv_codec.h"` at the top:

```cpp
bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH, PreviewImage* out) {
  if (!out || index >= reader.frameCount()) return false;
  const FileHeader& h = reader.header();
  if (h.whiteLevel == 0 || h.width < 2 || h.height < 2) return false;

  const size_t pixelCount = (size_t)h.width * h.height;
  std::vector<uint8_t> payload(h.frameSizeBytes);
  FrameMeta meta{};
  if (!reader.readFrame(index, &meta, payload.data())) return false;

  // Mirrors exporter.cpp's exportFrame(): the mode picks the unpack, and a
  // CompressedPredictive frame with meta.compressed == 0 is the compressor's
  // stored fallback -- already plain RAW16, so it takes the Raw16 path.
  std::vector<uint16_t> unpacked;
  const uint16_t* raw16 = nullptr;
  const PackMode mode = (PackMode)h.packMode;
  uint32_t strideSamples = h.width;

  if (mode == PackMode::Packed10) {
    unpacked.resize(pixelCount);
    unpack10(payload.data(), pixelCount, unpacked.data());
    raw16 = unpacked.data();
  } else if (mode == PackMode::Packed12) {
    unpacked.resize(pixelCount);
    unpack12(payload.data(), pixelCount, unpacked.data());
    raw16 = unpacked.data();
  } else if (mode == PackMode::CompressedPredictive && meta.compressed) {
    unpacked.resize(pixelCount);
    if (!decodeFrame(payload.data(), meta.payloadBytes, h.width, h.height,
                     h.whiteLevel, unpacked.data())) {
      return false;
    }
    raw16 = unpacked.data();
  } else {
    // Raw16 and the stored-fallback case. The plane arrives with the header's
    // row stride, which can be wider than the active width.
    raw16 = reinterpret_cast<const uint16_t*>(payload.data());
    if (h.rowStrideBytes >= h.width * 2) strideSamples = h.rowStrideBytes / 2;
  }

  PreviewImage full;
  if (!developRaw16(raw16, h.width, h.height, strideSamples, (Cfa)h.cfa,
                    h.blackLevel, h.whiteLevel, h.asShotNeutral, &full)) {
    return false;
  }
  return downscaleTo(full, maxW, maxH, out);
}
```

**Note for the implementer:** the `decodeFrame(...)` call above is written from the declaration's shape at `core/include/rawcam/rawv_codec.h:31`. Read that header and match its parameter list exactly — the compiler is the authority, not this plan.

- [ ] **Step 5: Run the tests**

Run: `cd core/build && cmake --build . && ctest --output-on-failure -R test_preview`
Expected: PASS, 16 test cases.

- [ ] **Step 6: Run the whole suite**

Run: `cd core/build && ctest --output-on-failure`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add core/include/rawcam/preview.h core/src/preview.cpp core/tests/test_preview.cpp
git commit -m "feat(core): develop a preview frame from a clip in any pack mode"
```

---

### Task 4: JNI surface

**Files:**
- Modify: `app/src/main/cpp/jni_bridge.cpp`
- Modify: `app/src/main/java/com/shez/rawcam/NativeBridge.kt`

**Interfaces:**
- Consumes: `developFrame` (Task 3)
- Produces:
  ```kotlin
  external fun nativeOpenClip(path: String): Long                 // 0 on failure
  external fun nativeClipFrameCount(handle: Long): Long
  external fun nativeDecodeFrame(handle: Long, index: Long, maxW: Int, maxH: Int): IntArray?
  external fun nativeCloseClip(handle: Long)
  ```
  `nativeDecodeFrame` returns `[width, height, argb0, argb1, ...]` — dimensions travel in the array so the caller can build a `Bitmap` without a second JNI round trip.

- [ ] **Step 1: Add the JNI functions**

Append to `app/src/main/cpp/jni_bridge.cpp`, following the style of `nativeClipInfo` at line 176, and add `#include "rawcam/preview.h"` at the top:

```cpp
// Owns a reader for the life of a decoding session. RawvReader::open scans the
// whole file to build its offset index, so opening per frame would make every
// decode pay for a full-file scan.
extern "C" JNIEXPORT jlong JNICALL
Java_com_shez_rawcam_NativeBridge_nativeOpenClip(JNIEnv* env, jobject, jstring jPath) {
  const char* pathChars = env->GetStringUTFChars(jPath, nullptr);
  std::string path(pathChars ? pathChars : "");
  env->ReleaseStringUTFChars(jPath, pathChars);
  auto reader = rawcam::RawvReader::open(path);
  if (!reader) return 0;
  return (jlong)(intptr_t)reader.release();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_shez_rawcam_NativeBridge_nativeClipFrameCount(JNIEnv*, jobject, jlong handle) {
  auto* reader = (rawcam::RawvReader*)(intptr_t)handle;
  if (!reader) return 0;
  return (jlong)reader->frameCount();
}

// Returns [width, height, argb...] for Bitmap.createBitmap, or null.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_shez_rawcam_NativeBridge_nativeDecodeFrame(JNIEnv* env, jobject, jlong handle,
                                                    jlong index, jint maxW, jint maxH) {
  auto* reader = (rawcam::RawvReader*)(intptr_t)handle;
  if (!reader || maxW <= 0 || maxH <= 0) return nullptr;
  rawcam::PreviewImage img;
  if (!rawcam::developFrame(*reader, (uint64_t)index, (uint32_t)maxW, (uint32_t)maxH, &img)) {
    return nullptr;
  }
  const jsize count = (jsize)(img.width * img.height);
  jintArray arr = env->NewIntArray(count + 2);
  if (!arr) return nullptr;
  std::vector<jint> pixels((size_t)count + 2);
  pixels[0] = (jint)img.width;
  pixels[1] = (jint)img.height;
  for (jsize i = 0; i < count; i++) {
    const uint8_t* p = img.rgba.data() + (size_t)i * 4;
    pixels[(size_t)i + 2] =
        (jint)(((uint32_t)0xFF << 24) | ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2]);
  }
  env->SetIntArrayRegion(arr, 0, count + 2, pixels.data());
  return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shez_rawcam_NativeBridge_nativeCloseClip(JNIEnv*, jobject, jlong handle) {
  delete (rawcam::RawvReader*)(intptr_t)handle;
}
```

- [ ] **Step 2: Declare them in Kotlin**

Add to `app/src/main/java/com/shez/rawcam/NativeBridge.kt`, after `nativeClipInfo`:

```kotlin
    // Opens a clip for preview decoding, returning an opaque handle (0 = failure).
    // RawvReader builds its frame-offset index on open, so a handle MUST be held
    // across a decoding session rather than opened per frame. Every successful
    // open must be paired with nativeCloseClip.
    external fun nativeOpenClip(path: String): Long
    external fun nativeClipFrameCount(handle: Long): Long
    // Returns intArrayOf(width, height, argb...) developed and downscaled to fit
    // maxW x maxH, or null if the frame cannot be developed.
    external fun nativeDecodeFrame(handle: Long, index: Long, maxW: Int, maxH: Int): IntArray?
    external fun nativeCloseClip(handle: Long)
```

- [ ] **Step 3: Build to verify the JNI compiles and links**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/cpp/jni_bridge.cpp app/src/main/java/com/shez/rawcam/NativeBridge.kt
git commit -m "feat(jni): expose clip open/decode/close for previews"
```

---

### Task 5: Proxy store and the sampling contract

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/preview/ProxyStore.kt`
- Create: `app/src/test/java/com/shez/rawcam/preview/ProxyStoreTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `ProxyStore` object and `ProxyIndex` data class, as written below. `PreviewService` (Task 6), `ClipsScreen` (Task 8) and `ClipViewerScreen` (Task 9) all use them.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/preview/ProxyStoreTest.kt`:

```kotlin
package com.shez.rawcam.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStoreTest {

    @Test fun `short takes use the default stride`() {
        assertEquals(5, ProxyStore.strideFor(152))
        assertEquals(31, ProxyStore.proxyCountFor(152))
    }

    @Test fun `a forty second take samples every fifth frame`() {
        assertEquals(5, ProxyStore.strideFor(960))
        assertEquals(192, ProxyStore.proxyCountFor(960))
    }

    @Test fun `the cap raises the stride rather than truncating the clip`() {
        val frames = 14_400
        val stride = ProxyStore.strideFor(frames)
        val count = ProxyStore.proxyCountFor(frames)
        assertEquals(12, stride)
        assertTrue("proxy count must respect the cap", count <= ProxyStore.MAX_PROXIES)
        // The whole-clip guarantee: the last sample lands within one stride of
        // the end, so sampling spans the take instead of stopping early.
        val last = ProxyStore.sourceIndexOf(count - 1, stride)
        assertEquals(14_388L, last)
        assertTrue("last sample must be near the end", last >= frames - stride)
    }

    @Test fun `the cap boundary keeps the default stride`() {
        // 6000 frames is exactly MAX_PROXIES * MIN_STRIDE.
        assertEquals(5, ProxyStore.strideFor(6_000))
        assertEquals(1_200, ProxyStore.proxyCountFor(6_000))
    }

    @Test fun `an empty clip yields no proxies`() {
        assertEquals(5, ProxyStore.strideFor(0))
        assertEquals(0, ProxyStore.proxyCountFor(0))
    }

    @Test fun `source index maps by stride`() {
        assertEquals(0L, ProxyStore.sourceIndexOf(0, 5))
        assertEquals(190L, ProxyStore.sourceIndexOf(38, 5))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testReleaseUnitTest --tests '*ProxyStoreTest*' 2>&1 | tail -10`
Expected: FAIL — unresolved reference `ProxyStore`.

- [ ] **Step 3: Implement the store**

Create `app/src/main/java/com/shez/rawcam/preview/ProxyStore.kt`:

```kotlin
package com.shez.rawcam.preview

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What a clip's proxy set is: [proxyCount] frames, [stride] source frames apart,
 * covering a clip of [sourceFrames] frames. [complete] is false while generation
 * is still running or was interrupted.
 */
data class ProxyIndex(
    val stride: Int,
    val sourceFrames: Int,
    val proxyCount: Int,
    val complete: Boolean,
)

/**
 * Layout of, and arithmetic over, the pre-rendered preview frames in cacheDir.
 *
 * Free of Android lifecycle on purpose: the sampling contract is the part with a
 * correctness claim, so it lives where JVM unit tests can drive it.
 */
object ProxyStore {
    const val MAX_PROXIES = 1200
    const val MIN_STRIDE = 5
    const val PROXY_MAX_W = 1024
    const val PROXY_MAX_H = 768
    const val JPEG_QUALITY = 80

    private const val INDEX_NAME = "index.json"

    /**
     * Frames between samples: 20% of the clip by default. Above the cap the
     * stride GROWS so the count stays bounded -- the sampled range still spans
     * the whole clip, it just gets coarser. It never truncates the take.
     */
    fun strideFor(frameCount: Int): Int {
        if (frameCount <= 0) return MIN_STRIDE
        val capped = (frameCount + MAX_PROXIES - 1) / MAX_PROXIES
        return maxOf(MIN_STRIDE, capped)
    }

    fun proxyCountFor(frameCount: Int): Int {
        if (frameCount <= 0) return 0
        val stride = strideFor(frameCount)
        return (frameCount + stride - 1) / stride
    }

    /** The source frame a proxy ordinal came from. */
    fun sourceIndexOf(proxyOrdinal: Int, stride: Int): Long = proxyOrdinal.toLong() * stride

    fun dirFor(context: Context, clipName: String): File =
        File(File(context.cacheDir, "proxies"), clipName)

    fun frameFile(dir: File, ordinal: Int): File = File(dir, "%06d.jpg".format(ordinal))

    fun readIndex(dir: File): ProxyIndex? {
        val f = File(dir, INDEX_NAME)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            ProxyIndex(
                stride = o.getInt("stride"),
                sourceFrames = o.getInt("sourceFrames"),
                proxyCount = o.getInt("proxyCount"),
                complete = o.getBoolean("complete"),
            )
        }.getOrNull()
    }

    fun writeIndex(dir: File, index: ProxyIndex) {
        dir.mkdirs()
        val o = JSONObject()
            .put("stride", index.stride)
            .put("sourceFrames", index.sourceFrames)
            .put("proxyCount", index.proxyCount)
            .put("complete", index.complete)
        File(dir, INDEX_NAME).writeText(o.toString())
    }

    /**
     * How many proxies are already on disk. Generation walks ordinals in order,
     * so what exists is always a prefix -- which makes this the resume point
     * after an interrupted run.
     */
    fun completedCount(dir: File): Int {
        if (!dir.isDirectory) return 0
        var n = 0
        while (frameFile(dir, n).isFile) n++
        return n
    }

    fun deleteFor(context: Context, clipName: String) {
        dirFor(context, clipName).deleteRecursively()
    }

    /** Drops proxy directories whose clip is gone. The cache owes nothing to anyone. */
    fun pruneOrphans(context: Context, liveClipNames: Set<String>) {
        val root = File(context.cacheDir, "proxies")
        if (!root.isDirectory) return
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name !in liveClipNames) d.deleteRecursively()
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testReleaseUnitTest --tests '*ProxyStoreTest*' 2>&1 | tail -10`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/preview/ProxyStore.kt app/src/test/java/com/shez/rawcam/preview/ProxyStoreTest.kt
git commit -m "feat(preview): proxy store layout and whole-clip sampling contract"
```

---

### Task 6: Background generation service

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/preview/PreviewService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `NativeBridge.nativeOpenClip` / `nativeClipFrameCount` / `nativeDecodeFrame` / `nativeCloseClip` (Task 4); `ProxyStore`, `ProxyIndex` (Task 5)
- Produces:
  ```kotlin
  PreviewService.start(context: Context, rawvPath: String, clipName: String)
  PreviewService.progressFor(clipName: String): Int   // proxies written so far, -1 if not queued
  ```

**Read first:** `app/src/main/java/com/shez/rawcam/export/ExportService.kt` — `onStartCommand` (line 71), `startForeground` (line 102), the notification channel setup (line 282), and the `companion object` (line 322). Mirror its structure rather than inventing a different service shape.

- [ ] **Step 1: Write the service**

Create `app/src/main/java/com/shez/rawcam/preview/PreviewService.kt`:

```kotlin
package com.shez.rawcam.preview

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a clip's preview proxies in the background, one clip at a time.
 *
 * Never started while a recording is in flight: developing frames is CPU-heavy,
 * and competing with the capture pipeline is exactly how frames get dropped.
 * RecordViewModel enqueues on stop (see Task 7), which is what keeps this off
 * the capture path -- the service does not police that itself.
 */
class PreviewService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_RAWV_PATH)
        val clipName = intent?.getStringExtra(EXTRA_CLIP_NAME)
        if (path == null || clipName == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(clipName, 0, 0))
        executor.execute {
            runCatching { generate(File(path), clipName) }
            progress.remove(clipName)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun generate(rawv: File, clipName: String) {
        val dir = ProxyStore.dirFor(this, clipName)
        val handle = NativeBridge.nativeOpenClip(rawv.absolutePath)
        if (handle == 0L) {
            // Unreadable clip: record an empty-but-complete set so the UI says
            // "unavailable" instead of spinning on a preview that never arrives.
            ProxyStore.writeIndex(dir, ProxyIndex(ProxyStore.MIN_STRIDE, 0, 0, complete = true))
            return
        }
        try {
            val frames = NativeBridge.nativeClipFrameCount(handle).toInt()
            val stride = ProxyStore.strideFor(frames)
            val total = ProxyStore.proxyCountFor(frames)
            ProxyStore.writeIndex(dir, ProxyIndex(stride, frames, total, complete = false))
            // Resume: ordinals are written in order, so what exists is a prefix.
            var ordinal = ProxyStore.completedCount(dir)
            while (ordinal < total && !cancelled.get()) {
                val src = ProxyStore.sourceIndexOf(ordinal, stride)
                val pixels = NativeBridge.nativeDecodeFrame(
                    handle, src, ProxyStore.PROXY_MAX_W, ProxyStore.PROXY_MAX_H,
                ) ?: break
                writeJpeg(pixels, ProxyStore.frameFile(dir, ordinal))
                ordinal++
                progress[clipName] = ordinal
                notifyProgress(clipName, ordinal, total)
            }
            ProxyStore.writeIndex(dir, ProxyIndex(stride, frames, total, complete = ordinal >= total))
        } finally {
            NativeBridge.nativeCloseClip(handle)
        }
    }

    /** `pixels` is [width, height, argb...] as returned by nativeDecodeFrame. */
    private fun writeJpeg(pixels: IntArray, out: File) {
        if (pixels.size < 3) return
        val w = pixels[0]
        val h = pixels[1]
        if (w <= 0 || h <= 0 || pixels.size < w * h + 2) return
        val bmp = Bitmap.createBitmap(pixels, 2, w, w, h, Bitmap.Config.ARGB_8888)
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, ProxyStore.JPEG_QUALITY, it) }
        bmp.recycle()
    }

    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RAWV_PATH = "rawvPath"
        const val EXTRA_CLIP_NAME = "clipName"
        private const val NOTIFICATION_ID = 4201
        private val cancelled = AtomicBoolean(false)
        private val progress = ConcurrentHashMap<String, Int>()

        fun start(context: Context, rawvPath: String, clipName: String) {
            cancelled.set(false)
            progress[clipName] = 0
            val i = Intent(context, PreviewService::class.java)
                .putExtra(EXTRA_RAWV_PATH, rawvPath)
                .putExtra(EXTRA_CLIP_NAME, clipName)
            context.startForegroundService(i)
        }

        /** Proxies written so far for [clipName], or -1 when it is not queued. */
        fun progressFor(clipName: String): Int = progress[clipName] ?: -1
    }
}
```

`buildNotification(clipName, done, total)` and `notifyProgress(clipName, done, total)` and the channel: copy the shape from `ExportService.kt:282` verbatim, changing the channel id to `"preview"`, the channel name to `"Preview"`, and the text to `"Preparing preview"`. Keeping `IMPORTANCE_LOW` keeps it silent.

- [ ] **Step 2: Register the service**

In `app/src/main/AndroidManifest.xml`, beside the existing `ExportService` entry:

```xml
<service
    android:name=".preview.PreviewService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/preview/PreviewService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(preview): background proxy generation service"
```

---

### Task 7: Enqueue generation when a recording stops

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — the stop completion inside `stopRecordingInternal`, in the existing `if (stats[0] > 0) { ... }` branch that handles auto-export

**Interfaces:**
- Consumes: `PreviewService.start` (Task 6)
- Produces: nothing new

- [ ] **Step 1: Enqueue after a successful stop**

Inside that `if (stats[0] > 0)` branch, before the `st.autoExport` check:

```kotlin
                    // Preview proxies. Enqueued only once the take has finished --
                    // developing frames is CPU-heavy and must never compete with an
                    // active capture (see PreviewService's kdoc).
                    lastClipName?.let { name ->
                        PreviewService.start(
                            getApplication(),
                            File(controller.clipsDir, name).absolutePath,
                            name,
                        )
                    }
```

Add `import com.shez.rawcam.preview.PreviewService` to the file's imports.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify on device that generation runs after a take, not during**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# record a short take, stop it, then:
adb shell "run-as com.shez.rawcam ls cache/proxies"
adb shell "run-as com.shez.rawcam ls cache/proxies/<clipName> | head"
```
Expected: a directory named after the clip, filling with `%06d.jpg` files **after** the recording stops. `DROPPED` on the take itself must stay at its usual value for that configuration — new dropped frames mean generation is racing capture.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat(record): generate preview proxies when a take finishes"
```

---

### Task 8: Thumbnails and pending state in the Clips list

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt`

**Interfaces:**
- Consumes: `ProxyStore.dirFor` / `frameFile` / `pruneOrphans` (Task 5), `PreviewService.progressFor` (Task 6)
- Produces: `ClipsScreen(onBack: () -> Unit, onOpenViewer: (File) -> Unit)` — the new parameter is wired up in Task 10

- [ ] **Step 1: Carry the poster path on each entry**

`ClipEntry` (line 64) gains a field:

```kotlin
private data class ClipEntry(
    val file: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    val exportedFrameCount: Int,   // -1 if never exported / no output folder yet
    val posterPath: String?,       // proxy 0 on disk, or null while it does not exist yet
)
```

In the existing `withContext(Dispatchers.IO)` load block that builds the entries, compute:

```kotlin
                val proxyDir = ProxyStore.dirFor(context, file.name)
                val poster = ProxyStore.frameFile(proxyDir, 0).takeIf { it.isFile }?.absolutePath
```

and once the list is built, drop caches for clips that no longer exist:

```kotlin
            ProxyStore.pruneOrphans(context, files.map { it.name }.toSet())
```

- [ ] **Step 2: Render the thumbnail column**

In the row composable (around line 315, before `Column(Modifier.weight(1f))`):

```kotlin
            Box(
                Modifier.size(96.dp, 72.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(RawCamColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                val poster = clip.posterPath
                val pending = PreviewService.progressFor(clip.file.name)
                when {
                    poster != null -> {
                        val bmp = remember(poster) { BitmapFactory.decodeFile(poster) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    pending >= 0 -> Text("...", color = RawCamColors.Muted, fontSize = 12.sp)
                    else -> Text("--", color = RawCamColors.Muted, fontSize = 12.sp)
                }
            }
```

Imports: `androidx.compose.foundation.Image`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.asImageBitmap`, `androidx.compose.ui.layout.ContentScale`, `android.graphics.BitmapFactory`, `com.shez.rawcam.preview.ProxyStore`, `com.shez.rawcam.preview.PreviewService`.

**Check `RawCamColors.Surface` exists in `ui/Theme.kt`** and use whatever the surrounding rows already use if it does not.

- [ ] **Step 3: Make the row open the viewer**

Change the signature at line 163:

```kotlin
fun ClipsScreen(onBack: () -> Unit = {}, onOpenViewer: (File) -> Unit = {}) {
```

and give the row container `Modifier.clickable { onOpenViewer(clip.file) }`.

- [ ] **Step 4: Build, install, and LOOK at it**

```bash
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
adb exec-out screencap -p > clips.png
```
Expected: each row shows a thumbnail, or `...` while its proxies are still being written. **Open the screenshot and look at it.** This project has a standing rule that visual work is never called done off a green build — six layout bugs shipped that way once already.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt
git commit -m "feat(clips): poster thumbnail and pending state per clip"
```

---

### Task 9: The clip viewer

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/ui/ClipViewerScreen.kt`

**Interfaces:**
- Consumes: `ProxyStore` (Task 5), `PreviewService.progressFor` (Task 6)
- Produces: `@Composable fun ClipViewerScreen(clip: File, onBack: () -> Unit)`

- [ ] **Step 1: Write the screen**

Create `app/src/main/java/com/shez/rawcam/ui/ClipViewerScreen.kt`:

```kotlin
package com.shez.rawcam.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shez.rawcam.preview.PreviewService
import com.shez.rawcam.preview.ProxyStore
import kotlinx.coroutines.delay
import java.io.File

/**
 * Flips through a clip's pre-rendered proxy frames. Nothing is developed here --
 * every frame on screen is a JPEG that PreviewService already wrote, which is
 * what keeps this responsive however slow RAW decoding turns out to be.
 *
 * Playback is deliberately choppy: proxies are every Nth frame, so 24fps
 * material plays at 24/N. Enough to see what happened in a take, which is the
 * whole point of the screen.
 */
@Composable
fun ClipViewerScreen(clip: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val dir = remember(clip) { ProxyStore.dirFor(context, clip.name) }

    var meta by remember(clip) { mutableStateOf(ProxyStore.readIndex(dir)) }
    var available by remember(clip) { mutableStateOf(0) }
    var index by remember(clip) { mutableStateOf(0) }
    var playing by remember(clip) { mutableStateOf(true) }

    // Poll what exists, so a viewer opened mid-generation fills in as frames land
    // instead of blocking on "ready".
    LaunchedEffect(dir) {
        while (true) {
            available = ProxyStore.completedCount(dir)
            meta = ProxyStore.readIndex(dir)
            val m = meta
            if (m != null && m.complete && available >= m.proxyCount) break
            delay(500)
        }
    }

    val stride = meta?.stride ?: ProxyStore.MIN_STRIDE
    // Frame interval is stride/fps seconds -- derived, not hardcoded, so a
    // capped stride on a long take still plays at the right speed.
    val frameIntervalMs = remember(stride) { (1000L * stride) / 24 }

    LaunchedEffect(playing, available, frameIntervalMs) {
        while (playing && available > 0) {
            delay(frameIntervalMs)
            index = if (index + 1 < available) index + 1 else 0
        }
    }

    val bitmap = remember(index, available) {
        ProxyStore.frameFile(dir, index).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text(clip.name, color = RawCamColors.Muted)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                PreviewService.progressFor(clip.name) >= 0 || meta?.complete == false ->
                    Text("Preparing preview -- $available / ${meta?.proxyCount ?: 0}")
                else -> Text("Preview unavailable")
            }
        }
        // Scrub: horizontal drag maps position along the bar to a proxy ordinal.
        Box(
            Modifier.fillMaxWidth().height(48.dp).pointerInput(available) {
                detectHorizontalDragGestures { change, _ ->
                    if (available > 0) {
                        playing = false
                        val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        index = ((available - 1) * f).toInt()
                    }
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("f=${ProxyStore.sourceIndexOf(index, stride)} / ${meta?.sourceFrames ?: 0}")
        }
    }
}
```

**Note for the implementer:** `RawCamColors` and the typography helpers live in `ui/Theme.kt`. Match what `ClipsScreen` and `ExportsScreen` already use rather than introducing new styles — the app has a deliberate type scale. The 24 in `frameIntervalMs` is the capture rate; if `ProxyIndex` is later extended to carry fps, read it from there instead of assuming.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleRelease 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/ClipViewerScreen.kt
git commit -m "feat(clips): viewer that plays and scrubs proxy frames"
```

---

### Task 10: Route the viewer and verify end to end on device

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/MainActivity.kt`

**Interfaces:**
- Consumes: `ClipsScreen(onBack, onOpenViewer)` (Task 8), `ClipViewerScreen(clip, onBack)` (Task 9)
- Produces: nothing further

- [ ] **Step 1: Add the route**

Extend the enum:

```kotlin
private enum class Screen { Record, Clips, ClipViewer, Exports, Settings }
```

Add an activity field beside the existing `screen` field:

```kotlin
    // Which clip the viewer is showing. Held beside `screen` rather than inside
    // the enum so returning to the list does not have to re-derive it.
    private var viewerClip by mutableStateOf<File?>(null)
```

Replace the `Screen.Clips` branch and add the viewer branch:

```kotlin
                        Screen.Clips -> ClipsScreen(
                            onBack = { screen = Screen.Record },
                            onOpenViewer = { file -> viewerClip = file; screen = Screen.ClipViewer },
                        )
                        Screen.ClipViewer -> {
                            val c = viewerClip
                            if (c == null) screen = Screen.Clips
                            else ClipViewerScreen(clip = c, onBack = { screen = Screen.Clips })
                        }
```

Add `import java.io.File`.

- [ ] **Step 2: Build and install**

```bash
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

- [ ] **Step 3: Verify the matrix on device**

Record and check each of these, screenshotting the viewer each time:

1. **Compressed clip, main lens.** Verify the clip really is compressed before trusting anything: `adb exec-out "dd if=<clip> bs=64 count=1" | od -A d -t u4` — the word at byte offset 20 must read `3`. (The `3` at offset 24 is CFA, not packMode. The compression toggle has silently reverted three times in this project's history.)
2. **Uncompressed clip** (packMode `0`), same lens — previews must match in framing and exposure.
3. **A second lens** (12mm) — different geometry, exercises CFA/stride handling.
4. **Viewer opened mid-generation** — shows "Preparing preview -- n / m" and starts playing as frames land, without blocking.
5. **Delete a clip** — its proxy directory disappears on the next Clips load.
6. **Start a recording while generation is running** — `DROPPED` must not rise above its usual value for that configuration.

- [ ] **Step 4: Measure generation throughput**

Sample the proxy count a few seconds apart during generation:

```bash
adb shell "run-as com.shez.rawcam ls cache/proxies/<clipName>" | wc -l
```

Record proxies-per-second in the commit message. It decides whether the 1200 cap is comfortable, or whether a long take takes unacceptably long to become viewable.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/MainActivity.kt
git commit -m "feat(clips): route the clip viewer"
```

---

## Self-Review Notes

**Spec coverage:** poster thumbnail (Task 8); viewer with playback and scrub (Task 9); pending state (Tasks 8 and 9); all four pack modes (Task 3); all lenses (Task 3 reads geometry and CFA from the clip header, verified in Task 10.3); stride, cap, and the whole-clip guarantee (Task 5); cacheDir store with `index.json` (Task 5); deferral from the capture path (Tasks 6 and 7, verified in Task 10.6); orphan cleanup (Task 8 step 1); host tests (Tasks 1-3); error handling (Task 3's range and white-level cases, Task 6's handle-0 path, Task 9's "Preview unavailable").

**Known gap, deliberately left explicit:** `PreviewService` does not itself check `recording || busy`. Deferral comes from only ever enqueuing on stop (Task 7). If a manual "generate preview" action is added to the Clips screen later, that action must gate on recording state itself — the service will not do it for you.

**Unverified assumption:** the `decodeFrame(...)` argument list in Task 3 is written from the declaration's shape at `core/include/rawcam/rawv_codec.h:31`. The implementer must read that header and match it exactly.

**Deliberate simplification:** the viewer assumes 24fps when deriving its playback interval. Every clip this app has recorded is 24 or 30fps, and the visible consequence of guessing wrong is playback that is 25% fast — not a correctness bug. Reading the real rate via `nativeClipInfo` is a one-line upgrade if it ever matters.
