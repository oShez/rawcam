# Manual white balance & slider UX overhaul — design

Date: 2026-07-16
Status: approved by user ("go on looks good")

## Goal

Add fully manual white balance (Kelvin + tint), matching how ISO/shutter/focus
are already forced manual rather than offering an auto fallback. While in
there, fix the concrete slider complaints raised for the existing manual
controls: no scale reference, hard to land on exact values.

## Device facts (Pixel 7 Pro, confirmed via the existing dumpsys capture)

All three back lenses (main, ultrawide, tele) report `MANUAL_POST_PROCESSING`
alongside `MANUAL_SENSOR` and `RAW` — the capability that makes
`COLOR_CORRECTION_MODE = TRANSFORM_MATRIX` with manual
`COLOR_CORRECTION_GAINS` / `COLOR_CORRECTION_TRANSFORM` settable. Manual WB is
therefore supported on every lens this app already exposes, with no fallback
path needed.

Color correction gains/transform apply only to processed (non-RAW) output
streams and to capture metadata — never to the `RAW_SENSOR` buffer. This
means: the live preview will visibly respond to the chosen WB (it's a
processed stream), the per-frame metadata capture already wired into
`captureCallback` (`wbR`/`wbG`/`wbB`, currently reading back AWB's auto gains)
will pick up the manual gains with no changes, and the actual RAW pixel data
recorded to `.rawv`/DNG is completely untouched — WB stays a fully
re-gradable, non-destructive metadata tag, same as any real raw workflow.

## CameraController changes

- No new capture surfaces, no session changes, no native core changes.
- `applyManual` (called for both preview and record repeating requests) gains:
  `set(CONTROL_AWB_MODE, CONTROL_AWB_MODE_OFF)`,
  `set(COLOR_CORRECTION_MODE, COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)`,
  `set(COLOR_CORRECTION_GAINS, gainsFor(kelvin, tint))`,
  `set(COLOR_CORRECTION_TRANSFORM, IDENTITY_3X3)` (a rational identity matrix
  — gains alone do the work; no cross-channel color matrix warp).
- `gainsFor(kelvin, tint)`: converts Kelvin to an approximate RGB white point
  via a standard blackbody CCT→RGB curve fit (the well-known Tanner Helland
  approximation: piecewise log/power formulas valid 1000K–40000K, public
  domain, no native dependency), inverts to per-channel gains normalized so
  the green channel gain is 1 and red/blue stay ≥1 (avoids sub-unity gains
  clipping downstream). Tint then biases the green gain by a small
  multiplicative factor proportional to the tint value (positive tint =
  more magenta = green gain reduced; negative = more green = green gain
  raised), tuned so the full ±50 range spans a visually sane green/magenta
  shift without clipping.
- Default state: 5600K, tint 0 — occupies the same "default before user
  touches anything" slot AWB auto used to.
- `applyManual` replaces today's hardcoded
  `set(CONTROL_AWB_MODE, CONTROL_AWB_MODE_AUTO)` in both
  `setRepeatingPreview` and `setRepeatingRecord`; WB becomes as fully manual
  as ISO/shutter/focus already are.

## ViewModel / state

- `RecordUiState` gains `kelvin: Int = 5600`, `tint: Int = 0`.
- `setKelvin(k: Int)` / `setTint(t: Int)` — same shape as `setIso`/`setFocus`:
  update state, call `pushManual()`. No recording-lock needed beyond what
  `pushManual` already implies (WB, like ISO/shutter/focus, is adjustable
  live during recording — only lens/resolution/fps are locked).

## Slider component redesign

New shared composable, `TickedSlider`, replacing the bare `Slider` calls in
the expanded parameter panel:

- Renders min/max endpoint labels beside the track (the concrete "no scale
  reference" fix).
- For discrete parameters, uses Compose's `steps` mechanism (which draws tick
  dots for free) so dragging snaps to meaningful stops — the concrete "hard
  to land on exact values" fix.
- For continuous parameters that still want visual major-stop markers
  without forcing snapping to them, draws a custom tick overlay independent
  of drag resolution.

Applied per parameter:

- **ISO**: was a continuous log-scale slider; becomes discrete, snapping to
  standard full-stop values (50/100/200/400/800/1600/3200/6400/12800/25600/
  51200/102400) filtered to the lens's actual `isoRange`, with the range's
  true min/max spliced in as endpoints if not already present (same
  filter-and-splice pattern the fps/shutter lists already use).
- **FOCUS**: was raw diopters; becomes discrete stops at friendly distances
  (∞, 10m, 5m, 3m, 2m, 1m, 0.5m, macro), converted to diopters
  (`1/meters`, ∞ → 0), filtered and clamped to the lens's actual
  `minFocusDiopters` (the macro-end stop becomes whatever the lens truly
  supports rather than an arbitrary fixed number). The chip label switches
  from `ƒ 0.2D` to a distance string (e.g. `ƒ 5m`, `ƒ ∞`).
- **SHUTTER**: already discrete via `ALL_SHUTTER_DENOMS`; gains the same
  endpoint-label/tick visual treatment, no behavioral change.
- **KELVIN**: discrete stops at common references (2000/2700/3200/4000/5000/
  5600/6500/7500/9000/10000K) — a fixed list, not lens-dependent.
- **TINT**: continuous, integer resolution across −50..50, with major tick
  labels only at multiples of 10.

## UI placement

- New WB chip (`5600K`, or similar) added to the bottom parameter chip row,
  which currently reads `LENS · RES · ISO · SHUTTER · FOCUS` — six chips no
  longer fit the fixed 400dp row. The row becomes a horizontally scrollable
  list (visually identical when everything fits; doesn't break as later
  monitoring-tool chips get added). Expanding the WB chip shows two stacked
  `TickedSlider`s (Kelvin, then Tint) in the same expanding panel used for
  every other parameter today.
- WB chip is never disabled by the recording lock (matches ISO/shutter/focus,
  which stay live-adjustable during recording) — only LENS/RES stay locked.

## Invariants preserved

- No native core, container format, or exporter changes.
- RAW pixel data recorded to `.rawv` is unaffected by WB — only capture
  metadata (and the live preview) change. DNG output stays fully re-gradable.
- Lens/resolution/fps recording-lock behavior is unchanged; WB joins
  ISO/shutter/focus as always-adjustable.

## Testing

- Host: unaffected (no core changes), nothing to run.
- On-device: sweep Kelvin from cold to warm and confirm the live preview
  visibly shifts blue→orange; sweep tint and confirm a green↔magenta shift.
  Record a short clip at a non-default Kelvin/tint, export, and confirm the
  DNG's white-balance-related metadata reflects the chosen values (not
  daylight defaults) while the image still opens and debayers correctly.
  Verify ISO/FOCUS/KELVIN sliders snap to their discrete stops and show tick
  marks; verify the chip row scrolls with six chips present.
