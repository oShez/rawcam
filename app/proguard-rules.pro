# NativeBridge's `external fun` declarations are resolved via implicit JNI
# name mangling (jni_bridge.cpp defines Java_com_shez_rawcam_NativeBridge_xxx
# symbols directly, not JNI_OnLoad/RegisterNatives) -- renaming the class or
# any native method would break that mangled-symbol match at runtime
# (UnsatisfiedLinkError), so keep it and its nested types untouched.
-keep class com.shez.rawcam.NativeBridge { *; }
-keep class com.shez.rawcam.NativeBridge$* { *; }

# jni_bridge.cpp's export-progress callback is looked up by literal method
# name+signature (env->GetMethodID(cbClass, "onProgress", "(JJ)Z")), not
# reflection on a keep-annotated class -- an obfuscated/renamed method here
# would silently return a null jmethodID and break export.
-keepclassmembers interface com.shez.rawcam.NativeBridge$ExportCallback {
    *;
}
