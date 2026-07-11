package com.shez.rawcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Camera2 controller for RAW video capture with fully manual exposure.
 *
 * Lifecycle: construct (queries [rawSpec]) -> [openAndPreview] -> [startRecording] /
 * [updateManual] / [stopRecording] (repeatable) -> [close].
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

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String

    /** Sensor/RAW capabilities, queried once from CameraCharacteristics at init. */
    val rawSpec: RawSpec

    /** Directory Task 11 should place clip files in (created eagerly). */
    val clipsDir: File = File(context.getExternalFilesDir(null), "clips").apply { mkdirs() }

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawSurface: Surface? = null

    @Volatile private var recording = false
    @Volatile private var manualSet = false
    @Volatile private var recordFps = 24
    @Volatile private var iso = 100
    @Volatile private var exposureNs = 10_000_000L
    @Volatile private var focusDiopters = 0f
    /** Set by stopRecording(); counted down by the session's onReady (idle) callback. */
    @Volatile private var idleLatch: CountDownLatch? = null

    init {
        cameraId = cameraManager.cameraIdList.first { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        }
        val ch = cameraManager.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        // Camera2 CFA constants share our Cfa enum order: RGGB=0 GRBG=1 GBRG=2 BGGR=3.
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)!!
        val blackLevel = IntArray(4).also {
            ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)!!.copyTo(it, 0)
        }
        val xform = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)!!
        // Row-major 3x3: index i -> row i/3, column i%3 (getElement takes column, row).
        val colorMatrix1 = FloatArray(9) { i -> xform.getElement(i % 3, i / 3).toFloat() }
        val sensRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        val minFrameDurNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)
        rawSpec = RawSpec(
            width = size.width,
            height = size.height,
            cfa = cfa,
            whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)!!,
            blackLevel = blackLevel,
            colorMatrix1 = colorMatrix1,
            isoRange = sensRange.lower..sensRange.upper,
            maxFps = (1e9 / minFrameDurNs).toInt(),
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            deviceName = Build.MODEL,
        )
    }

    /** Opens the camera and starts a preview-only repeating request (AWB auto). */
    @SuppressLint("MissingPermission")
    fun openAndPreview(previewSurface: Surface, onReady: () -> Unit) {
        this.previewSurface = previewSurface
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(listOf(previewSurface), forRecording = false) { onReady() }
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
            }
        }, cameraHandler)
    }

    /**
     * Starts a RAW recording into [path] (decided by the caller; see [clipsDir]).
     * Gets the RAW Surface from NativeBridge, recreates the session with both
     * surfaces, and issues a fully manual repeating request. Returns false if the
     * native writer or session setup failed. Blocks until the session is configured.
     */
    fun startRecording(
        path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float,
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
        manualSet = true

        val configured = CountDownLatch(1)
        var ok = false
        cameraHandler.post {
            session?.close()
            session = null
            createSession(listOf(preview, raw), forRecording = true) {
                ok = true
                configured.countDown()
            }
        }
        if (!configured.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS) || !ok) {
            Log.e(TAG, "recording session configuration failed")
            NativeBridge.nativeStopRecording() // discard the never-fed native writer
            rawSurface = null
            previewSurface?.let { ps ->
                cameraHandler.post { createSession(listOf(ps), forRecording = false) {} }
            }
            return false
        }
        recording = true
        return true
    }

    /** Applies new manual values live, during preview or recording. */
    fun updateManual(iso: Int, exposureNs: Long, focusDiopters: Float) {
        this.iso = iso.coerceIn(rawSpec.isoRange)
        this.exposureNs = if (recording) clampExposure(exposureNs, recordFps) else exposureNs
        this.focusDiopters = focusDiopters
        manualSet = true
        cameraHandler.post {
            val s = session ?: return@post
            try {
                if (recording) setRepeatingRecord(s) else setRepeatingPreview(s)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "updateManual failed", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "updateManual on closed session", e)
            }
        }
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
            try {
                session?.stopRepeating()
                session?.abortCaptures()
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
        }
        cameraThread.quitSafely()
    }

    // --- internals -------------------------------------------------------------

    private fun createSession(
        surfaces: List<Surface>, forRecording: Boolean, onConfigured: () -> Unit,
    ) {
        val dev = device ?: return
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { OutputConfiguration(it) },
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        if (forRecording) setRepeatingRecord(s) else setRepeatingPreview(s)
                        onConfigured()
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "setRepeatingRequest failed", e)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configuration failed (recording=$forRecording)")
                }

                override fun onReady(s: CameraCaptureSession) {
                    // Fires when the session has no work in flight; used by
                    // stopRecording() as the frames-stopped barrier.
                    idleLatch?.countDown()
                }
            },
        )
        dev.createCaptureSession(config)
    }

    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
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
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            applyManual(this, withFrameDuration = true)
        }.build()
        s.setRepeatingRequest(req, captureCallback, cameraHandler)
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
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val wbR: Float
            val wbG: Float
            val wbB: Float
            if (gains != null) {
                wbR = safeInv(gains.red)
                wbG = safeInv((gains.greenEven + gains.greenOdd) / 2f)
                wbB = safeInv(gains.blue)
            } else {
                wbR = 1f; wbG = 1f; wbB = 1f
            }
            NativeBridge.nativePushFrameMeta(ts, isoOut, expOut, focusOut, wbR, wbG, wbB)
        }
    }

    private fun safeInv(x: Float): Float = if (x > 0f) 1f / x else 1f

    companion object {
        private const val TAG = "CameraController"
        private const val SESSION_TIMEOUT_S = 3L
    }
}
