#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cstdint>
#include <cstring>
#include <vector>

#include "rawcam/crop.h"
#include "rawcam/rawv_codec.h"

namespace {

// A frame whose every sample encodes its own (x, y) so any stride or origin
// slip shows up as a wrong VALUE, not just a wrong byte count.
std::vector<uint8_t> makeFrame(uint32_t w, uint32_t h, size_t rowBytes) {
  std::vector<uint8_t> buf(rowBytes * h, 0);
  for (uint32_t y = 0; y < h; y++) {
    auto* row = reinterpret_cast<uint16_t*>(buf.data() + (size_t)y * rowBytes);
    for (uint32_t x = 0; x < w; x++) row[x] = (uint16_t)(((y * 977u) ^ (x * 131u)) & 0x3FFF);
  }
  return buf;
}

}  // namespace

TEST_CASE("cropPlane16 de-strides the requested sub-rectangle") {
  const uint32_t fullW = 64, fullH = 32;
  const size_t rowBytes = (size_t)fullW * 2 + 16;  // padded stride, as a camera delivers
  const auto full = makeFrame(fullW, fullH, rowBytes);

  const uint32_t cx = 8, cy = 4, cw = 16, ch = 8;
  std::vector<uint8_t> out((size_t)cw * 2 * ch, 0xAB);
  rawcam::cropPlane16(full.data(), rowBytes, cx, cy, cw, ch, out.data());

  for (uint32_t y = 0; y < ch; y++) {
    const auto* srcRow = reinterpret_cast<const uint16_t*>(full.data() + (size_t)(cy + y) * rowBytes);
    const auto* dstRow = reinterpret_cast<const uint16_t*>(out.data() + (size_t)y * cw * 2);
    for (uint32_t x = 0; x < cw; x++) CHECK(dstRow[x] == srcRow[cx + x]);
  }
}

TEST_CASE("cropPlane16 output is tightly packed, no padding carried over") {
  const uint32_t fullW = 32, fullH = 16;
  const size_t rowBytes = (size_t)fullW * 2 + 64;  // a lot of padding
  const auto full = makeFrame(fullW, fullH, rowBytes);

  std::vector<uint8_t> out((size_t)8 * 2 * 4, 0);
  rawcam::cropPlane16(full.data(), rowBytes, 4, 2, 8, 4, out.data());

  // Row 1 of the output must start exactly 16 bytes in, not rowBytes in.
  const auto* dst = reinterpret_cast<const uint16_t*>(out.data());
  const auto* src1 = reinterpret_cast<const uint16_t*>(full.data() + (size_t)3 * rowBytes);
  CHECK(dst[8] == src1[4]);
}

TEST_CASE("THE 1x PIN: a full-frame crop of an unpadded frame is a plain copy") {
  // This is the regression guarantee that cannot be tested on device: you
  // cannot record the same live scene twice and get identical bytes. Here it
  // is exact.
  const uint32_t fullW = 40, fullH = 24;
  const size_t rowBytes = (size_t)fullW * 2;
  const auto full = makeFrame(fullW, fullH, rowBytes);

  std::vector<uint8_t> out(full.size(), 0);
  rawcam::cropPlane16(full.data(), rowBytes, 0, 0, fullW, fullH, out.data());
  CHECK(std::memcmp(out.data(), full.data(), full.size()) == 0);
}

TEST_CASE("cropBase16 offsets to the first cropped sample") {
  const uint32_t fullW = 32, fullH = 16;
  const size_t rowBytes = (size_t)fullW * 2 + 8;
  const auto full = makeFrame(fullW, fullH, rowBytes);

  const uint8_t* base = rawcam::cropBase16(full.data(), rowBytes, 6, 3);
  const auto* srcRow = reinterpret_cast<const uint16_t*>(full.data() + (size_t)3 * rowBytes);
  CHECK(*reinterpret_cast<const uint16_t*>(base) == srcRow[6]);
}

TEST_CASE("compressed encode over a cropped base equals encoding a standalone crop") {
  // The whole reason the compressed path gets its crop for free: the encoder's
  // predictAt() guards on BAND-LOCAL x and y, so it never reads outside the
  // crop rectangle. Encoding the sub-rect in place must therefore be
  // bit-identical to encoding the same pixels as a frame of their own.
  //
  // threadCount is FORCED to 4 (not left at the 0 default) because that guard
  // only does anything at a band BOUNDARY. hardware_concurrency() may report 1
  // in a sandboxed or CI environment, which would collapse the frame to a
  // single band and let a broken guard pass unnoticed -- the same trap
  // rawv_codec.h's constructor comment warns about.
  const uint32_t fullW = 128, fullH = 64;
  const size_t rowBytes = (size_t)fullW * 2 + 32;
  const auto full = makeFrame(fullW, fullH, rowBytes);

  const uint32_t cx = 16, cy = 8, cw = 64, ch = 32;
  const uint32_t bitDepth = 14;

  // (a) encode in place, over a cropped base pointer at the camera's stride
  std::vector<uint8_t> outA((size_t)cw * 2 * ch, 0);
  uint32_t nA = 0;
  {
    rawcam::ParallelFrameEncoder enc(cw, ch, /*threadCount=*/4);
    const uint8_t* base = rawcam::cropBase16(full.data(), rowBytes, cx, cy);
    uint32_t slot = enc.computeBands(reinterpret_cast<const uint16_t*>(base),
                                     (uint32_t)(rowBytes / 2), bitDepth);
    nA = enc.mergeSlot(slot, outA.data(), (uint32_t)outA.size());
  }

  // (b) de-stride first, then encode the contiguous crop as its own frame
  std::vector<uint8_t> tight((size_t)cw * 2 * ch, 0);
  rawcam::cropPlane16(full.data(), rowBytes, cx, cy, cw, ch, tight.data());
  std::vector<uint8_t> outB((size_t)cw * 2 * ch, 0);
  uint32_t nB = 0;
  {
    rawcam::ParallelFrameEncoder enc(cw, ch, /*threadCount=*/4);
    uint32_t slot = enc.computeBands(reinterpret_cast<const uint16_t*>(tight.data()), cw, bitDepth);
    nB = enc.mergeSlot(slot, outB.data(), (uint32_t)outB.size());
  }

  REQUIRE(nA > 0);
  CHECK(nA == nB);
  CHECK(std::memcmp(outA.data(), outB.data(), nA) == 0);
}
