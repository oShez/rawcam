package com.shez.rawcam.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.hardware.camera2.params.RggbChannelVector
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.camera.CameraController
import com.shez.rawcam.camera.ControlTier
import com.shez.rawcam.camera.LensProfile
import com.shez.rawcam.camera.ShutterStops
import com.shez.rawcam.export.ExportService
import com.shez.rawcam.settings.CaptureState
import com.shez.rawcam.settings.MainsFreq
import com.shez.rawcam.settings.MeterRegion
import com.shez.rawcam.settings.MeterScope
import com.shez.rawcam.settings.Settings
import com.shez.rawcam.settings.SettingsRepository
import com.shez.rawcam.settings.ShutterDisplay
import com.shez.rawcam.settings.StartupMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** UI state for [RecordScreen]. Sliders store the raw value the user picked; the
 * viewmodel derives exposureNs from [shutterIndex] against the fps-filtered stop list.
 * [busy] is true while an async start/stop transition is in flight; the record button
 * is disabled during it (debounce). [rawSpec] and [lenses] are null/empty until
 * camera enumeration (off-main, see [CameraController.initialize]) completes and
 * publishes them here -- the composable renders a loading placeholder until then. */
data class RecordUiState(
    val rawSpec: CameraController.RawSpec? = null,
    val lenses: List<LensProfile> = emptyList(),
    val previewReady: Boolean = false,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val elapsedSeconds: Int = 0,
    val written: Long = 0,
    val dropped: Long = 0,
    val thermalSevere: Boolean = false,
    val iso: Int = 100,
    val shutterIndex: Int = 0,
    val focusDiopters: Float = 0f,
    val fps: Int = 24,
    val freeSpaceBytes: Long = 0,
    val lensIndex: Int = 0,
    val sizeIndex: Int = 0,
    val kelvin: Int = 5600,
    val tint: Int = 0,
    val controlTier: ControlTier = ControlTier.FULL,
    val exposureRangeNs: LongRange? = null,
    // Locking a slider freezes its value: the slider itself is disabled (no manual
    // drag) and meterAt()'s tap-to-meter result skips that field entirely, whatever
    // MeterScope is active. Session-only (not persisted via CaptureState).
    val isoLocked: Boolean = false,
    val shutterLocked: Boolean = false,
    val focusLocked: Boolean = false,
    val kelvinLocked: Boolean = false,
    val tintLocked: Boolean = false,
    val metering: Boolean = false,
    val meterPoint: androidx.compose.ui.geometry.Offset? = null,
    val settings: Settings = Settings(),
)

