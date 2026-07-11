package com.shez.rawcam.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.StatFs
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * viewmodel derives exposureNs from [shutterIndex] against the fps-filtered stop list. */
data class RecordUiState(
    val previewReady: Boolean = false,
    val recording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val written: Long = 0,
    val dropped: Long = 0,
    val thermalSevere: Boolean = false,
    val iso: Int = 100,
    val shutterIndex: Int = 0,
    val focusDiopters: Float = 0f,
    val fps: Int = 24,
)

/**
 * Owns the [CameraController] for the lifetime of the activity, the manual-control
 * state, the record/stop flow (with the free-space refusal check), the 500ms stats
 * poll while recording, and the thermal-status listener.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    val controller = CameraController(application)

    private val initialFps =
        FPS_OPTIONS.firstOrNull { it <= controller.rawSpec.maxFps } ?: controller.rawSpec.maxFps

    private val _uiState = MutableStateFlow(
        RecordUiState(iso = controller.rawSpec.isoRange.start, fps = initialFps, shutterIndex = 0)
    )
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private var opened = false
    private var pollJob: Job? = null
    private var recordStartMs = 0L
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _uiState.update { it.copy(thermalSevere = status >= PowerManager.THERMAL_STATUS_SEVERE) }
    }

    init {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.addThermalStatusListener(application.mainExecutor, thermalListener)
    }

    /** Shutter stops valid at [fps]: exposure must stay strictly below the frame interval. */
    fun shutterStops(fps: Int): List<Int> = ALL_SHUTTER_DENOMS.filter { it > fps }

    fun openCamera(surface: android.view.Surface) {
        if (opened) return
        opened = true
        controller.openAndPreview(surface) {
            _uiState.update { it.copy(previewReady = true) }
            pushManual()
        }
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
        if (_uiState.value.recording) stopRecordingInternal() else startRecordingInternal()
    }

    private fun startRecordingInternal() {
        val s = _uiState.value
        if (!s.previewReady || s.recording) return
        val spec = controller.rawSpec
        // Packed10 payload record size: (w*h/4)*5 + 64 bytes of FrameMeta.
        val frameBytes = (spec.width.toLong() * spec.height / 4) * 5 + 64
        val available = StatFs(controller.clipsDir.absolutePath).availableBytes
        val required = frameBytes * s.fps * 35L
        if (available < required) {
            val maxSeconds = available / (frameBytes * s.fps)
            _events.tryEmit("Not enough free space; max ~${maxSeconds}s recordable")
            return
        }
        val name = "clip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".rawv"
        val path = File(controller.clipsDir, name).absolutePath
        val exposureNs = exposureNsFor(s)
        viewModelScope.launch(Dispatchers.Default) {
            val ok = controller.startRecording(path, s.fps, s.iso, exposureNs, s.focusDiopters)
            withContext(Dispatchers.Main) {
                if (ok) {
                    recordStartMs = System.currentTimeMillis()
                    _uiState.update { it.copy(recording = true, elapsedSeconds = 0, written = 0, dropped = 0) }
                    startPolling()
                } else {
                    _events.tryEmit("Failed to start recording")
                }
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
        viewModelScope.launch(Dispatchers.Default) {
            val stats = controller.stopRecording()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(recording = false, written = stats[0], dropped = stats[1]) }
                if (stats[0] == 0L && stats[1] > 0L) {
                    _events.tryEmit("Recording failed: writer error")
                } else {
                    _events.tryEmit("${stats[0]} frames, ${stats[1]} dropped")
                }
            }
        }
    }

    /**
     * Called from MainActivity.onStop() when a recording is in progress: finalizes
     * the file and releases the camera before the system may tear things down.
     * Deliberately synchronous (blocks the main thread briefly) so onStop cannot
     * return before the writer has finalized -- see CameraController's documented
     * contract, which explicitly allows calling stopRecording()/close() from main.
     */
    fun handleActivityStop() {
        if (!_uiState.value.recording) return
        pollJob?.cancel()
        pollJob = null
        val stats = controller.stopRecording()
        _uiState.update { it.copy(recording = false, written = stats[0], dropped = stats[1]) }
        controller.close()
        opened = false
    }

    override fun onCleared() {
        pollJob?.cancel()
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.removeThermalStatusListener(thermalListener)
        controller.close()
    }

    companion object {
        val ALL_SHUTTER_DENOMS = listOf(24, 48, 60, 120, 240, 500, 1000)
        val FPS_OPTIONS = listOf(24, 30)
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

@Composable
fun RecordScreen(viewModel: RecordViewModel = viewModel()) {
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
    val shutterStops = viewModel.shutterStops(state.fps)
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
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

        if (state.thermalSevere) {
            Surface(
                color = Color.Red,
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

        // Small benchmark trigger (Task 8), kept reachable but out of the way.
        TextButton(
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
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            Text(if (benchRunning) "…" else "B", color = Color.White.copy(alpha = 0.6f))
        }

        // Right rail: record/stop, timer, drop counter.
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                enabled = state.previewReady,
                onClick = { viewModel.toggleRecord() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.recording) Color.Red else MaterialTheme.colorScheme.primary,
                ),
                shape = CircleShape,
                modifier = Modifier.size(84.dp),
            ) {
                Text(if (state.recording) "STOP" else "REC")
            }
            Spacer(Modifier.height(12.dp))
            Text(formatTimer(state.elapsedSeconds), color = Color.White, fontSize = 22.sp)
            Text(
                "dropped: ${state.dropped}",
                color = if (state.dropped > 0) Color.Red else Color.White,
            )
        }

        // Bottom rail: fps selector + manual sliders.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FPS", color = Color.White, modifier = Modifier.width(70.dp))
                RecordViewModel.FPS_OPTIONS.filter { it <= spec.maxFps }.forEach { fps ->
                    TextButton(enabled = !state.recording, onClick = { viewModel.setFps(fps) }) {
                        Text(
                            "$fps" + if (fps == state.fps) " ✓" else "",
                            color = if (fps == state.fps) Color.Yellow else Color.White,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ISO ${state.iso}", color = Color.White, modifier = Modifier.width(120.dp))
                Slider(
                    value = tFromIso(state.iso, spec.isoRange),
                    onValueChange = { t -> viewModel.setIso(isoFromT(t, spec.isoRange)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val denom = shutterStops.getOrElse(state.shutterIndex) { shutterStops.lastOrNull() ?: 0 }
                Text("1/$denom s", color = Color.White, modifier = Modifier.width(120.dp))
                Slider(
                    value = state.shutterIndex.coerceIn(0, (shutterStops.size - 1).coerceAtLeast(0)).toFloat(),
                    onValueChange = { v -> viewModel.setShutterIndex(v.roundToInt()) },
                    valueRange = 0f..(shutterStops.size - 1).coerceAtLeast(0).toFloat(),
                    steps = (shutterStops.size - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val focusLabel = if (state.focusDiopters <= 0f) "Focus: ∞" else "Focus: %.1fD".format(state.focusDiopters)
                Text(focusLabel, color = Color.White, modifier = Modifier.width(120.dp))
                val maxFocus = spec.minFocusDiopters.coerceAtLeast(0.01f)
                Slider(
                    value = state.focusDiopters.coerceIn(0f, maxFocus),
                    onValueChange = { viewModel.setFocus(it) },
                    valueRange = 0f..maxFocus,
                    enabled = spec.minFocusDiopters > 0f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

