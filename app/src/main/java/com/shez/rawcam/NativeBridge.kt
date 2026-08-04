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

    fun interface ExportCallback {
        fun onProgress(done: Long, total: Long): Boolean
    }
}
