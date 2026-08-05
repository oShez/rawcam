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
// throughput. Round 4 stage 1: fuses predict+residual+Rice-pack into each
// row-band's worker. Round 4 stage 2: splits encode() into computeBands()
// (k-selection + per-band pack, the expensive ~48ms/frame step) and
// mergeSlot() (merge, ~12ms/frame) so a caller can pipeline them across
// frames -- computeBands() for frame N+1 can run while a DIFFERENT thread
// calls mergeSlot() for frame N. See
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

  // Same contract as before: returns encoded size in bytes, or 0 if it would
  // not fit in outCapacity (caller falls back to uncompressed). Implemented
  // as computeBands() immediately followed by mergeSlot() on the same
  // thread -- kept for callers that don't need pipelining (all existing host
  // tests use this).
  uint32_t encode(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth,
                   uint8_t* out, uint32_t outCapacity);

  // Async split of encode() for pipelining: computeBands() does k-selection +
  // per-band predict+residual+Rice-pack (the CPU-heavy step) and returns a
  // SLOT index; mergeSlot() does the merge into a final contiguous bitstream
  // for that slot, and is safe to call from a DIFFERENT thread than
  // computeBands() -- this is what lets a dedicated "Finish" thread
  // merge+write frame N while computeBands() already starts on frame N+1's
  // k-selection+dispatch. See this file's history and the design doc's
  // "Pipelining" section.
  //
  // Exactly kSlotCount (2) frames' worth of computed-but-not-yet-merged band
  // buffers can be outstanding at once. If both slots are already holding
  // unmerged bands, a third computeBands() call BLOCKS until mergeSlot() is
  // called for one of them -- this is the pipeline's backpressure (mirrors
  // this project's existing bounded-queue drop-when-full pattern one level
  // up, at the camera capture callback).
  //
  // Only ever call computeBands() from one thread and mergeSlot() from one
  // (possibly different) thread -- neither is safe to call concurrently with
  // itself.
  uint32_t computeBands(const uint16_t* raw16, uint32_t rowStrideSamples, uint32_t bitDepth);

  // Merges the given slot's bands (from a prior computeBands() call) into
  // `out`, same return contract as encode(). ALWAYS releases the slot before
  // returning, whether it succeeds, fails to fit outCapacity, or that slot's
  // Compute already overflowed. The caller MUST call this exactly once for
  // every computeBands() call -- skipping it leaks a slot and eventually
  // deadlocks every future computeBands() call waiting for backpressure to
  // clear.
  uint32_t mergeSlot(uint32_t slot, uint8_t* out, uint32_t outCapacity);

 private:
  static constexpr uint32_t kSlotCount = 2;

  void workerLoop(uint32_t bandIndex);
  // Computes predict+residual+Rice-pack for this band directly into this
  // slot's local buffer -- fused, no shared residual buffer.
  void computeAndPackBand(uint32_t bandIndex, uint32_t bandStart, uint32_t bandEnd, uint32_t slot);

  uint32_t width_;
  uint32_t height_;
  uint32_t threadCount_;

  // Per-slot (double-buffered), per-band local pack buffers and each band's
  // exact bit count after computeBands() -- read by mergeSlot() once that
  // slot's workers have finished. bandPtrs_ is per-slot merge scratch,
  // rebuilt (not reallocated) at the top of each mergeSlot() call.
  std::vector<std::vector<uint8_t>> bandBufs_[kSlotCount];
  std::vector<uint64_t> bandBits_[kSlotCount];
  std::vector<const uint8_t*> bandPtrs_[kSlotCount];
  // Set by computeBands() (Compute thread), read by mergeSlot() (possibly
  // the Finish thread). Safe without their own lock: the Compute->Finish
  // handoff in Capture always goes through a mutex lock/unlock (the finish
  // job queue's mutex) between computeBands() returning and mergeSlot()
  // being called for that slot, which establishes happens-before per the
  // C++ memory model even though these fields aren't directly guarded by
  // that mutex.
  uint32_t slotK_[kSlotCount] = {0, 0};
  bool slotOverflowed_[kSlotCount] = {false, false};

  // Slot availability -- a separate mutex from the worker-dispatch mu_ below
  // so waiting for a free slot never contends with the hot per-frame
  // dispatch path. slotBusy_[s] is true from the moment computeBands()
  // claims slot s until mergeSlot() releases it.
  std::mutex slotMu_;
  std::condition_variable slotCv_;
  bool slotBusy_[kSlotCount] = {false, false};
  uint32_t nextSlot_ = 0;

  // Current job, set by computeBands() before waking workers -- only touched
  // while mu_ is held by the sole in-flight computeBands() caller; workers
  // only read after observing a new generation_, which happens-after
  // computeBands()'s write under the same mutex (same reasoning as round 3's
  // original dispatch design).
  const uint16_t* jobRaw16_ = nullptr;
  uint32_t jobRowStrideSamples_ = 0;
  uint32_t jobBitDepth_ = 0;
  uint32_t jobK_ = 0;
  uint32_t jobSlot_ = 0;
  bool jobOverflowed_ = false;  // true if any band's local buffer couldn't hold its content

  std::vector<std::thread> workers_;
  std::mutex mu_;
  std::condition_variable cvStart_;
  std::condition_variable cvDone_;
  uint64_t generation_ = 0;  // bumped by computeBands() to wake workers for a new job
  uint32_t pending_ = 0;     // workers remaining to finish this generation
  bool stopping_ = false;
};

}  // namespace rawcam