/**
 * Owns the [CameraController] for the lifetime of the activity, the manual-control
 * state, the record/stop flow (with the free-space refusal check), the 500ms stats
 * poll while recording, and the thermal-status listener.
 *
 * All controller calls that may block (openAndPreview, startRecording, stopRecording,
 * close) run on [cameraOps], a single-lane Default-dispatcher scope: nothing blocks the
 * main thread, and serialization guarantees ordering (a stop always completes -- file
 * finalized -- before a subsequent reopen/start/close runs). The scope deliberately
 * outlives viewModelScope so an in-flight stop/close cannot be cancelled by onCleared.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    // Cheap: no camera binder IPC happens until initialize() runs (below, off-main).
    val controller = CameraController(application)

    private val cameraOps = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private var pollJob: Job? = null
    private var recordStartMs = 0L
    // Filename (with .rawv extension, e.g. "clip_20260719_120000.rawv") of the clip
    // most recently started -- written once, on cameraOps, where startRecordingInternal
    // builds the name; read (also on cameraOps) by stopRecordingInternal's successful-
    // stop completion to auto-export that exact clip. Not @Volatile: both the write and
    // the read happen on cameraOps, which is single-lane FIFO, so there is no
    // cross-thread visibility concern (same reasoning as autoMeteredLensIndices below).
    private var lastClipName: String? = null
    // Runs on application.mainExecutor (see the addThermalStatusListener call in
    // init{} below) -- NOT the camera thread. stopRecordingInternal() itself only
    // touches _uiState/_events directly and launches the actual controller.stopRecording()
    // call onto cameraOps, so calling it from the main thread here is the same shape as
    // toggleRecord()'s UI-thread call into stopRecordingInternal.
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _uiState.update { it.copy(thermalSevere = status >= PowerManager.THERMAL_STATUS_SEVERE) }
        if (status >= PowerManager.THERMAL_STATUS_SEVERE &&
            _uiState.value.settings.thermalAutoStop && _uiState.value.recording
        ) {
            if (stopRecordingInternal()) _events.tryEmit("Recording stopped: thermal")
        }
    }

    init {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.addThermalStatusListener(application.mainExecutor, thermalListener)
        // Settings collector: independent of cameraOps, started immediately so
        // uiState.settings is live (a) for the restore block below (which also reads
        // SettingsRepository directly, since it needs a one-shot snapshot, not the
        // live collected value -- see that block's kdoc) and (b) for every setter's
        // persistCaptureState() and the startup-meter gating in openCamera(), both of
        // which read uiState.settings rather than re-querying the repository.
        viewModelScope.launch {
            // Tracks the previously-emitted Settings (null for the first emission) so
            // reactions below only fire on an actual CHANGE of the field they care
            // about, not on every unrelated settings write (DataStore re-emits the
            // whole record on any single-field edit, e.g. from SettingsScreen).
            var previous: Settings? = null
            SettingsRepository.settings.collect { s ->
                val prev = previous
                _uiState.update { it.copy(settings = s) }
                if (prev != null && prev.mainsFreq != s.mainsFreq) {
                    // The stop list just changed shape (see shutterStops) -- reclamp
                    // the persisted index into the new list's bounds, then push the
                    // (possibly unchanged-in-Hz-terms) exposure at that index live.
                    _uiState.update {
                        it.copy(shutterIndex = it.shutterIndex.coerceIn(0, shutterStops(it.fps).size - 1))
                    }
                    pushManual()
                }
                // Unconditional on every emission (not just changes): the FIRST
                // emission carries the loaded persisted oisMode, and a gated write
                // here would leave controller.oisMode at its AUTO default for the
                // whole session for a user with a saved ON/OFF preference. This is a
                // cheap volatile field write, so writing it every time is fine; only
                // the pushManual() re-arm (which re-posts a repeating request onto
                // the camera thread) stays gated on an actual change.
                controller.oisMode = s.oisMode
                if (prev != null && prev.oisMode != s.oisMode) {
                    pushManual()
                }
                // Unconditional for the same reason as oisMode above: the FIRST
                // emission must apply a saved non-default meterRegion, and this is
                // a cheap volatile write. Unlike oisMode there is no pushManual()
                // companion -- meterRegionFraction only matters on the next
                // meterAt() call (it sizes the AE/AWB/AF metering rectangle at tap
                // time), there is no live repeating-request value to re-arm.
                controller.meterRegionFraction = when (s.meterRegion) {
                    MeterRegion.SMALL -> 0.05f
                    MeterRegion.MEDIUM -> 0.10f
                    MeterRegion.LARGE -> 0.20f
                }
                // Unconditional for the same reason as oisMode/meterRegionFraction
                // above: the FIRST emission must apply a saved debugLogging=true, and
                // this is a cheap @Volatile write with no live request to re-arm.
                controller.debugLogging = s.debugLogging
                previous = s
            }
        }
        ensureCameraInitialized()
        // Free-space poll for the "space remaining" readout is driven from the
        // composable (see RecordScreen's repeatOnLifecycle block calling
        // refreshFreeSpace()) rather than looping here for the whole viewmodel
        // lifetime -- the ViewModel has no Lifecycle of its own to gate on (and
        // shouldn't be handed the Activity's, which would break its
        // configuration-change independence), so pausing this StatFs + uiState
        // poll while backgrounded is delegated to the UI side instead.
    }

    @Volatile private var cameraInitStarted = false

    /**
     * Starts camera enumeration/restore exactly once, and only once CAMERA
     * permission is actually granted. Previously this ran unconditionally from
     * init{} regardless of permission state -- and enumeration used to throw on
     * the redacted characteristics Android hands an app that lacks the CAMERA
     * permission, crashing the whole process the instant the app launched without
     * it (found 2026-07-21 on-device while verifying the RecordScreen
     * permission-gate UI, which that crash meant could never actually be
     * reached). Enumeration no longer throws -- LensDiscovery classifies that
     * exact case as UnsupportedReason.PERMISSION_REDACTED -- but the gate stays:
     * there is no point burning a full characteristics sweep to learn we lack
     * permission, and the caller still wants a real profile, not a redacted one.
     * Called from init{} (the common case -- permission already
     * granted from a prior run) and again from RecordScreen once its runtime
     * permission request resolves to granted (the fresh-install/just-denied case)
     * -- the guard makes every call after the first a no-op, so calling from both
     * places is safe.
     */
    fun ensureCameraInitialized() {
        if (cameraInitStarted) return
        if (!hasCameraPermission(getApplication())) return
        cameraInitStarted = true
        // Camera lens enumeration is binder IPC (getCameraCharacteristics per lens +
        // stream-config queries) -- runs on cameraOps, never the main thread. Queued
        // first on cameraOps, so every later cameraOps.launch (openCamera, etc.) is
        // guaranteed to run after this completes. lenses/rawSpec are published into
        // uiState only once initialize() returns; the composable gates on rawSpec
        // being non-null and renders nothing controller-derived before that.
        //
        // Restore ordering -- why the saved-state read happens BEFORE
        // controller.initialize(): `remember`/`saved` come from SettingsRepository
        // (DataStore, already warm -- SettingsRepository.init() runs from
        // Activity.onCreate ahead of this ViewModel's construction), which is
        // independent of and far cheaper than the camera binder IPC below, so
        // capturing them first takes one consistent snapshot ahead of the slow call
        // rather than racing a settings write that might land mid-initialize(); it
        // also means a saved lens/size choice is known before selectMode() is
        // reachable, letting the restore apply in the same pass instead of a second
        // mode switch after publish. Every clamp below is then safe by construction:
        // lensIndex/sizeIndex are checked against controller.lenses (populated by
        // initialize(), hence read only after it returns), fps against
        // fpsOptions(rawSpec), shutterIndex by nearest-match against
        // shutterStops(fps) (coerceAtLeast(0) guards indexOf's -1-not-found case,
        // which cannot actually trigger since minByOrNull always returns a list
        // member), iso against rawSpec.isoRange, kelvin/tint snapped to the nearest
        // fixed stop (always valid even from a stale saved value), and focus coerced
        // into 0f..max(minFocusDiopters, 0f). A too-large or negative saved
        // lensIndex/sizeIndex falls back to controller.defaultLensIndex / 0 -- the
        // same values a fresh install would use -- rather than an out-of-bounds
        // list read.
        cameraOps.launch {
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
            val lens = controller.lenses.getOrNull(lensIndex)
            val fps = (saved?.fps ?: s0.defaultFps)
                .let { f -> fpsOptions(controller.rawSpec).let { o -> if (f in o) f else o.first() } }
            // shutterStopsFor(fps, lens?.exposureRangeNs), not shutterStops(fps): at this
            // point _uiState.value.exposureRangeNs is still the pre-init default (null),
            // since it's only published below -- reading it here would restore an
            // unfiltered index for one frame on lenses with a narrower sensor range.
            val stops = shutterStopsFor(fps, lens?.exposureRangeNs)
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
            restoredLensIndex = lensIndex
            _uiState.update {
                it.copy(
                    rawSpec = controller.rawSpec, lenses = controller.lenses,
                    iso = iso, fps = fps, shutterIndex = shutterIndex, lensIndex = lensIndex, sizeIndex = sizeIndex,
                    kelvin = kelvin, tint = tint, focusDiopters = focus,
                    controlTier = lens?.controlTier ?: ControlTier.FULL,
                    exposureRangeNs = lens?.exposureRangeNs,
                )
            }
        }
    }

    /** One StatFs (IO) read of [CameraController.clipsDir]'s free space, published
     * into uiState. Called repeatedly by RecordScreen's lifecycle-gated poll so
     * this doesn't run while the app is backgrounded. */
    suspend fun refreshFreeSpace() {
        val free = withContext(Dispatchers.IO) {
            try { StatFs(controller.clipsDir.absolutePath).availableBytes }
            catch (e: Exception) { 0L }
        }
        _uiState.update { it.copy(freeSpaceBytes = free) }
    }

    /** Shutter stops valid at [fps]: exposure must stay strictly below the frame interval.
     * The candidate list itself is biased by [Settings.mainsFreq] -- each list includes
     * denominators that divide evenly into the mains frequency (and its double), which
     * keeps exposure an integer multiple of the flicker period and avoids banding under
     * artificial light; OFF keeps the old flat list. Further intersected with the active
     * lens's real exposure range (state.exposureRangeNs) so a lens whose sensor can't
     * honour the full fixed-stop table never offers a stop the HAL would silently clamp
     * anyway -- see [ShutterStops]. */
    fun shutterStops(fps: Int): List<Int> = shutterStopsFor(fps, _uiState.value.exposureRangeNs)

    private fun shutterStopsFor(fps: Int, range: LongRange?): List<Int> {
        val raw = when (_uiState.value.settings.mainsFreq) {
            // No flicker constraint -- densified with more standard shutter speeds.
            MainsFreq.OFF -> listOf(
                24, 30, 40, 48, 50, 60, 75, 90, 100, 120, 150, 180, 200, 240, 250, 300,
                350, 400, 500, 600, 750, 800, 1000,
            )
            // Every entry below (besides the fixed 24 anchor) stays a clean multiple of
            // 50 -- more of them than before, but the anti-flicker property from the
            // original list is preserved exactly, just at finer granularity.
            MainsFreq.HZ50 -> listOf(
                24, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 900, 1000,
            )
            // Same idea, multiples of 60.
            MainsFreq.HZ60 -> listOf(
                24, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 720, 840, 900, 960, 1000,
            )
        }.filter { it > fps }
        if (range == null) return raw
        // Denominators are converted to exposure-time nanoseconds (what the sensor's
        // range is expressed in), filtered, then mapped back to the matching denom(s).
        val nsStops = raw.map { 1_000_000_000L / it }
        val allowedNs = ShutterStops.available(nsStops, range).toSet()
        return raw.filterIndexed { i, _ -> nsStops[i] in allowedNs }
    }

    /** FPS choices valid for [spec]. Never empty. Takes the mode's spec explicitly
     * (rather than reading controller.rawSpec) so composable callers can pass
     * uiState.rawSpec instead of touching the controller directly. */
    fun fpsOptions(spec: CameraController.RawSpec): List<Int> =
        FPS_OPTIONS.filter { it <= spec.maxFps }.ifEmpty { listOf(spec.maxFps) }

    /**
     * (Re)opens the camera against [surface]. Called from every surfaceCreated -- on
     * first launch AND whenever the activity returns to the foreground with a fresh
     * surface (the old one is destroyed on backgrounding, and the system may have
     * force-disconnected the camera meanwhile). Reopening the same camera id from the
     * same process is safe: the framework disconnects the previous device first.
     */
    fun openCamera(surface: android.view.Surface) {
        _uiState.update { it.copy(previewReady = false) }
        cameraOps.launch {
            try {
                controller.openAndPreview(surface, onFailed = { handleModeFailure() }) {
                    _uiState.update { it.copy(previewReady = true) }
                    pushManual()
                    // First preview-ready of THIS LENS this process: auto-meter the
                    // center once so it shows a correctly white-balanced image (and
                    // seeds CameraController's per-lens anchor -- see anchorState's
                    // kdoc) instead of the calibrated model's possibly-placeholder-
                    // matrix output persisting until the user's first manual tap --
                    // subject to the user's startupMeter setting. Keyed per lens
                    // index (not a single one-shot flag): each physical camera has
                    // its own sensor with its own absolute color response, so a lens
                    // switched to mid-session needs its OWN real measurement just as
                    // much as the very first lens did at launch (bug found alongside
                    // CameraController.anchorState's fix -- previously this only ever
                    // fired once per process, so every lens but the first relied on
                    // DEFAULT_ANCHOR_* forever unless the user happened to re-meter).
                    // autoMeteredLensIndices is a plain (non-Volatile) field -- fine
                    // because onReady always runs on the single camera thread (see
                    // CameraController's class kdoc), never concurrently with itself.
                    val lensIdx = _uiState.value.lensIndex
                    if (lensIdx !in autoMeteredLensIndices) {
                        autoMeteredLensIndices += lensIdx
                        val mode = _uiState.value.settings.startupMeter
                        val shouldMeter = when (mode) {
                            StartupMeter.ALWAYS -> true
                            // Only the lens actually restored from saved state (if
                            // any) honors IF_NO_SAVED's "don't disturb a restored WB"
                            // intent -- a DIFFERENT lens opened later via a mid-session
                            // switch has no saved WB of its own to disturb, so it
                            // always meters under this mode. restoredFromSaved/
                            // restoredLensIndex are @Volatile and written once, on
                            // cameraOps, by the init{} restore block before this
                            // onReady can ever fire (openCamera() is itself only
                            // reachable once the composable observes rawSpec != null,
                            // which that same block publishes last) -- so the read
                            // here is always the settled value.
                            StartupMeter.IF_NO_SAVED -> !(restoredFromSaved && lensIdx == restoredLensIndex)
                            StartupMeter.NEVER -> false
                        }
                        if (shouldMeter) meterAt(0.5f, 0.5f, quiet = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "openAndPreview failed", e)
                _events.tryEmit("Camera open failed")
            }
        }
    }

    /** Guards the one-shot-per-lens auto-meter in [openCamera] -- a lens index is
     * added the first time ITS preview becomes ready this process; re-opening the
     * SAME lens (backgrounding/returning, orientation change) does not re-trigger
     * it, but switching to a lens not yet in this set does. Deliberately never
     * cleared for the lifetime of this ViewModel (survives configuration changes,
     * not process death) -- a lens only needs its one real anchor measurement
     * once. */
    private val autoMeteredLensIndices = HashSet<Int>()

    /** True once the init{} restore block has determined a [CaptureState] was
     * actually applied at launch (vs. falling through to settings defaults because
     * nothing was saved, or [Settings.rememberLastState] was off). Written exactly
     * once, on cameraOps, before rawSpec is published (so before openCamera() can
     * possibly be called -- see openCamera's onReady comment); read on the camera
     * thread by the [StartupMeter.IF_NO_SAVED] check above. @Volatile makes that
     * cross-thread read see the write without relying on incidental ordering. */
    @Volatile private var restoredFromSaved = false

    /** The lensIndex the init{} restore block actually applied [restoredFromSaved]'s
     * CaptureState to (or the default lens index when nothing was restored) --
     * lets [openCamera]'s IF_NO_SAVED check distinguish "the one lens that had a
     * saved WB" from every other lens, which never had one. Written alongside
     * [restoredFromSaved], same visibility guarantee. */
    @Volatile private var restoredLensIndex = 0

    /**
     * Preview session failed to configure. If a non-default lens/size mode is
     * selected, revert to main lens full-res — the state change re-keys the
     * SurfaceView, which reopens the camera on the safe mode. If the default
     * mode itself failed, just report it (today's behavior). Runs on the camera
     * thread (posted from onFailed, only reachable once openAndPreview has been
     * called, which is itself only reachable post-enumeration); StateFlow.update
     * and tryEmit are thread-safe, and controller.defaultLensIndex is @Volatile so
     * the value initialize() wrote on cameraOps is visible here.
     */
    private fun handleModeFailure() {
        val s = _uiState.value
        if (s.lensIndex == controller.defaultLensIndex && s.sizeIndex == 0) {
            _events.tryEmit("Camera open failed")
            return
        }
        controller.selectMode(controller.defaultLensIndex, 0)
        _uiState.update {
            coerceToMode(it.copy(lensIndex = controller.defaultLensIndex, sizeIndex = 0))
        }
        _events.tryEmit("Mode not supported — reverted to main lens")
    }

    fun setIso(iso: Int) {
        if (_uiState.value.isoLocked) return
        _uiState.update { it.copy(iso = iso) }
        pushManual()
        persistCaptureState()
    }

    fun setShutterIndex(index: Int) {
        if (_uiState.value.shutterLocked) return
        _uiState.update { it.copy(shutterIndex = index) }
        pushManual()
        persistCaptureState()
    }

    fun setFocus(diopters: Float) {
        if (_uiState.value.focusLocked) return
        _uiState.update { it.copy(focusDiopters = diopters) }
        pushManual()
        persistCaptureState()
    }

    fun setKelvin(k: Int) {
        if (_uiState.value.kelvinLocked) return
        _uiState.update { it.copy(kelvin = k) }
        pushManual()
        persistCaptureState()
    }

    fun toggleIsoLock() = _uiState.update { it.copy(isoLocked = !it.isoLocked) }
    fun toggleShutterLock() = _uiState.update { it.copy(shutterLocked = !it.shutterLocked) }
    fun toggleFocusLock() = _uiState.update { it.copy(focusLocked = !it.focusLocked) }
    fun toggleKelvinLock() = _uiState.update { it.copy(kelvinLocked = !it.kelvinLocked) }
    fun toggleTintLock() = _uiState.update { it.copy(tintLocked = !it.tintLocked) }

    fun setTint(t: Int) {
        if (_uiState.value.tintLocked) return
        _uiState.update { it.copy(tint = t) }
        pushManual()
        persistCaptureState()
    }

    /**
     * Tap-to-meter: one-shot AE/AF/AWB converge at the normalized preview point
     * ([nx], [ny]), then snap the result onto the existing manual stops and apply
     * it through the existing setters (manual == locked; no new persisted lock
     * state). Disabled while recording, mid-meter, or before the preview is up.
     * [CameraController.meterAt] posts to the camera thread and always invokes
     * onResult there (converged values, or null on failure/timeout) after
     * restoring manual -- the callback hops back via [viewModelScope] to touch
     * uiState/_events from a normal coroutine context. [quiet] suppresses the
     * failure snackbar (the reticle still shows) -- for the automatic startup
     * meter in [openCamera], where a failure isn't a user action to explain.
     */
    fun meterAt(nx: Float, ny: Float, quiet: Boolean = false) {
        if (controller.debugLogging) Log.i(TAG, "meterAt nx=$nx ny=$ny quiet=$quiet")
        val s = _uiState.value
        if (s.recording || s.metering || !s.previewReady) return
        val p = Offset(nx, ny)
        _uiState.update { it.copy(metering = true, meterPoint = p) }
        cameraOps.launch {
            controller.meterAt(nx, ny) { m ->
                viewModelScope.launch {
                    if (m != null) {
                        // Batched into ONE uiState update + ONE controller push (was
                        // five setIso/setShutterIndex/setFocus/setKelvin/setTint calls,
                        // each its own update+pushManual). Branches on Settings.meterScope
                        // -- EVERYTHING copies all five metered values; EXPOSURE_FOCUS
                        // copies iso/shutter/focus only (WB untouched); WB_ONLY copies
                        // kelvin/tint only (iso/shutter/focus untouched). Fields NOT
                        // copied by a branch simply keep their pre-meter uiState values --
                        // that's correct: the meter didn't change them, and the later
                        // persistCaptureState() below snapshots exactly this post-branch
                        // state, so an EXPOSURE_FOCUS meter persists the OLD kelvin/tint
                        // (unchanged) alongside the NEW iso/shutter/focus, and a WB_ONLY
                        // meter persists the OLD iso/shutter/focus alongside NEW kelvin/
                        // tint. Applies identically to the startup auto-meter in
                        // openCamera() (same meterAt() call, same onResult callback).
                        val scope = _uiState.value.settings.meterScope
                        val newIso = nearestIso(m.iso)
                        val newShutter = nearestShutterIndex(m.exposureNs)
                        // wbLocked tracks whether EITHER WB slider was locked at the moment
                        // the meter converged -- read once before the update so the
                        // setWbOverride gate below (outside _uiState.update) sees the same
                        // decision the copy() below made, not a value from a later recompose.
                        val preLock = _uiState.value
                        val wbLocked = preLock.kelvinLocked || preLock.tintLocked
                        _uiState.update { cur ->
                            val scoped = when (scope) {
                                MeterScope.EVERYTHING -> cur.copy(
                                    iso = newIso, shutterIndex = newShutter,
                                    focusDiopters = m.focusDiopters, kelvin = m.kelvin, tint = m.tint,
                                )
                                MeterScope.EXPOSURE_FOCUS -> cur.copy(
                                    iso = newIso, shutterIndex = newShutter, focusDiopters = m.focusDiopters,
                                )
                                MeterScope.WB_ONLY -> cur.copy(kelvin = m.kelvin, tint = m.tint)
                            }
                            // Locked sliders are immune to tap-to-meter no matter what scope
                            // is active -- fall back to cur's pre-meter value for anything
                            // the user has frozen, on top of the scope's own field selection.
                            scoped.copy(
                                iso = if (cur.isoLocked) cur.iso else scoped.iso,
                                shutterIndex = if (cur.shutterLocked) cur.shutterIndex else scoped.shutterIndex,
                                focusDiopters = if (cur.focusLocked) cur.focusDiopters else scoped.focusDiopters,
                                kelvin = if (cur.kelvinLocked) cur.kelvin else scoped.kelvin,
                                tint = if (cur.tintLocked) cur.tint else scoped.tint,
                            )
                        }
                        // Ordering note (WB override, CameraController.wbOverride):
                        // pushManual() below calls controller.updateManual(..., kelvin=
                        // uiState.kelvin, tint=uiState.tint) -- under EVERYTHING/WB_ONLY
                        // those are the just-applied m.kelvin/m.tint, which typically
                        // differ from the controller's OWN stored kelvin/tint (whatever
                        // they were before this meter), so updateManual's clear-on-change
                        // check typically DOES fire and clears wbOverride here. That's
                        // fine: setWbOverride(m.wbGains) runs immediately after and is
                        // the last (and effective) word on this frame's WB gains
                        // regardless of whether the clear fired -- its own post re-arms
                        // the repeating request a second time with the exact metered
                        // gains applied. Under EXPOSURE_FOCUS, uiState.kelvin/tint were
                        // left unchanged by the branch above, so pushManual() calls
                        // updateManual with the SAME kelvin/tint the controller already
                        // has -- the clear-on-change check does NOT fire, wbOverride (and
                        // the anchor) survive untouched, and setWbOverride is skipped
                        // below -- applied WB genuinely does not change under this scope.
                        // wbLocked adds one more skip case: setWbOverride applies the raw
                        // metered gains directly, bypassing the kelvin/tint slider model
                        // entirely -- if either WB slider is locked, applying it would
                        // silently shift the recorded white balance even though the slider
                        // itself didn't move. Deferring to pushManual()'s already-locked-
                        // aware kelvin/tint is the correct fallback in that case.
                        pushManual()
                        if (scope != MeterScope.EXPOSURE_FOCUS && !wbLocked) controller.setWbOverride(m.wbGains)
                        persistCaptureState()
                    } else if (!quiet) {
                        _events.tryEmit("Couldn't meter — try again")
                    }
                    _uiState.update { it.copy(metering = false) }
                    delay(_uiState.value.settings.reticleHoldMs.toLong())   // leave the reticle briefly, then clear it
                    // Only clear the reticle if it still belongs to THIS tap and isn't
                    // mid-convergence for a newer tap -- a stale timer from a fast
                    // re-tap must never clear a newer tap's live reticle.
                    _uiState.update { if (it.meterPoint == p && !it.metering) it.copy(meterPoint = null) else it }
                }
            }
        }
    }

    /** Only called from meterAt()'s onResult callback, itself gated on
     * s.previewReady -- previewReady only ever becomes true from openCamera()'s
     * onReady, which cannot fire before controller.initialize() has run (cameraOps
     * is single-lane FIFO, and openCamera() is only invoked once the SurfaceView is
     * composed, which the UI gates on uiState.rawSpec != null). controller.rawSpec
     * is guaranteed valid here. */
    private fun nearestIso(iso: Int): Int =
        isoStops(controller.rawSpec.isoRange).minByOrNull { kotlin.math.abs(it - iso) } ?: iso

    private fun nearestShutterIndex(exposureNs: Long): Int {
        val stops = shutterStops(_uiState.value.fps)      // denominators, e.g. 48 => 1/48s
        val target = if (exposureNs > 0) (1_000_000_000.0 / exposureNs) else stops.first().toDouble()
        var best = 0
        var bestErr = Double.MAX_VALUE
        stops.forEachIndexed { i, denom ->
            val err = kotlin.math.abs(denom - target)
            if (err < bestErr) { bestErr = err; best = i }
        }
        return best
    }

    fun setFps(fps: Int) {
        if (_uiState.value.recording) return
        val stops = shutterStops(fps)
        _uiState.update { it.copy(fps = fps, shutterIndex = it.shutterIndex.coerceIn(0, stops.size - 1)) }
        pushManual()
        persistCaptureState()
    }

    /** Lens change resets the size to the new lens's full resolution. */
    fun setLens(index: Int) = setMode(index, 0)

    fun setResolution(index: Int) = setMode(_uiState.value.lensIndex, index)

    private fun setMode(lensIndex: Int, sizeIndex: Int) {
        val s = _uiState.value
        if (s.recording || s.busy) return
        if (lensIndex == s.lensIndex && sizeIndex == s.sizeIndex) return
        if (!controller.selectMode(lensIndex, sizeIndex)) return
        _uiState.update { coerceToMode(it.copy(lensIndex = lensIndex, sizeIndex = sizeIndex)) }
        // The lensIndex/sizeIndex change re-keys the preview SurfaceView; its
        // surfaceCreated calls openCamera, which reopens on the new mode.
        persistCaptureState()
    }

    /** Clamps fps/shutter/iso/focus to what the (just-selected) mode supports, AND
     * republishes [RecordUiState.rawSpec] itself. Only ever called after a
     * successful controller.selectMode (recording/lens-switch paths, themselves only
     * reachable once the UI is showing lens controls, i.e. after enumeration) --
     * controller.rawSpec is guaranteed valid here.
     *
     * [RecordUiState.rawSpec] was previously published exactly once, at ViewModel
     * init, and never again -- every composable read of it (ISO stops, resolution
     * list, aspect ratio, min focus) stayed frozen on whichever lens happened to be
     * restored/default at launch, for the rest of the process, regardless of how
     * many times the user actually switched lenses afterward. This was invisible
     * while 0.5x/1x happened to have similar enough specs, but the telephoto
     * lenses' genuinely different isoRange (e.g. max 1119 vs 3200) made it obvious:
     * the ISO slider kept showing whatever lens was active at launch, not the one
     * actually selected. iso/focusDiopters themselves also weren't coerced here at
     * all (unlike fps/shutter) -- switching to a lens with a lower
     * isoRange.endInclusive/minFocusDiopters left the UI's numeric value stale too,
     * even though CameraController.updateManual was already silently clamping the
     * applied value underneath. Both mirror the same clamp already applied once at
     * startup restore (see the init{} block's
     * `.coerceIn(controller.rawSpec.isoRange)` / focus clamp), just re-applied (and,
     * for rawSpec, re-published) on every mode change too. */
    private fun coerceToMode(state: RecordUiState): RecordUiState {
        val spec = controller.rawSpec
        val opts = fpsOptions(spec)
        val fps = if (state.fps in opts) state.fps
        else (opts.lastOrNull { it <= state.fps } ?: opts.first())
        val lens = state.lenses.getOrNull(state.lensIndex)
        // Use the NEW lens's exposureRangeNs directly (not _uiState.value's, which is
        // still the outgoing lens's until this function's return value is committed) --
        // otherwise a lens switch would clamp shutterIndex against the wrong range for
        // one frame, exactly the class of stale-index bug fixed in 0ba7eaa for ISO.
        val stops = shutterStopsFor(fps, lens?.exposureRangeNs)
        return state.copy(
            rawSpec = spec,
            fps = fps,
            shutterIndex = state.shutterIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
            iso = state.iso.coerceIn(spec.isoRange),
            focusDiopters = state.focusDiopters.coerceIn(0f, maxOf(spec.minFocusDiopters, 0f)),
            previewReady = false,
            controlTier = lens?.controlTier ?: ControlTier.FULL,
            exposureRangeNs = lens?.exposureRangeNs,
        )
    }

    private fun exposureNsFor(state: RecordUiState): Long {
        val stops = shutterStops(state.fps)
        val denom = stops.getOrElse(state.shutterIndex) { stops.lastOrNull() ?: (state.fps + 1) }
        return 1_000_000_000L / denom
    }

    private fun pushManual() {
        val s = _uiState.value
        if (!s.previewReady) return
        controller.updateManual(s.iso, exposureNsFor(s), s.focusDiopters, s.kelvin, s.tint)
    }

    private var persistJob: Job? = null

    /**
     * Debounced write-through of the current manual controls into
     * [SettingsRepository.saveCaptureState], gated on [Settings.rememberLastState].
     * Called from every setter that changes a persisted field (setIso,
     * setShutterIndex, setFocus, setKelvin, setTint, setFps, setMode) and from the
     * meter-apply block in [meterAt]'s onResult. 500ms debounce (cancel-and-relaunch
     * on [viewModelScope], same pattern as [pollJob]) coalesces a fast slider drag
     * into a single DataStore write instead of one per intermediate value.
     * [CameraController.wbAnchorOrNull] -- not uiState -- is the source for the
     * saved WB anchor: it's the controller's own real metered-gains state, which is
     * what a later restore should reproduce, not a derived kelvin/tint slider value.
     */
    private fun persistCaptureState() {
        if (!_uiState.value.settings.rememberLastState) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(500)
            val s = _uiState.value
            val stops = shutterStops(s.fps)
            val anchor = controller.wbAnchorOrNull()
            SettingsRepository.saveCaptureState(
                CaptureState(
                    iso = s.iso, shutterDenom = stops.getOrElse(s.shutterIndex) { stops.last() },
                    focusDiopters = s.focusDiopters, kelvin = s.kelvin, tint = s.tint, fps = s.fps,
                    lensIndex = s.lensIndex, sizeIndex = s.sizeIndex,
                    anchorR = anchor?.first?.red ?: 0f, anchorG = anchor?.first?.greenEven ?: 0f,
                    anchorB = anchor?.first?.blue ?: 0f, anchorKelvin = anchor?.second ?: 5600,
                )
            )
        }
    }

    fun toggleRecord() {
        val s = _uiState.value
        if (s.busy) return // debounce: a start/stop transition is already in flight
        if (s.recording) stopRecordingInternal() else startRecordingInternal()
    }

    private fun startRecordingInternal() {
        val s = _uiState.value
        if (!s.previewReady || s.recording) return
        _uiState.update { it.copy(busy = true) }
        val exposureNs = exposureNsFor(s)
        cameraOps.launch {
            try {
                // Gated above by s.previewReady (captured before this launch), which
                // cannot be true until controller.initialize() has completed -- see
                // nearestIso's comment for the full chain. controller.rawSpec is valid.
                val spec = controller.rawSpec
                val frameBytes = frameRecordBytes(spec)
                val available = StatFs(controller.clipsDir.absolutePath).availableBytes
                val required = frameBytes * s.fps * s.settings.freeSpaceReserveSeconds.toLong()
                if (available < required) {
                    val maxSeconds = available / (frameBytes * s.fps)
                    _events.tryEmit("Not enough free space; max ~${maxSeconds}s recordable")
                    return@launch
                }
                val name =
                    "${s.settings.clipPrefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".rawv"
                lastClipName = name
                val path = File(controller.clipsDir, name).absolutePath
                val ok = controller.startRecording(
                    path, s.fps, s.iso, exposureNs, s.focusDiopters, s.kelvin, s.tint,
                )
                if (ok) {
                    recordStartMs = System.currentTimeMillis()
                    _uiState.update {
                        it.copy(recording = true, elapsedSeconds = 0, written = 0, dropped = 0)
                    }
                    withContext(Dispatchers.Main) { startPolling() }
                } else {
                    _events.tryEmit("Failed to start recording")
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        // Dispatchers.Default, not Main: nativeGetStats() is a JNI call, and
        // _uiState.update is a thread-safe StateFlow write, so there's no reason to
        // tie up the UI dispatcher for this every 500ms while recording.
        pollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500)
                val stats = NativeBridge.nativeGetStats()
                val elapsed = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
                _uiState.update { it.copy(written = stats[0], dropped = stats[1], elapsedSeconds = elapsed) }
                // Max clip length (0 = off). stopRecordingInternal() itself cancels this
                // pollJob (see its `pollJob?.cancel()`), so the `break` below is
                // belt-and-braces against this loop iterating once more before that
                // cancellation is observed.
                val limit = _uiState.value.settings.maxClipLengthSeconds
                if (limit > 0 && elapsed >= limit && _uiState.value.recording) {
                    if (stopRecordingInternal()) _events.tryEmit("Auto-stopped: clip length limit")
                    break
                }
            }
        }
    }

    /** Returns true iff this call actually proceeded to stop the recording (false
     * when the atomic guard below rejected it as a duplicate/racing call) --
     * callers use this to decide whether to emit their own "stopped because X"
     * cause toast, so a guard-rejected duplicate stop no longer emits a second,
     * misleading cause toast alongside the first stop's real one. */
    private fun stopRecordingInternal(): Boolean {
        // Atomic re-entry guard: this function has three uncoordinated callers
        // (toggleRecord on the main thread, thermalListener on mainExecutor, and the
        // max-length check on the Dispatchers.Default poll loop) that can each fire
        // while a prior stop is still in flight -- `recording` stays true until the
        // cameraOps stop task below actually completes, and thermal status escalating
        // SEVERE -> CRITICAL -> EMERGENCY fires the listener once per transition.
        // getAndUpdate performs the "recording && !busy" check and the busy=true set
        // as one atomic StateFlow operation, so two near-simultaneous callers can't
        // both observe busy==false and both proceed: the loser sees prev.busy == true
        // (or prev.recording == false) and returns before touching pollJob or
        // cameraOps. Without this guard, a second controller.stopRecording() call
        // would queue behind the first (idempotent, returns [0, 0] per its kdoc), and
        // its completion block would then overwrite the just-written real
        // written/dropped stats with zeros and emit a misleading "0 frames, 0 dropped"
        // toast.
        val prev = _uiState.getAndUpdate { if (it.recording && !it.busy) it.copy(busy = true) else it }
        if (!prev.recording || prev.busy) return false
        pollJob?.cancel()
        pollJob = null
        cameraOps.launch {
            try {
                val stats = controller.stopRecording()
                _uiState.update {
                    it.copy(recording = false, written = stats[0], dropped = stats[1])
                }
                if (stats[0] == 0L && stats[1] > 0L) {
                    _events.tryEmit("Recording failed: writer error")
                } else {
                    _events.tryEmit("${stats[0]} frames, ${stats[1]} dropped")
                }
                // Auto-export: only on a genuinely successful stop (stats[0] > 0 --
                // at least one frame was written), and only reachable through THIS
                // completion block -- the atomic re-entry guard above (getAndUpdate)
                // rejects a duplicate/racing stop call before it ever reaches
                // controller.stopRecording(), so a guard-rejected duplicate can never
                // double-start an export here. If both autoExport and deleteAfterExport
                // are on, the clip just recorded is exported and then its source .rawv
                // is deleted by ExportService once the export completes successfully
                // (see ExportService.onStartCommand).
                if (stats[0] > 0) {
                    val st = _uiState.value.settings
                    if (st.autoExport) {
                        lastClipName?.let { name ->
                            val app = getApplication<Application>()
                            val rawvPath = File(controller.clipsDir, name).absolutePath
                            val baseName = name.removeSuffix(".rawv")
                            val outDir = File(app.getExternalFilesDir(null), "exports/$baseName").absolutePath
                            ExportService.start(app, rawvPath, outDir, baseName, st.deleteAfterExport)
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
        return true
    }

    /**
     * Called from MainActivity.onStop() when a recording is in progress: finalizes the
     * clip off the main thread. Trade-off (deliberate): onStop returns immediately and
     * the stop runs on [cameraOps] -- serialization guarantees the finalize completes
     * before any subsequent reopen (surfaceCreated on return) or close (onCleared)
     * touches the camera, and the native writer additionally guards partial teardown.
     * The controller is NOT closed here: close() is one-shot (it quits the camera
     * HandlerThread), so closing on backgrounding would permanently kill the camera
     * for the rest of the process. close() is reserved for onCleared().
     */
    fun handleActivityStop() {
        if (!_uiState.value.recording) return
        pollJob?.cancel()
        pollJob = null
        _uiState.update { it.copy(recording = false, busy = true) }
        cameraOps.launch {
            try {
                val stats = controller.stopRecording()
                _uiState.update { it.copy(written = stats[0], dropped = stats[1]) }
                _events.tryEmit("${stats[0]} frames, ${stats[1]} dropped")
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.removeThermalStatusListener(thermalListener)
        // Off-main and serialized after any in-flight stop; cameraOps outlives
        // viewModelScope precisely so this final close cannot be cancelled.
        cameraOps.launch { controller.close() }
    }

    companion object {
        private const val TAG = "RecordViewModel"
        val FPS_OPTIONS = listOf(24, 30, 48, 60)
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun formatTimer(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Bytes per recorded frame record (packed payload + 64-byte FrameMeta), mirroring
 * capture.cpp's pack-mode choice exactly: Packed10 (1.25 B/px) only for a <=10-bit
 * white level on a width divisible by 4, Packed12 (1.5 B/px) for <=12-bit on an
 * even width, else Raw16 (2 B/px -- the true row stride isn't known until the
 * first frame arrives, so this is a floor; stride padding can only add to it).
 * Was hardcoded to the Packed10 formula, which under-reserved free space ~20% on
 * a 12-bit sensor and ~60% on anything recording Raw16.
 */
private fun frameRecordBytes(spec: CameraController.RawSpec): Long {
    val pixels = spec.width.toLong() * spec.height
    val payload = when {
        spec.whiteLevel <= 0x3FF && spec.width % 4 == 0 -> (pixels / 4) * 5
        spec.whiteLevel <= 0xFFF && spec.width % 2 == 0 -> (pixels / 2) * 3
        else -> pixels * 2
    }
    return payload + 64
}

/** Free space -> recordable time at the current fps/frame size ("~23 min"). */
private fun remainingLabel(freeBytes: Long, fps: Int, spec: CameraController.RawSpec): String {
    val frameBytes = frameRecordBytes(spec)
    val perSecond = frameBytes * fps
    if (perSecond <= 0) return "—"
    val seconds = freeBytes / perSecond
    return when {
        seconds >= 6000 -> "99+ min"
        seconds >= 120 -> "~${seconds / 60} min"
        else -> "~$seconds s"
    }
}

// Standard 1/3-stop photographic ISO scale (vs the old full-stop-only list) for
// much finer manual control.
private val NICE_ISO_STOPS = listOf(
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
    2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
    32000, 40000, 51200, 64000, 80000, 102400,
)

/** Standard full-stop ISO values within [range], with the range's true endpoints spliced in
 *  so the slider's ends always reach what the lens actually supports. */
private fun isoStops(range: ClosedRange<Int>): List<Int> =
    (NICE_ISO_STOPS.filter { it in range } + range.start + range.endInclusive)
        .distinct().sorted()

private val NICE_FOCUS_METERS = listOf(10f, 5f, 3f, 2f, 1f, 0.5f, 0.3f)

/** Friendly focus-distance stops (infinity first) converted to diopters, clamped so the
 *  macro-end stop is whatever the lens actually supports rather than a fixed distance. */
private fun focusStops(minFocusDiopters: Float): List<Float> {
    if (minFocusDiopters <= 0f) return listOf(0f, 0f)
    val within = NICE_FOCUS_METERS.map { 1f / it }.filter { it < minFocusDiopters }
    return (listOf(0f) + within + minFocusDiopters).distinct().sorted()
}

private fun focusLabel(diopters: Float): String {
    if (diopters <= 0f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) "%.0fm".format(meters) else "%.0fcm".format(meters * 100f)
}

/** Shutter-speed label per [Settings.shutterDisplay]: a plain fraction of a second
 *  ("1/48"), or the film/video shutter angle in degrees derived from [fps] and [denom]
 *  ("180°" for 24fps at 1/48s: `360 * 24 / 48 = 180`). Storage stays the shutter
 *  denominator throughout -- this is display-only, applied at the two read sites (the
 *  SHUTTER chip and the shutter panel's slider labels). */
private fun shutterLabel(denom: Int, fps: Int, mode: ShutterDisplay) = when (mode) {
    ShutterDisplay.FRACTION -> "1/$denom"
    ShutterDisplay.ANGLE -> "${(360f * fps / denom).roundToInt()}°"
}

/** Single call-site wrapper around the [Offset] factory function -- the grid overlay's
 *  DrawScope block below constructs several Offsets from plain (non-constant) Float
 *  pairs in the same lexical block; routing them all through one named call site here
 *  avoids a resolution issue where repeated inline `Offset(x, y)` construction sites in
 *  the same block were only correctly resolving on the first occurrence. */
private fun gridPoint(x: Float, y: Float): Offset = Offset(x, y)

// internal (not private): reused by SettingsScreen.kt's SliderRow for the
// default-white-balance / default-tint settings, which use the same stop lists.
// 100K steps across the full range (vs the old ~10 coarse presets) for finer control.
internal val KELVIN_STOPS = (2000..10000 step 100).toList()
internal val TINT_STOPS = (-50..50 step 2).toList()

private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS, WB }

@Composable
fun RecordScreen(
    viewModel: RecordViewModel = viewModel(),
    clipsEnabled: Boolean = true,
    onOpenClips: () -> Unit = {},
    exportsEnabled: Boolean = true,
    onOpenExports: () -> Unit = {},
    settingsEnabled: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        // Covers the fresh-install/just-denied case: the ViewModel's own init{}
        // call to ensureCameraInitialized() was a no-op without permission (see
        // its kdoc), so nothing starts camera enumeration until it's retried here.
        if (granted) viewModel.ensureCameraInitialized()
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        // Matches the rest of the app's flat bordered-pill / accent-fill language
        // (NavButton, ShutterButton) instead of a stock M3 Button -- the default
        // Button pulls in Material's capsule shape and elevation shadow, the only
        // place in the app that would have looked like generic Material chrome
        // rather than RawCam's own control-panel look, and it's also the very
        // first thing a fresh install shows.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "CAMERA ACCESS NEEDED", color = RawCamColors.OnSurface,
                    fontSize = 15.sp, letterSpacing = 1.5.sp,
                )
                Text(
                    "RawCam needs your camera to show a preview and record RAW video.",
                    color = RawCamColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                Surface(color = RawCamColors.Accent, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "GRANT ACCESS", color = Color.White, fontSize = 13.sp, letterSpacing = 1.sp,
                        modifier = Modifier
                            .clickable { launcher.launch(Manifest.permission.CAMERA) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
        return
    }

    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // Auto-export starts ExportService straight from the record flow, but the
    // runtime POST_NOTIFICATIONS ask lived only in ClipsScreen -- a user who
    // records with autoExport on and never opens Clips would get invisible
    // exports (the service still runs; its progress notification is just never
    // shown). Ask here the first time the setting is observed enabled.
    val notificationPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.settings.autoExport) {
        if (state.settings.autoExport &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Free-space poll lives here (not in the viewmodel) so it's lifecycle-gated:
    // repeatOnLifecycle cancels the loop below STARTED (backgrounded) and restarts
    // it on return to the foreground, instead of spinning a StatFs + uiState.update
    // every 2s regardless of visibility.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refreshFreeSpace()
                delay(2000)
            }
        }
    }

    val spec = state.rawSpec
    if (spec == null) {
        // Camera enumeration (binder IPC, off-main) hasn't published lenses/rawSpec
        // into uiState yet. Nothing controller-derived can render safely before
        // that -- mirrors the previewReady gating idiom below, one step earlier.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Loading camera…", color = RawCamColors.Muted, fontSize = 13.sp)
        }
        return
    }
    val lenses = state.lenses
    val lens = lenses.getOrElse(state.lensIndex) { lenses[0] }
    val sizes = lens.sizes
    val selectedSize = sizes.getOrElse(state.sizeIndex) { sizes[0] }
    val shutterStops = viewModel.shutterStops(state.fps)
    val shutterDenom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
    val modeEnabled = !state.recording && !state.busy
    // AUTO_ONLY lenses (no MANUAL_SENSOR capability, or no usable ISO range) don't
    // offer a real manual ISO/shutter/focus control; disabling-because-absent must
    // read differently from disabling-because-locked (see Task 8 in the plan) --
    // the sliders below stay disabled and swap their body copy instead of showing
    // a lock, since there is nothing to unlock.
    val manualAvailable = state.controlTier == ControlTier.FULL
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Param?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Letterboxed preview at the sensor's true aspect ratio; the side gutters
        // that creates host the status/action rails. Keyed on the selected mode:
        // changing lens or resolution recreates the SurfaceView, and the fresh
        // surfaceCreated -> openCamera reopens the camera with the new physical
        // lens and spec (same path as returning from background).
        //
        // This Box matches the SurfaceView's visible bounds exactly (same
        // .align/.fillMaxHeight/.aspectRatio chain as the AndroidView below), so
        // the tap gesture and reticle normalize/draw against the actual preview
        // rect rather than the full-bleed outer Box. Taps landing in the side
        // gutters (which host the control rails) fall outside this Box and never
        // reach detectTapGestures -- no metering is triggered, which is intended.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .aspectRatio(spec.width.toFloat() / spec.height.toFloat())
                .pointerInput(state.previewReady, state.recording) {
                    detectTapGestures { offset ->
                        // A tap while a slider panel is open is a dismissal, not a
                        // meter request -- close the panel and do NOT re-meter (that
                        // would silently overwrite whatever the user just hand-set).
                        if (expanded != null) {
                            expanded = null
                        } else if (state.previewReady && !state.recording) {
                            viewModel.meterAt(offset.x / this.size.width.toFloat(), offset.y / this.size.height.toFloat())
                        }
                    }
                }
        ) {
            key(state.lensIndex, state.sizeIndex) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    viewModel.openCamera(holder.surface)
                                }
                                override fun surfaceChanged(
                                    holder: SurfaceHolder, format: Int, width: Int, height: Int,
                                ) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {}
                            })
                        }
                    },
                )
            }

            // Rule-of-thirds grid: composed above the SurfaceView, below the reticle,
            // and OUTSIDE the pointerInput chain (a bare Canvas has no pointerInput of
            // its own), so it never intercepts the tap-to-meter gesture attached to the
            // enclosing Box above -- purely a paint layer.
            if (state.settings.gridEnabled) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Color.White.copy(alpha = 0.30f)
                    val strokeWidth = 1.dp.toPx()
                    val w = this.size.width
                    val h = this.size.height
                    for (f in listOf(1f / 3f, 2f / 3f)) {
                        val x = w * f
                        val y = h * f
                        drawLine(c, gridPoint(x, 0f), gridPoint(x, h), strokeWidth)
                        drawLine(c, gridPoint(0f, y), gridPoint(w, y), strokeWidth)
                    }
                }
            }

            // Horizon level: only composed (and its sensor listener only registered)
            // while the setting is on -- see HorizonLevel's kdoc for the sensor
            // lifecycle. Non-interactive, same reasoning as the grid above.
            if (state.settings.levelEnabled) {
                HorizonLevel(Modifier.align(Alignment.Center), debugLogging = state.settings.debugLogging)
            }

            // Tap-to-meter reticle: grey while converging, green briefly after, at the
            // normalized tap point mapped back into this Box's pixel dimensions.
            state.meterPoint?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    val cx = p.x * this.size.width
                    val cy = p.y * this.size.height
                    val r = 36.dp.toPx()
                    val c = if (state.metering) Color(0xFFE0E0E0) else Color(0xFF7CFF7C)
                    drawRect(c, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(width = 3.dp.toPx()))
                }
            }
        }

        // displayCutoutPadding (on top of systemBarsPadding) keeps the overlay
        // controls clear of the camera punch-hole. In landscape this device
        // reserves the left 48dp (168px) for a vertically-centered cutout, which
        // sat directly over the left status rail; systemBarsPadding alone doesn't
        // account for the cutout, so the timer/frames text was under the hole.
        // Only the overlay is inset -- the preview underneath still fills the edge.
        Box(Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding()) {
            if (state.thermalSevere) {
                Surface(
                    color = RawCamColors.Accent,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                ) {
                    Text(
                        "THERMAL WARNING — device overheating",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    )
                }
            }

            // Benchmark (Task 8) + Settings entry, reachable but out of the way in the
            // top-left gutter. BENCH disabled while recording: its ~6 GB write would
            // compete with the ~376 MB/s capture hot path and force drops. SETTINGS
            // disabled while recording/busy (settingsEnabled, from MainActivity's
            // `locked`) -- leaving Record mid-recording would dispose the SurfaceView
            // and stall the RAW stream, same reasoning as CLIPS below. BENCH itself is
            // gated on Settings.showBench -- SETTINGS always renders below it (or alone,
            // at the same TopStart slot) regardless of that toggle, since it's the only
            // way back into the settings screen that turned it off.
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.settings.showBench) {
                    NavButton(
                        text = if (benchRunning) "…" else "BENCH",
                        enabled = !state.recording && !state.busy,
                        onClick = {
                            if (benchRunning) return@NavButton
                            benchRunning = true
                            scope.launch {
                                val mbps = withContext(Dispatchers.IO) {
                                    val path = File(context.getExternalFilesDir(null), "bench.bin").absolutePath
                                    NativeBridge.nativeBenchmarkWrite(path, 25_000_000, 240)
                                }
                                benchRunning = false
                                snackbarHostState.showSnackbar("Bench: %.0f MB/s".format(mbps))
                            }
                        },
                    )
                }
                NavButton(text = "SETTINGS", enabled = settingsEnabled, onClick = onOpenSettings)
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NavButton(text = "EXPORTS", enabled = exportsEnabled, onClick = onOpenExports)
                NavButton(text = "CLIPS", enabled = clipsEnabled, onClick = onOpenClips)
            }

            // Left status rail, gated on Settings.showStatsSidebar. The action rails
            // (right, bottom) and the top gutter buttons don't read anything from this
            // column, so hiding it is a pure subtraction -- nothing else in the layout
            // depends on it being present (each surviving element is independently
            // aligned/positioned against the outer Box, not against this Column).
            if (state.settings.showStatsSidebar) {
                Column(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Idle, the timer reads 00:00 and frames-written/dropped are both
                    // 0 -- three dead readouts stacked down the left edge. Show the
                    // live capture stats only while recording (when they mean
                    // something); idle keeps just the one useful readout, space
                    // remaining. This is the bulk of the left-side declutter.
                    if (state.recording) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RecDot()
                            Text(
                                formatTimer(state.elapsedSeconds),
                                color = RawCamColors.OnSurface, fontSize = 24.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        StatItem("${state.written}", "frames written")
                        StatItem(
                            "${state.dropped}", "dropped",
                            valueColor = if (state.dropped > 0) RawCamColors.Accent else RawCamColors.Success,
                        )
                    }
                    StatItem(
                        remainingLabel(state.freeSpaceBytes, state.fps, spec),
                        "space remaining",
                    )
                }
            }

            // Right action rail.
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ShutterButton(
                    recording = state.recording,
                    enabled = state.previewReady && !state.busy,
                    onClick = { viewModel.toggleRecord() },
                )
                FpsToggle(
                    options = viewModel.fpsOptions(spec),
                    selected = state.fps,
                    enabled = !state.recording,
                    onSelect = { viewModel.setFps(it) },
                )
            }

            // Bottom: one expandable slider + the parameter chips.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).width(400.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                expanded?.let { param ->
                    Surface(
                        color = Color(0xD90A0B0D), shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RawCamColors.Outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            when (param) {
                                Param.LENS -> {
                                    ParamLabel("LENS")
                                    OptionPills(
                                        labels = lenses.map { it.label },
                                        selectedIndex = state.lensIndex,
                                        enabled = modeEnabled,
                                        onSelect = { viewModel.setLens(it) },
                                    )
                                }
                                Param.RES -> {
                                    ParamLabel("RESOLUTION")
                                    OptionPills(
                                        labels = sizes.map { it.label },
                                        selectedIndex = state.sizeIndex,
                                        enabled = modeEnabled,
                                        onSelect = { viewModel.setResolution(it) },
                                    )
                                }
                                Param.ISO -> {
                                    ParamLabel("ISO")
                                    TickedSlider(
                                        stops = isoStops(spec.isoRange),
                                        selected = state.iso,
                                        labelFor = { "$it" },
                                        enabled = manualAvailable,
                                        locked = state.isoLocked,
                                        onToggleLock = { viewModel.toggleIsoLock() },
                                        onSelect = { viewModel.setIso(it) },
                                    )
                                }
                                Param.SHUTTER -> {
                                    ParamLabel("SHUTTER")
                                    TickedSlider(
                                        stops = shutterStops,
                                        selected = shutterDenom,
                                        labelFor = { shutterLabel(it, state.fps, state.settings.shutterDisplay) },
                                        enabled = manualAvailable,
                                        locked = state.shutterLocked,
                                        onToggleLock = { viewModel.toggleShutterLock() },
                                        onSelect = { viewModel.setShutterIndex(shutterStops.indexOf(it)) },
                                    )
                                }
                                Param.FOCUS -> {
                                    ParamLabel("FOCUS")
                                    TickedSlider(
                                        stops = focusStops(spec.minFocusDiopters),
                                        selected = state.focusDiopters,
                                        labelFor = ::focusLabel,
                                        enabled = manualAvailable && spec.minFocusDiopters > 0f,
                                        locked = state.focusLocked,
                                        onToggleLock = { viewModel.toggleFocusLock() },
                                        onSelect = { viewModel.setFocus(it) },
                                    )
                                }
                                Param.WB -> {
                                    ParamLabel("WHITE BALANCE")
                                    TickedSlider(
                                        stops = KELVIN_STOPS,
                                        selected = state.kelvin,
                                        labelFor = { "${it}K" },
                                        locked = state.kelvinLocked,
                                        onToggleLock = { viewModel.toggleKelvinLock() },
                                        onSelect = { viewModel.setKelvin(it) },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TickedSlider(
                                        stops = TINT_STOPS,
                                        selected = state.tint,
                                        labelFor = { if (it > 0) "+$it" else "$it" },
                                        locked = state.tintLocked,
                                        onToggleLock = { viewModel.toggleTintLock() },
                                        onSelect = { viewModel.setTint(it) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!manualAvailable) {
                    // Absent, not locked: no lock icon here -- this lens never had a
                    // manual control to unlock, unlike isoLocked/shutterLocked/focusLocked
                    // above, which freeze a real value the user could still see and set.
                    Text(
                        "This lens records RAW with automatic exposure",
                        color = RawCamColors.Muted, fontSize = 12.sp,
                    )
                }
                val chipScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .horizontalScroll(chipScroll)
                        .horizontalFadingEdge(chipScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParamChip(lens.label, expanded == Param.LENS, enabled = modeEnabled) {
                        expanded = if (expanded == Param.LENS) null else Param.LENS
                    }
                    ParamChip(selectedSize.label, expanded == Param.RES, enabled = modeEnabled) {
                        expanded = if (expanded == Param.RES) null else Param.RES
                    }
                    ParamChip("ISO ${state.iso}", expanded == Param.ISO) {
                        expanded = if (expanded == Param.ISO) null else Param.ISO
                    }
                    ParamChip(shutterLabel(shutterDenom, state.fps, state.settings.shutterDisplay), expanded == Param.SHUTTER) {
                        expanded = if (expanded == Param.SHUTTER) null else Param.SHUTTER
                    }
                    ParamChip("ƒ ${focusLabel(state.focusDiopters)}", expanded == Param.FOCUS) {
                        expanded = if (expanded == Param.FOCUS) null else Param.FOCUS
                    }
                    ParamChip("${state.kelvin}K", expanded == Param.WB) {
                        expanded = if (expanded == Param.WB) null else Param.WB
                    }
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Horizon-tilt indicator drawn over the preview. Registers a
 * [Sensor.TYPE_ROTATION_VECTOR] listener at [SensorManager.SENSOR_DELAY_UI] for exactly
 * as long as this composable stays in composition; the caller (RecordScreen) only
 * composes it while `state.settings.levelEnabled` is true, so toggling the setting off
 * -- or leaving the Record screen entirely, which removes this composable from the tree
 * -- runs [DisposableEffect]'s onDispose and unregisters the listener. Devices with no
 * rotation-vector sensor (`getDefaultSensor` returns null) register nothing; `roll`
 * simply stays at its initial 0f and the widget draws a level (green) line rather than
 * crashing.
 *
 * Roll math, computed directly from [SensorManager.getRotationMatrixFromVector]'s
 * device-to-world matrix R rather than through [SensorManager.getOrientation]'s
 * azimuth/pitch/roll decomposition: column 0 of R (R[0],R[3],R[6]) is the device's
 * physical +X axis (portrait-natural "right edge") expressed in world coordinates, and
 * column 1 (R[1],R[4],R[7]) is the device's +Y axis ("top edge"); the world-up unit
 * vector (0,0,1) projected onto those two columns is (R[6], R[7]) -- "world up",
 * decomposed into the device's own screen-plane basis. For this app's fixed `landscape`
 * lock (AndroidManifest.xml; not sensorLandscape/reverseLandscape), holding the device
 * to view it upright puts portrait-natural physical +X (the right edge) at the top of
 * the frame and +Y (the top edge) at the frame's right, so "world up" in the *frame's*
 * basis is (contentRight, contentUp) = (R[7], R[6]); atan2(R[7], R[6]) is 0 exactly
 * when the frame is level, and its sign is such that the line drawn with
 * `.rotate(-roll)` below counter-rotates against the physical tilt. EMPIRICALLY
 * DERIVED end to end, not assumed -- two earlier guesses were each wrong in a
 * different way and both caught on a physical Pixel 7 Pro rather than shipped: (1) the
 * original pre-fix getOrientation()-based formula was off by ~90 degrees (line
 * rendered near-vertical at true level; the `abs(roll) <= 0.5f` green threshold could
 * never be met); (2) a first rewrite guessed the opposite axis polarity
 * (contentUp = -R[6]) from first principles and read ~166 degrees during a moderate
 * intentional tilt (raw capture: R[6]=0.963, R[7]=0.230) -- fixed by deriving the axis
 * pairing from that raw data instead of a second guess, which gave a correctly-scaled
 * ~9-degree reading and turned the line green at rest, but still rotated WITH the
 * phone instead of against it; the sign flip here (R[7] alone, not the axis pairing)
 * is fix (3), confirmed 2026-07-19 by rolling the device continuously through level in
 * both directions: the line counter-rotates to stay visually parallel with real-world
 * horizontals (table/shelf edges) and is green only at true level.
 */
@Composable
private fun HorizonLevel(modifier: Modifier = Modifier, debugLogging: Boolean = false) {
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                roll = Math.toDegrees(atan2(r[7].toDouble(), r[6].toDouble())).toFloat()
                if (debugLogging) Log.i("HorizonLevel", "R6=${r[6]} R7=${r[7]} roll=$roll")
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            if (sensor != null) sensorManager.unregisterListener(listener)
        }
    }

    val lineColor = if (abs(roll) <= 0.5f) Color.Green else Color.White.copy(alpha = 0.6f)
    Box(modifier, contentAlignment = Alignment.Center) {
        // Fixed center reference tick -- does not rotate; the moving line below
        // visually overlaps it exactly when the device is level.
        Box(Modifier.width(2.dp).height(24.dp).background(Color.White.copy(alpha = 0.6f)))
        Box(
            Modifier
                .width(120.dp)
                .height(2.dp)
                .rotate(-roll)
                .background(lineColor)
        )
    }
}

@Composable
private fun RecDot() {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Box(
        Modifier.size(12.dp).graphicsLayer { alpha = pulse }
            .background(RawCamColors.Accent, CircleShape)
    )
}

@Composable
private fun StatItem(value: String, label: String, valueColor: Color = RawCamColors.OnSurface) {
    Column {
        Text(value, color = valueColor, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = RawCamColors.Muted, fontSize = 11.sp)
    }
}

/** Round camera shutter: red circle idle, red rounded square while recording. */
@Composable
private fun ShutterButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val border = if (enabled) RawCamColors.OnSurface else RawCamColors.OnSurface.copy(alpha = 0.35f)
    Box(
        Modifier.size(76.dp)
            .border(4.dp, border, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(30.dp).background(RawCamColors.Accent, RoundedCornerShape(6.dp)))
        } else {
            val fill = if (enabled) RawCamColors.Accent else RawCamColors.Accent.copy(alpha = 0.4f)
            Box(Modifier.size(56.dp).background(fill, CircleShape))
        }
    }
}

@Composable
private fun FpsToggle(options: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp))
    ) {
        options.forEach { fps ->
            val on = fps == selected
            Box(
                Modifier
                    .clickable(enabled = enabled) { onSelect(fps) }
                    .background(if (on) RawCamColors.SurfaceVariant else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("$fps", color = if (on) RawCamColors.OnSurface else RawCamColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** Horizontal pill selector for the LENS / RESOLUTION panels (FpsToggle, by index). */
@Composable
private fun OptionPills(
    labels: List<String>, selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp))
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .clickable(enabled = enabled) { onSelect(i) }
                    .background(if (on) RawCamColors.SurfaceVariant else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    color = if (on) RawCamColors.OnSurface else RawCamColors.Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * Top-corner screen-navigation button (BENCH/SETTINGS/EXPORTS/CLIPS) -- a bordered
 * pill in the same visual language as [ParamChip] below (which these four buttons
 * previously did NOT share: they were bare, unbordered [TextButton]s, and BENCH/
 * SETTINGS hardcoded a literal [RawCamColors.Muted] text color that never actually
 * dimmed further when disabled, unlike EXPORTS/CLIPS's manual enabled-check --
 * so of four visually-equivalent nav buttons, two looked permanently washed-out
 * and gave no visual feedback when locked during a recording). Alpha-on-the-whole-
 * Surface, not a hardcoded per-branch Text color, so disabled always reads
 * consistently no matter which button.
 */
@Composable
private fun NavButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = Color(0xB80A0B0D),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, RawCamColors.Outline),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text, color = RawCamColors.OnSurface, fontSize = 11.sp, letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            // A fixed floor (sized to fit "SETTINGS", the longest of the four nav
            // labels) rather than each pill hugging its own text -- BENCH/CLIPS
            // were visibly narrower than SETTINGS/EXPORTS, so the two stacked/
            // paired buttons never lined up. All four now share one width, so the
            // spacing between them reads as even instead of ragged.
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .defaultMinSize(minWidth = 96.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Fades this composable's OWN rendered content to transparent near whichever
 * scrollable edges of [scrollState] still have more content off-screen --
 * distinct from a fixed dark-gradient scrim, which would look like a smudge
 * over this app's chip row (drawn directly atop the live camera preview, with
 * no background of its own). [BlendMode.DstIn] only multiplies this
 * modifier's own already-drawn pixels' alpha, so nothing new is painted over
 * the preview -- the chips themselves simply dissolve toward the edge,
 * revealing whatever is behind them (as they always would once scrolled
 * past), which is the same visual language a native scrollable list uses.
 */
private fun Modifier.horizontalFadingEdge(scrollState: ScrollState, edge: androidx.compose.ui.unit.Dp = 24.dp) =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edgePx = edge.toPx().coerceAtMost(size.width / 2f)
            if (scrollState.value > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black), startX = 0f, endX = edgePx,
                    ),
                    size = Size(edgePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (scrollState.value < scrollState.maxValue) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - edgePx, endX = size.width,
                    ),
                    topLeft = Offset(size.width - edgePx, 0f),
                    size = Size(edgePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

@Composable
private fun ParamChip(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = Color(0xB80A0B0D),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (active) RawCamColors.Accent else RawCamColors.Outline),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text, color = RawCamColors.OnSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(text, color = RawCamColors.Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
}

/** Small bordered pill toggling a slider's lock state -- same visual language as
 * [ParamChip]/[NavButton] (dark surface, border color carries the state). While
 * locked the owning [TickedSlider] disables its drag AND meterAt() skips that
 * field on every tap-to-meter, regardless of MeterScope. */
@Composable
private fun LockToggle(locked: Boolean, onClick: () -> Unit) {
    Surface(
        color = Color(0xB80A0B0D),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (locked) RawCamColors.Accent else RawCamColors.Outline),
    ) {
        Text(
            if (locked) "LOCKED" else "LOCK",
            color = if (locked) RawCamColors.Accent else RawCamColors.Muted,
            fontSize = 10.sp, letterSpacing = 1.sp,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Discrete slider fixing the two concrete complaints about the old bare sliders:
 * endpoint labels give a scale reference, and snapping to [stops] makes it
 * possible to land on exact values. [stops] must have at least 2 entries.
 *
 * [locked]/[onToggleLock] are opt-in (default null skips the lock row entirely,
 * e.g. SettingsScreen.kt's SliderRow, which has no meter to guard against): when
 * provided, a [LockToggle] + the current value ([labelFor] of [selected]) render
 * above the slider itself, and the slider is force-disabled while locked so the
 * only way to change a locked value is to unlock it first.
 */
// internal (not private): reused by SettingsScreen.kt's SliderRow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> TickedSlider(
    stops: List<T>, selected: T, labelFor: (T) -> String,
    enabled: Boolean = true, locked: Boolean = false, onToggleLock: (() -> Unit)? = null,
    onSelect: (T) -> Unit,
) {
    val maxIndex = (stops.size - 1).coerceAtLeast(0)
    val index = stops.indexOf(selected).coerceAtLeast(0)
    // SliderState (not the classic value=/onValueChange= Slider) is what's needed to
    // get a `thumb` slot at all -- the classic overload has no such slot, and the
    // thumb's exact x-position (needed to place ValueBubble precisely above it,
    // rather than guessing at Material3's internal thumb-inset math) is only
    // available this way.
    val sliderState = remember(maxIndex) {
        SliderState(value = index.toFloat(), steps = (stops.size - 2).coerceAtLeast(0), valueRange = 0f..maxIndex.toFloat())
    }
    // One-way sync IN: keep the slider's position current when `selected` changes
    // for a reason other than this slider's own drag (tap-to-meter, lens-switch
    // clamping in coerceToMode, lock toggles reverting a value). Guarded so this
    // doesn't fight the drag-originated write below.
    LaunchedEffect(index) {
        if (sliderState.value != index.toFloat()) sliderState.value = index.toFloat()
    }
    // One-way sync OUT: SliderState has no onValueChange callback (unlike the
    // classic overload this replaced) -- observing .value via snapshotFlow is the
    // supported way to react live to drag, matching the old live-while-dragging
    // behavior (not just on release).
    LaunchedEffect(sliderState, stops) {
        snapshotFlow { sliderState.value }.collect { v ->
            val newSelected = stops.getOrNull(v.roundToInt().coerceIn(0, maxIndex)) ?: return@collect
            if (newSelected != selected) onSelect(newSelected)
        }
    }
    Column {
        if (onToggleLock != null) {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LockToggle(locked = locked, onClick = onToggleLock)
                Text(
                    labelFor(selected), color = RawCamColors.OnSurface,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                labelFor(stops.first()), color = RawCamColors.Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
            val thumbInteraction = remember { MutableInteractionSource() }
            Slider(
                state = sliderState,
                enabled = enabled && !locked,
                modifier = Modifier.weight(1f),
                thumb = { state ->
                    val thumbValue = state.value.roundToInt().coerceIn(0, maxIndex)
                    // ValueBubble is positioned via offset (a draw-time shift), NOT stacked
                    // in a Column above the thumb -- stacking made the thumb SLOT itself
                    // taller, which the Slider then centered against the track, leaving a
                    // large dead black gap below the track to keep the taller slot
                    // symmetric. offset moves the bubble without changing the slot's
                    // measured size, so the Slider's overall height goes back to exactly
                    // what a plain thumb needs.
                    Box(contentAlignment = Alignment.Center) {
                        ValueBubble(
                            labelFor(stops.getOrElse(thumbValue) { selected }),
                            modifier = Modifier.offset(y = (-28).dp),
                        )
                        SliderDefaults.Thumb(interactionSource = thumbInteraction, enabled = enabled && !locked)
                    }
                },
                // Material3 1.3's default Track leaves a themed "thumbTrackGapSize" gap
                // (unpainted, background shows through) on each side of the thumb -- on
                // this dark panel background that gap reads as black bars flanking the
                // thumb. Zeroing it restores a continuous track, matching how the slider
                // looked before the value bubble was added.
                track = { state -> SliderDefaults.Track(sliderState = state, thumbTrackGapSize = 0.dp) },
            )
            Text(
                labelFor(stops.last()), color = RawCamColors.Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Small pill floating above a [TickedSlider]'s thumb showing its exact current
 * value -- placed in the slider's `thumb` slot so it tracks the real thumb
 * position exactly (no guessing at Material3's internal thumb-inset math). Always
 * visible (not just while dragging): the point is a same-glance value readout
 * right where the thumb is, not a drag-only tooltip. */
@Composable
private fun ValueBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xE60A0B0D),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, RawCamColors.Accent),
    ) {
        Text(
            text, color = RawCamColors.OnSurface,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
