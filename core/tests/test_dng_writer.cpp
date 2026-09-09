#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/dng_writer.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>

using namespace rawcam;

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

TEST_CASE("dng has required CFA tags and correct pixel strip") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 12;  // 4px*2B + 4B pad per row
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  std::strcpy(h.deviceName, "Pixel Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;

  // stride-padded source: pixel value = row*100 + col
  uint8_t src[24] = {};
  for (int r = 0; r < 2; r++)
    for (int c = 0; c < 4; c++) {
      uint16_t v = (uint16_t)(r * 100 + c);
      std::memcpy(src + r * 12 + c * 2, &v, 2);
    }

  REQUIRE(writeDng("t.dng", h, m, src));

  int fd = io::openRead("t.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);

  auto tags = parseIfd(b);
  CHECK(tags.at(256).valueOrOffset == 4);       // width
  CHECK(tags.at(257).valueOrOffset == 2);       // height
  CHECK(tags.at(262).valueOrOffset == 32803);   // CFA photometric
  CHECK(tags.at(279).valueOrOffset == 16);      // 4*2*2 bytes, de-strided
  CHECK(tags.count(50706) == 1);                // DNGVersion
  CHECK(tags.count(50721) == 1);                // ColorMatrix1
  CHECK(tags.at(50717).valueOrOffset == 1023);  // WhiteLevel
  // BlackLevelRepeatDim [2,2] must accompany the 4-entry BlackLevel — DNG
  // defaults to [1,1] without it, and Resolve rejects the file (media offline)
  REQUIRE(tags.count(50713) == 1);
  CHECK(tags.at(50713).type == 3);              // SHORT
  CHECK(tags.at(50713).count == 2);
  CHECK(tags.at(50713).valueOrOffset == (2u | (2u << 16)));
  CHECK(tags.at(50714).count == 4);             // BlackLevel per CFA site
  // CFAPattern RGGB fits inline in value field: bytes 0,1,1,2
  uint32_t cfa = tags.at(33422).valueOrOffset;
  CHECK((cfa & 0xFF) == 0); CHECK(((cfa >> 8) & 0xFF) == 1);
  // strip is de-strided pixels
  uint32_t off = tags.at(273).valueOrOffset;
  uint16_t px5; std::memcpy(&px5, &b[off + 5 * 2], 2);  // row1 col1 => 101
  CHECK(px5 == 101);
  CHECK(tags.at(50778).valueOrOffset == 21);  // illuminant1 unset -> D65 default
  CHECK(tags.count(50779) == 0);              // no second illuminant -> no CalibrationIlluminant2
  CHECK(tags.count(50722) == 0);              // ...and no ColorMatrix2 either
  std::remove("t.dng");
}

TEST_CASE("dng omits ColorMatrix2/CalibrationIlluminant2 when illuminant2 is unset") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 2; h.height = 2; h.rowStrideBytes = 4;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.illuminant1 = 17;  // a real, non-D65 illuminant1 must still come through as-is
  h.illuminant2 = 0;   // sentinel: sensor exposed no second calibration point
  for (int i = 0; i < 9; i++) h.colorMatrix2[i] = 999.0f;  // must be ignored, not written
  std::strcpy(h.deviceName, "Single Illuminant Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  uint8_t src[8] = {};

  REQUIRE(writeDng("single_illum.dng", h, m, src));
  int fd = io::openRead("single_illum.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);
  auto tags = parseIfd(b);

  CHECK(tags.at(50778).valueOrOffset == 17);  // real illuminant1 passed through
  CHECK(tags.count(50779) == 0);
  CHECK(tags.count(50722) == 0);
  std::remove("single_illum.dng");
}

TEST_CASE("dng writes ColorMatrix2/CalibrationIlluminant2 when the sensor has two calibration points") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 2; h.height = 2; h.rowStrideBytes = 4;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.illuminant1 = 21;  // D65
  h.illuminant2 = 17;  // StandardA
  h.colorMatrix2[0] = 0.5f; h.colorMatrix2[4] = 0.6f; h.colorMatrix2[8] = 0.7f;
  std::strcpy(h.deviceName, "Dual Illuminant Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  uint8_t src[8] = {};

  REQUIRE(writeDng("dual_illum.dng", h, m, src));
  int fd = io::openRead("dual_illum.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);
  auto tags = parseIfd(b);

  CHECK(tags.at(50778).valueOrOffset == 21);
  REQUIRE(tags.count(50779) == 1);
  CHECK(tags.at(50779).valueOrOffset == 17);
  REQUIRE(tags.count(50722) == 1);  // ColorMatrix2 present
  std::remove("dual_illum.dng");
}

TEST_CASE("dng embeds an XMP videoFrameRate tag derived from fpsNum/fpsDen") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 2; h.height = 2; h.rowStrideBytes = 4;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.fpsNum = 24000; h.fpsDen = 1001;  // NTSC-style 23.976fps
  std::strcpy(h.deviceName, "Fps Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  uint8_t src[8] = {};

  REQUIRE(writeDng("fps.dng", h, m, src));
  int fd = io::openRead("fps.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);
  auto tags = parseIfd(b);

  REQUIRE(tags.count(700) == 1);  // XMP
  auto xmpTag = tags.at(700);
  std::string xmp(reinterpret_cast<const char*>(&b[xmpTag.valueOrOffset]), xmpTag.count);
  CHECK(xmp.find("xmpDM:videoFrameRate") != std::string::npos);
  CHECK(xmp.find("23.976024") != std::string::npos);
  std::remove("fps.dng");
}

TEST_CASE("dng omits the XMP tag when fpsDen is zero (no known frame rate)") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 2; h.height = 2; h.rowStrideBytes = 4;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.fpsNum = 0; h.fpsDen = 0;
  std::strcpy(h.deviceName, "No Fps Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;
  uint8_t src[8] = {};

  REQUIRE(writeDng("no_fps.dng", h, m, src));
  int fd = io::openRead("no_fps.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);
  auto tags = parseIfd(b);

  CHECK(tags.count(700) == 0);
  std::remove("no_fps.dng");
}

// ---- TimeCode (tag 51043). Eight bytes will not fit in a TIFF entry's 4-byte
// value field, so the entry must hold an OFFSET into the data area -- reading
// the timecode back through that offset is what these tests actually prove.

// A header whose take-start anchor is 20000 days past the epoch at 12:33:33,
// in a zone with no UTC offset, recorded at exactly 24 fps.
static FileHeader anchoredHeader() {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 2; h.height = 2; h.rowStrideBytes = 4;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  h.fpsNum = 24; h.fpsDen = 1;
  h.startEpochNs = 20000LL * 86400000000000LL + 45213000000000LL;
  h.tzOffsetSec = 0;
  std::strcpy(h.deviceName, "Timecode Test");
  return h;
}

static std::vector<uint8_t> writeAndRead(const char* path, const FileHeader& h,
                                         const FrameMeta& m) {
  uint8_t src[8] = {};
  REQUIRE(writeDng(path, h, m, src));
  int fd = io::openRead(path);
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);
  std::remove(path);
  return b;
}

// Eight bytes cannot fit in a TIFF entry, so the value field must be a real
// data-area OFFSET. Bounds-check it before dereferencing: if this is ever
// routed through an inline entry instead, the field holds timecode bytes
// reinterpreted as an offset, and that mistake should fail legibly here
// rather than reading off the end of the file buffer.
static const uint8_t* timecodeAt(const std::vector<uint8_t>& b, const TagVal& t) {
  REQUIRE(t.type == 1);  // BYTE
  REQUIRE(t.count == 8);
  REQUIRE(t.valueOrOffset >= 8);
  REQUIRE((size_t)t.valueOrOffset + 8 <= b.size());
  return &b[t.valueOrOffset];
}

TEST_CASE("dng stamps frame 0 with the take-start timecode") {
  FrameMeta m{}; m.frameIndex = 0;
  auto b = writeAndRead("tc_frame0.dng", anchoredHeader(), m);
  auto tags = parseIfd(b);

  REQUIRE(tags.count(51043) == 1);
  // BCD, so the bytes read like a clock in hex: 12:33:33:00.
  const uint8_t* tc = timecodeAt(b, tags.at(51043));
  CHECK(tc[0] == 0x00);  // frames
  CHECK(tc[1] == 0x33);  // seconds
  CHECK(tc[2] == 0x33);  // minutes
  CHECK(tc[3] == 0x12);  // hours
  CHECK(tc[4] == 0x00);
  CHECK(tc[5] == 0x00);
  CHECK(tc[6] == 0x00);
  CHECK(tc[7] == 0x00);
}

TEST_CASE("each frame is stamped with its own position in the take") {
  FrameMeta m{}; m.frameIndex = 25;  // one second and one frame in, at 24 fps
  auto b = writeAndRead("tc_frame25.dng", anchoredHeader(), m);
  auto tags = parseIfd(b);
  REQUIRE(tags.count(51043) == 1);
  const uint8_t* tc = timecodeAt(b, tags.at(51043));
  CHECK(tc[0] == 0x01);  // frames
  CHECK(tc[1] == 0x34);  // seconds ticked over
  CHECK(tc[2] == 0x33);
  CHECK(tc[3] == 0x12);
}

TEST_CASE("dng omits the timecode when the take carries no start anchor") {
  FileHeader h = anchoredHeader();
  h.startEpochNs = 0;  // a clip recorded before the anchor existed
  h.tzOffsetSec = 0;
  FrameMeta m{}; m.frameIndex = 0;
  auto b = writeAndRead("tc_none.dng", h, m);
  // Stamping 00:00:00:00 here would read to an NLE as a genuine take that
  // began at midnight, and it would sync the audio confidently against it.
  // No tag at all is the honest answer.
  CHECK(parseIfd(b).count(51043) == 0);
}

TEST_CASE("the take's local zone, not UTC, decides the stamped hour") {
  FileHeader h = anchoredHeader();  // the anchor instant is 12:33:33 UTC
  h.tzOffsetSec = -5 * 3600;        // ...but the take was shot in New York,
  FrameMeta m{}; m.frameIndex = 0;  //    where the wall clock read 07:33:33.
  auto b = writeAndRead("tc_tz.dng", h, m);
  auto tags = parseIfd(b);
  REQUIRE(tags.count(51043) == 1);
  const uint8_t* tc = timecodeAt(b, tags.at(51043));
  CHECK(tc[3] == 0x07);
  CHECK(tc[2] == 0x33);
  CHECK(tc[1] == 0x33);
}
