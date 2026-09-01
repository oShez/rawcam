#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/bit_depth.h"
#include "rawcam/rawv_codec.h"
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <future>
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

static int32_t medRef(int32_t l, int32_t u, int32_t ul) {
  int32_t lin = l + u - ul, lo = std::min(l, u), hi = std::max(l, u);
  return std::max(lo, std::min(lin, hi));
}
static uint32_t zzRef(int32_t v) {
  return (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31);
}

TEST_CASE("computeInteriorResidualsRow matches scalar predictor+zigzag (vectorized path)") {
  // Widths chosen to exercise the 4-lane body AND a scalar tail of every size
  // 0..3: interior length = width-2, so widths 6,7,8,9 give tails 0,1,2,3.
  const uint32_t widths[] = {6, 7, 8, 9, 33, 64, 100};
  for (uint32_t width : widths) {
    const uint32_t height = 8, bitDepth = 14;
    std::srand(9876 + width);
    auto src = makeFrame(width, height, bitDepth, [](uint32_t, uint32_t, uint16_t maxVal) {
      return static_cast<uint16_t>(std::rand() % (maxVal + 1));
    });
    for (uint32_t y = 2; y < height; y++) {  // interior rows only (y >= 2)
      std::vector<uint32_t> got(width, 0xDEADBEEFu);
      computeInteriorResidualsRow(src.data(), y, width, 2, width, got.data());
      for (uint32_t x = 2; x < width; x++) {
        int32_t p = medRef(src[y * width + x - 2], src[(y - 2) * width + x], src[(y - 2) * width + x - 2]);
        uint32_t want = zzRef(static_cast<int32_t>(src[y * width + x]) - p);
        CHECK(got[x] == want);
      }
    }
  }
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

TEST_CASE("default-constructed ParallelFrameEncoder matches serial encodeFrame byte-for-byte") {
  const uint32_t width = 128, height = 96, bitDepth = 12;
  auto src = makeFrame(width, height, bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 3 + y * 5) * 11) % (maxVal + 1));
  });

  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t sn = encodeFrame(src.data(), width, height, width, bitDepth,
                            serial.data(), static_cast<uint32_t>(serial.size()));
  REQUIRE(sn > 0);

  // threadCount = 0 -> exercises the new default sizing path (falls back to
  // min(hw,4) on this host since sysfs topology paths don't exist off-device).
  ParallelFrameEncoder enc(width, height, /*threadCount=*/0);
  std::vector<uint8_t> par(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t pn = enc.encode(src.data(), width /*rowStrideSamples*/, bitDepth,
                           par.data(), static_cast<uint32_t>(par.size()));
  REQUIRE(pn == sn);
  CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
}

TEST_CASE("ParallelFrameEncoder SIMD path is byte-identical to encodeFrame across the full matrix") {
  struct Case { uint32_t width, height, bitDepth; };
  // Includes tiny widths (1-9: interior empty or a pure tail) and non-lane-multiple
  // widths, heights that exercise y<2 rows and multi-band splits.
  const Case cases[] = {
    {1, 8, 14}, {2, 8, 14}, {3, 8, 12}, {5, 7, 10}, {8, 8, 16}, {9, 9, 14},
    {33, 40, 12}, {64, 64, 16}, {100, 130, 14}, {257, 64, 10},
  };
  const uint32_t threadCounts[] = {1, 2, 5};
  using Gen = uint16_t (*)(uint32_t, uint32_t, uint16_t);
  const Gen gens[] = {
    [](uint32_t, uint32_t, uint16_t) -> uint16_t { return 0; },                        // all-min
    [](uint32_t, uint32_t, uint16_t m) -> uint16_t { return m; },                      // all-max/saturation
    [](uint32_t x, uint32_t y, uint16_t m) -> uint16_t { return static_cast<uint16_t>((x + y) % (m + 1)); }, // diag gradient
    [](uint32_t x, uint32_t y, uint16_t m) -> uint16_t { return ((x ^ y) & 1) ? m : static_cast<uint16_t>(0); }, // salt-and-pepper
  };
  for (const auto& c : cases) {
    for (Gen gen : gens) {
      auto src = makeFrame(c.width, c.height, c.bitDepth, gen);
      std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
      uint32_t sn = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                                serial.data(), static_cast<uint32_t>(serial.size()));
      REQUIRE(sn > 0);
      for (uint32_t tc : threadCounts) {
        ParallelFrameEncoder enc(c.width, c.height, tc);
        std::vector<uint8_t> par(static_cast<size_t>(c.width) * c.height * 2 + 64);
        uint32_t pn = enc.encode(src.data(), c.width, c.bitDepth, par.data(),
                                 static_cast<uint32_t>(par.size()));
        REQUIRE(pn == sn);
        CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
        std::vector<uint16_t> dec(src.size());
        REQUIRE(decodeFrame(par.data(), pn, dec.data(), c.width, c.height, c.width, c.bitDepth));
        CHECK(dec == src);
      }
    }
  }
}

