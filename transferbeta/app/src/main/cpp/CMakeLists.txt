cmake_minimum_required(VERSION 3.22.1)
project(nllb_sentencepiece LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Google SentencePiece sources must be supplied by the build environment.
# The Android NDK build must not silently use an unrelated host installation.
set(SENTENCEPIECE_SOURCE_DIR "" CACHE PATH "Path to pinned Google sentencepiece source checkout")
if(NOT EXISTS "${SENTENCEPIECE_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "SENTENCEPIECE_SOURCE_DIR must point to a pinned SentencePiece source tree")
endif()

set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
add_subdirectory("${SENTENCEPIECE_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/sentencepiece" EXCLUDE_FROM_ALL)

add_library(nllb_sentencepiece SHARED nllb_sentencepiece_jni.cpp)
target_link_libraries(nllb_sentencepiece PRIVATE sentencepiece-static log)
target_include_directories(nllb_sentencepiece PRIVATE "${SENTENCEPIECE_SOURCE_DIR}/src")
