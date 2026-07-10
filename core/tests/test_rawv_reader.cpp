#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <vector>

using namespace rawcam;

static FileHeader testHeader() {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = 16;
  return h;
}

static void writeClip(const char* path, int frames) {
  auto w = RawvWriter::create(path, testHeader());
  std::vector<uint8_t> p(16);
  for (int i = 0; i < frames; i++) {
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i;
    std::memset(p.data(), i, p.size());
    w->writeFrame(m, p.data());
  }
  w->finalize();
}

TEST_CASE("round trip") {
  writeClip("rt.rawv", 5);
  auto r = RawvReader::open("rt.rawv");
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 5);
  FrameMeta m{}; std::vector<uint8_t> p(16);
  CHECK(r->readFrame(4, &m, p.data()));
  CHECK(m.timestampNs == 1004);
  CHECK(p[0] == 4);
  std::remove("rt.rawv");
}

TEST_CASE("truncated file recovers whole frames only") {
  writeClip("tr.rawv", 5);
  // simulate crash: chop mid-record AND zero the header count like an unfinalized file
  int fd = io::openRead("tr.rawv");
  int64_t full = io::fileSize(fd);
  io::closeFd(fd);
  std::vector<uint8_t> bytes(full);
  fd = io::openRead("tr.rawv");
  io::readAll(fd, bytes.data(), bytes.size());
  io::closeFd(fd);
  FileHeader* h = reinterpret_cast<FileHeader*>(bytes.data());
  h->frameCount = 0;
  size_t cut = kHeaderSize + 3 * (kFrameMetaSize + 16) + 7;  // mid 4th record
  fd = io::openWrite("tr.rawv");
  io::writeAll(fd, bytes.data(), cut);
  io::closeFd(fd);

  auto r = RawvReader::open("tr.rawv");
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 3);   // partial 4th frame discarded
  FrameMeta m{}; std::vector<uint8_t> p(16);
  CHECK(r->readFrame(2, &m, p.data()));
  CHECK(m.timestampNs == 1002);
  CHECK_FALSE(r->readFrame(3, &m, p.data()));
  std::remove("tr.rawv");
}
