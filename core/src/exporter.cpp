#include "rawcam/exporter.h"

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

#include "rawcam/dng_writer.h"
#include "rawcam/pack10.h"
#include "rawcam/rawv_reader.h"

namespace rawcam {

namespace {

bool exportFrame(RawvReader& reader, uint64_t index, PackMode mode, bool packed,
                  size_t pixelCount, const FileHeader& dngHdr, const std::string& outDir,
                  std::vector<uint8_t>& payload, std::vector<uint16_t>& unpacked) {
  FrameMeta meta{};
  if (!reader.readFrame(index, &meta, payload.data())) return false;

  const uint8_t* raw16;
  if (mode == PackMode::Packed10) {
    unpack10(payload.data(), pixelCount, unpacked.data());
    raw16 = reinterpret_cast<const uint8_t*>(unpacked.data());
  } else if (mode == PackMode::Packed12) {
    unpack12(payload.data(), pixelCount, unpacked.data());
    raw16 = reinterpret_cast<const uint8_t*>(unpacked.data());
  } else {
    raw16 = payload.data();
  }

  char name[32];
  std::snprintf(name, sizeof name, "%06llu.dng", (unsigned long long)index);
  return writeDng(outDir + "/" + name, dngHdr, meta, raw16);
}

}  // namespace

bool exportClip(const std::string& rawvPath, const std::string& outDir,
                 const std::function<bool(uint64_t done, uint64_t total)>& progress) {
  auto probe = RawvReader::open(rawvPath);
  if (!probe) return false;

  const FileHeader srcHdr = probe->header();
  const uint64_t total = probe->frameCount();
  probe.reset();  // each worker below opens its own independent fd

  const PackMode mode = (PackMode)srcHdr.packMode;
  const bool packed = mode == PackMode::Packed10 || mode == PackMode::Packed12;
  const size_t pixelCount = (size_t)srcHdr.width * (size_t)srcHdr.height;

  // Header passed to writeDng: for packed modes, the payload we hand it is
  // contiguous (post-unpack) RAW16, not the original strided sensor layout,
  // so rowStrideBytes must be synthesized to width*2. Raw16 clips are already
  // stride-padded and keep their original rowStrideBytes.
  FileHeader dngHdr = srcHdr;
  if (packed) dngHdr.rowStrideBytes = srcHdr.width * 2;

  if (total == 0) return true;

  // Each output DNG is a fully independent file (000000.dng, 000001.dng, ...
  // never depend on each other), so frames are handed out to a small pool of
  // worker threads instead of processed one at a time on a single core. The
  // old design alternated between I/O wait (read/write) and CPU work (unpack,
  // DNG serialization) on one thread, leaving every other core idle for the
  // whole export; this overlaps that work across cores and lets the storage
  // stack see more than one request in flight at a time.
  //
  // Only the ORIGINAL calling thread (already JNI-attached when invoked via
  // nativeExportClip) may call `progress` -- worker threads below are plain
  // std::thread and were never attached to the JVM, so a JNIEnv* obtained on
  // the caller's thread is not valid there. Workers therefore only touch
  // plain counters guarded by `mu`; this thread waits on `cv` and is the sole
  // caller of `progress`, exactly preserving the single-thread JNI callback
  // contract the rest of the bridge assumes.
  const unsigned hw = std::thread::hardware_concurrency();
  const unsigned workerCount = (unsigned)std::max<uint64_t>(
      1, std::min<uint64_t>(hw == 0 ? 4u : hw, std::min<uint64_t>(total, 8)));

  std::mutex mu;
  std::condition_variable cv;
  std::atomic<uint64_t> nextIndex{0};
  uint64_t doneCount = 0;                // guarded by mu
  bool stopAll = false;                  // guarded by mu: failure or caller cancel
  bool failed = false;                   // guarded by mu
  unsigned activeWorkers = workerCount;  // guarded by mu

  auto worker = [&]() {
    auto reader = RawvReader::open(rawvPath);
    std::vector<uint8_t> payload(srcHdr.frameSizeBytes);
    std::vector<uint16_t> unpacked;
    if (packed) unpacked.resize(pixelCount);

    bool localFailed = !reader;
    if (reader) {
      for (;;) {
        {
          std::lock_guard<std::mutex> lock(mu);
          if (stopAll) break;
        }
        uint64_t idx = nextIndex.fetch_add(1, std::memory_order_relaxed);
        if (idx >= total) break;
        bool ok = exportFrame(*reader, idx, mode, packed, pixelCount, dngHdr, outDir, payload,
                               unpacked);
        std::lock_guard<std::mutex> lock(mu);
        if (!ok) {
          failed = true;
          stopAll = true;
          break;
        }
        doneCount++;
        cv.notify_one();
      }
    }
    std::lock_guard<std::mutex> lock(mu);
    if (localFailed) {
      failed = true;
      stopAll = true;
    }
    if (--activeWorkers == 0) cv.notify_one();
  };

  std::vector<std::thread> pool;
  pool.reserve(workerCount);
  for (unsigned i = 0; i < workerCount; i++) pool.emplace_back(worker);

  uint64_t lastReported = 0;
  bool cancelledByCaller = false;
  {
    std::unique_lock<std::mutex> lock(mu);
    for (;;) {
      cv.wait(lock, [&] { return doneCount != lastReported || activeWorkers == 0; });
      uint64_t done = doneCount;
      bool allStopped = activeWorkers == 0;
      if (done != lastReported) {
        lastReported = done;
        lock.unlock();
        bool cont = !progress || progress(done, total);
        lock.lock();
        if (!cont) {
          cancelledByCaller = true;
          stopAll = true;
        }
      }
      if (allStopped) break;
    }
  }

  for (auto& t : pool) t.join();

  return !failed && !cancelledByCaller;
}

}  // namespace rawcam
