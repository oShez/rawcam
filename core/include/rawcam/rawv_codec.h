#pragma once
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace rawcam {

// Lossless compression for one frame of RAW16 Bayer samples (row-strided,
// `rowStrideSamples` SAMPLES per row -- i.e. rowStrideBytes / 2). Predicts
// each sample from its same-color neighbors (2 samples away in each axis --
// always same CFA color regardless of RGGB/GRBG/GBRG/BGGR arrangement, so no
// CFA parameter is needed) using the MED/LOCO-I median predictor, then
// Rice-codes the signed residuals with one Golomb-Rice parameter for the
// whole frame. `bitDepth` (10/12/16) only affects the fixed baseline used to
// predict the first two rows/columns, which have no same-color neighbor yet.
//
// Returns the encoded size in bytes on success, written into `out`
// (caller-owned, must be at least `outCapacity` bytes). Returns 0 if the
// encoded output would not fit in `outCapacity` -- caller should fall back
// to storing the frame uncompressed. Never allocates, never throws.
uint32_t encodeFrame(const uint16_t* raw16, uint32_t width, uint32_t height,
                      uint32_t rowStrideSamples, uint32_t bitDepth,
                      uint8_t* out, uint32_t outCapacity);

// Inverse of encodeFrame. `out` must have room for height * rowStrideSamples
// samples. Returns false if `compressed` is malformed/truncated -- caller
// should treat this as a corrupt-frame read error.
bool decodeFrame(const uint8_t* compressed, uint32_t compressedSize,
                  uint16_t* out, uint32_t width, uint32_t height,
                  uint32_t rowStrideSamples, uint32_t bitDepth);

// Parallel drop-in replacement for encodeFrame(), for real-time capture
// throughput. Round 4: fuses predict+residual+Rice-pack into each row-band's
// worker (round 3 only parallelized predict+residual, leaving the actual
// bottleneck -- the serial write pass, ~88.5% of encode() cost per on-device
// profiling, 2026-08-05 -- untouched). Each band packs directly into its own
// local buffer; a cheap serial merge (see mergeBitstreams() in
// rawv_codec.cpp) concatenates them into one bit-exact stream, identical to
// what encodeFrame() produces for the same input. See
// docs/superpowers/specs/2026-08-05-rawv-codec-round4-pipeline-design.md.
// width/height are fixed for the life of the encoder (matches one recording
// session, which has one fixed resolution).
class ParallelFrameEncoder {
 public:
  // threadCount: 0 (default) auto-picks min(hardware_concurrency(), 4); a
  // nonzero value forces exactly that many worker threads -- used by tests
  // to force a deterministic multi-band split regardless of the host
  // machine's actual core count (hardware_concurrency() can report 1 in a
  // CI/sandboxed environment, which would silently collapse every band
  // test down to a single band and defeat the point of testing the merge).
  explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0);
  ~ParallelFrameEncoder();
  ParallelFrameEncoder(const ParallelFrameEncoder&) = delete;
  ParallelFrameEncoder& operator=(const ParallelFrameEncoder&) = delete;

  // Same contract as encodeFrame(): returns encoded size in bytes, or 0 if
  // it would not fit in outCapacity (caller falls back to uncompressed).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

 private:
  void workerLoop(uint32_t bandIndex);
  // Computes predict+residual+Rice-pack for this band directly into its
  // local buffer -- fused, no shared residual buffer.
  void computeAndPackBand(uint32_t bandIndex, uint32_t bandStart, uint32_t bandEnd);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;

  // Per-band local pack buffers (sized once at construction) and each
  // band's exact bit count after the last encode() call -- read by
  // encode()'s merge step once all workers finish.
  std::vector<std::vector<uint8_t>> bandBufs_;
  std::vector<uint64_t> bandBits_;
  // Scratch array of per-band buffer pointers, rebuilt (not reallocated --
  // sized once here) at the top of each encode() call for mergeBitstreams().
  // A member instead of a local so the real-time encode() hot path never
  // allocates.
  std::vector<const uint8_t*> bandPtrs_;

  // Current job, set by encode() before waking workers -- see round 3's
  // original comment on this pattern: only touched while mu_ is held by
  // the sole caller of encode(), workers only read after observing a new
  // generation_, which happens-after encode()'s write under the same mutex.
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;
  uint32_t jobK_ = 0;
  bool jobOverflowed_ = false;  // true if any band's local buffer couldn't hold its content

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by encode() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};

}  // namespace rawcam