TEST_CASE("Rice q=0 fast path: encoder stays byte-identical to encodeFrame and round-trips (fast-path stress)") {
  struct Case { uint32_t width, height, bitDepth; };
  const Case cases[] = {
    {1, 8, 14}, {2, 8, 14}, {3, 8, 12}, {8, 8, 16}, {9, 9, 14},
    {33, 40, 12}, {64, 64, 16}, {100, 130, 14}, {257, 64, 10},
  };
  const uint32_t threadCounts[] = {1, 2, 5};
  // Mostly-flat frame (drives q=0 for nearly every pixel) with sparse large
  // spikes every 101th pixel (forces q>=32 and the multi-chunk drain), so a
  // single frame exercises both the fast path and the slow path.
  auto gen = [](uint32_t x, uint32_t y, uint16_t maxVal) -> uint16_t {
    uint32_t idx = x * 131u + y * 17u;
    if (idx % 101u == 0) return maxVal;                 // spike
    return static_cast<uint16_t>(maxVal / 2 + ((idx) % 3u));  // near-flat
  };
  for (const auto& c : cases) {
    auto src = makeFrame(c.width, c.height, c.bitDepth, gen);
    std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t sn = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                              serial.data(), static_cast<uint32_t>(serial.size()));
    REQUIRE(sn > 0);
    for (uint32_t tc : threadCounts) {
      ParallelFrameEncoder enc(c.width, c.height, tc);
      std::vector<uint8_t> par(static_cast<size_t>(c.width) * c.height * 2 + 64);
      uint32_t pn = enc.encode(src.data(), c.width, c.bitDepth, par.data(),
                               static_cast<uint32_t>(par.size()));
      REQUIRE(pn == sn);
      CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
      std::vector<uint16_t> dec(src.size());
      REQUIRE(decodeFrame(par.data(), pn, dec.data(), c.width, c.height, c.width, c.bitDepth));
      CHECK(dec == src);
    }
  }
}

TEST_CASE("worstCaseRiceRowBytes is a conservative upper bound on a packed row") {
  using rawcam::worstCaseRiceRowBytes;
  // k=9, 14-bit: qExp = 15-9 = 6 -> maxQ=64, codeword <= 74 bits/sample.
  CHECK(worstCaseRiceRowBytes(8, 14, 9) == (8ull * (64 + 1 + 9) + 7) / 8);      // 74
  CHECK(worstCaseRiceRowBytes(4096, 14, 9) == (4096ull * 74 + 7) / 8);          // 37888
  // k >= bitDepth+1 -> qExp clamps to 0 -> maxQ=1 -> codeword <= (k+2) bits.
  CHECK(worstCaseRiceRowBytes(100, 14, 20) == (100ull * (1 + 1 + 20) + 7) / 8); // 275
  // Smaller k => strictly larger (or equal) bound (monotonic).
  CHECK(worstCaseRiceRowBytes(64, 12, 3) >= worstCaseRiceRowBytes(64, 12, 9));
  // Small k on a wide frame -> a huge but finite bound (qExp=17), far larger than
  // any real band buffer, so the per-row check falls back to the checked path.
  CHECK(worstCaseRiceRowBytes(4096, 16, 0) == (4096ull * (131072 + 1) + 7) / 8);  // 67109376
  CHECK(worstCaseRiceRowBytes(4096, 16, 0) > 4096ull * 3072 * 2);  // > full raw frame size
  // The UINT64_MAX saturation guard triggers only for absurd bit depths (defensive).
  CHECK(worstCaseRiceRowBytes(4096, 60, 0) == UINT64_MAX);
}

TEST_CASE("ParallelFrameEncoder returns 0 (caller falls back) when the merged output doesn't fit outCapacity") {
  auto src = makeFrame(64, 64, 16, [](uint32_t, uint32_t, uint16_t maxVal) { return maxVal; });
  ParallelFrameEncoder enc(64, 64, /*threadCount=*/4);
  std::vector<uint8_t> tiny(4);
  uint32_t n = enc.encode(src.data(), 64, 16, tiny.data(), static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);
}

