#include "rawcam/rawv_codec.h"
#include <algorithm>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

namespace rawcam {
namespace {

// MSB-first bit writer over a caller-owned, pre-zeroed buffer, using a
// 64-bit accumulator so multi-bit fields (the Rice remainder, and runs of
// quotient one-bits) are packed with one shift+OR instead of one function
// call per bit. The per-bit version was the throughput bottleneck at real
// camera resolution (~91% dropped frames at 4096x3072@24fps on-device,
// 2026-08-05) -- see docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md.
// Produces the byte-for-byte IDENTICAL bitstream the per-bit version did;
// this is a performance refactor, not a new format.
class BitWriter {
 public:
  BitWriter(uint8_t* buf, uint32_t capacity) : buf_(buf), capacity_(capacity) {}

  // Writes the low `nbits` bits of `bits` (nbits in [0,32]), most
  // significant of those bits first, immediately after any bits already
  // written. Returns false (and stops writing) once `capacity` would be
  // exceeded.
  bool writeBits(uint32_t bits, uint32_t nbits) {
    if (nbits == 0) return true;
    // Check bit-granularly upfront: total bits used (current + new) must fit
    // in capacity_*8 bits. This prevents silently dropping trailing partial
    // bytes when bytePos_ reaches capacity mid-remainder.
    uint64_t bitsUsed = static_cast<uint64_t>(bytePos_) * 8 + accBits_;
    if (bitsUsed + nbits > static_cast<uint64_t>(capacity_) * 8) return false;
    acc_ = (acc_ << nbits) | static_cast<uint64_t>(bits & maskFor(nbits));
    accBits_ += nbits;
    while (accBits_ >= 8) {
      accBits_ -= 8;
      buf_[bytePos_++] = static_cast<uint8_t>(acc_ >> accBits_);
    }
    return true;
  }

  // `q` one-bits, a zero bit, then `k` bits of `value`'s low bits -- the
  // standard Golomb-Rice codeword shape. In normal frames (well-predicted
  // residuals), q < 32, so this batches into at most 3 writeBits calls
  // total per pixel; large quotients (q >= 32) drain in the while loop.
  bool writeRice(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    while (q >= 32) {
      if (!writeBits(0xFFFFFFFFu, 32)) return false;
      q -= 32;
    }
    // q one-bits followed by a terminating zero bit, as one (q+1)-bit field.
    uint32_t qval = (q == 0) ? 0u : (((1u << q) - 1u) << 1);
    if (!writeBits(qval, q + 1)) return false;
    if (k > 0 && !writeBits(value, k)) return false;
    return true;
  }

  // Flushes any partial byte (zero-padded, matching the pre-zeroed-buffer
  // padding semantics the per-bit version relied on) and returns the total
  // bytes written. Not const: the flush is a real write, deferred from
  // writeBits() until now since fewer than 8 bits may still be pending.
  uint32_t finishedBytes() {
    if (accBits_ > 0 && bytePos_ < capacity_) {
      buf_[bytePos_++] = static_cast<uint8_t>(acc_ << (8 - accBits_));
      accBits_ = 0;
    }
    return bytePos_;
  }

 private:
  static uint32_t maskFor(uint32_t nbits) {
    return nbits >= 32 ? 0xFFFFFFFFu : ((1u << nbits) - 1u);
  }
  uint8_t* buf_;
  uint32_t capacity_;
  uint32_t bytePos_ = 0;
  uint64_t acc_ = 0;
  uint32_t accBits_ = 0;
};

// Matches BitWriter's accumulator approach on the read side. The Rice
// remainder (up to 20 bits, see riceParamFor's k<20 cap) is read in one
// batched call; the unary quotient is still read one bit at a time since
// its expected length is ~1 bit (riceParamFor picks k so that's true for
// any well-behaved frame) -- batching that too would add real complexity
// for a part that's already cheap on average. Decode speed was not the
// throughput blocker Task 8 found (capture/encode was); this still speeds
// up export of compressed clips via the now-batched remainder reads.
class BitReader {
 public:
  BitReader(const uint8_t* buf, uint32_t size) : buf_(buf), size_(size) {}

  // Reads `nbits` bits (nbits in [0,32]) MSB-first into the low bits of
  // *out. Returns false if not enough bits remain in the buffer.
  bool readBits(uint32_t nbits, uint32_t* out) {
    if (nbits == 0) { *out = 0; return true; }
    while (accBits_ < nbits) {
      if (bytePos_ >= size_) return false;
      acc_ = (acc_ << 8) | buf_[bytePos_++];
      accBits_ += 8;
    }
    accBits_ -= nbits;
    *out = static_cast<uint32_t>((acc_ >> accBits_) & maskFor(nbits));
    return true;
  }

  bool readRice(uint32_t k, uint32_t* value) {
    uint32_t q = 0, bit = 0;
    while (true) {
      if (!readBits(1, &bit)) return false;
      if (bit == 0) break;
      if (++q > (1u << 24)) return false;  // corrupt-stream guard
    }
    uint32_t remainder = 0;
    if (k > 0 && !readBits(k, &remainder)) return false;
    *value = (q << k) + remainder;
    return true;
  }

