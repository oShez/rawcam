#include "rawcam/pack10.h"

namespace rawcam {

void pack10(const uint16_t* src, size_t count, uint8_t* dst) {
  for (size_t i = 0; i < count; i += 4) {
    uint16_t a = src[i] & 0x3FF, b = src[i + 1] & 0x3FF,
             c = src[i + 2] & 0x3FF, d = src[i + 3] & 0x3FF;
    dst[0] = (uint8_t)a;
    dst[1] = (uint8_t)b;
    dst[2] = (uint8_t)c;
    dst[3] = (uint8_t)d;
    dst[4] = (uint8_t)((a >> 8) | ((b >> 8) << 2) | ((c >> 8) << 4) | ((d >> 8) << 6));
    dst += 5;
  }
}

void unpack10(const uint8_t* src, size_t count, uint16_t* dst) {
  for (size_t i = 0; i < count; i += 4) {
    uint8_t hi = src[4];
    dst[i]     = (uint16_t)(src[0] | ((hi & 0x03) << 8));
    dst[i + 1] = (uint16_t)(src[1] | ((hi & 0x0C) << 6));
    dst[i + 2] = (uint16_t)(src[2] | ((hi & 0x30) << 4));
    dst[i + 3] = (uint16_t)(src[3] | ((hi & 0xC0) << 2));
    src += 5;
  }
}

void pack12(const uint16_t* src, size_t count, uint8_t* dst) {
  for (size_t i = 0; i < count; i += 2) {
    uint16_t a = src[i] & 0xFFF, b = src[i + 1] & 0xFFF;
    dst[0] = (uint8_t)a;
    dst[1] = (uint8_t)((a >> 8) | ((b & 0x0F) << 4));
    dst[2] = (uint8_t)(b >> 4);
    dst += 3;
  }
}

void unpack12(const uint8_t* src, size_t count, uint16_t* dst) {
  for (size_t i = 0; i < count; i += 2) {
    dst[i]     = (uint16_t)(src[0] | ((src[1] & 0x0F) << 8));
    dst[i + 1] = (uint16_t)((src[1] >> 4) | ((uint16_t)src[2] << 4));
    src += 3;
  }
}

}  // namespace rawcam