TEST_CASE("Tier2: encoder still fails whole-frame on overflow when early rows took the fast path") {
  const uint32_t width = 96, height = 96, bitDepth = 16;
  // Incompressible-ish content so real output far exceeds the tight buffer below.
  auto src = makeFrame(width, height, bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 2654435761u) ^ (y * 40503u)) & maxVal);
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> tiny(width * height / 4);  // far too small -> must overflow
  uint32_t n = enc.encode(src.data(), width, bitDepth, tiny.data(),
                          static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);  // whole-frame failure, no partial/corrupt result
}

TEST_CASE("ParallelFrameEncoder fails the whole frame (not a partial/corrupt result) when one band's content overflows its local buffer") {
  // Rows with y%4 in {1,3} are never read by k-selection (not a sampled
  // point -- needs y%4==0; not an up/upleft reference -- needs y%4==2; not
  // a left reference -- needs the row itself to be sampled, y%4==0). Making
  // ONLY those rows adversarial (alternating 0/maxVal) while everything
  // else stays flat guarantees k-selection sees zero contamination (k stays
  // near 0), while band 0 (which contains y=1 and y=3 for a 16-row/4-band
  // split) genuinely risks overflowing its local buffer under k=0's large
  // per-pixel unary codewords for near-maxVal residuals.
  const uint32_t width = 16, height = 16;
  auto src = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    if (y % 4 == 1 || y % 4 == 3) return (x % 2 == 0) ? static_cast<uint16_t>(0) : maxVal;
    return static_cast<uint16_t>(maxVal / 2);
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  // `out` is sized to width*height*2+64 -- comfortably large enough that a
  // MERGE/outCapacity failure can't happen here; the only way this test's
  // `n == 0` assertion can pass is a genuine per-band local-buffer overflow.
  // If you ever shrink `out`, re-verify that invariant still holds.
  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t n = enc.encode(src.data(), width, 16, out.data(), static_cast<uint32_t>(out.size()));
  CHECK(n == 0);
}

TEST_CASE("ParallelFrameEncoder correctly handles multiple frames reused on one instance, including overflow-then-recover (regression: stale jobOverflowed_/bandBits_ state)") {
  // Every other ParallelFrameEncoder test constructs a fresh instance per
  // encode() call. This test pins the stateful, concurrency-sensitive reuse
  // path instead: one instance, three different frames, in sequence --
  // specifically including a call that overflows immediately followed by a
  // normal call, to pin that jobOverflowed_ (and each band's bandBits_) get
  // reset between generations rather than leaking stale state from the
  // previous encode() into the next one.
  const uint32_t width = 16, height = 16;
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);

  auto checkNormalFrame = [&](const std::vector<uint16_t>& src) {
    uint32_t serialN = encodeFrame(src.data(), width, height, width, 16, serial.data(),
                                    static_cast<uint32_t>(serial.size()));
    REQUIRE(serialN > 0);
    uint32_t n = enc.encode(src.data(), width, 16, out.data(), static_cast<uint32_t>(out.size()));
    REQUIRE(n == serialN);
    CHECK(std::equal(serial.begin(), serial.begin() + serialN, out.begin()));
  };

  // Frame 1: normal content.
  auto frame1 = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  });
  checkNormalFrame(frame1);

  // Frame 2: deliberately overflows band 0's local buffer -- same adversarial
  // pattern as the dedicated overflow test above (rows y%4 in {1,3} are
  // invisible to k-selection, so k stays near 0 while band 0's real content
  // is near-maxVal noise on those rows).
  auto frame2 = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    if (y % 4 == 1 || y % 4 == 3) return (x % 2 == 0) ? static_cast<uint16_t>(0) : maxVal;
    return static_cast<uint16_t>(maxVal / 2);
  });
  uint32_t n2 = enc.encode(frame2.data(), width, 16, out.data(), static_cast<uint32_t>(out.size()));
  CHECK(n2 == 0);

  // Frame 3: normal content again, immediately after the overflow, on the
  // SAME instance -- this is the regression pin. If jobOverflowed_ (or any
  // other per-band state) weren't reset for the new generation, this call
  // would incorrectly fail or produce corrupt output even though frame 3's
  // content alone doesn't overflow anything.
  auto frame3 = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 7 + y * 19) ^ 0x22) % (maxVal + 1));
  });
  checkNormalFrame(frame3);
}

