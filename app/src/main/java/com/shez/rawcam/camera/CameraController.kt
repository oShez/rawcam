package com.shez.rawcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Camera2 controller for RAW video capture with fully manual exposure.
 *
 * Lifecycle: construct (cheap, no camera IPC) -> [initialize] (binder IPC; enumerates
 * lenses and populates [rawSpec] -- MUST run off the main thread, e.g. the caller's
 * cameraOps dispatcher) -> [openAndPreview] -> [startRecording] / [updateManual] /
 * [stopRecording] (repeatable) -> [close]. No method below [initialize] in that list
 * may be called until it has returned.
 *
 * Threading: all Camera2 callbacks run on a dedicated HandlerThread ("camera").
 * [startRecording], [stopRecording] and [close] block briefly on that thread's work,
 * so they must NOT be called from the camera thread itself (call from main or a
 * background coroutine). Runtime CAMERA permission is assumed granted; a
 * SecurityException from openCamera propagates to the caller.
 */
class CameraController(private val context: Context) {

    data class RawSpec(
        val width: Int, val height: Int, val cfa: Int, val whiteLevel: Int,
        val blackLevel: IntArray, val colorMatrix1: FloatArray,
        val isoRange: ClosedRange<Int>, val maxFps: Int,
        val minFocusDiopters: Float, val deviceName: String,
    )

    /** One selectable RAW output size of a lens. [label] is what the UI shows. */
    data class LensSize(val width: Int, val height: Int, val maxFps: Int, val label: String)

    /**
     * One selectable back lens. [physicalId] is null only for the single-lens
     * fallback (open the logical camera untagged — today's behavior). [fovMetric]
     * is sensorPhysicalWidth / focalLength: proportional to field of view, used
     * for sorting (widest first) and zoom labels.
     */
    data class LensInfo(
        val physicalId: String?, val label: String, val focalMm: Float,
        val fovMetric: Float, val sizes: List<LensSize>,
        val cfa: Int, val whiteLevel: Int, val blackLevel: IntArray,
        val colorMatrix1: FloatArray, val isoRange: ClosedRange<Int>,
        val minFocusDiopters: Float, val activeArraySize: Rect,
        /** DNG-style second calibration illuminant (nullable -- some sensors only
         * expose one). Row-major 3x3, same convention as [colorMatrix1]: CIE
         * XYZ -> sensor space under [illuminant2]. */
        val colorMatrix2: FloatArray?,
        /** DNG/EXIF reference-illuminant codes for [colorMatrix1] / [colorMatrix2];
         * mapped to a CCT by [illuminantCct]. Null when the characteristic is absent. */
        val illuminant1: Int?, val illuminant2: Int?,
    )

    /** Result of a tap-to-meter pass: converged 3A readings snapped to manual control values. */
    data class MeteredValues(
        val iso: Int,
        val exposureNs: Long,
        val focusDiopters: Float,
        val kelvin: Int,
        val tint: Int,
        /** Raw COLOR_CORRECTION_GAINS from the converged AWB result, passed through
         * untouched so a caller can apply it exactly instead of the lossy
         * kelvin/tint snap. Null only if the result carried no gains. */
        val wbGains: RggbChannelVector?,
    )

    /**
     * DNG-style sensor WB calibration for [gainsFor]: two color matrices (CIE
     * XYZ -> sensor space) under two reference illuminants, and each
     * illuminant's CCT. [matrix2]/[cct2] fall back to [matrix1] alone when the
     * sensor exposes only one illuminant (see [interpolatedColorMatrix]).
     */
    private data class WbCalib(
        val matrix1: FloatArray, val cct1: Int,
        val matrix2: FloatArray?, val cct2: Int,
    )

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private lateinit var cameraId: String

    /** Selectable back lenses, widest first. At least one entry. Populated by
     * [initialize]; empty until then. */
    @Volatile var lenses: List<LensInfo> = emptyList()
        private set

    /** Index in [lenses] of the main (1×) lens — the revert target on mode failure.
     * Populated by [initialize]. */
    @Volatile var defaultLensIndex: Int = 0
        private set

    /** Snapshot of the selected lens+size mode. Replaced atomically by [selectMode].
     * Populated by [initialize]; unset (throws on read) until then -- every other
     * method on this class must not be called before [initialize] returns. */
    @Volatile lateinit var rawSpec: RawSpec
        private set

    /** Physical camera id every session's OutputConfigurations are tagged with. */
    @Volatile private var activePhysicalId: String? = null

    /** Active-array size (sensor pixel bounds) of the active lens; used by [meterAt]
     * to map a normalized tap point into a metering region. Cached from the same
     * per-lens [CameraCharacteristics] already fetched in [enumerateLenses] — never
     * refetched on the open/select hot path. */
    @Volatile private var activeArraySize: Rect? = null

    /** Directory Task 11 should place clip files in (created eagerly). */
    val clipsDir: File = File(context.getExternalFilesDir(null), "clips").apply { mkdirs() }

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    /**
     * Dedicated callback thread for [meterAt]'s capture callbacks. meterAt blocks
     * [cameraHandler] on a latch while convergence proceeds, so those callbacks
     * must land elsewhere or they'd queue behind the blocked thread and never run
     * (permanent 1.8s timeout, every time). Lazily started on first use (from
     * [cameraHandler] only), quit alongside the camera thread in [close].
     */
    private var meterCallbackThread: HandlerThread? = null
    private var meterCallbackHandler: Handler? = null

