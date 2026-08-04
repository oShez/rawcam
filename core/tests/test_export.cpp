#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/exporter.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/pack10.h"
#include "rawcam/rawv_codec.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <map>
#include <utility>
#include <vector>
#ifdef _WIN32
#include <direct.h>
#else
#include <sys/stat.h>
#endif

using namespace rawcam;

static int makeDir(const char* path) {
#ifdef _WIN32
  return _mkdir(path);
#else
  return mkdir(path, 0755);
#endif
}

static void removeDir(const char* path) {
#ifdef _WIN32
  _rmdir(path);
#else
  rmdir(path);
#endif
}

// Mirrors the real recording pipeline (capture.cpp): live clips are always
// written Packed10, with rowStrideBytes recording the ORIGINAL sensor stride
// (unused by the packed payload itself) and frameSizeBytes == packed10Size.
static FileHeader packed10Header(uint32_t frameSize) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;  // original sensor stride, for reference
  h.packMode = (uint32_t)PackMode::Packed10;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameSize;
  std::strcpy(h.deviceName, "hosttest");
  return h;
}

static void writePacked10Clip(const char* path, int frames) {
  const size_t count = 4 * 2;  // width * height
  const uint32_t packedSize = (uint32_t)packed10Size(count);
  auto w = RawvWriter::create(path, packed10Header(packedSize));
  std::vector<uint8_t> packed(packedSize);
  std::vector<uint16_t> px(count);
  for (int i = 0; i < frames; i++) {
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i; m.payloadBytes = packedSize;
    for (size_t p = 0; p < count; p++) px[p] = (uint16_t)((i * 10 + (int)p) & 0x3FF);
    pack10(px.data(), count, packed.data());
    CHECK(w->writeFrame(m, packed.data(), packedSize));
  }
  CHECK(w->finalize());
}

// Mirrors packed10Header/writePacked10Clip for the Packed12 path.
static FileHeader packed12Header(uint32_t frameSize) {
  FileHeader h = packed10Header(frameSize);
  h.packMode = (uint32_t)PackMode::Packed12;
  h.whiteLevel = 4095;
  return h;
}

static void writePacked12Clip(const char* path, int frames) {
  const size_t count = 4 * 2;  // width * height
  const uint32_t packedSize = (uint32_t)packed12Size(count);
  auto w = RawvWriter::create(path, packed12Header(packedSize));
  std::vector<uint8_t> packed(packedSize);
  std::vector<uint16_t> px(count);
  for (int i = 0; i < frames; i++) {
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i; m.payloadBytes = packedSize;
    for (size_t p = 0; p < count; p++) px[p] = (uint16_t)((i * 10 + (int)p) & 0xFFF);
    pack12(px.data(), count, packed.data());
    CHECK(w->writeFrame(m, packed.data(), packedSize));
  }
  CHECK(w->finalize());
}

// Minimal TIFF/IFD tag reader, mirroring test_dng_writer.cpp's parseIfd --
// this project's DNG writer output can't be opened by Pillow, so exported
// pixel content is verified by hand-parsing the IFD instead.
struct TagVal { uint16_t type; uint32_t count; uint32_t valueOrOffset; };

static std::map<uint16_t, TagVal> parseIfd(const std::vector<uint8_t>& b) {
  REQUIRE(b.size() > 8);
  REQUIRE(b[0] == 'I'); REQUIRE(b[1] == 'I');  // little-endian TIFF
  uint32_t ifdOff; std::memcpy(&ifdOff, &b[4], 4);
  uint16_t n; std::memcpy(&n, &b[ifdOff], 2);
  std::map<uint16_t, TagVal> tags;
  for (uint16_t i = 0; i < n; i++) {
    const uint8_t* e = &b[ifdOff + 2 + i * 12];
    uint16_t tag, type; uint32_t count, val;
    std::memcpy(&tag, e, 2); std::memcpy(&type, e + 2, 2);
    std::memcpy(&count, e + 4, 4); std::memcpy(&val, e + 8, 4);
    tags[tag] = {type, count, val};
  }
  return tags;
}

