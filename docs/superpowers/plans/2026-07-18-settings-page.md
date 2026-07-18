# Settings Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A DataStore-backed Settings screen (7 sections, ~25 settings) that parameterizes currently-hardcoded behavior, persists last-used capture state across launches, and adds grid + horizon-level viewfinder overlays.

**Architecture:** `SettingsRepository` (Preferences DataStore) exposes one `Flow<Settings>` + a `CaptureState` read/write pair. `RecordViewModel` collects settings into `RecordUiState.settings` and restores capture state during its existing enumeration coroutine. New `SettingsScreen.kt` is the third `Screen` enum entry in MainActivity. Controller-affecting settings (OIS, meter region) become `@Volatile` fields on `CameraController`, applied on the next repeating-request push.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.datastore:datastore-preferences:1.1.1`, Camera2. No native changes.

**Spec:** `docs/superpowers/specs/2026-07-18-settings-page-design.md` — authoritative for every default/value; this plan repeats the values inline so tasks are self-contained.

## Global Constraints

- Kotlin only; no changes under `core/` or to `NativeBridge` signatures.
- Verification: NO unit-test infra exists in this repo (established project fact — do not add a test framework). Every task's cycle is: implement → `./gradlew assembleDebug` passes → documented trace-through of the touched paths in the task report. On-device verification is done by the orchestrator after all tasks.
- Commit directly to `main`, conventional messages (`feat:`/`fix:`), each ending with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- PowerShell 5.1 traps: no `&&` chaining; NO double quotes inside `git commit -m @'...'@` here-strings (breaks commits). The Bash tool with a heredoc is safe.
- Style: match existing files (verbose kdoc on non-obvious logic, `_uiState.update { copy(...) }` idiom, `@Volatile` for cross-thread controller fields).
- Defaults MUST preserve today's behavior except the one disclosed change: startup metering default is `IF_NO_SAVED` (spec §1).
- GateGuard hook may block a first Bash/Edit with a "[Fact-Forcing Gate]" message: state the demanded facts in reply text, retry the identical operation.

## File Structure

- Create `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` — data model + DataStore (Task 1).
- Create `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt` — the whole settings UI (Task 2).
- Modify `app/src/main/java/com/shez/rawcam/MainActivity.kt` — third screen (Task 2).
- Modify `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` — collector, restore, startup-meter modes, recording/meter/viewfinder behaviors (Tasks 3-6).
- Modify `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` — OIS + meter-region fields (Tasks 4-5).
- Modify `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt`, `app/src/main/java/com/shez/rawcam/export/ExportService.kt` — clips/export behaviors (Task 7).
- Modify `app/build.gradle.kts` — DataStore dependency (Task 1).

---

### Task 1: Data model + SettingsRepository (DataStore)

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block)
- Create: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (later tasks rely on these EXACT names/types):

```kotlin
enum class StartupMeter { ALWAYS, IF_NO_SAVED, NEVER }
enum class MeterScope { EVERYTHING, EXPOSURE_FOCUS, WB_ONLY }
enum class MainsFreq { OFF, HZ50, HZ60 }
enum class OisMode { AUTO, ON, OFF }
enum class ShutterDisplay { FRACTION, ANGLE }
enum class MeterRegion { SMALL, MEDIUM, LARGE }   // box fraction 0.05f / 0.10f / 0.20f

