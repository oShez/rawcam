# RawCam Settings Page — Design

Date: 2026-07-18. Status: approved direction (persistence = settings + last-used
state; grid + level included; anamorphic dropped; MotionCam Pro / Blackmagic Cam
used as reference points, filtered to what fits this architecture).

## Goal

A Settings screen that (a) parameterizes behavior currently hardcoded across
RecordScreen/CameraController/ExportService/ClipsScreen, (b) persists both the
settings and the user's last-used capture state across launches, and (c) adds two
small viewfinder overlays (rule-of-thirds grid, horizon level). Everything applies
live — no restart, no camera reopen except where a setting changes the active
capture request (those re-push the repeating request the same way existing
setters do).

## Non-goals (out of scope)

Focus peaking, zebras, histogram, waveform, false color, LUT preview — all require
a per-frame analysis stream added to the capture session; risk to the ~376 MB/s
recording hot path. Already on the deferred-features list. Audio, compression,
timecode, SAF storage relocation, anamorphic desqueeze (dropped by user): out.

## Architecture

- **Persistence:** Jetpack DataStore (Preferences flavor). New dependency
  `androidx.datastore:datastore-preferences`. One file (`settings`).
- **`SettingsRepository`** (new, `settings/SettingsRepository.kt`): singleton
  owning the DataStore. Exposes `val settings: Flow<Settings>` (immutable data
  class, one field per setting, hardcoded defaults matching today's behavior
  unless noted) plus `suspend fun update((Settings) -> Settings)`-style setters,
  and a separate `CaptureState` read/write pair for last-used state. All reads
  tolerate missing/corrupt keys by falling back to defaults; DataStore IO
  exceptions are caught and surfaced as defaults (never crash).
- **`SettingsScreen.kt`** (new): scrollable sectioned list styled like the
  existing dark UI (same colors/typography as ClipsScreen). Row widgets: toggle
  rows, enum rows (inline segmented selector, matching the FpsToggle/TickedSlider
  visual language), slider rows reusing `TickedSlider` for numeric ranges. Back
  arrow like ClipsScreen.
- **Navigation:** third entry in MainActivity's `when(screen)` enum
  (`Screen.Settings`). Entry point: a "SETTINGS" text button in the Record
  screen's left gutter under BENCH, disabled while `locked` (recording/busy),
  same pattern as CLIPS.
- **Consumption:** `RecordViewModel` collects `settings` into
  `RecordUiState.settings` (single field holding the data class). Composables
  read `state.settings.*`. Controller-affecting changes (OIS, meter region)
  are passed down as plain parameters/fields on `CameraController` (kept
  `@Volatile`, same pattern as existing manual fields) and take effect on the
  next repeating-request push; the collector triggers `pushManual()` when one of
  those changes.
- Alternatives considered: Proto DataStore (typed schema — ceremony for ~25 keys,
  YAGNI), SharedPreferences (no Flow, legacy). Rejected.

## Settings catalog (keys, types, defaults)

### 1. Capture defaults
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Startup metering | ALWAYS / IF_NO_SAVED / NEVER | IF_NO_SAVED | Replaces the current unconditional startup auto-meter. IF_NO_SAVED = meter only when no last-used state was restored. |
| Default kelvin | KELVIN_STOPS value | 5600 | Used when nothing restored. |
| Default tint | TINT_STOPS value | 0 | |
| Default ISO | 0 = device minimum, else ISO stop | 0 | 0 preserves today's `isoRange.start`. |
| Default shutter | denominator from the active stops list | 48 | Snapped to nearest valid for fps. |
| Default fps | 24/30/48/60 | 24 | Clamped to device max. |
| Default lens | -1 = device main, else lens index | -1 | Validated post-enumeration. |
| Default resolution | size index | 0 (full) | Validated per lens. |

### 2. Remember
| Remember last settings | on/off | ON | When ON, capture state (iso, shutter denom, focus diopters, kelvin, tint, fps, lensIndex, sizeIndex, WB anchor gains + anchor kelvin) is written through on change (debounced ~500 ms on the VM side) and restored at launch. |

Restore flow: the enumeration coroutine in `RecordViewModel.init` reads the
persisted `CaptureState` (DataStore `first()`), runs `controller.initialize()`,
then clamps every restored value against what enumeration reports (iso into
isoRange, lens/size indices into the enumerated lists else defaults, shutter
denom to nearest stop for the restored fps, kelvin/tint to candidate stops,
focus into [0, minFocusDiopters]) and publishes everything in the same single
`_uiState.update` that already publishes rawSpec/lenses. Restored WB anchor is
handed to the controller before the first `pushManual`. Startup metering
IF_NO_SAVED skips the auto-meter when a state was restored.

