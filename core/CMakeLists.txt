cmake_minimum_required(VERSION 3.22)
project(rawcam_core CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rawcam_core STATIC
  src/rawv_writer.cpp
  src/rawv_reader.cpp
  src/pack10.cpp
  src/dng_writer.cpp)
target_include_directories(rawcam_core PUBLIC include)

if(NOT ANDROID)
  enable_testing()
  file(GLOB TEST_SOURCES tests/*.cpp)
  foreach(t ${TEST_SOURCES})
    get_filename_component(name ${t} NAME_WE)
    add_executable(${name} ${t})
    target_link_libraries(${name} rawcam_core)
    target_include_directories(${name} PRIVATE third_party/doctest)
    add_test(NAME ${name} COMMAND ${name})
  endforeach()
endif()
