# Round 5 — Optimized serial Rice packer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the compressed-`.rawv` encoder's dominant Golomb-Rice pack cost (~84% of encode CPU) with a faster **serial** bit-packer — a q=0 fast path plus a per-row adaptive capacity check — as a pure bit-exact, lossless change, to bring the 4096×3072@24fps compute stage under the 41.6 ms/frame budget.

**Architecture:** Two bit-exact tiers in `core/src/rawv_codec.cpp`. Tier 1 adds a q=0 fast path to the shared `BitWriter::writeRice` (one `(k+1)`-bit append instead of two), keeping all safety checks — scalar `encodeFrame` and `ParallelFrameEncoder` change identically, so the scalar oracle stays valid and output is byte-unchanged. Tier 2 hoists the per-append capacity check in `ParallelFrameEncoder::computeAndPackBand` to a once-per-row adaptive check against a proven worst-case row-byte bound, letting the hot loop use an unchecked (but bit-identical) fast `put`.

**Tech Stack:** C++17, doctest, CMake + Ninja. Host toolchain: MSYS2 mingw64 g++ (run via PowerShell — g++ silently fails under the Git Bash tool). Android: Gradle NDK arm64 build.

**Spec:** `docs/superpowers/specs/2026-08-17-rawv-codec-round5-serial-rice-packer-design.md`
**Context:** `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`

## Global Constraints

- **Format frozen / bit-exact.** `ParallelFrameEncoder::encode` and scalar `encodeFrame` output must be **byte-for-byte identical to the current implementation** and to each other; `encode → decode == input` losslessly. Any single-byte diff is a failure.
- **`decodeFrame`, `riceParamFor`, k-selection, and pass-1 sampling are NOT modified.**
- **Lossless only.** No LSB truncation (a separate future lever).
- **Bit-exact-change TDD adaptation.** Because both tiers produce byte-identical output by design, an equality/round-trip test cannot go RED for the change itself. Per this repo's round-4 precedent, such tests are *pinned green before the edit* (they lock the oracle equivalence) and must *stay green after*. Genuine RED cycles are used only where new observable logic exists (Tier 2's `worstCaseRiceRowBytes` helper).
- **Host build:** configure `cmake -S core -B core/build -G Ninja`; build `cmake --build core/build`; test `ctest --test-dir core/build --output-on-failure`. Run all of these via **PowerShell**, invoking the SDK's CMake/ctest by full path (`$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe`) — they are not on PATH.
- **`file(GLOB)` gotcha:** `core/CMakeLists.txt` globs `tests/*.cpp`. This plan adds NO new test files (tests go into the existing `core/tests/test_rawv_codec.cpp`), so no re-configure is needed for new files — but re-run the build after every edit.
- **PowerShell caveats (this repo):** do NOT rewrite existing files via Get-Content+Set-Content (adds a BOM / mojibakes non-ASCII — use Edit). Avoid double quotes inside `git commit -m` here-strings on PS 5.1 — use single quotes or `git commit -F -`.
- **Commit trailer:** end every commit message with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Device (Task 3 only):** `24030PN60G` (Xiaomi 14 Ultra), 4096×3072@24fps, compression ON; verify `packMode@20 == 3` and 14-bit `whiteLevel == 16383` before trusting any measurement; measure cool + unplugged; HyperOS drops the app's own logcat tags → any instrumentation must log to a file via raw `open(O_CREAT|O_TRUNC|O_WRONLY, 0644)` (unqualified `open`, not `::open`, under Bionic `_FORTIFY_SOURCE=2`).

## Reference — current code (read before starting)

- `core/src/rawv_codec.cpp:36-130` — `BitWriter` (writeBits, writeRice, finishedBytes, totalBits, appendAlignedBytes). `writeRice` is at **lines 64-75**.
- `core/src/rawv_codec.cpp:445-479` — `ParallelFrameEncoder::computeAndPackBand` (the per-row fill + pack loop; pack loop at **468-470**, epilogue capturing `bits`/`finishedBytes()`/`jobOverflowed_` at **472-478**).
- `core/tests/test_rawv_codec.cpp:13` — `makeFrame(width, height, bitDepth, gen)` where `gen` is `uint16_t(*)(uint32_t x, uint32_t y, uint16_t maxVal)`.
- `core/tests/test_rawv_codec.cpp:222` — existing "SIMD path byte-identical … full matrix" test (cases × gens × threadCounts). **Guards both tiers for free.**
- `core/tests/test_rawv_codec.cpp:268,292` — existing overflow + reuse/overflow-recover tests. **Tier 2 must keep these green.**
- Signatures: `encodeFrame(raw16, width, height, rowStride, bitDepth, out, outCapacity)`; `ParallelFrameEncoder enc(width, height, threadCount); enc.encode(raw16, rowStrideSamples, bitDepth, out, outCapacity)`; `decodeFrame(compressed, compressedSize, out16, width, height, rowStride, bitDepth)`.

