# RawCam RAW Video Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app for the user's Pixel that records full-resolution RAW Bayer video at 24/30fps with manual exposure into a crash-safe `.rawv` container, and exports CinemaDNG folders that grade in DaVinci Resolve.

**Architecture:** Kotlin/Compose UI + Camera2 session control on top; a C++ NDK core owns the hot path (`AImageReader` RAW16 → frame queue → writer thread → sequential container file). Export to per-frame DNGs is a separate, non-time-critical step. The C++ core is host-buildable so container/DNG logic is unit-tested on the PC.

**Tech Stack:** Kotlin, Jetpack Compose, Camera2 (Kotlin side), NDK r27 + CMake, `AImageReader`/`ANativeWindow` (native side), doctest (host unit tests), Gradle 8.x with externalNativeBuild.

## Global Constraints

- Target device: the user's Pixel only. `minSdk = 33`, `targetSdk = 35`, `compileSdk = 35`.
- Package name: `com.shez.rawcam`. Repo root: `C:\Users\User\rawcam`.
- RAW frames are `AIMAGE_FORMAT_RAW16` at the sensor's largest RAW_SENSOR size.
- No Adobe DNG SDK; we write minimal TIFF/DNG ourselves. No `io_uring` (blocked for apps by Android seccomp) — plain `write()` on a dedicated thread.
- Hot path rule: zero copies and zero allocation per frame; if the queue is full, drop the newest frame and count it — never stall the camera.
- Container frame records are **fixed size** (`frameSizeBytes` from header) so truncated files recover by scanning. Simplification vs. spec wording: fixed-size records make a trailing index redundant — finalize rewrites `frameCount` in the header instead; recovery scans when `frameCount == 0`.
- The hot path stores the RAW plane **as delivered, including row stride padding** (`frameSizeBytes = rowStrideBytes * height` in Raw16 mode); the exporter de-strides. Never copy per-row while recording.
- Storage: recordings in `<app external files>/clips/*.rawv`, exports in `<app external files>/exports/<clipname>/`. No storage permissions; only `CAMERA`.
- Host builds use SDK CMake + Ninja with MinGW g++ (`C:\msys64\mingw64\bin\g++.exe`). Host tool paths below assume `$SDK = $env:LOCALAPPDATA\Android\Sdk`.
- All PowerShell commands: PS 5.1, no `&&` — chain with `;`.
- Commit after every task (steps show exact commands). Git identity is already configured locally in the repo.
- Deferred (do NOT build): compression, audio, long recordings, on-device playback, histogram/zebras/peaking, WB control, multi-device support.

---

### Task 1: Toolchain setup + wireless adb

**Files:**
- Create: `.gitignore`
- Create: `local.properties` (git-ignored)

**Interfaces:**
- Produces: installed NDK `27.0.12077973` and CMake `3.22.1` under `$SDK`; a `deviceserial` you can pass to adb; host build tools verified.

- [ ] **Step 1: Install NDK + CMake via sdkmanager**

```powershell
$SDK = "$env:LOCALAPPDATA\Android\Sdk"
& "$SDK\cmdline-tools\latest\bin\sdkmanager.bat" --install "ndk;27.0.12077973" "cmake;3.22.1"
```
Expected: `100% Computing updates... done` (accept licenses if prompted with `--licenses`).

- [ ] **Step 2: Verify host toolchain trio**

```powershell
& "$SDK\cmake\3.22.1\bin\cmake.exe" --version
& "$SDK\cmake\3.22.1\bin\ninja.exe" --version
C:\msys64\mingw64\bin\g++.exe --version
```
Expected: cmake 3.22.1, ninja 1.x, g++ 13+/14+ banners. If g++ is missing, install with `C:\msys64\usr\bin\pacman.exe -S --noconfirm mingw-w64-x86_64-gcc`.

- [ ] **Step 3: Pair wireless adb (USB is flaky on this Pixel)**

On the phone: Settings → Developer options → Wireless debugging → ON → *Pair device with pairing code*. Then (substitute the IP:PORT and code shown on the phone):

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $ADB pair 192.168.x.x:XXXXX   # enter 6-digit code when prompted
& $ADB connect 192.168.x.x:YYYYY  # the *connection* port from the Wireless debugging main screen
& $ADB devices
```
Expected: one device listed as `192.168.x.x:YYYYY  device`. Record this serial; every later `adb` step uses `-s <serial>`.

- [ ] **Step 4: Record device facts we design against**

```powershell
& $ADB -s <serial> shell getprop ro.product.model
& $ADB -s <serial> shell getprop ro.build.version.sdk
```
Expected: a Pixel model name and SDK ≥ 33. Note both in the commit message.

- [ ] **Step 5: Create .gitignore and commit**

```gitignore
.gradle/
build/
local.properties
.idea/
*.iml
core/build/
.cxx/
```

```powershell
cd C:\Users\User\rawcam
git add .gitignore ; git commit -m "chore: toolchain setup (NDK 27, CMake 3.22.1, wireless adb to <model>)"
```

---

### Task 2: C++ core scaffold + container format header

**Files:**
- Create: `core/CMakeLists.txt`
- Create: `core/include/rawcam/rawv.h`
- Create: `core/third_party/doctest/doctest.h` (vendored single header)
- Create: `core/tests/test_rawv_layout.cpp`

**Interfaces:**
- Produces: `rawcam::FileHeader` (512 bytes packed), `rawcam::FrameMeta` (64 bytes packed), `kMagic`, `kHeaderSize=512`, `kFrameMetaSize=64`, `enum class PackMode : uint32_t { Raw16=0, Packed10=1 }`, `enum class Cfa : uint32_t { RGGB=0, GRBG=1, GBRG=2, BGGR=3 }`. All later tasks include `rawcam/rawv.h`.

- [ ] **Step 1: Vendor doctest**

```powershell
mkdir C:\Users\User\rawcam\core\third_party\doctest -Force
Invoke-WebRequest https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h -OutFile C:\Users\User\rawcam\core\third_party\doctest\doctest.h
```

- [ ] **Step 2: Write the failing layout test**

`core/tests/test_rawv_layout.cpp`:
```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
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
```

- [ ] **Step 3: Write CMakeLists**

`core/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(rawcam_core CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rawcam_core STATIC
  src/rawv_writer.cpp
  src/rawv_reader.cpp
  src/pack10.cpp
  src/dng_writer.cpp)
target_include_directories(rawcam_core PUBLIC include)

