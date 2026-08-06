#include "rawcam/rawv_codec.h"
#include <algorithm>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#ifdef __ANDROID__
#include <sched.h>
#include <pthread.h>
#include <android/log.h>
#include <cstdio>
#include <cerrno>
#endif

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

  // Exact number of REAL bits written so far, excluding the zero-padding
  // finishedBytes() adds to byte-align a trailing partial byte. Must be
  // called BEFORE finishedBytes() -- finishedBytes() advances bytePos_ and
  // resets accBits_, so calling this after would return an inflated,
  // byte-rounded count instead of the true bit count. Used by
  // mergeBitstreams() to merge multiple independently-packed local
  // bitstreams without including any of their individual trailing padding.
  uint64_t totalBits() const {
    return static_cast<uint64_t>(bytePos_) * 8 + accBits_;
  }

  // True if the next write starts at a byte boundary (no partial byte
  // pending in the accumulator). Lets appendBits() below pick a bulk
  // memcpy fast path instead of a shift-and-OR merge -- always true for the
  // very first band merged into a fresh BitWriter, and true again for any
  // later band whenever the running total lands on a byte multiple.
  bool isByteAligned() const { return accBits_ == 0; }

  // Bulk-copies `nBytes` already byte-aligned, MSB-first-packed bytes
  // straight into the output buffer. Only valid to call when
  // isByteAligned() is true (accBits_ == 0, i.e. no partial byte pending) --
  // appendBits() guarantees this. Returns false (state unchanged) if
  // `nBytes` would not fit in the remaining capacity, same bit-granular
  // contract as writeBits().
  bool appendAlignedBytes(const uint8_t* src, uint64_t nBytes) {
    uint64_t bitsUsed = static_cast<uint64_t>(bytePos_) * 8;
    if (bitsUsed + nBytes * 8 > static_cast<uint64_t>(capacity_) * 8) return false;
    std::memcpy(buf_ + bytePos_, src, nBytes);
    bytePos_ += static_cast<uint32_t>(nBytes);
    return true;
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

// Appends `bitCount` real bits from a byte-aligned, MSB-first packed local
// buffer onto `bw` -- in bulk, not bit-at-a-time or byte-at-a-time, since
// this may need to move a whole band's worth of already-packed bits (up to
// ~12-15M individual bits at 4K resolution -- byte-at-a-time here was a
// measured serial-critical-path cost, see this file's history). `src` must
// have at least ceil(bitCount/8) valid bytes (guaranteed by
// BitWriter::finishedBytes() having flushed the source before this is
// called). Produces byte-for-byte identical output to the old
// byte-at-a-time version: a 32-bit shift+OR into the accumulator is exactly
// equivalent to four sequential 8-bit ones in the same MSB-first order,
// since writeBits()'s accumulator update (acc_ = (acc_<<n)|bits) is
// associative under bit concatenation.
inline bool appendBits(BitWriter& bw, const uint8_t* src, uint64_t bitCount) {
  if (bitCount == 0) return true;
  uint64_t fullBytes = bitCount / 8;
  uint32_t trailingBits = static_cast<uint32_t>(bitCount % 8);

  if (bw.isByteAligned()) {
    // Fast path: the destination write position is byte-aligned -- always
    // true for the first band merged, and possibly true for later bands
    // too whenever the running total so far happens to land on a byte
    // multiple. Move the full bytes with one memcpy instead of one
    // writeBits() call per byte.
    if (fullBytes > 0 && !bw.appendAlignedBytes(src, fullBytes)) return false;
    if (trailingBits > 0) {
      uint32_t lastBits = src[fullBytes] >> (8 - trailingBits);
      if (!bw.writeBits(lastBits, trailingBits)) return false;
    }
    return true;
  }

  // Slower path: destination position is mid-byte (the common case for
  // every band after the first). Move the source 32 bits (4 bytes) at a
  // time -- each becomes one shift+OR into bw's accumulator via a single
  // writeBits() call, instead of four. Only the leading/trailing partial
  // word falls back to a per-byte or per-bit call.
  uint64_t fullWords = fullBytes / 4;
  uint64_t wordBytes = fullWords * 4;
  for (uint64_t w = 0; w < fullWords; w++) {
    const uint8_t* p = src + w * 4;
    uint32_t word = (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
                     (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
    if (!bw.writeBits(word, 32)) return false;
  }
  for (uint64_t i = wordBytes; i < fullBytes; i++) {
    if (!bw.writeBits(src[i], 8)) return false;
  }
  if (trailingBits > 0) {
    uint32_t lastBits = src[fullBytes] >> (8 - trailingBits);
    if (!bw.writeBits(lastBits, trailingBits)) return false;
  }
  return true;
}

// Concatenates `bandCount` independently-packed local bitstreams (each
// produced by a per-band BitWriter, flushed via finishedBytes() with its
// REAL bit count captured beforehand via totalBits()) into one bit-exact
// contiguous stream written into `out`. Produces byte-for-byte identical
// output to what a single BitWriter packing the same sequence of
// writeRice() calls in raster order would have produced -- a Rice
// codeword's bits depend only on its own value and k, never on prior
// accumulator state, so only the byte OFFSET at which a band's bits land
// differs between "packed alone" (byte-aligned start) and "packed as a
// continuation of the previous band" (usually mid-byte), which this
// corrects band-by-band via appendBits(). Returns the merged byte count
// (matching BitWriter::finishedBytes()'s contract), or 0 if it doesn't fit
// outCapacity -- caller falls back to storing the frame uncompressed, same
// as encodeFrame()'s existing contract.
uint32_t mergeBitstreams(const uint8_t* const* bandBufs, const uint64_t* bandBits,
                          uint32_t bandCount, uint8_t* out, uint32_t outCapacity) {
  BitWriter bw(out, outCapacity);
  for (uint32_t b = 0; b < bandCount; b++) {
    if (!appendBits(bw, bandBufs[b], bandBits[b])) return 0;
  }
  return bw.finishedBytes();
}

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

#ifdef __ANDROID__
// Reads /sys/.../cpuN/cpufreq/cpuinfo_max_freq for each core [0, hw). Returns
// one entry per core in kHz, or -1 for any core whose file is missing/
// unreadable/non-numeric. Empty if hardware_concurrency() reports 0.
std::vector<long> readMaxFreqPerCore() {
  unsigned hw = std::thread::hardware_concurrency();
  if (hw == 0) return {};
  std::vector<long> freqs;
  freqs.reserve(hw);
  for (unsigned i = 0; i < hw; i++) {
    char path[128];
    std::snprintf(path, sizeof(path),
                  "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
    std::FILE* f = std::fopen(path, "r");
    if (!f) { freqs.push_back(-1); continue; }
    long val = -1;
    if (std::fscanf(f, "%ld", &val) != 1) val = -1;
    std::fclose(f);
    freqs.push_back(val);
  }
  return freqs;
}
#endif

}  // namespace

std::vector<int> selectWorkerCores(const std::vector<long>& maxFreqKhzPerCore) {
  if (maxFreqKhzPerCore.empty()) return {};
  for (long f : maxFreqKhzPerCore) {
    if (f < 0) return {};  // any unreadable core invalidates the whole set
  }
  std::vector<long> distinct(maxFreqKhzPerCore.begin(), maxFreqKhzPerCore.end());
  std::sort(distinct.begin(), distinct.end());
  distinct.erase(std::unique(distinct.begin(), distinct.end()), distinct.end());
  if (distinct.size() < 2) return {};  // uniform -> no confident split
  long lowest = distinct.front();  // ascending sort -> front is the lowest cluster
  std::vector<int> result;
  for (int i = 0; i < static_cast<int>(maxFreqKhzPerCore.size()); i++) {
    if (maxFreqKhzPerCore[i] != lowest) result.push_back(i);
  }
  return result;
}

uint32_t workerThreadCount(std::size_t clusterCoreCount, uint32_t defaultCap) {
  if (clusterCoreCount == 0) return defaultCap;  // detection failed -> today's behavior
  uint32_t clusterBased = clusterCoreCount > 1
                              ? static_cast<uint32_t>(clusterCoreCount - 1)
                              : 1u;
  return std::max(clusterBased, defaultCap);
}

ParallelFrameEncoder::ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount)
    : width_(width), height_(height) {
  if (threadCount > 0) {
    threadCount_ = threadCount;
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    uint32_t defaultCap = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
#ifdef __ANDROID__
    workerCores_ = selectWorkerCores(readMaxFreqPerCore());
    threadCount_ = workerThreadCount(workerCores_.size(), defaultCap);
#else
    threadCount_ = defaultCap;  // host/non-Android: byte-for-byte today's behavior
#endif
  }
  // Per-band local pack buffer capacity -- unchanged sizing from round 4
  // stage 1, just allocated twice now (once per slot) for double-buffering.
  uint32_t floorBandRows = height_ / threadCount_;
  uint32_t maxBandRows = floorBandRows + threadCount_ - 1;
  uint32_t bandCapacity = maxBandRows * width_ * 2 * 2 + 64;
  for (uint32_t s = 0; s < kSlotCount; s++) {
    bandBufs_[s].resize(threadCount_);
    for (auto& buf : bandBufs_[s]) buf.resize(bandCapacity);
    bandBits_[s].resize(threadCount_, 0);
    bandPtrs_[s].resize(threadCount_);
  }

  workers_.reserve(threadCount_);
  for (uint32_t i = 0; i < threadCount_; i++) {
    workers_.emplace_back([this, i] { workerLoop(i); });
  }
  applyWorkerAffinity();  // no-op unless Android + a confident cluster was found
}

void ParallelFrameEncoder::applyWorkerAffinity() {
#ifdef __ANDROID__
  // Bionic (Android's libc) doesn't provide the glibc extension
  // pthread_setaffinity_np -- use its own pthread_gettid_np() to resolve the
  // kernel tid, then the POSIX sched_setaffinity() that IS available.
  if (workerCores_.empty()) return;
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int core : workerCores_) CPU_SET(core, &set);
  for (auto& t : workers_) {
    pid_t tid = pthread_gettid_np(t.native_handle());
    int rc = sched_setaffinity(tid, sizeof(set), &set);
    if (rc != 0) {
      __android_log_print(ANDROID_LOG_WARN, "rawv_codec",
                          "sched_setaffinity failed (errno=%d), worker unpinned", errno);
    }
  }
#endif
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
    uint32_t slot = jobSlot_;
    lock.unlock();

    // bandRows*threadCount_ <= height_ by construction (floor division), so
    // every non-last band's [bandStart,bandEnd) stays within [0,height_];
    // the last band absorbs any remainder rows up to height_ exactly.
    uint32_t bandRows = height_ / threadCount_;
    uint32_t bandStart = bandIndex * bandRows;
    uint32_t bandEnd = (bandIndex + 1 == threadCount_) ? height_ : bandStart + bandRows;
    computeAndPackBand(bandIndex, bandStart, bandEnd, slot);

    lock.lock();
    if (--pending_ == 0) cvDone_.notify_one();
  }
}

