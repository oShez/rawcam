#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/pack10.h"
#include <vector>

using namespace rawcam;

TEST_CASE("pack12 round-trips all 12-bit values") {
  std::vector<uint16_t> src(4096);
  for (size_t i = 0; i < src.size(); i++) src[i] = (uint16_t)(i & 0xFFF);
  std::vector<uint8_t> packed(packed12Size(src.size()));
  std::vector<uint16_t> out(src.size());
  pack12(src.data(), src.size(), packed.data());
  unpack12(packed.data(), out.size(), out.data());
  CHECK(out == src);
  CHECK(packed.size() == 6144);  // 4096 * 12 / 8
}

TEST_CASE("values above 12 bits are truncated to low 12") {
  uint16_t src[2] = {0xFFFF, 0x0FFF};
  uint8_t packed[3];
  uint16_t out[2];
  pack12(src, 2, packed);
  unpack12(packed, 2, out);
  CHECK(out[0] == 0xFFF);
  CHECK(out[1] == 0xFFF);
}
