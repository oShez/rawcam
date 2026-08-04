#include "rawcam/rawv_writer.h"
#include "rawcam/file_io.h"

namespace rawcam {

std::unique_ptr<RawvWriter> RawvWriter::create(const std::string& path, const FileHeader& hdr) {
  if (hdr.magic != kMagic || hdr.frameSizeBytes == 0) return nullptr;
  int fd = io::openWrite(path.c_str());
  if (fd < 0) return nullptr;
  FileHeader h = hdr;
  h.frameCount = 0;  // finalize fills this in; 0 means "recover by scan"
  if (!io::writeAll(fd, &h, sizeof h)) { io::closeFd(fd); return nullptr; }
  return std::unique_ptr<RawvWriter>(new RawvWriter(fd, h));
}

bool RawvWriter::writeFrame(const FrameMeta& meta, const uint8_t* payload, uint32_t payloadBytes) {
  if (fd_ < 0 || finalized_) return false;
  if (meta.payloadBytes != payloadBytes) return false;  // caller bug guard
  if (!io::writeAll(fd_, &meta, sizeof meta)) return false;
  if (payloadBytes > 0 && !io::writeAll(fd_, payload, payloadBytes)) return false;
  frames_++;
  return true;
}

bool RawvWriter::finalize() {
  if (fd_ < 0 || finalized_) return false;
  hdr_.frameCount = frames_;
  bool ok = io::seekTo(fd_, 0) && io::writeAll(fd_, &hdr_, sizeof hdr_);
  io::syncFd(fd_);  // best-effort; narrows the post-finalize power-pull window
  io::closeFd(fd_);
  fd_ = -1;
  finalized_ = true;
  return ok;
}

RawvWriter::~RawvWriter() { if (!finalized_ && fd_ >= 0) finalize(); }

}  // namespace rawcam
