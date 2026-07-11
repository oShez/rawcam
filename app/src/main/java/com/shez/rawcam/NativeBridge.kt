package com.shez.rawcam

object NativeBridge {
    init { System.loadLibrary("rawcam_jni") }
    external fun nativeVersion(): String
    external fun nativeBenchmarkWrite(path: String, frameBytes: Int, frames: Int): Double

    // returns a Surface for the Camera2 RAW target, or null
    external fun nativeStartRecording(path: String, width: Int, height: Int,
        cfa: Int, whiteLevel: Int, blackLevel: IntArray /*4*/,
        colorMatrix1: FloatArray /*9*/, fpsNum: Int, fpsDen: Int,
        deviceName: String): android.view.Surface?
    external fun nativePushFrameMeta(timestampNs: Long, iso: Int, exposureNs: Long,
        focusDistance: Float, wbR: Float, wbG: Float, wbB: Float)
    // returns longArrayOf(framesWritten, framesDropped)
    external fun nativeStopRecording(): LongArray
    // atomic snapshot, safe to poll while recording: longArrayOf(framesWritten, framesDropped)
    external fun nativeGetStats(): LongArray
}
