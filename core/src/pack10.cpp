#include "rawcam/pack10.h"
#include "rawcam/bit_depth.h"

namespace rawcam {
namespace {

// Templated on the reduction for the same reason the codec is, and it is not a
// micro-optimisation: calling reduceSample unconditionally puts its `shift == 0`
// test INSIDE the loop, and clang will not auto-vectorise across it. Measured on
// the shipping toolchain (NDK 27 clang, --target=aarch64-linux-android30,
// -O2 -DNDEBUG): pack10 emitted 77 vector instructions before the bit-depth
// work, 0 with the branch in the loop, and 252 with this dispatch. Native is the
// default, so leaving the branch there taxes every existing user on a path they
// have not enabled -- exactly what the plan's cost-identical constraint forbids,
// and the one place the encoder's `template <bool Reduce>` pattern had not been
// applied.
template <bool Reduce>
inline uint16_t packSample(const uint16_t* src, size_t i, uint32_t shift, uint32_t newWhite) {
  if constexpr (Reduce) return reduceSample(src[i], shift, newWhite);
  else return src[i];
}

template <bool Reduce>
inline void pack10Impl(const uint16_t* src, size_t count, uint8_t* dst,
                       uint32_t shift, uint32_t newWhite) {
  for (size_t i = 0; i < count; i += 4) {
    uint16_t a = packSample<Reduce>(src, i, shift, newWhite) & 0x3FF,
             b = packSample<Reduce>(src, i + 1, shift, newWhite) & 0x3FF,
             c = packSample<Reduce>(src, i + 2, shift, newWhite) & 0x3FF,
             d = packSample<Reduce>(src, i + 3, shift, newWhite) & 0x3FF;
    dst[0] = (uint8_t)a;
    dst[1] = (uint8_t)b;
    dst[2] = (uint8_t)c;
    dst[3] = (uint8_t)d;
    dst[4] = (uint8_t)((a >> 8) | ((b >> 8) << 2) | ((c >> 8) << 4) | ((d >> 8) << 6));
    dst += 5;
  }
}

template <bool Reduce>
inline void pack12Impl(const uint16_t* src, size_t count, uint8_t* dst,
                       uint32_t shift, uint32_t newWhite) {
  for (size_t i = 0; i < count; i += 2) {
    uint16_t a = packSample<Reduce>(src, i, shift, newWhite) & 0xFFF,
             b = packSample<Reduce>(src, i + 1, shift, newWhite) & 0xFFF;
    dst[0] = (uint8_t)a;
    dst[1] = (uint8_t)((a >> 8) | ((b & 0x0F) << 4));
    dst[2] = (uint8_t)(b >> 4);
    dst += 3;
  }
}

}  // namespace

void pack10(const uint16_t* src, size_t count, uint8_t* dst, uint32_t shift, uint32_t newWhite) {
  if (shift == 0) pack10Impl<false>(src, count, dst, 0, 0);
  else pack10Impl<true>(src, count, dst, shift, newWhite);
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

void pack12(const uint16_t* src, size_t count, uint8_t* dst, uint32_t shift, uint32_t newWhite) {
  if (shift == 0) pack12Impl<false>(src, count, dst, 0, 0);
  else pack12Impl<true>(src, count, dst, shift, newWhite);
}

void unpack12(const uint8_t* src, size_t count, uint16_t* dst) {
  for (size_t i = 0; i < count; i += 2) {
    dst[i]     = (uint16_t)(src[0] | ((src[1] & 0x0F) << 8));
    dst[i + 1] = (uint16_t)((src[1] >> 4) | ((uint16_t)src[2] << 4));
    src += 3;
  }
}

}  // namespace rawcam
