# ALOYO Inference JNI - CMake构建配置
# 编译NCNN推理引擎的JNI桥接库

cmake_minimum_required(VERSION 3.22.1)
project(aloyo_inference)

set(CMAKE_CXX_STANDARD 14)

# NCNN SDK路径
set(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/ncnn-android-vulkan")

# 根据ABI选择对应的NCNN库目录
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(NCNN_LIB_DIR "${NCNN_DIR}/armeabi-v7a/lib")
    set(NCNN_INCLUDE_DIR "${NCNN_DIR}/armeabi-v7a/include")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NCNN_LIB_DIR "${NCNN_DIR}/arm64-v8a/lib")
    set(NCNN_INCLUDE_DIR "${NCNN_DIR}/arm64-v8a/include")
elseif(ANDROID_ABI STREQUAL "x86")
    set(NCNN_LIB_DIR "${NCNN_DIR}/x86/lib")
    set(NCNN_INCLUDE_DIR "${NCNN_DIR}/x86/include")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(NCNN_LIB_DIR "${NCNN_DIR}/x86_64/lib")
    set(NCNN_INCLUDE_DIR "${NCNN_DIR}/x86_64/include")
else()
    message(WARNING "Unsupported ABI: ${ANDROID_ABI}")
endif()

# 添加NCNN头文件
include_directories(${NCNN_INCLUDE_DIR})

# 添加JNI源文件
add_library(aloyo_inference SHARED aloyo_inference_jni.cpp)

# 使用NCNN动态库（.so），避免静态库的STL兼容性问题
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libncnn.so")

# 链接Android系统库
find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)
find_library(z-lib z)

# 链接依赖
target_link_libraries(aloyo_inference
    ncnn
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
    ${z-lib}
)