    // device/previewSurface/rawSurface are read directly (no Handler.post barrier) from
    // caller threads -- e.g. startRecording's `previewSurface ?: return false` and
    // `device == null` checks run on cameraOps, not the camera thread -- while written
    // from camera-thread callbacks (onOpened/onDisconnected/onError) or, for
    // rawSurface, from startRecording/stopRecording themselves on cameraOps. session is
    // written only from the camera thread but is included for the same defensive
    // reason. @Volatile makes those writes visible without relying on incidental
    // happens-before through unrelated Handler posts.
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var rawSurface: Surface? = null

    @Volatile private var recording = false
    @Volatile private var manualSet = false
    @Volatile private var recordFps = 24
    @Volatile private var iso = 100
    @Volatile private var exposureNs = 10_000_000L
    @Volatile private var focusDiopters = 0f
    @Volatile private var kelvin = 5600
    @Volatile private var tint = 0

    /** Sensor WB calibration for the active lens; populated by [initialize] and
     * [selectMode] on the same off-main thread that already writes [rawSpec] --
     * mirrors that field's lateinit + @Volatile pattern (not read before those
     * complete). */
    @Volatile private lateinit var wbCalib: WbCalib

    /** Exact metered WB gains, applied verbatim by [applyManual] and the
     * capture-callback DNG-metadata fallback in place of [gainsFor]'s
     * kelvin/tint model. Set by [setWbOverride] (tap-to-meter); cleared by
     * [updateManual] / [startRecording] when the user moves a WB slider
     * (kelvin or tint actually changes). Null = no override, use the model. */
    @Volatile private var wbOverride: RggbChannelVector? = null

    /** Real measured AWB gains from the most recent successful meter, and the
     * kelvin [gainsToKelvinTint] matched them to (against the anchor state
     * BEFORE this one) -- see [gainsFor]'s kdoc. This is what lets the slider
     * model track this device's *actual* sensor response instead of a static
     * calibration matrix that may be a placeholder (observed on a Pixel 7 Pro:
     * SENSOR_COLOR_TRANSFORM2 was the textbook XYZ->sRGB matrix, not a real
     * per-unit calibration). Null until the first successful meter of this
     * process; [gainsFor] substitutes DEFAULT_ANCHOR_* in that case. Written
     * only from [readMetered], on the camera thread, same as every other field
     * gainsFor reads. */
    @Volatile private var anchorGains: RggbChannelVector? = null
    @Volatile private var anchorKelvin: Int = 5600
    /** Set by stopRecording(); counted down by the session's onReady (idle) callback. */
    @Volatile private var idleLatch: CountDownLatch? = null

    /**
     * Monotonic session generation, bumped by every createSession call. Verified:
     * every createSession() call site (openAndPreview's onOpened, and the two
     * cameraHandler.post{} blocks in startRecording/stopRecording) and every reader
     * (onConfigured/onConfigureFailed/onReady, delivered via [cameraExecutor], itself
     * a cameraHandler.post wrapper) executes exclusively on the camera thread -- so
     * `++sessionGeneration` has a single writer thread and @Volatile below is a
     * defensive addition, not a correctness requirement today; it does NOT make `++`
     * atomic, which is fine only because no second writer thread exists. A late
     * onConfigured from a superseded createCaptureSession must not clobber the
     * current session reference; it compares its captured generation and closes
     * itself instead.
     */
    @Volatile private var sessionGeneration = 0

    /**
     * Enumerates the logical camera's RAW-capable back lenses and populates [lenses],
     * [defaultLensIndex], [rawSpec] and the active-lens tracking fields. This is
     * per-lens [CameraManager.getCameraCharacteristics] binder IPC (plus stream-config
     * queries) -- it MUST be called off the main thread (the caller's cameraOps
     * dispatcher or Dispatchers.Default), exactly once, before any other method on
     * this class. Not called from init{} so construction itself is main-thread-safe.
     */
    fun initialize() {
        cameraId = cameraManager.cameraIdList.first { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        }
        lenses = enumerateLenses(cameraManager.getCameraCharacteristics(cameraId))
        defaultLensIndex = lenses.indexOfFirst { it.label == "1×" }.coerceAtLeast(0)
        activePhysicalId = lenses[defaultLensIndex].physicalId
        rawSpec = specFor(lenses[defaultLensIndex], 0)
        activeArraySize = lenses[defaultLensIndex].activeArraySize
        wbCalib = wbCalibFor(lenses[defaultLensIndex])
        // Permanent cheap sanity line for field debugging: catches a matrix-direction
        // or model regression immediately in logcat without needing a unit test.
        val g2000 = gainsFor(2000, 0)
        val g5600 = gainsFor(5600, 0)
        val g10000 = gainsFor(10000, 0)
        Log.i(
            TAG,
            "WB gains sanity 2000K=(${g2000.red},${g2000.greenEven},${g2000.blue}) " +
                "5600K=(${g5600.red},${g5600.greenEven},${g5600.blue}) " +
                "10000K=(${g10000.red},${g10000.greenEven},${g10000.blue})",
        )
    }

