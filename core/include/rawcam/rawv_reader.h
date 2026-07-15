#pragma once
#include <memory>
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {

class RawvReader {
 public:
  static std::unique_ptr<RawvReader> open(const std::string& path);
  const FileHeader& header() const { return hdr_; }
  uint64_t frameCount() const { return count_; }
  bool readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload);
  ~RawvReader();
  RawvReader(const RawvReader&) = delete;             // owns a raw fd
  RawvReader& operator=(const RawvReader&) = delete;

 private:
  RawvReader(int fd, const FileHeader& h, uint64_t c) : fd_(fd), hdr_(h), count_(c) {}
  int fd_;
  FileHeader hdr_;
  uint64_t count_;
};

}  // namespace rawcam
