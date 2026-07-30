# Zebra Shadow Warning + Highlight Restyle — open items after final review

Date: 2026-07-30 (code), 2026-07-31 (device verification). Plan:
`docs/superpowers/plans/2026-07-29-zebra-shadow-warning.md`. Spec:
`docs/superpowers/specs/2026-07-29-zebra-shadow-warning-design.md`.

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
rebuild when one flag toggled while the other was already on). Fixed and
verified in `f8476fa`/`257bc91`: `key()` now keys on
`state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled`,
matching `CameraController`'s own OR-based session gate exactly.
`:app:assembleDebug :app:testDebugUnitTest` is BUILD SUCCESSFUL, 69/69 tests
passing. The whole-branch review is clean after this fix.

## On-device verification — 2026-07-31 (Xiaomi device, model `24030PN60G`, Android 16)

Ran against the installed build after resolving a debug-signature conflict
(old install uninstalled, fresh install succeeded). Results:

- [x] **Toggle persistence, both directions.** Highlight ON / Shadow OFF,
  force-stopped, relaunched — both values held. Repeated with Highlight
  OFF / Shadow ON — also held independently. Confirmed via screenshots.
- [x] **Restyled highlight stripes.** With only "Highlight zebra" on, pointed
  at a lamp: a fine red/white candy-stripe appeared, confined exactly to the
  blown-out ceiling/wall area, visibly tighter-pitched than the old 14dp
  bars. Screenshot-confirmed, zoomed.
- [x] **New shadow stripes.** With only "Shadow zebra" on, pointing at an
  ordinarily "dark" room produced no stripes at all — the sensor's noise
  floor apparently never hits the literal `Y == 0` threshold from ambient
  darkness alone (unlike highlight clipping, which is a hard sensor
  ceiling). Confirmed instead by fully covering the lens with a fingertip:
  a fine blue stripe pattern appeared, precisely tracing the fingertip's
  irregular shape, dark background visible through the gaps. This also
  serves as the orientation/mapping check below. **Design note, not a
  defect:** true `Y == 0` may be rare-to-unreachable in normal shooting
  conditions; this matches the spec's explicit choice of the strict floor,
  but is worth knowing going in — the highlight and shadow thresholds are
  not symmetric in how easily real scenes reach them.
- [x] **Orientation/mapping check.** The fingertip-shaped blue-stripe blob
  appeared in the correct location/shape (not flipped, not offset) —
  confirms the shadow run list's grid mapping is correct, not just the
  already-shipped highlight side's.
- [x] **Both together, no interference.** Enabled both toggles simultaneously
  and pointed at scenes that separately triggered each color — each
  rendered correctly in the other's presence, no visual or toggle-state
  interference.
- [~] **Simultaneous highlight+shadow overlap in one frame.** Attempted (lamp
  + partial finger-cover on one lens corner) but could not force a single
  analysis cell to register both `Y == 255` and `Y == 0` at once in this
  session — auto-exposure and partial occlusion don't reach the strict
  floor the same way full lens occlusion does. **Still an open question,
  not ruled out**: if a real scene does trigger both in one cell, the two
  in-phase, differently-opaque brushes (opaque red/white vs. opaque
  blue/transparent) may blend into an unintended color rather than reading
  as "both." Revisit if this is ever seen in real use — not blocking, since
  the spec doesn't dictate overlap behavior.
- [x] **Toggle stability.** Across this session, both flags were flipped
  independently and together roughly 10+ times (including a mid-verification
  screen-lock/camera-error recovery) — the preview recovered every time
  after relaunch, no persistent black frames or stuck "Camera open failed"
  state tied to the zebra toggles themselves.
- [x] **Recording not degraded.** Recorded ~111 seconds with both toggles ON
  at the device's active resolution/fps. Completion toast: **2667 frames,
  0 dropped.**
- [x] **Crash buffer check.** `adb logcat -b crash -d` — empty. No `FATAL
  EXCEPTION` from `com.shez.rawcam` anywhere in the buffer.

### Unrelated observation (not a zebra-feature defect)

Mid-session, the device's screen locked from inactivity while screenshots
were being taken (adb-driven screenshots don't count as user activity, so
the normal screen timeout elapsed). This tore down the camera session with a
real error cascade (`BufferQueue has been abandoned`, `CameraDevice-JV-0`
close). This is existing app/OS lifecycle behavior — any recording in
progress across a screen lock would hit the same thing regardless of the
zebra feature — not something introduced by this plan. Not filed as a task
item here since it's out of scope for this feature; worth a look someday if
background/lock-screen recording continuity ever becomes a goal.

### Not verified this session

- [ ] **Old single-toggle upgrade migration path.** The plan's Task 5 called
  for installing the pre-branch APK (shipping the single "Zebras" toggle),
  turning it on, then upgrading to this build in place and confirming
  "Highlight zebra" comes up ON / "Shadow zebra" comes up OFF. No pre-branch
  APK was available in this session to test the actual upgrade — only the
  new build's own toggle persistence was verified (see above). The
  migration code itself (`SettingsRepository`'s
  `this[KEY_ZEBRA_HIGHLIGHT_ENABLED] ?: this[KEY_ZEBRA_ENABLED] ?: fallback`
  fallback chain) was verified logically correct in code review, but this
  specific real-upgrade path has never been exercised on a device. If a
  build predating this plan is ever available again, this is still worth
  running once.

## Conclusion

The feature is implemented, code-reviewed clean, and device-verified for
every case that could be exercised in this session. The two items above
(overlap-color rendering, old-APK upgrade path) are edge cases that could
not be forced or tested with what was available, not known failures — this
is not a "done, no open items" close like the original zebra exposure
warning feature, but a "shipped, two narrow follow-ups identified" one.
