#include "capture.h"

#include <android/native_window_jni.h>
#include <cstdio>
#include <cstring>

#include "rawcam/pack10.h"
#include "rawcam/rawv_codec.h"

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
  if (queueCount_ >= kQueueCap) {
    AImage_delete(image);
    dropped_++;
    return;
  }
  queue_[(queueHead_ + queueCount_) % kQueueCap] = image;
  queueCount_++;
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
    switch ((PackMode)hdr.packMode) {
      case PackMode::Packed10:
        hdr.frameSizeBytes = (uint32_t)packed10Size((size_t)width_ * (size_t)height_);
        packBuf_.resize(hdr.frameSizeBytes);
        break;
      case PackMode::Packed12:
        hdr.frameSizeBytes = (uint32_t)packed12Size((size_t)width_ * (size_t)height_);
        packBuf_.resize(hdr.frameSizeBytes);
        break;
      case PackMode::Raw16:
        hdr.frameSizeBytes = (uint32_t)rowStride_ * (uint32_t)height_;
        break;
      case PackMode::CompressedPredictive:
        // frameSizeBytes is only an allocation ceiling for this mode (Task 1)
        // -- size it to what Raw16 would have needed, the guaranteed-safe
        // upper bound for a frame that doesn't compress at all.
        hdr.frameSizeBytes = (uint32_t)rowStride_ * (uint32_t)height_;
        compressBuf_.resize(hdr.frameSizeBytes);
        break;
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
  const PackMode mode = (PackMode)headerTemplate_.packMode;
  if (mode == PackMode::Packed10 || mode == PackMode::Packed12) {
    // De-stride while packing: address each row via the original sensor
    // stride, pack exactly `width_` pixels per row into the contiguous
    // preallocated scratch buffer.
    const size_t packedRowBytes =
        mode == PackMode::Packed10 ? packed10Size((size_t)width_) : packed12Size((size_t)width_);
    for (int32_t row = 0; row < height_; row++) {
      const uint16_t* srcRow =
          reinterpret_cast<const uint16_t*>(data + (size_t)row * (size_t)rowStride_);
      uint8_t* dstRow = packBuf_.data() + (size_t)row * packedRowBytes;
      if (mode == PackMode::Packed10) {
        pack10(srcRow, (size_t)width_, dstRow);
      } else {
        pack12(srcRow, (size_t)width_, dstRow);
      }
    }
    meta.payloadBytes = headerTemplate_.frameSizeBytes;
    meta.compressed = 0;
    ok = writer_->writeFrame(meta, packBuf_.data(), headerTemplate_.frameSizeBytes);
  } else if (mode == PackMode::CompressedPredictive) {
    // whiteLevel == 0 would make __builtin_clz(0) undefined below (mirrors
    // the same guard exporter.cpp applies before deriving bitDepth) -- fall
    // back to storing this one frame uncompressed, same as an encode that
    // doesn't fit the ceiling.
    uint32_t n = 0;
    if (headerTemplate_.whiteLevel != 0 && frameEncoder_) {
      const uint32_t rowStrideSamples = (uint32_t)rowStride_ / 2;
      // MUST match exporter.cpp's decode-side bitDepth derivation exactly --
      // a mismatch would corrupt the first two rows/columns of every frame.
      const uint32_t bitDepth = 32 - __builtin_clz(headerTemplate_.whiteLevel);
      n = frameEncoder_->encode(reinterpret_cast<const uint16_t*>(data), rowStrideSamples,
                                 bitDepth, compressBuf_.data(), (uint32_t)compressBuf_.size());
    }
    if (n > 0) {
      meta.payloadBytes = n;
      meta.compressed = 1;
      ok = writer_->writeFrame(meta, compressBuf_.data(), n);
    } else {
      // Encode didn't fit the ceiling (pathological content) or whiteLevel
      // was 0 -- fall back to storing this one frame as plain Raw16, flagged
      // uncompressed, exactly like the existing Raw16 branch below.
      meta.payloadBytes = headerTemplate_.frameSizeBytes;
      meta.compressed = 0;
      ok = writer_->writeFrame(meta, data, headerTemplate_.frameSizeBytes);
    }
  } else {
    meta.payloadBytes = headerTemplate_.frameSizeBytes;
    meta.compressed = 0;
    ok = writer_->writeFrame(meta, data, headerTemplate_.frameSizeBytes);
  }
  if (!ok) {
    // Partial-write failure (e.g. ENOSPC mid-recording): the frame was not
    // durably written, so count it as dropped and stop writing for good.
    writeFailed_ = true;
    dropped_++;
  } else {
    written_.fetch_add(1, std::memory_order_relaxed);
  }

  AImage_delete(image);
}

void Capture::writerLoop() {
  for (;;) {
    AImage* image = nullptr;
    {
      std::unique_lock<std::mutex> lock(queueMutex_);
      queueCv_.wait(lock, [this] { return queueCount_ > 0 || stopping_.load(); });
      if (queueCount_ == 0) {
        if (stopping_.load()) break;
        continue;
      }
      image = queue_[queueHead_];
      queueHead_ = (queueHead_ + 1) % kQueueCap;
      queueCount_--;
    }
    processImage(image);
  }
}

// --- Lifecycle --------------------------------------------------------------