    /**
     * Opens the camera and starts a preview-only repeating request (AWB auto).
     * [onFailed] fires when the device errors out or the preview session cannot
     * be configured — e.g. an unsupported lens/size mode.
     */
    @SuppressLint("MissingPermission")
    fun openAndPreview(previewSurface: Surface, onFailed: () -> Unit = {}, onReady: () -> Unit) {
        this.previewSurface = previewSurface
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(listOf(previewSurface), forRecording = false, onFailed = onFailed) {
                    onReady()
                }
            }

            override fun onDisconnected(cam: CameraDevice) {
                Log.w(TAG, "camera disconnected")
                cam.close()
                device = null
            }

            override fun onError(cam: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error")
                cam.close()
                device = null
                onFailed()
            }
        }, cameraHandler)
    }

    /**
     * Selects which lens+size the NEXT session uses. Refused while recording.
     * Deliberately does not touch the live session: the caller re-keys the
     * preview SurfaceView on the mode, and the resulting surfaceCreated ->
     * openCamera -> openAndPreview reopens the camera with the new
     * [activePhysicalId] and [rawSpec] — the same proven path as returning
     * from background.
     */
    fun selectMode(lensIndex: Int, sizeIndex: Int): Boolean {
        if (recording) return false
        val lens = lenses.getOrNull(lensIndex) ?: return false
        if (sizeIndex !in lens.sizes.indices) return false
        activePhysicalId = lens.physicalId
        rawSpec = specFor(lens, sizeIndex)
        activeArraySize = lens.activeArraySize
        wbCalib = wbCalibFor(lens)
        return true
    }

    /**
     * Starts a RAW recording into [path] (decided by the caller; see [clipsDir]).
     * Gets the RAW Surface from NativeBridge, recreates the session with both
     * surfaces, and issues a fully manual repeating request. Returns false if the
     * native writer or session setup failed. Blocks until the session is configured.
     */
    fun startRecording(
        path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float,
        kelvin: Int, tint: Int,
    ): Boolean {
        if (recording) return false
        val preview = previewSurface ?: return false
        if (device == null) return false
        val spec = rawSpec
        val raw = NativeBridge.nativeStartRecording(
            path, spec.width, spec.height, spec.cfa, spec.whiteLevel,
            spec.blackLevel, spec.colorMatrix1, /* fpsNum = */ fps, /* fpsDen = */ 1,
            spec.deviceName,
        ) ?: return false

        rawSurface = raw
        recordFps = fps
        this.iso = iso.coerceIn(spec.isoRange)
        this.exposureNs = clampExposure(exposureNs, fps)
        this.focusDiopters = focusDiopters
        // Compare BEFORE assigning: a real WB-slider change invalidates any metered
        // override; startRecording called with the SAME kelvin/tint (the normal
        // meter -> record path) must NOT clear it.
        if (kelvin != this.kelvin || tint != this.tint) wbOverride = null
        this.kelvin = kelvin
        this.tint = tint
        manualSet = true
        // Set before frames can flow so the very first onCaptureCompleted forwards meta.
        recording = true

        val configured = CountDownLatch(1)
        var ok = false
        cameraHandler.post {
            session?.close()
            session = null
            createSession(
                listOf(preview, raw), forRecording = true,
                onFailed = { configured.countDown() }, // fail fast; ok stays false
            ) {
                ok = true
                configured.countDown()
            }
        }
        if (!configured.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS) || !ok) {
            recording = false
            Log.e(TAG, "recording session configuration failed")
            NativeBridge.nativeStopRecording() // discard the never-fed native writer
            rawSurface = null
            previewSurface?.let { ps ->
                cameraHandler.post { createSession(listOf(ps), forRecording = false) {} }
            }
            return false
        }
        return true
    }

    /** Applies new manual values live, during preview or recording. */
    fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float, kelvin: Int, tint: Int) {
        this.iso = iso.coerceIn(rawSpec.isoRange)
        this.exposureNs = if (recording) clampExposure(exposureNs, recordFps) else exposureNs
        this.focusDiopters = focusDiopters
        // ISO/shutter/focus pushes call this with the SAME kelvin/tint (must not
        // clear); an actual WB-slider move passes a changed value (must clear the
        // metered override so the model takes back over).
        if (kelvin != this.kelvin || tint != this.tint) wbOverride = null
        this.kelvin = kelvin
        this.tint = tint
        manualSet = true
        cameraHandler.post {
            val s = session ?: return@post
            try {
                if (recording) setRepeatingRecord(s) else setRepeatingPreview(s)
            } catch (e: Exception) {
                // CameraAccessException, closed-session IllegalStateException, or
                // abandoned-surface IllegalArgumentException; never fatal here.
                Log.e(TAG, "updateManual failed", e)
            }
        }
    }

    /**
     * Sets (or clears, with null) the exact metered WB-gains override -- see
     * [wbOverride]. Re-arms the repeating request immediately, the same way
     * [updateManual] does, so the override takes visible effect on the very next
     * frame instead of waiting for some other manual value to change.
     */
    fun setWbOverride(gains: RggbChannelVector?) {
        wbOverride = gains
        cameraHandler.post {
            val s = session ?: return@post
            try {
                if (recording) setRepeatingRecord(s) else setRepeatingPreview(s)
            } catch (e: Exception) {
                Log.e(TAG, "setWbOverride failed", e)
            }
        }
    }

    /**
     * One-shot hardware-3A meter at a normalized preview point (nx, ny in 0f..1f,
     * top-left origin, landscape preview orientation). Flips the preview to auto
     * with a metering region at the point, waits up to ~1500ms for AE/AF/AWB to
     * settle, reads back the values, restores full manual, and posts the result
     * (null on not-ready / recording / failure) to [onResult] on the camera thread.
     *
     * Runs entirely on [cameraHandler] and blocks it for the duration of the
     * convergence wait, which is what serializes metering against
     * [updateManual] / [startRecording] (queued posts wait their turn). Capture
     * callbacks are delivered on [meterHandler] — a separate thread — never on
     * [cameraHandler] itself, since that thread is blocked on [CountDownLatch.await]
     * for the duration.
     *
     * If [cameraHandler]'s looper has already quit (e.g. during teardown),
     * [android.os.Handler.post] returns false without ever running the posted
     * block, which would otherwise leave the caller's `metering` flag stuck true
     * forever. In that case [onResult] is invoked with `null` synchronously, on
     * the caller's own thread, instead of on the camera thread.
     */
    fun meterAt(nx: Float, ny: Float, onResult: (MeteredValues?) -> Unit) {
        val posted = cameraHandler.post {
            val dev = device
            val s = session
            val preview = previewSurface
            val arr = activeArraySize
            if (recording || dev == null || s == null || preview == null || arr == null) {
                onResult(null); return@post
            }
            try {
                val region = meteringRectFor(nx, ny, arr)
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    configureAutoMetering(this, region)
                }.build()

                val deadline = SystemClock.elapsedRealtime() + 1500L
                val done = CountDownLatch(1)
                val last = AtomicReference<TotalCaptureResult>()
                val cb = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        last.set(result)
                        if (settled(result) || SystemClock.elapsedRealtime() >= deadline) {
                            done.countDown()
                        }
                    }
                }
                s.setRepeatingRequest(req, cb, meterHandler())
                // Same configuration as the repeating request (modes + regions),
                // plus the AF trigger key -- a bare trigger-only request would flip
                // control modes for one frame instead of nudging the AF search.
                val trigger = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    configureAutoMetering(this, region)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }.build()
                s.capture(trigger, null, meterHandler())

                done.await(1800, TimeUnit.MILLISECONDS)
                val result = last.get()
                val out = if (result != null && usableAe(result)) readMetered(result) else null
                restoreManualPreview() // always restore before returning
                onResult(out)
            } catch (e: Exception) {
                Log.e(TAG, "meterAt failed", e)
                restoreManualPreview()
                onResult(null)
            }
        }
        if (!posted) onResult(null)
    }

    /** Shared AE/AF/AWB auto config + metering region for meterAt's repeating and
     * trigger requests -- keeps both requests in lockstep. */
    private fun configureAutoMetering(b: CaptureRequest.Builder, region: MeteringRectangle) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
    }

    private fun settled(r: TotalCaptureResult): Boolean {
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val af = r.get(CaptureResult.CONTROL_AF_STATE)
        val awb = r.get(CaptureResult.CONTROL_AWB_STATE)
        val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
        val afOk = af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        val awbOk = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        return aeOk && afOk && awbOk
    }

    private fun usableAe(r: TotalCaptureResult): Boolean {
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        return ae == null || ae != CaptureResult.CONTROL_AE_STATE_INACTIVE
    }

    private fun readMetered(r: TotalCaptureResult): MeteredValues {
        val isoOut = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso
        val expOut = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
        val focusOut = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
        val gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val (k, t) = if (gains != null) gainsToKelvinTint(gains) else (kelvin to tint)
        if (gains != null) {
            // Anchor the calibrated model's SHAPE to this real measurement. `k` was
            // just matched against the OLD anchor (gainsToKelvinTint/gainsFor above
            // read anchorGains/anchorKelvin before this assignment) -- update the
            // anchor only now, so the stored kelvin is never matched against itself.
            anchorGains = gains
            anchorKelvin = k
        }
        return MeteredValues(isoOut, expOut, focusOut, k, t, gains)
    }

    /** Re-arms the manual preview repeating request, reusing the same helper the
     * rest of the controller uses to (re)arm preview -- never throws. */
    private fun restoreManualPreview() {
        val s = session ?: return
        try {
            setRepeatingPreview(s)
        } catch (e: Exception) {
            Log.e(TAG, "restoreManualPreview failed", e)
        }
    }

    private fun meteringRectFor(nx: Float, ny: Float, arr: Rect): MeteringRectangle {
        // Preview is locked landscape and fills the active array; map normalized
        // (nx, ny) directly into active-array pixels. A ~10% box is the metered area.
        val cx = (arr.left + nx.coerceIn(0f, 1f) * arr.width()).toInt()
        val cy = (arr.top + ny.coerceIn(0f, 1f) * arr.height()).toInt()
        val halfW = (arr.width() * 0.05f).toInt().coerceAtLeast(1)
        val halfH = (arr.height() * 0.05f).toInt().coerceAtLeast(1)
        val left = (cx - halfW).coerceIn(arr.left, arr.right - 1)
        val top = (cy - halfH).coerceIn(arr.top, arr.bottom - 1)
        val right = (cx + halfW).coerceIn(left + 1, arr.right)
        val bottom = (cy + halfH).coerceIn(top + 1, arr.bottom)
        return MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /** Lazily starts a dedicated thread for meterAt's capture callbacks -- must
     * never be [cameraHandler], which is blocked on the convergence latch while
     * these callbacks need to run. Only called from [cameraHandler], so no
     * synchronization is needed around the fields it touches. */
    private fun meterHandler(): Handler {
        meterCallbackHandler?.let { return it }
        val t = HandlerThread("camera-meter").apply { start() }
        meterCallbackThread = t
        return Handler(t.looper).also { meterCallbackHandler = it }
    }

    /**
     * Stops recording and returns [framesWritten, framesDropped] from the native
     * layer. `[0, N]` means the writer failed outright (e.g. could not create the
     * file or the disk filled) — treat as a failed clip.
     *
     * Teardown ordering (mandatory): frames into the RAW surface are fully stopped
     * FIRST — stopRepeating() + abortCaptures(), then wait for the session's
     * onReady (idle) callback — and only THEN is nativeStopRecording() called.
     * A late frame arriving after native stop would leak a hardware buffer.
     */
    fun stopRecording(): LongArray {
        if (!recording) return longArrayOf(0, 0)

        // 1. Stop the flow of frames into the RAW surface.
        val idle = CountDownLatch(1)
        idleLatch = idle
        cameraHandler.post {
            val s = session
            if (s == null) {
                idle.countDown() // no session => nothing in flight, already idle
                return@post
            }
            try {
                s.stopRepeating()
                s.abortCaptures()
            } catch (e: CameraAccessException) {
                Log.e(TAG, "stopRepeating/abortCaptures failed", e)
                idle.countDown()
            } catch (e: IllegalStateException) {
                idle.countDown() // session already closed => already idle
            }
        }
        // 2. Wait until the session is actually idle (onReady) — no frames in flight.
        if (!idle.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "timed out waiting for session idle before native stop")
        }
        idleLatch = null
        recording = false

        // 3. Only now is it safe to tear down the native writer.
        val stats = NativeBridge.nativeStopRecording()
        rawSurface = null

        // 4. Return to preview-only.
        previewSurface?.let { ps ->
            cameraHandler.post {
                session?.close()
                session = null
                createSession(listOf(ps), forRecording = false) {}
            }
        }
        return stats
    }

    /** Releases the session, device and camera thread. Stops any active recording. */
    fun close() {
        if (recording) stopRecording()
        cameraHandler.post {
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            session?.close()
            session = null
            device?.close()
            device = null
            meterCallbackThread?.quitSafely()
            meterCallbackThread = null
            meterCallbackHandler = null
        }
        cameraThread.quitSafely()
    }

    // --- internals -------------------------------------------------------------

    /**
     * Enumerates the logical camera's physical lenses: dedupes sensors exposed
     * under two ids (same focal length -> keep the id with the most RAW sizes),
     * sorts widest-first, and labels each with its zoom factor relative to the
     * main lens (the focal length the logical camera itself advertises).
     * Falls back to a single logical-camera entry when nothing enumerates.
     */
    private fun enumerateLenses(logicalCh: CameraCharacteristics): List<LensInfo> {
        val candidates = logicalCh.physicalCameraIds.mapNotNull { id ->
            try {
                buildLensCandidate(id, cameraManager.getCameraCharacteristics(id))
            } catch (e: Exception) {
                Log.w(TAG, "skipping physical camera $id", e)
                null
            }
        }
        val deduped = candidates
            .groupBy { it.focalMm }
            .map { (_, group) -> group.maxBy { it.sizes.size } }
            .sortedByDescending { it.fovMetric }
            .ifEmpty { listOfNotNull(buildLensCandidate(null, logicalCh)) }
        check(deduped.isNotEmpty()) { "no RAW-capable back lens" }
        val logicalFocal =
            logicalCh.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val mainIdx = if (logicalFocal == null) 0
        else deduped.indices.minBy { abs(deduped[it].focalMm - logicalFocal) }
        val mainFov = deduped[mainIdx].fovMetric
        return deduped.map { lens ->
            val zoom = mainFov / lens.fovMetric
            val label =
                if (zoom < 0.95f) "%.1f×".format(Locale.US, zoom) else "${zoom.roundToInt()}×"
            lens.copy(label = label)
        }
    }

    /** Null when [ch] lacks RAW, manual-sensor support, or any required key. */
    private fun buildLensCandidate(physicalId: String?, ch: CameraCharacteristics): LensInfo? {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val focal =
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: return null
        val physSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        // Camera2 CFA constants share our Cfa enum order: RGGB=0 GRBG=1 GBRG=2 BGGR=3.
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        val whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        val blackPattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return null
        val xform = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
        // Second calibration illuminant/matrix, and both illuminant codes: all
        // nullable (not every sensor exposes a second calibration point) -- consumed
        // by gainsFor's DNG-style interpolation, never gate lens enumeration.
        val xform2 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        // SDK type is Key<Byte> despite the DNG/EXIF codes being small ints; widen so
        // LensInfo/illuminantCct can work with plain Int like every other code path.
        val illum1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
        val illum2 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        val sensRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val sorted = rawSizes.sortedByDescending { it.width.toLong() * it.height }
        val maxArea = sorted.first().width.toLong() * sorted.first().height
        val sizes = sorted.map { s ->
            val minDur = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
            LensSize(
                width = s.width, height = s.height,
                maxFps = if (minDur > 0) (1e9 / minDur).toInt() else 30,
                label = sizeLabel(s.width, s.height, maxArea),
            )
        }
        return LensInfo(
            physicalId = physicalId,
            label = "", // filled by enumerateLenses once the main lens is known
            focalMm = focal,
            fovMetric = physSize.width / focal,
            sizes = sizes,
            cfa = cfa,
            whiteLevel = whiteLevel,
            blackLevel = IntArray(4).also { blackPattern.copyTo(it, 0) },
            // Row-major 3x3: index i -> row i/3, column i%3 (getElement takes column, row).
            colorMatrix1 = FloatArray(9) { i -> xform.getElement(i % 3, i / 3).toFloat() },
            isoRange = sensRange.lower..sensRange.upper,
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            activeArraySize = activeArray,
            colorMatrix2 = xform2?.let { t -> FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() } },
            illuminant1 = illum1,
            illuminant2 = illum2,
        )
    }

    /** "4:3" / "16:9" for full-area sizes, "LOW" for binned (under half the max area). */
    private fun sizeLabel(w: Int, h: Int, maxArea: Long): String {
        if (w.toLong() * h < maxArea / 2) return "LOW"
        val aspect = w.toFloat() / h
        return when {
            abs(aspect - 4f / 3f) < 0.05f -> "4:3"
            abs(aspect - 16f / 9f) < 0.1f -> "16:9"
            else -> "${h}p"
        }
    }

    private fun specFor(lens: LensInfo, sizeIndex: Int): RawSpec {
        val size = lens.sizes[sizeIndex]
        return RawSpec(
            width = size.width, height = size.height, cfa = lens.cfa,
            whiteLevel = lens.whiteLevel, blackLevel = lens.blackLevel,
            colorMatrix1 = lens.colorMatrix1, isoRange = lens.isoRange,
            maxFps = size.maxFps, minFocusDiopters = lens.minFocusDiopters,
            deviceName = Build.MODEL,
        )
    }

    private fun createSession(
        surfaces: List<Surface>, forRecording: Boolean,
        onFailed: () -> Unit = {}, onConfigured: () -> Unit,
    ) {
        val dev = device ?: run { onFailed(); return }
        val generation = ++sessionGeneration
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        activePhysicalId?.let { setPhysicalCameraId(it) }
                    }
                },
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (generation != sessionGeneration) {
                            // A newer createSession superseded this one while it was
                            // configuring; do not clobber the current session.
                            Log.w(TAG, "discarding stale session (gen $generation)")
                            s.close()
                            return
                        }
                        session = s
                        try {
                            if (forRecording) setRepeatingRecord(s) else setRepeatingPreview(s)
                            onConfigured()
                        } catch (e: Exception) {
                            // CameraAccessException, or IllegalArgumentException if a
                            // target surface was abandoned mid-flight (activity
                            // backgrounded); must not kill the camera thread.
                            Log.e(TAG, "setRepeatingRequest failed", e)
                            onFailed()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "session configuration failed (recording=$forRecording)")
                        if (generation == sessionGeneration) onFailed()
                    }

                    override fun onReady(s: CameraCaptureSession) {
                        // Fires when the session has no work in flight; used by
                        // stopRecording() as the frames-stopped barrier.
                        if (generation == sessionGeneration) idleLatch?.countDown()
                    }
                },
            )
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            // OutputConfiguration() throws IllegalArgumentException ("Surface was
            // abandoned") when the preview surface died while this call was queued,
            // e.g. HOME pressed right as a recording started (observed on device).
            // An uncaught exception here kills the camera HandlerThread and the
            // process; fail soft so the UI can reopen with a fresh surface instead.
            Log.e(TAG, "createCaptureSession failed", e)
            onFailed()
        }
    }

    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            if (manualSet) applyManual(this, withFrameDuration = false)
        }.build()
        s.setRepeatingRequest(req, null, cameraHandler)
    }

    private fun setRepeatingRecord(s: CameraCaptureSession) {
        val dev = device ?: return
        val raw = rawSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface ?: return)
            addTarget(raw)
            applyManual(this, withFrameDuration = true)
        }.build()
        s.setRepeatingRequest(req, captureCallback, cameraHandler)
    }

    /** DNG/EXIF reference-illuminant code -> CCT (Kelvin). Table values from the
     * DNG/EXIF LightSource enumeration; unlisted/unknown codes return null so the
     * caller can apply its own default (see [wbCalibFor]). */
    private fun illuminantCct(code: Int?): Int? = when (code) {
        17 -> 2856 // Standard A
        21 -> 6504 // D65
        23 -> 5003 // D50
        20 -> 5503 // D55
        22 -> 7504 // D75
        1 -> 5503  // Daylight
        2 -> 4230  // Fluorescent
        3 -> 2856  // Tungsten
        else -> null
    }

    /** Builds the active lens's [WbCalib]: illuminant codes resolved to CCTs
     * (defaulting illuminant1 -> 2856K, illuminant2 -> 6504K when absent/unknown --
     * the DNG spec's own conventional default pair). */
    private fun wbCalibFor(lens: LensInfo): WbCalib = WbCalib(
        matrix1 = lens.colorMatrix1,
        cct1 = illuminantCct(lens.illuminant1) ?: 2856,
        matrix2 = lens.colorMatrix2,
        cct2 = illuminantCct(lens.illuminant2) ?: 6504,
    )

    /**
     * Kelvin -> CIE 1931 xy chromaticity of the Planckian locus: Kim et al.'s cubic
     * approximation (valid 1667K-25000K; our UI range 2000K-10000K is safely
     * inside it).
     */
    private fun kelvinToXy(kelvinValue: Int): Pair<Double, Double> {
        val t = kelvinValue.toDouble()
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /**
     * DNG-convention color-matrix interpolation by inverse CCT between the two
     * calibration illuminants. Falls back to matrix1 alone when there's no second
     * illuminant or the two CCTs coincide (would divide by zero otherwise).
     */
    private fun interpolatedColorMatrix(calib: WbCalib, kelvinValue: Int): FloatArray {
        val cm2 = calib.matrix2
        if (cm2 == null || calib.cct1 == calib.cct2) return calib.matrix1
        val invK = 1.0 / kelvinValue
        val invK1 = 1.0 / calib.cct1
        val invK2 = 1.0 / calib.cct2
        val w = ((invK - invK2) / (invK1 - invK2)).coerceIn(0.0, 1.0)
        return FloatArray(9) { i -> (w * calib.matrix1[i] + (1.0 - w) * cm2[i]).toFloat() }
    }

    /**
     * DNG-calibrated model gains at tint=0, BEFORE the >=1 renormalization --
     * the *shape* of the kelvin -> gains curve (kelvin -> CIE xy -> XYZ ->
     * multiply by the sensor's interpolated calibration matrix -> green-
     * normalize). This is deliberately NOT the final gains: [gainsFor] uses the
     * RATIO of this curve at two kelvins to carry a real measurement (the
     * anchor) across the kelvin range, because on at least one real device
     * (Pixel 7 Pro) the static SENSOR_COLOR_TRANSFORM1/2 calibration matrices
     * are placeholders (textbook XYZ->sRGB, not a per-unit calibration) and
     * this curve's absolute LEVEL cannot be trusted -- only its shape (how
     * gains change as kelvin moves) is assumed meaningful.
     */
    private fun modelGainsRaw(kelvinValue: Int): Pair<Double, Double> {
        val calib = wbCalib
        val (x, y) = kelvinToXy(kelvinValue)
        val safeY = if (abs(y) < 1e-9) 1e-9 else y
        val xyzX = x / safeY
        val xyzY = 1.0
        val xyzZ = (1.0 - x - safeY) / safeY
        val cm = interpolatedColorMatrix(calib, kelvinValue)
        // cm is row-major [R row, G row, B row] x [X, Y, Z] -- same convention as
        // colorMatrix1's extraction (index i -> row i/3, column i%3).
        val nR = (cm[0] * xyzX + cm[1] * xyzY + cm[2] * xyzZ).coerceAtLeast(1e-4)
        val nG = (cm[3] * xyzX + cm[4] * xyzY + cm[5] * xyzZ).coerceAtLeast(1e-4)
        val nB = (cm[6] * xyzX + cm[7] * xyzY + cm[8] * xyzZ).coerceAtLeast(1e-4)
        val modelR = (nG / nR).coerceIn(1e-2, 8.0)
        val modelB = (nG / nB).coerceIn(1e-2, 8.0)
        return modelR to modelB
    }

    /**
     * Kelvin/tint -> per-channel gains that cancel the sensor's *own* response
     * to that color temperature -- anchored to a real measurement rather than
     * trusting the calibrated model's absolute level (see [modelGainsRaw]'s
     * kdoc for why). `raw = anchor (.) (model(kelvin) / model(anchorKelvin))`
     * channel-wise: the anchor (real AWB gains from the most recent successful
     * [meterAt], or a DEFAULT_ANCHOR_* seed before the first one) supplies the
     * absolute level; the calibrated model only supplies the *relative* shape
     * of how gains should move away from the anchor's kelvin. Tint is applied
     * multiplicatively to the (already anchor-scaled) green channel, then the
     * whole vector is renormalized so gains stay >=1 (required by many HALs)
     * without disturbing the R:G:B ratio -- unchanged from the prior model.
     */
    private fun gainsFor(kelvinValue: Int, tintValue: Int): RggbChannelVector {
        val anchor = anchorGains
        val aKelvin = anchorKelvin
        val (modelR, modelB) = modelGainsRaw(kelvinValue)
        val (anchorModelR, anchorModelB) = modelGainsRaw(aKelvin)
        val anchorR = anchor?.red?.toDouble() ?: DEFAULT_ANCHOR_R
        val anchorG = anchor?.let { ((it.greenEven + it.greenOdd) / 2f).toDouble() } ?: DEFAULT_ANCHOR_G
        val anchorB = anchor?.blue?.toDouble() ?: DEFAULT_ANCHOR_B

        val tintFactor = (1.0 - tintValue / 100.0).coerceIn(0.3, 2.0)
        var gainR = (anchorR * (modelR / anchorModelR)).coerceIn(1e-2, 8.0)
        var gainB = (anchorB * (modelB / anchorModelB)).coerceIn(1e-2, 8.0)
        var gainG = anchorG * tintFactor
        val minGain = minOf(gainR, gainG, gainB)
        if (minGain < 1.0) {
            val scale = 1.0 / minGain
            gainR *= scale; gainG *= scale; gainB *= scale
        }
        return RggbChannelVector(gainR.toFloat(), gainG.toFloat(), gainG.toFloat(), gainB.toFloat())
    }

    /**
     * Inverse of gainsFor: map measured AWB per-channel gains back to the nearest
     * (kelvin, tint) representable by the manual controls. Matching is purely on
     * ln(gainR/gainB) against gainsFor(k, 0) for each kelvin candidate, for two
     * reasons that both survive a fixed anchor state: (1) it's invariant to the
     * uniform >=1 renormalization gainsFor applies (a common scale factor cancels
     * in the ratio); (2) away from the [1e-2, 8] per-channel clamp, the anchor
     * only adds a constant offset to the unanchored model's own log-ratio, so
     * whatever monotonicity that curve has is preserved. (On real hardware the
     * clamp CAN saturate at the extreme low-kelvin end -- confirmed on this
     * Pixel 7 Pro's actual calibration matrix, see wb-fix-report.md's "Fix
     * cycle" section -- which breaks the *exact* constant-offset property at
     * that point; monotonicity across the full candidate set held in every
     * case checked there, which is what this match actually depends on.) Since
     * gainsFor(k, 0) is deterministic for a fixed anchor, feeding it its own
     * output back in reproduces the generating k exactly regardless of any
     * clamp. Tint is recovered by comparing the measured green gain against the
     * model's green at the matched kelvin (same normalization AND same anchor
     * state on both sides, so this also round-trips exactly for gains produced
     * by gainsFor(k, 0)).
     */
    fun gainsToKelvinTint(gains: RggbChannelVector): Pair<Int, Int> {
        val gR = gains.red.coerceAtLeast(1e-6f)
        val gG = ((gains.greenEven + gains.greenOdd) / 2f).coerceAtLeast(1e-6f)
        val gB = gains.blue.coerceAtLeast(1e-6f)
        val targetLogRatio = ln(gR / gB)
        var bestK = KELVIN_CANDIDATES.first()
        var bestErr = Float.MAX_VALUE
        for (k in KELVIN_CANDIDATES) {
            val g = gainsFor(k, 0)
            val err = abs(ln(g.red / g.blue) - targetLogRatio)
            if (err < bestErr) { bestErr = err; bestK = k }
        }
        val gGModel = gainsFor(bestK, 0).greenEven.coerceAtLeast(1e-6f)
        val tintFactorMeasured = (gG / gGModel).coerceIn(0.3f, 2f)
        val rawTint = ((1f - tintFactorMeasured) * 100f).roundToInt()
        val bestT = TINT_CANDIDATES.minByOrNull { abs(it - rawTint) } ?: 0
        return bestK to bestT
    }

    private fun applyManual(b: CaptureRequest.Builder, withFrameDuration: Boolean) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        if (withFrameDuration) {
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / recordFps)
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, wbOverride ?: gainsFor(kelvin, tint))
        b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
    }

    /** Exposure must stay strictly below the frame duration (1e9 / fps). */
    private fun clampExposure(exposureNs: Long, fps: Int): Long =
        exposureNs.coerceIn(1L, 1_000_000_000L / fps - 1)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            if (!recording) return
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val isoOut = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso
            val expOut = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
            val focusOut = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
            // COLOR_CORRECTION_GAINS is not reliably echoed back in the result on this
            // device/config (observed null during recording); wbOverride ?: gainsFor(...)
            // is the exact value applyManual set on the request, so it's a faithful fallback.
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: (wbOverride ?: gainsFor(kelvin, tint))
            val wbR = safeInv(gains.red)
            val wbG = safeInv((gains.greenEven + gains.greenOdd) / 2f)
            val wbB = safeInv(gains.blue)
            NativeBridge.nativePushFrameMeta(ts, isoOut, expOut, focusOut, wbR, wbG, wbB)
        }
    }

    private fun safeInv(x: Float): Float = if (x > 0f) 1f / x else 1f

    companion object {
        private const val TAG = "CameraController"
        private const val SESSION_TIMEOUT_S = 3L
        // Mirror of RecordScreen.KELVIN_STOPS / TINT_STOPS. gainsToKelvinTint returns
        // values from these sets so the metered result lands exactly on a slider tick.
        private val KELVIN_CANDIDATES = intArrayOf(2000, 2700, 3200, 4000, 5000, 5600, 6500, 7500, 9000, 10000)
        private val TINT_CANDIDATES = (-50..50 step 5).toList()
        // Seed anchor for gainsFor before the first successful meterAt of this process
        // (see anchorGains's kdoc): a typical green-dominant phone-sensor neutral at
        // 5600K (anchorKelvin's own default). Better than trusting a possibly-
        // placeholder calibration matrix's absolute level on unknown hardware, and
        // roughly harmless (close to gainsFor's old floor-1 behavior) on honest
        // hardware -- gets replaced by real numbers the moment any meter succeeds,
        // including the automatic startup one (RecordViewModel.didAutoMeter).
        private const val DEFAULT_ANCHOR_R = 2.0
        private const val DEFAULT_ANCHOR_G = 1.0
        private const val DEFAULT_ANCHOR_B = 1.7
        // Identity 3x3 (row-major rationals num/den): color correction here is
        // gains-only, no cross-channel matrix warp.
        private val IDENTITY_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            )
        )
    }
}