---

## Task 1: Tier 1 — q=0 fast path in `BitWriter::writeRice` (host, bit-exact)

Collapse the 97.7%-common q=0 codeword from two appends (1-bit terminator + k-bit remainder) to one `(k+1)`-bit append, in the shared `BitWriter`. Safety checks retained; output byte-unchanged.

**Files:**
- Modify: `core/src/rawv_codec.cpp:64-75` (`BitWriter::writeRice`)
- Test: `core/tests/test_rawv_codec.cpp` (add one focused stress test)

**Interfaces:**
- Consumes: existing `BitWriter::writeBits(uint32_t bits, uint32_t nbits)`.
- Produces: no signature change. `writeRice(uint32_t value, uint32_t k)` behavior byte-identical; internally one append for q=0.

- [ ] **Step 1: Add a fast-path stress test (pins equivalence before the edit)**

Add to `core/tests/test_rawv_codec.cpp` (near the other `ParallelFrameEncoder` tests). It stresses the q=0 fast path (mostly-flat content) mixed with q≥32 spikes, across bit depths and thread counts, and asserts byte-identity to the scalar oracle + lossless round-trip:

```cpp
TEST_CASE("Rice q=0 fast path: encoder stays byte-identical to encodeFrame and round-trips (fast-path stress)") {
  struct Case { uint32_t width, height, bitDepth; };
  const Case cases[] = {
    {1, 8, 14}, {2, 8, 14}, {3, 8, 12}, {8, 8, 16}, {9, 9, 14},
    {33, 40, 12}, {64, 64, 16}, {100, 130, 14}, {257, 64, 10},
  };
  const uint32_t threadCounts[] = {1, 2, 5};
  // Mostly-flat frame (drives q=0 for nearly every pixel) with sparse large
  // spikes every 101th pixel (forces q>=32 and the multi-chunk drain), so a
  // single frame exercises both the fast path and the slow path.
  auto gen = [](uint32_t x, uint32_t y, uint16_t maxVal) -> uint16_t {
    uint32_t idx = x * 131u + y * 17u;
    if (idx % 101u == 0) return maxVal;                 // spike
    return static_cast<uint16_t>(maxVal / 2 + ((idx) % 3u));  // near-flat
  };
  for (const auto& c : cases) {
    auto src = makeFrame(c.width, c.height, c.bitDepth, gen);
    std::vector<uint8_t> serial(static_cast<size_t>(c.width) * c.height * 2 + 64);
    uint32_t sn = encodeFrame(src.data(), c.width, c.height, c.width, c.bitDepth,
                              serial.data(), static_cast<uint32_t>(serial.size()));
    REQUIRE(sn > 0);
    for (uint32_t tc : threadCounts) {
      ParallelFrameEncoder enc(c.width, c.height, tc);
      std::vector<uint8_t> par(static_cast<size_t>(c.width) * c.height * 2 + 64);
      uint32_t pn = enc.encode(src.data(), c.width, c.bitDepth, par.data(),
                               static_cast<uint32_t>(par.size()));
      REQUIRE(pn == sn);
      CHECK(std::equal(serial.begin(), serial.begin() + sn, par.begin()));
      std::vector<uint16_t> dec(src.size());
      REQUIRE(decodeFrame(par.data(), pn, dec.data(), c.width, c.height, c.width, c.bitDepth));
      CHECK(dec == src);
    }
  }
}
```

- [ ] **Step 2: Build and run the new test — expect GREEN on current code**

