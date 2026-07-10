#pragma once
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {
// Writes one uncompressed 16-bit CFA DNG. raw16 points at stride-padded RAW16 rows
// (rowStrideBytes per row); the function de-strides to width*2 internally.
bool writeDng(const std::string& path, const FileHeader& hdr,
              const FrameMeta& meta, const uint8_t* raw16);
}