data class Settings(
    val startupMeter: StartupMeter = StartupMeter.IF_NO_SAVED,
    val defaultKelvin: Int = 5600,
    val defaultTint: Int = 0,
    val defaultIso: Int = 0,                 // 0 = device minimum
    val defaultShutterDenom: Int = 48,
    val defaultFps: Int = 24,
    val defaultLensIndex: Int = -1,          // -1 = device main
    val defaultSizeIndex: Int = 0,
    val rememberLastState: Boolean = true,
    val freeSpaceReserveSeconds: Int = 35,   // 5..120 step 5
    val maxClipLengthSeconds: Int = 0,       // 0=off, 30, 60, 300, 600
    val thermalAutoStop: Boolean = false,
    val mainsFreq: MainsFreq = MainsFreq.OFF,
    val oisMode: OisMode = OisMode.AUTO,
    val clipPrefix: String = "clip",         // sanitized [A-Za-z0-9_-], 1..16
    val meterScope: MeterScope = MeterScope.EVERYTHING,
    val meterRegion: MeterRegion = MeterRegion.MEDIUM,
    val reticleHoldMs: Int = 600,            // 300, 600, 1200
    val gridEnabled: Boolean = false,
    val levelEnabled: Boolean = false,
    val shutterDisplay: ShutterDisplay = ShutterDisplay.FRACTION,
    val showStatsSidebar: Boolean = true,
    val showBench: Boolean = true,
    val confirmDelete: Boolean = true,
    val deleteAfterExport: Boolean = false,
    val autoExport: Boolean = false,
    val debugLogging: Boolean = false,
)

data class CaptureState(
    val iso: Int, val shutterDenom: Int, val focusDiopters: Float,
    val kelvin: Int, val tint: Int, val fps: Int,
    val lensIndex: Int, val sizeIndex: Int,
    val anchorR: Float, val anchorG: Float, val anchorB: Float, // <=0f means no anchor
    val anchorKelvin: Int,
)

object SettingsRepository {
    fun init(context: Context)                     // idempotent, call once from Activity onCreate
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
    val captureState: Flow<CaptureState?>          // null = nothing saved
    suspend fun saveCaptureState(s: CaptureState)
    suspend fun clearAll()                         // reset-all: clears settings AND capture state
    fun sanitizePrefix(raw: String): String        // filter [A-Za-z0-9_-], take(16), ifEmpty "clip"
}
```

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts` dependencies block add:
```kotlin
implementation("androidx.datastore:datastore-preferences:1.1.1")
```

- [ ] **Step 2: Implement `SettingsRepository.kt`**

Implementation requirements (complete behavior, no interpretation needed):
- `private val Context.dataStore by preferencesDataStore(name = "settings")`; `init(context)` stores `context.applicationContext.dataStore` in a `lateinit` field (second call is a no-op).
- One `stringPreferencesKey`/`intPreferencesKey`/`booleanPreferencesKey`/`floatPreferencesKey` per field, key name = field name (e.g. `intPreferencesKey("defaultKelvin")`). Enums stored as `name` strings, decoded with `runCatching { enumValueOf<T>(s) }.getOrDefault(default)`.
- `settings` = `dataStore.data.catch { emit(emptyPreferences()) }.map { prefs -> Settings( each field: prefs[key] decoded ?: default ) }`.
- `update(transform)` = `dataStore.edit { prefs -> build current Settings from prefs, apply transform, write every field back }`. Apply `sanitizePrefix` to `clipPrefix` on write; coerce `freeSpaceReserveSeconds` into `5..120`.
- Capture state: a `booleanPreferencesKey("captureStateSaved")` guard plus one key per CaptureState field (prefix `cs_`, e.g. `intPreferencesKey("cs_iso")`). `captureState` maps to null when the guard is absent/false. `saveCaptureState` writes all fields + guard=true. `clearAll()` = `dataStore.edit { it.clear() }`.
- kdoc on the object explaining the corrupt-key fallback contract (every read falls back to the field default; IO failures emit defaults via the `catch`).

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

`feat: settings data model and DataStore repository`

---