TEST_CASE("ParallelFrameEncoder handles last-band capacity correctly (regression: height % threadCount >= 2)") {
  // Regression test for buffer capacity fix. threadCount=8, height=15,
  // width=32 is chosen specifically because it DISCRIMINATES between the
  // old (buggy) and new (fixed) capacity formula for this content -- an
  // earlier version of this test (threadCount=4) didn't: its content landed
  // on a small enough Rice k that every band's real bit usage stayed well
  // under BOTH formulas, so it passed either way and didn't actually guard
  // against a regression.
  //
  // With threadCount=8: the last band absorbs floor(15/8)+(15%8) = 1+7 = 8
  // rows. Old formula (ceil(height/threadCount)*width*4 + 64):
  // ceil(15/8)*32*4+64 = 2*128+64 = 320 bytes -- under-provisions the last
  // band, which needs roughly 8 rows * 32px * ~12 bits/px / 8 =~ 384 bytes
  // for this content's Rice k, causing the OLD code to overflow that band's
  // local buffer and fail. New formula ((floor(h/tc)+tc-1)*width*4 + 64):
  // (1+7)*32*4+64 = 1024+64 = 1088 bytes -- comfortably covers it. This was
  // confirmed empirically against the current (fixed) code, not just from
  // the arithmetic above.
  const uint32_t width = 32, height = 15;
  auto src = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x7A) % (maxVal + 1));
  });
  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t serialN = encodeFrame(src.data(), width, height, width, 16, serial.data(),
                                  static_cast<uint32_t>(serial.size()));
  REQUIRE(serialN > 0);

  ParallelFrameEncoder enc(width, height, /*threadCount=*/8);
  std::vector<uint8_t> parallelOut(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t parallelN = enc.encode(src.data(), width, 16, parallelOut.data(),
                                   static_cast<uint32_t>(parallelOut.size()));
  REQUIRE(parallelN == serialN);
  CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));
}

TEST_CASE("ParallelFrameEncoder computeBands()+mergeSlot() produce byte-identical output to encode(), called sequentially across 3 frames") {
  // Pins that the async split behaves identically to the old synchronous
  // encode() when used the simplest way: compute, then immediately merge,
  // one frame at a time, never overlapping two in-flight frames. This is
  // exactly the pattern encode() itself now uses internally.
  const uint32_t width = 64, height = 64;
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  std::vector<uint8_t> split(static_cast<size_t>(width) * height * 2 + 64);

  auto checkFrame = [&](const std::vector<uint16_t>& src) {
    uint32_t serialN = encodeFrame(src.data(), width, height, width, 16, serial.data(),
                                    static_cast<uint32_t>(serial.size()));
    REQUIRE(serialN > 0);
    uint32_t slot = enc.computeBands(src.data(), width, 16);
    uint32_t splitN = enc.mergeSlot(slot, split.data(), static_cast<uint32_t>(split.size()));
    REQUIRE(splitN == serialN);
    CHECK(std::equal(serial.begin(), serial.begin() + serialN, split.begin()));
  };

  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  }));
  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 7 + y * 19) ^ 0x22) % (maxVal + 1));
  }));
  checkFrame(makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 31 + y * 3) ^ 0x33) % (maxVal + 1));
  }));
}

TEST_CASE("ParallelFrameEncoder computeBands() allows 2 outstanding unmerged slots before blocking") {
  // The pipeline design relies on double-buffering: Compute can finish frame
  // N+1's bands while Finish hasn't yet merged frame N's. Two back-to-back
  // computeBands() calls with NEITHER merged yet must both return promptly
  // (not deadlock), using two distinct slots.
  const uint32_t width = 64, height = 64;
  auto frame = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);

  uint32_t slot0 = enc.computeBands(frame.data(), width, 16);
  uint32_t slot1 = enc.computeBands(frame.data(), width, 16);
  CHECK(slot0 != slot1);

  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  CHECK(enc.mergeSlot(slot0, out.data(), static_cast<uint32_t>(out.size())) > 0);
  CHECK(enc.mergeSlot(slot1, out.data(), static_cast<uint32_t>(out.size())) > 0);
}

TEST_CASE("ParallelFrameEncoder computeBands() blocks when both slots are busy, unblocks after mergeSlot()") {
  // Backpressure: a 3rd computeBands() call with both prior slots still
  // unmerged must BLOCK (not silently drop or corrupt) until mergeSlot()
  // frees one. Run the 3rd call on a background thread with a bounded
  // std::future wait so a broken implementation fails this test instead of
  // hanging it forever.
  const uint32_t width = 64, height = 64;
  auto frame = makeFrame(width, height, 16, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 13 + y * 29) ^ 0x11) % (maxVal + 1));
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);

  uint32_t slot0 = enc.computeBands(frame.data(), width, 16);
  uint32_t slot1 = enc.computeBands(frame.data(), width, 16);

  auto fut = std::async(std::launch::async,
                         [&] { return enc.computeBands(frame.data(), width, 16); });
  CHECK(fut.wait_for(std::chrono::milliseconds(200)) == std::future_status::timeout);

  std::vector<uint8_t> out(static_cast<size_t>(width) * height * 2 + 64);
  CHECK(enc.mergeSlot(slot0, out.data(), static_cast<uint32_t>(out.size())) > 0);

  CHECK(fut.wait_for(std::chrono::milliseconds(1000)) == std::future_status::ready);
  uint32_t slot2 = fut.get();
  CHECK(slot2 == slot0);  // the freed slot gets reused

  CHECK(enc.mergeSlot(slot1, out.data(), static_cast<uint32_t>(out.size())) > 0);
  CHECK(enc.mergeSlot(slot2, out.data(), static_cast<uint32_t>(out.size())) > 0);
}