// Mirrors capture.cpp's CompressedPredictive geometry contract: rowStrideBytes
// is the real (here, unpadded) sensor stride, and frameSizeBytes is only an
// allocation ceiling -- the real per-frame size lives in FrameMeta.payloadBytes.
static FileHeader compressedHeader(uint32_t width, uint32_t height, uint32_t frameSizeCeiling) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = width; h.height = height; h.rowStrideBytes = width * 2;
  h.packMode = (uint32_t)PackMode::CompressedPredictive;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameSizeCeiling;
  std::strcpy(h.deviceName, "hosttest");
  return h;
}

TEST_CASE("exportClip decodes a CompressedPredictive frame to match the original source") {
  const char* clipPath = "export_compressed.rawv";
  const char* outDir = "export_compressed_out";
  const uint32_t width = 8, height = 4;
  const uint32_t rowStrideSamples = width;  // no padding, for a simple index check below
  const uint32_t ceiling = width * 2 * height;

  std::vector<uint16_t> src(width * height);
  for (uint32_t y = 0; y < height; y++)
    for (uint32_t x = 0; x < width; x++)
      src[y * width + x] = (uint16_t)(((x + y) * 37) % 1024);

  std::vector<uint8_t> compressed(ceiling + 64);
  uint32_t n = encodeFrame(src.data(), width, height, rowStrideSamples, /*bitDepth=*/10,
                            compressed.data(), (uint32_t)compressed.size());
  REQUIRE(n > 0);

  auto w = RawvWriter::create(clipPath, compressedHeader(width, height, ceiling));
  REQUIRE(w != nullptr);
  FrameMeta m{};
  m.frameIndex = 0; m.payloadBytes = n; m.compressed = 1;
  m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  CHECK(w->writeFrame(m, compressed.data(), n));
  CHECK(w->finalize());

  makeDir(outDir);
  bool ok = exportClip(clipPath, outDir, [](uint64_t, uint64_t) { return true; });
  CHECK(ok);

  char name[64];
  std::snprintf(name, sizeof name, "%s/%06d.dng", outDir, 0);
  int fd = io::openRead(name);
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);

  auto tags = parseIfd(b);
  uint32_t off = tags.at(273).valueOrOffset;
  for (uint32_t y = 0; y < height; y++) {
    for (uint32_t x = 0; x < width; x++) {
      uint16_t px;
      std::memcpy(&px, &b[off + (y * width + x) * 2], 2);
      CHECK(px == src[y * width + x]);
    }
  }

  std::remove(name);
  std::remove(clipPath);
  removeDir(outDir);
}

TEST_CASE("exportClip passes a stored-fallback CompressedPredictive frame through unchanged") {
  const char* clipPath = "export_stored_fallback.rawv";
  const char* outDir = "export_stored_fallback_out";
  const uint32_t width = 4, height = 2;
  const uint32_t ceiling = width * 2 * height;

  std::vector<uint16_t> src(width * height);
  for (uint32_t i = 0; i < width * height; i++) src[i] = (uint16_t)(i * 11 + 3);

  auto w = RawvWriter::create(clipPath, compressedHeader(width, height, ceiling));
  REQUIRE(w != nullptr);
  FrameMeta m{};
  m.frameIndex = 0; m.payloadBytes = ceiling; m.compressed = 0;  // encode fell back to stored
  m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  CHECK(w->writeFrame(m, reinterpret_cast<const uint8_t*>(src.data()), ceiling));
  CHECK(w->finalize());

  makeDir(outDir);
  bool ok = exportClip(clipPath, outDir, [](uint64_t, uint64_t) { return true; });
  CHECK(ok);

  char name[64];
  std::snprintf(name, sizeof name, "%s/%06d.dng", outDir, 0);
  int fd = io::openRead(name);
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);

  auto tags = parseIfd(b);
  uint32_t off = tags.at(273).valueOrOffset;
  for (uint32_t i = 0; i < width * height; i++) {
    uint16_t px;
    std::memcpy(&px, &b[off + i * 2], 2);
    CHECK(px == src[i]);
  }

  std::remove(name);
  std::remove(clipPath);
  removeDir(outDir);
}