 private:
  static uint32_t maskFor(uint32_t nbits) {
    return nbits >= 32 ? 0xFFFFFFFFu : ((1u << nbits) - 1u);
  }
  const uint8_t* buf_;
  uint32_t size_;
  uint64_t acc_ = 0;
  uint32_t accBits_ = 0;
  uint32_t bytePos_ = 0;
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

ParallelFrameEncoder::ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount)
    : width_(width), height_(height) {
  if (threadCount > 0) {
    threadCount_ = threadCount;
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    threadCount_ = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
  }
  residuals_.resize(static_cast<size_t>(width_) * height_);
  workers_.reserve(threadCount_);
  for (uint32_t i = 0; i < threadCount_; i++) {
    workers_.emplace_back([this, i] { workerLoop(i); });
  }
}

ParallelFrameEncoder::~ParallelFrameEncoder() {
  {
    std::lock_guard<std::mutex> lock(mu_);
    stopping_ = true;
  }
  cvStart_.notify_all();
  for (auto& t : workers_) t.join();
}

void ParallelFrameEncoder::workerLoop(uint32_t bandIndex) {
  uint64_t seenGeneration = 0;
  for (;;) {
    std::unique_lock<std::mutex> lock(mu_);
    cvStart_.wait(lock, [&] { return generation_ != seenGeneration || stopping_; });
    if (stopping_) return;
    seenGeneration = generation_;
    lock.unlock();

    // bandRows*threadCount_ <= height_ by construction (floor division), so
    // every non-last band's [bandStart,bandEnd) stays within [0,height_];
    // the last band absorbs any remainder rows up to height_ exactly.
    uint32_t bandRows = height_ / threadCount_;
    uint32_t bandStart = bandIndex * bandRows;
    uint32_t bandEnd = (bandIndex + 1 == threadCount_) ? height_ : bandStart + bandRows;
    computeBand(bandStart, bandEnd);

    lock.lock();
    if (--pending_ == 0) cvDone_.notify_one();
  }
}

void ParallelFrameEncoder::computeBand(uint32_t bandStart, uint32_t bandEnd) {
  for (uint32_t y = bandStart; y < bandEnd; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      int32_t actual = jobRaw16_[y * jobRowStrideSamples_ + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      residuals_[y * width_ + x] = static_cast<uint32_t>(zigzagEncode(actual - predicted));
    }
  }
}

uint32_t ParallelFrameEncoder::encode(const uint16_t* raw16, uint32_t rowStrideSamples,
                                       uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width_ == 0 || height_ == 0) return 0;

  // Pass 1: same strided-sample k-selection as encodeFrame() -- unchanged,
  // already cheap after round 2's fix.
  constexpr uint32_t kSampleStride = 4;
  uint64_t sumAbs = 0;
  uint64_t count = 0;
  for (uint32_t y = 0; y < height_; y += kSampleStride) {
    for (uint32_t x = 0; x < width_; x += kSampleStride) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
      count++;
    }
  }
  uint32_t k = riceParamFor(sumAbs, count);

  // Pass 2, stage 1: dispatch the row-band predict+residual compute to the
  // persistent worker pool and wait for it to finish.
  {
    std::lock_guard<std::mutex> lock(mu_);
    jobRaw16_ = raw16;
    jobRowStrideSamples_ = rowStrideSamples;
    jobBitDepth_ = bitDepth;
    pending_ = threadCount_;
    generation_++;
  }
  cvStart_.notify_all();
  {
    std::unique_lock<std::mutex> lock(mu_);
    cvDone_.wait(lock, [&] { return pending_ == 0; });
  }

  // Pass 2, stage 2: serial batched write over the precomputed residuals --
  // no predictor arithmetic left here, just BitWriter::writeRice calls, in
  // the same raster order encodeFrame() would produce them in.
  std::memset(out, 0, outCapacity);
  out[0] = static_cast<uint8_t>(k);
  BitWriter bw(out + 1, outCapacity - 1);
  for (uint32_t y = 0; y < height_; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      if (!bw.writeRice(residuals_[y * width_ + x], k)) return 0;
    }
  }
  return 1 + bw.finishedBytes();
}

uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height,
                      uint32_t rowStrideSamples, uint32_t bitDepth,
                      uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width == 0 || height == 0) return 0;

  // Pass 1: sum of absolute residuals, to pick one Rice parameter for the
  // whole frame. Recomputing predictAt() in pass 2 is cheap integer
  // arithmetic -- far cheaper than holding a full-frame residual buffer
  // (width*height*4 bytes) alive just to avoid a second pass.
  // Sample a strided grid (1/16th of pixels) instead of scanning every one
  // -- this pass does no bit I/O, so its only cost is the scan itself, and
  // real sensor noise doesn't vary pixel-to-pixel in a way uniform sampling
  // would miss. (0,0) is always included (x=0,y=0 satisfies any stride), so
  // count is always >= 1 for any non-empty frame -- no divide-by-zero risk
  // even though riceParamFor doesn't divide, just compares.
  constexpr uint32_t kSampleStride = 4;
  uint64_t sumAbs = 0;
  uint64_t count = 0;
  for (uint32_t y = 0; y < height; y += kSampleStride) {
    for (uint32_t x = 0; x < width; x += kSampleStride) {
      int32_t actual = raw16[y * rowStrideSamples + x];
      int32_t predicted = predictAt(raw16, x, y, rowStrideSamples, bitDepth);
      sumAbs += static_cast<uint64_t>(std::abs(actual - predicted));
      count++;
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
