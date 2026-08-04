#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_codec.h"
#include <cstdlib>
#include <vector>

using namespace rawcam;

namespace {
std::vector<uint16_t> makeFrame(uint32_t width, uint32_t height, uint32_t bitDepth,
                                 uint16_t (*gen)(uint32_t x, uint32_t y, uint16_t maxVal)) {
  uint16_t maxVal = static_cast<uint16_t>((1u << bitDepth) - 1);
  std::vector<uint16_t> buf(static_cast<size_t>(width) * height);
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      buf[y * width + x] = gen(x, y, maxVal);
  return buf;
}

bool roundTrips(const std::vector<uint16_t>& src, uint32_t width, uint32_t height, uint32_t bitDepth) {
  std::vector<uint8_t> compressed(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = encodeFrame(src.data(), width, height, width, bitDepth,
                            compressed.data(), static_cast<uint32_t>(compressed.size()));
  if (n == 0) return false;
  std::vector<uint16_t> out(src.size());
  if (!decodeFrame(compressed.data(), n, out.data(), width, height, width, bitDepth)) return false;
  return out == src;
}
}  // namespace

TEST_CASE("round-trips a flat (all-same-value) 16-bit frame") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return static_cast<uint16_t>(maxVal / 2); });
  CHECK(roundTrips(src, 64, 64, 16));
}

TEST_CASE("round-trips a smooth gradient at 12-bit depth") {
  auto src = makeFrame(64, 64, 12, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x + y) * 7) % (maxVal + 1));
  });
  CHECK(roundTrips(src, 64, 64, 12));
}

TEST_CASE("round-trips pseudo-random noise at 10-bit depth (exercises worst-case residuals)") {
  std::srand(12345);
  auto src = makeFrame(64, 64, 10, [](uint32_t, uint32_t, uint16_t maxVal) {
    return static_cast<uint16_t>(std::rand() % (maxVal + 1));
  });
  CHECK(roundTrips(src, 64, 64, 10));
}

TEST_CASE("round-trips a frame with one extreme residual spike (forces multi-chunk Rice quotients)") {
  // Flat content picks a small Rice k (near 0), then one pixel jumps to
  // maxVal -- its residual is large enough that q = residual >> k exceeds
  // 32, exercising the batched BitWriter/BitReader's chunk-draining loop
  // (Task 1 drains 32 bits at a time for large quotients), a boundary the
  // original per-bit implementation has no equivalent of.
  auto src = makeFrame(64, 64, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return (x == 40 && y == 40) ? maxVal : static_cast<uint16_t>(maxVal / 2);
  });
  CHECK(roundTrips(src, 64, 64, 16));
}

TEST_CASE("round-trips a single-row and single-column frame (edge-only prediction)") {
  auto row = makeFrame(64, 1, 16, [](uint32_t x, uint32_t, uint16_t) { return static_cast<uint16_t>(x * 37 % 65536); });
  CHECK(roundTrips(row, 64, 1, 16));
  auto col = makeFrame(1, 64, 16, [](uint32_t, uint32_t y, uint16_t) { return static_cast<uint16_t>(y * 37 % 65536); });
  CHECK(roundTrips(col, 1, 64, 16));
}

TEST_CASE("encodeFrame returns 0 (caller falls back) when outCapacity is too small") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return maxVal; });
  std::vector<uint8_t> tiny(4);
  uint32_t n = encodeFrame(src.data(), 64, 64, 64, 16, tiny.data(), static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);
}

TEST_CASE("handles rowStrideSamples wider than width (padded rows)") {
  const uint32_t width = 32, height = 32, stride = 40;  // stride > width
  std::vector<uint16_t> src(static_cast<size_t>(stride) * height, 0);
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      src[y * stride + x] = static_cast<uint16_t>((x * 13 + y * 29) % 4096);
  std::vector<uint8_t> compressed(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = encodeFrame(src.data(), width, height, stride, 12, compressed.data(), static_cast<uint32_t>(compressed.size()));
  REQUIRE(n > 0);
  std::vector<uint16_t> out(src.size(), 0);
  REQUIRE(decodeFrame(compressed.data(), n, out.data(), width, height, stride, 12));
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      CHECK(out[y * stride + x] == src[y * stride + x]);
}
