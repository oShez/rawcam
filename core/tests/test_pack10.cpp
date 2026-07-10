#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/pack10.h"
#include <vector>

using namespace rawcam;

TEST_CASE("pack10 round-trips all 10-bit values") {
  std::vector<uint16_t> src(1024);
  for (size_t i = 0; i < src.size(); i++) src[i] = (uint16_t)(i & 0x3FF);
  std::vector<uint8_t> packed(packed10Size(src.size()));
  std::vector<uint16_t> out(src.size());
  pack10(src.data(), src.size(), packed.data());
  unpack10(packed.data(), out.size(), out.data());
  CHECK(out == src);
  CHECK(packed.size() == 1280);  // 1024 * 10 / 8
}

TEST_CASE("values above 10 bits are truncated to low 10") {
  uint16_t src[4] = {0xFFFF, 0x0400, 0x03FF, 0};
  uint8_t packed[5];
  uint16_t out[4];
  pack10(src, 4, packed);
  unpack10(packed, 4, out);
  CHECK(out[0] == 0x3FF);
  CHECK(out[1] == 0x000);
  CHECK(out[2] == 0x3FF);
  CHECK(out[3] == 0x000);
}
