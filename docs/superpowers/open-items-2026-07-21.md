# RawCam — Open Items Handoff (2026-07-21)

Durable record of work in flight so a fresh session can resume without the
originating conversation. Read this + `.superpowers/sdd/progress.md`
(git-ignored scratch, sections "EXPORT PERFORMANCE, SESSION 2026-07-21" and
"TELEPHOTO LENS INVESTIGATION, SAME SESSION") to pick up.

## 1. Export performance — DONE (2026-07-21)

Root-caused to AGP defaulting the native build to unoptimized `Debug` for a
Gradle debug variant, plus a redundant memcpy in `dng_writer.cpp`. Fixed via
`-DCMAKE_BUILD_TYPE=RelWithDebInfo` (`app/build.gradle.kts`), a stride-skip
fast path in `writeDng()`, and raising the export worker-pool cap from 6 to 8
(`core/src/exporter.cpp`). Verified on-device: a 61-frame 4096x3072 clip
dropped from a 9510ms baseline to 1368ms (~7x), byte-identical output.
Committed as part of `dfdf6b1`. User declined further (NEON/SIMD) work after
being shown the pipeline is now likely storage-bandwidth-bound (~2.1GB/s,
near this device's UFS ceiling) rather than CPU-bound.

## 2. Telephoto lens support (Xiaomi 14 Ultra 3.2x/5x) — ROOT-CAUSED, NOT IMPLEMENTED

**User ask:** "the zoom lenses dont work, can you add the lenses and add the
respective focal lengths to all lens." Deferred by the user to a fresh
session before implementation started.

**Confirmed root cause:** the existing lens pipeline (`CameraController.kt`:
`enumerateLenses`, `buildLensCandidate`, `LensInfo`) is already correct and
complete for whatever the OS exposes — this is not a bug in that code.
MIUI/HyperOS hides camera ids `"4"` (3.2x telephoto, focal 12.28mm) and `"5"`
(5x periscope, focal 19.4mm) from both `cameraManager.cameraIdList` and the
main logical camera `"0"`'s `physicalCameraIds` (which only reports `[2,3]` =
ultrawide+main). Despite being hidden from enumeration, both ids are still
independently queryable (`getCameraCharacteristics("4")`/`("5")` succeed,
report RAW+MANUAL_SENSOR capability) and **openable**
(`cameraManager.openCamera("4", ...)` was tested and succeeded as a
standalone top-level `CameraDevice`). Ids `"7"`/`"8"` are a different,
genuinely hard-blocked case ("system only device") — not relevant here.
Confirming user detail: stock MIUI camera *can* shoot RAW on these lenses (at
a binned 12.5MP, not the sensor's full 50MP), so this is a software-visibility
restriction, not a hardware/RAW-capability gap.

**Why it's not a small patch:** `CameraController` opens one logical
`CameraDevice` (id `"0"`) for the app's whole lifetime; lens switching today
only retags `OutputConfiguration.setPhysicalCameraId()` within that one open
session — valid only for ids `"2"`/`"3"` (true physical children of `"0"`).
Ids `"4"`/`"5"` are not children of `"0"`, so using them requires closing the
current device and opening a wholly separate top-level `CameraDevice`, then
reversing that when the user switches back to a lens that *is* a child of
`"0"`.

**Implementation checklist for the next session:**
- Extend lens discovery beyond `physicalCameraIds` to also surface ids
  reachable via direct `getCameraCharacteristics()` probing (avoid
  hardcoding literal Xiaomi id strings if a general probe is feasible).
- Add the standalone-device open/close path to `CameraController`, mirrored
  against the existing physical-id-tagging path for ids `"2"`/`"3"`.
- Audit every piece of state keyed off "the active lens" for the standalone
  path: `anchorState`/`wbOverride` (per-physical-id keyed as of `796a712`,
  but keyed by an id that would no longer be a child id), tap-to-meter's
  `activeArraySize` region mapping, `RawSpec`/`specFor`, recording-session
  setup.
- Verify a full capture+recording session (not just open/close) actually
  works on ids `"4"`/`"5"` — only open/close has been tested so far; stream
  configuration, RAW output sizes/formats, and real frame capture are
  unverified.
- Disclose as a stability caveat: this is an OS-hidden, unofficial access
  path Xiaomi could restrict further in a future MIUI/HyperOS update.

Device (Xiaomi 14 Ultra) was left in a clean, non-diagnostic state — all
temporary diagnostic logging used during the investigation was fully
reverted, `assembleDebug` rebuilt and reinstalled.
