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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

/** UI state for [RecordScreen]. Sliders store the raw value the user picked; the
 * viewmodel derives exposureNs from [shutterIndex] against the fps-filtered stop list.
 * [busy] is true while an async start/stop transition is in flight; the record button
 * is disabled during it (debounce). [rawSpec] and [lenses] are null/empty until
 * camera enumeration (off-main, see [CameraController.initialize]) completes and
 * publishes them here -- the composable renders a loading placeholder until then. */
data class RecordUiState(
    val rawSpec: CameraController.RawSpec? = null,
    val lenses: List<CameraController.LensInfo> = emptyList(),
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
            _events.tryEmit("Recording stopped: thermal")
            stopRecordingInternal()
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
                previous = s
            }
        }
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
            _uiState.update {
                it.copy(
                    rawSpec = controller.rawSpec, lenses = controller.lenses,
                    iso = iso, fps = fps, shutterIndex = shutterIndex, lensIndex = lensIndex, sizeIndex = sizeIndex,
                    kelvin = kelvin, tint = tint, focusDiopters = focus,
                )
            }
        }
        // Free-space poll for the "space remaining" readout is driven from the
        // composable (see RecordScreen's repeatOnLifecycle block calling
        // refreshFreeSpace()) rather than looping here for the whole viewmodel
        // lifetime -- the ViewModel has no Lifecycle of its own to gate on (and
        // shouldn't be handed the Activity's, which would break its
        // configuration-change independence), so pausing this StatFs + uiState
        // poll while backgrounded is delegated to the UI side instead.
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
     * artificial light; OFF keeps the old flat list. */
    fun shutterStops(fps: Int): List<Int> = when (_uiState.value.settings.mainsFreq) {
        MainsFreq.OFF  -> listOf(24, 48, 60, 120, 240, 500, 1000)
        MainsFreq.HZ50 -> listOf(24, 50, 100, 200, 400, 500, 1000)
        MainsFreq.HZ60 -> listOf(24, 60, 120, 240, 500, 1000)
    }.filter { it > fps }

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
                    // First preview-ready of this process: auto-meter the center once so
                    // launch shows a correctly white-balanced image (and seeds
                    // CameraController's anchor) instead of the calibrated model's
                    // possibly-placeholder-matrix output persisting until the user's
                    // first manual tap -- subject to the user's startupMeter setting.
                    // didAutoMeter is a plain (non-Volatile) field -- fine because
                    // onReady always runs on the single camera thread (see
                    // CameraController's class kdoc), never concurrently with itself.
                    if (!didAutoMeter) {
                        didAutoMeter = true
                        val mode = _uiState.value.settings.startupMeter
                        val shouldMeter = when (mode) {
                            StartupMeter.ALWAYS -> true
                            // restoredFromSaved is @Volatile and written once, on
                            // cameraOps, by the init{} restore block before this
                            // onReady can ever fire (openCamera() is itself only
                            // reachable once the composable observes rawSpec != null,
                            // which that same block publishes last) -- so the read
                            // here is always the settled value, never the initial false.
                            StartupMeter.IF_NO_SAVED -> !restoredFromSaved
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

    /** Guards the one-shot startup auto-meter in [openCamera] -- true after the first
     * preview-ready of this process, for the lifetime of this ViewModel (i.e. once
     * per process for all practical purposes: this VM instance survives
     * configuration changes but not process death). Deliberately never reset by
     * lens/mode switches, so a re-open never re-triggers it. */
    private var didAutoMeter = false

    /** True once the init{} restore block has determined a [CaptureState] was
     * actually applied at launch (vs. falling through to settings defaults because
     * nothing was saved, or [Settings.rememberLastState] was off). Written exactly
     * once, on cameraOps, before rawSpec is published (so before openCamera() can
     * possibly be called -- see openCamera's onReady comment); read on the camera
     * thread by the [StartupMeter.IF_NO_SAVED] check above. @Volatile makes that
     * cross-thread read see the write without relying on incidental ordering. */
    @Volatile private var restoredFromSaved = false

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
        _uiState.update { it.copy(iso = iso) }
        pushManual()
        persistCaptureState()
    }

    fun setShutterIndex(index: Int) {
        _uiState.update { it.copy(shutterIndex = index) }
        pushManual()
        persistCaptureState()
    }

    fun setFocus(diopters: Float) {
        _uiState.update { it.copy(focusDiopters = diopters) }
        pushManual()
        persistCaptureState()
    }

    fun setKelvin(k: Int) {
        _uiState.update { it.copy(kelvin = k) }
        pushManual()
        persistCaptureState()
    }

    fun setTint(t: Int) {
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
        Log.i(TAG, "meterAt nx=$nx ny=$ny quiet=$quiet")
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
                        _uiState.update { cur ->
                            when (scope) {
                                MeterScope.EVERYTHING -> cur.copy(
                                    iso = newIso, shutterIndex = newShutter,
                                    focusDiopters = m.focusDiopters, kelvin = m.kelvin, tint = m.tint,
                                )
                                MeterScope.EXPOSURE_FOCUS -> cur.copy(
                                    iso = newIso, shutterIndex = newShutter, focusDiopters = m.focusDiopters,
                                )
                                MeterScope.WB_ONLY -> cur.copy(kelvin = m.kelvin, tint = m.tint)
                            }
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
                        pushManual()
                        if (scope != MeterScope.EXPOSURE_FOCUS) controller.setWbOverride(m.wbGains)
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

    /** Clamps fps and shutter to what the (just-selected) mode supports. Only ever
     * called after a successful controller.selectMode (recording/lens-switch paths,
     * themselves only reachable once the UI is showing lens controls, i.e. after
     * enumeration) -- controller.rawSpec is guaranteed valid here. */
    private fun coerceToMode(state: RecordUiState): RecordUiState {
        val opts = fpsOptions(controller.rawSpec)
        val fps = if (state.fps in opts) state.fps
        else (opts.lastOrNull { it <= state.fps } ?: opts.first())
        val stops = shutterStops(fps)
        return state.copy(
            fps = fps,
            shutterIndex = state.shutterIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
            previewReady = false,
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
                // Packed10 payload record size: (w*h/4)*5 + 64 bytes of FrameMeta.
                val frameBytes = (spec.width.toLong() * spec.height / 4) * 5 + 64
                val available = StatFs(controller.clipsDir.absolutePath).availableBytes
                val required = frameBytes * s.fps * s.settings.freeSpaceReserveSeconds.toLong()
                if (available < required) {
                    val maxSeconds = available / (frameBytes * s.fps)
                    _events.tryEmit("Not enough free space; max ~${maxSeconds}s recordable")
                    return@launch
                }
                val name =
                    "${s.settings.clipPrefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".rawv"
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
                    _events.tryEmit("Auto-stopped: clip length limit")
                    stopRecordingInternal()
                    break
                }
            }
        }
    }

    private fun stopRecordingInternal() {
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
        if (!prev.recording || prev.busy) return
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
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
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

/** Free space -> recordable time at the current fps/frame size ("~23 min"). */
private fun remainingLabel(freeBytes: Long, fps: Int, width: Int, height: Int): String {
    val frameBytes = (width.toLong() * height / 4) * 5 + 64
    val perSecond = frameBytes * fps
    if (perSecond <= 0) return "—"
    val seconds = freeBytes / perSecond
    return when {
        seconds >= 6000 -> "99+ min"
        seconds >= 120 -> "~${seconds / 60} min"
        else -> "~$seconds s"
    }
}

private val NICE_ISO_STOPS =
    listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)

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
internal val KELVIN_STOPS = listOf(2000, 2700, 3200, 4000, 5000, 5600, 6500, 7500, 9000, 10000)
internal val TINT_STOPS = (-50..50 step 5).toList()

private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS, WB }

