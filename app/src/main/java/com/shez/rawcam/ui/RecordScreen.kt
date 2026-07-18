package com.shez.rawcam.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.camera.CameraController
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _uiState.update { it.copy(thermalSevere = status >= PowerManager.THERMAL_STATUS_SEVERE) }
    }

    init {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.addThermalStatusListener(application.mainExecutor, thermalListener)
        // Camera lens enumeration is binder IPC (getCameraCharacteristics per lens +
        // stream-config queries) -- runs on cameraOps, never the main thread. Queued
        // first on cameraOps, so every later cameraOps.launch (openCamera, etc.) is
        // guaranteed to run after this completes. lenses/rawSpec are published into
        // uiState only once initialize() returns; the composable gates on rawSpec
        // being non-null and renders nothing controller-derived before that.
        cameraOps.launch {
            controller.initialize()
            val fps = FPS_OPTIONS.firstOrNull { it <= controller.rawSpec.maxFps }
                ?: controller.rawSpec.maxFps
            _uiState.update {
                it.copy(
                    rawSpec = controller.rawSpec,
                    lenses = controller.lenses,
                    iso = controller.rawSpec.isoRange.start,
                    fps = fps,
                    lensIndex = controller.defaultLensIndex,
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

    /** Shutter stops valid at [fps]: exposure must stay strictly below the frame interval. */
    fun shutterStops(fps: Int): List<Int> = ALL_SHUTTER_DENOMS.filter { it > fps }

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
                    // first manual tap. didAutoMeter is a plain (non-Volatile) field --
                    // fine because onReady always runs on the single camera thread (see
                    // CameraController's class kdoc), never concurrently with itself.
                    if (!didAutoMeter) {
                        didAutoMeter = true
                        meterAt(0.5f, 0.5f, quiet = true)
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
    }

    fun setShutterIndex(index: Int) {
        _uiState.update { it.copy(shutterIndex = index) }
        pushManual()
    }

    fun setFocus(diopters: Float) {
        _uiState.update { it.copy(focusDiopters = diopters) }
        pushManual()
    }

    fun setKelvin(k: Int) {
        _uiState.update { it.copy(kelvin = k) }
        pushManual()
    }

    fun setTint(t: Int) {
        _uiState.update { it.copy(tint = t) }
        pushManual()
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
                        // each its own update+pushManual). Ordering note (WB override,
                        // CameraController.wbOverride): pushManual() below calls
                        // controller.updateManual(..., kelvin=m.kelvin, tint=m.tint) --
                        // the controller's OWN stored kelvin/tint are still whatever they
                        // were before this meter (not necessarily equal to m.kelvin/
                        // m.tint), so updateManual's clear-on-change check typically DOES
                        // fire and clears wbOverride here. That's fine: setWbOverride()
                        // runs immediately after and is the last (and effective) word on
                        // this frame's WB gains regardless of whether the clear fired --
                        // its own post re-arms the repeating request a second time with
                        // the exact metered gains applied.
                        _uiState.update {
                            it.copy(
                                iso = nearestIso(m.iso),
                                shutterIndex = nearestShutterIndex(m.exposureNs),
                                focusDiopters = m.focusDiopters,
                                kelvin = m.kelvin,
                                tint = m.tint,
                            )
                        }
                        pushManual()
                        controller.setWbOverride(m.wbGains)
                    } else if (!quiet) {
                        _events.tryEmit("Couldn't meter — try again")
                    }
                    _uiState.update { it.copy(metering = false) }
                    delay(600)   // leave the reticle briefly, then clear it
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
                val required = frameBytes * s.fps * 35L
                if (available < required) {
                    val maxSeconds = available / (frameBytes * s.fps)
                    _events.tryEmit("Not enough free space; max ~${maxSeconds}s recordable")
                    return@launch
                }
                val name =
                    "clip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".rawv"
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
            }
        }
    }

    private fun stopRecordingInternal() {
        pollJob?.cancel()
        pollJob = null
        _uiState.update { it.copy(busy = true) }
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
        val ALL_SHUTTER_DENOMS = listOf(24, 48, 60, 120, 240, 500, 1000)
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
            // and stall the RAW stream, same reasoning as CLIPS below.
            Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
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

            // Left status rail.
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
                                        labelFor = { "1/$it" },
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
                    ParamChip("1/$shutterDenom", expanded == Param.SHUTTER) {
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
