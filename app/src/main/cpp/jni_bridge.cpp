#include <jni.h>
#include <string>
#include "rawcam/rawv.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/exporter.h"
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_shez_rawcam_NativeBridge_nativeGetStats(JNIEnv* env, jobject) {
  auto result = rawcam::Capture::instance().stats();
  jlongArray arr = env->NewLongArray(2);
  jlong values[2] = {(jlong)result.first, (jlong)result.second};
  env->SetLongArrayRegion(arr, 0, 2, values);
  return arr;
}

// nativeExportClip is called synchronously from a Kotlin background thread
// (ExportService's worker thread), which is already JVM-attached for the
// duration of this call -- the progress lambda below runs on that same
// thread/stack frame, never a native-spawned one, so no AttachCurrentThread
// dance is needed. jCallback is still promoted to a global ref (and released
// before returning) defensively, since it is invoked repeatedly from a
// C++ lambda captured by exportClip rather than used once inline.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shez_rawcam_NativeBridge_nativeExportClip(
    JNIEnv* env, jobject, jstring jRawvPath, jstring jOutDir, jobject jCallback) {
  const char* rawvChars = env->GetStringUTFChars(jRawvPath, nullptr);
  std::string rawvPath(rawvChars ? rawvChars : "");
  env->ReleaseStringUTFChars(jRawvPath, rawvChars);

  const char* outDirChars = env->GetStringUTFChars(jOutDir, nullptr);
  std::string outDir(outDirChars ? outDirChars : "");
  env->ReleaseStringUTFChars(jOutDir, outDirChars);

  jobject callback = env->NewGlobalRef(jCallback);
  jclass cbClass = env->GetObjectClass(callback);
  jmethodID onProgress = env->GetMethodID(cbClass, "onProgress", "(JJ)Z");
  env->DeleteLocalRef(cbClass);

  bool ok = false;
  if (onProgress != nullptr) {
    ok = rawcam::exportClip(
        rawvPath, outDir,
        [env, callback, onProgress](uint64_t done, uint64_t total) -> bool {
          jboolean cont = env->CallBooleanMethod(callback, onProgress, (jlong)done, (jlong)total);
          if (env->ExceptionCheck()) {
            // No exceptions may cross back into native code; treat as cancel.
            env->ExceptionClear();
            return false;
          }
          return cont == JNI_TRUE;
        });
  }
  env->DeleteGlobalRef(callback);
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_shez_rawcam_NativeBridge_nativeClipInfo(JNIEnv* env, jobject, jstring jPath) {
  const char* pathChars = env->GetStringUTFChars(jPath, nullptr);
  std::string path(pathChars ? pathChars : "");
  env->ReleaseStringUTFChars(jPath, pathChars);

  jintArray arr = env->NewIntArray(4);
  auto reader = rawcam::RawvReader::open(path);
  if (!reader) {
    jint zero[4] = {0, 0, 0, 0};
    env->SetIntArrayRegion(arr, 0, 4, zero);
    return arr;
  }
  const rawcam::FileHeader& h = reader->header();
  jint fps = h.fpsDen > 0 ? (jint)(h.fpsNum / h.fpsDen) : (jint)h.fpsNum;
  jint values[4] = {(jint)h.width, (jint)h.height, fps, (jint)reader->frameCount()};
  env->SetIntArrayRegion(arr, 0, 4, values);
  return arr;
}