@Composable
fun RecordScreen(
    viewModel: RecordViewModel = viewModel(),
    clipsEnabled: Boolean = true,
    onOpenClips: () -> Unit = {},
    settingsEnabled: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        }
        return
    }

    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
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
    val size = sizes.getOrElse(state.sizeIndex) { sizes[0] }
    val shutterStops = viewModel.shutterStops(state.fps)
    val shutterDenom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
    val modeEnabled = !state.recording && !state.busy
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
                            viewModel.meterAt(offset.x / size.width.toFloat(), offset.y / size.height.toFloat())
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
                    // Explicit `this.size` (not bare `size`) -- this composable's outer
                    // scope has its own local `size` (the selected LensSize, Int pixel
                    // dimensions of the sensor mode) which otherwise shadows this
                    // DrawScope's own `size: Size` (Float, the Canvas's actual layout
                    // pixel dimensions -- what the grid must be measured against).
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
                HorizonLevel(Modifier.align(Alignment.Center))
            }

            // Tap-to-meter reticle: grey while converging, green briefly after, at the
            // normalized tap point mapped back into this Box's pixel dimensions.
            state.meterPoint?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    val cx = p.x * size.width
                    val cy = p.y * size.height
                    val r = 36.dp.toPx()
                    val c = if (state.metering) Color(0xFFE0E0E0) else Color(0xFF7CFF7C)
                    drawRect(c, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(width = 3.dp.toPx()))
                }
            }
        }

        Box(Modifier.fillMaxSize().systemBarsPadding()) {
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
            Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                if (state.settings.showBench) {
                    TextButton(
                        enabled = !state.recording && !state.busy,
                        onClick = {
                            if (benchRunning) return@TextButton
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
                    ) {
                        Text(
                            if (benchRunning) "…" else "BENCH",
                            color = RawCamColors.Muted, fontSize = 11.sp, letterSpacing = 1.5.sp,
                        )
                    }
                }
                TextButton(
                    enabled = settingsEnabled,
                    onClick = onOpenSettings,
                ) {
                    Text(
                        "SETTINGS",
                        color = RawCamColors.Muted, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    )
                }
            }

            TextButton(
                enabled = clipsEnabled,
                onClick = onOpenClips,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "CLIPS",
                    color = if (clipsEnabled) RawCamColors.OnSurface
                            else RawCamColors.Muted.copy(alpha = 0.5f),
                    fontSize = 11.sp, letterSpacing = 1.5.sp,
                )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.recording) RecDot()
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
                    StatItem(
                        remainingLabel(state.freeSpaceBytes, state.fps, spec.width, spec.height),
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
                                        onSelect = { viewModel.setIso(it) },
                                    )
                                }
                                Param.SHUTTER -> {
                                    ParamLabel("SHUTTER")
                                    TickedSlider(
                                        stops = shutterStops,
                                        selected = shutterDenom,
                                        labelFor = { shutterLabel(it, state.fps, state.settings.shutterDisplay) },
                                        onSelect = { viewModel.setShutterIndex(shutterStops.indexOf(it)) },
                                    )
                                }
                                Param.FOCUS -> {
                                    ParamLabel("FOCUS")
                                    TickedSlider(
                                        stops = focusStops(spec.minFocusDiopters),
                                        selected = state.focusDiopters,
                                        labelFor = ::focusLabel,
                                        enabled = spec.minFocusDiopters > 0f,
                                        onSelect = { viewModel.setFocus(it) },
                                    )
                                }
                                Param.WB -> {
                                    ParamLabel("WHITE BALANCE")
                                    TickedSlider(
                                        stops = KELVIN_STOPS,
                                        selected = state.kelvin,
                                        labelFor = { "${it}K" },
                                        onSelect = { viewModel.setKelvin(it) },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TickedSlider(
                                        stops = TINT_STOPS,
                                        selected = state.tint,
                                        labelFor = { if (it > 0) "+$it" else "$it" },
                                        onSelect = { viewModel.setTint(it) },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParamChip(lens.label, expanded == Param.LENS, enabled = modeEnabled) {
                        expanded = if (expanded == Param.LENS) null else Param.LENS
                    }
                    ParamChip(size.label, expanded == Param.RES, enabled = modeEnabled) {
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
 * Roll math: [SensorManager.getRotationMatrixFromVector] plus the IDENTITY axis remap
 * defines "roll" (getOrientation's `values[2]`) as banking around the device's
 * natural-portrait "up" axis -- correct for a phone lying flat on a table, not for one
 * held up vertically like a camera pointed at the horizon (that usage is close to the
 * gimbal-lock edge of the roll/pitch/azimuth decomposition and the axis doesn't mean
 * "image rotation" there). Remapping coordinates with (AXIS_X, AXIS_Z) substitutes the
 * screen-normal axis (old Z, pointing out through the lens/screen) in as the roll axis,
 * which is the standard correction for a vertically-held viewfinder: the resulting roll
 * is the rotation of the frame around the lens axis -- i.e. "is the horizon level in the
 * image" -- and that quantity depends only on the physical device housing, not on
 * Surface.getRotation(), so it is correct for ANY UI orientation including this app's
 * fixed `landscape` lock (AndroidManifest.xml). That axis substitution IS the
 * "adjustment for the app's landscape lock" the task calls for; no further per-rotation
 * multiplier is layered on top; further defense at this device's fixed `landscape`
 * (not sensorLandscape/reverseLandscape) is that a single physical rotation is used for
 * the whole app lifetime, so the axis choice above doesn't need to react to runtime
 * rotation changes the way a compass app's display-rotation remap table would.
 *
 * NOT verified on a physical device -- no adb use is permitted in this task.
 * ON-DEVICE VERIFY: tilt the phone (held landscape, as it's locked) left/right and
 * confirm the drawn line rotates opposite to the phone (i.e. stays visually level with
 * the true horizon). If it instead rotates WITH the phone (mirrored), the fix is a
 * one-line sign flip: negate `roll` right after it's computed below.
 */
@Composable
private fun HorizonLevel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                val remapped = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped,
                )
                SensorManager.getOrientation(remapped, orientation)
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
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

/**
 * Discrete slider fixing the two concrete complaints about the old bare sliders:
 * endpoint labels give a scale reference, and snapping to [stops] makes it
 * possible to land on exact values. [stops] must have at least 2 entries.
 */
// internal (not private): reused by SettingsScreen.kt's SliderRow.
@Composable
internal fun <T> TickedSlider(
    stops: List<T>, selected: T, labelFor: (T) -> String,
    enabled: Boolean = true, onSelect: (T) -> Unit,
) {
    val index = stops.indexOf(selected).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            labelFor(stops.first()), color = RawCamColors.Muted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { v -> onSelect(stops[v.roundToInt().coerceIn(0, stops.size - 1)]) },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            labelFor(stops.last()), color = RawCamColors.Muted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
