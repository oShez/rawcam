#include "capture.h"

#include <android/native_window_jni.h>
#include <cstdio>
#include <cstring>

#include "rawcam/pack10.h"

namespace rawcam {

Capture& Capture::instance() {
  static Capture inst;
  return inst;
}

// --- AImageReader callback: must never block the camera. ------------------

void Capture::onImageAvailableThunk(void* context, AImageReader* reader) {
  static_cast<Capture*>(context)->onImageAvailable(reader);
}

void Capture::onImageAvailable(AImageReader* reader) {
  AImage* image = nullptr;
  media_status_t status = AImageReader_acquireNextImage(reader, &image);
  if (status != AMEDIA_OK || image == nullptr) {
    dropped_++;
    return;
  }

  std::lock_guard<std::mutex> lock(queueMutex_);
  if (queue_.size() >= kQueueCap) {
    AImage_delete(image);
    dropped_++;
    return;
  }
  queue_.push_back(image);
  queueCv_.notify_one();
}

// --- Writer thread: pops, matches metadata, packs, writes, recycles. ------

FrameMeta Capture::matchMeta(int64_t timestampNs) {
  std::lock_guard<std::mutex> lock(metaMutex_);
  auto it = pendingMeta_.find(timestampNs);
  FrameMeta meta;
  if (it != pendingMeta_.end()) {
    meta = it->second;
    pendingMeta_.erase(it);
  } else {
    // Miss: fall back to last-known values, but the image's own sensor
    // timestamp always goes in the record.
    meta = lastKnown_;
  }
  meta.timestampNs = (uint64_t)timestampNs;
  lastKnown_ = meta;
  return meta;
}

void Capture::processImage(AImage* image) {
  // Once a write has failed (e.g. disk full) don't keep pounding the disk:
  // consume the image, count the drop, keep the camera fed with buffers.
  if (writeFailed_) {
    dropped_++;
    AImage_delete(image);
    return;
  }

  uint8_t* data = nullptr;
  int dataLen = 0;
  if (AImage_getPlaneData(image, 0, &data, &dataLen) != AMEDIA_OK || data == nullptr) {
    dropped_++;
    AImage_delete(image);
    return;
  }

  int64_t timestampNs = 0;
  AImage_getTimestamp(image, &timestampNs);

  if (!writerInitialized_) {
    int32_t rowStride = 0;
    if (AImage_getPlaneRowStride(image, 0, &rowStride) != AMEDIA_OK || rowStride <= 0) {
      // Don't bake a garbage stride into the header; drop this frame and let
      // the next good frame initialize the writer.
      dropped_++;
      AImage_delete(image);
      return;
    }
    rowStride_ = rowStride;

    FileHeader hdr = headerTemplate_;
    hdr.rowStrideBytes = (uint32_t)rowStride_;
    if ((PackMode)hdr.packMode == PackMode::Packed10) {
      hdr.frameSizeBytes = (uint32_t)packed10Size((size_t)width_ * (size_t)height_);
      packBuf_.resize(hdr.frameSizeBytes);
    } else {
      hdr.frameSizeBytes = (uint32_t)rowStride_ * (uint32_t)height_;
    }
    headerTemplate_ = hdr;
    writer_ = RawvWriter::create(path_, hdr);
    if (!writer_) {
      // Could not even create the file: fail the session, count every frame.
      writeFailed_ = true;
      dropped_++;
      AImage_delete(image);
      return;
    }
    writerInitialized_ = true;
  }

  FrameMeta meta = matchMeta(timestampNs);
  meta.frameIndex = writer_->framesWritten();
  meta.droppedSoFar = (uint32_t)dropped_.load();

  bool ok;
  if ((PackMode)headerTemplate_.packMode == PackMode::Packed10) {
    // De-stride while packing: address each row via the original sensor
    // stride, pack exactly `width_` pixels per row into the contiguous
    // preallocated scratch buffer.
    const size_t packedRowBytes = packed10Size((size_t)width_);
    for (int32_t row = 0; row < height_; row++) {
      const uint16_t* srcRow =
          reinterpret_cast<const uint16_t*>(data + (size_t)row * (size_t)rowStride_);
      pack10(srcRow, (size_t)width_, packBuf_.data() + (size_t)row * packedRowBytes);
    }
    ok = writer_->writeFrame(meta, packBuf_.data());
  } else {
    ok = writer_->writeFrame(meta, data);
  }
  if (!ok) {
    // Partial-write failure (e.g. ENOSPC mid-recording): the frame was not
    // durably written, so count it as dropped and stop writing for good.
    writeFailed_ = true;
    dropped_++;
  }

  AImage_delete(image);
}

void Capture::writerLoop() {
  for (;;) {
    AImage* image = nullptr;
    {
      std::unique_lock<std::mutex> lock(queueMutex_);
      queueCv_.wait(lock, [this] { return !queue_.empty() || stopping_.load(); });
      if (queue_.empty()) {
        if (stopping_.load()) break;
        continue;
      }
      image = queue_.front();
      queue_.pop_front();
    }
    processImage(image);
  }
}

// --- Lifecycle --------------------------------------------------------------

jobject Capture::start(JNIEnv* env, const std::string& path, int32_t width, int32_t height,
                       int32_t cfa, int32_t whiteLevel, const int32_t blackLevel[4],
                       const float colorMatrix1[9], int32_t fpsNum, int32_t fpsDen,
                       const std::string& deviceName) {
  if (reader_ != nullptr) return nullptr;  // already recording

  width_ = width;
  height_ = height;
  rowStride_ = 0;
  writerInitialized_ = false;
  writeFailed_ = false;
  writer_.reset();
  path_ = path;
  dropped_.store(0);
  lastKnown_ = FrameMeta{};
  {
    std::lock_guard<std::mutex> lock(metaMutex_);
    pendingMeta_.clear();
  }

  FileHeader hdr{};
  hdr.magic = kMagic;
  hdr.version = kVersion;
  hdr.width = (uint32_t)width;
  hdr.height = (uint32_t)height;
  hdr.rowStrideBytes = 0;  // filled in on first frame
  hdr.packMode = (uint32_t)PackMode::Packed10;
  hdr.cfa = (uint32_t)cfa;
  hdr.whiteLevel = (uint32_t)whiteLevel;
  for (int i = 0; i < 4; i++) hdr.blackLevel[i] = (uint32_t)blackLevel[i];
  for (int i = 0; i < 9; i++) hdr.colorMatrix1[i] = colorMatrix1[i];
  hdr.asShotNeutral[0] = hdr.asShotNeutral[1] = hdr.asShotNeutral[2] = 0.0f;
  hdr.fpsNum = (uint32_t)fpsNum;
  hdr.fpsDen = (uint32_t)fpsDen;
  hdr.frameSizeBytes = 0;  // filled in on first frame
  hdr.frameCount = 0;
  std::snprintf(hdr.deviceName, sizeof(hdr.deviceName), "%s", deviceName.c_str());
  headerTemplate_ = hdr;

  media_status_t status = AImageReader_new(width, height, AIMAGE_FORMAT_RAW16, 12, &reader_);
  if (status != AMEDIA_OK || reader_ == nullptr) {
    reader_ = nullptr;
    return nullptr;
  }

  listener_.context = this;
  listener_.onImageAvailable = &Capture::onImageAvailableThunk;
  AImageReader_setImageListener(reader_, &listener_);

  ANativeWindow* window = nullptr;
  status = AImageReader_getWindow(reader_, &window);
  if (status != AMEDIA_OK || window == nullptr) {
    AImageReader_delete(reader_);
    reader_ = nullptr;
    return nullptr;
  }

  jobject surface = ANativeWindow_toSurface(env, window);

  stopping_.store(false);
  writerThread_ = std::thread(&Capture::writerLoop, this);

  return surface;
}

void Capture::pushFrameMeta(int64_t timestampNs, int32_t iso, int64_t exposureNs,
                            float focusDistance, float wbR, float wbG, float wbB) {
  FrameMeta meta{};
  meta.timestampNs = (uint64_t)timestampNs;
  meta.frameIndex = 0;  // filled in by the writer thread when matched to a frame
  meta.iso = (uint32_t)iso;
  meta.exposureNs = (uint64_t)exposureNs;
  meta.focusDistance = focusDistance;
  meta.wbNeutral[0] = wbR;
  meta.wbNeutral[1] = wbG;
  meta.wbNeutral[2] = wbB;
  meta.droppedSoFar = 0;

  std::lock_guard<std::mutex> lock(metaMutex_);
  pendingMeta_[meta.timestampNs] = meta;
  while (pendingMeta_.size() > kMetaCap) {
    pendingMeta_.erase(pendingMeta_.begin());  // prune oldest (smallest timestamp)
  }
}

std::pair<uint64_t, uint64_t> Capture::stop() {
  if (reader_ == nullptr) return {0, dropped_.load()};

  stopping_.store(true);
  queueCv_.notify_all();
  if (writerThread_.joinable()) writerThread_.join();

  uint64_t written = 0;
  if (writer_) {
    written = writer_->framesWritten();
    writer_->finalize();
    writer_.reset();
  }

  AImageReader_delete(reader_);
  reader_ = nullptr;
  writerInitialized_ = false;

  return {written, dropped_.load()};
}

}  // namespace rawcam
