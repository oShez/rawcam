#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include <cstddef>

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

// The take-start anchor (piece 2 of timecode auto-sync) is carved out of the
// reserved block rather than appended, so kVersion stays 5 and every clip
// already on a user's device still opens. That only holds if not one
// pre-existing field moves: those clips are read back through THIS struct, and
// a shifted offset would garble them without tripping any version check.
// 268 is where audioFileName -- the last pre-anchor field -- has always sat,
// and 332 is where reserved[] began.
TEST_CASE("take-start anchor is carved out of reserved without moving a field") {
  using rawcam::FileHeader;
  CHECK(offsetof(FileHeader, audioFileName) == 268);
  CHECK(offsetof(FileHeader, startEpochNs) == 332);
  CHECK(offsetof(FileHeader, tzOffsetSec) == 340);
  CHECK(offsetof(FileHeader, reserved) == 344);
  CHECK(sizeof(FileHeader) == rawcam::kHeaderSize);
}
