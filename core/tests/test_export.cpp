#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/exporter.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/pack10.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
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
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i;
    for (size_t p = 0; p < count; p++) px[p] = (uint16_t)((i * 10 + (int)p) & 0x3FF);
    pack10(px.data(), count, packed.data());
    CHECK(w->writeFrame(m, packed.data()));
  }
  CHECK(w->finalize());
}

TEST_CASE("exportClip writes one DNG per frame (Packed10 unpack path) and reports progress") {
  const char* clipPath = "export_test.rawv";
  const char* outDir = "export_test_out";
  writePacked10Clip(clipPath, 3);
  makeDir(outDir);

  std::vector<std::pair<uint64_t, uint64_t>> calls;
  bool ok = exportClip(clipPath, outDir, [&](uint64_t done, uint64_t total) {
    calls.push_back({done, total});
    return true;
  });

  CHECK(ok);
  REQUIRE(calls.size() == 3);
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
    return false;  // cancel immediately after the first frame
  });

  CHECK_FALSE(ok);
  CHECK(callCount == 1);

  int fd0 = io::openRead("export_cancel_out/000000.dng");
  CHECK(fd0 >= 0);
  if (fd0 >= 0) io::closeFd(fd0);
  int fd1 = io::openRead("export_cancel_out/000001.dng");
  CHECK(fd1 < 0);

  std::remove(clipPath);
  std::remove("export_cancel_out/000000.dng");
  removeDir(outDir);
}
