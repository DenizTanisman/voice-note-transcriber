cmake_minimum_required(VERSION 3.22.1)
project(sesjni C CXX)

# whisper.cpp is vendored (not committed) under third_party/; see README for the pinned commit.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
# Snapdragon 8 Gen 3 (Cortex-X4/A720/A520): dot product + int8 matmul + fp16 arithmetic.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper-build)

add_library(sesjni SHARED whisper_jni.cpp)
target_compile_options(sesjni PRIVATE -O3)
target_link_libraries(sesjni whisper log android)
