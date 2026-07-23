# Universal Camera Support (Spec A) — handoff after Tasks 1–4

Date: 2026-07-22. Branch: `spec/universal-camera-support`. Status: paused cleanly after Task 4.

Plan: `docs/superpowers/plans/2026-07-22-universal-camera-support.md` (Tasks 1–4 checkboxes ticked)
Spec: `docs/superpowers/specs/2026-07-22-universal-camera-support-design.md`

## What shipped

| Commit | Task | What it does |
|---|---|---|
| `d85c21d` | 1 | JVM test infrastructure + `camera/CameraSnapshot.kt` |
| `ae95e3a` | 2 | `camera/DeviceProfile.kt` + `camera/LensDiscovery.kt`, hard requirements |
| `b72a0f3` | 3 | Soft-field defaulting + never-throws fuzz invariant |
| `94554f1` | 4 | Ordering, dedupe, labelling, main-lens selection |
| `6cb739d` | — | Plan checkboxes for Tasks 1–4 |

The whole pure enumeration core is done. `LensDiscovery.discover()` takes plain
`CameraSnapshot` data and returns a sealed `DeviceProfile` that is either
`Supported` or `Unsupported` and never throws. Nothing in it imports `android.*`,
so every branch is reachable from a JVM test.

**Task 1 stood up the project's first Kotlin/JVM test infrastructure** — there was
no `app/src/test/` at all before this. Added the serialization plugin (its version
*must* equal the Kotlin plugin version, `2.1.0`, from the root `build.gradle.kts`),
`kotlinx-serialization-json:1.7.3`, `junit:4.13.2`, and
`testOptions { unitTests.isReturnDefaultValues = true }`. Serialization 1.7.3
against Kotlin 2.1.0 compiled with no version-mismatch warning.

## Verification

- **25 JVM tests, 0 failures**: 2 snapshot + 7 hard-requirement + 8 soft-field + 2 fuzz + 6 ordering.
- The fuzz test drives **8,000 randomised permutations** (absurd values, nulled
  fields, contradictory capabilities) through `discover()` and asserts it never
  throws. This is what actually enforces Spec A's never-crash floor.
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL including the native build.
- `core/` and `app/src/main/cpp/` are **provably untouched** —
  `git diff main..HEAD -- core/ app/src/main/cpp/` is empty — so the existing
  C++ ctest 7/7 result still stands without re-running it.
- Working tree clean. `main` has **none** of this; the branch is 7 commits ahead.

## Decisions worth carrying forward

- **Malformed ranges are treated as absent, never coerced.** A wrong-arity,
  inverted, or non-positive ISO/exposure range falls back to a documented default
  and is recorded in `LensProfile.defaulted`. Guessing a sensitivity range would
  put wrong numbers on a slider the user trusts.
- **`ControlTier.FULL` requires MANUAL_SENSOR *and* a usable ISO range.** A
  missing *exposure* range does **not** demote the tier — it only means the
  shutter stop table cannot be intersected, which is Task 9's job.
- **`LensProfile` overrides `equals`/`hashCode`** to compare identity fields only.
  It carries arrays, and generated data-class equality compares arrays by
  identity, which would break the golden tests in Task 10.

## Known issues, honestly recorded

- **The RED step was skipped on Task 2.** Both production files were written
  before the tests were run, so those 7 tests were never observed failing. They
  pass and their assertions are specific, but that confidence wasn't earned the
  way TDD intends. Tasks 3 and 4 ran RED properly (5/8 and 6/6 failures observed).
- **The plan mispredicted the fuzz test.** It expected a real crash against
  Task 2's code (`rawSizes[0]` on a zero-width size, `IntRange` from a malformed
  `isoRange`). Both fuzz tests passed immediately — Task 2's `buildLens` has no
  unguarded index or range construction. The test still earns its place: it now
  guards Task 3/4's far more aggressive code, which *does* contain `maxBy`/`minBy`
  calls that throw on empty collections.
- **Open design smell, plan-as-written, not introduced here.** In `finishLenses`,
  when a device has focal lengths but **no** logical multi-camera (`physicalIds`
  empty everywhere), `mainIndex` falls to `maxBy { fovMetric }` — the *widest*
  lens becomes the launch default rather than the ~24mm main. Both owned devices
  expose a logical camera so the nearest-focal branch always wins and this never
  fires; it only affects hypothetical single-logical-camera devices.

## Tooling gotcha found this session

