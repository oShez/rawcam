#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"
#include "rawcam/pack10.h"

namespace rawcam {

namespace {
// The header is corruption-controlled input: downstream consumers (exporter,
// dng_writer) size buffers and read payload bytes from these fields, so a
// header that lies about geometry vs frameSizeBytes must never leave open().
bool headerSane(const FileHeader& h) {
  constexpr uint32_t kMaxDim = 16384;  // beyond any phone sensor; bounds allocations
  // Largest frame we will ever allocate for: a full kMaxDim x kMaxDim 16-bit
  // plane. Caps payload/pixel buffers so a header that over-states frame size
  // cannot drive a multi-GB allocation (uncaught bad_alloc -> terminate).
  constexpr uint64_t kMaxFrameBytes = (uint64_t)kMaxDim * kMaxDim * 2;
  if (h.width == 0 || h.height == 0 || h.width > kMaxDim || h.height > kMaxDim)
    return false;
  const uint64_t pixels = (uint64_t)h.width * h.height;
  switch ((PackMode)h.packMode) {
    case PackMode::Packed10:
      // unpack10 consumes pixels in whole groups of 4 (and the on-disk layout
      // packs whole 4-pixel groups), so a pixel count not divisible by 4 would
      // drive unpack10 to read/write past its buffers during export. The size
      // must also match packed10Size exactly: the old `>=` lower bound let a
      // crafted header under-size the payload buffer relative to what the
      // unpacker reads. Exact match mirrors what the writer emits and, together
      // with the dimension cap, bounds the allocation implicitly.
      return pixels % 4 == 0 &&
             (uint64_t)h.frameSizeBytes == packed10Size(pixels);
    case PackMode::Raw16:
      return h.rowStrideBytes >= h.width * 2 &&
             (uint64_t)h.frameSizeBytes >= (uint64_t)h.rowStrideBytes * h.height &&
             (uint64_t)h.frameSizeBytes <= kMaxFrameBytes;
    default:
      return false;
  }
}
}  // namespace

std::unique_ptr<RawvReader> RawvReader::open(const std::string& path) {
  int fd = io::openRead(path.c_str());
  if (fd < 0) return nullptr;
  FileHeader h{};
  if (!io::readAll(fd, &h, sizeof h) || h.magic != kMagic || h.version != kVersion ||
      h.frameSizeBytes == 0 || !headerSane(h)) {
    io::closeFd(fd);
    return nullptr;
  }
  const uint64_t rec = kFrameMetaSize + (uint64_t)h.frameSizeBytes;
  // Whole records actually present on disk. The header's frameCount is
  // corruption-controlled and must never be trusted beyond this: an
  // over-stated count would let readFrame seek/read past EOF frame after frame.
  const int64_t sz = io::fileSize(fd);
  const uint64_t capacity =
      (sz > (int64_t)kHeaderSize) ? ((uint64_t)sz - kHeaderSize) / rec : 0;
  uint64_t count = h.frameCount;
  if (count == 0 || count > capacity) {
    // count == 0: unfinalized (crash / battery pull) -> recover by scan.
    // count > capacity: header over-claims (truncated or crafted) -> clamp.
    count = capacity;
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
