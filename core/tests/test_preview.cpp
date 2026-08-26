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
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[1] == 0);
  CHECK(out.rgba[2] == 0);
  CHECK(out.rgba[3] == 255);
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
  CHECK(b.rgba[1] > a.rgba[1]);
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
