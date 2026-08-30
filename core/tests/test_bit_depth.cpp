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
