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
    case PackMode::Packed12:
      // Same reasoning as Packed10: unpack12 consumes whole 2-pixel groups.
      return pixels % 2 == 0 &&
             (uint64_t)h.frameSizeBytes == packed12Size(pixels);
    case PackMode::Raw16:
      return h.rowStrideBytes >= h.width * 2 &&
             (uint64_t)h.frameSizeBytes >= (uint64_t)h.rowStrideBytes * h.height &&
             (uint64_t)h.frameSizeBytes <= kMaxFrameBytes;
    case PackMode::CompressedPredictive:
      // frameSizeBytes is only an allocation ceiling for this mode (Task 1) --
      // capture.cpp sizes it to whatever Raw16 would have needed for the same
      // geometry, so the same bound applies; the real per-frame size lives in
      // each record's FrameMeta.payloadBytes, checked per-record in
      // scanOffsets()/readFrame(), not here.
      return h.rowStrideBytes >= h.width * 2 &&
             (uint64_t)h.frameSizeBytes >= (uint64_t)h.rowStrideBytes * h.height &&
             (uint64_t)h.frameSizeBytes <= kMaxFrameBytes;
    default:
      return false;
  }
}
// Sequentially walks the file from kHeaderSize, reading only each frame's
// FrameMeta (kFrameMetaSize bytes -- cheap, not the full payload) and using
// payloadBytes to skip to the next record. Stops at EOF or at the first
// record that doesn't fit in the remaining file size (truncated/corrupt
// tail, or an unfinalized crash/battery-pull file -- same "stop cleanly,
// don't fail the whole open" behavior the old frameCount==0 scan-recovery
// path had, now handling variable stride too since it no longer assumes a
// constant per-frame size). `frameSizeCeiling` bounds payloadBytes the same
// way headerSane() bounds frameSizeBytes -- readFrame()'s caller-owned
// payload buffer is sized to this ceiling, so a crafted payloadBytes that
// fits the file but exceeds it would otherwise overflow that buffer.
std::vector<uint64_t> scanOffsets(int fd, uint64_t fileSize, uint32_t frameSizeCeiling) {
  std::vector<uint64_t> offsets;
  uint64_t pos = kHeaderSize;
  while (pos + kFrameMetaSize <= fileSize) {
    FrameMeta meta{};
    if (!io::seekTo(fd, pos) || !io::readAll(fd, &meta, sizeof meta)) break;
    if (meta.payloadBytes > frameSizeCeiling) break;  // exceeds the allocation ceiling
    uint64_t recordEnd = pos + kFrameMetaSize + meta.payloadBytes;
    if (recordEnd > fileSize) break;  // truncated tail -- stop here, don't include it
    offsets.push_back(pos);
    pos = recordEnd;
  }
  return offsets;
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
  const int64_t sz = io::fileSize(fd);
  // The header's frameCount is corruption-controlled and is no longer
  // trusted at all: scanOffsets() re-derives the true, on-disk frame count
  // (and each frame's real offset) directly from the records actually
  // present, for both fixed- and variable-stride files.
  std::vector<uint64_t> offsets = scanOffsets(fd, sz > 0 ? (uint64_t)sz : 0, h.frameSizeBytes);
  return std::unique_ptr<RawvReader>(new RawvReader(fd, h, std::move(offsets)));
}

bool RawvReader::readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload) {
  if (index >= offsets_.size()) return false;
  if (!io::seekTo(fd_, offsets_[index])) return false;
  if (!io::readAll(fd_, meta, sizeof *meta)) return false;
  if (meta->payloadBytes > 0 && !io::readAll(fd_, payload, meta->payloadBytes)) return false;
  return true;
}

RawvReader::~RawvReader() { if (fd_ >= 0) io::closeFd(fd_); }

}  // namespace rawcam
