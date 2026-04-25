package com.aloyo.inference

import com.aloyo.common.Detection
import com.aloyo.common.ModelConfig

/**
 * YOLO输出解码器接口
 * 不同版本的YOLO模型输出格式不同，需要对应的解码器
 */
interface YoloDecoder {
    /**
     * 解码模型原始输出
     * @param output 模型输出的原始数据数组（JNI层按NCNN通道分离的二维数组）
     * @param config 模型配置
     * @param confidenceThreshold 置信度阈值
     * @return 解码后的检测列表（未经过NMS）
     */
    fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection>
}

/**
 * 原始检测结果（NMS之前）
 */
data class RawDetection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val classId: Int,
    val confidence: Float
)

/**
 * 统一YOLO解码器
 * 自动检测NCNN输出格式，兼容YOLOv5/v7/v8的不同输出布局
 *
 * NCNN输出经过JNI层后为 Array<FloatArray>，其中：
 * - 第一维 = NCNN Mat的通道数(outChannels)
 * - 第二维 = outHeight * outWidth
 *
 * 可能的输出格式：
 * 1. YOLOv8格式: [4+numClasses, numDetections] → output.size小, output[0].size大
 *    每行是一个属性（cx/cy/w/h/class0/class1...），每列是一个检测
 *
 * 2. YOLOv5格式（转置后同v8）: [5+numClasses, numDetections] → output.size小, output[0].size大
 *    onnx2ncnn通常会自动转置，使输出与v8格式一致
 *
 * 3. YOLOv5格式（未转置）: [numDetections, 5+numClasses] → output.size大, output[0].size小
 *    每行是一个检测：[cx, cy, w, h, objectness, class0, class1...]
 *
 * 4. 扁平数组格式: [1, numDetections * (5+numClasses)] → output.size=1, output[0].size很大
 *    所有检测数据拼接在一个数组中
 */
class UnifiedYoloDecoder : YoloDecoder {

    companion object {
        private const val TAG = "UnifiedYoloDecoder"

        // 是否已打印过诊断日志（仅首次推理时打印）
        @Volatile
        private var hasLoggedDiag = false
    }

    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        val numRows = output.size
        if (numRows == 0) return emptyList()
        val numCols = output[0].size
        if (numCols == 0) return emptyList()

        val v8Attrs = 4 + config.numClasses   // YOLOv8: cx,cy,w,h + classProbs
        val v5Attrs = 5 + config.numClasses   // YOLOv5: cx,cy,w,h,objectness + classProbs

        // 自动检测输出格式
        val format = detectFormat(numRows, numCols, v8Attrs, v5Attrs)

        // 首次推理时打印诊断日志
        if (!hasLoggedDiag) {
            hasLoggedDiag = true
            val diagMsg = buildString {
                append("Output shape: [$numRows x $numCols], ")
                append("v8Attrs=$v8Attrs, v5Attrs=$v5Attrs, ")
                append("detected: ${format.name}")
            }
            android.util.Log.i(TAG, diagMsg)

            // 打印前几个通道的前5个值，帮助诊断
            for (c in 0 until minOf(numRows, 6)) {
                val vals = (0 until minOf(numCols, 5)).joinToString(", ") {
                    String.format("%.4f", output[c][it])
                }
                android.util.Log.i(TAG, "  ch[$c] first values: [$vals]")
            }
        }

