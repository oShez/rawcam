#include "rawcam/dng_writer.h"
#include "rawcam/file_io.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace rawcam {
namespace {

enum : uint16_t { BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, SRATIONAL = 10 };

class Dng {
 public:
  // inline value entries
  void addShort(uint16_t tag, uint16_t v) { entries_.push_back({tag, SHORT, 1, v, false}); }
  void addLong(uint16_t tag, uint32_t v) { entries_.push_back({tag, LONG, 1, v, false}); }
  void addBytes(uint16_t tag, const uint8_t* v, uint32_t n) {  // n <= 4
    uint32_t packed = 0;
    for (uint32_t i = 0; i < n; i++) packed |= (uint32_t)v[i] << (8 * i);
    entries_.push_back({tag, BYTE, n, packed, false});
  }
  void addShorts2(uint16_t tag, uint16_t a, uint16_t b) {
    entries_.push_back({tag, SHORT, 2, (uint32_t)a | ((uint32_t)b << 16), false});
  }
  // out-of-line entries (data area)
  void addAscii(uint16_t tag, const char* s) {
    uint32_t n = (uint32_t)std::strlen(s) + 1;
    if (n <= 4) {
      uint32_t v = 0;
      std::memcpy(&v, s, n);
      entries_.push_back({tag, ASCII, n, v, false});
    } else {
      entries_.push_back({tag, ASCII, n, defer(s, n), true});
    }
  }
  void addLongs(uint16_t tag, const uint32_t* v, uint32_t n) {
    if (n == 1) entries_.push_back({tag, LONG, n, v[0], false});
    else entries_.push_back({tag, LONG, n, defer(v, n * 4), true});
  }
  void addRationals(uint16_t tag, uint16_t type, const float* v, uint32_t n) {
    std::vector<uint32_t> r(n * 2);
    for (uint32_t i = 0; i < n; i++) {
      r[i * 2] = (uint32_t)(int32_t)std::lround((double)v[i] * 10000.0);
      r[i * 2 + 1] = 10000;
    }
    entries_.push_back({tag, type, n, defer(r.data(), n * 8), true});
  }
  // out-of-line raw byte blob, e.g. an embedded XMP packet
  void addRaw(uint16_t tag, uint16_t type, const void* data, uint32_t n) {
    entries_.push_back({tag, type, n, defer(data, n), true});
  }

  bool write(const std::string& path, const uint8_t* pixels, uint32_t pixelBytes) {
    // layout: header(8) + ifd(2 + n*12 + 4) + data area + strip
    std::sort(entries_.begin(), entries_.end(),
              [](const Entry& a, const Entry& b) { return a.tag < b.tag; });
    uint32_t ifdSize = 2 + (uint32_t)entries_.size() * 12 + 4;
    uint32_t dataStart = 8 + ifdSize;
    uint32_t stripStart = dataStart + (uint32_t)data_.size();
    // patch StripOffsets (tag 273 was added with value 0)
    for (auto& e : entries_) {
      if (e.tag == 273) e.value = stripStart;
      else if (e.deferred) e.value += dataStart;
    }
    // Header + IFD + data area only -- the pixel strip is written directly from
    // the caller's buffer as a second writeAll below instead of being copied in
    // here first: at 4096x3072 that copy was a ~25MB alloc+memcpy per frame,
    // multiplied across the export worker pool, for zero layout difference.
    std::vector<uint8_t> out;
    out.reserve(stripStart);
    const uint8_t th[8] = {'I', 'I', 42, 0, 8, 0, 0, 0};
    out.insert(out.end(), th, th + 8);
    uint16_t n = (uint16_t)entries_.size();
    append(out, &n, 2);
    for (const auto& e : entries_) {
      append(out, &e.tag, 2); append(out, &e.type, 2);
      append(out, &e.count, 4); append(out, &e.value, 4);
    }
    uint32_t zero = 0;
    append(out, &zero, 4);  // no next IFD
    out.insert(out.end(), data_.begin(), data_.end());
    int fd = io::openWrite(path.c_str());
    if (fd < 0) return false;
    bool ok = io::writeAll(fd, out.data(), out.size()) &&
              io::writeAll(fd, pixels, pixelBytes);
    io::closeFd(fd);
    return ok;
  }

 private:
  struct Entry { uint16_t tag, type; uint32_t count, value; bool deferred; };
  uint32_t defer(const void* src, uint32_t n) {
    uint32_t off = (uint32_t)data_.size();
    const uint8_t* p = static_cast<const uint8_t*>(src);
    data_.insert(data_.end(), p, p + n);
    if (data_.size() & 1) data_.push_back(0);  // word-align
    return off;
  }
  static void append(std::vector<uint8_t>& v, const void* p, size_t n) {
    const uint8_t* b = static_cast<const uint8_t*>(p);
    v.insert(v.end(), b, b + n);
  }
  std::vector<Entry> entries_;
  std::vector<uint8_t> data_;
};

}  // namespace

