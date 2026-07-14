#include "rawcam/exporter.h"

#include <cstdio>
#include <vector>

#include "rawcam/dng_writer.h"
#include "rawcam/pack10.h"
#include "rawcam/rawv_reader.h"

namespace rawcam {

bool exportClip(const std::string& rawvPath, const std::string& outDir,
                 const std::function<bool(uint64_t done, uint64_t total)>& progress) {
  auto reader = RawvReader::open(rawvPath);
  if (!reader) return false;

  const FileHeader& srcHdr = reader->header();
  const uint64_t total = reader->frameCount();
  const bool packed = (PackMode)srcHdr.packMode == PackMode::Packed10;
  const size_t pixelCount = (size_t)srcHdr.width * (size_t)srcHdr.height;

  // Header passed to writeDng: for Packed10, the payload we hand it is
  // contiguous (post-unpack) RAW16, not the original strided sensor layout,
  // so rowStrideBytes must be synthesized to width*2. Raw16 clips are already
  // stride-padded and keep their original rowStrideBytes.
  FileHeader dngHdr = srcHdr;
  if (packed) dngHdr.rowStrideBytes = srcHdr.width * 2;

  std::vector<uint8_t> payload(srcHdr.frameSizeBytes);
  std::vector<uint16_t> unpacked;
  if (packed) unpacked.resize(pixelCount);

  for (uint64_t i = 0; i < total; i++) {
    FrameMeta meta{};
    if (!reader->readFrame(i, &meta, payload.data())) return false;

    const uint8_t* raw16;
    if (packed) {
      unpack10(payload.data(), pixelCount, unpacked.data());
      raw16 = reinterpret_cast<const uint8_t*>(unpacked.data());
    } else {
      raw16 = payload.data();
    }

    char name[32];
    std::snprintf(name, sizeof name, "%06llu.dng", (unsigned long long)i);
    std::string path = outDir + "/" + name;
    if (!writeDng(path, dngHdr, meta, raw16)) return false;

    if (progress && !progress(i + 1, total)) return false;  // cancelled
  }
  return true;
}

}  // namespace rawcam
