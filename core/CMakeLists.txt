cmake_minimum_required(VERSION 3.22)
project(rawcam_core CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rawcam_core STATIC
  src/rawv_writer.cpp
  src/rawv_reader.cpp
  src/pack10.cpp
  src/crop.cpp
  src/rawv_codec.cpp
  src/dng_writer.cpp
  src/exporter.cpp
  src/preview.cpp)
target_include_directories(rawcam_core PUBLIC include)

# Host (non-Android) builds get the ARM_NEON_2_SSE shim so the SIMD path in
# rawv_codec.cpp compiles and runs (as SSE4.1) under ctest -- the byte-for-byte
# bit-exact assertion then genuinely guards the on-device NEON path. On Android,
# arm_neon.h is used directly (auto-detected via __ARM_NEON); no shim, no flag.
if(NOT ANDROID)
  target_include_directories(rawcam_core PRIVATE third_party/neon2sse)
  target_compile_definitions(rawcam_core PRIVATE RAWV_USE_NEON2SSE)
  target_compile_options(rawcam_core PRIVATE -msse4.1)
endif()

# exporter.cpp parallelizes frame export across a std::thread pool.
find_package(Threads REQUIRED)
target_link_libraries(rawcam_core PUBLIC Threads::Threads)

# Tests build for the host by default. They can also be built FOR ANDROID and
# run on a device over adb -- configure a separate NDK-toolchain build dir with
# -DRAWCAM_BUILD_TESTS=ON. That path exists because the host toolchain is not
# always usable, and because running on arm64 exercises the real NEON code
# instead of the SSE shim.
if(NOT DEFINED RAWCAM_BUILD_TESTS)
  if(ANDROID)
    set(RAWCAM_BUILD_TESTS OFF)
  else()
    set(RAWCAM_BUILD_TESTS ON)
  endif()
endif()

if(RAWCAM_BUILD_TESTS)
  enable_testing()
  file(GLOB TEST_SOURCES tests/*.cpp)
  foreach(t ${TEST_SOURCES})
    get_filename_component(name ${t} NAME_WE)
    add_executable(${name} ${t})
    target_link_libraries(${name} rawcam_core)
    # rawv_codec.cpp logs via __android_log_print on Android, so the test
    # executables need liblog just like the app's .so does.
    if(ANDROID)
      target_link_libraries(${name} log)
    endif()
    target_include_directories(${name} PRIVATE third_party/doctest)
    # rawcam_core's ARM_NEON_2_SSE include dir/define/flag above are PRIVATE
    # to that library target and do not propagate to these separate test
    # executables; test_rawv_simd_spike.cpp needs them directly to exercise
    # the real NEON2SSE path instead of falling through to its #else branch.
    # Harmless no-op for test files that don't touch NEON_2_SSE.h.
    # x86-only: on Android these compile against the real arm_neon.h.
    if(NOT ANDROID)
      target_include_directories(${name} PRIVATE third_party/neon2sse)
      target_compile_definitions(${name} PRIVATE RAWV_USE_NEON2SSE)
      target_compile_options(${name} PRIVATE -msse4.1)
    endif()
    add_test(NAME ${name} COMMAND ${name})
  endforeach()
endif()
