#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/preview.h"
#include <vector>

using namespace rawcam;

// One RGGB quad, black level 0, white level 1023, neutral gains.
// R=1023, both G=0, B=0 must develop to pure red at full scale.
TEST_CASE("developRaw16 bins one RGGB quad into one RGB pixel") {
  std::vector<uint16_t> raw = {1023, 0,
                               0,    0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.width == 1);
  CHECK(out.height == 1);
  CHECK(out.rgba.size() == 4);
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[1] == 0);
  CHECK(out.rgba[2] == 0);
  CHECK(out.rgba[3] == 255);
}

TEST_CASE("developRaw16 honours CFA order") {
  // The same buffer read as BGGR must put the full-scale sample in blue.
  std::vector<uint16_t> raw = {1023, 0,
                               0,    0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::BGGR, black, 1023, neutral, &out));
  CHECK(out.rgba[0] == 0);
  CHECK(out.rgba[2] == 255);
}

TEST_CASE("developRaw16 averages the two greens") {
  std::vector<uint16_t> oneGreen = {0, 1023,
                                    0, 0};
  std::vector<uint16_t> twoGreens = {0,    1023,
                                     1023, 0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage a, b;
  developRaw16(oneGreen.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &a);
  developRaw16(twoGreens.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &b);
  CHECK(b.rgba[1] > a.rgba[1]);
}

TEST_CASE("developRaw16 subtracts black level and clamps at zero") {
  // A sample below its quadrant's black level must clamp to 0, not wrap.
  std::vector<uint16_t> raw = {10, 0,
                               0,  0};
  uint32_t black[4] = {64, 64, 64, 64};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.rgba[0] == 0);
}

TEST_CASE("developRaw16 applies as-shot neutral gains") {
  std::vector<uint16_t> raw = {512, 512,
                               512, 512};
  uint32_t black[4] = {0, 0, 0, 0};
  float flat[3] = {1.0f, 1.0f, 1.0f};
  float redUp[3] = {2.0f, 1.0f, 1.0f};
  PreviewImage a, b;
  developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, flat, &a);
  developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 1023, redUp, &b);
  CHECK(b.rgba[0] > a.rgba[0]);
}

TEST_CASE("developRaw16 respects a row stride wider than the active width") {
  // 2 active columns but 4 samples per row on disk: padding must be skipped.
  std::vector<uint16_t> raw = {1023, 0, 999, 999,
                               0,    0, 999, 999};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 4, Cfa::RGGB, black, 1023, neutral, &out));
  CHECK(out.width == 1);
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[2] == 0);
}

TEST_CASE("developRaw16 rejects a zero white level") {
  std::vector<uint16_t> raw = {0, 0, 0, 0};
  uint32_t black[4] = {0, 0, 0, 0};
  float neutral[3] = {1.0f, 1.0f, 1.0f};
  PreviewImage out;
  CHECK(developRaw16(raw.data(), 2, 2, 2, Cfa::RGGB, black, 0, neutral, &out) == false);
}

static PreviewImage solid(uint32_t w, uint32_t h, uint8_t r, uint8_t g, uint8_t b) {
  PreviewImage p;
  p.width = w; p.height = h;
  p.rgba.assign((size_t)w * h * 4, 255);
  for (size_t i = 0; i < (size_t)w * h; i++) {
    p.rgba[i * 4 + 0] = r; p.rgba[i * 4 + 1] = g; p.rgba[i * 4 + 2] = b;
  }
  return p;
}

TEST_CASE("downscaleTo fits within the box and preserves aspect") {
  PreviewImage src = solid(2048, 1536, 10, 20, 30), out;
  CHECK(downscaleTo(src, 1024, 768, &out));
  CHECK(out.width == 1024);
  CHECK(out.height == 768);
}

