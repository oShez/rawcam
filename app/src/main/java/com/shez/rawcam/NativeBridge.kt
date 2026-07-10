package com.shez.rawcam

object NativeBridge {
    init { System.loadLibrary("rawcam_jni") }
    external fun nativeVersion(): String
    external fun nativeBenchmarkWrite(path: String, frameBytes: Int, frames: Int): Double
}
