#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"
#include "rawcam/pack10.h"
#include <algorithm>
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
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i; m.payloadBytes = (uint32_t)p.size();
    std::memset(p.data(), i, p.size());
    w->writeFrame(m, p.data(), (uint32_t)p.size());
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

TEST_CASE("open rejects headers whose geometry contradicts frameSizeBytes") {
  auto corruptAndTry = [](void (*mutate)(FileHeader*)) {
    writeClip("bad.rawv", 1);
    int fd = io::openRead("bad.rawv");
    std::vector<uint8_t> bytes((size_t)io::fileSize(fd));
    io::readAll(fd, bytes.data(), bytes.size());
    io::closeFd(fd);
    mutate(reinterpret_cast<FileHeader*>(bytes.data()));
    fd = io::openWrite("bad.rawv");
    io::writeAll(fd, bytes.data(), bytes.size());
    io::closeFd(fd);
    bool rejected = RawvReader::open("bad.rawv") == nullptr;
    std::remove("bad.rawv");
    return rejected;
  };
  // Raw16: frameSizeBytes smaller than rowStrideBytes*height -> OOB read in consumers
  CHECK(corruptAndTry([](FileHeader* h) { h->frameSizeBytes = 8; }));
  // Raw16: stride can't cover a row
  CHECK(corruptAndTry([](FileHeader* h) { h->rowStrideBytes = 4; }));
  // Packed10: frameSizeBytes below packed10Size(w*h)
  CHECK(corruptAndTry([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed10; h->frameSizeBytes = 4;
  }));
  // Packed12: frameSizeBytes below packed12Size(w*h)
  CHECK(corruptAndTry([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed12; h->frameSizeBytes = 4;
  }));
  // Packed12: pixel count not a multiple of 2 -> unpack12 walks off the tail
  // group's buffers during export.
  CHECK(corruptAndTry([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed12;
    h->width = 3; h->height = 1; h->frameSizeBytes = (uint32_t)packed12Size(3);
  }));
  // absurd geometry (multi-GB allocations downstream)
  CHECK(corruptAndTry([](FileHeader* h) { h->width = 1u << 30; }));
  CHECK(corruptAndTry([](FileHeader* h) { h->height = 0; }));
  // unknown pack mode
  CHECK(corruptAndTry([](FileHeader* h) { h->packMode = 7; }));
  // Packed10: pixel count not a multiple of 4 -> unpack10 walks off the tail
  // group's buffers during export. packed10Size(9)==10 so this passed the old
  // `frameSizeBytes >= packed10Size` lower bound and reached the unpacker.
  CHECK(corruptAndTry([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed10;
    h->width = 3; h->height = 3; h->frameSizeBytes = 10;
  }));
  // Packed10: frameSizeBytes must equal packed10Size exactly, not merely exceed
  // it (packed10Size(8)==10; an over-stated size is a malformed/hostile file).
  CHECK(corruptAndTry([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed10;
    h->width = 4; h->height = 2; h->frameSizeBytes = 1000;
  }));
  // Raw16: frameSizeBytes above the allocation cap -> multi-GB payload buffer
  CHECK(corruptAndTry([](FileHeader* h) { h->frameSizeBytes = 0xFFFFFFFFu; }));
}

TEST_CASE("open accepts valid packed10 and clamps an overstated frameCount") {
  auto rebuildWith = [](void (*mutate)(FileHeader*)) {
    writeClip("ok.rawv", 5);
    int fd = io::openRead("ok.rawv");
    std::vector<uint8_t> bytes((size_t)io::fileSize(fd));
    io::readAll(fd, bytes.data(), bytes.size());
    io::closeFd(fd);
    mutate(reinterpret_cast<FileHeader*>(bytes.data()));
    fd = io::openWrite("ok.rawv");
    io::writeAll(fd, bytes.data(), bytes.size());
    io::closeFd(fd);
  };

  // A finalized header that lies about frameCount (999 vs 5 records on disk)
  // must clamp to what is actually present rather than seeking past EOF.
  rebuildWith([](FileHeader* h) { h->frameCount = 999; });
  auto r = RawvReader::open("ok.rawv");
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 5);
  std::remove("ok.rawv");

  // A well-formed Packed10 header (w*h % 4 == 0, frameSizeBytes == packed10Size)
  // is still accepted after the tightening.
  rebuildWith([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed10;
    h->width = 4; h->height = 2; h->frameSizeBytes = 10;  // packed10Size(8)
  });
  auto r2 = RawvReader::open("ok.rawv");
  CHECK(r2 != nullptr);
  std::remove("ok.rawv");

  // A well-formed Packed12 header (w*h % 2 == 0, frameSizeBytes == packed12Size).
  rebuildWith([](FileHeader* h) {
    h->packMode = (uint32_t)PackMode::Packed12;
    h->width = 4; h->height = 2; h->frameSizeBytes = 12;  // packed12Size(8)
  });
  auto r3 = RawvReader::open("ok.rawv");
  CHECK(r3 != nullptr);
  std::remove("ok.rawv");
}

TEST_CASE("readFrame handles a mix of compressed and stored-fallback frames") {
  const char* path = "mixed.rawv";
  FileHeader hdr = testHeader();
  hdr.frameSizeBytes = 1000;  // allocation ceiling, matches the compressed-mode contract
  auto w = RawvWriter::create(path, hdr);
  REQUIRE(w != nullptr);

  // Frame 0: compressed, payloadBytes well under the ceiling.
  FrameMeta m0{}; m0.frameIndex = 0; m0.payloadBytes = 250; m0.compressed = 1;
  std::vector<uint8_t> c0(250, 0xAB);
  REQUIRE(w->writeFrame(m0, c0.data(), 250));

  // Frame 1: stored-fallback, payloadBytes == the ceiling.
  FrameMeta m1{}; m1.frameIndex = 1; m1.payloadBytes = 1000; m1.compressed = 0;
  std::vector<uint8_t> s1(1000, 0xCD);
  REQUIRE(w->writeFrame(m1, s1.data(), 1000));

  // Frame 2: compressed again, a different size than frame 0's -- proves
  // offsets are tracked per-frame, not assumed constant after the first one.
  FrameMeta m2{}; m2.frameIndex = 2; m2.payloadBytes = 90; m2.compressed = 1;
  std::vector<uint8_t> c2(90, 0xEF);
  REQUIRE(w->writeFrame(m2, c2.data(), 90));

  REQUIRE(w->finalize());

  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 3);

  std::vector<uint8_t> buf(1000);  // sized to the ceiling, per readFrame's contract
  FrameMeta out{};
  REQUIRE(r->readFrame(0, &out, buf.data()));
  CHECK(out.compressed == 1);
  CHECK(out.payloadBytes == 250);
  CHECK(std::equal(buf.begin(), buf.begin() + 250, c0.begin()));

  REQUIRE(r->readFrame(1, &out, buf.data()));
  CHECK(out.compressed == 0);
  CHECK(out.payloadBytes == 1000);
  CHECK(std::equal(buf.begin(), buf.begin() + 1000, s1.begin()));

  REQUIRE(r->readFrame(2, &out, buf.data()));
  CHECK(out.compressed == 1);
  CHECK(out.payloadBytes == 90);
  CHECK(std::equal(buf.begin(), buf.begin() + 90, c2.begin()));

  std::remove(path);
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
