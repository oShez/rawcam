#pragma once
#include <cstdint>
#include <vector>
#include "rawcam/rawv.h"

namespace rawcam {

// A developed preview frame: RGBA8, tightly packed, alpha always 255.
struct PreviewImage {
  std::vector<uint8_t> rgba;
  uint32_t width = 0;
  uint32_t height = 0;
};

// Develops an unpacked RAW16 Bayer plane into `out` at HALF dimensions, one RGB
// pixel per 2x2 CFA quad. `rowStrideSamples` is the plane's row pitch in uint16
// samples, which can exceed `width` -- the sensor delivers stride-padded rows,
// and the compressed codec's predictor addresses samples that way too.
// Returns false if whiteLevel is 0 or the dimensions are unusable.
bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, Cfa cfa,
                  const uint32_t blackLevel[4], uint32_t whiteLevel,
                  const float asShotNeutral[3], PreviewImage* out);

// Box-averages `src` down to fit within maxW x maxH, preserving aspect ratio.
// Never upscales: a source already inside the box is copied unchanged.
// Returns false on an empty source or a zero-sized box.
bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out);

}  // namespace rawcam