Editing a UTF-8 Markdown file via PowerShell `Get-Content` + `Set-Content -Encoding utf8`
**both** prepended a UTF-8 BOM **and** double-encoded every non-ASCII character
(`Get-Content` decoded the UTF-8 bytes as ANSI first). Caught by diffing before
committing, reverted with `git checkout --`, and redone with `sed -i`, which is
byte-safe. Use `sed` or the editor tool for UTF-8 text files here — never a
PowerShell read-modify-write round-trip.

## Next

**Task 5** — `Camera2SnapshotSource`, the Android adapter: the only place
`CameraCharacteristics.get()` is called, plus the widened id probe (`cameraIdList`
∪ every listed camera's `physicalCameraIds` ∪ a bounded numeric scan to 31) under
a ~400 ms wall-clock budget so a slow vendor HAL cannot stall launch.

**Task 6 is the risky one.** It rewires `CameraController` to consume
`DeviceProfile` and must preserve the `activePhysicalId` / `activeCameraId` /
`sessionTagId` three-way split **verbatim** (commit `e74ead8`) or telephoto
support breaks. Per this project's own hard-won lesson, it is not done until
verified on the Xiaomi 14 Ultra — not merely built green. Have the device
connected and unlocked before starting it.

---

## Update 2026-07-23: Tasks 5-6 shipped, device-verified

- `94206dd` Task 5 `Camera2SnapshotSource` (probe 0..31, 400ms budget); `61d9c1d` Task 6 wire-up (~250 lines deleted from CameraController); `194a333` topology fix; `3c07aa9` plan ticks.
- **The Task 6 gate caught a real regression:** the pure layer accepted the logical camera (id 0) as a lens; dedupe swapped it in for its 23mm child; `sessionTagId="0"` (the id being opened) failed session configuration — black preview, "Camera open failed". Fixed pure-side: `applyTopology()` derives `standalone` relative to the primary logical's children and excludes the container when a child survives; dedupe prefers taggable children. 6 new JVM tests (`LensTopologyTest`), suite now 31/31.
- On-device (Xiaomi 14 Ultra, release build, `install -r`): exactly 4 chips 12/23/74/117mm in order, launches on 23mm, ids 6/9 (newly accepted by soft-defaulting) dedupe away by focal, switches 23→74→117→12 clean on one PID, record on 74mm standalone: 183 frames / 7.6s / 24fps / 2.68GB, **Exported · 183 DNGs**, crash buffer clean.
- Pixel 7 Pro regression (the gate's other half) NOT run — device not present. Do it before merging to main.
- Stale note corrected: the preserved telephoto clip `clip_20260721_220030` no longer exists on-device (verified 2026-07-22); do not go looking for it.
- NEXT: Tasks 7-11. **Start with `2026-07-23-spec-a-tasks-7-11-execution-guide.md` in the plans directory** — it carries the environment facts, Xiaomi topology table, stale-plan corrections, per-task briefings, recommended order (8 → 9 → 7 → 10 → 11), and stop conditions. The plan file still holds the code listings.

---

## Update 2026-07-23: Task 7 shipped code-complete; device check owed

- `462bebf` Task 7: `CompatibilityReport.render` (RED confirmed — `Unresolved reference: CompatibilityReport` — then GREEN, 3/3), `UnsupportedDeviceScreen` composable (permission-gate visual language, clipboard COPY REPORT), the `ensureCameraInitialized()` guard right after `controller.initialize()`, and `camera.raw` uses-feature flipped to `required="false"`. Suite 39/39 (36 prior + 3 new), `assembleDebug` green.
- **The Xiaomi 14 Ultra was not connected this session.** The plan's Step 8 on-device check (`pm revoke`/`pm grant` CAMERA cycle, confirming the permission gate and not a crash) was **not run** — its checkbox is deliberately left unticked.
- **OWED before merge, batch with Task 10's device session:** the permission revoke → gate → grant cycle on the Xiaomi (and ideally the Pixel, once connected), confirming (a) normal launch is untouched — straight to viewfinder, no new screen — and (b) revoking CAMERA mid-session shows the existing permission gate, not `UnsupportedDeviceScreen` and not a crash. `PERMISSION_REDACTED` still cannot render on-device today (the permission gate returns before `initialize()` runs when permission is missing) — its mapping is wired but unreachable, exactly as the execution guide predicted.
- **DONE 2026-07-23, same day:** the Xiaomi was reconnected right after the commit above. Release build with Tasks 7–9 installed (`install -r`), then `pm revoke` → app launched to the system permission dialog, **no crash**, crash buffer empty → granted "While using the app" → straight into a live viewfinder on 23mm, lens panel shows exactly 12/23/74/117mm in order. Step 8's checkbox is now ticked. Only the Pixel half of this check remains owed (with the rest of the Pixel gate).

---

## Update 2026-07-23: Task 10 shipped, Xiaomi fixture captured, Pixel fixture owed

- `34ee203` Task 10: Settings gained a DEVICE section — Compatibility report row (scrollable text view of `CompatibilityReport.render`, plain-text SHARE) and Dump characteristics (JSON) row (`Camera2SnapshotSource(cameraManager).capture()` run on `RecordViewModel`'s existing `cameraOps` dispatcher, off the main thread; writes `snapshot-<model>.json` under `getExternalFilesDir(null)`, shared via the app's FileProvider — `file_paths.xml` gained a root-scoped `external-files-path` entry for it). `GoldenFixtureTest` added against 11 fixtures. Suite 45/45 (39 prior + 6 new), 1 skipped (Pixel, expected), `assembleDebug`/`assembleRelease` both green.
- **RED confirmed before the real fixture existed:** `xiaomi-14-ultra.json` was absent when the test was first run — `IllegalStateException: fixture not found: xiaomi-14-ultra.json`, 1 failed / 1 skipped (Pixel) / 4 passed. Went GREEN only after pulling the real dump off-device (see below); the assertions were never adjusted to fit the data.
- **Xiaomi 14 Ultra fixture captured live, on the same device session:** built+installed the release APK, opened Settings → Dump characteristics (JSON) on-device, confirmed the row wrote the file and opened the share sheet with `snapshot-24030PN60G.json` attached, then pulled it directly with `adb pull` from `/sdcard/Android/data/com.shez.rawcam/files/` (no share sheet completed) and committed it as `app/src/test/resources/fixtures/xiaomi-14-ultra.json` (18,203 bytes). `discover()` on this real fixture yields exactly **4 lenses, labels 12mm/23mm/74mm/117mm, main at 23mm, id=3/2/4/5** — the golden-test guardrail held; no changes were made to `LensDiscovery`.
- **DEVIATION (pre-approved):** the Pixel 7 Pro was not connected this session. `pixel-7-pro.json` was **not fabricated**; `GoldenFixtureTest`'s Pixel case uses `assumeTrue` and reports **skipped**, not failed, when the fixture is absent. **OWED before merge to main:** capture `pixel-7-pro.json` (Settings → Dump characteristics on the Pixel, pull the same way) and remove the `assumeTrue` guard so the test runs for real — this is in addition to the full Pixel regression gate already tracked above (2 lenses, ISO range, record+export).
- On-device verification, same session: 4 lens chips confirmed unchanged (12/23/74/117mm, 23mm selected/main) before touching Settings; Compatibility report row opens a scrollable monospace report ("RESULT: SUPPORTED - 4 lens(es)", per-lens control/sizes/ISO/standalone) with a working SHARE action (plain-text ACTION_SEND, share sheet opened and dismissed via back, not completed); Dump characteristics row wrote the file, updated its subtitle to "Saved snapshot-24030PN60G...", and opened the file share sheet (dismissed via back). Full logcat sweep after all of this: no `FATAL`/`AndroidRuntime` crashes, only normal `VM exiting with result code 0` from the relaunches.
- Plan checkboxes for Task 10 (`docs/superpowers/plans/2026-07-22-universal-camera-support.md`, Steps 1–6) ticked in a second commit.

---

## Update 2026-07-23: Task 11 shipped, Galaxy S10+ FV-5 sample owed

- `8948a79` Task 11: `FvFiveImporter` (`app/src/test/java/com/shez/rawcam/camera/FvFiveImporter.kt`) converts Camera FV-5 characteristic-dump JSON (`{sdkLevel: {cameraId: {AOSP-key: value}}}`) into `CameraSnapshot`/`SnapshotSet`, plus `FvFiveImporterTest`. Test-source-set only — `git diff --stat -- app/src/main/` was empty at commit time, confirming zero production-code diff.
- **RED confirmed:** running `FvFiveImporterTest` before the importer existed failed to compile — `Unresolved reference 'FvFiveImporter'` (and four downstream unresolved-reference errors from the same missing symbol) — not a runtime failure, a genuine compile-time RED.
- **GREEN (clean skip):** after implementing the importer, both tests compiled and ran as **skipped**, not failed — `assumeTrue` short-circuits because no FV-5 sample is present (see below). XML: `tests="2" skipped="2" failures="0" errors="0"`.
- **The free Galaxy S10+ FV-5 sample (`samsung_sm-g975f_beyond2.json`, from camerafv5.com) is OWED.** No browser access this session; it was not downloaded, and per the governing rule it was not fabricated either. `app/src/test/resources/fv5/` does not exist on this branch. Once the user downloads the sample and drops it at `app/src/test/resources/fv5/samsung_sm-g975f_beyond2.json`, both `FvFiveImporterTest` cases auto-unskip with no code change needed — that's the whole point of the `assumeTrue` pattern.
- **Step 5 (suite green without FV-5 data) result:** trivially satisfied — the `fv5/` directory was never created in the first place (confirmed via `ls` returning "No such file or directory"), so the full-suite run already exercised the no-data case. No move-away-and-restore dance was needed.
- Full suite after Task 11: **47 tests, 0 failures, 3 skipped** (45 prior + 2 new FvFiveImporterTest; skips = 1 Pixel golden [owed, tracked above] + 2 new FV-5 [owed, this entry]). `assembleDebug` green.
- Plan checkboxes for Task 11 ticked (Steps 2–6); **Step 1's checkbox deliberately left unticked** because its full text includes "save the free sample" — the test-writing half of Step 1 is done, but the sample-save half is the owed item above.
- This was the last task in the Tasks 7–11 execution guide. Remaining before merge to `main`, all previously tracked: Pixel 7 Pro fixture + golden-test un-skip + full Pixel regression gate (2 lenses, ISO range, record+export), and now also the Galaxy S10+ FV-5 sample.

---

## Update 2026-07-23 (later): FV-5 sample fetched, importer rewritten against real data

- Fetched the free sample directly from Camera FV-5's own licensing page (`camerafv5.com/devices/licensing/` links `files/database_samples/samsung_sm-g975f_beyond2.json`) and saved it to `app/src/test/resources/fv5/samsung_sm-g975f_beyond2.json` (2,009,380 bytes).
- **The real shape did not match Task 11's guess.** The importer had assumed flat `{AOSP-key: value}` maps; the actual corpus is `{sdkLevel: {cameraId: {apiNumber, cameraDirection, cameraId, cameraOrientation, capabilities: [{name, value}]}}}` — every AOSP field lives inside a `capabilities` array, and `value` is a typed wrapper: `NamedInteger` (int under `v`), `List` (items, themselves raw or `NamedInteger`), `IntegerRange`/`LongRange` (`min`/`max`), `FloatSize` (`w`/`h`), plus two fields — `android.sensor.blackLevelPattern` and `android.sensor.colorTransform1/2` — that arrive as stringified Java `toString()` output (e.g. `"BlackLevelPattern([0, 0], [0, 0])"`, `"ColorSpaceTransform([805/1024, -163/1024, ...], ...)"`) requiring regex parsing.
- **RED confirmed against real data:** with the sample present but the old importer unchanged, `imports the free sample into snapshots` failed with `NoSuchElementException` (the `.first { it.facing == 1 }` lookup found nothing, since `facing` was never being parsed out of the wrapper). The other test passed trivially (it only checks `discover()` doesn't throw).
- Rewrote `FvFiveImporter.kt` to parse the real wrapper types (see the file's kdoc for the full shape). Also corrected: `physicalCameraIds` **is** populated for this device's logical cameras (`framework20`→`["0","50","52"]`, `framework22`→`["1","51"]`) — the original "never present in this corpus" claim was wrong; fixed both the code and the plan doc's annotation.
- **GREEN:** both tests pass for real now, no more skip. XML: `tests="2" skipped="0" failures="0" errors="0"`. Verified assertions against the actual data: back-camera nodes (`framework0`, `framework2`, `framework20`, `framework52`, `semt0`, `semt2`) all show `whiteLevel=1023`, `cfa=1` (GRBG), `sensorOrientation=90` — matching the test's hardcoded expectations exactly.
- Re-ran **Step 5** for real this time (previously only trivially satisfied because the directory didn't exist): moved `app/src/test/resources/fv5/` aside, ran the full suite — still `BUILD SUCCESSFUL` — then restored it. Confirmed the governing rule still holds with real data in place.
- Full suite: **47 tests, 0 failures, 1 skipped** (Pixel golden only — the sole remaining owed item). `assembleDebug` + `assembleRelease` both green.
- Plan Task 11 Step 1 checkbox now ticked; a dated correction note was added inline after the Step 3 code block explaining the shape mismatch and pointing to the real `FvFiveImporter.kt` for ground truth.
- **Only remaining owed item before merge to `main`: the Pixel 7 Pro** — capture `pixel-7-pro.json` via the Settings → Dump characteristics action, remove its `assumeTrue` guard in `GoldenFixtureTest`, and run the full Pixel regression gate (2 lenses, correct ISO range, record+export, permission cycle). The FV-5 item is now closed.

---

## Update 2026-07-23 (final): Pixel 7 Pro regression gate — CLOSED, branch ready to merge

- Installed the branch's release build in-place on the Pixel 7 Pro (`29171FDH300E2E`), preserving app data. Old install was `versionCode=1`/`0.1`; matches the same release keystore, so it updated in place with no uninstall needed.
- **Compatibility report revealed the Pixel actually has THREE back-facing lenses, not two** — the plan's "2 lenses" line was a pre-data guess. Real topology: logical id 0 (children 2,3,4,5,6) → discovery accepts ids 2/3/4/5/6, dedupes 5 and 6 away by matching focal length against 2 and 4, yielding 13mm ultrawide (id=3), 24mm main (id=2), 117mm telephoto (id=4), all `control: FULL`, all `standalone: false`. Front camera (logical id 1, children 7/8) correctly excluded. This is the topology guardrail (`applyTopology`) working exactly as designed — not touched.
- **All 3 lens crossings verified live via the in-app LENS panel** (13mm ↔ 24mm ↔ 117mm): clean switches, correct per-lens ISO/shutter/focus values shown (e.g. 117mm telephoto correctly shows `f ∞` fixed focus), zero crashes across all transitions.
- **Record + export on a non-main lens (13mm ultrawide):** 651 frames recorded (0 dropped), exported to 651/651 DNGs — survived a screen-lock/unlock interruption mid-export with no data loss, confirming the export pipeline isn't tied to foreground UI state.
- **Permission cycle:** `pm revoke` → relaunch → Android's own runtime dialog appeared (not a RawCam bug) → tapped "Don't allow" → RawCam's own `UnsupportedDeviceScreen`-style "CAMERA ACCESS NEEDED" gate rendered correctly, no crash. One real Android platform quirk hit here, not a RawCam defect: after a single denial the permission flag jumped straight to `USER_FIXED`, meaning the in-app "GRANT ACCESS" button's `requestPermissions()` call can no longer re-surface the system dialog (standard AOSP behavior once a permission is user-fixed, likely primed by this Pixel's long history of RawCam permission toggling across past sessions). Re-granted via `pm grant` (the ADB equivalent of the user completing the flow in system Settings) — relaunch resumed normal live viewfinder immediately, no crash either direction.
- **Fixture captured for real:** `Settings → Dump characteristics (JSON)` written to `snapshot-Pixel_7_Pro.json` on-device (18,672 bytes), pulled and committed as `app/src/test/resources/fixtures/pixel-7-pro.json`. Share sheet opened correctly (dismissed via back, not completed, to avoid touching any real contacts shown in it).
- **`GoldenFixtureTest`'s Pixel case un-skipped and corrected** (commit `d3c8cd8`): removed the `assumeTrue` guard and the stale "two lenses" assertion, replaced with the verified real result (3 lenses, labels `13mm`/`24mm`/`117mm`, main at `24mm`, all `ControlTier.FULL`). This is a genuine ground-truth correction, not an assertion loosened to fit buggy code — the code's behavior matches the live device exactly.
- Full suite after this: **47 tests, 0 skipped, 0 failures** — every test in the project now runs for real, no more owed fixtures. Both `assembleDebug`/`assembleRelease` green; C++ `ctest` 7/7 confirmed still green (`core/` untouched all along).
- Housekeeping: temporarily bumped the Pixel's `screen_off_timeout` to 600000 during verification (it kept locking mid-check), restored to `60000` afterward.
- **Branch `spec/universal-camera-support` has no remaining owed items before merging to `main`.** All 11 Spec A tasks shipped and now fully device-verified on both the Xiaomi 14 Ultra and the Pixel 7 Pro. Merge itself was not requested this session — ask the user before merging.