TEST_CASE("downscaleTo is bounded by the tighter dimension") {
  // 4:3 source into a wide box: height binds, width lands below the max.
  PreviewImage src = solid(2048, 1536, 0, 0, 0), out;
  CHECK(downscaleTo(src, 4000, 768, &out));
  CHECK(out.height == 768);
  CHECK(out.width == 1024);
}

TEST_CASE("downscaleTo preserves a solid colour") {
  PreviewImage src = solid(400, 300, 10, 20, 30), out;
  CHECK(downscaleTo(src, 200, 150, &out));
  CHECK(out.rgba[0] == 10);
  CHECK(out.rgba[1] == 20);
  CHECK(out.rgba[2] == 30);
  CHECK(out.rgba[3] == 255);
}

TEST_CASE("downscaleTo never upscales") {
  PreviewImage src = solid(100, 75, 1, 2, 3), out;
  CHECK(downscaleTo(src, 1024, 768, &out));
  CHECK(out.width == 100);
  CHECK(out.height == 75);
}

TEST_CASE("downscaleTo handles odd dimensions without losing the last row") {
  PreviewImage src = solid(101, 77, 9, 9, 9), out;
  CHECK(downscaleTo(src, 50, 50, &out));
  CHECK(out.width >= 1);
  CHECK(out.height >= 1);
  CHECK(out.rgba.size() == (size_t)out.width * out.height * 4);
  CHECK(out.rgba[0] == 9);
}

#include "rawcam/rawv_writer.h"
#include "rawcam/rawv_reader.h"
#include <cstdio>

// A 4x2 RGGB frame whose first quad is full-scale red.
static std::vector<uint16_t> samplePixels(uint32_t white) {
  return { (uint16_t)white, 0, 0, 0,
           0,     0, 0, 0 };
}

static FileHeader previewHeader(PackMode mode, uint32_t white, uint32_t frameBytes) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)mode;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = white;
  h.asShotNeutral[0] = 1.0f; h.asShotNeutral[1] = 1.0f; h.asShotNeutral[2] = 1.0f;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameBytes;
  return h;
}

static void writeOneFrameClip(const char* path, const FileHeader& h,
                              const std::vector<uint16_t>& pixels, uint32_t compressed) {
  auto w = RawvWriter::create(path, h);
  FrameMeta m{};
  m.frameIndex = 0;
  m.payloadBytes = (uint32_t)(pixels.size() * 2);
  m.compressed = compressed;
  w->writeFrame(m, reinterpret_cast<const uint8_t*>(pixels.data()), m.payloadBytes);
  w->finalize();
}

TEST_CASE("developFrame develops a Raw16 clip") {
  const char* path = "preview_raw16.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 1023, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out));
  CHECK(out.width == 2);
  CHECK(out.height == 1);
  CHECK(out.rgba[0] == 255);  // full-scale red in the first quad
  CHECK(out.rgba[2] == 0);
  std::remove(path);
}

TEST_CASE("developFrame refuses an out-of-range index") {
  const char* path = "preview_range.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 1023, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 99, 1024, 768, &out) == false);
  std::remove(path);
}

TEST_CASE("developFrame rejects a clip with a zero white level") {
  const char* path = "preview_nowhite.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::Raw16, 0, 16), samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out) == false);
  std::remove(path);
}

TEST_CASE("a stored-fallback frame in a compressed clip develops as plain RAW16") {
  // packMode says CompressedPredictive but meta.compressed == 0: the payload is
  // raw, and feeding it to the Rice decoder would produce garbage.
  const char* path = "preview_fallback.rawv";
  writeOneFrameClip(path, previewHeader(PackMode::CompressedPredictive, 1023, 64),
                    samplePixels(1023), 0);
  auto reader = RawvReader::open(path);
  REQUIRE(reader != nullptr);
  PreviewImage out;
  CHECK(developFrame(*reader, 0, 1024, 768, &out));
  CHECK(out.rgba[0] == 255);
  CHECK(out.rgba[2] == 0);
  std::remove(path);
}
