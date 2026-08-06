# Round 4 Stage 3: Thread-Topology Tuning — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Size and pin `ParallelFrameEncoder`'s worker pool to the device's big+mid CPU cluster (never the efficiency cores), raising real parallelism for the dominant dispatch+wait phase without regressing today's behavior on any device.

**Architecture:** A pure, host-testable `selectWorkerCores()` clusters per-core max frequencies and returns the non-efficiency core indices; a pure `workerThreadCount()` applies the margin and regression floor. A thin, Android-only (`#ifdef __ANDROID__`) layer reads the frequencies from sysfs and applies a shared affinity mask to the workers via `pthread_setaffinity_np`. On the host (and any non-Android build) the encoder falls back to today's exact `min(hardware_concurrency(), 4)` behavior with no affinity call.

**Tech Stack:** C++17, doctest (host tests via SDK CMake/ctest under PowerShell), Android NDK (`sched.h`/`pthread` affinity, `<android/log.h>`), arm64.

## Global Constraints

- **Host tests run under PowerShell only**, using the SDK's CMake/ctest by full path — MinGW g++ silently fails under the Git Bash tool. Build: `& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build`. Full suite: `& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir C:\Users\User\rawcam\core\build --output-on-failure`. Single doctest case: `C:\Users\User\rawcam\core\build\test_rawv_codec.exe -tc "<case name>"`.
- **`app/src/main/cpp/` has no host test coverage by convention** — the sysfs reader and the real `pthread_setaffinity_np` calls are Android-only and verified only by the on-device checkpoint (Task 4). Everything host-testable in this plan lives in `core/`.
- **No bitstream/format change**: `kVersion`, the Rice/merge logic, and encoded bytes must be byte-identical to today. This round changes only worker-pool size and CPU affinity — timing, not output.
- **Never regress below today's baseline**: for any device where topology can't be read confidently, behavior must be byte-for-byte identical to the current `min(hardware_concurrency(), 4)` default with no affinity call.
- **Only the default `threadCount=0` constructor path changes.** Every existing test call site passes an explicit count (e.g. `4`, `8`) and must be completely unaffected.
- Spec: `docs/superpowers/specs/2026-08-06-rawv-codec-round4-thread-topology-tuning-design.md`. History: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`.

---

## File Structure

- `core/include/rawcam/rawv_codec.h` — declare the two new pure free functions (`selectWorkerCores`, `workerThreadCount`); add a `workerCores_` member and a private `applyWorkerAffinity()` method to `ParallelFrameEncoder`.
- `core/src/rawv_codec.cpp` — implement the two pure functions; add the Android-only `readMaxFreqPerCore()` (file-local) and `applyWorkerAffinity()`; wire both into the constructor's `threadCount==0` branch under `#ifdef __ANDROID__`.
- `core/tests/test_rawv_codec.cpp` — host tests for `selectWorkerCores()`, `workerThreadCount()`, and a default-constructor equivalence test.
- `app/src/main/cpp/capture.cpp` — **no change** (line 389 already constructs with the default `threadCount`; the new behavior is entirely inside the encoder).
- `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md` — append the Task 4 on-device checkpoint results.

---

### Task 1: Pure `selectWorkerCores()` — cluster non-efficiency cores

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h` (add declaration after the existing free-function declarations, before `class ParallelFrameEncoder`)
- Modify: `core/src/rawv_codec.cpp` (add `#include <algorithm>` if absent; implement in the `rawcam` namespace, near the other free functions)
- Test: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Produces: `std::vector<int> selectWorkerCores(const std::vector<long>& maxFreqKhzPerCore);` — returns the indices of every core NOT in the lowest-frequency cluster. Returns an **empty vector** if the input is empty, if any entry is `< 0` (unreadable core → whole detection invalid), or if fewer than 2 distinct frequencies are present (uniform topology → no confident split). This empty return is the "fall back to default" signal for Task 3.

- [ ] **Step 1: Write the failing tests**

