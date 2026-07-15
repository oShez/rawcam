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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
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
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** UI state for [RecordScreen]. Sliders store the raw value the user picked; the
 * viewmodel derives exposureNs from [shutterIndex] against the fps-filtered stop list.
 * [busy] is true while an async start/stop transition is in flight; the record button
 * is disabled during it (debounce). */
data class RecordUiState(
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

    val controller = CameraController(application)

    private val cameraOps = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    private val initialFps =
        FPS_OPTIONS.firstOrNull { it <= controller.rawSpec.maxFps } ?: controller.rawSpec.maxFps

    private val _uiState = MutableStateFlow(
        RecordUiState(
            iso = controller.rawSpec.isoRange.start, fps = initialFps, shutterIndex = 0,
            lensIndex = controller.defaultLensIndex, sizeIndex = 0,
        )
    )
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
        // Free-space poll for the "space remaining" readout; runs for the whole
        // viewmodel lifetime so the value is honest both idle and mid-recording.
        viewModelScope.launch {
            while (isActive) {
                val free = withContext(Dispatchers.IO) {
                    try { StatFs(controller.clipsDir.absolutePath).availableBytes }
                    catch (e: Exception) { 0L }
                }
                _uiState.update { it.copy(freeSpaceBytes = free) }
                delay(2000)
            }
        }
    }

    /** Shutter stops valid at [fps]: exposure must stay strictly below the frame interval. */
    fun shutterStops(fps: Int): List<Int> = ALL_SHUTTER_DENOMS.filter { it > fps }

    /** FPS choices valid for the selected lens/size mode. Never empty. */
    fun fpsOptions(): List<Int> =
        FPS_OPTIONS.filter { it <= controller.rawSpec.maxFps }
            .ifEmpty { listOf(controller.rawSpec.maxFps) }

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
                }
            } catch (e: Exception) {
                Log.e(TAG, "openAndPreview failed", e)
                _events.tryEmit("Camera open failed")
            }
        }
    }

    /**
     * Preview session failed to configure. If a non-default lens/size mode is
     * selected, revert to main lens full-res — the state change re-keys the
     * SurfaceView, which reopens the camera on the safe mode. If the default
     * mode itself failed, just report it (today's behavior). Runs on the camera
     * thread; StateFlow.update and tryEmit are thread-safe.
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

    /** Clamps fps and shutter to what the (just-selected) mode supports. */
    private fun coerceToMode(state: RecordUiState): RecordUiState {
        val opts = fpsOptions()
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
        controller.updateManual(s.iso, exposureNsFor(s), s.focusDiopters)
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
                val ok = controller.startRecording(path, s.fps, s.iso, exposureNs, s.focusDiopters)
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
        pollJob = viewModelScope.launch {
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

/** ISO slider position (0..1) <-> value, log scale across [range]. */
private fun tFromIso(iso: Int, range: ClosedRange<Int>): Float {
    val lo = range.start.toDouble()
    val hi = range.endInclusive.toDouble()
    if (hi <= lo) return 0f
    val t = ln(iso.toDouble().coerceIn(lo, hi) / lo) / ln(hi / lo)
    return t.toFloat().coerceIn(0f, 1f)
}

private fun isoFromT(t: Float, range: ClosedRange<Int>): Int {
    val lo = range.start.toDouble()
    val hi = range.endInclusive.toDouble()
    if (hi <= lo) return range.start
    val iso = lo * (hi / lo).pow(t.toDouble())
    return iso.roundToInt().coerceIn(range.start, range.endInclusive)
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

private enum class Param { LENS, RES, ISO, SHUTTER, FOCUS }

@Composable
fun RecordScreen(
    viewModel: RecordViewModel = viewModel(),
    clipsEnabled: Boolean = true,
    onOpenClips: () -> Unit = {},
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

    val spec = viewModel.controller.rawSpec
    val lenses = viewModel.controller.lenses
    val lens = lenses.getOrElse(state.lensIndex) { lenses[0] }
    val sizes = lens.sizes
    val size = sizes.getOrElse(state.sizeIndex) { sizes[0] }
    val shutterStops = viewModel.shutterStops(state.fps)
    val modeEnabled = !state.recording && !state.busy
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Param?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Letterboxed preview at the sensor's true aspect ratio; the side gutters
        // that creates host the status/action rails. Keyed on the selected mode:
        // changing lens or resolution recreates the SurfaceView, and the fresh
        // surfaceCreated -> openCamera reopens the camera with the new physical
        // lens and spec (same path as returning from background).
        key(state.lensIndex, state.sizeIndex) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(spec.width.toFloat() / spec.height.toFloat()),
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

            // Benchmark (Task 8), reachable but out of the way. Disabled while
            // recording: its ~6 GB write would compete with the ~376 MB/s capture
            // hot path and force drops.
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
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Text(
                    if (benchRunning) "…" else "BENCH",
                    color = RawCamColors.Muted, fontSize = 11.sp, letterSpacing = 1.5.sp,
                )
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
                    options = viewModel.fpsOptions(),
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
                            Text(
                                when (param) {
                                    Param.LENS -> "LENS"; Param.RES -> "RESOLUTION"
                                    Param.ISO -> "ISO"; Param.SHUTTER -> "SHUTTER"; Param.FOCUS -> "FOCUS"
                                },
                                color = RawCamColors.Muted, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            )
                            when (param) {
                                Param.LENS -> OptionPills(
                                    labels = lenses.map { it.label },
                                    selectedIndex = state.lensIndex,
                                    enabled = modeEnabled,
                                    onSelect = { viewModel.setLens(it) },
                                )
                                Param.RES -> OptionPills(
                                    labels = sizes.map { it.label },
                                    selectedIndex = state.sizeIndex,
                                    enabled = modeEnabled,
                                    onSelect = { viewModel.setResolution(it) },
                                )
                                Param.ISO -> Slider(
                                    value = tFromIso(state.iso, spec.isoRange),
                                    onValueChange = { t -> viewModel.setIso(isoFromT(t, spec.isoRange)) },
                                )
                                Param.SHUTTER -> Slider(
                                    value = state.shutterIndex
                                        .coerceIn(0, (shutterStops.size - 1).coerceAtLeast(0)).toFloat(),
                                    onValueChange = { v -> viewModel.setShutterIndex(v.roundToInt()) },
                                    valueRange = 0f..(shutterStops.size - 1).coerceAtLeast(0).toFloat(),
                                    steps = (shutterStops.size - 2).coerceAtLeast(0),
                                )
                                Param.FOCUS -> {
                                    val maxFocus = spec.minFocusDiopters.coerceAtLeast(0.01f)
                                    Slider(
                                        value = state.focusDiopters.coerceIn(0f, maxFocus),
                                        onValueChange = { viewModel.setFocus(it) },
                                        valueRange = 0f..maxFocus,
                                        enabled = spec.minFocusDiopters > 0f,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val denom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
                    val focusLabel =
                        if (state.focusDiopters <= 0f) "ƒ ∞" else "ƒ %.1fD".format(state.focusDiopters)
                    ParamChip(lens.label, expanded == Param.LENS, enabled = modeEnabled) {
                        expanded = if (expanded == Param.LENS) null else Param.LENS
                    }
                    ParamChip(size.label, expanded == Param.RES, enabled = modeEnabled) {
                        expanded = if (expanded == Param.RES) null else Param.RES
                    }
                    ParamChip("ISO ${state.iso}", expanded == Param.ISO) {
                        expanded = if (expanded == Param.ISO) null else Param.ISO
                    }
                    ParamChip("1/$denom", expanded == Param.SHUTTER) {
                        expanded = if (expanded == Param.SHUTTER) null else Param.SHUTTER
                    }
                    ParamChip(focusLabel, expanded == Param.FOCUS) {
                        expanded = if (expanded == Param.FOCUS) null else Param.FOCUS
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
