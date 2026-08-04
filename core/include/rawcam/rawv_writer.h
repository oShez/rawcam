#pragma once
#include <memory>
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {

class RawvWriter {
 public:
  static std::unique_ptr<RawvWriter> create(const std::string& path, const FileHeader& hdr);
  bool writeFrame(const FrameMeta& meta, const uint8_t* payload, uint32_t payloadBytes);
  bool finalize();
  uint64_t framesWritten() const { return frames_; }
  ~RawvWriter();
  RawvWriter(const RawvWriter&) = delete;             // owns a raw fd
  RawvWriter& operator=(const RawvWriter&) = delete;

 private:
  RawvWriter(int fd, const FileHeader& hdr) : fd_(fd), hdr_(hdr) {}
  int fd_ = -1;
  FileHeader hdr_{};
  uint64_t frames_ = 0;
  bool finalized_ = false;
};

}  // namespace rawcam
