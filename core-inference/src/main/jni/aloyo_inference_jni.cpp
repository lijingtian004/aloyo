#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>

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

// 存储上次推理的输出blob形状信息，供Kotlin层查询
static int s_last_output_blob_count = 0;
static std::vector<int> s_last_output_shapes; // [c0, h0, w0, c1, h1, w1, ...]

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
 * 提取所有输出blob并将通道拼接为Java二维数组
 * @param netPtr NCNN Net指针
 * @param inputData 预处理后的输入数据（CHW格式，BGR通道顺序）
 * @param width 输入宽度
 * @param height 输入高度
 * @return 输出数据二维数组，第一维=通道，第二维=空间(h*w)
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

    // 使用NCNN自动发现的输入blob索引
    const std::vector<int>& inputIndexes = net->input_indexes();
    if (inputIndexes.empty()) {
        LOGE("No input blob found in model");
        return nullptr;
    }
    int inputBlobIndex = inputIndexes[0];
    extractor.input(inputBlobIndex, inputMat);
    LOGI("Input blob index: %d", inputBlobIndex);

    // 获取所有输出blob索引
    const std::vector<int>& outputIndexes = net->output_indexes();
    if (outputIndexes.empty()) {
        LOGE("No output blob found in model");
        return nullptr;
    }
    LOGI("Number of output blobs: %d", (int)outputIndexes.size());

    // 提取所有输出blob
    s_last_output_shapes.clear();
    s_last_output_blob_count = 0;

    std::vector<ncnn::Mat> outputMats;
    int totalChannels = 0;

    for (size_t bi = 0; bi < outputIndexes.size(); bi++) {
        ncnn::Mat outputMat;
        int ret = extractor.extract(outputIndexes[bi], outputMat);
        if (ret != 0) {
            LOGE("Failed to extract output blob %d (index=%d), ret=%d", (int)bi, outputIndexes[bi], ret);
            continue;
        }
        outputMats.push_back(outputMat);
        totalChannels += outputMat.c;
        s_last_output_shapes.push_back(outputMat.c);
        s_last_output_shapes.push_back(outputMat.h);
        s_last_output_shapes.push_back(outputMat.w);
        s_last_output_blob_count++;
        LOGI("Output blob %d: index=%d, shape=[c=%d, h=%d, w=%d, spatial=%d]",
             (int)bi, outputIndexes[bi], outputMat.c, outputMat.h, outputMat.w, outputMat.h * outputMat.w);
    }

    if (outputMats.empty()) {
        LOGE("No output blobs extracted");
        return nullptr;
    }

    // 将所有blob的通道拼接成一个Java二维数组
    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray resultArray = env->NewObjectArray(totalChannels, floatArrayClass, nullptr);

    int channelOffset = 0;
    for (size_t bi = 0; bi < outputMats.size(); bi++) {
        const ncnn::Mat& outputMat = outputMats[bi];
        int outChannels = outputMat.c;
        int outHeight = outputMat.h;
        int outWidth = outputMat.w;
        int channelDataSize = outHeight * outWidth;

        // 首次推理时打印前几个通道的前5个值（诊断用）
        static bool has_logged_detail = false;
        if (!has_logged_detail && bi == 0) {
            has_logged_detail = true;
            for (int c = 0; c < outChannels && c < 8; c++) {
                const ncnn::Mat channelMat = outputMat.channel(c);
                std::string vals;
                for (int i = 0; i < channelDataSize && i < 5; i++) {
                    if (i > 0) vals += ", ";
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%.4f", channelMat.row(i / outWidth)[i % outWidth]);
                    vals += buf;
                }
                LOGI("  blob%d ch[%d] first values: [%s]", (int)bi, c, vals.c_str());
            }
            // 也打印最后几个通道
            if (outChannels > 8) {
                for (int c = outChannels - 4; c < outChannels; c++) {
                    if (c < 8) continue;
                    const ncnn::Mat channelMat = outputMat.channel(c);
                    std::string vals;
                    for (int i = 0; i < channelDataSize && i < 5; i++) {
                        if (i > 0) vals += ", ";
                        char buf[32];
                        snprintf(buf, sizeof(buf), "%.4f", channelMat.row(i / outWidth)[i % outWidth]);
                        vals += buf;
                    }
                    LOGI("  blob%d ch[%d] first values: [%s]", (int)bi, c, vals.c_str());
                }
            }
        }

        for (int c = 0; c < outChannels; c++) {
            const ncnn::Mat channelMat = outputMat.channel(c);
            jfloatArray channelArray = env->NewFloatArray(channelDataSize);

            jfloat* channelData = new jfloat[channelDataSize];
            for (int y = 0; y < outHeight; y++) {
                for (int x = 0; x < outWidth; x++) {
                    channelData[y * outWidth + x] = channelMat.row(y)[x];
                }
            }
            env->SetFloatArrayRegion(channelArray, 0, channelDataSize, channelData);
            delete[] channelData;

            env->SetObjectArrayElement(resultArray, channelOffset + c, channelArray);
            env->DeleteLocalRef(channelArray);
        }

        channelOffset += outChannels;
    }

    LOGI("Inference completed: %d blobs, %d total channels", (int)outputMats.size(), totalChannels);
    return resultArray;
}

/**
 * 获取上次推理的输出blob形状信息
 * @return IntArray格式: [numBlobs, c0, h0, w0, c1, h1, w1, ...]
 */
JNIEXPORT jintArray JNICALL
Java_com_aloyo_inference_NcnnInferenceEngine_nativeGetLastOutputShape(
    JNIEnv* env, jobject thiz) {
    int size = 1 + (int)s_last_output_shapes.size();
    jintArray result = env->NewIntArray(size);
    if (result == nullptr) {
        return nullptr;
    }
    jint* data = new jint[size];
    data[0] = s_last_output_blob_count;
    for (size_t i = 0; i < s_last_output_shapes.size(); i++) {
        data[i + 1] = s_last_output_shapes[i];
    }
    env->SetIntArrayRegion(result, 0, size, data);
    delete[] data;
    return result;
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
