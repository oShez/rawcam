package com.shez.rawcam

object NativeBridge {
    init { System.loadLibrary("rawcam_jni") }
    external fun nativeVersion(): String
    external fun nativeBenchmarkWrite(path: String, frameBytes: Int, frames: Int): Double

    // returns a Surface for the Camera2 RAW target, or null. illuminant1/2 are
    // DNG/EXIF LightSource codes (illuminant2 == 0 means the sensor exposed no
    // second calibration point, and colorMatrix2 is then ignored).
    external fun nativeStartRecording(path: String, width: Int, height: Int,
        cfa: Int, whiteLevel: Int, blackLevel: IntArray /*4*/,
        colorMatrix1: FloatArray /*9*/, illuminant1: Int, illuminant2: Int,
        colorMatrix2: FloatArray /*9*/, fpsNum: Int, fpsDen: Int,
        deviceName: String, compressRecordings: Boolean): android.view.Surface?
    external fun nativePushFrameMeta(timestampNs: Long, iso: Int, exposureNs: Long,
        focusDistance: Float, wbR: Float, wbG: Float, wbB: Float)
    // Records audio parameters and sync provenance into the .rawv header. MUST be
    // called before nativeStopRecording(), which is what finalizes the header --
    // calling it afterwards is silently a no-op.
    external fun nativeSetAudioInfo(
        present: Boolean, sampleRate: Int, channels: Int, bitsPerSample: Int,
        offsetNs: Long, driftPpm: Int, timestampSource: Int, status: Int,
        source: Int, fileName: String,
    )
    // returns longArrayOf(framesWritten, framesDropped)
    external fun nativeStopRecording(): LongArray
    // atomic snapshot, safe to poll while recording: longArrayOf(framesWritten, framesDropped)
    external fun nativeGetStats(): LongArray

    // Exports rawvPath (a finalized .rawv clip) to outDir/000000.dng, 000001.dng, ...
    // outDir must already exist. cb.onProgress is called after each frame is written
    // with (framesDone, totalFrames); returning false cancels the export. Returns
    // false if the clip can't be opened/read or the export was cancelled.
    external fun nativeExportClip(rawvPath: String, outDir: String, cb: ExportCallback): Boolean

    // Reads just the header (+ crash-recovery frame count scan) of a .rawv clip.
    // Returns intArrayOf(width, height, fps, frameCount), or all-zero if unreadable.
    external fun nativeClipInfo(path: String): IntArray

    // Opens a clip for preview decoding, returning an opaque handle (0 = failure).
    // RawvReader builds its frame-offset index on open, so a handle MUST be held
    // across a decoding session rather than opened per frame. Every successful
    // open must be paired with nativeCloseClip.
    external fun nativeOpenClip(path: String): Long
    external fun nativeClipFrameCount(handle: Long): Long
    // Returns intArrayOf(width, height, argb...) developed and downscaled to fit
    // maxW x maxH, or null if the frame cannot be developed.
    external fun nativeDecodeFrame(handle: Long, index: Long, maxW: Int, maxH: Int): IntArray?
    external fun nativeCloseClip(handle: Long)

    fun interface ExportCallback {
        fun onProgress(done: Long, total: Long): Boolean
    }
}
