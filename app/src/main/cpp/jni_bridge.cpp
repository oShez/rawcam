#include <jni.h>
#include <string>
#include "rawcam/rawv.h"
#include "benchmark.h"
#include "capture.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_shez_rawcam_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
  std::string v = "rawv v" + std::to_string(rawcam::kVersion);
  return env->NewStringUTF(v.c_str());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_shez_rawcam_NativeBridge_nativeBenchmarkWrite(
    JNIEnv* env, jobject, jstring jpath, jint frameBytes, jint frames) {
  const char* p = env->GetStringUTFChars(jpath, nullptr);
  double r = rawcam::benchmarkWrite(p, (uint32_t)frameBytes, (uint32_t)frames);
  env->ReleaseStringUTFChars(jpath, p);
  return r;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_shez_rawcam_NativeBridge_nativeStartRecording(
    JNIEnv* env, jobject, jstring jpath, jint width, jint height, jint cfa,
    jint whiteLevel, jintArray jblackLevel, jfloatArray jcolorMatrix1,
    jint fpsNum, jint fpsDen, jstring jdeviceName) {
  const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
  std::string path(pathChars ? pathChars : "");
  env->ReleaseStringUTFChars(jpath, pathChars);

  const char* deviceChars = env->GetStringUTFChars(jdeviceName, nullptr);
  std::string deviceName(deviceChars ? deviceChars : "");
  env->ReleaseStringUTFChars(jdeviceName, deviceChars);

  int32_t blackLevel[4] = {0, 0, 0, 0};
  if (jblackLevel != nullptr && env->GetArrayLength(jblackLevel) >= 4) {
    jint tmp[4];
    env->GetIntArrayRegion(jblackLevel, 0, 4, tmp);
    for (int i = 0; i < 4; i++) blackLevel[i] = tmp[i];
  }

  float colorMatrix1[9] = {0};
  if (jcolorMatrix1 != nullptr && env->GetArrayLength(jcolorMatrix1) >= 9) {
    jfloat tmp[9];
    env->GetFloatArrayRegion(jcolorMatrix1, 0, 9, tmp);
    for (int i = 0; i < 9; i++) colorMatrix1[i] = tmp[i];
  }

  return rawcam::Capture::instance().start(env, path, width, height, cfa, whiteLevel,
                                            blackLevel, colorMatrix1, fpsNum, fpsDen,
                                            deviceName);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shez_rawcam_NativeBridge_nativePushFrameMeta(
    JNIEnv*, jobject, jlong timestampNs, jint iso, jlong exposureNs,
    jfloat focusDistance, jfloat wbR, jfloat wbG, jfloat wbB) {
  rawcam::Capture::instance().pushFrameMeta((int64_t)timestampNs, (int32_t)iso,
                                             (int64_t)exposureNs, focusDistance, wbR, wbG,
                                             wbB);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_shez_rawcam_NativeBridge_nativeStopRecording(JNIEnv* env, jobject) {
  auto result = rawcam::Capture::instance().stop();
  jlongArray arr = env->NewLongArray(2);
  jlong values[2] = {(jlong)result.first, (jlong)result.second};
  env->SetLongArrayRegion(arr, 0, 2, values);
  return arr;
}
