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