### Task 2: SettingsScreen UI + navigation

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/shez/rawcam/MainActivity.kt` (Screen enum + when + entry wiring)
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (add SETTINGS button under BENCH, left gutter)

**Interfaces:**
- Consumes: `SettingsRepository.settings`, `.update`, `.clearAll`, `.init`, all Task-1 types.
- Produces: `@Composable fun SettingsScreen(onBack: () -> Unit)`; `Screen.Settings` enum entry; RecordScreen gains parameters `settingsEnabled: Boolean = true, onOpenSettings: () -> Unit = {}` (same pattern as `clipsEnabled`/`onOpenClips`).

- [ ] **Step 1: Row widgets**

In `SettingsScreen.kt` build private composables, styled to match ClipsScreen's dark list look (same color/typography constants as existing screens):

```kotlin
@Composable private fun SectionHeader(title: String)  // letter-spaced muted caps, like panel titles
@Composable private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit)
@Composable private fun <T> EnumRow(title: String, subtitle: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit)  // inline segmented selector, FpsToggle visual language
@Composable private fun SliderRow(title: String, stops: List<Int>, selected: Int, labelFor: (Int) -> String, onSelect: (Int) -> Unit)      // reuse TickedSlider
@Composable private fun TextFieldRow(title: String, value: String, onCommit: (String) -> Unit)  // single line; sanitize via SettingsRepository.sanitizePrefix on commit/IME-done
```

- [ ] **Step 2: Screen scaffold**

`SettingsScreen(onBack)` collects `SettingsRepository.settings` via `collectAsState(initial = Settings())`; a scrollable `Column` renders every row below; each `onChange`/`onSelect` calls `scope.launch { SettingsRepository.update { it.copy(field = new) } }` (`rememberCoroutineScope`). Header row: back arrow + "Settings" title, mirroring ClipsScreen's header exactly.

- [ ] **Step 3: Render ALL rows (complete enumeration — implement every line)**

| Section | Row | Widget | Options/labels → Settings field |
|---|---|---|---|
| CAPTURE DEFAULTS | Startup metering | EnumRow | ALWAYS "Always" / IF_NO_SAVED "If nothing saved" / NEVER "Never" → `startupMeter` |
| | Default white balance | SliderRow | KELVIN_STOPS (2000..10000) → `defaultKelvin`, label "${it}K" |
| | Default tint | SliderRow | TINT_STOPS (-50..50 step 5) → `defaultTint` |
| | Default ISO | EnumRow | 0 "Device min" / 100 / 200 / 400 / 800 → `defaultIso` |
| | Default shutter | EnumRow | 48 "1/48" / 60 "1/60" / 120 "1/120" → `defaultShutterDenom` |
| | Default frame rate | EnumRow | 24 / 30 / 48 / 60 → `defaultFps` |
| | Default lens | EnumRow | -1 "Main" / 0 "First" / 1 "Second" / 2 "Third" → `defaultLensIndex`, subtitle "Falls back to Main if out of range" |
| REMEMBER | Remember last settings | ToggleRow | subtitle "Reopen with your last ISO, shutter, WB, focus, lens and frame rate" → `rememberLastState` |
| RECORDING | Free-space reserve | SliderRow | listOf(5,10,...,120) step 5 → `freeSpaceReserveSeconds`, label "${it}s" |
| | Max clip length | EnumRow | 0 "Off" / 30 "30s" / 60 "1m" / 300 "5m" / 600 "10m" → `maxClipLengthSeconds` |
| | Thermal auto-stop | ToggleRow | subtitle "Stop recording when the device overheats (otherwise warn only)" → `thermalAutoStop` |
| | Anti-flicker | EnumRow | OFF "Off" / HZ50 "50 Hz" / HZ60 "60 Hz" → `mainsFreq` |
| | Optical stabilization | EnumRow | AUTO "Auto" / ON "On" / OFF "Off" → `oisMode` |
| | Clip name prefix | TextFieldRow | current `clipPrefix` |
| TAP-TO-METER | Tap adjusts | EnumRow | EVERYTHING "Everything" / EXPOSURE_FOCUS "Exposure + focus" / WB_ONLY "White balance" → `meterScope` |
| | Meter region | EnumRow | SMALL "S" / MEDIUM "M" / LARGE "L" → `meterRegion` |
| | Reticle hold | EnumRow | 300 "0.3s" / 600 "0.6s" / 1200 "1.2s" → `reticleHoldMs` |
| VIEWFINDER | Grid | ToggleRow | subtitle "Rule-of-thirds overlay" → `gridEnabled` |
| | Level | ToggleRow | subtitle "Horizon indicator" → `levelEnabled` |
| | Shutter display | EnumRow | FRACTION "1/48" / ANGLE "180°" → `shutterDisplay` |
| | Stats sidebar | ToggleRow | → `showStatsSidebar` |
| | BENCH button | ToggleRow | → `showBench` |
| CLIPS & EXPORT | Confirm before delete | ToggleRow | → `confirmDelete` |
| | Delete original after export | ToggleRow | subtitle "Removes the .rawv once DNGs are written" → `deleteAfterExport` |
| | Auto-export after recording | ToggleRow | → `autoExport` |
| ADVANCED | Diagnostic logging | ToggleRow | subtitle "Verbose meter/WB logs" → `debugLogging` |
| | Reset all settings | action row | AlertDialog "Reset all settings? This also clears remembered capture state." → confirm calls `clearAll()` |
| | About | static rows | "RawCam " + BuildConfig.VERSION_NAME, "Core " + NativeBridge.nativeVersion(), Build.MODEL |

- [ ] **Step 4: Navigation**

MainActivity: add `Settings` to the `Screen` enum; `Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Record })`; call `SettingsRepository.init(applicationContext)` in `onCreate` before `setContent`. RecordScreen: add `settingsEnabled`/`onOpenSettings` params; render a "SETTINGS" `TextButton` directly below BENCH (same style/letter-spacing, `Alignment.TopStart` column), `enabled = settingsEnabled`; MainActivity passes `settingsEnabled = !locked, onOpenSettings = { if (!locked) screen = Screen.Settings }`.

- [ ] **Step 5: Build + trace**

`./gradlew assembleDebug` BUILD SUCCESSFUL. Trace in report: every row present (count against the table — 27 rows + 3 about lines), each writes through `update`, reset clears and the UI snaps back to defaults via the flow.

- [ ] **Step 6: Commit** — `feat: settings screen UI and navigation`

---

### Task 3: Settings collector, capture-state restore, startup-meter modes

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (RecordUiState, RecordViewModel init/collector, setters write-through, startup meter gating)
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (anchor accessors)

**Interfaces:**
- Consumes: Task 1 API; existing `controller.initialize()`, enumeration publish block, `didAutoMeter` startup-meter flag.
- Produces: `RecordUiState.settings: Settings = Settings()`; `CameraController.restoreWbAnchor(gains: RggbChannelVector, kelvin: Int)` (assigns the existing `@Volatile` anchor fields, no request push) and `CameraController.wbAnchorOrNull(): Pair<RggbChannelVector, Int>?`; private `RecordViewModel.persistCaptureState()`.

- [ ] **Step 1: Collector**

In `RecordViewModel.init`: `viewModelScope.launch { SettingsRepository.settings.collect { s -> _uiState.update { it.copy(settings = s) } } }`. Add `val settings: Settings = Settings()` to `RecordUiState`.

- [ ] **Step 2: Restore flow inside the existing enumeration coroutine**

Extend the existing `cameraOps.launch { controller.initialize(); ... }` block:

```kotlin
val remember = SettingsRepository.settings.first().rememberLastState
val saved = if (remember) SettingsRepository.captureState.first() else null
controller.initialize()
val s0 = SettingsRepository.settings.first()
val lensCount = controller.lenses.size
val lensIndex = (saved?.lensIndex ?: s0.defaultLensIndex)
    .let { if (it in 0 until lensCount) it else controller.defaultLensIndex }
