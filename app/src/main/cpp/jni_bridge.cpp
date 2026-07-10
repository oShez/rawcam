#include <jni.h>
#include <string>
#include "rawcam/rawv.h"
#include "benchmark.h"

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
