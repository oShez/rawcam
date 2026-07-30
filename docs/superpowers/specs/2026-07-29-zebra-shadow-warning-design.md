# Zebra Shadow Warning + Highlight Restyle — Design Spec

**Date:** 2026-07-29
**Status:** Implemented 2026-07-30, on-device verification outstanding (no device connected at implementation time)
**Feature:** Extends the shipped zebra exposure warning
(`2026-07-26-zebra-exposure-warning-design.md`) two ways: (1) restyles the
existing highlight-clip stripes from solid white to a fine red/white
candy-stripe, and (2) adds a second, independently-toggleable warning for
crushed shadows, drawn as a fine blue candy-stripe. Both are cine-camera
zebra conventions — this brings RawCam's overlay in line with reference
screenshots from another camera app showing exactly this two-color scheme.

## Goal

The shipped feature only warns about blown highlights. Shadows clipping to
black are exposure information just as useful as blown highlights, and are
invisible today. Give the user both warnings, visually distinguished by
color, at a finer stripe pitch that reads more clearly as "zebra" than the
current 14dp bars.

## Global constraints

- Shadow clip threshold: Y == 0, the minimum value of the 8-bit Y plane —
  mirrors the existing highlight threshold's philosophy (the true floor, not
  "near black"). No user-adjustable threshold, same as highlights.
- Two independent Settings toggles, not one shared toggle: a user may want
  only highlight warnings, only shadow warnings, both, or neither. This is a
  deliberate divergence from a simpler single-toggle design, chosen so
  toggling one never has an unwanted side effect on the other.
- Stripe pitch changes from 14dp to 7dp for both warnings (highlight
  included) — a deliberate restyle, not just an addition. Same animated
  diagonal convention, same 900ms period, unchanged.
- Highlight stripes change from solid white (0.85 alpha) to alternating
  opaque red/white (0.85 alpha each) — a two-tone "candy stripe" rather than
  a stripe-vs-transparent pattern. Shadow stripes are opaque blue (0.85
  alpha) alternating with fully transparent, letting the real (dark) preview
  pixels show through the gaps.
- Same graceful-degradation and default-off philosophy as the shipped
  feature: both toggles default off, no new "unsupported" UI state, and a
  device with no usable analysis stream silently no-ops for both.

## Architecture

No change to the camera-session architecture from the shipped feature: still
one optional YUV analysis stream, added when needed. The only change to
*when* it's needed: previously gated on the single `zebraEnabled` flag, now
gated on `zebraHighlightEnabled || zebraShadowEnabled` — either toggle alone
is enough to justify the stream, since both warnings read the same Y-plane
frame.

The per-frame threshold pass itself gains a second comparison per pixel
(`Y == 0` alongside the existing `Y >= 255`) and a second run-length merge.
This stays a single scan over the luma plane — no second pass, negligible
added cost against the existing sub-millisecond budget.

## Components

- **`ZebraAnalysis.kt`** —
  - `CLIP_THRESHOLD` renamed to `HIGHLIGHT_CLIP_THRESHOLD` (= 255, unchanged
    value); new `SHADOW_CLIP_THRESHOLD` (= 0).
  - `ZebraMask` changes from a single `runs: List<CellRun>` to
    `highlightRuns: List<CellRun>` and `shadowRuns: List<CellRun>`, sharing
    the existing `cols`/`rows` grid. `ZebraMask.EMPTY` becomes
    `ZebraMask(0, 0, emptyList(), emptyList())`.
  - `threshold()` walks two `BooleanArray`s (highlight, shadow) in the same
    pixel loop it already has, then run-length-merges each independently
    using the existing per-row merge logic (unchanged algorithm, applied
    twice).
