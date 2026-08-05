#pragma once
#include <jni.h>
#include <media/NdkImageReader.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "rawcam/rawv.h"
#include "rawcam/rawv_codec.h"
#include "rawcam/rawv_writer.h"

namespace rawcam {

// Owns the RAW capture hot path: AImageReader -> bounded queue -> writer thread
// -> RawvWriter. Singleton because there is exactly one recording session at a
// time (Camera2 target Surface is produced by start(), consumed by Task 10).
class Capture {
 public:
  static Capture& instance();

  // Creates the AImageReader (RAW16, maxImages=12), starts the writer thread,
  // and returns a Surface wrapping the reader's window for the Camera2 RAW
  // target -- or nullptr on failure. RawvWriter creation is deferred to the
  // first delivered frame so the real row stride goes into the file header.
  jobject start(JNIEnv* env, const std::string& path, int32_t width, int32_t height,
                 int32_t cfa, int32_t whiteLevel, const int32_t blackLevel[4],
                 const float colorMatrix1[9], int32_t illuminant1, int32_t illuminant2,
                 const float colorMatrix2[9], int32_t fpsNum, int32_t fpsDen,
                 const std::string& deviceName, bool compressRecordings);

  // Records per-frame metadata keyed by exact sensor timestamp, for the writer
  // thread to match against arriving AImages. Callable from any thread.
  void pushFrameMeta(int64_t timestampNs, int32_t iso, int64_t exposureNs,
                      float focusDistance, float wbR, float wbG, float wbB);

  // Signals the writer thread to drain the queue, joins it, finalizes the
  // file and tears down the reader. Returns {framesWritten, framesDropped}.
  std::pair<uint64_t, uint64_t> stop();

  // Lock-free snapshot of {framesWritten, framesDropped} for UI polling while
  // recording is in progress. Safe to call from any thread concurrently with
  // the writer thread; both counters are std::atomic.
  std::pair<uint64_t, uint64_t> stats() const {
    return {written_.load(std::memory_order_relaxed), dropped_.load(std::memory_order_relaxed)};
  }

  // How many CompressedPredictive frames this session fell back to storing
  // uncompressed because ParallelFrameEncoder::encode() returned 0 (a
  // per-band local buffer overflowed -- see rawv_codec.h's ParallelFrameEncoder
  // doc). Not currently wired into the UI/JNI layer -- deliberately minimal,
  // intended to be read via a debugger or a temporary logcat print during a
  // future on-device check of how often this happens on real footage (open
  // question flagged in docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
  // about whether the per-band capacity's 2x safety margin is right). Lock-free,
  // same pattern as stats() above.
  uint64_t compressedFallbacks() const {
    return compressedFallbacks_.load(std::memory_order_relaxed);
  }

 private:
  Capture() = default;
  Capture(const Capture&) = delete;
  Capture& operator=(const Capture&) = delete;

  static void onImageAvailableThunk(void* context, AImageReader* reader);
  void onImageAvailable(AImageReader* reader);
  void writerLoop();
  void processImage(AImage* image);
  FrameMeta matchMeta(int64_t timestampNs);

  static constexpr size_t kQueueCap = 8;
  static constexpr size_t kMetaCap = 64;

  AImageReader* reader_ = nullptr;
  AImageReader_ImageListener listener_{};

  std::mutex queueMutex_;
  std::condition_variable queueCv_;
  // Fixed-capacity ring buffer, not std::deque: the capture callback (must
  // never block/allocate on the camera thread) and the writer thread share
  // this at up to the frame rate, and a deque's per-push/pop node churn is
  // not provably allocation-free. queue_[queueHead_] is the oldest queued
  // image; queueCount_ (<= kQueueCap) is how many of queue_'s kQueueCap
  // slots are live, starting at queueHead_ and wrapping.
  std::array<AImage*, kQueueCap> queue_{};
  size_t queueHead_ = 0;
  size_t queueCount_ = 0;
  std::atomic<bool> stopping_{false};

  std::thread writerThread_;

  std::mutex metaMutex_;
  std::map<int64_t, FrameMeta> pendingMeta_;
  FrameMeta lastKnown_{};

  std::atomic<uint64_t> dropped_{0};
  // Mirrors writer_->framesWritten(), but atomic so stats() can be read from
  // the JNI/UI thread without touching the writer_ pointer itself (which is
  // only ever safely accessed from the writer thread while recording).
  std::atomic<uint64_t> written_{0};
  // Counts CompressedPredictive frames that fell back to uncompressed
  // storage because encode() returned 0 -- see compressedFallbacks() above.
  std::atomic<uint64_t> compressedFallbacks_{0};

  std::unique_ptr<RawvWriter> writer_;
  FileHeader headerTemplate_{};
  std::string path_;
  int32_t width_ = 0;
  int32_t height_ = 0;
  int32_t rowStride_ = 0;
  bool writerInitialized_ = false;
  bool writeFailed_ = false;  // set on first write failure; later frames drop, disk untouched
  std::vector<uint8_t> packBuf_;  // preallocated Packed10/Packed12 scratch buffer
  std::vector<uint8_t> compressBuf_;  // preallocated CompressedPredictive scratch buffer
  // Owns the persistent thread pool used by CompressedPredictive's parallel
  // encode path (round 3 throughput fix) -- constructed in start() once
  // packMode is known, destroyed in stop(). Null when compressRecordings is
  // off for this session.
  std::unique_ptr<ParallelFrameEncoder> frameEncoder_;
};

}  // namespace rawcam
