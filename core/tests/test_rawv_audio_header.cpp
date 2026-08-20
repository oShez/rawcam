#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "rawcam/rawv.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/rawv_writer.h"

using namespace rawcam;

namespace {

// Fills a header-sane fixture so version handling can be exercised directly.
FileHeader baseHeader(uint32_t version) {
  FileHeader h{};
  h.magic = kMagic;
  h.version = version;
  h.width = 4;
  h.height = 2;
  h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  h.fpsNum = 24;
  h.fpsDen = 1;
  h.frameSizeBytes = 16;
  h.illuminant1 = 21;
  std::snprintf(h.deviceName, sizeof h.deviceName, "fixture");
  return h;
}

std::string writeFixture(uint32_t version, uint32_t frames) {
  static int counter = 0;
  std::string path = "test_audio_hdr_" + std::to_string(counter++) + ".rawv";
  FileHeader h = baseHeader(version);
  std::FILE* f = std::fopen(path.c_str(), "wb");
  REQUIRE(f != nullptr);
  std::fwrite(&h, sizeof h, 1, f);
  std::vector<uint8_t> payload(h.frameSizeBytes, 0);
  for (uint32_t i = 0; i < frames; ++i) {
    FrameMeta m{};
    m.timestampNs = 1000 + i;
    m.frameIndex = i;
    m.payloadBytes = h.frameSizeBytes;
    std::fwrite(&m, sizeof m, 1, f);
    std::fwrite(payload.data(), payload.size(), 1, f);
  }
  std::fclose(f);
  return path;
}

}  // namespace

TEST_CASE("header is still exactly 512 bytes after adding audio fields") {
  CHECK(sizeof(FileHeader) == kHeaderSize);
  CHECK(sizeof(FrameMeta) == kFrameMetaSize);
}

TEST_CASE("version is 5 and 4 is still readable") {
  CHECK(kVersion == 5u);
  CHECK(kMinReadableVersion == 4u);
}

TEST_CASE("a v4 file opens and reports no audio") {
  std::string path = writeFixture(4, 3);
  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  CHECK(r->header().version == 4u);
  CHECK(r->header().audioPresent == 0u);
  CHECK(r->frameCount() == 3u);
  std::remove(path.c_str());
}

TEST_CASE("a v5 file opens") {
  std::string path = writeFixture(5, 2);
  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  CHECK(r->header().version == 5u);
  CHECK(r->frameCount() == 2u);
  std::remove(path.c_str());
}

TEST_CASE("an unknown future version is still rejected") {
  std::string path = writeFixture(6, 1);
  CHECK(RawvReader::open(path) == nullptr);
  std::remove(path.c_str());
}

TEST_CASE("a pre-v4 version is rejected") {
  std::string path = writeFixture(3, 1);
  CHECK(RawvReader::open(path) == nullptr);
  std::remove(path.c_str());
}

TEST_CASE("audio status bits are distinct single bits") {
  const uint32_t bits[] = {
      kAudioPermissionDenied, kAudioOpenFailed, kAudioEndedEarly,
      kAudioOverruns,         kAudioSuspended,  kAudioPadded,
      kAudioDriftHigh,        kAudioProcessedSource,
  };
  uint32_t seen = 0;
  for (uint32_t b : bits) {
    CHECK((b & (b - 1)) == 0u);   // exactly one bit set
    CHECK((seen & b) == 0u);      // no duplicates
    seen |= b;
  }
  CHECK(kAudioSyncInvalidating == (kAudioOverruns | kAudioSuspended | kAudioPadded));
}

TEST_CASE("writer stores audio info into the finalized header") {
  std::string path = "test_audio_hdr_write.rawv";
  FileHeader h = baseHeader(kVersion);

  auto w = RawvWriter::create(path, h);
  REQUIRE(w != nullptr);
  FrameMeta m{};
  m.timestampNs = 1000;
  m.payloadBytes = h.frameSizeBytes;
  std::vector<uint8_t> payload(h.frameSizeBytes, 0);
  REQUIRE(w->writeFrame(m, payload.data(), h.frameSizeBytes));

  AudioInfo ai{};
  ai.present = 1;
  ai.sampleRate = 48000;
  ai.channels = 2;
  ai.bitsPerSample = 24;
  ai.offsetNs = -1234567;   // signed, negative on purpose
  ai.driftPpm = -42;        // signed
  ai.timestampSource = 1;
  ai.status = kAudioOverruns;
  ai.source = 9;
  std::snprintf(ai.fileName, sizeof ai.fileName, "clip_x.wav");
  w->setAudioInfo(ai);
  REQUIRE(w->finalize());
  w.reset();

  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  const FileHeader& out = r->header();
  CHECK(out.audioPresent == 1u);
  CHECK(out.audioSampleRate == 48000u);
  CHECK(out.audioChannels == 2u);
  CHECK(out.audioBitsPerSample == 24u);
  CHECK(out.audioOffsetNs == -1234567);
  CHECK(out.audioDriftPpm == -42);
  CHECK(out.audioTimestampSource == 1u);
  CHECK(out.audioStatus == kAudioOverruns);
  CHECK(out.audioSource == 9u);
  CHECK(std::string(out.audioFileName) == "clip_x.wav");
  std::remove(path.c_str());
}
