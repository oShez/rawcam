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
// throughput (round 3, see
// docs/superpowers/specs/2026-08-05-rawv-codec-round3-throughput-design.md).
// Splits the frame into row-bands processed by a persistent pool of worker
// threads (created once at construction, not per encode() call -- thread
// creation cost is too high to pay every frame at real-time rates),
// producing the SAME bitstream encodeFrame() would for the same input: same
// predictor, same k-selection, same Golomb-Rice coding, just computed with
// the predict+residual step parallelized across cores instead of done
// serially. width/height are fixed for the life of the encoder (matches one
// recording session, which has one fixed resolution).
class ParallelFrameEncoder {
 public:
  // threadCount: 0 (default) auto-picks min(hardware_concurrency(), 4); a
  // nonzero value forces exactly that many worker threads -- used by tests
  // to force a deterministic multi-band split regardless of the host
  // machine's actual core count (hardware_concurrency() could report 1 in
  // some CI/sandbox environments, which would silently collapse the encoder
  // to a single band and defeat the point of a "spans multiple bands" test).
  explicit ParallelFrameEncoder(uint32_t width, uint32_t height, uint32_t threadCount = 0);
  ~ParallelFrameEncoder();
  ParallelFrameEncoder(const ParallelFrameEncoder&) = delete;
  ParallelFrameEncoder& operator=(const ParallelFrameEncoder&) = delete;

  // Same contract as encodeFrame(): returns encoded size in bytes, or 0 if
  // it would not fit in outCapacity (caller falls back to uncompressed).
  // width/height are fixed at construction; rowStrideSamples/bitDepth may
  // vary per call (they don't, in practice, within one recording session,
  // but nothing here assumes that).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

 private:
  void workerLoop(uint32_t bandIndex);
  void computeBand(uint32_t bandStart, uint32_t bandEnd);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;
  std::vector<uint32_t> residuals_;  // width_*height_, zigzag(residual) per pixel, raster order

  // Current job, set by encode() before waking workers. Only ever touched
  // while mu_ is held by the sole caller of encode() (this project's single
  // dedicated writer thread) between generation bumps -- workers only read
  // these after observing a new generation_, which happens-after encode()'s
  // write under the same mutex.
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by encode() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};

}  // namespace rawcam
