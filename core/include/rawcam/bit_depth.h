#pragma once
#include <cstddef>
#include <cstdint>

#include "rawcam/rawv.h"

namespace rawcam {

// See docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md.
// whiteLevel truncates, levels and samples round. The asymmetry is deliberate:
// capture.cpp derives bitDepth as 32 - clz(whiteLevel), so a rounded whiteLevel
// can carry into the next bit and desynchronise the codec from its own samples.

inline uint32_t reducedWhiteLevel(uint32_t whiteLevel, uint32_t shift) {
  return whiteLevel >> shift;
}

inline uint32_t reduceLevel(uint32_t level, uint32_t shift) {
  if (shift == 0) return level;
  return (level + (1u << (shift - 1))) >> shift;
}

inline uint16_t reduceSample(uint16_t sample, uint32_t shift, uint32_t newWhite) {
  if (shift == 0) return sample;
  uint32_t r = ((uint32_t)sample + (1u << (shift - 1))) >> shift;
  return (uint16_t)(r > newWhite ? newWhite : r);
}

// 0 means "no reduction": either Native was requested, or the request meets or
// exceeds what this sensor delivers (the per-lens clamp).
inline uint32_t shiftForDepth(uint32_t whiteLevel, uint32_t requestedDepth) {
  if (requestedDepth == 0 || whiteLevel == 0) return 0;
  uint32_t nativeDepth = 32u - (uint32_t)__builtin_clz(whiteLevel);
  if (requestedDepth >= nativeDepth) return 0;
  return nativeDepth - requestedDepth;
}

// Scales a header in place for `requestedDepth` and returns the sample shift the
// encoder must apply. whiteLevel truncates; blackLevel rounds. Returns 0 (and
// changes nothing) for Native or for a request the sensor cannot reach.
inline uint32_t applyBitDepth(FileHeader& h, uint32_t requestedDepth) {
  uint32_t shift = shiftForDepth(h.whiteLevel, requestedDepth);
  if (shift == 0) return 0;
  h.whiteLevel = reducedWhiteLevel(h.whiteLevel, shift);
  for (int i = 0; i < 4; i++) h.blackLevel[i] = reduceLevel(h.blackLevel[i], shift);
  return shift;
}

// Reduces a whole plane of samples in place. `shift == 0` is a no-op.
//
// This exists for ONE caller: the compressed path's uncompressed fallback, which
// writes its raw copy verbatim and would otherwise put full-depth samples under a
// reduced header -- frames that export several stops too bright, silently. That
// caller lives in capture.cpp, which has no host harness, so the arithmetic lives
// here where it can be tested and only the call site stays uncovered. Same reason
// applyBitDepth is here rather than inlined into Capture::start.
//
// NOT for the encode path: that reduces on READ, one sample at a time inside the
// predictor, precisely to avoid a separate whole-plane pass over ~25 MB a frame.
// Calling this per frame would reintroduce the memory traffic the design avoids.
inline void reducePlaneInPlace(uint16_t* samples, size_t count, uint32_t shift,
                               uint32_t newWhite) {
  if (shift == 0) return;
  for (size_t i = 0; i < count; i++)
    samples[i] = reduceSample(samples[i], shift, newWhite);
}

}  // namespace rawcam
