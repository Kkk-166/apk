cmake_minimum_required(VERSION 3.22)
project(vsdecoder CXX)

# Build ncnn from source with OpenMP OFF. The prebuilt's libomp aborts in
# __kmp_affinity_initialize on HyperOS after repeated decoder creation.
include(FetchContent)
FetchContent_Declare(ncnn_source
    URL https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-full-source.zip)
FetchContent_GetProperties(ncnn_source)
if(NOT ncnn_source_POPULATED)
    FetchContent_Populate(ncnn_source)
endif()

set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_RTTI OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)
add_subdirectory(${ncnn_source_SOURCE_DIR} ${CMAKE_BINARY_DIR}/ncnn)

add_library(vsdecoder SHARED ncnn_decoder.cpp)
target_link_libraries(vsdecoder ncnn android log)
