#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv.h"

TEST_CASE("container structs have fixed on-disk sizes") {
  CHECK(sizeof(rawcam::FileHeader) == rawcam::kHeaderSize);
  CHECK(sizeof(rawcam::FrameMeta) == rawcam::kFrameMetaSize);
  CHECK(rawcam::kHeaderSize == 512);
  CHECK(rawcam::kFrameMetaSize == 64);
}

TEST_CASE("magic spells RAWV little-endian") {
  CHECK(rawcam::kMagic == 0x56574152u);
}

TEST_CASE("FrameMeta stays 64 bytes after adding payloadBytes/compressed") {
  CHECK(sizeof(rawcam::FrameMeta) == rawcam::kFrameMetaSize);
}

TEST_CASE("PackMode::CompressedPredictive has value 3") {
  CHECK(static_cast<uint32_t>(rawcam::PackMode::CompressedPredictive) == 3u);
}
