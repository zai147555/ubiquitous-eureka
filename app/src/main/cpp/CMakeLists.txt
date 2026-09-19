cmake_minimum_required(VERSION 3.22)
project(yolo_ncnn)

set(CMAKE_CXX_STANDARD 17)

# NCNN 预编译包由 tools/fetch_ncnn.sh 在构建前解压到本目录下的 ncnn/
# 目录结构：ncnn/<ANDROID_ABI>/lib/cmake/ncnn/...
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(yolo_ncnn SHARED yolo_ncnn_jni.cpp)
target_link_libraries(yolo_ncnn ncnn android log jnigraphics)
