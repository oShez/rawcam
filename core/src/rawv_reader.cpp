#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"

namespace rawcam {

std::unique_ptr<RawvReader> RawvReader::open(const std::string& path) {
  int fd = io::openRead(path.c_str());
  if (fd < 0) return nullptr;
  FileHeader h{};
  if (!io::readAll(fd, &h, sizeof h) || h.magic != kMagic || h.version != kVersion ||
      h.frameSizeBytes == 0) {
    io::closeFd(fd);
    return nullptr;
  }
  const uint64_t rec = kFrameMetaSize + (uint64_t)h.frameSizeBytes;
  uint64_t count = h.frameCount;
  if (count == 0) {
    // unfinalized (crash / battery pull): recover whole records from file size
    int64_t sz = io::fileSize(fd);
    if (sz > (int64_t)kHeaderSize) count = ((uint64_t)sz - kHeaderSize) / rec;
  }
  return std::unique_ptr<RawvReader>(new RawvReader(fd, h, count));
}

bool RawvReader::readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload) {
  if (index >= count_) return false;
  const uint64_t rec = kFrameMetaSize + (uint64_t)hdr_.frameSizeBytes;
  if (!io::seekTo(fd_, kHeaderSize + index * rec)) return false;
  if (!io::readAll(fd_, meta, sizeof *meta)) return false;
  return io::readAll(fd_, payload, hdr_.frameSizeBytes);
}

RawvReader::~RawvReader() { if (fd_ >= 0) io::closeFd(fd_); }

}  // namespace rawcam
