#pragma once
#include <cstdint>

namespace rawcam {

constexpr uint32_t kMagic = 0x56574152u;  // "RAWV" LE
constexpr uint32_t kVersion = 5;
// Oldest header version this build can still read. v4 files predate audio and
// read back with every audio field zero, which is exactly audioPresent == 0.
// The reader must range-check rather than demand equality, or bumping kVersion
// silently orphans every clip already on a user's device.
constexpr uint32_t kMinReadableVersion = 4;
constexpr uint32_t kHeaderSize = 512;
constexpr uint32_t kFrameMetaSize = 64;

// Packed10 truncates every sample to its low 10 bits -- only safe when the
// sensor's actual SENSOR_INFO_WHITE_LEVEL fits in 10 bits (true on e.g. the
// Pixel 7 Pro, not guaranteed on other hardware). Packed12 covers sensors
// whose white level needs up to 12 bits; anything beyond that falls back to
// unpacked Raw16, which is exact regardless of bit depth. capture.cpp picks
// the mode per-recording from the active lens's white level. CompressedPredictive
// losslessly compresses RAW16 samples via a MED/LOCO-I predictor + Golomb-Rice
// coding (see rawv_codec.h); its frames are variable-stride -- see
// FrameMeta.payloadBytes below.
enum class PackMode : uint32_t { Raw16 = 0, Packed10 = 1, Packed12 = 2, CompressedPredictive = 3 };
enum class Cfa : uint32_t { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

// FileHeader.audioStatus bits. A clip can report several at once, so this is a
// bitfield rather than an enum of states. "Sync is trustworthy" means
// (audioStatus & kAudioSyncInvalidating) == 0.
constexpr uint32_t kAudioPermissionDenied = 1u << 0;  // RECORD_AUDIO not granted
constexpr uint32_t kAudioOpenFailed       = 1u << 1;  // AudioRecord would not open
constexpr uint32_t kAudioEndedEarly       = 1u << 2;  // disconnect/read error/disk full
constexpr uint32_t kAudioOverruns         = 1u << 3;  // samples dropped mid-stream
constexpr uint32_t kAudioSuspended        = 1u << 4;  // clock bridge moved mid-take
constexpr uint32_t kAudioPadded           = 1u << 5;  // head is inserted silence
constexpr uint32_t kAudioDriftHigh        = 1u << 6;  // drift over the warning threshold
constexpr uint32_t kAudioProcessedSource  = 1u << 7;  // UNPROCESSED unavailable
constexpr uint32_t kAudioSyncInvalidating =
    kAudioOverruns | kAudioSuspended | kAudioPadded;

// Audio parameters and sync provenance, handed to RawvWriter before finalize.
// Mirrors the FileHeader fields below; kept as its own type so the JNI layer and
// the writer share one definition instead of ten loose arguments.
struct AudioInfo {
  uint32_t present = 0;
  uint32_t sampleRate = 0;
  uint32_t channels = 0;
  uint32_t bitsPerSample = 0;
  int64_t  offsetNs = 0;
  int32_t  driftPpm = 0;
  uint32_t timestampSource = 0;
  uint32_t status = 0;
  uint32_t source = 0;
  char     fileName[64] = {};
};

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
  // ---- Audio (v5+). All zero in v4 files, which reads as audioPresent == 0.
  // The sidecar WAV named by audioFileName lives beside this file and is already
  // head-trimmed, so its sample 0 coincides with frame 0's SENSOR_TIMESTAMP
  // (start of exposure). audioOffsetNs is provenance only: it is the PRE-trim
  // measurement, positive when audio started first (the normal case, since audio
  // arms before the capture session).
  uint32_t audioPresent;
  uint32_t audioSampleRate;
  uint32_t audioChannels;
  uint32_t audioBitsPerSample;
  int64_t  audioOffsetNs;
  int32_t  audioDriftPpm;
  uint32_t audioTimestampSource;  // 0 = unknown/monotonic, 1 = realtime/boottime
  uint32_t audioStatus;           // bitfield, see kAudio* above
  uint32_t audioSource;           // MediaRecorder.AudioSource actually opened
  char     audioFileName[64];     // NUL-terminated sidecar basename
  uint8_t  reserved[180];
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
  // Actual on-disk bytes of this frame's payload. For Raw16/Packed10/Packed12
  // this always equals FileHeader.frameSizeBytes (fixed stride, unchanged
  // behavior). For CompressedPredictive, FileHeader.frameSizeBytes is only an
  // allocation ceiling -- this field is the real per-frame stride, and the
  // reader MUST use it (not frameSizeBytes) to find the next record.
  uint32_t payloadBytes;
  // 0 = payload is stored uncompressed (the compressor's fallback path for a
  // frame that wouldn't shrink); 1 = payload is CompressedPredictive-encoded.
  // Always 0 for Raw16/Packed10/Packed12 frames.
  uint32_t compressed;
  uint8_t  reserved[4];
};
#pragma pack(pop)

static_assert(sizeof(FileHeader) == kHeaderSize, "header must be 512 bytes");
static_assert(sizeof(FrameMeta) == kFrameMetaSize, "frame meta must be 64 bytes");

// On-disk layout: [FileHeader][FrameMeta+payload][FrameMeta+payload]...
// For Raw16/Packed10/Packed12, record size = kFrameMetaSize +
// header.frameSizeBytes, constant per file. For CompressedPredictive,
// each record's payload is FrameMeta.payloadBytes (<= header.frameSizeBytes,
// the allocation ceiling) -- readers must use payloadBytes, not
// frameSizeBytes, to find the next record.

}  // namespace rawcam