TEST_CASE("exportClip writes one DNG per frame (Packed12 unpack path)") {
  const char* clipPath = "export_test12.rawv";
  const char* outDir = "export_test12_out";
  writePacked12Clip(clipPath, 2);
  makeDir(outDir);

  bool ok = exportClip(clipPath, outDir, [](uint64_t, uint64_t) { return true; });
  CHECK(ok);

  for (int i = 0; i < 2; i++) {
    char name[64];
    std::snprintf(name, sizeof name, "%s/%06d.dng", outDir, i);
    int fd = io::openRead(name);
    CHECK(fd >= 0);
    if (fd >= 0) {
      CHECK(io::fileSize(fd) > 0);
      io::closeFd(fd);
    }
    std::remove(name);
  }

  std::remove(clipPath);
  removeDir(outDir);
}

TEST_CASE("exportClip writes one DNG per frame (Packed10 unpack path) and reports progress") {
  const char* clipPath = "export_test.rawv";
  const char* outDir = "export_test_out";
  writePacked10Clip(clipPath, 3);
  makeDir(outDir);

  // Frames are processed by a worker pool (see exporter.cpp); progress is
  // still only ever invoked from the original caller thread (never a worker),
  // so this callback needs no locking of its own. But because several frames
  // can finish between wakeups of that reporting thread, a caller may see
  // fewer than one call per frame -- only strict ordering and the final
  // (total,total) call are guaranteed, not a 1:1 call-per-frame count.
  std::vector<std::pair<uint64_t, uint64_t>> calls;
  bool ok = exportClip(clipPath, outDir, [&](uint64_t done, uint64_t total) {
    calls.push_back({done, total});
    return true;
  });

  CHECK(ok);
  REQUIRE(calls.size() >= 1);
  for (size_t i = 1; i < calls.size(); i++) CHECK(calls[i].first > calls[i - 1].first);
  CHECK(calls.back().first == 3);
  CHECK(calls.back().second == 3);

  for (int i = 0; i < 3; i++) {
    char name[64];
    std::snprintf(name, sizeof name, "%s/%06d.dng", outDir, i);
    int fd = io::openRead(name);
    CHECK(fd >= 0);
    if (fd >= 0) {
      CHECK(io::fileSize(fd) > 0);
      io::closeFd(fd);
    }
    std::remove(name);
  }

  std::remove(clipPath);
  removeDir(outDir);
}

TEST_CASE("exportClip stops and returns false when progress callback cancels") {
  const char* clipPath = "export_cancel.rawv";
  const char* outDir = "export_cancel_out";
  writePacked10Clip(clipPath, 3);
  makeDir(outDir);

  int callCount = 0;
  bool ok = exportClip(clipPath, outDir, [&](uint64_t /*done*/, uint64_t /*total*/) {
    callCount++;
    return false;  // cancel on the first progress callback
  });

  // With a worker pool, several frames can already be claimed (and finish
  // writing) before the caller's very first "cancel" is observed, so which
  // frame files exist afterward is no longer deterministic -- only that the
  // export overall reports non-completion is guaranteed.
  CHECK_FALSE(ok);
  CHECK(callCount >= 1);

  std::remove(clipPath);
  for (int i = 0; i < 3; i++) {
    char name[64];
    std::snprintf(name, sizeof name, "%s/%06d.dng", outDir, i);
    std::remove(name);
  }
  removeDir(outDir);
}