Run (PowerShell):
```
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build core/build
core/build/test_rawv_codec.exe -tc="*fast-path stress*"
```
Expected: PASS. This pins the oracle equivalence BEFORE the edit (bit-exact change ⇒ no RED; see Global Constraints). If it fails now, fix the test, not the encoder.

- [ ] **Step 3: Apply the q=0 fast path**

Replace `BitWriter::writeRice` (`core/src/rawv_codec.cpp:64-75`) body with:

```cpp
  bool writeRice(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    // Fast path (the common, well-predicted case): q == 0 means value < 2^k, so
    // the whole codeword is the (k+1)-bit field whose top bit is the unary
    // terminator 0 followed by the k remainder bits -- i.e. `value` itself as a
    // (k+1)-bit field. One checked append instead of two. k <= 20 (riceParamFor)
    // so k+1 <= 21 <= 32, valid for writeBits. Bit-identical to the slow path.
    if (q == 0) return writeBits(value, k + 1);
    while (q >= 32) { if (!writeBits(0xFFFFFFFFu, 32)) return false; q -= 32; }
    // q one-bits followed by a terminating zero bit, as one (q+1)-bit field.
    uint32_t qval = (((1u << q) - 1u) << 1);
    if (!writeBits(qval, q + 1)) return false;
    if (k > 0 && !writeBits(value, k)) return false;
    return true;
  }
```

(Note: the old `q == 0 ? 0u : …` branch for `qval` is gone because q≥1 is guaranteed past the fast-path return.)

- [ ] **Step 4: Rebuild and re-run the focused test — expect GREEN (bit-exact preserved)**

Run:
```
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build core/build
core/build/test_rawv_codec.exe -tc="*fast-path stress*"
```
Expected: PASS — byte-identical output preserved through the change.

- [ ] **Step 5: Full suite green**

Run: `& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir core/build --output-on-failure`
Expected: all 9 tests PASS (the full matrix, overflow, reuse, round-trips — every `encodeFrame`-equality test now exercises the fast path).

- [ ] **Step 6: Commit**

```bash
git add core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -F - <<'MSG'
feat: Rice q=0 fast path in BitWriter::writeRice (bit-exact, round 5 tier 1)

Collapse the common q==0 codeword from two writeBits appends to one (k+1)-bit
append. Byte-identical to the scalar encodeFrame oracle; ~1.44x on pack (host
spike). Safety/capacity checks retained.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
MSG
```

---

## Task 2: Tier 2 — per-row adaptive capacity check in `computeAndPackBand` (host, bit-exact)

Hoist the per-append capacity check to once per row so the hot loop uses an unchecked, bit-identical fast `put`, while overflow detection (`jobOverflowed_`) stays exactly as today. Adds a testable worst-case-row-bytes helper (genuine RED cycle).

**Files:**
- Modify: `core/src/rawv_codec.cpp` (add `worstCaseRiceRowBytes` free fn near `riceParamFor` ~line 293; add `BitWriter::remainingBytes`, `putUnchecked`, `writeRiceUnchecked`; rewrite `computeAndPackBand` pack loop at 468-470)
- Modify: `core/include/rawcam/rawv_codec.h` (declare `worstCaseRiceRowBytes`)
- Test: `core/tests/test_rawv_codec.cpp` (unit test for the helper + a mixed fast/fallback overflow assertion)

**Interfaces:**
- Consumes: `BitWriter::writeRice` (Task 1), `jobBitDepth_`, `jobK_`, `width_`, `zScratch_`.
- Produces:
  ```cpp
  // Upper bound (bytes) on one packed row for a frame of `bitDepth` at Rice
  // param `k`. zigzag(residual) < 2^(bitDepth+1) => q < 2^(bitDepth+1-k) => each
  // codeword <= (q+1+k) bits. Conservative (may over-estimate q by up to 1).
  // Returns UINT64_MAX when the bound would overflow (tiny k) -> caller always
  // uses the checked path. Exposed for host tests.
  uint64_t worstCaseRiceRowBytes(uint32_t width, uint32_t bitDepth, uint32_t k);
  ```
  plus `BitWriter::remainingBytes() const`, `BitWriter::putUnchecked(uint32_t,uint32_t)`, `BitWriter::writeRiceUnchecked(uint32_t,uint32_t)` (unchecked twins, bit-identical output).

- [ ] **Step 1: Write the failing unit test for `worstCaseRiceRowBytes`**

