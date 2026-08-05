#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_codec.h"
#include <algorithm>
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

TEST_CASE("rejects encoding when outCapacity lands in partial-byte boundary (regression: capacity-boundary bug)") {
  // Flat 64x64 frame results in k=0, one residual bit per pixel (a zero terminator).
  // 4096 pixels * 1 bit = 4096 bits = 512 bytes of residuals, plus 1 header byte = 513 bytes total.
  // Setting outCapacity=512 makes BitWriter's capacity 511 bytes (3968 bits), leaving 128 bits short.
  // The batched writeBits must reject this upfront (bit-granular check) rather than silently
  // dropping the trailing bits. Regression test for the capacity-boundary bug fixed in Task 1.
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) {
    return static_cast<uint16_t>(maxVal / 2);  // All same value -> all residuals 0 -> k=0
  });
  std::vector<uint8_t> tight(512);  // 1 byte too small
  uint32_t n = encodeFrame(src.data(), 64, 64, 64, 16, tight.data(), static_cast<uint32_t>(tight.size()));
  CHECK(n == 0);  // Must fail, not silently truncate
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

TEST_CASE("round-trips dimensions not evenly divisible by the k-sampling stride") {
  // 63x65 isn't a multiple of the 4x4 sampling stride Task 2 introduces --
  // this pins that the sampling loop's bounds never go out of range and
  // still produce a usable k (count is always >= 1 since (0,0) is always
  // sampled) regardless of width/height parity.
  auto src = makeFrame(63, 65, 12, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 17 + y * 5) ^ 0x2A) % (maxVal + 1));
  });
  CHECK(roundTrips(src, 63, 65, 12));
}

TEST_CASE("ParallelFrameEncoder (round 4: band-parallel write) produces byte-identical output to encodeFrame") {
  // 512x512 with threadCount forced to 4 guarantees a real multi-band split
  // and exercises the merge step across real band boundaries.
  auto src = makeFrame(512, 512, 14, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 31 + y * 17) ^ 0x5A) % (maxVal + 1));
  });
  std::vector<uint8_t> serial(static_cast<size_t>(512) * 512 * 2 + 64);
  uint32_t serialN = encodeFrame(src.data(), 512, 512, 512, 14, serial.data(),
                                  static_cast<uint32_t>(serial.size()));
  REQUIRE(serialN > 0);

  ParallelFrameEncoder parallel(512, 512, /*threadCount=*/4);
  std::vector<uint8_t> parallelOut(static_cast<size_t>(512) * 512 * 2 + 64);
  uint32_t parallelN = parallel.encode(src.data(), 512, 14, parallelOut.data(),
                                        static_cast<uint32_t>(parallelOut.size()));
  REQUIRE(parallelN == serialN);
  CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));

  std::vector<uint16_t> decoded(src.size());
  REQUIRE(decodeFrame(parallelOut.data(), parallelN, decoded.data(), 512, 512, 512, 14));
  CHECK(decoded == src);
}

TEST_CASE("ParallelFrameEncoder byte-identical output across varied dimensions and content (merge boundary coverage)") {
  // Different width/height/content per case produces different per-band bit
  // counts and therefore different sub-byte phase offsets at each band
  // boundary -- a merge bug at a specific phase would very likely surface
  // as a mismatch in at least one of these varied cases.
  struct Case { uint32_t width, height, bitDepth; };
  const Case cases[] = {
    {64, 64, 16}, {63, 65, 12}, {200, 300, 14}, {129, 129, 10}, {257, 64, 16},
  };
  for (const auto& c : cases) {
    auto src = makeFrame(c.width, c.height, c.bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
      return static_cast<uint16_t>(((x * 13 + y * 29 + x * y) ^ 0x33) % (maxVal + 1));
    });
    std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t serialN = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                                    serial.data(), static_cast<uint32_t>(serial.size()));
    REQUIRE(serialN > 0);

    ParallelFrameEncoder parallel(c.width, c.height, /*threadCount=*/4);
    std::vector<uint8_t> parallelOut(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t parallelN = parallel.encode(src.data(), c.width, c.bitDepth, parallelOut.data(),
                                          static_cast<uint32_t>(parallelOut.size()));
    REQUIRE(parallelN == serialN);
    CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));
  }
}

TEST_CASE("ParallelFrameEncoder returns 0 (caller falls back) when the merged output doesn't fit outCapacity") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return maxVal; });
  ParallelFrameEncoder enc(64, 64, /*threadCount=*/4);
  std::vector<uint8_t> tiny(4);
  uint32_t n = enc.encode(src.data(), 64, 16, tiny.data(), static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);
}

TEST_CASE("ParallelFrameEncoder handles large residuals with band-local overflow recovery") {
  // High-variance content where sampled positions are small but many unsampled
  // pixels have large residuals. The per-band buffers must be large enough to
  // handle this variance without overflow. This test verifies the encoder
  // succeeds and produces byte-for-byte identical output to the serial version
  // even when individual bands have high-variance content.
  const uint32_t width = 16, height = 16;
  auto src = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    if (x % 4 == 0 && y % 4 == 0) return static_cast<uint16_t>(maxVal / 2);
    if (y < 4) return (x % 2 == 0) ? static_cast<uint16_t>(0) : maxVal;
    return static_cast<uint16_t>(maxVal / 2);
  });
  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t serialN = encodeFrame(src.data(), width, height, width, 16, serial.data(),
                                  static_cast<uint32_t>(serial.size()));
  REQUIRE(serialN > 0);

  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> parallelOut(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = enc.encode(src.data(), width, 16, parallelOut.data(),
                           static_cast<uint32_t>(parallelOut.size()));
  REQUIRE(n > 0);
  CHECK(n == serialN);
  CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));
}
