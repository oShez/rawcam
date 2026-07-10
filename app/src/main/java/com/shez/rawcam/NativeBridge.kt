package com.shez.rawcam

object NativeBridge {
    init { System.loadLibrary("rawcam_jni") }
    external fun nativeVersion(): String
}
