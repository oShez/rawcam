#include <jni.h>
#include <string>
#include "rawcam/rawv.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_shez_rawcam_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
  std::string v = "rawv v" + std::to_string(rawcam::kVersion);
  return env->NewStringUTF(v.c_str());
}
