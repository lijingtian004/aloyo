# ALOYO Inference JNI - CMake构建配置
# 编译NCNN推理引擎的JNI桥接库

cmake_minimum_required(VERSION 3.22.1)
project(aloyo_inference)

# 设置C++标准
set(CMAKE_CXX_STANDARD 14)

# NCNN头文件路径（需要根据实际NCNN SDK位置调整）
set(NCNN_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/ncnn-android-vulkan/${ANDROID_ABI}/include")
set(NCNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/ncnn-android-vulkan/${ANDROID_ABI}/lib")

# 添加NCNN头文件
include_directories(${NCNN_INCLUDE_DIR})

# 添加JNI源文件
add_library(aloyo_inference SHARED aloyo_inference_jni.cpp)

# 链接NCNN库
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libncnn.so")

# 链接Android日志库
find_library(log-lib log)

# 链接Vulkan库（GPU加速）
find_library(vulkan-lib vulkan)

target_link_libraries(aloyo_inference
    ncnn
    ${log-lib}
    ${vulkan-lib}
)
