# Spec A Tasks 7–11 — execution guide for the implementing agent

Written 2026-07-23 after completing Tasks 1–6, for a session that did not live
through them. Read this FIRST, then the matching task in
`2026-07-22-universal-camera-support.md` (the plan holds the full code
listings; this guide holds everything the plan doesn't know).

## Ground truth at handoff

- Branch `spec/universal-camera-support`, 14 commits ahead of `main`, tree clean.
  **Never commit to `main`.** End commit messages with the Claude co-author trailer.
- Tasks 1–6 done and device-verified. JVM suite: **31 tests, 0 failures** across
  6 classes. `assembleDebug` and `assembleRelease` both green.
- The Xiaomi 14 Ultra currently runs this branch's release build.
- `LensDiscovery` gained `applyTopology()` after the plan was written (commit
  `194a333`): only physical children of the primary logical camera are
  taggable; the logical container is excluded when a child survives; dedupe
  prefers taggable children. The plan text predates this — where they seem to
  disagree, the code and its tests win.

## Environment facts (cost an hour each to learn — don't relearn)

1. **adb is not on PATH.** Use
   `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` from PowerShell.
   Device: Xiaomi 14 Ultra, transport id `1d4c0048`. The Pixel 7 Pro is NOT
   connected.
2. **Never trust `BUILD SUCCESSFUL` as proof tests ran.** Parse
   `app\build\test-results\testDebugUnitTest\*.xml` and check
   `tests=/failures=/errors=/skipped=` per class. Stale XML from earlier runs
   persists in that directory — check the class you care about, not the file
   count.
3. **Never edit UTF-8 text files with PowerShell `Get-Content`/`Set-Content`.**
   It BOM-stamps and mojibakes every non-ASCII character. Use the Edit tool or
   `sed -i` via Bash. Diff before committing any bulk edit.
4. **PowerShell 5.1**: no `&&`/`||`, no ternary. Multi-line commit messages: use
   Bash with a `git commit -F -` heredoc, never PowerShell quoting.
5. **GateGuard hooks intercept first-use of Bash/Edit/Write per file and
   "destructive" commands.** When denied: state the facts it asks for in your
   reply text, then retry the identical call. Appending to
   `.superpowers/sdd/progress.md` stays blocked regardless — record session
   notes in `docs/superpowers/open-items-2026-07-22-spec-a.md` instead
   (committed, and the established pattern).
6. **Device handling.** The screen dozes and re-locks on its own; a black
   screencap usually means `mWakefulness=Dozing`, not a broken app — check
   `dumpsys power` before diagnosing. `input keyevent KEYCODE_WAKEUP` wakes it,
   but the keyguard cannot be dismissed over adb — **ask the user to unlock**
   and wait. Screenshots come back 3200×1440 landscape; if the harness displays
   at 2000×900, multiply displayed coordinates by 1.60 before `input tap`.
7. **Expected logcat noise:** camera ids 7/8 log `CameraAccessException ...
   system only device` at Debug level from `Camera2SnapshotSource`. That is the
   handled, expected case — not an error. Enumeration notes log id
   `0: logical container; its physical children are the lenses` — also correct.
8. Gradle: run `.\gradlew.bat` from `C:\Users\User\rawcam`. Full unit run takes
   ~5 s warm; `assembleRelease` ~1 min.

## Xiaomi camera topology (verified on-device)

| id | What it is | In lens list? |
|---|---|---|
| 0 | logical container (children 2, 3) | No — excluded by `applyTopology` |
| 2 | 23 mm main (taggable child) | Yes, main, `standalone=false` |
| 3 | 12 mm ultrawide (taggable child) | Yes, `standalone=false` |
| 4 | 74 mm tele (hidden, probed) | Yes, `standalone=true` |
| 5 | 117 mm periscope (hidden, probed) | Yes, `standalone=true` |
| 6, 9 | extra RAW-capable ids, full metadata | Accepted then **deduped away by focal length** |
| 7, 8 | system-only, refuse characteristics | Never — snapshotOf returns null |

Final UI truth: exactly 4 chips `12mm / 23mm / 74mm / 117mm`, launch on 23mm.
Any deviation after your change is a regression in your change.

## Stale spots in the plan (verified while doing Tasks 5–6)

- Line numbers into `RecordScreen.kt` and `CameraController.kt` are all
  slightly off — **search for symbols** (`data class RecordUiState`,
  `ensureCameraInitialized`, `TickedSlider`), don't trust offsets.
- The plan's `initialize()` snippet for Task 7 Step 6 matches what Task 6
  actually built — `controller.initialize()` returns `DeviceProfile` and is
  called inside `ensureCameraInitialized()`'s coroutine in `RecordScreen.kt`.
  The guard goes immediately after that call; everything below it (rawSpec
  publication, WB restore, auto-meter) assumes a working camera.
- There are no `lens.physicalId` usages anywhere anymore; the field is
  `cameraId` on `LensProfile`.

## Per-task briefing

Recommended order: **8 → 9 → 7 → 10 → 11.** 8 and 9 are pure-Kotlin with
invisible-on-device outcomes (lowest risk, builds confidence in the harness);
7 adds the first new screen; 10 needs sustained device interaction; 11 is
optional breadth. Each task is one commit, TDD (write the test, SEE it fail,
implement, see it pass, then commit). If a RED step unexpectedly passes,
say so in your report — do not silently continue.

### Task 8 — AUTO_ONLY tier in the UI (no device needed to verify)

- The tier logic already exists (Task 3); the plan's Step 2 test should pass
  immediately — that is expected, not a skipped RED.
- Publish `controlTier` + `exposureRangeNs` into `RecordUiState` at every site
  where `lensIndex` changes (search `coerceToMode` and the post-initialize
  publication). Missing one site means a stale tier after a lens switch.
- Disabled-because-absent must be visually distinct from locked. Do not reuse
  the lock icon.
- **On the Xiaomi every lens is FULL, so zero visible change is the passing
  result.** Verify by building, running the suite, and confirming sliders still
  work in a quick on-device sanity tap if the device happens to be unlocked;
  don't block on the device for this task.

### Task 9 — shutter stops ∩ sensor exposure range (no device needed)

- Pure function + one call site. Depends on Task 8's `exposureRangeNs`
  publication — do 8 first.
- The empty-intersection fallback (keep nearest stop) is the load-bearing case;
  its test is the third one in the plan.
- Find the shutter stop list by grepping `RecordScreen.kt` for the shutter
  slider / `SHUTTER` constants; clamp `shutterIndex` where ISO is already
  clamped on lens change.
- On the Xiaomi the visible list must be UNCHANGED (its range comfortably
  contains all stops).

### Task 7 — unsupported-device screen + manifest unblock (device: brief)

- Manifest: `android.hardware.camera.raw` → `required="false"`. One line.
- `CompatibilityReport.render` is fully specified in the plan — copy it.
  One consequence of the topology fix: the Xiaomi report's enumeration log will
  contain `SKIP` lines for id 0 (container) and the deduped ids — that is
  correct output, don't "fix" it.
- The `UnsupportedDeviceScreen` composable reuses the camera-permission gate's
  visual language (centred column, near-black background, bordered accent
  pill). COPY REPORT uses the clipboard, NOT a share intent (Task 10 owns
  sharing).
- On-device check is the permission revoke/grant cycle from the plan. Expect
  the permission gate, no crash. `pm revoke` kills the app process — that is
  normal.
- `PERMISSION_REDACTED` never actually renders today (the
  `ensureCameraInitialized` guard returns before `initialize()` when permission
  is missing) — wire the mapping anyway; it is the honest state if that guard
  ever changes.

### Task 10 — report screen + fixture dump + golden fixtures (device: heavy, user-in-loop)

- Settings rows follow the existing row-widget pattern in `SettingsScreen.kt`
  (see `EnumRow` and friends). Run `capture()` on the existing camera-ops
  dispatcher — it is binder IPC and must stay off the main thread.
- **Fixture capture requires the user.** The dump action shares
  `snapshot-<model>.json` via the share sheet; you cannot complete a share
  sheet over adb reliably. Simpler: after implementing the dump action, pull
  the written file directly with adb from
  `/sdcard/Android/data/com.shez.rawcam/files/`. Copy it to
  `app/src/test/resources/fixtures/xiaomi-14-ultra.json`.
- **The Pixel is not connected.** Do NOT fabricate `pixel-7-pro.json`. Write
  the Pixel golden test to skip cleanly when its fixture is absent (same
  `assumeTrue` pattern as Task 11), and record in the handoff doc that the
  Pixel fixture + un-skipping is owed before merge. This is a deliberate
  deviation from the plan — say so in your report.
- The hand-written `galaxy-s10plus-fv5.json` and the 10 shape fixtures come
  straight from the plan's listings; keep them tiny.
- Golden-test guardrail: if the real Xiaomi fixture yields anything but 4
  lenses labelled 12/23/74/117mm with main at 23mm, **stop — that's a
  discovery regression, fix code not assertions.** (The fixture will contain
  ids 0,2,3,4,5,6,9 and possibly 7/8-adjacent gaps; topology + dedupe must
  collapse it to 4. This is the whole point of the golden test.)

### Task 11 — FV-5 importer (optional breadth; no device)

- Test-source-set only. The suite MUST stay green with the entire
  `app/src/test/resources/fv5/` directory absent (`assumeTrue` skip, then the
  plan's Step 5 removal check — use a scratch dir, not literal `/tmp`, on this
  Windows box).
- The free Galaxy S10+ sample must come from the user or
  camerafv5.com; if you cannot obtain it, implement importer + tests anyway
  (they skip), note the sample as owed, and still run the Step 5 check.
- `rawSizes` stays deliberately empty (field coverage only) — resolving to
  `Unsupported(NO_USABLE_RAW_SIZES)` is a valid outcome.

## Definition of done for this phase

The plan's Final verification block, minus the Pixel items, plus:

1. Suite green (parse the XML), both variants build, ctest 7/7
   (`& "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin\ctest.exe" --test-dir core/build`
   — only needed if you touched `core/` or `app/src/main/cpp/`, which no task
   here does; `git diff` those paths instead).
2. Xiaomi: 4 chips unchanged, launch on 23mm, permission revoke→gate→grant
   cycle clean, compatibility report renders and copies, full-logcat crash
   sweep clean.
3. Plan checkboxes ticked per completed task (use `sed`, verify diff is
   checkbox-only), one commit per task, handoff doc updated with anything the
   next session must know — especially the owed Pixel items:
   **Pixel fixture, Pixel golden un-skip, and the full Pixel regression gate
   (2 lenses, ISO range, record+export) are ALL required before `main` merge.**

## Stop conditions — hand back to the user instead of pushing through

- Any deviation in the Xiaomi 4-chip result.
- `session configuration failed` in logcat after your change (that exact error
  cost this session an hour; its causes are topology-related — reread the
  `applyTopology` KDoc before touching anything).
- The keyguard is up and won't dismiss (user must unlock).
- A GateGuard denial that repeats 3× on the identical call (switch tool, as
  described above, or ask).
- Anything that tempts you to edit `LensDiscovery`'s topology/dedupe logic
  beyond what a task explicitly specifies.
