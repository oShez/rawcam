#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/timecode.h"
#include <cstdint>

using namespace rawcam;

// SMPTE 12M packs each field as BCD, so a correct timecode reads back in hex
// exactly as it reads on a clock: 12:33:33:00 -> hours 0x12, minutes 0x33,
// seconds 0x33, frames 0x00. That is what makes these expectations legible.
static uint64_t nsAt(uint32_t h, uint32_t m, uint32_t s) {
  return ((uint64_t)h * 3600 + (uint64_t)m * 60 + s) * 1000000000ull;
}

TEST_CASE("frame 0 carries the take's start wall-clock") {
  uint8_t tc[8] = {0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE};
  REQUIRE(packTimecode(nsAt(12, 33, 33), 0, 24, 1, tc));
  CHECK(tc[0] == 0x00);  // frames
  CHECK(tc[1] == 0x33);  // seconds
  CHECK(tc[2] == 0x33);  // minutes
  CHECK(tc[3] == 0x12);  // hours
  // Binary groups are unused; leaving them as scratch would emit garbage into
  // every DNG, so the packer must zero them rather than only writing 0..3.
  CHECK(tc[4] == 0x00);
  CHECK(tc[5] == 0x00);
  CHECK(tc[6] == 0x00);
  CHECK(tc[7] == 0x00);
}

TEST_CASE("the frame index advances the timecode") {
  uint8_t tc[8];
  // One second in at 24 fps: seconds tick, frames return to 0.
  REQUIRE(packTimecode(nsAt(12, 33, 33), 24, 24, 1, tc));
  CHECK(tc[0] == 0x00);
  CHECK(tc[1] == 0x34);
  // The frame before that is the last frame of the previous second: :23, and
  // BCD means 23 is 0x23, not 0x17 -- a plain binary write would pass a
  // decimal-looking check here and still be wrong.
  REQUIRE(packTimecode(nsAt(12, 33, 33), 23, 24, 1, tc));
  CHECK(tc[0] == 0x23);
  CHECK(tc[1] == 0x33);
}

TEST_CASE("30 fps counts to 29 before rolling the second") {
  uint8_t tc[8];
  REQUIRE(packTimecode(nsAt(1, 2, 3), 29, 30, 1, tc));
  CHECK(tc[0] == 0x29);
  CHECK(tc[1] == 0x03);
  REQUIRE(packTimecode(nsAt(1, 2, 3), 30, 30, 1, tc));
  CHECK(tc[0] == 0x00);
  CHECK(tc[1] == 0x04);
  CHECK(tc[2] == 0x02);
  CHECK(tc[3] == 0x01);
}

TEST_CASE("a take crossing local midnight wraps instead of reaching hour 24") {
  uint8_t tc[8];
  // Start one frame before midnight, then step past it.
  REQUIRE(packTimecode(nsAt(23, 59, 59), 23, 24, 1, tc));
  CHECK(tc[3] == 0x23);
  CHECK(tc[0] == 0x23);
  REQUIRE(packTimecode(nsAt(23, 59, 59), 24, 24, 1, tc));
  CHECK(tc[0] == 0x00);
  CHECK(tc[1] == 0x00);
  CHECK(tc[2] == 0x00);
  CHECK(tc[3] == 0x00);  // hour 24 would be unparseable to an NLE
}

TEST_CASE("a sub-second start offset lands on the right frame") {
  uint8_t tc[8];
  // Recording began half a second past the second: frame 0 is frame 12 of 24.
  REQUIRE(packTimecode(nsAt(12, 33, 33) + 500000000ull, 0, 24, 1, tc));
  CHECK(tc[0] == 0x12);
  CHECK(tc[1] == 0x33);
}

TEST_CASE("an unusable frame rate is rejected without touching the buffer") {
  uint8_t tc[8];
  for (int i = 0; i < 8; i++) tc[i] = 0xEE;
  CHECK_FALSE(packTimecode(nsAt(12, 0, 0), 0, 24, 0, tc));
  CHECK_FALSE(packTimecode(nsAt(12, 0, 0), 0, 0, 1, tc));
  // A rejected call must not half-write a timecode the caller might still emit.
  for (int i = 0; i < 8; i++) CHECK(tc[i] == 0xEE);
}
