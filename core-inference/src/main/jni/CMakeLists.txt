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

# 使用NCNN静态库（.a）
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libncnn.a")

# glslang静态库（Vulkan着色器编译所需）
add_library(glslang STATIC IMPORTED)
set_target_properties(glslang PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libglslang.a")

add_library(SPIRV STATIC IMPORTED)
set_target_properties(SPIRV PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libSPIRV.a")

add_library(MachineIndependent STATIC IMPORTED)
set_target_properties(MachineIndependent PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libMachineIndependent.a")

add_library(OSDependent STATIC IMPORTED)
set_target_properties(OSDependent PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libOSDependent.a")

add_library(GenericCodeGen STATIC IMPORTED)
set_target_properties(GenericCodeGen PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libGenericCodeGen.a")

add_library(glslang-default-resource-limits STATIC IMPORTED)
set_target_properties(glslang-default-resource-limits PROPERTIES IMPORTED_LOCATION "${NCNN_LIB_DIR}/libglslang-default-resource-limits.a")

# 链接Android系统库
find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)
find_library(z-lib z)

# 链接依赖（顺序很重要：被依赖的放后面）
target_link_libraries(aloyo_inference
    glslang
    SPIRV
    MachineIndependent
    OSDependent
    GenericCodeGen
    glslang-default-resource-limits
    ncnn
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
    ${z-lib}
)