val sizeIndex = (saved?.sizeIndex ?: s0.defaultSizeIndex)
    .let { if (it in controller.lenses[lensIndex].sizes.indices) it else 0 }
if (lensIndex != controller.defaultLensIndex || sizeIndex != 0) controller.selectMode(lensIndex, sizeIndex)
val fps = (saved?.fps ?: s0.defaultFps)
    .let { f -> fpsOptions(controller.rawSpec).let { o -> if (f in o) f else o.first() } }
val stops = shutterStops(fps)
val denom = saved?.shutterDenom ?: s0.defaultShutterDenom
val shutterIndex = stops.indexOf(stops.minByOrNull { kotlin.math.abs(it - denom) } ?: stops.first()).coerceAtLeast(0)
val iso = (saved?.iso ?: if (s0.defaultIso == 0) controller.rawSpec.isoRange.start else s0.defaultIso)
    .coerceIn(controller.rawSpec.isoRange)
val kelvin = KELVIN_STOPS.minByOrNull { kotlin.math.abs(it - (saved?.kelvin ?: s0.defaultKelvin)) } ?: 5600
val tint = TINT_STOPS.minByOrNull { kotlin.math.abs(it - (saved?.tint ?: s0.defaultTint)) } ?: 0
val focus = (saved?.focusDiopters ?: 0f).coerceIn(0f, maxOf(controller.rawSpec.minFocusDiopters, 0f))
if (saved != null && saved.anchorG > 0f)
    controller.restoreWbAnchor(RggbChannelVector(saved.anchorR, saved.anchorG, saved.anchorG, saved.anchorB), saved.anchorKelvin)
