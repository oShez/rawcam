#pragma once
#include <cstdint>
#include <cstddef>

namespace rawcam {
// Packs 4 samples into 5 bytes per group (count must be a multiple of 4; the
// sole production caller packs one row at a time and every supported sensor
// width divides evenly into 4 -- see capture.cpp's packMode selection, which
// only chooses Packed10 when width % 4 == 0). No runtime assert: this runs
// once per row on the capture hot path.
void pack10(const uint16_t* src, size_t count, uint8_t* dst);
void unpack10(const uint8_t* src, size_t count, uint16_t* dst);
constexpr size_t packed10Size(size_t count) { return (count / 4) * 5; }

// Same scheme as pack10 but 12 bits/sample: two 12-bit samples packed into
// 3 bytes (count must be a multiple of 2).
void pack12(const uint16_t* src, size_t count, uint8_t* dst);
void unpack12(const uint8_t* src, size_t count, uint16_t* dst);
constexpr size_t packed12Size(size_t count) { return (count / 2) * 3; }
}
