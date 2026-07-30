# Zebra Shadow Warning + Highlight Restyle — open items after final review

Date: 2026-07-30. Plan: `docs/superpowers/plans/2026-07-29-zebra-shadow-warning.md`.
Spec: `docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md`.

## What's done

All 4 code tasks from the plan are committed and individually reviewed clean:

| Commit | Task | What it does |
|---|---|---|
| `075502e` | 1 | Two-threshold luminance analysis (`ZebraAnalysis.threshold` returns independent `highlightRuns`/`shadowRuns`) |
| `d6eec42` | 2 | Independent `zebraHighlightEnabled`/`zebraShadowEnabled` Settings toggles, migrating from the old single `zebraEnabled` key |
| `cf77733` | 3 | `CameraController` gates the analysis stream on either flag (OR) |
| `c05f649` | 4 | `RecordScreen` wiring, session-recreation key, and the red/white + blue candy-stripe `ZebraOverlay` |

The final whole-branch review found one Important-severity issue (the
session-recreation `key()` in `RecordScreen.kt` was keying on both zebra
flags individually instead of their OR, over-triggering a full camera-session
rebuild when one flag toggled while the other was already on) plus this
documentation gap. The code fix is applied and verified: `key()` now keys on
`state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled`,
matching `CameraController`'s own OR-based session gate exactly.
`:app:assembleDebug :app:testDebugUnitTest` is BUILD SUCCESSFUL, 69/69 tests
passing. The whole-branch review is clean after this fix.

## What's NOT yet done: on-device verification

**No Android device was connected at implementation time**, so Task 5 of the
plan (on-device verification) could not run. Every item below is owed before
this feature is considered done, per this project's standing convention that
a visual feature is not complete off a green build alone. From the plan's
Task 5:

- [ ] **Toggle persistence, including the upgrade path.** Turn "Highlight
  zebra" on and "Shadow zebra" off, force-stop, relaunch, reopen Settings —
  confirm both values survive independently. Repeat with the opposite
  combination.
- [ ] **Restyled highlight stripes.** With only "Highlight zebra" on, point at
  a blown highlight — confirm a fine red/white candy-stripe (not solid
  white), visibly tighter-pitched than the old 14dp bars, gone once exposure
  drops. Screenshot and zoom in to confirm — a green build proves nothing
  here.
- [ ] **New shadow stripes.** With only "Shadow zebra" on, point at a crushed
  shadow — confirm a fine blue stripe with the dark image visible through the
  gaps, gone once the shadow lifts. Screenshot and zoom in.
- [ ] **Both together.** Both toggles on, a scene with both a blown highlight
  and a crushed shadow — confirm each stripe stays confined to its own
  region and both animate at the same apparent speed.
- [ ] **Orientation/corner check.** Fill only one corner of the frame with a
  crushed shadow — confirm blue stripes appear in that same corner (the
  shadow run list shares the highlight side's already-verified grid/pixel
  mapping, but hasn't itself been confirmed on real hardware).
- [ ] **Toggle stability.** With the viewfinder up, toggle both flags off and
  on several times, independently and together — confirm the preview
  recovers every time, no black frames left behind, no "Camera open failed"
  toast.
- [ ] **Recording not degraded.** Record at least 30 seconds with both
  toggles ON at full resolution/fps — confirm 0 dropped frames, compared
  against a same-length clip with both OFF.
- [ ] **Crash buffer check.** `adb logcat -b crash -d` — confirm no `FATAL
  EXCEPTION` from `com.shez.rawcam`.

## Additional case flagged by the final review (not in the original Task 5 list)

- [ ] **A cell that clips both highlight and shadow in the same frame.** The
  overlay's analysis grid is coarse (~60x45 px per cell at typical preview
  resolutions) — a bright window against a black interior can put both a 255
  pixel and a 0 pixel in the *same* cell, flagging it in both `highlightRuns`
  and `shadowRuns` simultaneously. The two brushes are drawn in-phase (same
  shared diagonal/period), and the highlight brush is opaque red/white while
  the shadow brush is opaque blue over transparent — stacking them in the
  same cell may render as an unintended blue-purple blend rather than
  anything that reads as "this cell has both problems." Worth a specific
  look on-device once a scene that triggers it is found (a bright window
  against a dark interior is the easiest way to force it). This is **not
  necessarily a defect** — the spec doesn't dictate overlap behavior, and no
  task in the plan called it out — but it should be looked at deliberately
  rather than discovered by accident.

## Migration path — specific verification owed

- [ ] **Old single-toggle upgrade.** Install the pre-branch APK (the one
  shipping the single "Zebras" toggle), turn it on, then install this
  branch's build over it (no uninstall). Open Settings and confirm
  "Highlight zebra" comes up **ON** and "Shadow zebra" comes up **OFF**. This
  exercises `SettingsRepository`'s DataStore migration fallback
  (`zebraHighlightEnabled` reads `KEY_ZEBRA_HIGHLIGHT_ENABLED` first, falling
  back to the old `KEY_ZEBRA_ENABLED`) — there is no automated test for this
  path since it needs a live `Context`, so this on-device check is the only
  verification it will ever get.

## Next

Once a device is connected, run through Task 5's steps above in order,
capture screenshots for the two visual checks, and update the spec's status
line (`docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md:4`)
to `Implemented and device-verified <date>` once everything passes — or
append a new dated update section to this file recording exactly what was
found, same pattern as this project's other open-items docs.
