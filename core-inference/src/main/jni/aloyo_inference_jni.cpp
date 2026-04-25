#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

// NCNN头文件
#include "ncnn/net.h"
#include "ncnn/mat.h"

#define LOG_TAG "ALOYO_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * NCNN推理引擎JNI桥接层
 * 提供Java层调用NCNN C++ API的接口
 * 包括模型加载、推理执行和资源释放
 */

// 将NCNN Net指针存储为jlong
static ncnn::Net* getNetFromPtr(jlong ptr) {
    return reinterpret_cast<ncnn::Net*>(ptr);
}

extern "C" {

/**
 * 加载NCNN模型
 * @param paramPath 模型参数文件路径（.param）
 * @param binPath 模型权重文件路径（.bin）
 * @return NCNN Net指针，0表示失败
 */
JNIEXPORT jlong JNICALL
Java_com_aloyo_inference_NcnnInferenceEngine_nativeLoadModel(
    JNIEnv* env, jobject thiz,
    jstring paramPath, jstring binPath) {

    const char* param_path = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin_path = env->GetStringUTFChars(binPath, nullptr);

    // 创建NCNN Net对象
    ncnn::Net* net = new ncnn::Net();

    // 启用Vulkan GPU加速（如果可用）
    net->opt.use_vulkan_compute = true;

    // 加载模型参数
    int ret = net->load_param(param_path);
    if (ret != 0) {
        LOGE("Failed to load param file: %s", param_path);
        delete net;
        env->ReleaseStringUTFChars(paramPath, param_path);
        env->ReleaseStringUTFChars(binPath, bin_path);
        return 0;
    }

    // 加载模型权重
    ret = net->load_model(bin_path);
    if (ret != 0) {
        LOGE("Failed to load model file: %s", bin_path);
        delete net;
        env->ReleaseStringUTFChars(paramPath, param_path);
        env->ReleaseStringUTFChars(binPath, bin_path);
        return 0;
    }

    env->ReleaseStringUTFChars(paramPath, param_path);
    env->ReleaseStringUTFChars(binPath, bin_path);

    LOGI("NCNN model loaded successfully");
    return reinterpret_cast<jlong>(net);
}

/**
 * 执行NCNN推理
 * @param netPtr NCNN Net指针
 * @param inputData 预处理后的输入数据（CHW格式，BGR通道顺序）
 * @param width 输入宽度
 * @param height 输入高度
 * @return 输出数据二维数组
 */
JNIEXPORT jobjectArray JNICALL
Java_com_aloyo_inference_NcnnInferenceEngine_nativeRunInference(
    JNIEnv* env, jobject thiz,
    jlong netPtr, jfloatArray inputData, jint width, jint height) {

    ncnn::Net* net = getNetFromPtr(netPtr);
    if (net == nullptr) {
        LOGE("Invalid net pointer");
        return nullptr;
    }

    // 获取输入数据
    jsize inputLen = env->GetArrayLength(inputData);
    jfloat* input_data = env->GetFloatArrayElements(inputData, nullptr);

    // 创建NCNN Mat（CHW格式，3通道）
    ncnn::Mat inputMat(width, height, 3);

    // 将输入数据复制到NCNN Mat
    const int channelSize = width * height;
    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                inputMat.channel(c).row(y)[x] = input_data[c * channelSize + y * width + x];
            }
        }
    }
    env->ReleaseFloatArrayElements(inputData, input_data, JNI_ABORT);

    // 创建提取器
    ncnn::Extractor extractor = net->create_extractor();

    // 尝试常见的输入blob名称
    const char* inputNames[] = {"in0", "images", "input", "data", "x"};
    bool inputSet = false;
    for (const char* name : inputNames) {
        int ret = extractor.input(name, inputMat);
        if (ret == 0) {
            LOGI("Input blob name: %s", name);
            inputSet = true;
            break;
        }
    }
    if (!inputSet) {
        // 使用索引方式设置输入
        extractor.input(0, inputMat);
        LOGI("Input blob set by index 0");
    }

    // 获取输出
    ncnn::Mat outputMat;
    const char* outputNames[] = {"out0", "output", "output0", "pred", "proto"};
    bool outputExtracted = false;
    for (const char* name : outputNames) {
        int ret = extractor.extract(name, outputMat);
        if (ret == 0) {
            LOGI("Output blob name: %s", name);
            outputExtracted = true;
            break;
        }
    }
    if (!outputExtracted) {
        // 使用索引方式提取输出
        int ret = extractor.extract(0, outputMat);
        if (ret != 0) {
            LOGE("Failed to extract any output, ret=%d", ret);
            return nullptr;
        }
        LOGI("Output blob extracted by index 0");
    }

    // 将输出转换为Java二维数组
    // outputMat形状: [channels, height, width] 或 [numDetections, attrs]
    int outChannels = outputMat.c;
    int outHeight = outputMat.h;
    int outWidth = outputMat.w;

    // 记录输出形状（帮助诊断解码问题）
    LOGI("NCNN output shape: c=%d, h=%d, w=%d (total elements per channel: %d)",
         outChannels, outHeight, outWidth, outHeight * outWidth);

    // 打印前几个通道的前5个值（仅首次推理）
    static bool has_logged_detail = false;
    if (!has_logged_detail) {
        has_logged_detail = true;
        for (int c = 0; c < outChannels && c < 6; c++) {
            const ncnn::Mat channelMat = outputMat.channel(c);
            std::string vals;
            for (int i = 0; i < outHeight * outWidth && i < 5; i++) {
                if (i > 0) vals += ", ";
                char buf[32];
                snprintf(buf, sizeof(buf), "%.4f", channelMat.row(i / outWidth)[i % outWidth]);
                vals += buf;
            }
            LOGI("  output ch[%d] first values: [%s]", c, vals.c_str());
        }
    }

    // 创建Java FloatArray数组
    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray resultArray = env->NewObjectArray(outChannels, floatArrayClass, nullptr);

    for (int c = 0; c < outChannels; c++) {
        const ncnn::Mat channelMat = outputMat.channel(c);
        int channelDataSize = outHeight * outWidth;
        jfloatArray channelArray = env->NewFloatArray(channelDataSize);

        jfloat* channelData = new jfloat[channelDataSize];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                channelData[y * outWidth + x] = channelMat.row(y)[x];
            }
        }
        env->SetFloatArrayRegion(channelArray, 0, channelDataSize, channelData);
        delete[] channelData;

        env->SetObjectArrayElement(resultArray, c, channelArray);
        env->DeleteLocalRef(channelArray);
    }

    LOGI("Inference completed: output shape [%d, %d, %d]", outChannels, outHeight, outWidth);
    return resultArray;
}

/**
 * 释放NCNN模型资源
 * @param netPtr NCNN Net指针
 */
JNIEXPORT void JNICALL
Java_com_aloyo_inference_NcnnInferenceEngine_nativeReleaseModel(
    JNIEnv* env, jobject thiz,
    jlong netPtr) {

    ncnn::Net* net = getNetFromPtr(netPtr);
    if (net != nullptr) {
        delete net;
        LOGI("NCNN model released");
    }
}

} // extern "C"
