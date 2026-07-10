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
    if (!io::writeAll(fd, frame.data(), frame.size())) {
      io::closeFd(fd);
      std::remove(path);  // don't leave a partial multi-GB file behind (e.g. ENOSPC)
      return -1.0;
    }
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
