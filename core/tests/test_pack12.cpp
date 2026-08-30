#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/pack10.h"
#include "rawcam/bit_depth.h"
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

TEST_CASE("count==0 is a no-op, not a crash") {
  uint8_t dummy = 0xAB;
  pack12(nullptr, 0, &dummy);
  unpack12(&dummy, 0, nullptr);
  CHECK(dummy == 0xAB);  // loop body never ran, buffer untouched
  CHECK(packed12Size(0) == 0);
}

TEST_CASE("pack12 reduces samples before packing") {
  std::vector<uint16_t> src = {16383, 8192, 7, 4};
  const uint32_t shift = 2, newWhite = 4095;
  std::vector<uint8_t> packed(rawcam::packed12Size(src.size()));
  rawcam::pack12(src.data(), src.size(), packed.data(), shift, newWhite);

  std::vector<uint16_t> back(src.size());
  rawcam::unpack12(packed.data(), src.size(), back.data());
  for (size_t i = 0; i < src.size(); i++)
    CHECK(back[i] == rawcam::reduceSample(src[i], shift, newWhite));
}

TEST_CASE("pack12 with shift 0 is unchanged") {
  std::vector<uint16_t> src = {4095, 2048, 7, 4};
  std::vector<uint8_t> a(rawcam::packed12Size(src.size())), b(a.size());
  rawcam::pack12(src.data(), src.size(), a.data());
  rawcam::pack12(src.data(), src.size(), b.data(), 0, 4095);
  CHECK(a == b);
}
