#include "rawcam/preview.h"
#include <algorithm>
#include <cmath>

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

}  // namespace rawcam