restoredFromSaved = saved != null
_uiState.update { it.copy(rawSpec = controller.rawSpec, lenses = controller.lenses,
    iso = iso, fps = fps, shutterIndex = shutterIndex, lensIndex = lensIndex, sizeIndex = sizeIndex,
    kelvin = kelvin, tint = tint, focusDiopters = focus) }
```
`restoredFromSaved` is a new private `@Volatile var restoredFromSaved = false` on the VM. `KELVIN_STOPS`/`TINT_STOPS` are file-level in RecordScreen.kt — already visible. NOTE: `shutterStops` reads `settings.mainsFreq` after Task 4; at Task 3 it is still the static list — no ordering hazard either way because the collector (Step 1) starts before this block completes.

- [ ] **Step 3: Startup-meter modes**

Where the one-shot startup meter currently fires (openCamera onReady path, guarded by `didAutoMeter`), replace the unconditional trigger with:
```kotlin
val mode = _uiState.value.settings.startupMeter
val shouldMeter = when (mode) {
    StartupMeter.ALWAYS -> true
    StartupMeter.IF_NO_SAVED -> !restoredFromSaved
    StartupMeter.NEVER -> false
}
if (!didAutoMeter) { didAutoMeter = true; if (shouldMeter) meterAt(0.5f, 0.5f, quiet = true) }
```

- [ ] **Step 4: Write-through persistence**

Add to RecordViewModel:
```kotlin
private var persistJob: Job? = null
private fun persistCaptureState() {
    if (!_uiState.value.settings.rememberLastState) return
    persistJob?.cancel()
    persistJob = viewModelScope.launch {
        delay(500)
        val s = _uiState.value
        val stops = shutterStops(s.fps)
        val anchor = controller.wbAnchorOrNull()
        SettingsRepository.saveCaptureState(CaptureState(
            iso = s.iso, shutterDenom = stops.getOrElse(s.shutterIndex) { stops.last() },
            focusDiopters = s.focusDiopters, kelvin = s.kelvin, tint = s.tint, fps = s.fps,
            lensIndex = s.lensIndex, sizeIndex = s.sizeIndex,
            anchorR = anchor?.first?.red ?: 0f, anchorG = anchor?.first?.greenEven ?: 0f,
            anchorB = anchor?.first?.blue ?: 0f, anchorKelvin = anchor?.second ?: 5600,
        ))
    }
}
```
Call `persistCaptureState()` at the end of: `setIso`, `setShutterIndex`, `setFocus`, `setKelvin`, `setTint`, `setFps`, `setMode`, and the meter-apply block. Add `restoreWbAnchor` + `wbAnchorOrNull` to CameraController (trivial accessors over the existing `@Volatile` anchor fields; kdoc noting restore happens pre-preview so no push is needed).

- [ ] **Step 5: Build + trace + commit**

`./gradlew assembleDebug`. Trace in report: cold launch with nothing saved (settings-defaults path), with saved state (restore + clamp path, startup meter skipped under IF_NO_SAVED), lens-index-out-of-range fallback, remember toggle OFF (no writes, no restore). Commit `feat: apply settings defaults, restore last capture state`.

---

### Task 4: Recording behaviors (reserve, max length, thermal, anti-flicker, OIS, prefix)

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`