bool writeDng(const std::string& path, const FileHeader& hdr,
              const FrameMeta& meta, const uint8_t* raw16) {
  const uint32_t w = hdr.width, h = hdr.height;
  const size_t pixelBytes = (size_t)w * h * 2;
  // De-stride into a contiguous buffer only when the source actually has row
  // padding to strip. The packed10/12 export path (exporter.cpp) already hands
  // us a stride-free buffer -- rowStrideBytes is synthesized to width*2 for
  // it precisely because unpack10/unpack12 write contiguously -- so on that
  // (common) path this would otherwise be a wasted extra full-frame memcpy.
  std::vector<uint8_t> destrided;
  const uint8_t* pixels;
  if (hdr.rowStrideBytes == w * 2) {
    pixels = raw16;
  } else {
    destrided.resize(pixelBytes);
    for (uint32_t r = 0; r < h; r++)
      std::memcpy(destrided.data() + (size_t)r * w * 2,
                  raw16 + (size_t)r * hdr.rowStrideBytes, (size_t)w * 2);
    pixels = destrided.data();
  }

  static const uint8_t cfaBytes[4][4] = {
      {0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
  const uint8_t* cfa = cfaBytes[hdr.cfa < 4 ? hdr.cfa : 0];

  Dng d;
  d.addLong(254, 0);
  d.addLong(256, w);
  d.addLong(257, h);
  d.addShort(258, 16);
  d.addShort(259, 1);
  d.addShort(262, 32803);
  d.addAscii(271, "RawCam");
  d.addAscii(272, hdr.deviceName);
  d.addLong(273, 0);  // StripOffsets, patched in write()
  d.addShort(274, 1);
  d.addShort(277, 1);
  d.addLong(278, h);
  d.addLong(279, w * h * 2);
  d.addShort(284, 1);
  d.addShorts2(33421, 2, 2);
  d.addBytes(33422, cfa, 4);
  static const uint8_t dngV[4] = {1, 4, 0, 0}, dngB[4] = {1, 2, 0, 0}, plane[3] = {0, 1, 2};
  d.addBytes(50706, dngV, 4);
  d.addBytes(50707, dngB, 4);
  d.addAscii(50708, hdr.deviceName);
  d.addBytes(50710, plane, 3);
  d.addShort(50711, 1);
  // BlackLevelRepeatDim [2,2] is required for a 4-entry BlackLevel: without it
  // the DNG default is [1,1], making count=4 malformed — LibRaw then ignores
  // the black level and DaVinci Resolve rejects the file as media offline.
  d.addShorts2(50713, 2, 2);
  d.addLongs(50714, hdr.blackLevel, 4);
  d.addLong(50717, hdr.whiteLevel);
  d.addRationals(50721, SRATIONAL, hdr.colorMatrix1, 9);
  float wb[3] = {meta.wbNeutral[0], meta.wbNeutral[1], meta.wbNeutral[2]};
  if (wb[0] == 0 && wb[1] == 0 && wb[2] == 0) { wb[0] = wb[1] = wb[2] = 1.0f; }
  d.addRationals(50728, RATIONAL, wb, 3);
  d.addShort(50778, (uint16_t)(hdr.illuminant1 != 0 ? hdr.illuminant1 : 21));
  // Only emit the second calibration point when the sensor actually exposed one
  // (SENSOR_REFERENCE_ILLUMINANT2/SENSOR_COLOR_TRANSFORM2 both present). A
  // single-illuminant DNG is valid and expected on those sensors; writing a
  // fabricated ColorMatrix2 would be worse than omitting it. But when a real
  // second point exists and is silently dropped, converters must extrapolate
  // CCT/tint from one calibration point instead of interpolating between two --
  // this is what made DaVinci Resolve show an implausible CCT and made nudging
  // WB/tint destabilize the image on hardware with two genuinely different
  // per-illuminant calibration matrices (observed on the Xiaomi 14 Ultra).
  if (hdr.illuminant2 != 0) {
    d.addShort(50779, (uint16_t)hdr.illuminant2);
    d.addRationals(50722, SRATIONAL, hdr.colorMatrix2, 9);
  }
  // XMP packet carrying the real capture frame rate (xmpDM:videoFrameRate),
  // the standard CinemaDNG convention DaVinci Resolve reads to auto-set an
  // image sequence's frame rate on import. Without this, Resolve has no way
  // to know the true fps and falls back to whatever the project/import
  // dialog defaults to, which silently plays the sequence at the wrong speed
  // on any mismatch.
  if (hdr.fpsDen != 0) {
    char fpsStr[32];
    std::snprintf(fpsStr, sizeof fpsStr, "%.6f", (double)hdr.fpsNum / hdr.fpsDen);
    const std::string xmp =
        "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
        "<rdf:Description rdf:about=\"\" "
        "xmlns:xmpDM=\"http://ns.adobe.com/xmp/1.0/DynamicMedia/\">"
        "<xmpDM:videoFrameRate>" + std::string(fpsStr) + "</xmpDM:videoFrameRate>"
        "</rdf:Description></rdf:RDF></x:xmpmeta>"
        "<?xpacket end=\"w\"?>";
    d.addRaw(700, BYTE, xmp.data(), (uint32_t)xmp.size());
  }
  return d.write(path, pixels, (uint32_t)pixelBytes);
}

}  // namespace rawcam
