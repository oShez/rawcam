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
// `channelGains` are MULTIPLIERS applied after black-level normalisation, not
// DNG AsShotNeutral coordinates -- AsShotNeutral is the reciprocal of a gain,
// so a caller holding one must invert it (see developFrame).
// Returns false if whiteLevel is 0 or the dimensions are unusable.
bool developRaw16(const uint16_t* raw16, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, Cfa cfa,
                  const uint32_t blackLevel[4], uint32_t whiteLevel,
                  const float channelGains[3], PreviewImage* out);

// Box-averages `src` down to fit within maxW x maxH, preserving aspect ratio.
// Never upscales: a source already inside the box is copied unchanged.
// Returns false on an empty source or a zero-sized box.
bool downscaleTo(const PreviewImage& src, uint32_t maxW, uint32_t maxH, PreviewImage* out);

class RawvReader;

// Reads frame `index` from `reader`, unpacks it according to the clip's pack
// mode, develops it, and downscales the result to fit maxW x maxH.
// Returns false if the frame cannot be read, the index is out of range, or the
// header cannot support development (whiteLevel == 0).
bool developFrame(RawvReader& reader, uint64_t index,
                  uint32_t maxW, uint32_t maxH, PreviewImage* out);

}  // namespace rawcam
