#include "rawcam/rawv_codec.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace rawcam {
namespace {

// MSB-first bit writer over a caller-owned, pre-zeroed buffer. write*()
// returns false (and stops writing) once `capacity` would be exceeded --
// encodeFrame uses that as its "won't fit" signal.
class BitWriter {
 public:
  BitWriter(uint8_t* buf, uint32_t capacity) : buf_(buf), capacity_(capacity) {}

  bool writeBit(uint32_t bit) {
    if (bytePos_ >= capacity_) return false;
    buf_[bytePos_] |= static_cast<uint8_t>((bit & 1u) << (7 - bitPos_));
    if (++bitPos_ == 8) { bitPos_ = 0; bytePos_++; }
    return true;
  }

  // `q` one-bits, a zero bit, then `k` bits of `value`'s low bits MSB-first --
  // the standard Golomb-Rice codeword shape.
  bool writeRice(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    for (uint32_t i = 0; i < q; i++) if (!writeBit(1)) return false;
    if (!writeBit(0)) return false;
    for (uint32_t i = 0; i < k; i++) {
      if (!writeBit((value >> (k - 1 - i)) & 1u)) return false;
    }
    return true;
  }

  uint32_t finishedBytes() const { return bitPos_ == 0 ? bytePos_ : bytePos_ + 1; }

 private:
  uint8_t* buf_;
  uint32_t capacity_;
  uint32_t bytePos_ = 0;
  uint32_t bitPos_ = 0;
};

class BitReader {
 public:
  BitReader(const uint8_t* buf, uint32_t size) : buf_(buf), size_(size) {}

  bool readBit(uint32_t* bit) {
    if (bytePos_ >= size_) return false;
    *bit = (buf_[bytePos_] >> (7 - bitPos_)) & 1u;
    if (++bitPos_ == 8) { bitPos_ = 0; bytePos_++; }
    return true;
  }

  bool readRice(uint32_t k, uint32_t* value) {
    uint32_t q = 0, bit = 0;
    while (true) {
      if (!readBit(&bit)) return false;
      if (bit == 0) break;
      if (++q > (1u << 24)) return false;  // corrupt-stream guard
    }
    uint32_t remainder = 0;
    for (uint32_t i = 0; i < k; i++) {
      if (!readBit(&bit)) return false;
      remainder = (remainder << 1) | bit;
    }
    *value = (q << k) + remainder;
    return true;
  }

 private:
  const uint8_t* buf_;
  uint32_t size_;
  uint32_t bytePos_ = 0;
  uint32_t bitPos_ = 0;
};

inline uint32_t zigzagEncode(int32_t v) {
  return (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31);
}

inline int32_t zigzagDecode(uint32_t v) {
  return static_cast<int32_t>(v >> 1) ^ -static_cast<int32_t>(v & 1);
}

// MED/LOCO-I predictor: median of (left, up, left+up-upleft). Always within
// [min(left,up), max(left,up)] by construction -- no separate clamp needed.
inline int32_t medPredict(int32_t left, int32_t up, int32_t upleft) {
  int32_t linear = left + up - upleft;
  int32_t lo = std::min(left, up), hi = std::max(left, up);
  return std::clamp(linear, lo, hi);
}

// `plane` is either the original frame (encode) or the buffer being filled
// in raster order (decode) -- valid either way since left/up/upleft are
// always earlier in raster scan order than (x, y).
inline int32_t predictAt(const uint16_t* plane, uint32_t x, uint32_t y,
                          uint32_t rowStrideSamples, uint32_t bitDepth) {
  bool hasLeft = x >= 2;
  bool hasUp = y >= 2;
  if (!hasLeft && !hasUp) return 1 << (bitDepth - 1);
  if (!hasLeft) return plane[(y - 2) * rowStrideSamples + x];
  if (!hasUp) return plane[y * rowStrideSamples + (x - 2)];
  int32_t left = plane[y * rowStrideSamples + (x - 2)];
  int32_t up = plane[(y - 2) * rowStrideSamples + x];
  int32_t upleft = plane[(y - 2) * rowStrideSamples + (x - 2)];
  return medPredict(left, up, upleft);
}

// Smallest k such that (count << k) >= sumAbs -- k=0 for a perfectly-
// predicted (all-zero-residual) frame, the common case for flat content.
uint32_t riceParamFor(uint64_t sumAbs, uint64_t count) {
  uint32_t k = 0;
  while (k < 20 && (count << k) < sumAbs) k++;
  return k;
}

}  // namespace

uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height,
                      uint32_t rowStrideSamples, uint32_t bitDepth,
                      uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width == 0 || height == 0) return 0;

  // Pass 1: sum of absolute residuals, to pick one Rice parameter for the
  // whole frame. Recomputing predictAt() in pass 2 is cheap integer
  // arithmetic -- far cheaper than holding a full-frame residual buffer
  // (width*height*4 bytes) alive just to avoid a second pass.
  uint64_t sumAbs = 0;
  uint64_t count = static_cast<uint64_t>(width) * height;
  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
    }
  }
  uint32_t k = riceParamFor(sumAbs, count);

  std::memset(out, 0, outCapacity);
  out[0] = static_cast<uint8_t>(k);
  BitWriter bw(out + 1, outCapacity - 1);

  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      uint32_t z = zigzagEncode(actual - predicted);
      if (!bw.writeRice(z, k)) return 0;  // wouldn't fit -- caller falls back
    }
  }
  return 1 + bw.finishedBytes();
}

bool decodeFrame(const uint8_t* compressed, uint32_t compressedSize,
                  uint16_t* out, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, uint32_t bitDepth) {
  if (compressedSize < 1 || width == 0 || height == 0) return false;
  uint32_t k = compressed[0];
  BitReader br(compressed + 1, compressedSize - 1);

  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      uint32_t z = 0;
      if (!br.readRice(k, &z)) return false;
      int32_t residual = zigzagDecode(z);
      int32_t predicted = predictAt(out, x, y, rowStrideSamples, bitDepth);
      out[y * rowStrideSamples + x] = static_cast<uint16_t>(predicted + residual);
    }
  }
  return true;
}

}  // namespace rawcam