**Interfaces:**
- Consumes: `RecordUiState.settings`.
- Produces: `CameraController.oisMode: OisMode` (`@Volatile`, default AUTO); `LensInfo.availableOisModes: IntArray?`.

- [ ] **Step 1: Reserve** — in `startRecordingInternal`, replace `* 35L` with `* s.settings.freeSpaceReserveSeconds.toLong()` (refusal toast already prints the computed max seconds).
- [ ] **Step 2: Max clip length** — in the stats-poll loop body add:
```kotlin
val limit = _uiState.value.settings.maxClipLengthSeconds
if (limit > 0 && elapsed >= limit && _uiState.value.recording) {
    _events.tryEmit("Auto-stopped: clip length limit")
    stopRecordingInternal()
    break
}
```
(`stopRecordingInternal` already cancels the poll job; `break` is belt-and-braces.)
- [ ] **Step 3: Thermal auto-stop** — in the existing `thermalListener`, when `status >= PowerManager.THERMAL_STATUS_SEVERE && _uiState.value.settings.thermalAutoStop && _uiState.value.recording`: `_events.tryEmit("Recording stopped: thermal")` then `stopRecordingInternal()`. Keep the existing banner behavior unchanged.
- [ ] **Step 4: Anti-flicker** — replace the static list in `shutterStops(fps)`:
```kotlin
fun shutterStops(fps: Int): List<Int> = when (_uiState.value.settings.mainsFreq) {
    MainsFreq.OFF  -> listOf(24, 48, 60, 120, 240, 500, 1000)
    MainsFreq.HZ50 -> listOf(24, 50, 100, 200, 400, 500, 1000)
    MainsFreq.HZ60 -> listOf(24, 60, 120, 240, 500, 1000)
}.filter { it > fps }
```
Delete the now-unused `ALL_SHUTTER_DENOMS` constant. In the Task-3 collector, when `mainsFreq` changed between emissions: `_uiState.update { it.copy(shutterIndex = it.shutterIndex.coerceIn(0, shutterStops(it.fps).size - 1)) }` then `pushManual()`.
- [ ] **Step 5: OIS** — CameraController: `@Volatile var oisMode: OisMode = OisMode.AUTO`; cache `availableOisModes` on `LensInfo` in `buildLensCandidate` from `CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` (nullable IntArray) and track the active lens's value alongside `activeArraySize`. In `applyManual`, after the focus key:
```kotlin
when (oisMode) {
    OisMode.AUTO -> { /* leave HAL default */ }
    OisMode.ON, OisMode.OFF -> {
        val want = if (oisMode == OisMode.ON) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                   else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        val modes = activeOisModes
        if (modes != null && want in modes) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, want)
    }
}
```
VM collector: on `oisMode` change, set `controller.oisMode` and call `pushManual()`.
- [ ] **Step 6: Prefix** — in `startRecordingInternal` the name becomes `"${s.settings.clipPrefix}_" + timestamp + ".rawv"` (prefix already sanitized at save).
- [ ] **Step 7: Build + trace + commit** — trace each behavior's thread/path (esp. thermal listener runs on mainExecutor while stopRecordingInternal launches cameraOps — same as toggleRecord). Commit `feat: recording settings behaviors`.

---

### Task 5: Meter settings (scope, region, reticle hold)

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (meter apply block, reticle delay)
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (`meteringRectFor` region size)