void ParallelFrameEncoder::computeAndPackBand(uint32_t bandIndex, uint32_t bandStart,
                                               uint32_t bandEnd, uint32_t slot) {
  BitWriter bw(bandBufs_[slot][bandIndex].data(),
               static_cast<uint32_t>(bandBufs_[slot][bandIndex].size()));
  bool ok = true;
  for (uint32_t y = bandStart; y < bandEnd && ok; y++) {
    for (uint32_t x = 0; x < width_; x++) {
      int32_t actual = jobRaw16_[y * jobRowStrideSamples_ + x];
      int32_t predicted = predictAt(jobRaw16_, x, y, jobRowStrideSamples_, jobBitDepth_);
      uint32_t z = zigzagEncode(actual - predicted);
      if (!bw.writeRice(z, jobK_)) { ok = false; break; }
    }
  }
  // Capture the exact bit count BEFORE finishedBytes() -- see this file's
  // Global Constraints on why the order matters.
  uint64_t bits = ok ? bw.totalBits() : 0;
  if (ok) bw.finishedBytes();
  std::lock_guard<std::mutex> lock(mu_);
  bandBits_[slot][bandIndex] = bits;
  if (!ok) jobOverflowed_ = true;
}

uint32_t ParallelFrameEncoder::computeBands(const uint16_t* raw16, uint32_t rowStrideSamples,
                                             uint32_t bitDepth) {
  // Claim a free slot -- blocks here if both are still holding a previous
  // computeBands() call's unmerged bands (the pipeline's backpressure).
  uint32_t slot;
  {
    std::unique_lock<std::mutex> lock(slotMu_);
    slot = nextSlot_;
    slotCv_.wait(lock, [&] { return !slotBusy_[slot]; });
    slotBusy_[slot] = true;
    nextSlot_ = (nextSlot_ + 1) % kSlotCount;
  }

  // Pass 1: same strided-sample k-selection as before -- unchanged, already
  // cheap (avg 2.79ms on-device per round 4 stage 2's re-profiling,
  // docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md).
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

  // Dispatch: each band's worker computes predict+residual+Rice-pack
  // directly into this slot's local buffers -- fused, no shared residual
  // buffer.
  {
    std::lock_guard<std::mutex> lock(mu_);
    jobRaw16_ = raw16;
    jobRowStrideSamples_ = rowStrideSamples;
    jobBitDepth_ = bitDepth;
    jobK_ = k;
    jobSlot_ = slot;
    jobOverflowed_ = false;
    pending_ = threadCount_;
    generation_++;
  }
  cvStart_.notify_all();
  {
    std::unique_lock<std::mutex> lock(mu_);
    cvDone_.wait(lock, [&] { return pending_ == 0; });
  }

  slotK_[slot] = k;
  slotOverflowed_[slot] = jobOverflowed_;
  return slot;
}

