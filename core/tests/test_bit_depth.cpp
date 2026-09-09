#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/bit_depth.h"
#include <vector>

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

#include "rawcam/rawv.h"

TEST_CASE("applyBitDepth scales the header consistently and keeps the derived depth right") {
  FileHeader h{};
  h.whiteLevel = 16383;
  // 1023, not 1024: 1024>>2==256 same as (1024+2)>>2==256, so this fixture
  // would stay green even if applyBitDepth's blackLevel reduction used
  // reducedWhiteLevel (truncate) instead of reduceLevel (round). 1023 only
  // reaches 256 by rounding up ((1023+2)>>2==256); truncation gives 255 --
  // don't "tidy" this back to 1024, it would make the check decorative again.
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 1023;

  uint32_t shift = applyBitDepth(h, 12);
  CHECK(shift == 2);
  CHECK(h.whiteLevel == 4095);                                 // truncated, NOT 4096
  // what capture.cpp's `bitDepth = 32 - clz(whiteLevel)` derivation relies on:
  CHECK(32u - (uint32_t)__builtin_clz(h.whiteLevel) == 12u);
  for (int i = 0; i < 4; i++) CHECK(h.blackLevel[i] == 256);   // rounded, not truncated (255)
  CHECK(h.blackLevel[0] < h.whiteLevel);
  // capture.cpp's PackMode selection reads whiteLevel: 4095 <= 0xFFF => Packed12.
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
  // 63, not 64: 64>>2==16 same as (64+2)>>2==16, so this fixture would stay
  // green under truncation too. 63 only reaches 16 by rounding up
  // ((63+2)>>2==16); truncation gives 15 -- keep it 63, not 64.
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 63;

  CHECK(applyBitDepth(h, 12) == 0);          // cannot synthesise precision
  CHECK(h.whiteLevel == 1023);
  CHECK(applyBitDepth(h, 8) == 2);
  CHECK(h.whiteLevel == 255);
  for (int i = 0; i < 4; i++) CHECK(h.blackLevel[i] == 16);  // rounded, not truncated (15)
}

// reducePlaneInPlace is the compressed path's uncompressed-fallback reduction.
// That caller is in capture.cpp, which has no host harness, so these cases are
// the only automated coverage the arithmetic gets -- the wiring is verified
// on-device. Without them the fallback is the one path in this feature that can
// write visibly wrong frames (several stops bright) with nothing testing it.
TEST_CASE("reducePlaneInPlace reduces every sample and clamps at saturation") {
  const uint32_t shift = 2, newWhite = reducedWhiteLevel(16383, shift);  // 4095
  std::vector<uint16_t> plane = {0, 4, 6, 1024, 16383, 65535};
  std::vector<uint16_t> expected;
  for (uint16_t v : plane) expected.push_back(reduceSample(v, shift, newWhite));

  reducePlaneInPlace(plane.data(), plane.size(), shift, newWhite);
  CHECK(plane == expected);
  // Spelled out too, so this fails loudly if reduceSample's contract ever drifts:
  CHECK(plane[0] == 0);
  CHECK(plane[1] == 1);
  CHECK(plane[2] == 2);          // rounds up
  CHECK(plane[3] == 256);
  CHECK(plane[4] == newWhite);   // 4096 unclamped -- would need 13 bits
  CHECK(plane[5] == newWhite);   // a saturated sensor sample, clamped
}

TEST_CASE("reducePlaneInPlace is an exact no-op at shift 0") {
  std::vector<uint16_t> plane = {0, 1, 1024, 16383, 65535};
  const std::vector<uint16_t> before = plane;
  reducePlaneInPlace(plane.data(), plane.size(), 0, 0);
  CHECK(plane == before);  // newWhite 0 must NOT clamp everything to zero
}

TEST_CASE("reducePlaneInPlace tolerates an empty plane") {
  std::vector<uint16_t> plane;
  reducePlaneInPlace(plane.data(), 0, 2, 4095);  // must not read or write
  CHECK(plane.empty());
}