        return when (format) {
            OutputFormat.V8_TRANSPOSED -> decodeV8Format(output, config, confidenceThreshold, numRows, numCols)
            OutputFormat.V5_PER_ROW -> decodeV5PerRowFormat(output, config, confidenceThreshold, numRows, numCols)
            OutputFormat.V5_FLAT -> decodeV5FlatFormat(output, config, confidenceThreshold, numCols, v5Attrs)
        }
    }

    /**
     * 检测输出格式
     */
    private fun detectFormat(numRows: Int, numCols: Int, v8Attrs: Int, v5Attrs: Int): OutputFormat {
        // 格式1: YOLOv8/转置V5 → [attrs, numDetections]
        // 行数等于属性数(4+N或5+N)，列数远大于行数
        if ((numRows == v8Attrs || numRows == v5Attrs) && numCols > numRows) {
            return OutputFormat.V8_TRANSPOSED
        }

        // 格式2: V5未转置 → [numDetections, attrs]
        // 列数等于属性数(5+N)，行数远大于列数
        if (numCols == v5Attrs && numRows > numCols) {
            return OutputFormat.V5_PER_ROW
        }

        // 格式3: 也可能是V8未转置 → [numDetections, 4+N]
        // 列数等于4+N，行数远大于列数
        if (numCols == v8Attrs && numRows > numCols) {
            // V8格式没有objectness，但布局类似V5 per-row
            // 按V8格式处理（每行一个检测，无objectness）
            return OutputFormat.V5_PER_ROW
        }

        // 格式4: 扁平数组 → [1, numDetections * attrs]
        // 只有一个通道，数据量很大
        if (numRows == 1 && numCols > v5Attrs * 10) {
            return OutputFormat.V5_FLAT
        }

        // 默认：行少列多按V8格式，行多列少按V5格式
        return if (numRows <= numCols) {
            OutputFormat.V8_TRANSPOSED
        } else {
            OutputFormat.V5_PER_ROW
        }
    }

    /**
     * YOLOv8格式解码（转置布局）
     * output[0][i] = cx_i, output[1][i] = cy_i, output[2][i] = w_i, output[3][i] = h_i
     * output[4+c][i] = class_c_prob_i
     * 也兼容转置后的V5格式（有objectness行）
     */
    private fun decodeV8Format(
        output: Array<FloatArray>, config: ModelConfig, confThresh: Float,
        numRows: Int, numCols: Int
    ): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val numDetections = numCols
        val hasObjectness = numRows == 5 + config.numClasses

        for (i in 0 until numDetections) {
            // 读取类别概率
            var maxClassConf = 0f
            var maxClassId = 0
            val classOffset = if (hasObjectness) 5 else 4
            for (c in 0 until config.numClasses) {
                if (classOffset + c >= numRows) break
                val classConf = output[classOffset + c][i]
                if (classConf > maxClassConf) {
                    maxClassConf = classConf
                    maxClassId = c
                }
            }

            // 计算最终置信度
            val finalConf = if (hasObjectness) {
                val objectness = output[4][i]
                objectness * maxClassConf
            } else {
                maxClassConf
            }

            if (finalConf < confThresh) continue

            detections.add(RawDetection(
                cx = output[0][i],
                cy = output[1][i],
                w = output[2][i],
                h = output[3][i],
                classId = maxClassId,
                confidence = finalConf
            ))
        }

        return detections
    }

    /**
     * YOLOv5格式解码（每行一个检测）
     * output[i] = [cx_i, cy_i, w_i, h_i, objectness_i, class0_i, class1_i, ...]
     */
    private fun decodeV5PerRowFormat(
        output: Array<FloatArray>, config: ModelConfig, confThresh: Float,
        numRows: Int, numCols: Int
    ): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val hasObjectness = numCols >= 5 + config.numClasses
        val numDetections = numRows

        for (i in 0 until numDetections) {
            // 读取objectness（如果有）
            if (hasObjectness) {
                val objectness = output[i][4]
                if (objectness < confThresh) continue
            }

            // 读取类别概率
            var maxClassConf = 0f
            var maxClassId = 0
            val classOffset = if (hasObjectness) 5 else 4
            for (c in 0 until config.numClasses) {
                if (classOffset + c >= numCols) break
                val classConf = output[i][classOffset + c]
                if (classConf > maxClassConf) {
                    maxClassConf = classConf
                    maxClassId = c
                }
            }

            // 计算最终置信度
            val finalConf = if (hasObjectness) {
                output[i][4] * maxClassConf
            } else {
                maxClassConf
            }

            if (finalConf < confThresh) continue

            detections.add(RawDetection(
                cx = output[i][0],
                cy = output[i][1],
                w = output[i][2],
                h = output[i][3],
                classId = maxClassId,
                confidence = finalConf
            ))
        }

        return detections
    }

    /**
     * YOLOv5格式解码（扁平数组）
     * output[0] = [cx0, cy0, w0, h0, obj0, cls0_0, ..., cx1, cy1, w1, h1, obj1, cls1_0, ...]
     */
    private fun decodeV5FlatFormat(
        output: Array<FloatArray>, config: ModelConfig, confThresh: Float,
        numCols: Int, v5Attrs: Int
    ): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val numDetections = numCols / v5Attrs

        for (i in 0 until numDetections) {
            val offset = i * v5Attrs
            val objectness = output[0][offset + 4]

            if (objectness < confThresh) continue

            var maxClassConf = 0f
            var maxClassId = 0
            for (c in 0 until config.numClasses) {
                val classConf = output[0][offset + 5 + c]
                if (classConf > maxClassConf) {
                    maxClassConf = classConf
                    maxClassId = c
                }
            }

            val finalConf = objectness * maxClassConf
            if (finalConf < confThresh) continue

            detections.add(RawDetection(
                cx = output[0][offset],
                cy = output[0][offset + 1],
                w = output[0][offset + 2],
                h = output[0][offset + 3],
                classId = maxClassId,
                confidence = finalConf
            ))
        }

        return detections
    }

    /**
     * 输出格式枚举
     */
    private enum class OutputFormat {
        /** YOLOv8/转置V5: [attrs, numDetections] */
        V8_TRANSPOSED,
        /** V5未转置: [numDetections, attrs] */
        V5_PER_ROW,
        /** V5扁平: [1, numDetections * attrs] */
        V5_FLAT
    }
}

/**
 * YOLOv5输出解码器（保留兼容，内部使用统一解码器）
 */
class YoloV5Decoder : YoloDecoder {
    private val unifiedDecoder = UnifiedYoloDecoder()

    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold)
    }
}

/**
 * YOLOv7输出解码器（格式与v5相同）
 */
class YoloV7Decoder : YoloDecoder {
    private val unifiedDecoder = UnifiedYoloDecoder()

    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold)
    }
}

/**
 * YOLOv8输出解码器（保留兼容，内部使用统一解码器）
 */
class YoloV8Decoder : YoloDecoder {
    private val unifiedDecoder = UnifiedYoloDecoder()

    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold)
    }
}