Add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("selectWorkerCores: 3-cluster big.LITTLE returns prime+performance only") {
  // Snapdragon-8-Gen-3-shaped: 1 prime (3.3GHz), 5 performance (3.2GHz),
  // 2 efficiency (2.3GHz). Indices 0..7. Only the 2.3GHz pair is excluded.
  std::vector<long> freqs = {3300000, 3200000, 3200000, 3200000,
                             3200000, 3200000, 2300000, 2300000};
  std::vector<int> got = selectWorkerCores(freqs);
  std::vector<int> want = {0, 1, 2, 3, 4, 5};
  CHECK(got == want);
}

TEST_CASE("selectWorkerCores: 2-cluster split excludes the lower cluster") {
  std::vector<long> freqs = {2800000, 2800000, 2800000, 2800000,
                             1800000, 1800000, 1800000, 1800000};
  std::vector<int> got = selectWorkerCores(freqs);
  std::vector<int> want = {0, 1, 2, 3};
  CHECK(got == want);
}

TEST_CASE("selectWorkerCores: uniform frequencies return empty (no confident split)") {
  std::vector<long> freqs = {2000000, 2000000, 2000000, 2000000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: any unreadable core (-1) invalidates the whole set") {
  std::vector<long> freqs = {3200000, 3200000, -1, 2300000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: single core returns empty") {
  std::vector<long> freqs = {2000000};
  CHECK(selectWorkerCores(freqs).empty());
}

TEST_CASE("selectWorkerCores: empty input returns empty") {
  std::vector<long> freqs;
  CHECK(selectWorkerCores(freqs).empty());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
```
Expected: **compile error** — `selectWorkerCores` is not declared. (This is the RED state for a not-yet-existing symbol.)

- [ ] **Step 3: Declare the function**

In `core/include/rawcam/rawv_codec.h`, immediately after the `decodeFrame(...)` declaration (around line 32) and before the `ParallelFrameEncoder` comment block (line 34), add:

```cpp
// Given each CPU core's max frequency in kHz (one entry per core; -1 for a
// core whose frequency couldn't be read), returns the indices of every core
// NOT in the lowest-frequency cluster -- i.e. the "big + middle" cores worth
// running compute workers on, excluding the slow efficiency cluster. Returns
// an empty vector when topology can't be determined confidently: empty input,
// ANY -1 entry (a partial read must not be half-trusted), or fewer than two
// distinct frequencies (uniform -- no big.LITTLE split visible). Pure; no I/O.
std::vector<int> selectWorkerCores(const std::vector<long>& maxFreqKhzPerCore);
```

- [ ] **Step 4: Implement the function**

In `core/src/rawv_codec.cpp`, ensure `#include <algorithm>` is present near the top (add it if not). Add this in the `rawcam` namespace, next to the other free functions (e.g. just after `riceParamFor`'s closing brace, but OUTSIDE the anonymous namespace so it's externally linkable for the header declaration):

```cpp
std::vector<int> selectWorkerCores(const std::vector<long>& maxFreqKhzPerCore) {
  if (maxFreqKhzPerCore.empty()) return {};
  for (long f : maxFreqKhzPerCore) {
    if (f < 0) return {};  // any unreadable core invalidates the whole set
  }
  std::vector<long> distinct(maxFreqKhzPerCore.begin(), maxFreqKhzPerCore.end());
  std::sort(distinct.begin(), distinct.end());
  distinct.erase(std::unique(distinct.begin(), distinct.end()), distinct.end());
  if (distinct.size() < 2) return {};  // uniform -> no confident split
  long lowest = distinct.front();  // ascending sort -> front is the lowest cluster
  std::vector<int> result;
  for (int i = 0; i < static_cast<int>(maxFreqKhzPerCore.size()); i++) {
    if (maxFreqKhzPerCore[i] != lowest) result.push_back(i);
  }
  return result;
}
```

Note: if `riceParamFor` lives inside the anonymous `namespace {}` block, place `selectWorkerCores` AFTER that block's closing `}  // namespace` so it has external linkage matching the header.

- [ ] **Step 5: Run tests to verify they pass**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
C:\Users\User\rawcam\core\build\test_rawv_codec.exe -tc "selectWorkerCores*"
```
Expected: all 6 `selectWorkerCores` cases PASS.

- [ ] **Step 6: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "feat: selectWorkerCores() -- pure big.LITTLE cluster detection for worker sizing"
```

---

### Task 2: Pure `workerThreadCount()` — margin + regression floor

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h` (add declaration next to `selectWorkerCores`)
- Modify: `core/src/rawv_codec.cpp` (implement next to `selectWorkerCores`)
- Test: `core/tests/test_rawv_codec.cpp`

**Interfaces:**
- Consumes: the size of `selectWorkerCores()`'s result (`clusterCoreCount`), and today's default cap (`defaultCap`, i.e. `min(hardware_concurrency(), 4)`).
- Produces: `uint32_t workerThreadCount(std::size_t clusterCoreCount, uint32_t defaultCap);` — when `clusterCoreCount == 0` returns `defaultCap` (fallback). Otherwise returns `max(max(1, clusterCoreCount - 1), defaultCap)`: cluster-size-minus-1 for the margin, floored at `defaultCap` so this can never produce fewer workers than today.

- [ ] **Step 1: Write the failing tests**

Add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("workerThreadCount: empty cluster falls back to defaultCap") {
  CHECK(workerThreadCount(0, 4) == 4u);
  CHECK(workerThreadCount(0, 1) == 1u);
}

TEST_CASE("workerThreadCount: 6-core cluster gives 5 (size-1 margin) above the floor") {
  // This device: 6 big+mid cores -> 5 workers, one core left free.
  CHECK(workerThreadCount(6, 4) == 5u);
}

TEST_CASE("workerThreadCount: regression floor -- never fewer than defaultCap") {
  // A hypothetical 3-big-core device: size-1 = 2, but floor keeps it at 4.
  CHECK(workerThreadCount(3, 4) == 4u);
  // 2-big-core: size-1 = 1, floor keeps it at 4.
  CHECK(workerThreadCount(2, 4) == 4u);
}

TEST_CASE("workerThreadCount: single big core clamps margin to 1, then floor applies") {
  // clusterCoreCount 1 -> max(1, 0) = 1, floored at defaultCap.
  CHECK(workerThreadCount(1, 4) == 4u);
  CHECK(workerThreadCount(1, 1) == 1u);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
```
Expected: **compile error** — `workerThreadCount` not declared.

- [ ] **Step 3: Declare the function**

In `core/include/rawcam/rawv_codec.h`, immediately after the `selectWorkerCores` declaration, add:

```cpp
// Final worker-thread count from a detected big+mid cluster size and today's
// default cap (min(hardware_concurrency(), 4)). clusterCoreCount == 0 means
// "detection failed" -> returns defaultCap unchanged. Otherwise returns
// max(max(1, clusterCoreCount - 1), defaultCap): one core left free for the
// unpinned writer/Finish/camera threads (the margin), floored at defaultCap so
// topology-aware sizing can only ADD workers vs. today, never remove them.
uint32_t workerThreadCount(std::size_t clusterCoreCount, uint32_t defaultCap);
```

(Ensure `<cstddef>` is available for `std::size_t`; `<cstdint>` is already included. If `<cstddef>` is not transitively present, add it to the header includes.)

- [ ] **Step 4: Implement the function**

In `core/src/rawv_codec.cpp`, next to `selectWorkerCores`:

```cpp
uint32_t workerThreadCount(std::size_t clusterCoreCount, uint32_t defaultCap) {
  if (clusterCoreCount == 0) return defaultCap;  // detection failed -> today's behavior
  uint32_t clusterBased = clusterCoreCount > 1
                              ? static_cast<uint32_t>(clusterCoreCount - 1)
                              : 1u;
  return std::max(clusterBased, defaultCap);
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
C:\Users\User\rawcam\core\build\test_rawv_codec.exe -tc "workerThreadCount*"
```
Expected: all 4 `workerThreadCount` cases PASS.

- [ ] **Step 6: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "feat: workerThreadCount() -- margin + regression floor for worker sizing"
```

---

### Task 3: Wire topology detection + affinity into the constructor (Android-only), host behavior unchanged

**Files:**
- Modify: `core/include/rawcam/rawv_codec.h` (add `workerCores_` member and `applyWorkerAffinity()` private method to `ParallelFrameEncoder`)
- Modify: `core/src/rawv_codec.cpp` (add Android-only `readMaxFreqPerCore()` + `applyWorkerAffinity()`; rewrite the `threadCount==0` branch of the constructor)
- Test: `core/tests/test_rawv_codec.cpp` (default-constructor equivalence test)

**Interfaces:**
- Consumes: `selectWorkerCores()` (Task 1), `workerThreadCount()` (Task 2).
- Produces: no new public interface. `ParallelFrameEncoder`'s default (`threadCount=0`) constructor now: on Android, reads per-core max frequencies, selects the big+mid cluster, sizes the pool via `workerThreadCount`, and pins all workers to that cluster with one shared affinity mask; on every non-Android build, behaves byte-for-byte as today (`min(hardware_concurrency(), 4)`, no affinity call).

- [ ] **Step 1: Write the guard test**

This test proves the default-constructed parallel encoder still produces byte-identical output to the serial `encodeFrame()` — i.e. the sizing/affinity change is timing-only. Add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("default-constructed ParallelFrameEncoder matches serial encodeFrame byte-for-byte") {
  const uint32_t width = 128, height = 96, bitDepth = 12;
  auto src = makeFrame(width, height, bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 3 + y * 5) * 11) % (maxVal + 1));
  });

  std::vector<uint8_t> serial(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t sn = encodeFrame(src.data(), width, height, width, bitDepth,
                            serial.data(), static_cast<uint32_t>(serial.size()));
  REQUIRE(sn > 0);

  // threadCount = 0 -> exercises the new default sizing path (falls back to
  // min(hw,4) on this host since sysfs topology paths don't exist off-device).
  ParallelFrameEncoder enc(width, height, /*threadCount=*/0);
  std::vector<uint8_t> par(static_cast<size_t>(width) * height * 2 + 64);
  uint32_t pn = enc.encode(src.data(), width, width /*rowStrideSamples*/, bitDepth,
                           par.data(), static_cast<uint32_t>(par.size()));
  REQUIRE(pn == sn);
  CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
}
```

- [ ] **Step 2: Run test to establish the pre-change baseline**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
C:\Users\User\rawcam\core\build\test_rawv_codec.exe -tc "default-constructed ParallelFrameEncoder*"
```
Expected: **PASS even before the constructor change** — the current default constructor already produces correct bytes. This test is a *guard* (it must stay green through the constructor rewrite), not a red-first test. Record the pre-change pass, then proceed; re-run it in Step 6 to prove the rewrite didn't alter output.

- [ ] **Step 3: Add the member and method declaration to the header**

In `core/include/rawcam/rawv_codec.h`, inside `class ParallelFrameEncoder`'s `private:` section, next to `void workerLoop(uint32_t bandIndex);` (around line 98), add the method declaration:

```cpp
  // Pins every worker thread to the shared big+mid core mask in workerCores_.
  // No-op when workerCores_ is empty. Best-effort: a failed affinity syscall is
  // logged and ignored (the thread keeps running unpinned). Android-only body.
  void applyWorkerAffinity();
```

And with the other members (near `uint32_t threadCount_;` at line 105), add:

```cpp
  // Big+mid core indices to pin workers to (from selectWorkerCores()); empty
  // means "no confident topology" -> no affinity applied, default sizing used.
  std::vector<int> workerCores_;
```

- [ ] **Step 4: Add the Android-only helpers to the .cpp**

In `core/src/rawv_codec.cpp`, add the Android-only includes near the top, guarded:

```cpp
#ifdef __ANDROID__
#include <sched.h>
#include <pthread.h>
#include <android/log.h>
#include <cstdio>
#endif
```

Then, in the anonymous `namespace {}` block (file-local), add the sysfs reader — Android-only:

```cpp
#ifdef __ANDROID__
// Reads /sys/.../cpuN/cpufreq/cpuinfo_max_freq for each core [0, hw). Returns
// one entry per core in kHz, or -1 for any core whose file is missing/
// unreadable/non-numeric. Empty if hardware_concurrency() reports 0.
std::vector<long> readMaxFreqPerCore() {
  unsigned hw = std::thread::hardware_concurrency();
  if (hw == 0) return {};
  std::vector<long> freqs;
  freqs.reserve(hw);
  for (unsigned i = 0; i < hw; i++) {
    char path[128];
    std::snprintf(path, sizeof(path),
                  "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
    std::FILE* f = std::fopen(path, "r");
    if (!f) { freqs.push_back(-1); continue; }
    long val = -1;
    if (std::fscanf(f, "%ld", &val) != 1) val = -1;
    std::fclose(f);
    freqs.push_back(val);
  }
  return freqs;
}
#endif
```

Then add the member method definition (outside the anonymous namespace, in `rawcam::`), Android-only body:

```cpp
void ParallelFrameEncoder::applyWorkerAffinity() {
#ifdef __ANDROID__
  if (workerCores_.empty()) return;
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int core : workerCores_) CPU_SET(core, &set);
  for (auto& t : workers_) {
    int rc = pthread_setaffinity_np(t.native_handle(), sizeof(set), &set);
    if (rc != 0) {
      __android_log_print(ANDROID_LOG_WARN, "rawv_codec",
                          "pthread_setaffinity_np failed (rc=%d), worker unpinned", rc);
    }
  }
#endif
}
```

(On non-Android builds the body compiles to an empty function — declared, defined, never meaningfully executed.)

- [ ] **Step 5: Rewrite the constructor's default branch and call affinity**

In `core/src/rawv_codec.cpp`, replace the constructor's `else` branch (currently lines ~289-292):

```cpp
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    threadCount_ = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
  }
```

with:

```cpp
  } else {
    unsigned hw = std::thread::hardware_concurrency();
    uint32_t defaultCap = std::max<unsigned>(1, std::min<unsigned>(hw == 0 ? 4u : hw, 4u));
#ifdef __ANDROID__
    workerCores_ = selectWorkerCores(readMaxFreqPerCore());
    threadCount_ = workerThreadCount(workerCores_.size(), defaultCap);
#else
    threadCount_ = defaultCap;  // host/non-Android: byte-for-byte today's behavior
#endif
  }
```

Then, at the very end of the constructor body — AFTER the `for` loop that `emplace_back`s the workers (currently lines ~305-308) — add:

```cpp
  applyWorkerAffinity();  // no-op unless Android + a confident cluster was found
```

- [ ] **Step 6: Build and run the full suite to verify nothing regressed**

Run (PowerShell):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build C:\Users\User\rawcam\core\build
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir C:\Users\User\rawcam\core\build --output-on-failure
```
Expected: **all suites PASS**, including every existing `test_rawv_codec` case (byte-identity/equivalence, merge-boundary, overflow, backpressure) and the new default-constructor equivalence test from Step 1. The host path is unchanged, so any failure here means the rewrite altered host behavior — investigate before continuing.

- [ ] **Step 7: Verify the Android build compiles (native arm64)**

Run (PowerShell) — confirms the `#ifdef __ANDROID__` sysfs/affinity code actually compiles for the real target, which the host build never exercises:

```powershell
cd C:\Users\User\rawcam
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`, including the native arm64 compile of `rawv_codec.cpp` with the Android-only branch active. (No device needed for this step — it's a compile check.)

- [ ] **Step 8: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -m "feat: topology-aware worker sizing + affinity in ParallelFrameEncoder (Android), host unchanged"
```

---

### Task 4: On-device throughput checkpoint

**Files:**
- Modify: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md` (append a "Round 4 stage 3 checkpoint" section)

**Interfaces:**
- Consumes: the release build from Task 3. No code produced.

This is the verification task — not TDD-shaped, but the whole point of the round. It requires the physical device (`24030PN60G`). Follow the exact methodology every prior round used.

- [ ] **Step 1: Build and install the release APK**

```powershell
cd C:\Users\User\rawcam
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```
Expected: `BUILD SUCCESSFUL`, install succeeds. If `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall the existing app first (`adb uninstall com.shez.rawcam`) — note this deletes app-private clips; confirm with the user first if any matter.

- [ ] **Step 2: Confirm "Compress recordings" is ON**

In the app's Settings → RECORDING section, verify the "Compress recordings" toggle is ON. (Per the round-2 false-negative lesson, the live frame counter alone can't confirm which code path ran — Step 4 confirms via the file header.)

- [ ] **Step 3: Record a diagnostic clip at the standard recording class**

Record ~40s at 4096×3072@24fps (the same duration/class as the stage-2 checkpoint, for an apples-to-apples landing-rate comparison). Note the in-app "N frames, M dropped" toast on stop.

- [ ] **Step 4: Confirm the recording actually used the compressed path**

Verify the recorded `.rawv` file's header reports `packMode=3` (CompressedPredictive) — this is the only reliable confirmation compression was genuinely active. Pull the file header or read it via the app's own diagnostics as in prior rounds.

Expected: `packMode=3`.

- [ ] **Step 5: Compute landing rate and compare against the stage-2 baseline**

Landing rate = `written / (written + dropped)`. Compare against stage 2's checkpoint (80.6% landing / 19.4% loss). A genuine measured improvement is success for this round; reaching 0-dropped is not required in a single round (matches the effort's staged pattern).

- [ ] **Step 6: Crash sweep**

```powershell
adb logcat -b crash -d
```
Expected: empty (no `FATAL EXCEPTION` / native crash across the test recordings).

- [ ] **Step 7: Write the checkpoint findings**

Append a "Round 4 stage 3 checkpoint — thread-topology tuning, 2026-08-06" section to `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`: the landing rate, the before/after comparison against stage 2, `packMode=3` confirmation, crash-sweep result, and whether the 0-dropped bar is met. If landing improved but the bar still isn't met, note the remaining staged follow-up (NEON on the per-band predict+residual arithmetic) as the next round. Update the parent spec's status line if warranted.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md docs/superpowers/specs/2026-08-04-compressed-rawv-capture-design.md
git commit -m "docs: round 4 stage 3 on-device checkpoint -- thread-topology tuning results"
```

---

## Self-Review

**Spec coverage** (checked against `2026-08-06-rawv-codec-round4-thread-topology-tuning-design.md`):
- Pure `selectWorkerCores()` + host tests → Task 1. ✓
- Sizing margin (cluster-size-minus-1) + regression floor (`max(clusterBased, defaultCap)`) → Task 2 (`workerThreadCount`). ✓
- Thin Android-only sysfs reader (`cpuinfo_max_freq`, -1 on failure, any -1 invalidates) → Task 3 `readMaxFreqPerCore` + `selectWorkerCores`'s -1 rule (Task 1). ✓
- Shared affinity mask (not 1:1) via `sched`/`pthread` → Task 3 `applyWorkerAffinity` (`pthread_setaffinity_np` with one shared `cpu_set_t`). ✓
- Fallback to `min(hw,4)` with no affinity call on failed/uniform detection → Task 3 constructor `#else` and the empty-`workerCores_` guard in `applyWorkerAffinity`. ✓
- Only the `threadCount=0` path changes; explicit-count call sites unaffected → Task 3 leaves the `if (threadCount > 0)` branch untouched; equivalence test uses `threadCount=0`. ✓
- `pthread_setaffinity_np` failure logged and ignored → Task 3 `applyWorkerAffinity` `rc != 0` branch. ✓
- Byte-identity preserved → Task 3 Step 1 equivalence test + Task 3 Step 6 full suite. ✓
- On-device checkpoint, same methodology, `packMode` confirmation → Task 4. ✓
- Non-goals (no Finish/writer pinning, no NEON, no format change) → respected; no task touches those. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step carries real code. ✓

**Type consistency:** `selectWorkerCores(const std::vector<long>&) -> std::vector<int>` used identically in Task 1 (def) and Task 3 (constructor call, `.size()` feeds `workerThreadCount`). `workerThreadCount(std::size_t, uint32_t) -> uint32_t` consistent Task 2 ↔ Task 3. `workerCores_` (`std::vector<int>`) declared once (Task 3 header), used in constructor and `applyWorkerAffinity`. `applyWorkerAffinity()` void, no args, declared+defined+called consistently. ✓

**Note on the design's "sched_setaffinity on each worker's tid" wording:** implemented as `pthread_setaffinity_np(native_handle, ...)` — the same operation applied from the constructor thread rather than each worker self-applying `sched_setaffinity(0, ...)`. Equivalent effect (a per-thread affinity mask), simpler (single site, no per-worker first-iteration flag), and avoids needing each worker to `gettid()` itself. Called out here so a reviewer doesn't flag the API choice as a spec deviation.