**Interfaces:**
- Consumes: `Settings.meterScope/meterRegion/reticleHoldMs`; the existing batched meter-apply block (single `_uiState.update` + `pushManual` + `setWbOverride`).
- Produces: `CameraController.meterRegionFraction: Float` (`@Volatile`, default 0.10f).

- [ ] **Step 1: Region** — CameraController: `@Volatile var meterRegionFraction = 0.10f`; in `meteringRectFor` replace the hardcoded `0.05f` half-width with `(meterRegionFraction / 2f)`. VM collector maps `MeterRegion.SMALL→0.05f, MEDIUM→0.10f, LARGE→0.20f` on change.
- [ ] **Step 2: Scope** — in the meter-apply block, branch the batched update:
```kotlin
val scope = _uiState.value.settings.meterScope
_uiState.update { cur -> when (scope) {
    MeterScope.EVERYTHING -> cur.copy(iso = newIso, shutterIndex = newShutter, focusDiopters = m.focusDiopters, kelvin = m.kelvin, tint = m.tint)
    MeterScope.EXPOSURE_FOCUS -> cur.copy(iso = newIso, shutterIndex = newShutter, focusDiopters = m.focusDiopters)
    MeterScope.WB_ONLY -> cur.copy(kelvin = m.kelvin, tint = m.tint)
} }
pushManual()
if (scope != MeterScope.EXPOSURE_FOCUS) controller.setWbOverride(m.wbGains)
```
Under EXPOSURE_FOCUS the pre-existing wbOverride/anchor stay exactly as they were (do not call setWbOverride; the anchor update in readMetered is acceptable — it tracks the scene; applied WB doesn't change; document this in the report). Applies to the startup meter too (same code path).
- [ ] **Step 3: Reticle hold** — replace `delay(600)` in the meter callback with `delay(_uiState.value.settings.reticleHoldMs.toLong())`.
- [ ] **Step 4: Build + trace + commit** — trace all three scopes incl. startup meter and persistCaptureState interaction; commit `feat: tap-to-meter settings`.

---

### Task 6: Viewfinder settings (grid, level, shutter-angle, visibility)

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`

**Interfaces:**
- Consumes: `Settings.gridEnabled/levelEnabled/shutterDisplay/showStatsSidebar/showBench`; the pillarboxed inner preview Box (where the reticle Canvas already draws).

- [ ] **Step 1: Grid** — inside the inner preview Box, above the SurfaceView and below the reticle Canvas:
```kotlin
if (state.settings.gridEnabled) Canvas(Modifier.fillMaxSize()) {
    val c = Color.White.copy(alpha = 0.30f)
    for (f in listOf(1f / 3f, 2f / 3f)) {
        drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1.dp.toPx())
        drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
    }
}
```
- [ ] **Step 2: Level** — new `@Composable private fun HorizonLevel(modifier: Modifier)` in RecordScreen.kt: `DisposableEffect(Unit)` registers a `SensorEventListener` on `Sensor.TYPE_ROTATION_VECTOR` (`SENSOR_DELAY_UI`) writing roll degrees into `remember { mutableFloatStateOf(0f) }`; derive roll via `SensorManager.getRotationMatrixFromVector` → `SensorManager.remapCoordinateSystem(m, AXIS_X, AXIS_Z, out)` → `getOrientation`, `roll = Math.toDegrees(orientation[2].toDouble()).toFloat()` adjusted for the app's landscape lock (verify sign on device; document chosen adjustment); unregister in `onDispose`. Compose it inside the preview Box only when `state.settings.levelEnabled` (not composed = no listener; leaving the screen disposes it). Draw: a 120dp centered horizontal line rotated by `-roll` (`Modifier.rotate(-roll)`), `Color.Green` when `abs(roll) <= 0.5f` else `Color.White.copy(alpha = 0.6f)`, plus a fixed 24dp center reference tick.
- [ ] **Step 3: Shutter display** — add file-level:
```kotlin
private fun shutterLabel(denom: Int, fps: Int, mode: ShutterDisplay) = when (mode) {
    ShutterDisplay.FRACTION -> "1/$denom"
    ShutterDisplay.ANGLE -> "${(360f * fps / denom).roundToInt()}°"
}
```
Use it for the SHUTTER chip text and the shutter panel's `labelFor`. Storage/indices unchanged (24 fps @ 1/48 = 180°).
- [ ] **Step 4: Visibility** — wrap the stats sidebar column in `if (state.settings.showStatsSidebar) { ... }`; wrap the BENCH TextButton in `if (state.settings.showBench) { ... }` (SETTINGS button renders regardless, below the optional BENCH slot).
- [ ] **Step 5: Build + trace + commit** — trace: overlays only inside pillarbox (tap coords unaffected — overlays are non-interactive), level sensor lifecycle, angle math. Commit `feat: viewfinder settings`.

---

### Task 7: Clips & export behaviors + diagnostic logging gate

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt` (confirm-delete bypass, deleteAfter at export call site)
- Modify: `app/src/main/java/com/shez/rawcam/export/ExportService.kt` (delete-after-export)
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` (auto-export, logging gate)
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` (logging gate)

