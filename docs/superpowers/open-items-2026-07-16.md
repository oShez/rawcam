# RawCam — Open Items Handoff (2026-07-16)

Durable record of work in flight so a fresh session can resume without the
originating conversation. Everything here is either committed to git or noted
below. Read this + the two plan/spec files + `.superpowers/sdd/progress.md`
(git-ignored scratch) to pick up.

## 1. Tap-to-Meter Auto — DONE (2026-07-17)

Executed via subagent-driven-development, commits 2d20ff1..eb050d8 on main.
Final review: ready to merge, on-device verified. Details in
`.superpowers/sdd/progress.md`. Original item kept below for reference.

## 1 (original). Tap-to-Meter Auto — READY TO EXECUTE

- Spec: `docs/superpowers/specs/2026-07-16-tap-to-meter-auto-design.md` (commit 0d2ea48)
- Plan: `docs/superpowers/plans/2026-07-16-tap-to-meter-auto.md` (commit 8a4b339), 3 tasks
- Approved by user. Kotlin-only (no native changes) — builds via `./gradlew assembleDebug`.
- To run: execute the plan with superpowers:subagent-driven-development (or
  executing-plans). Base commit for the review range = 8a4b339.
- Device: Pixel 7 Pro over USB (working as of end of session). adb at
  `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`. Screenshots via the Bash
  tool (`exec-out screencap -p > file`), NOT PowerShell (binary redirect
  corrupts PNGs). Use `MSYS_NO_PATHCONV=1` for adb shell paths.

## 2. Security fix — VERIFIED (2026-07-17)

Host ctest ran green: 6/6 passed (test_dng_writer, test_export, test_pack10,
test_rawv_layout, test_rawv_reader with the new hardening cases, test_rawv_writer),
built with SDK CMake 3.22.1 + Ninja + MinGW via PowerShell. The g++ wedge in
item 4 did not reoccur. Original item kept below for reference.

## 2 (original). Security fix — COMMITTED, HOST TESTS UNVERIFIED

- Commit 727d5dd: hardened `core/src/rawv_reader.cpp` `headerSane` against a
  reachable heap overflow in `unpack10` (crafted `.rawv` with `width*height`
  not a multiple of 4), plus a Raw16 frameSizeBytes allocation cap and a
  frameCount clamp. Tests added to `core/tests/test_rawv_reader.cpp`.
- VERIFIED: compiles via NDK clang (`:app:externalNativeBuildDebug`, arm64).
- NOT VERIFIED: the host doctest suite (`ctest` in `core/build`) never ran
  green this session — the host g++ toolchain wedged (see item 4).
- TODO on resume: once g++ works, `cd core/build && ctest --output-on-failure`
  -> expect 6/6, with new `test_rawv_reader` cases (1x1 OOB trigger, non-x4 and
  exact-size rejections, alloc cap, valid-packed10 acceptance, frameCount clamp).

## 3. Kotlin app-layer audit — FIXED (2026-07-17)

All 9 findings fixed via subagent-driven-development, commits a01d3cf (Task A:
CRITICAL async enumeration + LOW uiState fold) and 8070d2a (Task B: remaining
7 findings) on main. Per-task reviews and final whole-range review
(f9663a5..8070d2a): READY TO MERGE, 0 Critical/Important. Details, finding→
commit map, and deferred post-merge tidy items (loading-placeholder error
state + non-RAW-hardware error surfacing, NonCancellable delete hardening,
QUEUED export status, mkdirs off constructor) in `.superpowers/sdd/progress.md`.
Original item kept below for reference.

## 3 (original). Kotlin app-layer audit — 9 FINDINGS, NOT YET FIXED

From a review of the Kotlin/Compose layer (RecordScreen, CameraController,
ClipsScreen, ExportService, MainActivity, NativeBridge, Theme). Ranked:

- **[CRITICAL] Synchronous camera enumeration blocks the main thread at launch.**
  `CameraController.init{}` (via `enumerateLenses`/`buildLensCandidate`,
  CameraController.kt ~339-409) does per-lens `getCameraCharacteristics` (binder
  IPC) + `getOutputSizes`/`getOutputMinFrameDuration` synchronously, because
  `RecordViewModel` constructs `CameraController(application)` as a property
  initializer (RecordScreen.kt:132) created on the main thread during first
  `setContent` composition (MainActivity.kt:25-27). Launch jank / near-ANR on
  multi-lens devices. Fix: enumerate off-main (cameraOps/Dispatchers.Default),
  publish lenses/rawSpec into uiState when ready.