First declare it in the header so the test sees it — add to `core/include/rawcam/rawv_codec.h` after the `decodeFrame` declaration:

```cpp
uint64_t worstCaseRiceRowBytes(uint32_t width, uint32_t bitDepth, uint32_t k);
```

Then add to `core/tests/test_rawv_codec.cpp`:

```cpp
TEST_CASE("worstCaseRiceRowBytes is a conservative upper bound on a packed row") {
  using rawcam::worstCaseRiceRowBytes;
  // k=9, 14-bit: qExp = 15-9 = 6 -> maxQ=64, codeword <= 74 bits/sample.
  CHECK(worstCaseRiceRowBytes(8, 14, 9) == (8ull * (64 + 1 + 9) + 7) / 8);      // 74
  CHECK(worstCaseRiceRowBytes(4096, 14, 9) == (4096ull * 74 + 7) / 8);          // 37888
  // k >= bitDepth+1 -> qExp clamps to 0 -> maxQ=1 -> codeword <= (k+2) bits.
  CHECK(worstCaseRiceRowBytes(100, 14, 20) == (100ull * (1 + 1 + 20) + 7) / 8); // 275
  // Smaller k => strictly larger (or equal) bound (monotonic).
  CHECK(worstCaseRiceRowBytes(64, 12, 3) >= worstCaseRiceRowBytes(64, 12, 9));
  // Tiny k on a wide frame -> saturates to UINT64_MAX (forces checked path).
  CHECK(worstCaseRiceRowBytes(4096, 16, 0) == UINT64_MAX);
}
```

- [ ] **Step 2: Run the test — expect FAIL (undefined)**

Run: `& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build core/build`
Expected: LINK error / `undefined reference to worstCaseRiceRowBytes` (genuine RED).

- [ ] **Step 3: Implement `worstCaseRiceRowBytes`**

Add at `namespace rawcam` scope near `riceParamFor` (`core/src/rawv_codec.cpp` ~line 293, outside the anonymous namespace so the header decl links):

```cpp
uint64_t worstCaseRiceRowBytes(uint32_t width, uint32_t bitDepth, uint32_t k) {
  if (width == 0) return 0;
  uint32_t qExp = (bitDepth + 1u > k) ? (bitDepth + 1u - k) : 0u;
  if (qExp >= 40) return UINT64_MAX;  // bound too large to be useful -> checked path
  uint64_t maxQ = (uint64_t)1 << qExp;          // conservative upper bound on q
  uint64_t maxCodewordBits = maxQ + 1u + k;     // q ones + 0 terminator + k remainder
  if (maxCodewordBits > (UINT64_MAX - 7) / width) return UINT64_MAX;
  return ((uint64_t)width * maxCodewordBits + 7) / 8;
}
```

- [ ] **Step 4: Run the unit test — expect PASS**

Run:
```
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build core/build
core/build/test_rawv_codec.exe -tc="*worstCaseRiceRowBytes*"
```
Expected: PASS.

- [ ] **Step 5: Add the unchecked `BitWriter` twins**

In `BitWriter` (`core/src/rawv_codec.cpp`, in the public section alongside `writeBits`/`writeRice`), add — bit-identical to the checked versions minus the capacity guard:

```cpp
  // Bytes of output capacity not yet consumed by fully-flushed bytes. Ignores
  // the <8 pending accumulator bits (conservative under-count -> safe for the
  // once-per-row headroom check in computeAndPackBand).
  uint64_t remainingBytes() const { return capacity_ - bytePos_; }

  // Unchecked twin of writeBits: identical bit output, no capacity guard. Only
  // safe when the caller has proven headroom (see computeAndPackBand's per-row
  // worstCaseRiceRowBytes check).
  void putUnchecked(uint32_t bits, uint32_t nbits) {
    if (nbits == 0) return;
    acc_ = (acc_ << nbits) | static_cast<uint64_t>(bits & maskFor(nbits));
    accBits_ += nbits;
    while (accBits_ >= 8) { accBits_ -= 8; buf_[bytePos_++] = static_cast<uint8_t>(acc_ >> accBits_); }
  }

  // Unchecked twin of writeRice: bit-identical to writeRice (same q=0 fast path).
  void writeRiceUnchecked(uint32_t value, uint32_t k) {
    uint32_t q = value >> k;
    if (q == 0) { putUnchecked(value, k + 1); return; }
    while (q >= 32) { putUnchecked(0xFFFFFFFFu, 32); q -= 32; }
    putUnchecked((((1u << q) - 1u) << 1), q + 1);
    if (k > 0) putUnchecked(value, k);
  }
```

