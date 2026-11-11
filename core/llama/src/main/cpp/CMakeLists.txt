cmake_minimum_required(VERSION 3.22.1)
project(llama-android LANGUAGES C CXX)

# Passed in from core/llama/build.gradle.kts so this file has no hard-coded relative paths.
if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is not set. Run `git submodule update --init` and build through Gradle.")
endif()

# Build only the core libraries; nothing else from llama.cpp is needed on device.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} build-llama)

add_library(llama-android SHARED llama-android.cpp)
target_link_libraries(llama-android llama log android)