### 3. Recording
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Free-space reserve | 5–120 s (TickedSlider, step 5) | 35 | Replaces the hardcoded `* 35L` refusal margin in `startRecordingInternal`. |
| Max clip length | OFF / 30 s / 1 m / 5 m / 10 m | OFF | Checked in the existing 500 ms stats poll; on expiry: `stopRecordingInternal()` + toast "Auto-stopped: clip length limit". |
| Thermal auto-stop | on/off | OFF | ON: when thermal status ≥ SEVERE while recording, stop + toast "Recording stopped: thermal". OFF preserves today's warn-only banner. |
| Anti-flicker | OFF / 50 Hz / 60 Hz | OFF | Swaps `ALL_SHUTTER_DENOMS`: OFF = [24,48,60,120,240,500,1000]; 50 Hz = [24,50,100,200,400,500,1000]; 60 Hz = [24,60,120,240,500,1000]. Leading entries are strictly flicker-free multiples of the half-cycle; deeper stops kept for exposure range. On change, current shutterIndex re-snaps to the nearest denom. |
| OIS | AUTO / ON / OFF | AUTO | AUTO = don't set the key (HAL default, today's behavior). ON/OFF sets `LENS_OPTICAL_STABILIZATION_MODE` in `applyManual`; silently ignored if the lens doesn't advertise the mode. |
| Clip name prefix | string, 1–16 chars, [A-Za-z0-9_-] | "clip" | `<prefix>_yyyyMMdd_HHmmss.rawv`. Sanitized on save; empty → "clip". |

### 4. Tap-to-meter
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Tap adjusts | EVERYTHING / EXPOSURE_FOCUS / WB_ONLY | EVERYTHING | EXPOSURE_FOCUS applies iso/shutter/focus, leaves kelvin/tint/wbOverride untouched; WB_ONLY applies kelvin/tint/override only. Applies to the startup meter too. |
| Meter region size | S (5%) / M (10%) / L (20%) | M | Box size as a fraction of the active array (`meteringRectFor` half-width = size/2); M = today's 10% box. |
| Reticle hold | 300 / 600 / 1200 ms | 600 | The delay before the reticle clears. |

### 5. Viewfinder
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Grid | on/off | OFF | Rule-of-thirds lines, Canvas overlay inside the pillarboxed preview box (above SurfaceView, below reticle), non-interactive. |
| Level | on/off | OFF | Horizon indicator from ROTATION_VECTOR sensor: short centered line showing roll, turns green within ±0.5°. Sensor listener registered only while the Record screen is visible AND the setting is on (repeatOnLifecycle-gated like the polls). Landscape-locked app → roll only. |
| Shutter display | FRACTION / ANGLE | FRACTION | ANGLE shows shutter as degrees relative to current fps (`360 * fps / denom`, e.g. 24 fps @ 1/48 = 180°) on the chip and panel labels. Storage stays denominator-based. |
| Show stats sidebar | on/off | ON | Hides the timer/frames/dropped/space column (left gutter stays for buttons). |
| Show BENCH button | on/off | ON | |

### 6. Clips & export
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Confirm before delete | on/off | ON | OFF deletes immediately on Delete tap (same IO path). |
| Delete .rawv after export | on/off | OFF | On successful export completion, ExportService deletes the source .rawv (exported DNG dir remains); Clips list refresh already polls. |
| Auto-export after recording | on/off | OFF | On successful stop (frames > 0), start ExportService for the new clip. Serialized exports already handle overlap. |

### 7. Advanced
| Setting | Type/values | Default | Notes |
|---|---|---|---|
| Diagnostic logging | on/off | OFF | Gates the meterAt-entry and WB-sanity Log.i lines (initialize's one-time sanity line stays unconditional — it's one line per process). |
| Reset all settings | action row w/ confirm dialog | — | `dataStore.edit { clear() }`; also clears saved capture state. |
| About | static rows | — | App version, native lib version (`NativeBridge.nativeVersion()`), device model. |

## Interaction rules

- Settings apply live. OIS / meter-region changes re-push the repeating request
  via the existing `pushManual` path. Anti-flicker re-snaps shutterIndex.
- The Settings screen is unreachable while recording (entry disabled), so no
  setting can change mid-recording. `startRecording`'s captured values are
  therefore stable per clip.
- Last-used-state writes never block the UI: debounced, `Dispatchers.IO` via
  DataStore's own scope.
- Settings screen back → Record screen (same `screen = Screen.Record` pattern).

## Error handling

- DataStore read failure → defaults (log once). Write failure → log, ignore.
- Restored values are always clamped post-enumeration; an impossible restore
  (e.g. lens index from a different device) falls back to that field's default.
- OIS unsupported on the active lens → key omitted (checked against
  `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`), no user-facing error.
- Prefix sanitization at save time keeps filenames filesystem-safe.

## Testing / verification

No unit-test infra (established). Verification = `./gradlew assembleDebug` plus
an on-device matrix exercising each setting: restore-after-relaunch, startup
meter in all three modes, reserve refusal boundary, max-length auto-stop,
thermal path (code-trace only unless it naturally triggers), anti-flicker stop
lists, OIS toggle (log the request key), meter scope × 3, region size visual,
grid/level overlays, shutter-angle display, sidebar/BENCH hiding, delete
without confirm, delete-after-export, auto-export, reset-all. Implementer
verifies compile + trace; orchestrator runs the device matrix.

## Implementation sketch (for the plan)

1. DataStore dep + `Settings`/`CaptureState` data classes + `SettingsRepository`.
2. `SettingsScreen` UI + navigation + entry button.
3. Record-side plumbing: settings collector into uiState, defaults, remember/
   restore flow, startup-meter modes.
4. Recording behaviors: reserve, max length, thermal stop, anti-flicker, OIS,
   prefix.
5. Meter scope / region / reticle hold.
6. Viewfinder: grid, level, shutter-angle display, sidebar/BENCH visibility.
7. Clips & export behaviors + Advanced section.
