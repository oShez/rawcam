# RawCam UI/UX polish — design

Date: 2026-07-15
Status: approved by user (visual mockup: claude.ai artifact "RawCam UI proposal")

## Goal

Turn the v1 developer UI into a usable camera app. Presentation layer only: no changes
to RecordViewModel's recording flow, CameraController, ExportService logic, or the
native core. The app stays landscape-locked, single-activity, two screens.

## Theme (new `ui/Theme.kt`)

`RawCamTheme`: Material3 `darkColorScheme` applied at the `setContent` root.

- background `#0A0B0D`, surface `#17191D`, surfaceVariant `#24272C`
- onBackground/onSurface `#E9EAEC`, outline `#3A3E45`, muted text `#8F959D`
- primary/accent `#E5484D` (record red), error `#E5484D`
- success green `#6FBF73` used ad hoc (dropped=0, exported status)
- Numeric readouts (timer, ISO, counts, sizes) use `FontFamily.Monospace` with
  tabular feel; labels stay default sans.

Applies to every dialog, snackbar, and button — kills the default purple Material look.

## Navigation (MainActivity)

- TabRow removed. A `screen` state (`Record` | `Clips`) replaces `tab`.
- Record screen shows a "Clips" icon button (top-right); disabled while
  `recording || busy` — same lock rule as the old tab (leaving Record disposes the
  SurfaceView, which would stall a live recording).
- Clips screen shows a back arrow (top-left) returning to Record; system back
  (`BackHandler`) does the same. Camera reopen on return continues to work via the
  existing `surfaceCreated` path.

## Record screen

Layout: full-bleed black `Box`; SurfaceView centered with `aspectRatio(4f/3f)`
(sensor 4080×3072) and `fillMaxHeight` — true letterbox, no stretching. The side
gutters host the rails.

- **Left status rail** (vertically centered): timer `MM:SS` with a pulsing red dot
  while recording (infinite alpha animation); frames written; dropped (green `0`,
  red when >0); space remaining as `~N min` — new `freeSpaceBytes` in
  `RecordUiState`, refreshed by a 2 s poll in the viewmodel (idle and recording),
  converted to minutes at the current fps/frame size.
- **Right action rail**: custom shutter button — 76 dp circle, 4 dp light border;
  inner red circle when idle, inner rounded red square while recording; dimmed when
  `!previewReady || busy`. Below it a two-segment FPS toggle (24 | 30), disabled
  while recording.
- **Bottom parameter chips** (centered over the preview bottom): three pill chips —
  `ISO 388`, `1/48`, `ƒ ∞` / `ƒ 1.2D`. Tapping a chip expands a single slider panel
  above the chip row for that parameter (existing log-ISO / shutter-stop / diopter
  slider logic reused verbatim); tapping the active chip collapses it. Only one
  slider visible at a time; default collapsed. Expanded state is plain
  `remember` UI state (an enum `ISO|SHUTTER|FOCUS|null`).
- **Corners**: top-left small outlined icon button labeled `BENCH` (replaces the
  floating "B"; same behavior and recording lock). Top-right the `CLIPS` button
  (see navigation).
- Thermal warning banner unchanged (top, red).

## Clips screen

- `loadClips` runs on `Dispatchers.IO` (it lists files and reads headers every 2 s
  tick — known jank source; review-backlog item pulled into scope).
- Header row: back arrow, "Clips" title, right-aligned free space (`StatFs` on the
  clips dir, e.g. `118.4 GB free`).
- Each clip is a `Card`: title is the recording time parsed from the filename
  (`clip_yyyyMMdd_HHmmss.rawv` → `Jul 13 · 14:51`; unparseable names fall back to
  the raw filename); metadata line `4080×3072 · 24 fps · 330 frames · 13.8 s ·
  4.81 GB` (duration = frameCount/fps, new); status line below.
- Export status: while running, `Exporting… n / total` plus a
  `LinearProgressIndicator` (progress = exportedFrameCount/frameCount, both already
  refreshed by the 2 s poll; clamp exportedFrameCount ≥ 0). Finished: green
  `Exported · n DNGs` (with `(failed)`/`(cancelled)` suffixes as today). Never
  exported: muted `Not exported`.
- Actions: filled `Export` (or `Cancel` while running) + text `Delete` (disabled
  while running). Delete confirmation dialog unchanged.
- Empty state: centered muted "No clips yet — record something."

## Error handling / invariants preserved

- Recording lock invariants: Clips navigation, FPS, and BENCH all disabled while
  `recording || busy`; shutter disabled while `busy || !previewReady`.
- Snackbar events (free-space refusal, writer error, frame summary) unchanged.
- No new permissions, no manifest changes.

## Testing

Host ctest untouched (UI only). Verification: assembleDebug builds; install on the
Pixel; manual smoke — preview aspect correct, chips expand/collapse, record a short
take (timer/dot/stats live, space remaining ticks), navigate to Clips and back
(camera reopens), export shows progress bar, delete confirm works.