**Interfaces:**
- Consumes: `SettingsRepository.settings` (ClipsScreen reads it via `first()` inside its existing coroutines — it has no ViewModel; fine for these flags).
- Produces: `ExportService.start(context, clipName, deleteAfter: Boolean)` — existing `start` gains the flag (all call sites updated); `CameraController.debugLogging: Boolean` (`@Volatile`).

- [ ] **Step 1: Confirm delete** — extract the existing confirmed-delete block (IO delete + `refreshTick++`) into `fun performDelete(clip)` used by the dialog's confirm; Delete button `onClick` becomes `scope.launch { if (SettingsRepository.settings.first().confirmDelete) pendingDelete = clip else performDelete(clip) }`.
- [ ] **Step 2: Delete after export** — `ExportService.start` gains `deleteAfter: Boolean` passed as an intent extra → `onStartCommand` → on successful, non-cancelled completion: delete the source `.rawv` (existing IO context), log failure with the existing TAG. ClipsScreen's export call site passes `SettingsRepository.settings.first().deleteAfterExport` (inside its existing coroutine).
- [ ] **Step 3: Auto-export** — RecordViewModel: `private var lastClipName: String? = null`, set where `startRecordingInternal` builds the name. In `stopRecordingInternal` after a successful stop (`stats[0] > 0`): `val st = _uiState.value.settings; if (st.autoExport) lastClipName?.let { ExportService.start(getApplication(), it, st.deleteAfterExport) }`.
- [ ] **Step 4: Logging gate** — CameraController `@Volatile var debugLogging = false` (set from the VM collector). Wrap the `meterAt` entry Log.i (RecordViewModel) and per-meter WB logs in `if (debugLogging)` / `if (controller.debugLogging)`. The one-time `initialize()` sanity Log.i stays unconditional (spec).
- [ ] **Step 5: Build + trace + commit** — trace: delete both modes, export ± deleteAfter (incl. queued second export semantics), auto-export chain on stop (serialized service), logging on/off. Commit `feat: clips, export and diagnostics settings`.

---

## Self-Review (done at plan-writing time)

- Spec coverage: §1→T2/T3, §2→T1/T3, §3→T2/T4, §4→T2/T5, §5→T2/T6, §6→T2/T7, §7→T2 (reset/about) + T7 (logging gate); architecture→T1/T2; error handling→T1 (fallback contract) + T3 (clamps) + T4 (OIS capability check). No gaps.
- Placeholders: none — every step names exact values/keys/labels or shows the code.
- Type consistency: `Settings`/`CaptureState`/enums defined once in Task 1, consumed by those names everywhere; controller additions (`oisMode`, `meterRegionFraction`, `debugLogging`, `restoreWbAnchor`, `wbAnchorOrNull`, `activeOisModes`) are introduced in the task that first needs them and referenced identically afterward.
