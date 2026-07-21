#pragma once
#include <cstdint>

namespace rawcam {

constexpr uint32_t kMagic = 0x56574152u;  // "RAWV" LE
constexpr uint32_t kVersion = 3;
constexpr uint32_t kHeaderSize = 512;
constexpr uint32_t kFrameMetaSize = 64;

// Packed10 truncates every sample to its low 10 bits -- only safe when the
// sensor's actual SENSOR_INFO_WHITE_LEVEL fits in 10 bits (true on e.g. the
// Pixel 7 Pro, not guaranteed on other hardware). Packed12 covers sensors
// whose white level needs up to 12 bits; anything beyond that falls back to
// unpacked Raw16, which is exact regardless of bit depth. capture.cpp picks
// the mode per-recording from the active lens's white level.
enum class PackMode : uint32_t { Raw16 = 0, Packed10 = 1, Packed12 = 2 };
enum class Cfa : uint32_t { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

#pragma pack(push, 1)
struct FileHeader {
  uint32_t magic;            // kMagic
  uint32_t version;          // kVersion
  uint32_t width;            // active pixels
  uint32_t height;
  uint32_t rowStrideBytes;   // RAW16 plane stride as delivered
  uint32_t packMode;         // PackMode
  uint32_t cfa;              // Cfa
  uint32_t whiteLevel;
  uint32_t blackLevel[4];    // per CFA quadrant, sensor order
  float    colorMatrix1[9];  // XYZ->camera (SENSOR_COLOR_TRANSFORM1), row-major
  float    asShotNeutral[3];
  uint32_t fpsNum;
  uint32_t fpsDen;
  uint32_t frameSizeBytes;   // fixed payload bytes per frame record
  uint32_t _pad;
  uint64_t frameCount;       // 0 until finalize; 0 on read => recover by scan
  char     deviceName[64];   // NUL-terminated
  // DNG/EXIF LightSource code for colorMatrix1 (SENSOR_REFERENCE_ILLUMINANT1);
  // 0 means unset -- writers should fall back to 21 (D65) since CalibrationIlluminant1
  // must always accompany ColorMatrix1 in the DNG output.
  uint32_t illuminant1;
  // 0 means the sensor exposed no second calibration point -- colorMatrix2 is then
  // all-zero and must NOT be written to the DNG (a single-illuminant DNG is valid;
  // a bogus second illuminant/matrix is not). Non-zero mirrors
  // SENSOR_REFERENCE_ILLUMINANT2, and forces a genuine dual-illuminant DNG so
  // converters (e.g. DaVinci Resolve) interpolate CCT/tint between two real
  // calibration points instead of extrapolating from one -- omitting this caused
  // Resolve to report an implausible CCT and "break" the image on any WB nudge.
  uint32_t illuminant2;
  float    colorMatrix2[9];  // XYZ->camera under illuminant2; valid iff illuminant2 != 0
  uint8_t  reserved[284];
};

struct FrameMeta {
  uint64_t timestampNs;      // sensor timestamp
  uint64_t frameIndex;
  uint32_t iso;
  uint32_t _pad;
  uint64_t exposureNs;
  float    focusDistance;    // diopters
  float    wbNeutral[3];     // AsShotNeutral estimate from AWB
  uint32_t droppedSoFar;
  uint8_t  reserved[12];
};
#pragma pack(pop)

static_assert(sizeof(FileHeader) == kHeaderSize, "header must be 512 bytes");
static_assert(sizeof(FrameMeta) == kFrameMetaSize, "frame meta must be 64 bytes");

// On-disk layout: [FileHeader][FrameMeta+payload][FrameMeta+payload]...
// record size = kFrameMetaSize + header.frameSizeBytes, constant per file.

}  // namespace rawcam
