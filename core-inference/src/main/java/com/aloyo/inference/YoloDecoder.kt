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
     * @param output 模型输出的原始数据数组
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
 * YOLOv5输出解码器
 * YOLOv5输出形状: [1, numDetections, 5+numClasses]
 * 每行: [cx, cy, w, h, objectness, class1, class2, ...]
 */
class YoloV5Decoder : YoloDecoder {
    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val numDetections = output[0].size / (5 + config.numClasses)

        for (i in 0 until numDetections) {
            val offset = i * (5 + config.numClasses)
            val objectness = output[0][offset + 4]

            if (objectness < confidenceThreshold) continue

            // 找到最高置信度的类别
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
            if (finalConf < confidenceThreshold) continue

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
}

/**
 * YOLOv7输出解码器
 * YOLOv7输出格式与YOLOv5相同
 */
class YoloV7Decoder : YoloDecoder {
    // YOLOv7解码逻辑与v5相同，复用v5解码器
    private val v5Decoder = YoloV5Decoder()

    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        return v5Decoder.decode(output, config, confidenceThreshold)
    }
}

/**
 * YOLOv8输出解码器
 * YOLOv8输出形状: [1, 4+numClasses, numDetections]
 * 与v5/v7不同，v8没有objectness分支，直接输出类别概率
 * 输出是转置的：每列是一个检测，前4行是cx,cy,w,h，后面是类别概率
 */
class YoloV8Decoder : YoloDecoder {
    override fun decode(output: Array<FloatArray>, config: ModelConfig, confidenceThreshold: Float): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()

        // output形状: [4+numClasses, numDetections]
        val numRows = output.size
        if (numRows < 4 + config.numClasses) return detections

        val numDetections = output[0].size

        for (i in 0 until numDetections) {
            // 找到最高置信度的类别
            var maxClassConf = 0f
            var maxClassId = 0
            for (c in 0 until config.numClasses) {
                val classConf = output[4 + c][i]
                if (classConf > maxClassConf) {
                    maxClassConf = classConf
                    maxClassId = c
                }
            }

            if (maxClassConf < confidenceThreshold) continue

            detections.add(RawDetection(
                cx = output[0][i],
                cy = output[1][i],
                w = output[2][i],
                h = output[3][i],
                classId = maxClassId,
                confidence = maxClassConf
            ))
        }

        return detections
    }
}
