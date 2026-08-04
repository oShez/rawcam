#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <vector>

using namespace rawcam;

static FileHeader testHeader(uint32_t frameSize) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameSize;
  std::strcpy(h.deviceName, "hosttest");
  return h;
}

TEST_CASE("writer produces header + fixed records and finalizes count") {
  const char* path = "test_writer.rawv";
  const uint32_t fs = 16;  // rowStride(8) * height(2)
  {
    auto w = RawvWriter::create(path, testHeader(fs));
    REQUIRE(w != nullptr);
    std::vector<uint8_t> payload(fs);
    for (uint64_t i = 0; i < 3; i++) {
      FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = i; m.iso = 100; m.payloadBytes = fs;
      std::memset(payload.data(), (int)i, fs);
      CHECK(w->writeFrame(m, payload.data(), fs));
    }
    CHECK(w->framesWritten() == 3);
    CHECK(w->finalize());
  }
  int fd = io::openRead(path);
  REQUIRE(fd >= 0);
  CHECK(io::fileSize(fd) == (int64_t)(kHeaderSize + 3 * (kFrameMetaSize + fs)));
  FileHeader h{};
  CHECK(io::readAll(fd, &h, sizeof h));
  CHECK(h.magic == kMagic);
  CHECK(h.frameCount == 3);
  // spot-check record 1's meta
  CHECK(io::seekTo(fd, kHeaderSize + 1 * (kFrameMetaSize + fs)));
  FrameMeta m{};
  CHECK(io::readAll(fd, &m, sizeof m));
  CHECK(m.timestampNs == 1001);
  io::closeFd(fd);
  std::remove(path);
}

TEST_CASE("writeFrame accepts a payload shorter than frameSizeBytes (compressed case)") {
  FileHeader hdr = testHeader(1000);  // frameSizeBytes is only an allocation ceiling here
  auto w = RawvWriter::create("test_variable_frame.rawv", hdr);
  REQUIRE(w != nullptr);
  FrameMeta meta{};
  meta.frameIndex = 0;
  meta.payloadBytes = 250;  // actual compressed size, well under the 1000 ceiling
  meta.compressed = 1;
  std::vector<uint8_t> payload(250, 0xAB);
  CHECK(w->writeFrame(meta, payload.data(), 250));
  CHECK(w->finalize());
  std::remove("test_variable_frame.rawv");
}
