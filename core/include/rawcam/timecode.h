#pragma once
#include <cstdint>

namespace rawcam {

// SMPTE 12M timecode, as DNG tag 51043 (TimeCode) wants it: 8 bytes, the first
// four holding frames/seconds/minutes/hours as packed BCD, the last four the
// (unused) binary groups.
//
// Timecode is what lets an NLE auto-link an exported DNG sequence to its
// sidecar WAV: both sides are stamped from the SAME take-start anchor, so the
// audio lands against the picture without being dragged into place. Stamping
// them from one anchor aligns them NOMINALLY -- it does not correct the known
// sub-frame A/V residual, and must not be described as doing so.
//
// Non-drop-frame only, which is correct here rather than a shortcut: this app
// records at exact 24/1 and 30/1, never 30000/1001, so there is no 29.97
// wall-clock divergence for drop-frame to compensate.
//
// Returns false and leaves `out` untouched if the frame rate is unusable.
inline bool packTimecode(uint64_t startNsSinceMidnight, uint64_t frameIndex,
                         uint32_t fpsNum, uint32_t fpsDen, uint8_t out[8]) {
  if (fpsNum == 0 || fpsDen == 0) return false;
  // Nominal integer rate: the timecode counter is a frame COUNT, so it needs
  // whole frames per second. Rounding covers a 30000/1001-style rate arriving
  // from a future sensor -- it would count as 30, drifting against wall clock,
  // which is exactly the case drop-frame exists for and this app never hits.
  const uint64_t fps = ((uint64_t)fpsNum + fpsDen / 2) / fpsDen;
  if (fps == 0) return false;

  // ns * fpsNum can overflow only past ~2.4e11 seconds of day, which a
  // seconds-since-midnight value cannot reach; a full day at 30 fps is 2.6e15.
  const uint64_t startFrames =
      startNsSinceMidnight / 1000000000ull * fps +
      (startNsSinceMidnight % 1000000000ull) * fps / 1000000000ull;

  // A take running past local midnight wraps rather than emitting hour 24+,
  // which no NLE would parse. The `% 24` on hours below is what does it -- an
  // explicit `% framesPerDay` here was removed as provably redundant: mutation
  // testing showed no test could tell the two apart, because none can.
  const uint64_t total = startFrames + frameIndex;

  const uint64_t f = total % fps;
  const uint64_t totalSec = total / fps;
  const uint64_t s = totalSec % 60;
  const uint64_t m = (totalSec / 60) % 60;
  const uint64_t h = (totalSec / 3600) % 24;

  auto bcd = [](uint64_t v) -> uint8_t {
    return (uint8_t)(((v / 10) << 4) | (v % 10));
  };
  out[0] = bcd(f);
  out[1] = bcd(s);
  out[2] = bcd(m);
  out[3] = bcd(h);
  // Binary groups unused -- zeroed so a caller's scratch buffer cannot leak
  // into every exported DNG.
  out[4] = out[5] = out[6] = out[7] = 0;
  return true;
}

}  // namespace rawcam