- [ ] **Step 6: Rewrite the pack loop in `computeAndPackBand`**

Compute the bound once before the row loop (add right after the `uint32_t* z = zScratch_[bandIndex].data();` line at ~450):

```cpp
  // Per-row headroom bound: if the band buffer has at least this many bytes free,
  // the whole row provably fits, so pack it with the unchecked fast path. +1 byte
  // margin covers the <8 bits the accumulator may carry over from the prior row.
  const uint64_t worstRowBytes = worstCaseRiceRowBytes(width_, jobBitDepth_, jobK_);
```

Then replace the per-sample pack step (was `core/src/rawv_codec.cpp:467-470`) with:

```cpp
    // Pack the row -- bit-exact either way. Fast path when the row provably fits
    // (no per-append capacity branch); checked writeRice near the buffer end (or
    // for tiny-k frames where the bound saturates), which sets jobOverflowed_.
    if (worstRowBytes != UINT64_MAX && bw.remainingBytes() >= worstRowBytes + 1) {
      for (uint32_t x = 0; x < width_; x++) bw.writeRiceUnchecked(z[x], jobK_);
    } else {
      for (uint32_t x = 0; x < width_; x++) {
        if (!bw.writeRice(z[x], jobK_)) { ok = false; break; }
      }
    }
```

(The surrounding `for (y …)` loop, the `ok` flag, and the `bits`/`finishedBytes()`/`jobOverflowed_` epilogue at 472-478 stay exactly as-is.)

- [ ] **Step 7: Add a mixed fast/fallback overflow test (pins overflow semantics)**

Add to `core/tests/test_rawv_codec.cpp` — a frame whose early rows fast-path but whose total exceeds a deliberately tight buffer, asserting the encoder still reports whole-frame failure (returns 0), identical to pre-change behavior:

```cpp
TEST_CASE("Tier2: encoder still fails whole-frame on overflow when early rows took the fast path") {
  const uint32_t width = 96, height = 96, bitDepth = 16;
  // Incompressible-ish content so real output far exceeds the tight buffer below.
  auto src = makeFrame(width, height, bitDepth, [](uint32_t x, uint32_t y, uint16_t maxVal) {
    return static_cast<uint16_t>(((x * 2654435761u) ^ (y * 40503u)) & maxVal);
  });
  ParallelFrameEncoder enc(width, height, /*threadCount=*/4);
  std::vector<uint8_t> tiny(width * height / 4);  // far too small -> must overflow
  uint32_t n = enc.encode(src.data(), width, bitDepth, tiny.data(),
                          static_cast<uint32_t>(tiny.size()));
  CHECK(n == 0);  // whole-frame failure, no partial/corrupt result
}
```

- [ ] **Step 8: Build + full suite green**

Run:
```
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\cmake.exe" --build core/build
& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir core/build --output-on-failure
```
Expected: ALL PASS — the full matrix, both overflow tests (268 + the new one), reuse/recover, round-trips, the helper unit test, and the Task-1 fast-path stress test.

- [ ] **Step 9: Commit**

```bash
git add core/include/rawcam/rawv_codec.h core/src/rawv_codec.cpp core/tests/test_rawv_codec.cpp
git commit -F - <<'MSG'
feat: per-row adaptive capacity check in computeAndPackBand (bit-exact, round 5 tier 2)

Hoist the per-append capacity check to once per row, gated on a proven
worst-case row-byte bound (worstCaseRiceRowBytes), so the hot loop uses an
unchecked bit-identical fast put. Overflow detection (jobOverflowed_) unchanged.
~1.64x on pack together with tier 1 (host spike). Lossless, byte-identical.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
MSG
```

---

## Task 3: arm64 build + on-device round-trip + landing-rate measurement (device-gated)

Confirm bit-exactness on real hardware and measure the actual dropped-frame landing rate — the real acceptance bar. **Device-gated:** run when the `24030PN60G` is connected + unlocked; otherwise the host tasks stand alone and this is deferred (as prior rounds did).

