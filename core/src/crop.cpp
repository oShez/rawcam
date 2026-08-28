#include "rawcam/crop.h"

#include <cstring>

namespace rawcam {

const uint8_t* cropBase16(const uint8_t* src, size_t srcRowBytes,
                          uint32_t cropX, uint32_t cropY) {
  return src + (size_t)cropY * srcRowBytes + (size_t)cropX * 2;
}

void cropPlane16(const uint8_t* src, size_t srcRowBytes,
                 uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
                 uint8_t* dst) {
  const size_t dstRowBytes = (size_t)cropW * 2;
  const uint8_t* base = cropBase16(src, srcRowBytes, cropX, cropY);
  for (uint32_t y = 0; y < cropH; y++) {
    std::memcpy(dst + (size_t)y * dstRowBytes, base + (size_t)y * srcRowBytes, dstRowBytes);
  }
}

}  // namespace rawcam
