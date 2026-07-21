#pragma once
#include <jni.h>
#include <media/NdkImageReader.h>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "rawcam/rawv.h"
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
                 const std::string& deviceName);

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
  std::deque<AImage*> queue_;
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

  std::unique_ptr<RawvWriter> writer_;
  FileHeader headerTemplate_{};
  std::string path_;
  int32_t width_ = 0;
  int32_t height_ = 0;
  int32_t rowStride_ = 0;
  bool writerInitialized_ = false;
  bool writeFailed_ = false;  // set on first write failure; later frames drop, disk untouched
  std::vector<uint8_t> packBuf_;  // preallocated Packed10 scratch buffer
};

}  // namespace rawcam
