#pragma once
#include <memory>
#include <string>
#include <vector>
#include "rawcam/rawv.h"

namespace rawcam {

class RawvReader {
 public:
  static std::unique_ptr<RawvReader> open(const std::string& path);
  const FileHeader& header() const { return hdr_; }
  uint64_t frameCount() const { return offsets_.size(); }
  // `payload` must be sized for the worst case (hdr_.frameSizeBytes, the
  // allocation ceiling), not assumed to be exactly that many valid bytes --
  // for CompressedPredictive frames only the first meta->payloadBytes bytes
  // (populated by this call) are written/valid.
  bool readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload);
  ~RawvReader();
  RawvReader(const RawvReader&) = delete;             // owns a raw fd
  RawvReader& operator=(const RawvReader&) = delete;

 private:
  RawvReader(int fd, const FileHeader& h, std::vector<uint64_t> offsets)
      : fd_(fd), hdr_(h), offsets_(std::move(offsets)) {}
  int fd_;
  FileHeader hdr_;
  // Absolute byte offset of each frame's FrameMeta, in frame-index order.
  // offsets_[0] is always kHeaderSize. Built once at open() by sequentially
  // scanning records via their payloadBytes -- necessary because
  // CompressedPredictive frames vary in size, so offsets can't be computed
  // arithmetically from a constant stride.
  std::vector<uint64_t> offsets_;
};

}  // namespace rawcam
