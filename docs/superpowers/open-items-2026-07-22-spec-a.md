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