if(NOT ANDROID)
  enable_testing()
  file(GLOB TEST_SOURCES tests/*.cpp)
  foreach(t ${TEST_SOURCES})
    get_filename_component(name ${t} NAME_WE)
    add_executable(${name} ${t})
    target_link_libraries(${name} rawcam_core)
    target_include_directories(${name} PRIVATE third_party/doctest)
    add_test(NAME ${name} COMMAND ${name})
  endforeach()
endif()
```
Also create empty placeholder sources so it links: `core/src/rawv_writer.cpp`, `core/src/rawv_reader.cpp`, `core/src/pack10.cpp`, `core/src/dng_writer.cpp` each containing only `// implemented in a later task` and one dummy symbol `namespace rawcam { int _placeholder_<name>; }` (CMake STATIC libs need at least one object; replace the dummy when the real code lands).

- [ ] **Step 4: Run test to verify it fails**

```powershell
$SDK = "$env:LOCALAPPDATA\Android\Sdk"
$CM = "$SDK\cmake\3.22.1\bin\cmake.exe"
& $CM -S C:\Users\User\rawcam\core -B C:\Users\User\rawcam\core\build -G Ninja -DCMAKE_MAKE_PROGRAM="$SDK\cmake\3.22.1\bin\ninja.exe" -DCMAKE_CXX_COMPILER=C:/msys64/mingw64/bin/g++.exe
& $CM --build C:\Users\User\rawcam\core\build
```
Expected: FAIL — `rawcam/rawv.h: No such file or directory`.

- [ ] **Step 5: Write rawv.h**

`core/include/rawcam/rawv.h`:
```cpp
#pragma once
#include <cstdint>

namespace rawcam {

constexpr uint32_t kMagic = 0x56574152u;  // "RAWV" LE
constexpr uint32_t kVersion = 1;
constexpr uint32_t kHeaderSize = 512;
constexpr uint32_t kFrameMetaSize = 64;

enum class PackMode : uint32_t { Raw16 = 0, Packed10 = 1 };
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
  uint8_t  reserved[328];
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
```

- [ ] **Step 6: Run test to verify it passes**

```powershell
& $CM --build C:\Users\User\rawcam\core\build
& "$SDK\cmake\3.22.1\bin\ctest.exe" --test-dir C:\Users\User\rawcam\core\build --output-on-failure
```
Expected: `100% tests passed, 0 tests failed out of 1`.

- [ ] **Step 7: Commit**

```powershell
cd C:\Users\User\rawcam ; git add core ; git commit -m "feat(core): .rawv container format header + host test scaffold"
```

---

### Task 3: RawvWriter

**Files:**
- Create: `core/include/rawcam/file_io.h`
- Create: `core/include/rawcam/rawv_writer.h`
- Modify: `core/src/rawv_writer.cpp` (replace placeholder)
- Create: `core/tests/test_rawv_writer.cpp`

**Interfaces:**
- Consumes: `rawcam/rawv.h` structs.
- Produces:
  ```cpp
  class RawvWriter {
   public:
    // Opens path, writes header (frameCount=0). Returns nullptr on failure.
    static std::unique_ptr<RawvWriter> create(const std::string& path, const FileHeader& hdr);
    // Blocking sequential write of one fixed-size record. payload length == hdr.frameSizeBytes.
    bool writeFrame(const FrameMeta& meta, const uint8_t* payload);
    bool finalize();                 // rewrite header.frameCount, flush, close
    uint64_t framesWritten() const;
    ~RawvWriter();                   // finalizes if not already done
  };
  ```
  And `file_io.h` portability layer used by writer/reader/exporter:
  ```cpp
  namespace rawcam::io {
  int  openWrite(const char* path);            // create/truncate, returns fd or -1
  int  openRead(const char* path);             // returns fd or -1
  bool writeAll(int fd, const void* buf, size_t n);
  bool readAll(int fd, void* buf, size_t n);   // false on short read
  bool seekTo(int fd, uint64_t offset);
  int64_t fileSize(int fd);
  void closeFd(int fd);
  }
  ```

- [ ] **Step 1: Write the failing test**

`core/tests/test_rawv_writer.cpp`:
```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <vector>

using namespace rawcam;

static FileHeader testHeader(uint32_t frameSize) {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = frameSize;
  std::strcpy(h.deviceName, "hosttest");
  return h;
}

TEST_CASE("writer produces header + fixed records and finalizes count") {
  const char* path = "test_writer.rawv";
  const uint32_t fs = 16;  // rowStride(8) * height(2)
  {
    auto w = RawvWriter::create(path, testHeader(fs));
    REQUIRE(w != nullptr);
    std::vector<uint8_t> payload(fs);
    for (uint64_t i = 0; i < 3; i++) {
      FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = i; m.iso = 100;
      std::memset(payload.data(), (int)i, fs);
      CHECK(w->writeFrame(m, payload.data()));
    }
    CHECK(w->framesWritten() == 3);
    CHECK(w->finalize());
  }
  int fd = io::openRead(path);
  REQUIRE(fd >= 0);
  CHECK(io::fileSize(fd) == (int64_t)(kHeaderSize + 3 * (kFrameMetaSize + fs)));
  FileHeader h{};
  CHECK(io::readAll(fd, &h, sizeof h));
  CHECK(h.magic == kMagic);
  CHECK(h.frameCount == 3);
  // spot-check record 1's meta
  CHECK(io::seekTo(fd, kHeaderSize + 1 * (kFrameMetaSize + fs)));
  FrameMeta m{};
  CHECK(io::readAll(fd, &m, sizeof m));
  CHECK(m.timestampNs == 1001);
  io::closeFd(fd);
  std::remove(path);
}
```

- [ ] **Step 2: Run test to verify it fails**

Same configure/build commands as Task 2 Step 4.
Expected: FAIL — `rawcam/rawv_writer.h: No such file or directory`.

- [ ] **Step 3: Implement file_io.h and RawvWriter**

`core/include/rawcam/file_io.h`:
```cpp
#pragma once
#include <cstdint>
#include <cstddef>
#ifdef _WIN32
#include <io.h>
#include <fcntl.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#endif

namespace rawcam::io {

inline int openWrite(const char* path) {
#ifdef _WIN32
  return _open(path, _O_CREAT | _O_TRUNC | _O_RDWR | _O_BINARY, _S_IREAD | _S_IWRITE);
#else
  return ::open(path, O_CREAT | O_TRUNC | O_RDWR, 0644);
#endif
}
inline int openRead(const char* path) {
#ifdef _WIN32
  return _open(path, _O_RDONLY | _O_BINARY);
#else
  return ::open(path, O_RDONLY);
#endif
}
inline bool writeAll(int fd, const void* buf, size_t n) {
  const uint8_t* p = static_cast<const uint8_t*>(buf);
  while (n > 0) {
#ifdef _WIN32
    int w = _write(fd, p, (unsigned)(n > 1u << 30 ? 1u << 30 : n));
#else
    ssize_t w = ::write(fd, p, n);
#endif
    if (w <= 0) return false;
    p += w; n -= (size_t)w;
  }
  return true;
}
inline bool readAll(int fd, void* buf, size_t n) {
  uint8_t* p = static_cast<uint8_t*>(buf);
  while (n > 0) {
#ifdef _WIN32
    int r = _read(fd, p, (unsigned)(n > 1u << 30 ? 1u << 30 : n));
#else
    ssize_t r = ::read(fd, p, n);
#endif
    if (r <= 0) return false;
    p += r; n -= (size_t)r;
  }
  return true;
}
inline bool seekTo(int fd, uint64_t off) {
#ifdef _WIN32
  return _lseeki64(fd, (int64_t)off, SEEK_SET) == (int64_t)off;
#else
  return ::lseek(fd, (off_t)off, SEEK_SET) == (off_t)off;
#endif
}
inline int64_t fileSize(int fd) {
#ifdef _WIN32
  int64_t cur = _lseeki64(fd, 0, SEEK_CUR);
  int64_t end = _lseeki64(fd, 0, SEEK_END);
  _lseeki64(fd, cur, SEEK_SET);
  return end;
#else
  off_t cur = ::lseek(fd, 0, SEEK_CUR);
  off_t end = ::lseek(fd, 0, SEEK_END);
  ::lseek(fd, cur, SEEK_SET);
  return (int64_t)end;
#endif
}
inline void closeFd(int fd) {
#ifdef _WIN32
  _close(fd);
#else
  ::close(fd);
#endif
}

}  // namespace rawcam::io
```

`core/include/rawcam/rawv_writer.h`:
```cpp
#pragma once
#include <memory>
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {

class RawvWriter {
 public:
  static std::unique_ptr<RawvWriter> create(const std::string& path, const FileHeader& hdr);
  bool writeFrame(const FrameMeta& meta, const uint8_t* payload);
  bool finalize();
  uint64_t framesWritten() const { return frames_; }
  ~RawvWriter();

 private:
  RawvWriter(int fd, const FileHeader& hdr) : fd_(fd), hdr_(hdr) {}
  int fd_ = -1;
  FileHeader hdr_{};
  uint64_t frames_ = 0;
  bool finalized_ = false;
};

}  // namespace rawcam
```

`core/src/rawv_writer.cpp`:
```cpp
#include "rawcam/rawv_writer.h"
#include "rawcam/file_io.h"

namespace rawcam {

std::unique_ptr<RawvWriter> RawvWriter::create(const std::string& path, const FileHeader& hdr) {
  if (hdr.magic != kMagic || hdr.frameSizeBytes == 0) return nullptr;
  int fd = io::openWrite(path.c_str());
  if (fd < 0) return nullptr;
  FileHeader h = hdr;
  h.frameCount = 0;  // finalize fills this in; 0 means "recover by scan"
  if (!io::writeAll(fd, &h, sizeof h)) { io::closeFd(fd); return nullptr; }
  return std::unique_ptr<RawvWriter>(new RawvWriter(fd, h));
}

bool RawvWriter::writeFrame(const FrameMeta& meta, const uint8_t* payload) {
  if (fd_ < 0 || finalized_) return false;
  if (!io::writeAll(fd_, &meta, sizeof meta)) return false;
  if (!io::writeAll(fd_, payload, hdr_.frameSizeBytes)) return false;
  frames_++;
  return true;
}

bool RawvWriter::finalize() {
  if (fd_ < 0 || finalized_) return false;
  hdr_.frameCount = frames_;
  bool ok = io::seekTo(fd_, 0) && io::writeAll(fd_, &hdr_, sizeof hdr_);
  io::closeFd(fd_);
  fd_ = -1;
  finalized_ = true;
  return ok;
}

RawvWriter::~RawvWriter() { if (!finalized_ && fd_ >= 0) finalize(); }

}  // namespace rawcam
```
Remove the placeholder dummy symbol from `rawv_writer.cpp`.

- [ ] **Step 4: Run test to verify it passes**

```powershell
& $CM --build C:\Users\User\rawcam\core\build
& "$SDK\cmake\3.22.1\bin\ctest.exe" --test-dir C:\Users\User\rawcam\core\build --output-on-failure
```
Expected: all tests pass (layout + writer).

- [ ] **Step 5: Commit**

```powershell
cd C:\Users\User\rawcam ; git add core ; git commit -m "feat(core): RawvWriter with portable file io"
```

---

### Task 4: RawvReader + truncation recovery

**Files:**
- Create: `core/include/rawcam/rawv_reader.h`
- Modify: `core/src/rawv_reader.cpp` (replace placeholder)
- Create: `core/tests/test_rawv_reader.cpp`

**Interfaces:**
- Consumes: `RawvWriter`, `file_io.h`.
- Produces:
  ```cpp
  class RawvReader {
   public:
    static std::unique_ptr<RawvReader> open(const std::string& path);  // nullptr on bad magic/version
    const FileHeader& header() const;
    uint64_t frameCount() const;   // from header, or recovered by size scan when header says 0
    bool readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload);  // payload buffer >= frameSizeBytes
  };
  ```

- [ ] **Step 1: Write the failing test**

`core/tests/test_rawv_reader.cpp`:
```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/rawv_writer.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <vector>

using namespace rawcam;

static FileHeader testHeader() {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  h.fpsNum = 24; h.fpsDen = 1;
  h.frameSizeBytes = 16;
  return h;
}

static void writeClip(const char* path, int frames) {
  auto w = RawvWriter::create(path, testHeader());
  std::vector<uint8_t> p(16);
  for (int i = 0; i < frames; i++) {
    FrameMeta m{}; m.timestampNs = 1000 + i; m.frameIndex = (uint64_t)i;
    std::memset(p.data(), i, p.size());
    w->writeFrame(m, p.data());
  }
  w->finalize();
}

TEST_CASE("round trip") {
  writeClip("rt.rawv", 5);
  auto r = RawvReader::open("rt.rawv");
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 5);
  FrameMeta m{}; std::vector<uint8_t> p(16);
  CHECK(r->readFrame(4, &m, p.data()));
  CHECK(m.timestampNs == 1004);
  CHECK(p[0] == 4);
  std::remove("rt.rawv");
}

TEST_CASE("truncated file recovers whole frames only") {
  writeClip("tr.rawv", 5);
  // simulate crash: chop mid-record AND zero the header count like an unfinalized file
  int fd = io::openRead("tr.rawv");
  int64_t full = io::fileSize(fd);
  io::closeFd(fd);
  std::vector<uint8_t> bytes(full);
  fd = io::openRead("tr.rawv");
  io::readAll(fd, bytes.data(), bytes.size());
  io::closeFd(fd);
  FileHeader* h = reinterpret_cast<FileHeader*>(bytes.data());
  h->frameCount = 0;
  size_t cut = kHeaderSize + 3 * (kFrameMetaSize + 16) + 7;  // mid 4th record
  fd = io::openWrite("tr.rawv");
  io::writeAll(fd, bytes.data(), cut);
  io::closeFd(fd);

  auto r = RawvReader::open("tr.rawv");
  REQUIRE(r != nullptr);
  CHECK(r->frameCount() == 3);   // partial 4th frame discarded
  FrameMeta m{}; std::vector<uint8_t> p(16);
  CHECK(r->readFrame(2, &m, p.data()));
  CHECK(m.timestampNs == 1002);
  CHECK_FALSE(r->readFrame(3, &m, p.data()));
  std::remove("tr.rawv");
}
```

- [ ] **Step 2: Run test to verify it fails**

Build as before. Expected: FAIL — `rawcam/rawv_reader.h: No such file or directory`.

- [ ] **Step 3: Implement RawvReader**

`core/include/rawcam/rawv_reader.h`:
```cpp
#pragma once
#include <memory>
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {

class RawvReader {
 public:
  static std::unique_ptr<RawvReader> open(const std::string& path);
  const FileHeader& header() const { return hdr_; }
  uint64_t frameCount() const { return count_; }
  bool readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload);
  ~RawvReader();

 private:
  RawvReader(int fd, const FileHeader& h, uint64_t c) : fd_(fd), hdr_(h), count_(c) {}
  int fd_;
  FileHeader hdr_;
  uint64_t count_;
};

}  // namespace rawcam
```

`core/src/rawv_reader.cpp`:
```cpp
#include "rawcam/rawv_reader.h"
#include "rawcam/file_io.h"

namespace rawcam {

std::unique_ptr<RawvReader> RawvReader::open(const std::string& path) {
  int fd = io::openRead(path.c_str());
  if (fd < 0) return nullptr;
  FileHeader h{};
  if (!io::readAll(fd, &h, sizeof h) || h.magic != kMagic || h.version != kVersion ||
      h.frameSizeBytes == 0) {
    io::closeFd(fd);
    return nullptr;
  }
  const uint64_t rec = kFrameMetaSize + (uint64_t)h.frameSizeBytes;
  uint64_t count = h.frameCount;
  if (count == 0) {
    // unfinalized (crash / battery pull): recover whole records from file size
    int64_t sz = io::fileSize(fd);
    if (sz > (int64_t)kHeaderSize) count = ((uint64_t)sz - kHeaderSize) / rec;
  }
  return std::unique_ptr<RawvReader>(new RawvReader(fd, h, count));
}

bool RawvReader::readFrame(uint64_t index, FrameMeta* meta, uint8_t* payload) {
  if (index >= count_) return false;
  const uint64_t rec = kFrameMetaSize + (uint64_t)hdr_.frameSizeBytes;
  if (!io::seekTo(fd_, kHeaderSize + index * rec)) return false;
  if (!io::readAll(fd_, meta, sizeof *meta)) return false;
  return io::readAll(fd_, payload, hdr_.frameSizeBytes);
}

RawvReader::~RawvReader() { if (fd_ >= 0) io::closeFd(fd_); }

}  // namespace rawcam
```
Remove the placeholder dummy symbol from `rawv_reader.cpp`.

- [ ] **Step 4: Run tests to verify they pass**

Build + ctest as before. Expected: all pass including both reader cases.

- [ ] **Step 5: Commit**

```powershell
cd C:\Users\User\rawcam ; git add core ; git commit -m "feat(core): RawvReader with crash recovery by record scan"
```

---

### Task 5: 10-bit packing utilities

**Files:**
- Create: `core/include/rawcam/pack10.h`
- Modify: `core/src/pack10.cpp` (replace placeholder)
- Create: `core/tests/test_pack10.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by capture hot path *only if* Task 8's benchmark demands it, and by the exporter to unpack):
  ```cpp
  namespace rawcam {
  // Packs `count` 16-bit samples (only low 10 bits significant) into ceil(count/4)*5 bytes.
  // count must be a multiple of 4. Layout per group: 4 low bytes, then 1 byte of the four 2-bit highs.
  void pack10(const uint16_t* src, size_t count, uint8_t* dst);
  void unpack10(const uint8_t* src, size_t count, uint16_t* dst);  // count = sample count
  constexpr size_t packed10Size(size_t count) { return (count / 4) * 5; }
  }
  ```

- [ ] **Step 1: Write the failing test**

`core/tests/test_pack10.cpp`:
```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/pack10.h"
#include <vector>

using namespace rawcam;

TEST_CASE("pack10 round-trips all 10-bit values") {
  std::vector<uint16_t> src(1024);
  for (size_t i = 0; i < src.size(); i++) src[i] = (uint16_t)(i & 0x3FF);
  std::vector<uint8_t> packed(packed10Size(src.size()));
  std::vector<uint16_t> out(src.size());
  pack10(src.data(), src.size(), packed.data());
  unpack10(packed.data(), out.size(), out.data());
  CHECK(out == src);
  CHECK(packed.size() == 1280);  // 1024 * 10 / 8
}

TEST_CASE("values above 10 bits are truncated to low 10") {
  uint16_t src[4] = {0xFFFF, 0x0400, 0x03FF, 0};
  uint8_t packed[5];
  uint16_t out[4];
  pack10(src, 4, packed);
  unpack10(packed, 4, out);
  CHECK(out[0] == 0x3FF);
  CHECK(out[1] == 0x000);
  CHECK(out[2] == 0x3FF);
  CHECK(out[3] == 0x000);
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `rawcam/pack10.h: No such file or directory`.

- [ ] **Step 3: Implement**

`core/include/rawcam/pack10.h`:
```cpp
#pragma once
#include <cstdint>
#include <cstddef>

namespace rawcam {
void pack10(const uint16_t* src, size_t count, uint8_t* dst);
void unpack10(const uint8_t* src, size_t count, uint16_t* dst);
constexpr size_t packed10Size(size_t count) { return (count / 4) * 5; }
}
```

`core/src/pack10.cpp`:
```cpp
#include "rawcam/pack10.h"

namespace rawcam {

void pack10(const uint16_t* src, size_t count, uint8_t* dst) {
  for (size_t i = 0; i < count; i += 4) {
    uint16_t a = src[i] & 0x3FF, b = src[i + 1] & 0x3FF,
             c = src[i + 2] & 0x3FF, d = src[i + 3] & 0x3FF;
    dst[0] = (uint8_t)a;
    dst[1] = (uint8_t)b;
    dst[2] = (uint8_t)c;
    dst[3] = (uint8_t)d;
    dst[4] = (uint8_t)((a >> 8) | ((b >> 8) << 2) | ((c >> 8) << 4) | ((d >> 8) << 6));
    dst += 5;
  }
}

void unpack10(const uint8_t* src, size_t count, uint16_t* dst) {
  for (size_t i = 0; i < count; i += 4) {
    uint8_t hi = src[4];
    dst[i]     = (uint16_t)(src[0] | ((hi & 0x03) << 8));
    dst[i + 1] = (uint16_t)(src[1] | ((hi & 0x0C) << 6));
    dst[i + 2] = (uint16_t)(src[2] | ((hi & 0x30) << 4));
    dst[i + 3] = (uint16_t)(src[3] | ((hi & 0xC0) << 2));
    src += 5;
  }
}

}  // namespace rawcam
```

- [ ] **Step 4: Run tests to verify they pass**, then **Step 5: Commit**

```powershell
cd C:\Users\User\rawcam ; git add core ; git commit -m "feat(core): 10-bit pack/unpack utilities"
```

---

### Task 6: Minimal DNG writer

**Files:**
- Create: `core/include/rawcam/dng_writer.h`
- Modify: `core/src/dng_writer.cpp` (replace placeholder)
- Create: `core/tests/test_dng_writer.cpp`

**Interfaces:**
- Consumes: `rawv.h` (`FileHeader`, `FrameMeta`, `Cfa`).
- Produces:
  ```cpp
  namespace rawcam {
  // Writes one uncompressed 16-bit CFA DNG. raw16 points at stride-padded RAW16 rows
  // (rowStrideBytes per row); the function de-strides to width*2 internally.
  bool writeDng(const std::string& path, const FileHeader& hdr,
                const FrameMeta& meta, const uint8_t* raw16);
  }
  ```
- DNG layout: little-endian TIFF, single IFD, single strip, PhotometricInterpretation=32803 (CFA), BitsPerSample=16, DNGVersion 1.4.

**Tag list (all in IFD, ascending tag order):** 254 NewSubfileType=0(LONG) · 256 ImageWidth(LONG) · 257 ImageLength(LONG) · 258 BitsPerSample=16(SHORT) · 259 Compression=1(SHORT) · 262 Photometric=32803(SHORT) · 271 Make="RawCam"(ASCII) · 272 Model=hdr.deviceName(ASCII) · 273 StripOffsets(LONG) · 274 Orientation=1(SHORT) · 277 SamplesPerPixel=1(SHORT) · 278 RowsPerStrip=height(LONG) · 279 StripByteCounts=w*h*2(LONG) · 284 PlanarConfig=1(SHORT) · 33421 CFARepeatPatternDim={2,2}(SHORT×2) · 33422 CFAPattern(BYTE×4, from hdr.cfa: RGGB={0,1,1,2} GRBG={1,0,2,1} GBRG={1,2,0,1} BGGR={2,1,1,0}) · 50706 DNGVersion={1,4,0,0}(BYTE×4) · 50707 DNGBackwardVersion={1,2,0,0}(BYTE×4) · 50708 UniqueCameraModel=hdr.deviceName(ASCII) · 50710 CFAPlaneColor={0,1,2}(BYTE×3) · 50711 CFALayout=1(SHORT) · 50714 BlackLevel(LONG×4) · 50717 WhiteLevel(LONG) · 50721 ColorMatrix1(SRATIONAL×9, num=round(f×10000)/den=10000) · 50728 AsShotNeutral(RATIONAL×3, same encoding; if all zeros use {1,1,1}) · 50778 CalibrationIlluminant1=21/D65(SHORT).

- [ ] **Step 1: Write the failing test** — round-trip via a tiny TIFF-parsing checker inside the test

`core/tests/test_dng_writer.cpp`:
```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include "rawcam/dng_writer.h"
#include "rawcam/file_io.h"
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>

using namespace rawcam;

struct TagVal { uint16_t type; uint32_t count; uint32_t valueOrOffset; };

static std::map<uint16_t, TagVal> parseIfd(const std::vector<uint8_t>& b) {
  REQUIRE(b.size() > 8);
  REQUIRE(b[0] == 'I'); REQUIRE(b[1] == 'I');  // little-endian TIFF
  uint32_t ifdOff; std::memcpy(&ifdOff, &b[4], 4);
  uint16_t n; std::memcpy(&n, &b[ifdOff], 2);
  std::map<uint16_t, TagVal> tags;
  for (uint16_t i = 0; i < n; i++) {
    const uint8_t* e = &b[ifdOff + 2 + i * 12];
    uint16_t tag, type; uint32_t count, val;
    std::memcpy(&tag, e, 2); std::memcpy(&type, e + 2, 2);
    std::memcpy(&count, e + 4, 4); std::memcpy(&val, e + 8, 4);
    tags[tag] = {type, count, val};
  }
  return tags;
}

TEST_CASE("dng has required CFA tags and correct pixel strip") {
  FileHeader h{};
  h.magic = kMagic; h.version = kVersion;
  h.width = 4; h.height = 2; h.rowStrideBytes = 12;  // 4px*2B + 4B pad per row
  h.cfa = (uint32_t)Cfa::RGGB; h.whiteLevel = 1023;
  for (int i = 0; i < 4; i++) h.blackLevel[i] = 64;
  h.colorMatrix1[0] = 1.0f; h.colorMatrix1[4] = 1.0f; h.colorMatrix1[8] = 1.0f;
  std::strcpy(h.deviceName, "Pixel Test");
  FrameMeta m{}; m.wbNeutral[0] = 0.5f; m.wbNeutral[1] = 1.0f; m.wbNeutral[2] = 0.7f;

  // stride-padded source: pixel value = row*100 + col
  uint8_t src[24] = {};
  for (int r = 0; r < 2; r++)
    for (int c = 0; c < 4; c++) {
      uint16_t v = (uint16_t)(r * 100 + c);
      std::memcpy(src + r * 12 + c * 2, &v, 2);
    }

  REQUIRE(writeDng("t.dng", h, m, src));

  int fd = io::openRead("t.dng");
  REQUIRE(fd >= 0);
  std::vector<uint8_t> b((size_t)io::fileSize(fd));
  io::readAll(fd, b.data(), b.size());
  io::closeFd(fd);

  auto tags = parseIfd(b);
  CHECK(tags.at(256).valueOrOffset == 4);       // width
  CHECK(tags.at(257).valueOrOffset == 2);       // height
  CHECK(tags.at(262).valueOrOffset == 32803);   // CFA photometric
  CHECK(tags.at(279).valueOrOffset == 16);      // 4*2*2 bytes, de-strided
  CHECK(tags.count(50706) == 1);                // DNGVersion
  CHECK(tags.count(50721) == 1);                // ColorMatrix1
  CHECK(tags.at(50717).valueOrOffset == 1023);  // WhiteLevel
  // CFAPattern RGGB fits inline in value field: bytes 0,1,1,2
  uint32_t cfa = tags.at(33422).valueOrOffset;
  CHECK((cfa & 0xFF) == 0); CHECK(((cfa >> 8) & 0xFF) == 1);
  // strip is de-strided pixels
  uint32_t off = tags.at(273).valueOrOffset;
  uint16_t px5; std::memcpy(&px5, &b[off + 5 * 2], 2);  // row1 col1 => 101
  CHECK(px5 == 101);
  std::remove("t.dng");
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `rawcam/dng_writer.h: No such file or directory`.

- [ ] **Step 3: Implement the DNG writer**

`core/include/rawcam/dng_writer.h`:
```cpp
#pragma once
#include <string>
#include "rawcam/rawv.h"

namespace rawcam {
bool writeDng(const std::string& path, const FileHeader& hdr,
              const FrameMeta& meta, const uint8_t* raw16);
}
```

`core/src/dng_writer.cpp` — build the file in memory, then one `writeAll`:
```cpp
#include "rawcam/dng_writer.h"
#include "rawcam/file_io.h"
#include <cmath>
#include <cstring>
#include <vector>

namespace rawcam {
namespace {

enum : uint16_t { BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, SRATIONAL = 10 };

struct Entry { uint16_t tag, type; uint32_t count, value; };

class Dng {
 public:
  // inline value entries
  void addShort(uint16_t tag, uint16_t v) { entries_.push_back({tag, SHORT, 1, v}); }
  void addLong(uint16_t tag, uint32_t v) { entries_.push_back({tag, LONG, 1, v}); }
  void addBytes(uint16_t tag, const uint8_t* v, uint32_t n) {  // n <= 4
    uint32_t packed = 0;
    for (uint32_t i = 0; i < n; i++) packed |= (uint32_t)v[i] << (8 * i);
    entries_.push_back({tag, BYTE, n, packed});
  }
  void addShorts2(uint16_t tag, uint16_t a, uint16_t b) {
    entries_.push_back({tag, SHORT, 2, (uint32_t)a | ((uint32_t)b << 16)});
  }
  // out-of-line entries (data area)
  void addAscii(uint16_t tag, const char* s) {
    uint32_t n = (uint32_t)std::strlen(s) + 1;
    if (n <= 4) { uint32_t v = 0; std::memcpy(&v, s, n); entries_.push_back({tag, ASCII, n, v}); }
    else entries_.push_back({tag, ASCII, n, defer(s, n)});
  }
  void addLongs(uint16_t tag, const uint32_t* v, uint32_t n) {
    entries_.push_back({tag, LONG, n, n == 1 ? v[0] : defer(v, n * 4)});
  }
  void addRationals(uint16_t tag, uint16_t type, const float* v, uint32_t n) {
    std::vector<uint32_t> r(n * 2);
    for (uint32_t i = 0; i < n; i++) {
      r[i * 2] = (uint32_t)(int32_t)std::lround((double)v[i] * 10000.0);
      r[i * 2 + 1] = 10000;
    }
    entries_.push_back({tag, type, n, defer(r.data(), n * 8)});
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
    std::vector<uint8_t> out;
    out.reserve(stripStart + pixelBytes);
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
    out.insert(out.end(), pixels, pixels + pixelBytes);
    int fd = io::openWrite(path.c_str());
    if (fd < 0) return false;
    bool ok = io::writeAll(fd, out.data(), out.size());
    io::closeFd(fd);
    return ok;
  }

 private:
  struct Entry { uint16_t tag, type; uint32_t count, value; bool deferred = false;
                 Entry(uint16_t t, uint16_t ty, uint32_t c, uint32_t v) : tag(t), type(ty), count(c), value(v) {} };
  uint32_t defer(const void* src, uint32_t n) {
    uint32_t off = (uint32_t)data_.size();
    const uint8_t* p = static_cast<const uint8_t*>(src);
    data_.insert(data_.end(), p, p + n);
    if (data_.size() & 1) data_.push_back(0);  // word-align
    entriesDeferredNext_ = true;
    return off;
  }
  void push(Entry e) { e.deferred = entriesDeferredNext_; entriesDeferredNext_ = false; entries_.push_back(e); }
  static void append(std::vector<uint8_t>& v, const void* p, size_t n) {
    const uint8_t* b = static_cast<const uint8_t*>(p);
    v.insert(v.end(), b, b + n);
  }
  std::vector<Entry> entries_;
  std::vector<uint8_t> data_;
  bool entriesDeferredNext_ = false;
};

}  // namespace

bool writeDng(const std::string& path, const FileHeader& hdr,
              const FrameMeta& meta, const uint8_t* raw16) {
  const uint32_t w = hdr.width, h = hdr.height;
  // de-stride into contiguous pixels
  std::vector<uint8_t> pixels((size_t)w * h * 2);
  for (uint32_t r = 0; r < h; r++)
    std::memcpy(pixels.data() + (size_t)r * w * 2,
                raw16 + (size_t)r * hdr.rowStrideBytes, (size_t)w * 2);

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
  d.addLongs(50714, hdr.blackLevel, 4);
  d.addLong(50717, hdr.whiteLevel);
  d.addRationals(50721, SRATIONAL, hdr.colorMatrix1, 9);
  float wb[3] = {meta.wbNeutral[0], meta.wbNeutral[1], meta.wbNeutral[2]};
  if (wb[0] == 0 && wb[1] == 0 && wb[2] == 0) { wb[0] = wb[1] = wb[2] = 1.0f; }
  d.addRationals(50728, RATIONAL, wb, 3);
  d.addShort(50778, 21);
  return d.write(path, pixels.data(), (uint32_t)pixels.size());
}

}  // namespace rawcam
```
Note for the implementer: the sketch above mixes two Entry declarations — unify on the private `Entry` with the `deferred` flag and route every `entries_.push_back` through `push()`. The test is the contract; refactor freely until it passes. Remove the placeholder symbol from `dng_writer.cpp`.

- [ ] **Step 4: Run tests to verify they pass**

Build + ctest. Expected: all tests pass.

- [ ] **Step 5: Sanity-check a generated DNG with a real parser (best-effort)**

If Python is available: `pip install rawpy` then in the test build dir write a quick script that opens a generated `t.dng` via `rawpy.imread` and prints `raw_image.shape`. If rawpy refuses install, defer real-world validation to Task 13 (Resolve is the final authority). Do not block the task on this step.

- [ ] **Step 6: Commit**

```powershell
cd C:\Users\User\rawcam ; git add core ; git commit -m "feat(core): minimal DNG writer with CFA tags"
```

---

### Task 7: Android app scaffold with NDK bridge

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/shez/rawcam/MainActivity.kt`
- Create: `app/src/main/java/com/shez/rawcam/NativeBridge.kt`
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/jni_bridge.cpp`

**Interfaces:**
- Consumes: `core/` static library (added via `add_subdirectory`).
- Produces: installable debug APK; `NativeBridge` Kotlin object with `external fun nativeVersion(): String` proving the JNI + core link works. Later tasks extend `NativeBridge` and `jni_bridge.cpp`.

- [ ] **Step 1: Root Gradle files**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "rawcam"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
android.useAndroidX=true
```

Generate the wrapper (uses any existing Gradle via Android Studio's, or download): simplest is copying `gradle/wrapper/` + `gradlew.bat` from any existing project in `.gradle` cache era 8.11.1, or run `& "$env:ProgramFiles\Android\Android Studio\jbr\bin\java.exe" ...` — if neither exists, install Gradle 8.11.1 by downloading the distribution zip and running `gradle wrapper --gradle-version 8.11.1`. Verify with `.\gradlew.bat --version`.

- [ ] **Step 2: app module**

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shez.rawcam"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.shez.rawcam"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-feature android:name="android.hardware.camera.raw" android:required="true" />
    <application android:label="RawCam" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true"
                  android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(rawcam_jni CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core core_build)
add_library(rawcam_jni SHARED jni_bridge.cpp)
target_link_libraries(rawcam_jni rawcam_core android mediandk log)
```

`app/src/main/cpp/jni_bridge.cpp`:
```cpp
#include <jni.h>
#include <string>
#include "rawcam/rawv.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_shez_rawcam_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
  std::string v = "rawv v" + std::to_string(rawcam::kVersion);
  return env->NewStringUTF(v.c_str());
}
```

`app/src/main/java/com/shez/rawcam/NativeBridge.kt`:
```kotlin
package com.shez.rawcam

object NativeBridge {
    init { System.loadLibrary("rawcam_jni") }
    external fun nativeVersion(): String
}
```

`app/src/main/java/com/shez/rawcam/MainActivity.kt`:
```kotlin
package com.shez.rawcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("RawCam core: ${NativeBridge.nativeVersion()}") }
    }
}
```

- [ ] **Step 3: Build, install, verify on device**

```powershell
cd C:\Users\User\rawcam ; .\gradlew.bat :app:assembleDebug
& $ADB -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
& $ADB -s <serial> shell am start -n com.shez.rawcam/.MainActivity
```
Expected: build succeeds (core compiles under NDK too — this is the first cross-compile of `core/`, fix any MinGW-isms now); screen shows `RawCam core: rawv v1`.

- [ ] **Step 4: Commit**

```powershell
git add -A ; git commit -m "feat(app): Compose scaffold with NDK bridge linking rawcam_core"
```

---

### Task 8: Storage benchmark mode (the decision gate)

**Files:**
- Create: `app/src/main/cpp/benchmark.cpp`, `app/src/main/cpp/benchmark.h`
- Modify: `app/src/main/cpp/CMakeLists.txt` (add benchmark.cpp), `app/src/main/cpp/jni_bridge.cpp`
- Modify: `app/src/main/java/com/shez/rawcam/NativeBridge.kt`, `MainActivity.kt`

**Interfaces:**
- Produces: `NativeBridge.nativeBenchmarkWrite(path: String, frameBytes: Int, frames: Int): Double` returning sustained MB/s, and a "Benchmark" button in the UI showing the result.

- [ ] **Step 1: Implement native benchmark**

`app/src/main/cpp/benchmark.h`:
```cpp
#pragma once
#include <cstdint>
namespace rawcam { double benchmarkWrite(const char* path, uint32_t frameBytes, uint32_t frames); }
```

`app/src/main/cpp/benchmark.cpp`:
```cpp
#include "benchmark.h"
#include "rawcam/file_io.h"
#include <chrono>
#include <cstdio>
#include <vector>

namespace rawcam {
double benchmarkWrite(const char* path, uint32_t frameBytes, uint32_t frames) {
  std::vector<uint8_t> frame(frameBytes, 0xA5);
  int fd = io::openWrite(path);
  if (fd < 0) return -1.0;
  auto t0 = std::chrono::steady_clock::now();
  for (uint32_t i = 0; i < frames; i++)
    if (!io::writeAll(fd, frame.data(), frame.size())) { io::closeFd(fd); return -1.0; }
#ifndef _WIN32
  fsync(fd);
#endif
  auto t1 = std::chrono::steady_clock::now();
  io::closeFd(fd);
  std::remove(path);
  double secs = std::chrono::duration<double>(t1 - t0).count();
  return ((double)frameBytes * frames / 1e6) / secs;
}
}
```

JNI addition in `jni_bridge.cpp`:
```cpp
#include "benchmark.h"
extern "C" JNIEXPORT jdouble JNICALL
Java_com_shez_rawcam_NativeBridge_nativeBenchmarkWrite(
    JNIEnv* env, jobject, jstring jpath, jint frameBytes, jint frames) {
  const char* p = env->GetStringUTFChars(jpath, nullptr);
  double r = rawcam::benchmarkWrite(p, (uint32_t)frameBytes, (uint32_t)frames);
  env->ReleaseStringUTFChars(jpath, p);
  return r;
}
```

Kotlin: add `external fun nativeBenchmarkWrite(path: String, frameBytes: Int, frames: Int): Double` to `NativeBridge`; in `MainActivity` add a button that runs on `Dispatchers.IO`:
```kotlin
val path = File(getExternalFilesDir(null), "bench.bin").absolutePath
val mbps = NativeBridge.nativeBenchmarkWrite(path, 25_000_000, 240)  // ~6GB, ≈10s of 12MP RAW16 @24fps
```
and shows `"%.0f MB/s".format(mbps)`.

- [ ] **Step 2: Run on device, record the number**

Build, install, tap Benchmark. Run 3 times; note the *lowest* sustained MB/s.
**Decision gate (from spec):** if lowest ≥ ~700 MB/s → record RAW16 as-delivered (`PackMode::Raw16`); if lower → Task 9 must pack the hot path with `pack10` (`PackMode::Packed10`, `frameSizeBytes = packed10Size(width*height)... rounded appropriately` — de-stride happens during packing). Write the measured number and the chosen mode into the commit message and into `docs/superpowers/plans/benchmark-result.md`.

- [ ] **Step 3: Commit**

```powershell
git add -A ; git commit -m "feat(app): storage write benchmark - measured <N> MB/s, chose <mode>"
```

---

### Task 9: Native capture module (hot path)

**Files:**
- Create: `app/src/main/cpp/capture.h`, `app/src/main/cpp/capture.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`, `jni_bridge.cpp`, `NativeBridge.kt`

**Interfaces:**
- Consumes: `RawvWriter`, `pack10` (if Packed10 chosen in Task 8).
- Produces JNI surface for Task 10:
  ```kotlin
  object NativeBridge {
      // returns a Surface for the Camera2 RAW target, or null
      external fun nativeStartRecording(path: String, width: Int, height: Int,
          cfa: Int, whiteLevel: Int, blackLevel: IntArray /*4*/,
          colorMatrix1: FloatArray /*9*/, fpsNum: Int, fpsDen: Int,
          deviceName: String): android.view.Surface?
      external fun nativePushFrameMeta(timestampNs: Long, iso: Int, exposureNs: Long,
          focusDistance: Float, wbR: Float, wbG: Float, wbB: Float)
      // returns longArrayOf(framesWritten, framesDropped)
      external fun nativeStopRecording(): LongArray
  }
  ```

**Design (implement exactly):**
- `Capture` is a singleton owning: `AImageReader*` (RAW16, `maxImages = 12`), a mutex+condvar deque of `AImage*` (bounded at 8), a writer `std::thread`, the `RawvWriter`, an atomic drop counter, and a mutex-guarded `std::map<int64_t, FrameMeta>` of pending metadata keyed by sensor timestamp (pruned to the newest 64 entries).
- `AImageReader_ImageListener` callback (`onImageAvailable`): `AImageReader_acquireLatestImage`... **no — use `acquireNextImage`** (latest silently drops; we count drops ourselves). If acquire fails or queue size == 8: delete (`AImage_delete`) immediately and `dropped_++`. Otherwise push to deque and notify.
- Writer thread loop: pop image; `AImage_getPlaneData(img, 0, &data, &len)`; `AImage_getTimestamp` → look up pending meta by exact timestamp (erase on hit; on miss use last-known values and leave `timestampNs` = image timestamp); build `FrameMeta` (frameIndex = framesWritten, droppedSoFar = dropped_); Raw16 mode: `writeFrame(meta, data)` with `frameSizeBytes = rowStride * height` (query stride once from the first image via `AImage_getPlaneRowStride` — `RawvWriter::create` is deferred until the first frame so the real stride goes into the header); Packed10 mode: pack rows into a preallocated buffer (de-striding as it packs), then write. Finally `AImage_delete(img)` to recycle the hardware buffer.
- `start()`: `AImageReader_new(w, h, AIMAGE_FORMAT_RAW16, 12, &reader)`, set listener, `AImageReader_getWindow`, wrap with `ANativeWindow_toSurface(env, window)` for the return value. Store the header template; writer created lazily on first frame (see above).
- `stop()`: signal writer to drain the deque, join thread, `writer->finalize()`, `AImageReader_delete`, return `{framesWritten, dropped}`.
- Everything in the callback path must be allocation-free after the first frame.

- [ ] **Step 1: Implement `capture.h`/`capture.cpp` per the design above** (~200 lines; the design bullets are the spec — every named NDK call is real: `media/NdkImageReader.h`, `android/native_window_jni.h`).

- [ ] **Step 2: Add JNI wrappers + Kotlin externals** matching the Produces block exactly. `nativeStartRecording` marshals the arrays into a `FileHeader` template.

- [ ] **Step 3: Build for device**

```powershell
.\gradlew.bat :app:assembleDebug
```
Expected: compiles and links (`mediandk` provides AImageReader). Runtime verification lands in Task 10 — commit now anyway; this task's deliverable is reviewable code that builds.

- [ ] **Step 4: Commit**

```powershell
git add -A ; git commit -m "feat(app): native RAW16 capture pipeline (AImageReader -> queue -> RawvWriter)"
```

---

### Task 10: Camera2 controller with manual exposure

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: `NativeBridge` from Task 9.
- Produces:
  ```kotlin
  class CameraController(private val context: Context) {
      data class RawSpec(val width: Int, val height: Int, val cfa: Int, val whiteLevel: Int,
                         val blackLevel: IntArray, val colorMatrix1: FloatArray,
                         val isoRange: ClosedRange<Int>, val maxFps: Int,
                         val minFocusDiopters: Float, val deviceName: String)
      val rawSpec: RawSpec                       // queried from CameraCharacteristics at init
      fun openAndPreview(previewSurface: Surface, onReady: () -> Unit)
      fun startRecording(fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float): Boolean
      fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float)  // live while recording or previewing
      fun stopRecording(): LongArray             // [written, dropped] from NativeBridge
      fun close()
  }
  ```

**Implementation notes (follow exactly):**
- Pick the back camera with `REQUEST_AVAILABLE_CAPABILITIES_RAW`. RAW size = largest `StreamConfigurationMap.getOutputSizes(ImageFormat.RAW_SENSOR)`.
- `rawSpec` fields: `cfa` from `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` (same 0-3 order as our `Cfa` enum); `whiteLevel` from `SENSOR_INFO_WHITE_LEVEL`; `blackLevel` from `SENSOR_BLACK_LEVEL_PATTERN` (`.copyOf()` its 4 ints); `colorMatrix1` from `SENSOR_COLOR_TRANSFORM1` (9 `Rational`s → float); `isoRange` from `SENSOR_INFO_SENSITIVITY_RANGE`; `maxFps = (1e9 / map.getOutputMinFrameDuration(RAW_SENSOR, size)).toInt()`; `minFocusDiopters` from `LENS_INFO_MINIMUM_FOCUS_DISTANCE`; `deviceName = Build.MODEL`.
- Preview-only session first: one repeating request (TEMPLATE_PREVIEW) to `previewSurface` with AWB auto.
- `startRecording`: call `NativeBridge.nativeStartRecording(...)` to get the RAW `Surface`; recreate the session with both surfaces; repeating request (TEMPLATE_RECORD) targeting **both**, with `CONTROL_AE_MODE = OFF`, `SENSOR_SENSITIVITY = iso`, `SENSOR_EXPOSURE_TIME = exposureNs` (clamp to < 1e9/fps), `SENSOR_FRAME_DURATION = (1e9 / fps).toLong()`, `CONTROL_AF_MODE = OFF`, `LENS_FOCUS_DISTANCE = focusDiopters`, `CONTROL_AWB_MODE = AUTO`.
- `CaptureCallback.onCaptureCompleted`: read `SENSOR_TIMESTAMP`, `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `LENS_FOCUS_DISTANCE`, and derive `wbNeutral` from `COLOR_CORRECTION_GAINS` as `floatArrayOf(1f/g.red, 1f/((g.greenEven+g.greenOdd)/2f), 1f/g.blue)` (guard divide-by-zero → 1f); forward via `nativePushFrameMeta`.
- Clip file path decided by caller (Task 11) and passed through; recordings dir is `File(context.getExternalFilesDir(null), "clips")` (mkdir).
- All camera callbacks on a dedicated `HandlerThread("camera")`.

