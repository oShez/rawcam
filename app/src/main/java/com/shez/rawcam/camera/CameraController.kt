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
    )

    /** Result of a tap-to-meter pass: converged 3A readings snapped to manual control values. */
    data class MeteredValues(
        val iso: Int,
        val exposureNs: Long,
        val focusDiopters: Float,
        val kelvin: Int,
        val tint: Int,
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
        return MeteredValues(isoOut, expOut, focusOut, k, t)
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

    /**
     * Approximate blackbody color temperature -> RGB (0..255), the well-known
     * Tanner Helland curve fit (public domain, valid 1000K-40000K; our UI
     * range is 2000K-10000K, safely inside it). This is "the color of light
     * at this temperature" -- gainsFor inverts it to get the gains that
     * cancel that cast.
     */
    private fun kelvinRgb(kelvinValue: Int): Triple<Float, Float, Float> {
        val temp = kelvinValue / 100.0
        val red = if (temp <= 66.0) 255.0
        else (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val green = if (temp <= 66.0) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }
        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }
        return Triple(red.toFloat(), green.toFloat(), blue.toFloat())
    }

    /**
     * Kelvin/tint -> per-channel gains that cancel the color temperature's
     * cast, normalized so green stays the reference channel (gain 1 before
     * tint). Tint biases green relative to red/blue: positive = more
     * magenta (green reduced), negative = more green (green raised).
     */
    private fun gainsFor(kelvinValue: Int, tintValue: Int): RggbChannelVector {
        val (r, g, b) = kelvinRgb(kelvinValue)
        val gRef = g.coerceAtLeast(1f)
        val gainR = (gRef / r.coerceAtLeast(1f)).coerceAtLeast(1f)
        val gainB = (gRef / b.coerceAtLeast(1f)).coerceAtLeast(1f)
        val tintFactor = (1.0 - tintValue / 100.0).toFloat().coerceIn(0.3f, 2f)
        val gainG = tintFactor
        return RggbChannelVector(gainR, gainG, gainG, gainB)
    }

    /**
     * Inverse of gainsFor: map measured AWB per-channel gains back to the nearest
     * (kelvin, tint) representable by the manual controls. gainsFor maps kelvin to
     * a neutralizing red/blue gain pair (their ratio is monotonic in kelvin) with
     * green carrying the tint as tintFactor = (1 - tint/100). We pick the kelvin
     * candidate whose gainsFor(k, 0) red/blue ratio best matches the measured one,
     * then recover tint from the measured green gain directly (gainG = 1 - tint/100),
     * snapped to the nearest tint candidate.
     */
    fun gainsToKelvinTint(gains: RggbChannelVector): Pair<Int, Int> {
        val gR = gains.red.coerceAtLeast(1e-3f)
        val gG = ((gains.greenEven + gains.greenOdd) / 2f).coerceAtLeast(1e-3f)
        val gB = gains.blue.coerceAtLeast(1e-3f)
        val targetLogRatio = ln(gR / gB)
        var bestK = KELVIN_CANDIDATES.first()
        var bestErr = Float.MAX_VALUE
        for (k in KELVIN_CANDIDATES) {
            val g = gainsFor(k, 0)
            val err = abs(ln(g.red / g.blue) - targetLogRatio)
            if (err < bestErr) { bestErr = err; bestK = k }
        }
        val tintFactor = gG.coerceIn(0.3f, 2f)
        val rawTint = ((1f - tintFactor) * 100f).roundToInt()
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
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsFor(kelvin, tint))
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
            // device/config (observed null during recording); gainsFor(kelvin, tint) is
            // the exact value applyManual set on the request, so it's a faithful fallback.
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: gainsFor(kelvin, tint)
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
