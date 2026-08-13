cmake_minimum_required(VERSION 3.22)
project(rawcam_core CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rawcam_core STATIC
  src/rawv_writer.cpp
  src/rawv_reader.cpp
  src/pack10.cpp
  src/rawv_codec.cpp
  src/dng_writer.cpp
  src/exporter.cpp)
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

if(NOT ANDROID)
  enable_testing()
  file(GLOB TEST_SOURCES tests/*.cpp)
  foreach(t ${TEST_SOURCES})
    get_filename_component(name ${t} NAME_WE)
    add_executable(${name} ${t})
    target_link_libraries(${name} rawcam_core)
    target_include_directories(${name} PRIVATE third_party/doctest)
    # rawcam_core's ARM_NEON_2_SSE include dir/define/flag above are PRIVATE
    # to that library target and do not propagate to these separate test
    # executables; test_rawv_simd_spike.cpp needs them directly to exercise
    # the real NEON2SSE path instead of falling through to its #else branch.
    # Harmless no-op for test files that don't touch NEON_2_SSE.h.
    target_include_directories(${name} PRIVATE third_party/neon2sse)
    target_compile_definitions(${name} PRIVATE RAWV_USE_NEON2SSE)
    target_compile_options(${name} PRIVATE -msse4.1)
    add_test(NAME ${name} COMMAND ${name})
  endforeach()
endif()