jobject Capture::start(JNIEnv* env, const std::string& path, int32_t width, int32_t height,
                       int32_t cfa, int32_t whiteLevel, const int32_t blackLevel[4],
                       const float colorMatrix1[9], int32_t illuminant1, int32_t illuminant2,
                       const float colorMatrix2[9], int32_t fpsNum, int32_t fpsDen,
                       const std::string& deviceName, bool compressRecordings) {
  if (reader_ != nullptr) return nullptr;  // already recording

  width_ = width;
  height_ = height;
  rowStride_ = 0;
  writerInitialized_ = false;
  writeFailed_ = false;
  writer_.reset();
  frameEncoder_.reset();
  path_ = path;
  dropped_.store(0);
  written_.store(0);
  lastKnown_ = FrameMeta{};
  {
    std::lock_guard<std::mutex> lock(metaMutex_);
    pendingMeta_.clear();
  }
  {
    // Defensive: stop() drains the queue, so it should already be empty. If
    // anything is left it belongs to a deleted reader and must not be touched
    // (AImage_delete on it would double-free); just drop the pointers.
    std::lock_guard<std::mutex> lock(queueMutex_);
    queueHead_ = 0;
    queueCount_ = 0;
  }

  FileHeader hdr{};
  hdr.magic = kMagic;
  hdr.version = kVersion;
  hdr.width = (uint32_t)width;
  hdr.height = (uint32_t)height;
  hdr.rowStrideBytes = 0;  // filled in on first frame
  // Pick the tightest packing that can hold this sensor's actual range without
  // truncation. Packed10/Packed12 mask to 0x3FF/0xFFF respectively (see
  // pack10.cpp) -- silently corrupting samples above their range -- so the
  // choice must be driven by the real white level, not assumed from whatever
  // device this shipped on first (Packed10 alone was fine on the Pixel 7 Pro's
  // 10-bit sensor but is NOT safe on hardware with a wider white level).
  // Packing is additionally gated on the width dividing evenly into the pack
  // group (4 px/5B for Packed10, 2 px/3B for Packed12): pack10/pack12 step in
  // whole groups per ROW with no remainder handling, and RawvReader::headerSane
  // rejects a non-divisible pixel count outright -- so a non-conforming width
  // would record a file that looks successful but can never be exported.
  // Raw16 is exact for any width; losing the pack ratio beats losing the clip.
  // compressRecordings overrides the Packed10/Packed12/Raw16 choice entirely:
  // the predictor works per-pixel with no group-size requirement, so it
  // applies regardless of width parity (unlike Packed10/12's w4/w2 gates).
  const bool w4 = width % 4 == 0, w2 = width % 2 == 0;
  hdr.packMode = (uint32_t)(compressRecordings                ? PackMode::CompressedPredictive
                             : whiteLevel <= 0x3FF && w4       ? PackMode::Packed10
                             : whiteLevel <= 0xFFF && w2       ? PackMode::Packed12
                                                                : PackMode::Raw16);
  hdr.cfa = (uint32_t)cfa;
  hdr.whiteLevel = (uint32_t)whiteLevel;
  for (int i = 0; i < 4; i++) hdr.blackLevel[i] = (uint32_t)blackLevel[i];
  for (int i = 0; i < 9; i++) hdr.colorMatrix1[i] = colorMatrix1[i];
  hdr.asShotNeutral[0] = hdr.asShotNeutral[1] = hdr.asShotNeutral[2] = 0.0f;
  hdr.illuminant1 = (uint32_t)illuminant1;
  hdr.illuminant2 = (uint32_t)illuminant2;
  for (int i = 0; i < 9; i++) hdr.colorMatrix2[i] = colorMatrix2[i];
  hdr.fpsNum = (uint32_t)fpsNum;
  hdr.fpsDen = (uint32_t)fpsDen;
  hdr.frameSizeBytes = 0;  // filled in on first frame
  hdr.frameCount = 0;
  std::snprintf(hdr.deviceName, sizeof(hdr.deviceName), "%s", deviceName.c_str());
  headerTemplate_ = hdr;

  if (hdr.packMode == (uint32_t)PackMode::CompressedPredictive) {
    frameEncoder_ = std::make_unique<ParallelFrameEncoder>((uint32_t)width_, (uint32_t)height_);
  }

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

  // Detach the listener first so no new images get queued while we tear down.
  AImageReader_setImageListener(reader_, nullptr);

  stopping_.store(true);
  queueCv_.notify_all();
  if (writerThread_.joinable()) writerThread_.join();

  // Safe to reset only after the writer thread has joined -- it may still
  // be mid-call to frameEncoder_->encode() while draining the queue.
  frameEncoder_.reset();

  // Delete any image the callback managed to queue after the writer's final
  // drain. This MUST happen before AImageReader_delete: deleting the reader
  // invalidates outstanding AImages, and a stale invalidated image surviving
  // in queue_ aborts the NEXT session's writer thread inside
  // AImage_getPlaneData ("lockImage: AImage has no buffer" -- observed on
  // device, 2026-07-13 12:19 crash).
  {
    std::lock_guard<std::mutex> lock(queueMutex_);
    for (size_t i = 0; i < queueCount_; i++) {
      AImage_delete(queue_[(queueHead_ + i) % kQueueCap]);
    }
    queueHead_ = 0;
    queueCount_ = 0;
  }

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
