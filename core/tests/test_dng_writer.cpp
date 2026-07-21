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