TEST_CASE("selectWorkerCores: 3-cluster big.LITTLE returns prime+performance only") {
  // Snapdragon-8-Gen-3-shaped: 1 prime (3.3GHz), 5 performance (3.2GHz),
  // 2 efficiency (2.3GHz). Indices 0..7. Only the 2.3GHz pair is excluded.
  std::vector<long> freqs = {3300000, 3200000, 3200000, 3200000,
                             3200000, 3200000, 2300000, 2300000};
  std::vector<int> got = selectWorkerCores(freqs);
  std::vector<int> want = {0, 1, 2, 3, 4, 5};
  CHECK(got == want);
}

TEST_CASE("selectWorkerCores: 2-cluster split excludes the lower cluster") {
  std::vector<long> freqs = {2800000, 2800000, 2800000, 2800000,
                             1800000, 1800000, 1800000, 1800000};
  std::vector<int> got = selectWorkerCores(freqs);
  std::vector<int> want = {0, 1, 2, 3};
  CHECK(got == want);
}

TEST_CASE("selectWorkerCores: uniform frequencies return empty (no confident split)") {
  std::vector<long> freqs = {2000000, 2000000, 2000000, 2000000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: any unreadable core (-1) invalidates the whole set") {
  std::vector<long> freqs = {3200000, 3200000, -1, 2300000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: single core returns empty") {
  std::vector<long> freqs = {2000000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: empty input returns empty") {
  std::vector<long> freqs;
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("workerThreadCount: empty cluster falls back to defaultCap") {
  CHECK(workerThreadCount(0, 4) == 4u);
  CHECK(workerThreadCount(0, 1) == 1u);
}

TEST_CASE("workerThreadCount: 6-core cluster gives 5 (size-1 margin) above the floor") {
  // This device: 6 big+mid cores -> 5 workers, one core left free.
  CHECK(workerThreadCount(6, 4) == 5u);
}

TEST_CASE("workerThreadCount: regression floor -- never fewer than defaultCap") {
  // A hypothetical 3-big-core device: size-1 = 2, but floor keeps it at 4.
  CHECK(workerThreadCount(3, 4) == 4u);
  // 2-big-core: size-1 = 1, floor keeps it at 4.
  CHECK(workerThreadCount(2, 4) == 4u);
}

TEST_CASE("workerThreadCount: single big core clamps margin to 1, then floor applies") {
  // clusterCoreCount 1 -> max(1, 0) = 1, floored at defaultCap.
  CHECK(workerThreadCount(1, 4) == 4u);
  CHECK(workerThreadCount(1, 1) == 1u);
}

// A gradient plus small noise, NOT uniform random. Uniform 14-bit noise is
// near-incompressible: Rice-coded it can exceed 16 bits/sample, so encodeFrame
// would return 0 for insufficient outCapacity and these tests would fail on
// capacity rather than on the behaviour under test. This shape also exercises
// the q == 0 fast path that dominates real frames.
static std::vector<uint16_t> testFrame(uint32_t w, uint32_t h) {
  std::vector<uint16_t> v(w * h);
  for (uint32_t y = 0; y < h; y++)
    for (uint32_t x = 0; x < w; x++)
      v[y * w + x] = (uint16_t)(((x * 3 + y * 5) % 512) * 24 + ((x * 7919 + y) % 17));
  return v;
}

TEST_CASE("encoding with a shift round-trips to the reduced samples") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  const uint32_t shift = 2, newWhite = rawcam::reducedWhiteLevel(16383, shift);
  std::vector<uint16_t> expected(src.size());
  for (size_t i = 0; i < src.size(); i++)
    expected[i] = rawcam::reduceSample(src[i], shift, newWhite);

  std::vector<uint8_t> enc(src.size() * 4 + 4096);
  uint32_t n = rawcam::encodeFrame(src.data(), w, h, stride, 12, enc.data(),
                                   (uint32_t)enc.size(), shift, newWhite);
  REQUIRE(n > 0);

  std::vector<uint16_t> back(src.size());
  REQUIRE(rawcam::decodeFrame(enc.data(), n, back.data(), w, h, stride, 12));
  CHECK(back == expected);
}

TEST_CASE("a shift genuinely shrinks the encoded frame") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  std::vector<uint8_t> a(src.size() * 4 + 4096), b(src.size() * 4 + 4096);
  uint32_t full = rawcam::encodeFrame(src.data(), w, h, stride, 14, a.data(), (uint32_t)a.size());
  uint32_t red = rawcam::encodeFrame(src.data(), w, h, stride, 12, b.data(), (uint32_t)b.size(),
                                     2, rawcam::reducedWhiteLevel(16383, 2));
  REQUIRE(full > 0);
  REQUIRE(red > 0);
  CHECK(red < full);
}

TEST_CASE("shift 0 is bit-identical to the pre-existing encoder") {
  const uint32_t w = 64, h = 16, stride = w;
  std::vector<uint16_t> src = testFrame(w, h);

  std::vector<uint8_t> a(src.size() * 4 + 4096), b(src.size() * 4 + 4096);
  uint32_t x = rawcam::encodeFrame(src.data(), w, h, stride, 14, a.data(), (uint32_t)a.size());
  uint32_t y = rawcam::encodeFrame(src.data(), w, h, stride, 14, b.data(), (uint32_t)b.size(), 0, 16383);
  REQUIRE(x > 0);
  CHECK(x == y);
  CHECK(std::equal(a.begin(), a.begin() + x, b.begin()));
}

// The spec requires a timing gate on top of bit-exactness. Be precise about what
// each half of the evidence actually proves, because they prove different things.
//
//   STRUCTURE, not the stopwatch, is what guarantees Native pays nothing. The
//   reduction is an `if constexpr (Reduce)` on a template parameter, so
//   Reduce=false does not contain the reduction to skip -- there is no branch to
//   time. (An earlier version of this case timed shift=0 against no-shift: both
//   resolve to the SAME encodeFrameImpl<false>, so it was one binary measured
//   twice and could not fail. A test that cannot fail is a defect; this is its
//   replacement.)
//
//   MEASUREMENT covers the other direction: that the REDUCED path -- a genuinely
//   separate instantiation, with a vectorized reduction spliced into the NEON
//   interior loop -- has not become pathologically expensive relative to Native.
//   A lower record depth is bought to gain thermal headroom, and an encoder that
//   cost far more CPU per reduced frame would spend that headroom on itself.
//
// Measured through ParallelFrameEncoder because that, not the free encodeFrame,
// is the encoder the app records with, and it is the only path that runs
// computeAndPackBandImpl and the vectorized computeInteriorResidualsRowImpl at
// all. threadCount is fixed so the band split is deterministic.
//
// HOST CAVEAT, stated plainly so nobody reads more into a green run than is
// there. On this host the NEON path is the ARM_NEON_2_SSE shim, which emulates
// the register-operand vshlq_s32 lane-by-lane; on arm64 it is a single SSHL.
// That emulation dominates, and it makes the host ratio not merely loose but
// INVERTED: measured on this machine, the correct vectorized reduction runs
// ~2.2x Native, while deliberately replacing it with the scalar fallback (the
// regression this case would most like to catch) measures ~1.1x -- i.e. faster.
// So the threshold below is set to be non-flaky, not to discriminate: on the
// host this case catches only a gross blow-up. It becomes a real gate when
// core/ is built for arm64, where the shim is gone, and the authoritative
// reduced-vs-Native cost comparison is the on-device A/B, not this stopwatch.
TEST_CASE("reduced-depth encoding is not pathologically slower than Native") {
  const uint32_t w = 512, h = 256, shift = 2;
  const uint32_t newWhite = rawcam::reducedWhiteLevel(16383, shift);
  std::vector<uint16_t> src = testFrame(w, h);
  std::vector<uint8_t> out(src.size() * 4 + 4096);
  ParallelFrameEncoder enc(w, h, /*threadCount=*/4);

  auto timeIt = [&](bool reduced) {
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < 20; i++) {
      if (reduced)
        enc.encode(src.data(), w, 12, out.data(), (uint32_t)out.size(), shift, newWhite);
      else
        enc.encode(src.data(), w, 14, out.data(), (uint32_t)out.size());
    }
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  };
  // Best-of-N, not a single sample: this encoder wakes four worker threads per
  // frame, so any one timing can absorb an unrelated scheduling stall. The
  // minimum is the run least disturbed by the rest of the machine, which is the
  // honest estimate of what the code costs. Without it the two arms swing ~40%
  // run to run and the ratio is dominated by scheduling, not by the encoder.
  //
  // INTERLEAVED, not all-of-one-then-all-of-the-other. This case is also run on
  // the phone -- the host shim inverts the ratio (see the note above), so the
  // arm64 run is the only meaningful one -- and there several hundred encodes is
  // long enough for DVFS and temperature to drift. Measuring every Native sample
  // before every reduced sample silently credits whichever arm ran under the
  // better clocks, biasing in favour of whichever goes LAST. That is the same
  // confound this feature's own device A/B protocol forbids ("order 14, 12, 14,
  // 12 ... not as a single sequential pair"), and it applies here for the same
  // reason. Alternating gives both arms the same thermal and clock trajectory.
  timeIt(false);  // warm caches, clocks and the worker threads; discard
  timeIt(true);
  double nativeSecs = 1e9, reducedSecs = 1e9;
  for (int round = 0; round < 5; round++) {
    nativeSecs = std::min(nativeSecs, timeIt(false));
    reducedSecs = std::min(reducedSecs, timeIt(true));
  }
  CHECK(reducedSecs < nativeSecs * 3.0);
}

// testFrame tops out at 511*24 + 16 = 12280, which at shift 2 reduces to 3070 --
// comfortably under newWhite, so it never exercises reduceSample's `r > newWhite`
// clamp or its vectorized twin (the vminq_s32 in reduce4). Stamp a block of
// at-and-above-whiteLevel samples in so both clamps are live. A real sensor
// produces exactly this at saturation, and it is the case the depth contract
// turns on: reduceSample(16383, 2, 4095) would round to 4096 unclamped, which
// needs 13 bits and would break both the bitDepth-12 promise and the
// worstCaseRiceRowBytes bound the fast pack path relies on.
//
// The block sits at x in [100,140), y in [100,120): interior (x >= 2, y >= 2),
// and inside computeInteriorResidualsRow's 4-lane NEON body rather than its
// scalar tail (which for width 512 is only x = 510,511), so the clamped values
// reach reduce4 as all four of its operands -- actual, left, up and upleft.
static void stampSaturatedBlock(std::vector<uint16_t>& v, uint32_t w) {
  // A spread of values at and above whiteLevel=16383. All of them reduce to
  // >= 4096 before clamping, so all of them must come back as exactly 4095.
  const uint16_t sat[] = {16383, 16384, 16500, 20000, 40000, 65535};
  for (uint32_t y = 100; y < 120; y++)
    for (uint32_t x = 100; x < 140; x++)
      v[y * w + x] = sat[(x + y) % 6];
}

TEST_CASE("ParallelFrameEncoder with a shift is byte-identical to encodeFrame with the same shift") {
  // The real compressed capture path is ParallelFrameEncoder::computeBands(),
  // not the free encodeFrame() -- without this case the whole feature could be
  // a silent no-op on device while every other test here passed. 512x512 with
  // threadCount forced to 4 guarantees a real multi-band split, and every
  // interior row goes through computeInteriorResidualsRow's VECTORIZED
  // reduction while encodeFrame uses the scalar predictor for the same pixels,
  // so byte-identical output also pins the two against each other -- including,
  // thanks to the saturated block, their two independent clamps.
  const uint32_t w = 512, h = 512, shift = 2;
  const uint32_t newWhite = rawcam::reducedWhiteLevel(16383, shift);
  std::vector<uint16_t> src = testFrame(w, h);
  stampSaturatedBlock(src, w);

  std::vector<uint8_t> serial(static_cast<size_t>(w) * h * 4 + 4096);
  uint32_t serialN = encodeFrame(src.data(), w, h, w, 12, serial.data(),
                                  static_cast<uint32_t>(serial.size()), shift, newWhite);
  REQUIRE(serialN > 0);

  ParallelFrameEncoder parallel(w, h, /*threadCount=*/4);
  std::vector<uint8_t> parallelOut(static_cast<size_t>(w) * h * 4 + 4096);
  uint32_t parallelN = parallel.encode(src.data(), w, 12, parallelOut.data(),
                                        static_cast<uint32_t>(parallelOut.size()), shift, newWhite);
  REQUIRE(parallelN == serialN);
  CHECK(std::equal(serial.begin(), serial.begin() + serialN, parallelOut.begin()));

  std::vector<uint16_t> expected(src.size());
  for (size_t i = 0; i < src.size(); i++)
    expected[i] = rawcam::reduceSample(src[i], shift, newWhite);
  // Guard the guard: if testFrame or stampSaturatedBlock ever changed such that
  // nothing saturates, the clamp would quietly stop being covered again.
  REQUIRE(expected[105 * w + 105] == newWhite);
  std::vector<uint16_t> decoded(src.size());
  REQUIRE(decodeFrame(parallelOut.data(), parallelN, decoded.data(), w, h, w, 12));
  CHECK(decoded == expected);
}

TEST_CASE("ParallelFrameEncoder at shift 0 is unchanged by the new parameters") {
  // The Native default must stay bit-identical through the parallel path too.
  const uint32_t w = 512, h = 512;
  std::vector<uint16_t> src = testFrame(w, h);

  ParallelFrameEncoder parallel(w, h, /*threadCount=*/4);
  std::vector<uint8_t> a(static_cast<size_t>(w) * h * 4 + 4096), b(a.size());
  uint32_t bare = parallel.encode(src.data(), w, 14, a.data(), (uint32_t)a.size());
  uint32_t explicitZero = parallel.encode(src.data(), w, 14, b.data(), (uint32_t)b.size(), 0, 16383);
  REQUIRE(bare > 0);
  CHECK(bare == explicitZero);
  CHECK(std::equal(a.begin(), a.begin() + bare, b.begin()));
}

// Every other bit-depth case here uses one geometry (64x16, 512x256, 512x512) at
// a single shift of 2. The reduction rides a 4-lane NEON interior loop and a
// predictor with a 2-pixel stride, so the breakage it could plausibly hide lives
// at sizes those never reach: width below the vector width, width or height
// below the predictor's reach, odd and prime widths, and a white level that is
// not 2^n - 1. Sweeping them found no defect -- this case exists to keep it that
// way, since a regression here corrupts samples silently rather than loudly.
//
// Deliberately does NOT require the parallel encoder to succeed: its per-band
// buffer is sized at 4 bytes/sample, which incompressible noise beats on a
// 1-pixel-wide frame, so it correctly returns 0 (caller stores uncompressed) for
// a few of these. That is pre-existing and unrelated to bit depth -- it happens
// at shift 0 too. When it DOES encode, its bytes must match the serial encoder's.
TEST_CASE("reduction is exact across tiny, odd and non-power-of-two geometry") {
  std::srand(20260901);
  const uint32_t widths[] = {1, 2, 3, 5, 7, 9, 13, 33, 64, 127};
  const uint32_t heights[] = {1, 2, 3, 5, 16};
  const uint32_t whites[] = {255, 1023, 4095, 16383, 4000};  // 4000 is not 2^n - 1
  const uint32_t requests[] = {0, 8, 10, 12, 14};

  for (uint32_t w : widths) {
    for (uint32_t h : heights) {
      for (uint32_t nativeWhite : whites) {
        for (uint32_t requested : requests) {
          const uint32_t shift = shiftForDepth(nativeWhite, requested);
          const uint32_t newWhite = shift ? reducedWhiteLevel(nativeWhite, shift) : 0;
          const uint32_t effWhite = shift ? (nativeWhite >> shift) : nativeWhite;
          const uint32_t depth = 32u - (uint32_t)__builtin_clz(effWhite);

          std::vector<uint16_t> src((size_t)w * h);
          for (auto& v : src) v = (uint16_t)(std::rand() % (int)(nativeWhite + 1));
          src[src.size() / 2] = (uint16_t)nativeWhite;  // force the clamp to fire

          std::vector<uint16_t> expected(src.size());
          for (size_t i = 0; i < src.size(); i++)
            expected[i] = shift ? reduceSample(src[i], shift, newWhite) : src[i];

          const uint32_t cap = (uint32_t)(src.size() * 4 + 8192);
          std::vector<uint8_t> enc(cap);
          uint32_t n = encodeFrame(src.data(), w, h, w, depth, enc.data(), cap,
                                   shift, newWhite);
          if (n == 0) continue;  // would not fit; the caller stores uncompressed

          std::vector<uint16_t> back(src.size());
          REQUIRE(decodeFrame(enc.data(), n, back.data(), w, h, w, depth));
          REQUIRE(back == expected);

          std::vector<uint8_t> par(cap);
          ParallelFrameEncoder pe(w, h, 4);
          uint32_t pn = pe.encode(src.data(), w, depth, par.data(), cap, shift, newWhite);
          if (pn != 0) {
            REQUIRE(pn == n);
            REQUIRE(std::equal(enc.begin(), enc.begin() + n, par.begin()));
          }
        }
      }
    }
  }
}