- [ ] **Step 1: Implement CameraController.kt per the notes** (~180 lines).
- [ ] **Step 2: Build** — `.\gradlew.bat :app:assembleDebug`, expected clean compile.
- [ ] **Step 3: Commit** — `git add -A ; git commit -m "feat(app): Camera2 controller with locked manual exposure + meta forwarding"`

---

### Task 11: Record screen UI

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`
- Modify: `app/src/main/java/com/shez/rawcam/MainActivity.kt`

**Interfaces:**
- Consumes: `CameraController`.
- Produces: the working recording experience — viewfinder, manual controls, record/stop, live stats.

**Layout (landscape):** full-screen `AndroidView { SurfaceView }` viewfinder; right rail = big record/stop toggle button + recording timer (`MM:SS`) + drop counter (`dropped: N`, turns red when > 0); bottom rail = three sliders with value labels: ISO (log scale across `isoRange`), Shutter (discrete stops: 1/24 1/48 1/60 1/120 1/240 1/500 1/1000, clamped to frame interval), Focus (0..minFocusDiopters, labelled ∞ at 0); an fps selector (24/30, filtered to `<= rawSpec.maxFps`).

**Behavior:**
- Camera permission via `rememberLauncherForActivityResult(RequestPermission())` on first composition; the screen shows a "grant camera" button until granted.
- On record press: free-space check first — `StatFs(filesDir).availableBytes`; required = `frameBytes * fps * 35` (35s ceiling); if insufficient show a Snackbar with max recordable seconds and refuse. Clip name `clip_yyyyMMdd_HHmmss.rawv`.
- Timer + drop counter poll `NativeBridge`-reported stats every 500ms while recording (add `external fun nativeGetStats(): LongArray` returning `[written, dropped]` — implement in `capture.cpp` as atomic reads).
- On stop: show summary Snackbar `"{written} frames, {dropped} dropped"`.
- Register `PowerManager.addThermalStatusListener`; at `THERMAL_STATUS_SEVERE`+ show a persistent warning banner.
- On `onStop()` of the activity while recording: stop recording cleanly (finalize) before releasing the camera.

- [ ] **Step 1: Implement the screen + wire MainActivity** (~250 lines of Compose; keep state in a small `RecordViewModel`).
- [ ] **Step 2: Build, install, record a 10s test clip on the Pixel**

```powershell
.\gradlew.bat :app:assembleDebug ; & $ADB -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
```
Manual check: viewfinder live; set ISO 400, shutter 1/48, fps 24; record 10s; expected `written ≈ 240`, `dropped` small (ideally 0); file exists:
```powershell
& $ADB -s <serial> shell ls -l /sdcard/Android/data/com.shez.rawcam/files/clips/
```
Expected: one `.rawv` of ≈ `240 * (64 + frameSizeBytes) + 512` bytes. If drops are heavy, this is where the Task 8 pack-mode decision gets revisited — measure, don't guess.

- [ ] **Step 3: Commit** — `git add -A ; git commit -m "feat(app): record screen with manual controls and live stats"`

---

### Task 12: Export to CinemaDNG + clips library

**Files:**
- Create: `app/src/main/cpp/exporter.h`, `app/src/main/cpp/exporter.cpp`
- Create: `app/src/main/java/com/shez/rawcam/export/ExportService.kt`
- Create: `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt`
- Create: `core/tests/test_export.cpp` (host test of the export loop)
- Modify: `jni_bridge.cpp`, `NativeBridge.kt`, `MainActivity.kt` (simple two-tab navigation Record | Clips), `core/CMakeLists.txt` if needed

**Interfaces:**
- Produces:
  ```cpp
  // core-level, host-testable (move into core/src/exporter.cpp + core/include/rawcam/exporter.h):
  // callback returns false to cancel; frames named 000000.dng, 000001.dng, ...
  bool exportClip(const std::string& rawvPath, const std::string& outDir,
                  const std::function<bool(uint64_t done, uint64_t total)>& progress);
  ```
  ```kotlin
  external fun nativeExportClip(rawvPath: String, outDir: String, cb: ExportCallback): Boolean
  interface ExportCallback { fun onProgress(done: Long, total: Long): Boolean }
  ```

**Implementation notes:**
- `exportClip` (in `core/`, so the host test covers it): open with `RawvReader`; if `packMode == Packed10`, `unpack10` into a scratch RAW16 buffer and synthesize `rowStrideBytes = width*2` for the DNG call; loop `readFrame` → `writeDng(outDir + "/" + %06d + ".dng", ...)`; call `progress` each frame.
- Host test `test_export.cpp`: write a 3-frame clip (reuse Task 4's `writeClip` pattern), export to a temp dir, assert 3 `.dng` files exist and progress was called with (3,3) last. Directory creation: take pre-created dir; the Kotlin side makes dirs.
- `ExportService`: a `Service` started with `startForegroundService`, notification channel "export", posts progress % in the notification, runs `nativeExportClip` on a background thread, stops self when done. Cancel = stop service → callback returns false.
- `ClipsScreen`: lists `clips/*.rawv` (name, size, frame count = `(size - 512) / (64 + frameSizeBytes)` — read frameSizeBytes via a tiny `external fun nativeClipInfo(path: String): IntArray` returning `[width, height, fps, frameCount]`); per-clip Export button starts the service; exported folders shown under each clip; Delete button with a plain confirm dialog.

- [ ] **Step 1: Write the failing host test `test_export.cpp`**, run (FAIL: no exporter.h).
- [ ] **Step 2: Implement `core` exporter, run host tests** — expected: all pass.
- [ ] **Step 3: Implement JNI + ExportService + ClipsScreen**, build.
- [ ] **Step 4: On-device check** — export the Task 11 clip; expected notification progress → `exports/clip_.../000000.dng...` present with plausible sizes (≈ w*h*2 + ~1KB each).
- [ ] **Step 5: Commit** — `git add -A ; git commit -m "feat: CinemaDNG export service and clips library"`

---

### Task 13: End-to-end validation (the v1 bar)

**Files:**
- Create: `docs/superpowers/plans/v1-validation.md` (results record)

- [ ] **Step 1: Record the reference clip** — outdoors or well-lit: fps 24, shutter 1/48, ISO to taste, 15 seconds. Note `written`/`dropped` from the summary.
- [ ] **Step 2: Verify frame accounting** — `written + dropped ≈ 15 * 24 ± 5`. Written count must match `nativeClipInfo` frameCount.
- [ ] **Step 3: Export and pull** —
```powershell
& $ADB -s <serial> pull /sdcard/Android/data/com.shez.rawcam/files/exports/ C:\Users\User\rawcam\testfootage\
```
- [ ] **Step 4: Desktop validation** — if exiftool/rawpy available, verify tags on one DNG; then the real gate: **open the folder in DaVinci Resolve as an image sequence, confirm it debayers, plays at 24fps, and grades (exposure/WB wheels respond sanely)**. This step needs the user's eyes — ask them to confirm.
- [ ] **Step 5: Record results in `v1-validation.md`** — device model, benchmark MB/s, pack mode, fps achieved, drop counts across 3 takes, Resolve result. Commit.
- [ ] **Step 6: Tag** — `git tag v0.1 ; git commit` allow empty if needed; this is v1 done per spec.

---

## Self-review notes (already applied)

- **Spec coverage:** container+recovery (T2-4), packing decision by benchmark (T5+T8), DNG/no-Adobe (T6), zero-copy hot path with drop-don't-stall (T9), manual ISO/shutter/focus with AE off (T10-11), storage pre-check + thermal + drop surfacing (T11), export service + progress (T12), desktop-tested core (T2-6,12), wireless adb (T1), Resolve as final authority (T13). Deferred list untouched.
- **Known simplification vs spec:** no trailing index; header `frameCount` + fixed records + scan recovery achieve the same guarantees (documented in Global Constraints).
- **Type consistency check:** `FileHeader`/`FrameMeta` field names used in T3/T4/T6/T9 match T2; `NativeBridge` signatures in T9 match T10/T11 consumption; `Cfa` ordering matches Camera2 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` constants (0=RGGB..3=BGGR) and the DNG CFA byte table in T6.
- **Honest risk flags for the implementer:** (1) T6's `Dng` class sketch needs the Entry unification noted inline — the test is the contract. (2) `ANativeWindow_toSurface` requires the JNIEnv of the calling thread — do it inside the `nativeStartRecording` JNI call, not on the writer thread. (3) If Gradle wrapper bootstrap is painful, installing Gradle 8.11.1 once via a zip is fine — the wrapper pins it thereafter.