**Files:**
- Update: `docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md`
- Update: `C:\Users\User\.claude\projects\C--Users-User\memory\rawcam-project.md` (+ `MEMORY.md` line)
- (Optional, throwaway) re-apply `.superpowers/sdd/2026-08-13-rawv-codec-round4-neon-predict-simd/predict-vs-pack-instrumentation.patch` to re-measure `pack_ms`; revert + save, never commit.

- [ ] **Step 1: Build arm64 release**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleRelease --console=plain
```
Confirm `BUILD SUCCESSFUL` and `lib/arm64-v8a/librawcam_jni.so` present.

- [ ] **Step 2: On-device lossless round-trip**

`adb install -r` the release APK. Settings → "Compress recordings" ON. Record a short clip. Verify `packMode@20 == 3`, `whiteLevel == 16383`. Pull the clip; decode (host `decodeFrame` on the pulled `.rawv`, or the in-app export path) and confirm it decodes cleanly and matches expected content — the on-hardware confirmation of the host bit-exact result.

- [ ] **Step 3: Measure the landing rate (the real bar) on the clean build**

On a **cool, unplugged** device, record ~35 s at 4096×3072@24fps and read the app's own frames-written / frames-dropped counter. State plainly whether **0-dropped** is met. Optionally re-apply the round-4 phase-profiling patch to confirm the `pack_ms` drop matches the ~1.4–1.6× host prediction (file-based logging; revert after).

- [ ] **Step 4: Record results and keep `main` clean**

Write the before/after (pack_ms drop, landing rate, whether 0-dropped is met) into the open-items doc as a dated section. If any instrumentation was applied, `git checkout core/src/rawv_codec.cpp` and save the diff as a `.patch` in the round-5 SDD workspace — never commit it. If 0-dropped still misses, document the residual gap and hand off to the next lever (12-bit truncation); do not declare success.

- [ ] **Step 5: Update memory + commit docs**

Update `rawcam-project.md` (round-5 result) and the `MEMORY.md` pointer line (memory files are outside the repo — write them with the Write tool, not git). Commit the doc update:

```bash
git add docs/superpowers/open-items-2026-08-04-compressed-rawv-capture.md
git commit -m 'docs: round 5 on-device result -- pack_ms drop, landing rate'
```

---

## Self-Review

**Spec coverage:**
- §1 scope (faster serial pack, bit-exact) → Tasks 1–2. ✓
- §2 measure-first evidence → carried as the rationale; on-device re-measure in Task 3. ✓
- §3 Tier 1 (q=0 fast path, shared BitWriter) → Task 1. ✓
- §3 Tier 2 (per-row adaptive check, worst-case bound) → Task 2 (helper + unchecked twins + loop rewrite). ✓
- §3 rejected (bulk-drain, SIMD) → not implemented, by design. ✓
- §4 bit-exactness (format frozen, decodeFrame/riceParamFor untouched) → Global Constraints + Task 1/2 tests. ✓
- §5 edge cases (k=0, k=20, width<2, q≥32, overflow, threadCounts) → Task 1 stress cases + Task 2 helper test + overflow test. ✓
- §6 test matrix → Task 1 Step 1 (content×dims×threadCounts) + existing full matrix + Task 2 helper/overflow tests. ✓
- §7 on-device protocol → Task 3. ✓
- §8 acceptance (bit-exact host gate; measured speedup; 0-dropped reported-not-gated) → Task 1/2 (host gate) + Task 3 (measure/report). ✓
- §9 sequencing (Tier1 → Tier2 → device → whole-branch review) → Tasks 1→2→3; review via subagent-driven-development. ✓

**Placeholder scan:** none — every code step has concrete code; device build uses the exact command prior rounds used.

**Type consistency:** `worstCaseRiceRowBytes(uint32_t,uint32_t,uint32_t)->uint64_t` identical in header decl (Task 2 Step 1), impl (Step 3), and test/callsite. `remainingBytes()->uint64_t`, `putUnchecked`/`writeRiceUnchecked` consistent between Step 5 defs and Step 6 call site. `writeRice` signature unchanged across Task 1. `UINT64_MAX` sentinel handled identically in helper (Step 3) and call site (Step 6).
