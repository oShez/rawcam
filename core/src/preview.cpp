#include "rawcam/preview.h"
#include <algorithm>
#include <cmath>

#include "rawcam/pack10.h"
#include "rawcam/rawv_codec.h"
#include "rawcam/rawv_reader.h"

namespace rawcam {
namespace {

// Which colour each position of a 2x2 quad carries, as indices into RGB.
// Order within a quad: top-left, top-right, bottom-left, bottom-right.
struct QuadLayout { int tl, tr, bl, br; };

QuadLayout layoutFor(Cfa cfa) {
  switch (cfa) {
    case Cfa::RGGB: return {0, 1, 1, 2};
    case Cfa::GRBG: return {1, 0, 2, 1};
    case Cfa::GBRG: return {1, 2, 0, 1};
    case Cfa::BGGR: return {2, 1, 1, 0};
  }
  return {0, 1, 1, 2};
}

// sRGB transfer function on a 0..1 linear value.
inline float srgbGamma(float v) {
  v = std::clamp(v, 0.0f, 1.0f);
  return v <= 0.0031308f ? v * 12.92f : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}

// Black-subtract and normalise one sample against its quadrant's black level.
inline float normalise(uint16_t sample, uint32_t black, float range) {
  const float v = (float)sample - (float)black;
  return v <= 0.0f ? 0.0f : v / range;
}

}  // namespace

bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, Cfa cfa,
                  const uint32_t blackLevel[4], uint32_t whiteLevel,
                  const float asShotNeutral[3], PreviewImage* out) {
  if (!raw16 || !out || !blackLevel || !asShotNeutral) return false;
  if (whiteLevel == 0 || width < 2 || height < 2) return false;
  if (rowStrideSamples < width) return false;

  const uint32_t ow = width / 2, oh = height / 2;
  const QuadLayout q = layoutFor(cfa);
  out->width = ow;
  out->height = oh;
  out->rgba.assign((size_t)ow * oh * 4, 255);

  // blackLevel is indexed by position within the quad, in sensor order, which
  // is the same order as QuadLayout's fields.
  const float range[4] = {
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[0]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[1]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[2]),
      std::max(1.0f, (float)whiteLevel - (float)blackLevel[3]),
  };

  for (uint32_t y = 0; y < oh; y++) {
    const uint16_t* r0 = raw16 + (size_t)(y * 2) * rowStrideSamples;
    const uint16_t* r1 = r0 + rowStrideSamples;
    uint8_t* dst = out->rgba.data() + (size_t)y * ow * 4;
    for (uint32_t x = 0; x < ow; x++) {
      const uint32_t sx = x * 2;
      float acc[3] = {0.0f, 0.0f, 0.0f};
      int count[3] = {0, 0, 0};
      acc[q.tl] += normalise(r0[sx],     blackLevel[0], range[0]); count[q.tl]++;
      acc[q.tr] += normalise(r0[sx + 1], blackLevel[1], range[1]); count[q.tr]++;
      acc[q.bl] += normalise(r1[sx],     blackLevel[2], range[2]); count[q.bl]++;
      acc[q.br] += normalise(r1[sx + 1], blackLevel[3], range[3]); count[q.br]++;
      for (int c = 0; c < 3; c++) {
        float v = count[c] > 1 ? acc[c] / (float)count[c] : acc[c];
        v *= (asShotNeutral[c] > 0.0f ? asShotNeutral[c] : 1.0f);
        dst[x * 4 + c] = (uint8_t)std::lround(std::clamp(srgbGamma(v), 0.0f, 1.0f) * 255.0f);
      }
      dst[x * 4 + 3] = 255;
    }
  }
  return true;
}


bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out) {
  if (!out || src.width == 0 || src.height == 0 || maxW == 0 || maxH == 0) return false;
  if (src.rgba.size() < (size_t)src.width * src.height * 4) return false;

  const double scale = std::min({1.0,
                                 (double)maxW / (double)src.width,
                                 (double)maxH / (double)src.height});
  const uint32_t ow = std::max(1u, (uint32_t)std::lround(src.width * scale));
  const uint32_t oh = std::max(1u, (uint32_t)std::lround(src.height * scale));

  out->width = ow;
  out->height = oh;
  out->rgba.assign((size_t)ow * oh * 4, 255);

  for (uint32_t y = 0; y < oh; y++) {
    // Half-open source span for this destination row. The last box always
    // reaches the final source row, so odd sizes lose nothing.
    const uint32_t y0 = (uint32_t)((uint64_t)y * src.height / oh);
    const uint32_t y1 = std::max(y0 + 1, (uint32_t)((uint64_t)(y + 1) * src.height / oh));
    for (uint32_t x = 0; x < ow; x++) {
      const uint32_t x0 = (uint32_t)((uint64_t)x * src.width / ow);
      const uint32_t x1 = std::max(x0 + 1, (uint32_t)((uint64_t)(x + 1) * src.width / ow));
      uint32_t sum[3] = {0, 0, 0};
      uint32_t n = 0;
      for (uint32_t sy = y0; sy < y1 && sy < src.height; sy++) {
        const uint8_t* row = src.rgba.data() + (size_t)sy * src.width * 4;
        for (uint32_t sx = x0; sx < x1 && sx < src.width; sx++) {
          sum[0] += row[sx * 4 + 0];
          sum[1] += row[sx * 4 + 1];
          sum[2] += row[sx * 4 + 2];
          n++;
        }
      }
      uint8_t* dst = out->rgba.data() + ((size_t)y * ow + x) * 4;
      for (int c = 0; c < 3; c++) dst[c] = n ? (uint8_t)(sum[c] / n) : 0;
      dst[3] = 255;
    }
  }
  return true;
}


bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH, PreviewImage* out) {
  if (!out || index >= reader.frameCount()) return false;
  const FileHeader& h = reader.header();
  if (h.whiteLevel == 0 || h.width < 2 || h.height < 2) return false;
  if (h.rowStrideBytes < h.width * 2) return false;

  const size_t pixelCount = (size_t)h.width * h.height;
  std::vector<uint8_t> payload(h.frameSizeBytes);
  FrameMeta meta{};
  if (!reader.readFrame(index, &meta, payload.data())) return false;

  // Mirrors exporter.cpp's exportFrame(): the mode picks the unpack, and a
  // CompressedPredictive frame with meta.compressed == 0 is the compressor's
  // stored fallback -- already plain RAW16, so it takes the Raw16 path.
  std::vector<uint16_t> unpacked;
  const uint16_t* raw16 = nullptr;
  const PackMode mode = (PackMode)h.packMode;
  // Packed10/12 unpack to a CONTIGUOUS plane, so their stride is the active
  // width; every other mode keeps the sensor's stride-padded rows.
  uint32_t strideSamples = h.width;

  if (mode == PackMode::Packed10) {
    unpacked.resize(pixelCount);
    unpack10(payload.data(), pixelCount, unpacked.data());
    raw16 = unpacked.data();
  } else if (mode == PackMode::Packed12) {
    unpacked.resize(pixelCount);
    unpack12(payload.data(), pixelCount, unpacked.data());
    raw16 = unpacked.data();
  } else if (mode == PackMode::CompressedPredictive && meta.compressed) {
    // The decode target must match the ORIGINAL, possibly stride-padded frame
    // layout the encoder used -- decodeFrame's predictor addresses samples via
    // rowStrideSamples, so it writes height * rowStrideSamples samples, not
    // pixelCount. Same sizing exporter.cpp uses for this mode.
    strideSamples = h.rowStrideBytes / 2;
    unpacked.resize(h.frameSizeBytes / 2);
    const uint32_t bitDepth = 32 - __builtin_clz(h.whiteLevel);
    if (!decodeFrame(payload.data(), meta.payloadBytes, unpacked.data(), h.width,
                     h.height, strideSamples, bitDepth)) {
      return false;  // corrupt/truncated compressed frame
    }
    raw16 = unpacked.data();
  } else {
    // Raw16 and the stored-fallback case. The plane arrives with the header's
    // row stride, which can be wider than the active width.
    raw16 = reinterpret_cast<const uint16_t*>(payload.data());
    strideSamples = h.rowStrideBytes / 2;
  }

  PreviewImage full;
  if (!developRaw16(raw16, h.width, h.height, strideSamples, (Cfa)h.cfa,
                    h.blackLevel, h.whiteLevel, h.asShotNeutral, &full)) {
    return false;
  }
  return downscaleTo(full, maxW, maxH, out);
}

}  // namespace rawcam
