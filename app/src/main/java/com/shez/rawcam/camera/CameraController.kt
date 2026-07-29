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
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.settings.OisMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /** DNG/EXIF LightSource codes + second calibration matrix, straight from
         * the active [LensProfile] -- illuminant2==0 means no second calibration
         * point (colorMatrix2 is then all-zero and must not be written to the
         * DNG as if it were real -- see dng_writer.cpp). */
        val illuminant1: Int, val illuminant2: Int, val colorMatrix2: FloatArray,
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
    /** Id of the primary logical camera (the normal back logical camera, e.g. "0")
     * -- opened for every non-[LensProfile.standalone] lens; physical children are
     * reached by tagging OutputConfigurations under this same open device, never
     * by opening their id directly. */
    private lateinit var primaryCameraId: String

    /** Selectable back lenses, widest first. At least one entry. Populated by
     * [initialize]; empty until then. */
    @Volatile var lenses: List<LensProfile> = emptyList()
        private set

    /** Populated by [initialize]; null until then. */
    @Volatile var profile: DeviceProfile? = null
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

    /** Identity key for the active lens -- [LensProfile.cameraId] verbatim, used ONLY
     * to key per-lens WB state ([anchorState], [presetCurves]) and to detect an
     * actual lens change in [selectMode]/[meterAt]. NOT used to decide session
     * tagging or which camera id to open -- see [sessionTagId]/[activeCameraId]. */
    @Volatile private var activePhysicalId: String? = null

    /** Camera id actually passed to [CameraManager.openCamera] for the active lens:
     * [primaryCameraId] for a physical-child lens (or the single-lens fallback), or
     * the lens's own id when [LensProfile.standalone]. Set by [applySelectedLens]. */
    @Volatile private var activeCameraId: String = ""

    /** Id of the [CameraDevice] currently held in [device] (or about to be), so
     * [openAndPreview] can tell "reopening the same id" (the framework implicitly
     * disconnects the old device first -- see that function's kdoc) from "crossing
     * to a genuinely different top-level device" (e.g. into/out of a standalone
     * lens), which has no such guarantee and needs an explicit close first. */
    @Volatile private var openDeviceId: String? = null

    /** Physical camera id THIS SESSION's OutputConfigurations are tagged with via
     * setPhysicalCameraId -- null for the single-lens fallback AND for a standalone
     * lens ([LensProfile.standalone]): a standalone device's own captures already
     * target exactly that sensor and must never be tagged. Set by
     * [applySelectedLens]; distinct from [activePhysicalId], which keeps its
     * original per-lens-identity meaning regardless of standalone-ness. */
    @Volatile private var sessionTagId: String? = null

    /** Active-array size (sensor pixel bounds) of the active lens; used by [meterAt]
     * to map a normalized tap point into a metering region. Cached from the same
     * per-lens characteristics snapshot [LensDiscovery] already resolved — never
     * refetched on the open/select hot path. */
    @Volatile private var activeArraySize: Rect? = null

    /** Active lens's supported OIS modes ([LensProfile.oisModes]), tracked
     * alongside [activeArraySize] with the same lifecycle (set in [initialize] and
     * [selectMode], read by [applyManual] to gate [oisMode] ON/OFF requests against
     * what the hardware actually supports). Null means the characteristic itself
     * was absent -- no OIS key is ever set in that case, regardless of [oisMode]. */
    @Volatile private var activeOisModes: IntArray? = null

    /** User's OIS preference (Settings.oisMode), pushed here by the caller's settings
     * collector reaction. AUTO leaves the HAL default (no key set) since Camera2 does
     * not expose a "let the driver decide" OIS mode of its own -- omitting the key is
     * the closest equivalent, matching AE/AF/AWB's own auto-vs-manual convention
     * elsewhere in this controller. Read by [applyManual] on every repeating-request
     * (re)build, same as iso/exposureNs/focusDiopters/kelvin/tint. */
    @Volatile var oisMode: OisMode = OisMode.AUTO

    /** User's metering-region size (Settings.meterRegion), as a fraction of the
     * active-array width/height -- SMALL 0.05f / MEDIUM 0.10f / LARGE 0.20f. Read
     * by [meteringRectFor] (half-width = this / 2) on every [meterAt] call, so a
     * change only takes effect on the NEXT tap-to-meter -- there is no live
     * request to re-arm, unlike [oisMode]/manual values. Pushed here unconditionally
     * by the caller's settings collector reaction, same rationale as [oisMode]'s
     * kdoc: the first emission must apply a saved non-default region. */
    @Volatile var meterRegionFraction: Float = 0.10f

    /** User's diagnostic-logging preference (Settings.debugLogging), pushed here
     * unconditionally by the caller's settings collector reaction -- same rationale
     * as [oisMode]/[meterRegionFraction]'s kdoc: the first emission must apply a
     * saved `true`. Gates the per-[meterAt] WB result log below and the meterAt-entry
     * log in RecordViewModel; the one-time [initialize] sanity log is unconditional
     * regardless of this flag (spec requirement -- one line per process). */
    @Volatile var debugLogging: Boolean = false

    /** Directory clip files live in. A plain path -- no I/O -- so constructing a
     * CameraController never touches disk; callers that actually need the
     * directory to exist (StatFs reads, starting a recording) call .mkdirs()
     * themselves at the point of use, both already off the main thread. */
    val clipsDir: File = File(context.getExternalFilesDir(null), "clips")

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

    /**
     * Mirrors [Settings.zebraEnabled]; written by RecordViewModel's settings
     * collector (same pattern as [debugLogging]) and read by [createSession] when it
     * assembles the output list. Because the Settings screen is unreachable while
     * recording (its nav button is disabled), this can never flip mid-recording.
     */
    @Volatile var zebraEnabled: Boolean = false

    /** Optional low-res YUV analysis stream feeding the zebra overlay. Created and
     * torn down only from the camera thread (inside [createSession] / [close]). */
    private var zebraReader: ImageReader? = null
    private var zebraSurface: Surface? = null
    private var zebraThread: HandlerThread? = null
    private var zebraHandler: Handler? = null

    /** Reused across frames so the plane copy allocates nothing steady-state --
     * same reasoning as the capture queue's ring buffer. Analysis-thread-only. */
    private var zebraBuffer: ByteArray = ByteArray(0)
    private var lastZebraNs = 0L

    private val _zebraMask = MutableStateFlow<ZebraMask?>(null)

    /** Latest clipped-highlight mask, or null when zebra is off or unavailable on
     * this device. Updated at most every [ZEBRA_MIN_INTERVAL_NS] from the analysis
     * thread; consumers must read it in a way that does not force a full
     * recomposition (see RecordScreen's ZebraOverlay). */
    val zebraMask: StateFlow<ZebraMask?> = _zebraMask.asStateFlow()

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

    /** Real measured AWB gains from the most recent successful meter ON EACH
     * PHYSICAL LENS (""=unknown/null id), and the kelvin [gainsToKelvinTint]
     * matched them to (against that lens's own anchor state BEFORE this one) --
     * see [gainsFor]'s kdoc. This is what lets the slider model track this
     * device's *actual* sensor response instead of a static calibration matrix
     * that may be a placeholder (observed on a Pixel 7 Pro: SENSOR_COLOR_TRANSFORM2
     * was the textbook XYZ->sRGB matrix, not a real per-unit calibration).
     * Keyed per lens because each physical camera has its own sensor with its
     * own absolute color response -- a real measurement taken on one lens is
     * not a valid anchor for a DIFFERENT lens (bug found and fixed 2026-07-21:
     * this field used to be a single unkeyed value, so switching lenses on this
     * device's 3 RAW-capable back cameras silently carried the old lens's
     * absolute gain level onto the new lens's sensor, scaled by the new lens's
     * own -- correctly per-lens -- [presetCurves] shape, producing an internally
     * inconsistent result: right shape, wrong level, until the user happened to
     * change kelvin/tint or re-meter). No entry for a lens until its first
     * successful meter this process; [gainsFor] substitutes DEFAULT_ANCHOR_* in
     * that case. Written only from [readMetered] and [restoreWbAnchor]; read
     * cross-thread by [wbAnchorOrNull] (RecordViewModel.persistCaptureState, not
     * the camera thread) and [restoreWbAnchor] (RecordViewModel's init restore,
     * on cameraOps) -- same @Volatile whole-map-replacement (copy-on-write)
     * pattern as [presetCurves]. */
    @Volatile private var anchorState: Map<String, Pair<RggbChannelVector, Int>> = emptyMap()

    /** Real hardware AWB-preset curve per physical lens id (""=unknown/null id),
     * sampled once by [samplePresetCurve]: (nominal kelvin, red/green ratio,
     * blue/green ratio), sorted by kelvin. Supersedes the calibration-matrix
     * analytic model in [modelGainsRaw] the moment a lens has been sampled --
     * see [samplePresetCurve]'s kdoc for why this exists. Empty until then.
     * Written only from [samplePresetCurve] on the camera thread; entries are
     * never removed, so switching lenses back and forth re-uses a prior sample. */
    @Volatile private var presetCurves: Map<String, List<Triple<Int, Double, Double>>> = emptyMap()

    /** Physical lens ids already sampled or attempted this process -- camera-
     * thread-only (no @Volatile needed, single reader/writer), guards
     * [samplePresetCurve] against resampling on every reopen (lock/wake,
     * activity recreation) of a lens already covered. */
    private val sampledPhysicalIds = HashSet<String>()

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
     * Captures this device's camera characteristics and resolves them into a
     * [DeviceProfile], populating [lenses], [defaultLensIndex], [rawSpec] and the
     * active-lens tracking fields. Binder IPC per camera id -- it MUST be called
     * off the main thread (the caller's cameraOps dispatcher or Dispatchers.Default),
     * exactly once, before any other method on this class. Not called from init{}
     * so construction itself is main-thread-safe.
     *
     * Returns Unsupported rather than throwing. The previous implementation used
     * cameraIdList.first{} and check(deduped.isNotEmpty()), either of which
     * crashed the process on hardware without a RAW back camera (and on any
     * launch before CAMERA permission was granted).
     */
    fun initialize(): DeviceProfile {
        val snapshot = Camera2SnapshotSource(cameraManager).capture()
        val result = LensDiscovery.discover(snapshot.cameras)
        profile = result
        result.notes.forEach { Log.i(TAG, "lens ${it.cameraId}: ${it.message}") }
        if (result !is DeviceProfile.Supported) {
            Log.w(TAG, "device unsupported: ${(result as DeviceProfile.Unsupported).reason}")
            return result
        }
        // The primary logical camera is the parent of every non-standalone lens:
        // the first back camera declaring physical children, else the first back
        // camera, else the first accepted lens.
        primaryCameraId = snapshot.cameras.firstOrNull { it.physicalIds.isNotEmpty() && it.facing == 1 }?.cameraId
            ?: snapshot.cameras.firstOrNull { it.facing == 1 }?.cameraId
            ?: result.lenses.first().cameraId
        lenses = result.lenses
        defaultLensIndex = result.mainIndex
        applySelectedLens(lenses[defaultLensIndex], 0)
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
        return result
    }

    /**
     * Opens the camera and starts a preview-only repeating request (AWB auto).
     * [onFailed] fires when the device errors out or the preview session cannot
     * be configured — e.g. an unsupported lens/size mode.
     */
    @SuppressLint("MissingPermission")
    fun openAndPreview(previewSurface: Surface, onFailed: () -> Unit = {}, onReady: () -> Unit) {
        this.previewSurface = previewSurface
        val targetId = activeCameraId
        val prevDevice = device
        if (prevDevice != null && openDeviceId != targetId) {
            // Reopening the SAME id lets the framework implicitly disconnect the
            // previous device for us (see below); crossing to a genuinely different
            // top-level device -- e.g. switching into or out of a standalone lens,
            // see LensProfile.standalone -- has no such guarantee, so the old device
            // must be torn down explicitly first or it would just leak.
            try {
                prevDevice.close()
            } catch (e: Exception) {
                Log.w(TAG, "close before cross-device reopen failed", e)
            }
            device = null
        }
        openDeviceId = targetId
        // Reopening the same camera id from the same process is safe even without
        // the explicit close above: the framework disconnects the previous device
        // first.
        cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(listOf(previewSurface), forRecording = false, onFailed = onFailed) {
                    onReady()
                }
            }

            override fun onDisconnected(cam: CameraDevice) {
                Log.w(TAG, "camera disconnected")
                cam.close()
                if (device === cam) device = null
            }

            override fun onError(cam: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error")
                cam.close()
                if (device === cam) device = null
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
        // Exact metered gains are specific to the sensor they were measured on --
        // carrying them onto a DIFFERENT physical camera would apply one sensor's
        // absolute WB correction to a different sensor's raw data (same root cause
        // as anchorState's kdoc). Gated on an actual physical-camera change, not
        // sizeIndex alone, so a same-lens resolution switch keeps a still-valid
        // live meter instead of discarding it for no reason.
        if (activePhysicalId != lens.cameraId) wbOverride = null
        applySelectedLens(lens, sizeIndex)
        return true
    }

    /** Shared by [initialize] (default lens) and [selectMode]: publishes every
     * field keyed off "the active lens", including which camera id the NEXT
     * [openAndPreview] must open ([activeCameraId]) and whether this session's
     * OutputConfigurations need physical-id tagging ([sessionTagId]) -- see
     * [LensProfile.standalone]'s kdoc for why those two diverge from [activePhysicalId]
     * for a standalone lens. Does not touch the live session; the caller re-keys
     * the preview SurfaceView, and the resulting surfaceCreated -> openCamera ->
     * openAndPreview reopens against whatever this just published (same path
     * documented on [selectMode]). */
    private fun applySelectedLens(lens: LensProfile, sizeIndex: Int) {
        activePhysicalId = lens.cameraId          // WB identity key -- unchanged meaning
        rawSpec = specFor(lens, sizeIndex)
        activeArraySize = Rect(lens.activeArray.left, lens.activeArray.top,
                               lens.activeArray.right, lens.activeArray.bottom)
        activeOisModes = lens.oisModes
        wbCalib = wbCalibFor(lens)
        if (lens.standalone) {
            activeCameraId = lens.cameraId        // open this id directly
            sessionTagId = null                   // never tag a standalone device
        } else {
            activeCameraId = primaryCameraId
            sessionTagId = lens.cameraId
        }
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
        clipsDir.mkdirs() // idempotent; the actual write (below, via `path`) needs this to exist
        val spec = rawSpec
        val raw = NativeBridge.nativeStartRecording(
            path, spec.width, spec.height, spec.cfa, spec.whiteLevel,
            spec.blackLevel, spec.colorMatrix1, spec.illuminant1, spec.illuminant2,
            spec.colorMatrix2, /* fpsNum = */ fps, /* fpsDen = */ 1,
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
            val failedRaw = rawSurface
            rawSurface = null
            previewSurface?.let { ps ->
                cameraHandler.post {
                    // `session = null` above (before the failed createSession attempt)
                    // only covers the synchronous path -- a LATE onConfigured for that
                    // same abandoned attempt can still land on cameraHandler ahead of
                    // this post and reassign `session` to one still targeting
                    // failedRaw (sessionGeneration is normally bumped by createSession
                    // itself, which here only runs AFTER this block, i.e. too late to
                    // reject that stale callback). Close/null whatever session is live
                    // now and bump the generation so any still-pending late callback
                    // self-rejects (see sessionGeneration's kdoc), before releasing.
                    session?.close()
                    session = null
                    sessionGeneration++
                    try {
                        failedRaw?.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "rawSurface release failed", e)
                    }
                    createSession(listOf(ps), forRecording = false) {}
                }
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
     * Seeds [anchorState] (for the currently active lens) from a persisted [CaptureState] (Settings
     * restore, RecordViewModel init) instead of a live [readMetered] convergence.
     * Called before any session exists (during the enumeration coroutine, ahead of
     * the first [openAndPreview]) so there's no repeating request to re-arm --
     * unlike [setWbOverride], this never touches [session] or posts to
     * [cameraHandler]. The next call to [gainsFor] simply picks up the restored
     * anchor the same as it would a real meter's.
     */
    fun restoreWbAnchor(gains: RggbChannelVector, kelvin: Int) {
        anchorState = anchorState + ((activePhysicalId ?: "") to (gains to kelvin))
    }

    /** Current WB anchor for the ACTIVE lens (real metered gains + the kelvin
     * they were matched against), for persisting into [CaptureState]. Null if
     * no meter has converged for this lens yet this process and none was
     * restored. */
    fun wbAnchorOrNull(): Pair<RggbChannelVector, Int>? = anchorState[activePhysicalId ?: ""]

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
            val meterPhysicalId = activePhysicalId
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
                // selectMode() runs on the caller's coroutine (cameraOps), not this
                // thread, and isn't gated on a live meter -- the UI lets a user tap a
                // different lens chip while this thread was blocked in the await
                // above. If that happened, `result` (if any) was measured on a sensor
                // that is no longer the active one: readMetered's anchor write and
                // gainsToKelvinTint's kelvin/tint match both read activePhysicalId's
                // CURRENT (post-switch) state, so attributing this measurement to it
                // now would be exactly the cross-lens contamination anchorState's
                // fix exists to prevent. Treat it as a plain failed meter instead --
                // the caller's existing null handling (silent for quiet auto-meters,
                // a snackbar for a manual tap) already does the right thing.
                val staleLens = activePhysicalId != meterPhysicalId
                val out = if (!staleLens && result != null && usableAe(result)) readMetered(result) else null
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
            // Anchor the calibrated model's SHAPE to this real measurement, keyed
            // to the lens it was measured on. `k` was just matched against the OLD
            // anchor for this lens (gainsToKelvinTint/gainsFor above read
            // anchorState before this assignment) -- update the anchor only now,
            // so the stored kelvin is never matched against itself.
            anchorState = anchorState + ((activePhysicalId ?: "") to (gains to k))
        }
        if (debugLogging) {
            Log.i(
                TAG,
                "meter result iso=$isoOut exp=$expOut focus=$focusOut kelvin=$k tint=$t " +
                    "gains=$gains",
            )
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
        // (nx, ny) directly into active-array pixels. meterRegionFraction is the
        // metered box's fraction of width/height (Settings.meterRegion); half of
        // that is the half-width/half-height around the tap point.
        val cx = (arr.left + nx.coerceIn(0f, 1f) * arr.width()).toInt()
        val cy = (arr.top + ny.coerceIn(0f, 1f) * arr.height()).toInt()
        val halfFrac = meterRegionFraction / 2f
        val halfW = (arr.width() * halfFrac).toInt().coerceAtLeast(1)
        val halfH = (arr.height() * halfFrac).toInt().coerceAtLeast(1)
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
     * Samples this device's REAL AWB-preset gains (INCANDESCENT/WARM_FLUORESCENT/
     * FLUORESCENT/DAYLIGHT/CLOUDY_DAYLIGHT/SHADE -- fixed illuminant corrections
     * the HAL applies regardless of scene content, unlike AUTO) and stores them
     * into [presetCurves], keyed by [activePhysicalId]. Root cause this addresses:
     * [modelGainsRaw]'s calibration-matrix curve is built from
     * SENSOR_COLOR_TRANSFORM1/2, which on at least this hardware are placeholder
     * matrices (see that field's kdoc) -- so the *shape* the kelvin/tint slider
     * moves along was never trustworthy, only the anchor's absolute level was.
     * These preset modes hand back this vendor's own tuned gains for each named
     * illuminant, i.e. real hardware data instead of an analytic guess.
     *
     * Runs entirely on [cameraHandler] (posted from [createSession]'s onConfigured,
     * preview sessions only) and blocks it briefly per preset the same way
     * [meterAt] blocks it during convergence -- this serializes sampling against
     * any queued meterAt/updateManual so they can never interleave with the
     * preset sweep. Visibly cycles the preview through each illuminant's color
     * cast for roughly a second per preset; a one-time cost per physical lens id
     * per process (see [sampledPhysicalIds]), not repeated on lock/wake or
     * activity recreation. [restoreManualPreview] undoes the AWB override
     * regardless of how many presets actually returned usable gains.
     */
    private fun samplePresetCurve() {
        val key = activePhysicalId ?: ""
        if (key in sampledPhysicalIds) return
        val dev = device
        val s = session
        val preview = previewSurface
        if (recording || dev == null || s == null || preview == null) return
        sampledPhysicalIds += key
        val samples = mutableListOf<Triple<Int, Double, Double>>()
        for ((mode, kelvinNominal) in PRESET_KELVIN) {
            try {
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, mode)
                }.build()
                val done = CountDownLatch(1)
                val last = AtomicReference<TotalCaptureResult>()
                var count = 0
                val cb = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        last.set(result)
                        count++
                        if (count >= 3) done.countDown()
                    }
                }
                s.setRepeatingRequest(req, cb, meterHandler())
                done.await(600, TimeUnit.MILLISECONDS)
                val gains = last.get()?.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (gains != null) {
                    // Inverted vs. modelGainsRaw's raw-sensor-response nG/nR: these
                    // ARE the HAL's already-applied correction gains, not a raw
                    // response this app still needs to invert to get a gain out of.
                    // redGain/greenGain (not greenGain/redGain) is what tracks the
                    // same kelvin-increasing direction gainsFor already assumes --
                    // confirmed on device: greenGain/redGain shipped backwards
                    // (raising kelvin made the preview bluer instead of warmer).
                    val g = (gains.greenEven + gains.greenOdd) / 2.0
                    val mr = (gains.red.toDouble() / g).coerceIn(1e-2, 8.0)
                    val mb = (gains.blue.toDouble() / g).coerceIn(1e-2, 8.0)
                    samples += Triple(kelvinNominal, mr, mb)
                }
            } catch (e: Exception) {
                Log.e(TAG, "preset sample failed mode=$mode kelvin=$kelvinNominal", e)
            }
        }
        restoreManualPreview()
        if (samples.size >= 2) {
            val sorted = samples.sortedBy { it.first }
            presetCurves = presetCurves + (key to sorted)
            if (debugLogging) Log.i(TAG, "WB preset curve[$key] = $sorted")
        }
    }

    /** Mired-linear interpolation (same convention as [interpolatedColorMatrix])
     * between the two [samplePresetCurve] samples bracketing [kelvinValue].
     * Outside the sampled range, extrapolates using the FULL span's endpoint-
     * to-endpoint slope rather than clamping flat -- the UI's kelvin range
     * (2000K-10000K) reaches past the presets' real span (~2850K-7500K, no
     * AWB preset lower than INCANDESCENT exists to sample), and clamping left
     * the low end totally undifferentiated (2000K-2850K all produced the
     * identical 2850K gains -- "doesn't feel blue enough at 2000K"). The
     * nearest LOCAL pair (e.g. 2850-3000K) is deliberately NOT used for
     * extrapolation -- its short span amplifies noise into wild overshoot the
     * moment the query point is much farther out than the pair's own spacing
     * (verified: extrapolating 2000K off the 2850-3000K pair alone drives the
     * ratio negative before the safety clamp saves it). The full span is a
     * gentler, more stable slope for a modest extrapolation distance. Final
     * ratios are still hard-clamped to the same [1e-2, 8.0] bound used
     * everywhere else in this file, so a large extrapolation can't blow up. */
    private fun interpolatePresetCurve(
        curve: List<Triple<Int, Double, Double>>, kelvinValue: Int,
    ): Pair<Double, Double> {
        val first = curve.first()
        val last = curve.last()
        var lo = first
        var hi = last
        var clampWeight = true
        if (kelvinValue <= first.first || kelvinValue >= last.first) {
            clampWeight = false // extrapolate using the full span's slope
        } else {
            for (i in 0 until curve.size - 1) {
                if (kelvinValue >= curve[i].first && kelvinValue <= curve[i + 1].first) {
                    lo = curve[i]; hi = curve[i + 1]; break
                }
            }
        }
        if (lo.first == hi.first) return lo.second to lo.third
        val invK = 1.0 / kelvinValue
        val invLo = 1.0 / lo.first
        val invHi = 1.0 / hi.first
        var w = (invK - invHi) / (invLo - invHi)
        if (clampWeight) w = w.coerceIn(0.0, 1.0)
        val mr = (w * lo.second + (1 - w) * hi.second).coerceIn(1e-2, 8.0)
        val mb = (w * lo.third + (1 - w) * hi.third).coerceIn(1e-2, 8.0)
        return mr to mb
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
        val finishedRaw = rawSurface
        rawSurface = null

        // 4. Return to preview-only.
        previewSurface?.let { ps ->
            cameraHandler.post {
                session?.close()
                session = null
                // Release only after the session that targeted it has been closed
                // (immediately above, same camera-thread post) -- releasing first
                // would risk the still-referencing session's close() touching a
                // released Surface. The session was already idle (step 1/2 above)
                // before nativeStopRecording ran, so no capture is in flight here.
                // CameraCaptureSession.close()'s own docs call teardown "not
                // instantaneous" and point to onClosed() as the real completion
                // signal, which this class doesn't listen for -- accepted given the
                // drain above already quiesces captures, but caught defensively
                // rather than let a device-specific straggler crash the app.
                try {
                    finishedRaw?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "rawSurface release failed", e)
                }
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
            openDeviceId = null
            meterCallbackThread?.quitSafely()
            meterCallbackThread = null
            meterCallbackHandler = null
            // Inside this post{} because releaseZebra() is camera-thread-only, unlike
            // cameraThread.quitSafely() below.
            releaseZebra()
            zebraThread?.quitSafely()
            zebraThread = null
            zebraHandler = null
        }
        cameraThread.quitSafely()
    }

    // --- internals -------------------------------------------------------------

    private fun specFor(lens: LensProfile, sizeIndex: Int): RawSpec {
        val size = lens.sizes[sizeIndex]
        // A genuine second calibration point requires BOTH the matrix and its
        // illuminant code; a sensor exposing only one of the pair (seen on some
        // hardware) gets treated as single-illuminant rather than guessing.
        val hasSecondIlluminant = lens.colorMatrix2 != null && lens.illuminant2 != null

        // DNG readers (Resolve included) conventionally assume CalibrationIlluminant1
        // is the LOWER color temperature and CalibrationIlluminant2 the HIGHER one --
        // Adobe's own DNG interpolation code is built around that ordering, even
        // though the spec doesn't technically mandate it. A sensor's own
        // SENSOR_REFERENCE_ILLUMINANT1/2 has no such guarantee (this device reports
        // illuminant1=D65/~6504K and illuminant2=StandardA/~2856K -- backwards from
        // the convention), so sort by CCT here rather than trusting native order.
        // gainsFor()'s own interpolation is order-agnostic (symmetric inverse-CCT
        // ratio), so this reordering only affects what gets written to the DNG.
        var exportIlluminant1 = lens.illuminant1 ?: 21  // 21 = D65, DNG's implicit default
        var exportColorMatrix1 = lens.colorMatrix1
        var exportIlluminant2 = if (hasSecondIlluminant) lens.illuminant2!! else 0
        var exportColorMatrix2 = if (hasSecondIlluminant) lens.colorMatrix2!! else FloatArray(9)
        if (hasSecondIlluminant) {
            val cct1 = illuminantCct(exportIlluminant1) ?: 2856
            val cct2 = illuminantCct(exportIlluminant2) ?: 6504
            if (cct1 > cct2) {
                val ti = exportIlluminant1; exportIlluminant1 = exportIlluminant2; exportIlluminant2 = ti
                val tm = exportColorMatrix1; exportColorMatrix1 = exportColorMatrix2; exportColorMatrix2 = tm
            }
        }

        return RawSpec(
            width = size.width, height = size.height, cfa = lens.cfa,
            whiteLevel = lens.whiteLevel, blackLevel = lens.blackLevel,
            colorMatrix1 = exportColorMatrix1, isoRange = lens.isoRange,
            maxFps = size.maxFps, minFocusDiopters = lens.minFocusDiopters,
            deviceName = Build.MODEL,
            illuminant1 = exportIlluminant1,
            illuminant2 = exportIlluminant2,
            colorMatrix2 = exportColorMatrix2,
        )
    }

    /**
     * Reads the Y plane (which *is* luminance -- the reason this stream is YUV and
     * not a second RAW one), thresholds it, and publishes the mask.
     *
     * `acquireLatestImage` deliberately discards backlog: if analysis falls behind,
     * the right answer is the newest frame, not a queued stale one. Every failure is
     * logged and swallowed -- an exception escaping here would kill the analysis
     * thread, and a preview aid must never be able to do that.
     */
    private val zebraListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "zebra acquire failed", e); null
        } ?: return@OnImageAvailableListener
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastZebraNs >= ZEBRA_MIN_INTERVAL_NS) {
                lastZebraNs = now
                val plane = img.planes[0]
                val buf = plane.buffer
                val n = buf.remaining()
                if (zebraBuffer.size < n) zebraBuffer = ByteArray(n)
                buf.get(zebraBuffer, 0, n)
                _zebraMask.value = ZebraAnalysis.threshold(
                    zebraBuffer, img.width, img.height, plane.rowStride, plane.pixelStride,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "zebra analysis failed", e)
        } finally {
            try { img.close() } catch (e: Exception) { Log.w(TAG, "zebra image close failed", e) }
        }
    }

    /**
     * Returns the analysis surface for the session about to be created, building the
     * reader (and its thread) on first use and rebuilding it whenever the active
     * lens's chosen size changes. Camera-thread only.
     *
     * Returns null when the device advertises no usable YUV size -- the session is
     * then created without a third output and zebra is a silent no-op, per the
     * spec's graceful-degradation rule.
     */
    private fun ensureZebraSurface(): Surface? {
        val spec = rawSpec
        val sizes = try {
            cameraManager.getCameraCharacteristics(activeCameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { SizeSpec(it.width, it.height) }
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "zebra: YUV size query failed", e); emptyList()
        }
        val pick = ZebraAnalysis.pickAnalysisSize(
            sizes, spec.width.toFloat() / spec.height,
        ) ?: run {
            Log.w(TAG, "zebra: no usable YUV size on camera $activeCameraId")
            releaseZebra()
            return null
        }
        val existing = zebraReader
        if (existing != null && existing.width == pick.width && existing.height == pick.height) {
            return zebraSurface
        }
        releaseZebra()
        return try {
            val handler = zebraHandler ?: Handler(
                HandlerThread("camera-zebra").apply { start() }.also { zebraThread = it }.looper,
            ).also { zebraHandler = it }
            val reader = ImageReader.newInstance(pick.width, pick.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener(zebraListener, handler)
            zebraReader = reader
            zebraSurface = reader.surface
            Log.i(TAG, "zebra: analysis stream ${pick.width}x${pick.height}")
            zebraSurface
        } catch (e: Exception) {
            Log.e(TAG, "zebra: reader creation failed", e)
            releaseZebra()
            null
        }
    }

    /** Tears the analysis stream down and clears the published mask. Camera-thread
     * only. The HandlerThread itself is left running for reuse and quit in [close].
     *
     * Called from three places where a session may still hold a reference to the
     * old reader's [Surface]: the toggle-off branch in [createSession], the
     * size-change rebuild in [ensureZebraSurface], and the retry-without-zebra
     * path in [createSession]. Closing [zebraReader] here without first waiting for
     * that session to close is safe because every caller sits on a path that
     * immediately supersedes the old session before its own teardown would ever
     * reach the freed Surface -- either the device was just reopened (reopening the
     * same camera id implicitly disconnects the previous device and whatever
     * session it held, see [openAndPreview]), or the next step in [createSession]
     * is its own `dev.createCaptureSession(config)` call, which Camera2 documents
     * as discarding any session that currently exists on the device before the new
     * one takes hold. Same class of race as [stopRecording]'s surface-release
     * ordering comment; resolved here by relying on that framework guarantee
     * instead of an explicit close-and-wait.
     *
     * The null is posted onto [zebraHandler] rather than set here directly: a call
     * to [zebraListener] queued (or already executing) on that thread before
     * [ImageReader.close] can still land its own `_zebraMask.value = ...` after this
     * function returns, permanently reviving a mask for an analysis stream that no
     * longer exists. Posting after close() puts the null strictly behind any such
     * write in the same thread's queue. */
    private fun releaseZebra() {
        try { zebraReader?.close() } catch (e: Exception) { Log.w(TAG, "zebra reader close failed", e) }
        zebraReader = null
        zebraSurface = null
        val handler = zebraHandler
        if (handler != null) handler.post { _zebraMask.value = null } else _zebraMask.value = null
    }

    /**
     * [withZebra] is false only on the one-shot retry the two failure paths below
     * perform when a session that INCLUDED the analysis output failed to configure.
     * It has to be a parameter rather than a re-read of [zebraEnabled], which is
     * still true at that point and would put the output straight back.
     */
    private fun createSession(
        surfaces: List<Surface>, forRecording: Boolean, withZebra: Boolean = true,
        onFailed: () -> Unit = {}, onConfigured: () -> Unit,
    ) {
        val dev = device ?: run { onFailed(); return }
        val generation = ++sessionGeneration
        // Appended here rather than at createSession's four call sites so no path --
        // preview open, recording start, or either failure-recovery reopen -- can
        // silently omit it. Tagged with sessionTagId alongside the others below,
        // which a standalone lens correctly leaves null.
        val zebra = if (withZebra && zebraEnabled) ensureZebraSurface() else { releaseZebra(); null }
        val allSurfaces = if (zebra != null) surfaces + zebra else surfaces
        // Whether THIS attempt carried the analysis output, captured for the failure
        // paths. A device may construct the reader happily and still reject the
        // three-stream combination -- guaranteed by the mandatory-combination table
        // for the logical/standalone paths, but NOT when sessionTagId != null, where
        // the physical-stream table has no RAW entry at all. Dropping the output and
        // retrying once turns that rejection into the silent no-op the spec requires,
        // instead of surfacing it as the caller's "unsupported lens/size mode".
        val retryWithoutZebra = {
            Log.w(TAG, "zebra: session rejected the analysis output; retrying without it")
            // releaseZebra() is not called here: the recursive call below runs with
            // withZebra = false, so its own else-branch already guarantees it.
            createSession(surfaces, forRecording, withZebra = false, onFailed = onFailed, onConfigured = onConfigured)
        }
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                allSurfaces.map { s ->
                    OutputConfiguration(s).apply {
                        sessionTagId?.let { setPhysicalCameraId(it) }
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
                            // Posted rather than called inline so a synchronous
                            // meterAt from inside onConfigured() (if the caller's
                            // onReady triggers one) gets first claim on cameraHandler;
                            // an async startup meter (its own coroutine hop before
                            // reaching cameraHandler) is not ordered against this
                            // either way -- see samplePresetCurve's kdoc.
                            if (!forRecording) cameraHandler.post { samplePresetCurve() }
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
                        if (generation != sessionGeneration) return
                        if (zebra != null) retryWithoutZebra() else onFailed()
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
            if (zebra != null) retryWithoutZebra() else onFailed()
        }
    }

    private fun setRepeatingPreview(s: CameraCaptureSession) {
        val dev = device ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            zebraSurface?.let { addTarget(it) }
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
            zebraSurface?.let { addTarget(it) }
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
    private fun wbCalibFor(lens: LensProfile): WbCalib = WbCalib(
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
     *
     * SUPERSEDED the moment [samplePresetCurve] has real data for the active
     * lens: real vendor-tuned AWB-preset gains are trustworthy for shape AND
     * level, so this analytic curve only serves as the fallback shape before
     * that one-time sample completes (see [presetCurves]'s kdoc for why the
     * calibration-matrix curve below cannot be trusted on its own).
     */
    private fun modelGainsRaw(kelvinValue: Int): Pair<Double, Double> {
        presetCurves[activePhysicalId ?: ""]?.let { curve ->
            if (curve.size >= 2) return interpolatePresetCurve(curve, kelvinValue)
        }
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
        val (anchor, aKelvin) = anchorState[activePhysicalId ?: ""] ?: (null to 5600)
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
     * output back in reproduces the generating k exactly PROVIDED the clamp has
     * not collapsed two candidates onto the same log-ratio (a saturated channel
     * at adjacent low-kelvin candidates can do that for a large enough anchor;
     * the strict < in the loop would then keep the lower candidate). On this
     * hardware the candidate set stays injective, so round-trip is exact.
     * Tint is recovered by comparing the measured green gain against the
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
        // AUTO leaves the key unset (HAL default -- see oisMode's kdoc). ON/OFF are
        // only requested when the active lens's LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        // actually lists the wanted mode; a lens with no OIS hardware (or that lacks the
        // characteristic entirely, activeOisModes == null) silently keeps the HAL default
        // instead of setting a key the HAL never advertised support for.
        when (oisMode) {
            OisMode.AUTO -> { /* leave HAL default */ }
            OisMode.ON, OisMode.OFF -> {
                val want = if (oisMode == OisMode.ON) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                           else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                val modes = activeOisModes
                if (modes != null && want in modes) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, want)
            }
        }
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

        /** ~15 analyses/second. A visual exposure aid does not need every frame, and
         * halving the work matters most while recording, where it shares the device
         * with the capture hot path. */
        private const val ZEBRA_MIN_INTERVAL_NS = 66_000_000L

        // Curated subsets of RecordScreen.KELVIN_STOPS (step 100) / TINT_STOPS (step
        // 2, even only) -- gainsToKelvinTint returns values from these sets so the
        // metered result always lands on a real slider tick, not a mirror of the
        // full slider resolution. KELVIN_CANDIDATES' values are all multiples of
        // 100 (a true subset of KELVIN_STOPS) by construction. TINT_CANDIDATES was
        // previously step 5, which includes odd values (-45, -35, ... 45) that
        // aren't in TINT_STOPS at all -- a meter could set uiState.tint to a value
        // with no matching tick. Step 10 keeps a similarly coarse candidate set
        // while staying a guaranteed subset (every multiple of 10 is even).
        private val KELVIN_CANDIDATES = intArrayOf(2000, 2700, 3200, 4000, 5000, 5600, 6500, 7500, 9000, 10000)
        private val TINT_CANDIDATES = (-50..50 step 10).toList()
        // Seed anchor for gainsFor before the first successful meterAt of a given lens
        // (see anchorState's kdoc): a typical green-dominant phone-sensor neutral at
        // 5600K (gainsFor's own no-anchor-yet default). Better than trusting a possibly-
        // placeholder calibration matrix's absolute level on unknown hardware, and
        // roughly harmless (close to gainsFor's old floor-1 behavior) on honest
        // hardware -- gets replaced by real numbers the moment any meter succeeds,
        // including the automatic per-lens startup meter (RecordViewModel.autoMeteredLensIndices).
        private const val DEFAULT_ANCHOR_R = 2.0
        private const val DEFAULT_ANCHOR_G = 1.0
        private const val DEFAULT_ANCHOR_B = 1.7
        // Fixed-illuminant AWB presets sampled by samplePresetCurve, with each
        // preset's conventional correlated color temperature. TWILIGHT deliberately
        // omitted -- its real CCT isn't standardized/documented per-vendor, unlike
        // the other six, so including it risked a wrong data point rather than a
        // usefully wider range.
        private val PRESET_KELVIN = listOf(
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to 2850,
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to 3000,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to 4200,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to 5500,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to 6500,
            CameraMetadata.CONTROL_AWB_MODE_SHADE to 7500,
        )
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
