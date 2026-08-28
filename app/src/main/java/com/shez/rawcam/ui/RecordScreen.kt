package com.shez.rawcam.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.RggbChannelVector
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.audio.AudioDeviceCatalog
import com.shez.rawcam.audio.AudioRecorder
import com.shez.rawcam.audio.AudioInputDevice
import com.shez.rawcam.audio.AudioResult
import com.shez.rawcam.audio.AudioStatus
import com.shez.rawcam.audio.MeterLevels
import com.shez.rawcam.camera.Camera2SnapshotSource
import com.shez.rawcam.camera.CameraController
import com.shez.rawcam.camera.CompatibilityReport
import com.shez.rawcam.camera.ControlTier
import com.shez.rawcam.camera.DeviceProfile
import com.shez.rawcam.camera.LensProfile
import com.shez.rawcam.camera.ShutterStops
import com.shez.rawcam.camera.UnsupportedReason
import com.shez.rawcam.camera.ZebraMask
import com.shez.rawcam.export.ExportService
import com.shez.rawcam.export.ExportPaths
import com.shez.rawcam.preview.PreviewService
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
    /** Measured bytes per frame as a FRACTION of the modelled uncompressed frame
     *  size, per lens/geometry/compression (see captureRateKey). Seeded from what
     *  earlier takes persisted and refreshed live during one. This is what lets the
     *  time-left readout tell the truth about compressed capture -- where the real
     *  frame size is scene- and sensor-dependent, so no formula can predict it -- and
     *  about every lens on the device rather than just the one last recorded with. */
    val captureRates: Map<String, Float> = emptyMap(),
    val lensIndex: Int = 0,
    val sizeIndex: Int = 0,
    val kelvin: Int = 5600,
    val tint: Int = 0,
    val controlTier: ControlTier = ControlTier.FULL,
    val exposureRangeNs: LongRange? = null,
    val unsupported: DeviceProfile.Unsupported? = null,
    val reportText: String = "",
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
    // Audio provenance of the most recently STOPPED take, published by
    // stopRecordingInternal's completion block from controller.lastAudioResult.
    // audioChannels feeds AudioMeter's stereo/mono layout; audioFailed drives its
    // "NO AUDIO" state (see AudioMeter.kt). Defaults assume mono/present so a
    // recordAudio-off session (no AudioResult ever published) never shows a false
    // failure.
    val audioChannels: Int = 1,
    val audioFailed: Boolean = false,
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

    /** Straight pass-through of the controller's mask -- deliberately NOT folded into
     * RecordUiState: it updates ~15x/second, and RecordUiState drives the whole
     * screen's recomposition. See RecordScreen's ZebraOverlay for how it is read. */
    val zebraMask: StateFlow<ZebraMask?> get() = controller.zebraMask

    /** Straight pass-through of the controller's audio peak-level flow, same
     * rationale as [zebraMask]: it updates continuously while recording and must
     * not be folded into [RecordUiState]. See RecordScreen's AudioMeter render. */
    val audioMeter: StateFlow<MeterLevels> get() = controller.audioMeter

    /** Straight pass-through of the controller's live audio status bits -- same
     * rationale as [audioMeter]. Drives AudioMeter's AUDIO DEGRADED state, which
     * (unlike NO AUDIO) must react while a take is still recording, not only
     * after it stops -- see the RULING item in the audio-recording fix wave. */
    val audioStatus: StateFlow<Int> get() = controller.audioStatus

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
    // The capture-rate measurement from the take in progress, handed from the poll
    // (Dispatchers.Default) to the stop completion (cameraOps) that persists it --
    // @Volatile for that cross-thread hand-off, unlike lastClipName above which stays
    // on cameraOps throughout.
    @Volatile private var pendingRateKey: String? = null
    @Volatile private var pendingRatio: Float = 0f
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
                controller.zebraHighlightEnabled = s.zebraHighlightEnabled
                controller.zebraShadowEnabled = s.zebraShadowEnabled
                previous = s
            }
        }
        viewModelScope.launch {
            SettingsRepository.captureRates.collect { stored ->
                // Merged the other way round -- anything measured in THIS session is
                // newer than the store, and DataStore re-emits the whole record on
                // every unrelated settings write, which would otherwise clobber the
                // measurement from the take currently running.
                _uiState.update { it.copy(captureRates = stored + it.captureRates) }
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
            val result = controller.initialize()
            val report = CompatibilityReport.render(result, Build.MODEL, Build.VERSION.SDK_INT)
            if (result is DeviceProfile.Unsupported) {
                _uiState.update { it.copy(unsupported = result, reportText = report) }
                return@launch
            }
            _uiState.update { it.copy(reportText = report) }
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
            try {
                controller.clipsDir.mkdirs() // idempotent; StatFs needs the dir to exist
                StatFs(controller.clipsDir.absolutePath).availableBytes
            } catch (e: Exception) { 0L }
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

    /* ---- Audio, owned by Settings rather than CaptureState ---------------
     *
     * The AUDIO chip and the Settings screen are two views of the same three
     * SettingsRepository fields, so these write the repository and let the
     * settings collector above feed the value back into uiState.settings --
     * they deliberately do NOT touch _uiState directly (that would put a
     * second, briefly-disagreeing copy of the value on screen) and they do NOT
     * call persistCaptureState(), which is for per-take capture parameters.
     *
     * RecordScreen gates all three on `modeEnabled`, so none of them can land
     * mid-take: audio arming is read once when the take starts, and a chip
     * that changed after that would describe a file the take is not writing.
     */

    fun setRecordAudio(enabled: Boolean) {
        viewModelScope.launch { SettingsRepository.update { it.copy(recordAudio = enabled) } }
    }

    fun setAudioInput(key: String) {
        viewModelScope.launch { SettingsRepository.update { it.copy(audioInputKey = key) } }
    }

    fun setAudioGainDb(db: Float) {
        viewModelScope.launch { SettingsRepository.update { it.copy(audioGainDb = db) } }
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
                    zoomStop = controller.zoomIndex,
                    anchorR = anchor?.first?.red ?: 0f, anchorG = anchor?.first?.greenEven ?: 0f,
                    anchorB = anchor?.first?.blue ?: 0f, anchorKelvin = anchor?.second ?: 5600,
                )
            )
        }
    }

    fun toggleRecord() {
        val s = _uiState.value
        if (s.busy) return // debounce: a start/stop transition is already in flight
        // A tap-to-meter is mid-convergence (up to ~1.8s, blocking the camera
        // thread) -- starting a recording here would race its session
        // reconfiguration against meterAt's own repeating-request/session use.
        // Same guard meterAt() itself already applies before starting a new meter.
        if (s.metering) return
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
                val rateKey = captureRateKey(
                    s.lenses.getOrNull(s.lensIndex), spec, s.settings.compressRecordings,
                )
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
                    compressRecordings = s.settings.compressRecordings,
                    recordAudio = s.settings.recordAudio,
                    audioInputKey = s.settings.audioInputKey,
                    audioGainDb = s.settings.audioGainDb,
                )
                if (ok) {
                    recordStartMs = System.currentTimeMillis()
                    // controller.lastAudioResult is already populated by
                    // startRecording's own failure paths (permission denied, open
                    // failed, ...) by the time it returns -- read it HERE, in the
                    // same update that resets these fields for the new take,
                    // rather than waiting for stopRecordingInternal's completion
                    // block. Without this, a take whose audio failed to arm shows
                    // a flat, healthy-looking meter (audioFailed stays false) for
                    // its entire duration and NO AUDIO only appears after stop.
                    val audio = controller.lastAudioResult
                    val failed = s.settings.recordAudio && audio?.present == false
                    _uiState.update {
                        // audioChannels/audioFailed reset to their defaults here too --
                        // otherwise a failed take's audioFailed=true (or a stale channel
                        // count) would keep showing on the meter through an entirely new,
                        // healthy take until the NEXT stop overwrote it.
                        it.copy(
                            recording = true, elapsedSeconds = 0, written = 0, dropped = 0,
                            audioChannels = 1, audioFailed = failed,
                        )
                    }
                    withContext(Dispatchers.Main) { startPolling(File(path), frameBytes, rateKey) }
                } else {
                    _events.tryEmit("Failed to start recording")
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun startPolling(clipFile: File, modelledFrameBytes: Long, rateKey: String) {
        pollJob?.cancel()
        // Dispatchers.Default, not Main: nativeGetStats() is a JNI call, and
        // _uiState.update is a thread-safe StateFlow write, so there's no reason to
        // tie up the UI dispatcher for this every 500ms while recording.
        pollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500)
                val stats = NativeBridge.nativeGetStats()
                val elapsed = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
                // What this take actually costs per frame, against what the model
                // predicted. Measured per FRAME, not per second: that makes it
                // independent of fps and of dropped frames, and sidesteps the
                // truncated-seconds error a per-second rate carries early in a take.
                // length() is a stat syscall -- cheaper than the JNI hop above it.
                val frames = stats[0]
                val clipBytes = clipFile.length()
                val ratio = if (frames >= RATE_SETTLE_FRAMES && clipBytes > 0 && modelledFrameBytes > 0)
                    (clipBytes.toDouble() / (frames.toDouble() * modelledFrameBytes)).toFloat()
                        .takeIf { it.isFinite() && it in RATE_SANE_RANGE }
                else null
                if (ratio != null) { pendingRateKey = rateKey; pendingRatio = ratio }
                _uiState.update {
                    it.copy(
                        written = frames, dropped = stats[1], elapsedSeconds = elapsed,
                        captureRates = if (ratio != null) it.captureRates + (rateKey to ratio)
                        else it.captureRates,
                    )
                }
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
                val audio = controller.lastAudioResult
                if (_uiState.value.settings.recordAudio && audio != null) {
                    _uiState.update {
                        it.copy(audioChannels = audio.channels, audioFailed = !audio.present)
                    }
                    audioWarning(audio)?.let { _events.tryEmit(it) }
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
                // Persist what this take measured, so the readout is right for this
                // lens/geometry/compression from the next launch onwards -- including
                // before the first take on that combination in a session.
                val measuredKey = pendingRateKey
                if (stats[0] > 0 && measuredKey != null && pendingRatio > 0f) {
                    val measured = pendingRatio
                    viewModelScope.launch { SettingsRepository.saveCaptureRate(measuredKey, measured) }
                }
                if (stats[0] > 0) {
                    // Preview proxies. Enqueued only once the take has finished --
                    // developing frames is CPU-heavy and must never compete with an
                    // active capture (see PreviewService's kdoc).
                    lastClipName?.let { name ->
                        PreviewService.start(
                            getApplication(),
                            File(controller.clipsDir, name).absolutePath,
                            name,
                        )
                    }
                    val st = _uiState.value.settings
                    if (st.autoExport) {
                        lastClipName?.let { name ->
                            val app = getApplication<Application>()
                            val rawvPath = File(controller.clipsDir, name).absolutePath
                            val baseName = name.removeSuffix(".rawv")
                            val outDir = File(ExportPaths.exportsRootDir(app), baseName).absolutePath
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

    /** One short, honest cause for the user. Order matters: say why the clip has no
     * audio at all before saying its sync is merely degraded. Null when the take's
     * audio needs no warning (present, in sync, no flagged status bits). */
    private fun audioWarning(a: AudioResult): String? = when {
        a.status and AudioStatus.PERMISSION_DENIED != 0 -> "No audio: microphone permission denied"
        a.status and AudioStatus.OPEN_FAILED != 0 -> "No audio: could not open the input"
        a.status and AudioStatus.ENDED_EARLY != 0 -> "Audio ended early; clip is short on sound"
        a.status and AudioStatus.SUSPENDED != 0 -> "Audio sync unreliable: device slept mid-take"
        a.status and AudioStatus.OVERRUNS != 0 -> "Audio dropouts; sync may drift"
        a.status and AudioStatus.PADDED != 0 -> "Audio started late; head is padded with silence"
        a.status and AudioStatus.ALIGNMENT_UNVERIFIED != 0 -> "Audio alignment could not be verified"
        a.status and AudioStatus.DRIFT_HIGH != 0 -> "Audio clock drift ${a.driftPpm} ppm"
        a.status and AudioStatus.PROCESSED_SOURCE != 0 -> "Audio may be processed (UNPROCESSED unavailable)"
        else -> null
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
    /**
     * Captures this device's raw camera characteristics via a fresh
     * [Camera2SnapshotSource] and writes them to
     * `getExternalFilesDir(null)/snapshot-<model>.json` -- this is how real device
     * fixtures for [com.shez.rawcam.camera.GoldenFixtureTest] get produced. Runs on
     * [cameraOps]: [Camera2SnapshotSource.capture] is binder IPC, same as every other
     * camera call, and must never block the main thread. [onResult] is invoked back
     * on the main thread with the written [File] or the failure.
     */
    fun dumpCharacteristics(onResult: (Result<File>) -> Unit) {
        cameraOps.launch {
            val result = runCatching {
                val cameraManager = getApplication<Application>()
                    .getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val snapshot = Camera2SnapshotSource(cameraManager).capture()
                val dir = getApplication<Application>().getExternalFilesDir(null)
                    ?: error("no external files dir")
                val safeModel = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(dir, "snapshot-$safeModel.json").also { it.writeText(snapshot.toJson()) }
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

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

/**
 * Identity of the setup a capture-rate measurement belongs to: the lens (its sensor
 * and its noise floor decide how well frames compress -- an ultrawide and a tele on
 * the same phone do not compress alike), the geometry and bit depth, and whether
 * compression is on at all.
 *
 * fps is deliberately absent: what gets measured is bytes per FRAME, so a ratio
 * learned at 24fps is equally true at 30 and the readout never has to relearn it.
 */
private fun captureRateKey(lens: LensProfile?, spec: CameraController.RawSpec?, compress: Boolean): String =
    if (lens == null || spec == null) ""
    else "${lens.cameraId}|${spec.width}x${spec.height}|${spec.whiteLevel}|${if (compress) "c" else "r"}"

/** Frames a take must have written before its bytes-per-frame ratio means anything:
 *  the writer buffers, so early frames' bytes reach the file late and drag the ratio
 *  down. 48 = two seconds at 24fps. */
private const val RATE_SETTLE_FRAMES = 48L

/** A ratio outside this isn't a measurement, it's a half-written file or a bug.
 *  Slightly above 1.0 because an incompressible frame plus its header can exceed the
 *  modelled payload. */
private val RATE_SANE_RANGE = 0.02f..1.5f

/** "0:07", "12:34", "1:02:34" -- second-accurate, never rounded to whole minutes. */
private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Free space -> recordable time left, to the second ("12:34").
 *
 * Scales the modelled frame size by what this exact lens, geometry and compression
 * setting was last MEASURED to cost per frame (see [RecordUiState.captureRates]).
 * Those measurements persist, so switching to a lens you have recorded with before --
 * or turning compression back on -- gives a true number immediately rather than only
 * after another take. It matters most with compression on, where the real frame size
 * is data-dependent and well below the model, which otherwise understates how much
 * footage the card holds.
 *
 * With nothing measured for the current setup yet it falls back to the bare model.
 * That is a floor rather than an estimate (compression can only shrink a frame), so
 * it is marked "~" to say so; it firms up a couple of seconds into the next take.
 */
private fun remainingLabel(state: RecordUiState, spec: CameraController.RawSpec): String {
    // The WAV sidecar is a separate file, so it is outside the measured .rawv rate
    // and has to be added to both branches. 24-bit at 48 kHz = 144 kB/s per channel:
    // trivial against RAW, but free at this point.
    val audioPerSecond =
        if (state.settings.recordAudio) 3L * AudioRecorder.SAMPLE_RATE * state.audioChannels else 0L
    val ratio = state.captureRates[
        captureRateKey(state.lenses.getOrNull(state.lensIndex), spec, state.settings.compressRecordings)
    ]
    val frameBytes = (frameRecordBytes(spec) * (ratio ?: 1f).toDouble()).toLong()
    val perSecond = frameBytes * state.fps + audioPerSecond
    if (perSecond <= 0) return "—"
    val text = formatDuration(state.freeSpaceBytes / perSecond)
    return if (ratio == null) "~$text" else text
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

private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS, WB, AUDIO }

@Composable
fun RecordScreen(
    viewModel: RecordViewModel = viewModel(),
    clipsEnabled: Boolean = true,
    onOpenClips: () -> Unit = {},
    exportsEnabled: Boolean = true,
    onOpenExports: () -> Unit = {},
    settingsEnabled: Boolean = true,
    onOpenSettings: () -> Unit = {},
    /** Same contract SettingsScreen already takes -- enumerated lazily, because
     * AudioManager reports devices that come and go (USB-C, Bluetooth) and a list
     * captured at composition would go stale the moment a mic is plugged in. */
    audioInputs: () -> List<AudioInputDevice> = { emptyList() },
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

    // Mic permission, asked when the AUDIO chip is switched ON and never at
    // record time -- a permission dialog appearing as the user hits record is
    // how takes get lost (same rule the Settings toggle follows). Declared here,
    // beside the camera launcher and ABOVE the !hasPermission early return, so
    // both launchers occupy fixed positions in this composable's slot table.
    //
    // The result is intentionally ignored: a denial must not un-arm the setting
    // or block anything. It surfaces at record time as
    // AudioStatus.PERMISSION_DENIED, which lights the meter's NO AUDIO and
    // tints the chip -- video always wins, warn loudly.
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
    val meterLevels by viewModel.audioMeter.collectAsState()
    val audioStatusBits by viewModel.audioStatus.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // Keep the device awake for the duration of a take -- audio and RAW capture
    // both run for as long as the screen would otherwise allow before sleeping.
    val view = LocalView.current
    LaunchedEffect(state.recording) { view.keepScreenOn = state.recording }

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
    // every second regardless of visibility.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refreshFreeSpace()
                // 1s, not 2s: the readout below is second-accurate now, and a 2s
                // poll made it visibly tick down in jumps of two.
                delay(1000)
            }
        }
    }

    val unsupported = state.unsupported
    if (unsupported != null) {
        UnsupportedDeviceScreen(
            reason = when (unsupported.reason) {
                UnsupportedReason.NO_RAW_CAPABILITY -> "This phone's cameras don't provide RAW capture"
                UnsupportedReason.NO_USABLE_RAW_SIZES -> "This phone reports RAW but offers no usable RAW image size"
                UnsupportedReason.NO_BACK_CAMERA -> "No back-facing camera was found"
                UnsupportedReason.PERMISSION_REDACTED -> "Camera details are hidden until permission is granted"
            },
            detail = unsupported.detail,
            reportText = state.reportText,
        )
        return
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

    // UI-only predicate, deliberately wider than the header's
    // kAudioSyncInvalidating (which stays exactly as-is): if the read thread dies
    // mid-take, _meter freezes at its last emitted (non-silent) value and the
    // ticking peak-hold in AudioMeter settles onto it, so the HUD would otherwise
    // affirmatively show a steady, healthy-looking signal while the mic is
    // actually dead. ENDED_EARLY is folded in here, UI-side only, so "warn
    // loudly" holds even though ENDED_EARLY correctly stays out of the header's
    // sync contract (it means "stopped early", not "sync is off").
    //
    // Hoisted out of the AudioMeter call so the AUDIO chip's warn tint and the
    // meter's AUDIO DEGRADED label are one predicate rather than two that could
    // drift apart.
    val audioDegraded =
        (audioStatusBits and (AudioStatus.SYNC_INVALIDATING or AudioStatus.ENDED_EARLY)) != 0
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
            // The OR of zebraHighlightEnabled/zebraShadowEnabled joins the key, not
            // the two flags individually: CameraController's session gate is itself
            // an OR (either flag alone justifies the analysis stream), so keying on
            // both flags would force a session rebuild when one toggles while the
            // other is already on -- even though the output list doesn't actually
            // change. Recreating the SurfaceView drives surfaceCreated -> openCamera
            // -> openAndPreview, which rebuilds the session with (or without) the
            // analysis stream. Same proven path as a lens or resolution switch. Safe
            // to do unconditionally because the Settings screen is disabled while
            // recording, so this key cannot change mid-take.
            key(
                state.lensIndex, state.sizeIndex,
                state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled,
            ) {
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

            // Zebra stripes over clipped highlights and/or crushed shadows. Same
            // paint-layer-only reasoning as the grid above: no pointerInput of its
            // own, so tap-to-meter is unaffected.
            if (state.settings.zebraHighlightEnabled || state.settings.zebraShadowEnabled) {
                ZebraOverlay(
                    viewModel.zebraMask,
                    state.settings.zebraHighlightEnabled,
                    state.settings.zebraShadowEnabled,
                    Modifier.fillMaxSize(),
                )
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

            // Viewfinder frame. Four corner ticks mark the recorded area, so the image
            // reads as a framed picture rather than something letterboxed into black,
            // and while rolling the whole edge turns accent.
            //
            // The edge, not a blinking light, is what says RECORDING. A pulsing dot has
            // to be found before it can be read; an outline around the entire frame is
            // already in view no matter where on the image the eye is. It is also
            // static: this draws once per state change, never per frame, because on this
            // device anything animating continuously during capture competes with the
            // encoder for the cycles that decide whether frames land.
            Canvas(Modifier.fillMaxSize()) {
                val arm = 18.dp.toPx()
                val gap = 8.dp.toPx()
                val hair = 1.dp.toPx()
                val tick = if (state.recording) RawCamColors.Accent else Color.White.copy(alpha = 0.55f)
                val right = size.width - gap
                val bottom = size.height - gap
                // Each corner is two strokes; drawn inward from the inset so the arms
                // never clip against the preview edge.
                listOf(
                    Offset(gap, gap) to listOf(Offset(gap + arm, gap), Offset(gap, gap + arm)),
                    Offset(right, gap) to listOf(Offset(right - arm, gap), Offset(right, gap + arm)),
                    Offset(gap, bottom) to listOf(Offset(gap + arm, bottom), Offset(gap, bottom - arm)),
                    Offset(right, bottom) to listOf(Offset(right - arm, bottom), Offset(right, bottom - arm)),
                ).forEach { (corner, arms) ->
                    arms.forEach { end -> drawLine(tick, corner, end, strokeWidth = hair) }
                }
                if (state.recording) {
                    val w = 2.dp.toPx()
                    drawRect(
                        RawCamColors.Accent,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w),
                    )
                }
            }
        }

        // displayCutoutPadding (on top of systemBarsPadding) keeps the overlay
        // controls clear of the camera punch-hole. In landscape this device
        // reserves the left 48dp (168px) for a vertically-centered cutout, which
        // sat directly over the left status rail; systemBarsPadding alone doesn't
        // account for the cutout, so the timer/frames text was under the hole.
        // Only the overlay is inset -- the preview underneath still fills the edge.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The sensor is 4:3 while the screen is ~20:9, so the preview -- which is
            // height-bound and aspect-locked -- leaves a black bar down each side. Those
            // bars were dead space holding two buttons and one readout, while the
            // parameter strip sat on top of the picture. Measure the bar and put the
            // parameters in it.
            //
            // Measured on the UN-INSET box on purpose: the preview is a sibling of this
            // chrome inside the outer full-screen Box, so it is laid out against the full
            // height. Measuring after systemBarsPadding/displayCutoutPadding made
            // maxHeight smaller, which under-estimated the preview width, which
            // over-estimated the bar -- and the rail's values spilled onto the picture.
            //
            // Clamped because the same app runs on other sensors:
            // a 16:9 mode leaves a far narrower bar, and a rail wider than the bar would
            // start covering the very image this move exists to uncover.
            val previewWidth = maxHeight * (spec.width.toFloat() / spec.height.toFloat())
            // The bar is measured in un-inset screen coordinates, but the rail is laid
            // out inside the inset Box below, so it starts already pushed right by the
            // display cutout. Subtract that or the rail's right edge lands past the bar
            // and the values print over the picture -- which is exactly what happened.
            val startInset = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .asPaddingValues().calculateStartPadding(LocalLayoutDirection.current)
            val railWidth = (((maxWidth - previewWidth) / 2) - startInset).coerceIn(0.dp, 190.dp)
            val railFits = railWidth >= 104.dp

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

            // Left rail. Everything that used to be scattered across three separate
            // alignments -- the stats column at CenterStart, the meter at BottomStart,
            // and the parameter chips ON TOP OF THE PICTURE at BottomCenter -- is one
            // column in the letterbox bar when the bar is wide enough to hold it.
            //
            // Vertical stacking is the point, not the styling: the chip row scrolled
            // horizontally, so the seventh parameter (AUDIO) was permanently off-screen
            // and you could not see lens and white balance in the same glance. A column
            // shows all seven at once and cannot overflow.
            //
            // Top padding clears the TopStart nav buttons rather than sharing a parent
            // with them, so the nav keeps working unchanged in the narrow-bar fallback.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(if (railFits) railWidth else 168.dp)
                    .padding(start = 14.dp, end = 8.dp, bottom = 12.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Nav lives in the rail rather than in its own TopStart box. When it sat
                // outside, the rail had to pad itself down past it by a hand-guessed
                // 104dp -- a quarter of the screen spent dodging two buttons, which left
                // the seven parameter rows and the status block fighting over what was
                // left and clipped the list mid-row.
                if (railFits) {
                    // weight(1f) with no competing weighted sibling: the list takes all
                    // the room the status block below does not need, and scrolls inside
                    // it. An earlier version paired weight(1f, fill = false) here with a
                    // weighted Spacer, which split the free space in half and clipped
                    // the list mid-row at ISO -- four of the seven parameters were
                    // simply unreachable.
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    ) {
                        // Lens and resolution are locked for the whole take, so while
                        // recording they are two rows of dead weight shoving the
                        // controls you CAN still ride -- ISO, shutter, focus, WB --
                        // off the bottom of the rail. Show them only when they mean
                        // something.
                        if (!state.recording) {
                            ParamRow("LENS", lens.label, expanded == Param.LENS, modeEnabled) {
                                expanded = if (expanded == Param.LENS) null else Param.LENS
                            }
                            ParamRow("RES", selectedSize.label, expanded == Param.RES, modeEnabled) {
                                expanded = if (expanded == Param.RES) null else Param.RES
                            }
                        }
                        ParamRow("ISO", "${state.iso}", expanded == Param.ISO) {
                            expanded = if (expanded == Param.ISO) null else Param.ISO
                        }
                        ParamRow(
                            "SHUTTER",
                            shutterLabel(shutterDenom, state.fps, state.settings.shutterDisplay),
                            expanded == Param.SHUTTER,
                        ) { expanded = if (expanded == Param.SHUTTER) null else Param.SHUTTER }
                        ParamRow("FOCUS", focusLabel(state.focusDiopters), expanded == Param.FOCUS) {
                            expanded = if (expanded == Param.FOCUS) null else Param.FOCUS
                        }
                        ParamRow("WB", "${state.kelvin}K", expanded == Param.WB) {
                            expanded = if (expanded == Param.WB) null else Param.WB
                        }
                        ParamRow(
                            "AUDIO",
                            audioRailValue(state.settings),
                            expanded == Param.AUDIO,
                            warn = state.settings.recordAudio && (state.audioFailed || audioDegraded),
                        ) { expanded = if (expanded == Param.AUDIO) null else Param.AUDIO }
                    }
                }

                // Capture stats, still gated on Settings.showStatsSidebar -- hiding them
                // remains a pure subtraction, it just subtracts from the rail now.
                if (state.settings.showStatsSidebar) {
                    Column {
                        // Idle, the timer reads 00:00 and frames-written/dropped are
                        // both 0 -- dead readouts. Show the live capture stats only
                        // while recording, when they mean something; idle keeps just
                        // the one useful readout, space remaining.
                        if (state.recording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RecDot()
                                Text(
                                    formatTimer(state.elapsedSeconds),
                                    color = RawCamColors.OnSurface,
                                    style = RawCamType.Timecode,
                                )
                            }
                            StatItem("${state.written}", "frames")
                            StatItem(
                                "${state.dropped}", "dropped",
                                valueColor = if (state.dropped > 0) RawCamColors.Accent else RawCamColors.OnSurface,
                            )
                        }
                        StatItem(
                            remainingLabel(state, spec),
                            "left",
                        )
                    }
                }

                // Peak level meter: a recording-critical indicator, deliberately NOT
                // gated on showStatsSidebar (see AudioMeter's own kdoc) -- only on
                // whether audio is actually being recorded this take.
                if (state.settings.recordAudio) {
                    AudioMeter(
                        levels = meterLevels,
                        channels = state.audioChannels,
                        noAudio = state.audioFailed,
                        degraded = audioDegraded,
                        recording = state.recording,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    )
                }
            }

            // Right rail: everything you navigate or trigger, in the opposite bar to
            // everything you read. Navigation used to be split for no reason -- BENCH
            // and SETTINGS in the left rail, EXPORTS and CLIPS in a top-right row that
            // started at x~1590 while the picture ran to x~1600, so two of the four
            // were sitting on the image. Stacking all four in the bar puts them in one
            // place and takes them off the frame.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(if (railFits) railWidth else 168.dp)
                    .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Hidden while rolling, like the left rail's: every one of these is
                // already disabled mid-take, so greying them just spends rail on
                // controls that do nothing.
                if (!state.recording) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NavButton(text = "CLIPS", enabled = clipsEnabled, onClick = onOpenClips)
                        NavButton(text = "EXPORTS", enabled = exportsEnabled, onClick = onOpenExports)
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
                }

                Spacer(Modifier.weight(1f))

                ShutterButton(
                    recording = state.recording,
                    enabled = state.previewReady && !state.busy,
                    onClick = { viewModel.toggleRecord() },
                )
                Spacer(Modifier.height(16.dp))
                FpsToggle(
                    options = viewModel.fpsOptions(spec),
                    selected = state.fps,
                    enabled = !state.recording,
                    onSelect = { viewModel.setFps(it) },
                )
                Spacer(Modifier.weight(1f))
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
                                Param.AUDIO -> {
                                    // Enumerated once per panel opening rather than
                                    // per recomposition: listInputs() queries
                                    // AudioManager, and the panel recomposes on every
                                    // meter tick while a take rolls.
                                    val inputs = remember(expanded) { audioInputs() }
                                    val settings = state.settings
                                    ParamLabel("AUDIO")
                                    OptionPills(
                                        labels = listOf("OFF", "ON"),
                                        selectedIndex = if (settings.recordAudio) 1 else 0,
                                        enabled = modeEnabled,
                                        onSelect = { idx ->
                                            val on = idx == 1
                                            if (on) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            viewModel.setRecordAudio(on)
                                        },
                                    )
                                    if (settings.recordAudio) {
                                        // "" (system default) is a real, selectable
                                        // choice, so it heads the list and the saved
                                        // key is matched against these same keys.
                                        val keys = listOf("") + inputs.map { it.key }
                                        // displayNamesFor, not displayName: a handset with two
                                        // built-in mics labels both "Built-in mic" otherwise.
                                        val labels = listOf("System default") +
                                            AudioDeviceCatalog.displayNamesFor(inputs)
                                        val savedIdx = keys.indexOf(settings.audioInputKey)
                                        Spacer(Modifier.height(4.dp))
                                        ParamLabel("INPUT")
                                        OptionPills(
                                            labels = labels,
                                            scrollable = true,
                                            // A saved input that has since been
                                            // unplugged is not in `keys` at all;
                                            // fall back to showing default selected,
                                            // which is what the recorder will
                                            // actually open (see the note below).
                                            selectedIndex = if (savedIdx >= 0) savedIdx else 0,
                                            enabled = modeEnabled,
                                            onSelect = { viewModel.setAudioInput(keys[it]) },
                                        )
                                        if (settings.audioInputKey.isNotEmpty() &&
                                            AudioDeviceCatalog.resolve(inputs, settings.audioInputKey) == null
                                        ) {
                                            Text(
                                                "Saved input unavailable — using default",
                                                color = RawCamColors.Accent, fontSize = 11.sp,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        ParamLabel("GAIN")
                                        OptionPills(
                                            labels = AUDIO_GAIN_STOPS.map { gainLabel(it) },
                                            selectedIndex = AUDIO_GAIN_STOPS.indexOfFirst { it == settings.audioGainDb }
                                                .let { if (it >= 0) it else AUDIO_GAIN_STOPS.indexOf(0f) },
                                            enabled = modeEnabled,
                                            onSelect = { viewModel.setAudioGainDb(AUDIO_GAIN_STOPS[it]) },
                                        )
                                    }
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
                // Fallback for sensors whose aspect ratio leaves a bar too narrow to
                // hold the rail (see railWidth above). There the parameters stay where
                // they always were, as a horizontally scrolling chip strip over the
                // frame -- worse, but present and usable, which beats a rail squeezed
                // to an unreadable width on hardware this build cannot test.
                if (!railFits) {
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
                        // Audio's only always-visible presence. Before this chip a
                        // take with audio off looked identical to one with audio on,
                        // since AudioMeter renders nothing at all when recordAudio is
                        // false. `warn` re-states the meter's own NO AUDIO /
                        // AUDIO DEGRADED verdict as a border tint, so a failure is
                        // still legible with eyes on the frame rather than the meter.
                        ParamChip(
                            audioChipLabel(state.settings),
                            expanded == Param.AUDIO,
                            warn = state.settings.recordAudio && (state.audioFailed || audioDegraded),
                        ) {
                            expanded = if (expanded == Param.AUDIO) null else Param.AUDIO
                        }
                }
                }
            }

                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * Full-screen terminal state for a device [CameraController.initialize] decided
 * cannot record RAW at all (or one whose CAMERA-permission-redacted
 * characteristics made that undecidable) -- reuses the camera-permission
 * gate's visual language in [RecordScreen] (centred column, near-black
 * background, one bordered accent pill) so the two "can't show a preview yet"
 * states read as siblings rather than two unrelated screens. COPY REPORT
 * writes [reportText] to the clipboard rather than firing a share intent --
 * Settings (Task 10) owns sharing; duplicating that here would give the report
 * two divergent exit paths.
 */
@Composable
private fun UnsupportedDeviceScreen(reason: String, detail: String, reportText: String) {
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "DEVICE NOT SUPPORTED", color = RawCamColors.OnSurface,
                fontSize = 15.sp, letterSpacing = 1.5.sp,
            )
            Text(
                reason, color = RawCamColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            if (detail.isNotBlank()) {
                Text(
                    detail, color = RawCamColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
            Surface(
                color = Color.Transparent, shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, RawCamColors.Interactive),
            ) {
                Text(
                    "COPY REPORT", color = RawCamColors.Interactive, fontSize = 13.sp, letterSpacing = 1.sp,
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(reportText)) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
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

/**
 * Animated diagonal stripes over the cells [CameraController.zebraMask] flagged as
 * clipping: red/white over blown highlights, blue over crushed shadows, each drawn
 * only when its corresponding setting is on.
 *
 * Both the mask and the animation phase are read INSIDE the Canvas draw lambda, via
 * State objects that are never destructured in the composable body. That is the whole
 * point of the shape: a new mask ~15x/second (and a new phase every frame) invalidates
 * the draw phase only. Hoisting either read with `by` would recompose this composable
 * at that rate instead, and folding the mask into RecordUiState would recompose the
 * entire screen.
 */
@Composable
private fun ZebraOverlay(
    maskFlow: StateFlow<ZebraMask?>,
    highlightEnabled: Boolean,
    shadowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val mask = maskFlow.collectAsState()
    val transition = rememberInfiniteTransition(label = "zebra")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "zebraPhase",
    )
    Canvas(modifier) {
        val m = mask.value ?: return@Canvas
        if (m.cols <= 0 || m.rows <= 0) return@Canvas
        // 7dp candy-stripe period, half the originally-shipped 14dp bars -- a
        // tighter pitch reads more clearly as "zebra". Hard stops at the midpoint
        // make a stripe, not a gradient; a diagonal start->end vector plus
        // TileMode.Repeated tiles it across the whole layer, so stripes stay
        // continuous from one cell run to the next instead of restarting per rect.
        // Both warnings share one phase/diagonal so they animate in lockstep when
        // both are visible at once.
        val period = 7.dp.toPx()
        val shift = phase.value * period
        val start = Offset(shift, shift)
        val end = Offset(shift + period, shift + period)
        val cw = size.width / m.cols
        val ch = size.height / m.rows
        if (highlightEnabled && m.highlightRuns.isNotEmpty()) {
            val stripes = Brush.linearGradient(
                0.0f to ZebraHighlightColor.copy(alpha = 0.85f),
                0.5f to ZebraHighlightColor.copy(alpha = 0.85f),
                0.5f to Color.White.copy(alpha = 0.85f),
                1.0f to Color.White.copy(alpha = 0.85f),
                start = start,
                end = end,
                tileMode = TileMode.Repeated,
            )
            m.highlightRuns.forEach { run ->
                drawRect(
                    brush = stripes,
                    topLeft = Offset(run.startCol * cw, run.row * ch),
                    size = Size((run.endColExclusive - run.startCol) * cw, ch),
                )
            }
        }
        if (shadowEnabled && m.shadowRuns.isNotEmpty()) {
            // Blue alternating with fully transparent (not a second opaque color):
            // the gaps show the real, already-dark preview pixels through, unlike
            // the highlight brush's opaque white counter-stripe.
            val stripes = Brush.linearGradient(
                0.0f to ZebraShadowColor.copy(alpha = 0.85f),
                0.5f to ZebraShadowColor.copy(alpha = 0.85f),
                0.5f to Color.Transparent,
                1.0f to Color.Transparent,
                start = start,
                end = end,
                tileMode = TileMode.Repeated,
            )
            m.shadowRuns.forEach { run ->
                drawRect(
                    brush = stripes,
                    topLeft = Offset(run.startCol * cw, run.row * ch),
                    size = Size((run.endColExclusive - run.startCol) * cw, ch),
                )
            }
        }
    }
}

/** Reuses the app's existing red accent rather than a new one-off hex -- close
 * enough to the reference screenshots' red to read as the same convention. */
private val ZebraHighlightColor = RawCamColors.Accent

/** No existing theme color is blue; this is zebra-only. */
private val ZebraShadowColor = Color(0xFF3385FF)

@Composable
private fun RecDot() {
    // Static. The pulse this used to run was an infinite transition redrawing for the
    // entire length of a take, and the accent frame edge now states the same thing
    // without animating anything during capture.
    Box(Modifier.size(12.dp).background(RawCamColors.Accent, CircleShape))
}

@Composable
private fun StatItem(value: String, label: String, valueColor: Color = RawCamColors.OnSurface) {
    // Same one-line shape as ParamRow, for two reasons: the rail reads as one
    // instrument rather than two stacked idioms, and stacked stats cost ~30dp each,
    // which while recording squeezed the parameter list down to three visible rows.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label.uppercase(), color = RawCamColors.Muted, style = RawCamType.Label, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, style = RawCamType.Value, maxLines = 1)
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
                    .background(if (on) RawCamColors.InteractiveMid else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("$fps", color = if (on) RawCamColors.OnSurface else RawCamColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** [audioChipLabel] without the leading "AUDIO", for the rail, whose label column
 * already says it. Keeps one source of truth for the state wording: OFF stays OFF and
 * a trim stays "+6dB", so the two surfaces can never disagree about what audio is
 * doing. */
private fun audioRailValue(settings: com.shez.rawcam.settings.Settings): String =
    audioChipLabel(settings).removePrefix("AUDIO").trim().ifEmpty { "ON" }

/** The eight gain stops the AUDIO panel offers, identical to the Settings
 * screen's list -- the two surfaces write one [Settings.audioGainDb] field, so a
 * value chosen on one must be selectable on the other. */
private val AUDIO_GAIN_STOPS = listOf(-20f, -12f, -6f, 0f, 6f, 12f, 20f, 30f)

/** Pill text for a gain stop: sign-carrying and unit-less, since the panel's own
 * GAIN label supplies the unit and the pills have to fit six-plus abreast. */
private fun gainLabel(db: Float): String {
    val n = db.roundToInt()
    return if (n > 0) "+$n" else "$n"
}

/** Horizontal pill selector for the LENS / RESOLUTION panels (FpsToggle, by index). */
@Composable
private fun OptionPills(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    /** Lets the row overflow sideways instead of growing. Off for the fixed,
     * short label sets (LENS, RESOLUTION, gain stops); ON for anything whose
     * labels come from the device -- see the note on wrapping below. */
    scrollable: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp))
            .then(
                if (scrollable) Modifier.horizontalScroll(scroll).horizontalFadingEdge(scroll)
                else Modifier
            )
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .clickable(enabled = enabled) { onSelect(i) }
                    .background(if (on) RawCamColors.InteractiveMid else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    color = if (on) RawCamColors.OnSurface else RawCamColors.Muted,
                    fontSize = 13.sp,
                    // A wrapped pill label is not a cosmetic problem here: the Row
                    // sizes to its tallest child, so ONE two-line label stretched
                    // the whole bordered box to the height of the two lines and
                    // shoved the GAIN row and the chip strip off the bottom of the
                    // screen. Overflow has to go sideways, never downwards.
                    maxLines = 1,
                    softWrap = false,
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

/**
 * One parameter in the left rail: field name and current value on one line, in a quiet
 * bordered surface, with a chevron marking that the row opens something.
 *
 * The first version of this row was bare text on the rail background. That was a
 * mistake: it read as a readout rather than a control, so nothing on the capture screen
 * announced that ISO or shutter could be tapped at all. The reasoning at the time --
 * that seven stacked borders would be noise -- was right about density and wrong about
 * priority. A control that does not look like a control is the worse failure, and the
 * fill here is only a few percent off the bar, so the row reads as tappable without
 * shouting.
 *
 * Stacked (name above value) does not fit: seven stacked rows plus the nav, status
 * block and meter come to roughly 495dp of content in a bar about 393dp tall, so two of
 * the seven were always below the fold -- which defeats the reason for leaving the
 * horizontal chip strip, where AUDIO was permanently off-screen.
 */
@Composable
private fun ParamRow(
    label: String,
    value: String,
    active: Boolean,
    enabled: Boolean = true,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    // A fault outranks an open panel: if audio has failed you need to see that even
    // while the row happens to be the one you are editing.
    val mark = when {
        warn -> RawCamColors.Accent
        active -> RawCamColors.Interactive
        else -> null
    }
    Surface(
        color = when {
            warn -> Color(0xFF2A1113)
            active -> RawCamColors.InteractiveSurface
            else -> Color(0xFF14171B)
        },
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, mark ?: RawCamColors.Outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // The LABEL carries the weight, not a Spacer between label and value.
            // A Row measures unweighted children first, in order, so with the label
            // and value both unweighted the chevron was measured last and got nothing
            // left: SHUTTER (longest label, wide value) rendered with no chevron while
            // every other row had one. Weighting the label makes it the only thing that
            // can lose space, and the value and chevron are always laid out in full.
            Box(Modifier.weight(1f)) {
                Text(label, color = RawCamColors.Muted, style = RawCamType.Label, maxLines = 1)
            }
            Text(
                value,
                color = mark ?: RawCamColors.OnSurface,
                style = RawCamType.Value,
                maxLines = 1,
            )
            // Points down when open, right when closed: the same glyph states whether
            // the row is tappable AND whether its panel is currently showing, so the
            // open row never depends on colour alone to be identified.
            // Three states, three glyphs. Red-green is the worst possible pair to carry
            // meaning -- to a deuteranope the accent red collapses onto olive #8F8F44
            // and a pure green lands nearly on top of it -- so a fault must be legible
            // without seeing colour at all. Jade widens the colour gap; this closes it.
            Text(
                when {
                    warn -> "!"
                    active -> "▾"
                    else -> "›"
                },
                color = mark ?: RawCamColors.Muted,
                fontSize = 11.sp,
                fontFamily = RawCamMono,
            )
        }
    }
}

@Composable
private fun ParamChip(
    text: String,
    active: Boolean,
    enabled: Boolean = true,
    /** Carries a fault on the chip's LABEL rather than its border, because the
     * border already spends [RawCamColors.Accent] on [active]; a warning drawn
     * there would be indistinguishable from an open panel. */
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(0xB80A0B0D),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (active) RawCamColors.Interactive else RawCamColors.Outline),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text,
            color = if (warn) RawCamColors.Accent else RawCamColors.OnSurface,
            fontSize = 14.sp, fontFamily = RawCamMono, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(text, color = RawCamColors.Muted, style = RawCamType.Label)
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
        border = BorderStroke(1.dp, if (locked) RawCamColors.Interactive else RawCamColors.Outline),
    ) {
        Text(
            if (locked) "LOCKED" else "LOCK",
            color = if (locked) RawCamColors.Interactive else RawCamColors.Muted,
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
                    fontSize = 13.sp, fontFamily = RawCamMono,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                labelFor(stops.first()), color = RawCamColors.Muted,
                fontSize = 11.sp, fontFamily = RawCamMono,
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
                // Colours pinned rather than inherited. Material derives the inactive
                // track and tick marks from the scheme's tonal palette, and against a
                // green primary that derivation came out visibly purple on the dark
                // panel -- it had simply been unnoticeable while the accent was red.
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        thumbTrackGapSize = 0.dp,
                        colors = SliderDefaults.colors(
                            // The track is the largest solid green on screen, and a
                            // large fill reads brighter than the same colour in type.
                            // It takes the ramp's fill step, like the pills and the
                            // frame-rate chip; the thumb and bubble keep the lighter
                            // step so they still separate from it.
                            activeTrackColor = RawCamColors.InteractiveMid,
                            inactiveTrackColor = RawCamColors.SurfaceVariant,
                            activeTickColor = RawCamColors.Background,
                            inactiveTickColor = RawCamColors.Muted,
                            disabledActiveTrackColor = RawCamColors.Outline,
                            disabledInactiveTrackColor = RawCamColors.SurfaceVariant,
                        ),
                    )
                },
            )
            Text(
                labelFor(stops.last()), color = RawCamColors.Muted,
                fontSize = 11.sp, fontFamily = RawCamMono,
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
        border = BorderStroke(1.dp, RawCamColors.Interactive),
    ) {
        Text(
            text, color = RawCamColors.OnSurface,
            fontSize = 12.sp, fontFamily = RawCamMono,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