uint32_t ParallelFrameEncoder::mergeSlot(uint32_t slot, uint8_t* out, uint32_t outCapacity) {
  uint32_t result = 0;
  if (!slotOverflowed_[slot] && outCapacity >= 1) {
    // Merge: concatenate this slot's per-band local bitstreams into one
    // bit-exact contiguous stream, same header convention as before
    // (leading k byte).
    out[0] = static_cast<uint8_t>(slotK_[slot]);
    for (uint32_t i = 0; i < threadCount_; i++) bandPtrs_[slot][i] = bandBufs_[slot][i].data();
    uint32_t merged = mergeBitstreams(bandPtrs_[slot].data(), bandBits_[slot].data(), threadCount_,
                                       out + 1, outCapacity - 1);
    result = (merged == 0) ? 0 : 1 + merged;
  }
  // Always release the slot -- see the header doc comment: the caller must
  // call this exactly once per computeBands() call, or a slot leaks and
  // every future computeBands() call eventually deadlocks.
  {
    std::lock_guard<std::mutex> lock(slotMu_);
    slotBusy_[slot] = false;
  }
  slotCv_.notify_all();
  return result;
}

uint32_t ParallelFrameEncoder::encode(const uint16_t* raw16, uint32_t rowStrideSamples,
                                       uint32_t bitDepth, uint8_t* out, uint32_t outCapacity) {
  if (outCapacity < 1 || width_ == 0 || height_ == 0) return 0;
  uint32_t slot = computeBands(raw16, rowStrideSamples, bitDepth);
  return mergeSlot(slot, out, outCapacity);
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
