cmake_minimum_required(VERSION 3.22)
project(rawcam_jni CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core core_build)
add_library(rawcam_jni SHARED jni_bridge.cpp benchmark.cpp capture.cpp)
target_link_libraries(rawcam_jni rawcam_core android mediandk log)
# 16 KB page-size devices (Pixel, Android 15+) require 16K-aligned LOAD segments
target_link_options(rawcam_jni PRIVATE "-Wl,-z,max-page-size=16384")