- **`CameraController`** — `zebraEnabled: Boolean` splits into
  `zebraHighlightEnabled: Boolean` and `zebraShadowEnabled: Boolean`
  (`@Volatile`, mirroring `RecordViewModel`'s settings collector). The
  `withZebra` gate passed into `createSession` becomes `zebraHighlightEnabled
  || zebraShadowEnabled`. No other change to `ensureZebraSurface` /
  `releaseZebra` — the analysis stream itself doesn't know or care which
  warning(s) are active, only whether at least one is.
- **`SettingsRepository`** — `zebraEnabled` splits into
  `zebraHighlightEnabled: Boolean = false` and `zebraShadowEnabled: Boolean =
  false`. `zebraHighlightEnabled` reads the existing `zebraEnabled` DataStore
  key as its migration fallback (`this[KEY_ZEBRA_HIGHLIGHT_ENABLED] ?:
  this[KEY_ZEBRA_ENABLED] ?: fallback.zebraHighlightEnabled`), so a device
  that already has the shipped toggle on keeps it on after this change.
  `zebraShadowEnabled` has no prior key to migrate from. The old
  `KEY_ZEBRA_ENABLED` preference key is left as-is (read-only, for the
  migration above) rather than deleted, matching this codebase's no
  "removed comment" convention — it simply stops being written once the new
  keys exist.
- **`SettingsScreen.kt`** — the single "Zebras" toggle row becomes two rows
  in the same overlays section: "Highlight zebra" / "Stripe blown highlights
  (clipping to white)" and "Shadow zebra" / "Stripe crushed shadows
  (clipping to black)".
- **`RecordScreen.kt`** —
  - The session-recreation `key(...)` around the `AndroidView` gains the
    second flag: `key(state.lensIndex, state.sizeIndex,
    state.settings.zebraHighlightEnabled, state.settings.zebraShadowEnabled)`.
    Flipping either toggle recreates the session, same cost class and same
    mechanism as today's single-flag key — not re-engineered here.
  - The overlay composition site becomes `if
    (state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled)
    ZebraOverlay(viewModel.zebraMask, state.settings.zebraHighlightEnabled,
    state.settings.zebraShadowEnabled, Modifier.fillMaxSize())`.
  - `ZebraOverlay` takes the two enabled flags as new parameters. One shared
    `rememberInfiniteTransition` phase (unchanged cadence) drives both
    brushes' diagonal shift. Two `Brush.linearGradient`s replace the current
    single one, both built with `period = 7.dp.toPx()`:
    - Highlight: `0.0 to Red(0.85α), 0.5 to Red(0.85α), 0.5 to White(0.85α),
      1.0 to White(0.85α)` — opaque candy stripe.
    - Shadow: `0.0 to Blue(0.85α), 0.5 to Blue(0.85α), 0.5 to Transparent,
      1.0 to Transparent` — stripe over otherwise-untouched pixels, same
      shape as today's white-over-transparent brush just recolored.
    Draws `highlightRuns` with the highlight brush only when
    `highlightEnabled`, `shadowRuns` with the shadow brush only when
    `shadowEnabled` — independent draw calls, so either can be on alone.
  - Exact colors: red `#E6392F`, blue `#3385FF` (both matched from the
    reference screenshots during design; adjustable in review without
    affecting anything else in this spec).

## Data flow

Unchanged from the shipped feature except the mask now carries two run
lists instead of one, and the overlay composable reads two settings flags
instead of one to decide what to draw. Camera → YUV `ImageReader` → per-frame
Y-plane double-threshold (pure function) → `ZebraMask(highlightRuns,
shadowRuns)` → published into the same `StateFlow<ZebraMask?>` → Compose
`Canvas` draws whichever run lists their corresponding setting enables.

## Error handling

No new failure modes beyond the shipped feature's: no usable YUV size, an
analysis callback failure, or a session-recreation race all degrade exactly
as documented in the original spec's Error Handling section — none of that
logic changes here, since both warnings share the same stream and the same
failure paths.

## Testing

- Extend `ZebraAnalysisTest.kt`'s existing highlight-threshold cases to also
  assert `shadowRuns`: given a synthetic luma plane, verify the shadow mask
  flags exactly the pixels equal to 0 and none above it, plus the same
  edge cases already covered for highlights (all-crushed, all-white,
  empty/zero-size plane), and that highlight/shadow flagging is independent
  (a plane with both 0 and 255 pixels present produces correct non-empty
  runs in both lists, with cells that are neither correctly excluded from
  both).
- On-device verification required before this is considered done, per this
  project's established convention (same as the original zebra spec): confirm
  shadow stripes track genuinely crushed shadows (point at a deep-shadow
  scene), confirm both warnings can be toggled independently without
  affecting the other, confirm the restyled highlight stripes still track
  blown highlights correctly at the new pitch/color, and confirm no visible
  frame-rate cost with both warnings active simultaneously during an actual
  recording.

## Out of scope (this feature)

- Adjustable thresholds or stripe pitch as user-facing settings (same
  reasoning as the original spec — a natural future addition, not this one).
- A shared/combined toggle — explicitly rejected in favor of two independent
  ones (see Global Constraints).
- GPU/shader implementation (considered and declined in the original spec;
  nothing about adding a second threshold changes that calculus).
- Any change to the RAW capture/export pipeline — still preview-overlay
  only.
