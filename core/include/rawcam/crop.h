#pragma once

#include <cstddef>
#include <cstdint>

namespace rawcam {

// Zoom is a real crop of the sensor readout, so both halves of the capture
// path need to address a centred sub-rectangle of a RAW16 plane that is
// delivered with the CAMERA's row stride, not a tight one.
//
// Callers MUST pass an EVEN cropX and cropY. The Bayer phase is fixed by the
// origin's parity: an odd origin shifts the CFA by one pixel while
// FileHeader.cfa still claims the sensor's pattern, so the clip decodes
// cleanly into wrong colour with no error raised anywhere. ZoomLadder
// guarantees this on the Kotlin side; nothing here can detect a violation.

/**
 * Pointer to the first sample of the crop rectangle, with the source stride
 * left alone. This is all the compressed path needs: ParallelFrameEncoder
 * walks cropH rows of cropW samples at srcRowBytes/2 stride, and its
 * predictAt() guards on band-local x and y, so it never reads outside the
 * rectangle.
 */
const uint8_t* cropBase16(const uint8_t* src, size_t srcRowBytes,
                          uint32_t cropX, uint32_t cropY);

/**
 * Copies the crop rectangle into `dst` TIGHTLY PACKED at cropW*2 bytes per
 * row, discarding the source's stride padding. `dst` must have room for
 * cropW * 2 * cropH bytes. Used by the paths that need a contiguous frame:
 * Raw16, and the compressed path's uncompressed fallback copy.
 */
void cropPlane16(const uint8_t* src, size_t srcRowBytes,
                 uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
                 uint8_t* dst);

}  // namespace rawcam
