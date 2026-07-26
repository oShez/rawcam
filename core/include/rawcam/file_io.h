#pragma once
#include <cerrno>
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
    if (w < 0 && errno == EINTR) continue;  // signal-interrupted, not a failure
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
    if (r < 0 && errno == EINTR) continue;  // signal-interrupted, not a failure
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
// Flushes kernel buffers to durable storage. Best-effort: the return value is
// intentionally ignored at call sites -- a failed sync narrows an already-rare
// post-finalize power-pull window further but must never fail the finalize
// (or export) it's called from.
inline void syncFd(int fd) {
#ifdef _WIN32
  _commit(fd);
#else
  ::fsync(fd);
#endif
}

}  // namespace rawcam::io