- **[HIGH] Synchronous file delete on the main thread.** ClipsScreen.kt:171 --
  `toDelete.delete()` runs in the Compose click callback (main thread), unlike
  the other file ops here which use `withContext(Dispatchers.IO)` (145-156).
  Fix: wrap delete in `scope.launch { withContext(Dispatchers.IO){ delete() } }`.
- **[MEDIUM] Unbounded 2s polling loops run while backgrounded.**
  ClipsScreen.kt:157-162 and RecordScreen.kt:163-172 (`while(true){delay(2000)...}`
  and the viewModelScope free-space poll) are not lifecycle-gated; keep doing
  StatFs + dir listing + `nativeClipInfo` (a crash-recovery frame scan) every
  2s in the background. Fix: gate with `repeatOnLifecycle(STARTED)`.
- **[MEDIUM] Concurrent multi-clip export shares one cancel flag + notification
  ID.** ExportService.kt:28,102 (`cancelled: AtomicBoolean`, `NOTIFICATION_ID=1001`)
  is a singleton; ClipsScreen only disables Export per-clip, so two concurrent
  exports share state -- Cancel stops all, notification shows only one. Fix:
  serialize exports, or key cancel/notification per clip/startId.
- **[MEDIUM] MainActivity collects the full RecordUiState for two booleans.**
  MainActivity.kt:38-46 -- `collectAsState()` on the whole state recomposes every
  0.5-1s tick during recording, reallocating the `onOpenClips` lambda and
  forcing extra RecordScreen recomposition. Fix: map to a narrow
  `distinctUntilChanged` lock flag.
- **[MEDIUM] Cross-thread camera fields read without @Volatile.**
  CameraController.kt:97-100 (`device`, `session`, `previewSurface`, `rawSurface`,
  `sessionGeneration` ~120) are written on the camera HandlerThread but read from
  caller threads (e.g. startRecording 194-195 on cameraOps); not @Volatile unlike
  `recording`/`manualSet`/`rawSpec`. Relies on incidental happens-before. Fix:
  mark them @Volatile. (Note: the tap-to-meter meterAt also reads these -- worth
  doing this fix alongside its Task 2.)
- **[LOW] Composable reads raw mutable controller props** (`rawSpec`, `lenses`)
  directly (RecordScreen.kt:496-497) -- works via incidental write ordering. Fix:
  fold rawSpec into RecordUiState / a StateFlow.
- **[LOW] JNI stats poll on Dispatchers.Main every 500ms** while recording
  (RecordScreen.kt:341-351). Cheap today (atomic snapshot) but a JNI call on the
  UI dispatcher. Fix: move to Dispatchers.Default.
- **[LOW] Export failures swallowed without logging.** ExportService.kt:52-54
  `catch(e:Exception){ false }`. Fix: `Log.e(TAG,"export failed",e)`.

Categories found clean: camera session/resource teardown ordering, coroutine
cancellation, StateFlow mutation (all via `.update{ copy }`).

## 4. Host C++ toolchain wedge — RESOLVED (2026-07-17): full rebuild + ctest
ran clean via PowerShell with no Defender intervention. Original notes below.

## 4 (original). Host C++ toolchain wedge (environment, not code)

Mid-session the msys2 host g++ began failing to build even a trivial program:
`cc1plus` exits 1 with zero diagnostic (driver `--version` and the cc1plus
binary itself are fine; ~18GB RAM and 275GB disk free). Signature = the compiler
backend's execution being blocked -- almost certainly Windows Defender real-time
protection after several fresh `.exe` writes. NDK clang (Gradle) is unaffected.
Fix before running item 2's ctest: add a Defender exclusion for `C:\msys64`
(or check Protection History for a `cc1plus.exe` block). Does NOT affect
tap-to-meter (Kotlin/Gradle only).

## 5. Build/config minor shortcomings (non-blocking)

- No R8/minification or release-signing config in `app/build.gradle.kts`.
- `AndroidManifest.xml`: `allowBackup` defaults true; no `configChanges`.
- `gradle.properties`: missing `org.gradle.parallel/caching/configuration-cache`
  (build-time only, not runtime).

## Session housekeeping state

- `screen_off_timeout` restored to 60000 (was raised for wireless-adb work).
- No stray untracked files (working tree clean at handoff).
