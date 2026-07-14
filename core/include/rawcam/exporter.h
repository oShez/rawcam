#pragma once
#include <cstdint>
#include <functional>
#include <string>

namespace rawcam {

// Exports every frame of the .rawv clip at rawvPath to sequentially numbered
// uncompressed CinemaDNG files (000000.dng, 000001.dng, ...) inside outDir,
// which the caller must have already created. Handles Packed10 clips by
// unpacking each frame into a scratch RAW16 buffer first (synthesizing
// rowStrideBytes = width*2 for the de-strided payload, since writeDng expects
// stride-padded RAW16 input). progress is invoked after each frame is written
// with (framesDone, totalFrames); returning false cancels the export (the
// current frame's file has already been written, later frames are not).
// Returns false if the clip can't be opened, a frame can't be read/written,
// or the export was cancelled.
bool exportClip(const std::string& rawvPath, const std::string& outDir,
                 const std::function<bool(uint64_t done, uint64_t total)>& progress);

}  // namespace rawcam
