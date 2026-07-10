#pragma once
#include <cstdint>
#include <cstddef>

namespace rawcam {
void pack10(const uint16_t* src, size_t count, uint8_t* dst);
void unpack10(const uint8_t* src, size_t count, uint16_t* dst);
constexpr size_t packed10Size(size_t count) { return (count / 4) * 5; }
}
