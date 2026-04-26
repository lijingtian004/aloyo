package com.aloyo.inference

import com.aloyo.common.Detection
import com.aloyo.common.ModelConfig
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

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
     * @param blobShapes 每个输出blob的形状信息列表（c, h, w），为空时自动推断
     * @return 解码后的检测列表（未经过NMS）
     */
    fun decode(
        output: Array<FloatArray>,
        config: ModelConfig,
        confidenceThreshold: Float,
        blobShapes: List<OutputBlobInfo> = emptyList()
    ): List<RawDetection>
}

/**
 * 输出blob形状信息
 * @param channels 通道数
 * @param height 高度（网格行数）
 * @param width 宽度（网格列数）
 */
data class OutputBlobInfo(
    val channels: Int,
    val height: Int,
    val width: Int
) {
    val spatialSize: Int get() = height * width
}

/**
 * 原始检测结果（NMS之前）
 * cx, cy, w, h 均为模型输入空间坐标（如640×640）
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
 *
 * 5. YOLOv5多锚框拼接格式: [numAnchors * (5+numClasses), numDetections]
 *    多个锚框组的通道沿通道维度拼接，如 [3×7=21, 1024]
 *    每个锚框组7个通道: cx, cy, w, h, objectness, cls0, cls1
 */
class UnifiedYoloDecoder : YoloDecoder {

    // 追踪最近一次解码是否跳过了objectness（供全局自适应过滤使用）
    @Volatile
    private var lastSkipObjectness = false

    companion object {
        private const val TAG = "UnifiedYoloDecoder"

        // 是否已打印过诊断日志（仅首次推理时打印）
        @Volatile
        private var hasLoggedDiag = false

        /**
         * sigmoid激活函数
         * 将原始logit值映射到(0, 1)区间
         */
        private fun sigmoid(x: Float): Float {
            return 1.0f / (1.0f + exp(-x.coerceIn(-80f, 80f)))
        }

        /**
         * YOLOv5默认锚框尺寸（针对640×640输入）
         * 按stride分组，每组3个锚框(anchor_w, anchor_h)
         * 这些锚框尺寸是在COCO数据集上k-means聚类得到的
         */
        private val DEFAULT_ANCHORS = mapOf(
            8 to floatArrayOf(10f, 13f, 16f, 30f, 33f, 23f),
            16 to floatArrayOf(30f, 61f, 62f, 45f, 59f, 119f),
            32 to floatArrayOf(116f, 90f, 156f, 198f, 373f, 326f)
        )

        /**
         * 根据stride获取对应的锚框尺寸
         * 如果stride不是标准值(8/16/32)，找最近的stride并按输入尺寸缩放
         * @param stride 特征图步长
         * @param inputSize 模型输入尺寸（宽或高，假设正方形输入）
         * @return 锚框数组 [aw0, ah0, aw1, ah1, aw2, ah2]
         */
        fun getAnchorsForStride(stride: Float, inputSize: Int): FloatArray {
            val closestStride = DEFAULT_ANCHORS.keys.minByOrNull { kotlin.math.abs(it - stride) } ?: 16
            val scale = inputSize.toFloat() / 640f
            return DEFAULT_ANCHORS[closestStride]!!.map { it * scale }.toFloatArray()
        }
    }

    override fun decode(
        output: Array<FloatArray>,
        config: ModelConfig,
        confidenceThreshold: Float,
        blobShapes: List<OutputBlobInfo>
    ): List<RawDetection> {
        lastSkipObjectness = false
        val numRows = output.size
        if (numRows == 0) return emptyList()
        val numCols = output[0].size
        if (numCols == 0) return emptyList()

        val v8Attrs = 4 + config.numClasses
        val v5Attrs = 5 + config.numClasses

        // 首次推理时打印诊断日志
        if (!hasLoggedDiag) {
            hasLoggedDiag = true
            val diagMsg = buildString {
                append("Output shape: [$numRows x $numCols], ")
                append("v8Attrs=$v8Attrs, v5Attrs=$v5Attrs, ")
                append("blobShapes=$blobShapes")
            }
            android.util.Log.i(TAG, diagMsg)

            // 打印前几个通道的前5个值
            for (c in 0 until minOf(numRows, 8)) {
                val vals = (0 until minOf(numCols, 5)).joinToString(", ") {
                    String.format("%.4f", output[c][it])
                }
                android.util.Log.i(TAG, "  ch[$c] first values: [$vals]")
            }
        }

        // 解码原始输出
        val detections = if (blobShapes.isNotEmpty()) {
            decodeMultiBlob(output, config, confidenceThreshold, blobShapes, v5Attrs, v8Attrs)
        } else {
            // 单blob情况：自动检测格式并解码
            val format = detectFormat(numRows, numCols, v8Attrs, v5Attrs)
            android.util.Log.i(TAG, "Detected format: ${format.name}")
            when (format) {
                OutputFormat.V5_MULTI_ANCHOR -> decodeV5MultiAnchor(output, config, confidenceThreshold, numRows, numCols, null, null)
                OutputFormat.V8_TRANSPOSED -> decodeV8Format(output, config, confidenceThreshold, numRows, numCols)
                OutputFormat.V5_PER_ROW -> decodeV5PerRowFormat(output, config, confidenceThreshold, numRows, numCols)
                OutputFormat.V5_FLAT -> decodeV5FlatFormat(output, config, confidenceThreshold, numCols, v5Attrs)
            }
        }

        // 全局自适应过滤：当objectness被跳过时，类别置信度无法区分前景/背景
        // 使用gap-based方法：在排序后的置信度中找到最大间隔作为前景/背景分离点
        if (lastSkipObjectness && detections.size > 5) {
            return applyGapBasedFiltering(detections, confidenceThreshold)
        }

        return detections
    }

    /**
     * 基于最大间隔（gap）的自适应过滤
     * 当objectness通道未使用时，类别置信度对前景/背景区分度不足，
     * 背景和前景的置信度可能非常接近（如0.978 vs 0.998）。
     *
     * 原理：将所有检测按置信度降序排列，找到相邻元素之间的最大间隔。
     * 真实目标的置信度通常明显高于背景，因此最大间隔自然出现在
     * 前景和背景的交界处，以此作为分离阈值。
     *
     * 优点：不依赖固定百分位数，直接从数据分布中找到最佳分离点
     */
    private fun applyGapBasedFiltering(
        detections: List<RawDetection>,
        confThresh: Float
    ): List<RawDetection> {
        val confs = detections.map { it.confidence }.sortedDescending()
        val maxConf = confs.first()
        val medianConf = confs[confs.size / 2]

        // 在排序后的置信度中查找最大间隔
        // 只检查前半部分（高置信度区域），避免被尾部噪声干扰
        val checkRange = minOf(confs.size - 1, maxOf(50, confs.size / 2))
        var maxGap = 0f
        var maxGapIdx = 0
        for (i in 0 until checkRange) {
            val gap = confs[i] - confs[i + 1]
            if (gap > maxGap) {
                maxGap = gap
                maxGapIdx = i
            }
        }

        val beforeCount = detections.size
        val result: List<RawDetection>

        if (maxGap > 0.005f) {
            // 找到显著间隔：以间隔中点作为前景/背景分离阈值
            val gapThresh = (confs[maxGapIdx] + confs[maxGapIdx + 1]) / 2f
            val effectiveThresh = maxOf(confThresh, gapThresh)
            result = detections.filter { it.confidence >= effectiveThresh }

            android.util.Log.i(TAG, "Gap-based filtering: maxGap=${"%.4f".format(maxGap)} " +
                    "at idx=$maxGapIdx, gapThresh=${"%.4f".format(gapThresh)}, " +
                    "maxConf=${"%.4f".format(maxConf)}, medianConf=${"%.4f".format(medianConf)}, " +
                    "before=$beforeCount, after=${result.size}")
        } else {
            // 无显著间隔：前景/背景置信度几乎无差异，无法可靠区分
            // 保守策略：仅保留置信度最高的少量检测（可能是全部假阳性或全部真阳性）
            val topN = minOf(10, confs.size)
            val topThresh = confs[topN - 1]
            result = detections.filter { it.confidence >= topThresh }

            android.util.Log.i(TAG, "Gap-based filtering (no significant gap): " +
                    "maxGap=${"%.4f".format(maxGap)}, topThresh=${"%.4f".format(topThresh)}, " +
                    "maxConf=${"%.4f".format(maxConf)}, medianConf=${"%.4f".format(medianConf)}, " +
                    "before=$beforeCount, after=${result.size}")
        }

        return result
    }

    /**
     * 多blob解码：按blob分别解码后合并结果
     * 每个blob可能有不同的空间尺寸（对应不同的检测头/stride）
     */
    private fun decodeMultiBlob(
        output: Array<FloatArray>,
        config: ModelConfig,
        confThresh: Float,
        blobShapes: List<OutputBlobInfo>,
        v5Attrs: Int,
        v8Attrs: Int
    ): List<RawDetection> {
        val allDetections = mutableListOf<RawDetection>()
        var channelOffset = 0

        // 计算标准YOLOv5 stride：基于grid尺寸排名分配stride 8/16/32
        // YOLOv5的stride是模型架构属性，不依赖于config中的inputSize
        // 最大grid → 最小stride(8)，中等grid → stride(16)，最小grid → 最大stride(32)
        val STANDARD_STRIDES = intArrayOf(8, 16, 32)
        val sortedGridSizes = blobShapes.map { it.height }.sortedDescending()
        val strideMap = mutableMapOf<Int, Float>()
        for ((idx, gridSize) in sortedGridSizes.withIndex()) {
            val standardStride = if (idx < STANDARD_STRIDES.size) STANDARD_STRIDES[idx].toFloat()
                                 else config.inputHeight.toFloat() / gridSize
            // 同一grid尺寸可能出现在多个blob中，取第一次分配的stride
            if (!strideMap.containsKey(gridSize)) {
                strideMap[gridSize] = standardStride
            }
        }
        // 推断模型实际训练时的输入尺寸：最大grid × 最小stride
        val effectiveInputSize = (sortedGridSizes.firstOrNull() ?: config.inputHeight) *
                (strideMap[sortedGridSizes.firstOrNull()] ?: 8f).toInt()

        android.util.Log.i(TAG, "decodeMultiBlob: sortedGrids=$sortedGridSizes, strideMap=$strideMap, " +
                "effectiveInputSize=$effectiveInputSize, configInputSize=${config.inputWidth}x${config.inputHeight}")

        for (blobInfo in blobShapes) {
            val blobChannels = blobInfo.channels
            val blobSpatial = blobInfo.spatialSize
            val blobHeight = blobInfo.height
            val blobWidth = blobInfo.width

            // 提取该blob的通道数据
            val blobOutput = Array(blobChannels) { ch ->
                output[channelOffset + ch].copyOfRange(0, minOf(blobSpatial, output[channelOffset + ch].size))
            }

            // 检测该blob的格式
            val format = detectFormat(blobChannels, blobSpatial, v8Attrs, v5Attrs)

            // 获取该blob对应的标准stride
            val standardStride = strideMap[blobHeight] ?: (config.inputHeight.toFloat() / blobHeight)

            val detections = when (format) {
                OutputFormat.V5_MULTI_ANCHOR -> decodeV5MultiAnchor(
                    blobOutput, config, confThresh, blobChannels, blobSpatial,
                    blobHeight, blobWidth, standardStride, effectiveInputSize
                )
                OutputFormat.V8_TRANSPOSED -> decodeV8Format(blobOutput, config, confThresh, blobChannels, blobSpatial)
                OutputFormat.V5_PER_ROW -> decodeV5PerRowFormat(blobOutput, config, confThresh, blobChannels, blobSpatial)
                OutputFormat.V5_FLAT -> decodeV5FlatFormat(blobOutput, config, confThresh, blobSpatial, v5Attrs)
            }

            allDetections.addAll(detections)
            channelOffset += blobChannels
        }

        return allDetections
    }

    /**
     * 检测输出格式
     */
    private fun detectFormat(numRows: Int, numCols: Int, v8Attrs: Int, v5Attrs: Int): OutputFormat {
        // 格式5: YOLOv5多锚框拼接 → [numAnchors * v5Attrs, numDetections]
        // 当行数是v5Attrs的倍数且大于v5Attrs时，认为是多个锚框组拼接
        // 例如 [21, 1024] = 3个锚框 × 7个属性，1024个空间位置
        if (numRows > v5Attrs && numRows % v5Attrs == 0 && numCols > numRows) {
            return OutputFormat.V5_MULTI_ANCHOR
        }

        // 格式1: YOLOv8/转置V5 → [attrs, numDetections]
        if ((numRows == v8Attrs || numRows == v5Attrs) && numCols > numRows) {
            return OutputFormat.V8_TRANSPOSED
        }

        // 格式2: V5未转置 → [numDetections, attrs]
        if (numCols == v5Attrs && numRows > numCols) {
            return OutputFormat.V5_PER_ROW
        }

        // 格式3: V8未转置 → [numDetections, 4+N]
        if (numCols == v8Attrs && numRows > numCols) {
            return OutputFormat.V5_PER_ROW
        }

        // 格式4: 扁平数组 → [1, numDetections * attrs]
        if (numRows == 1 && numCols > v5Attrs * 10) {
            return OutputFormat.V5_FLAT
        }

        // 默认
        return if (numRows <= numCols) {
            OutputFormat.V8_TRANSPOSED
        } else {
            OutputFormat.V5_PER_ROW
        }
    }

    /**
     * YOLOv5多锚框拼接格式解码
     * 通道布局: [anchor0_cx, anchor0_cy, anchor0_w, anchor0_h, anchor0_obj, anchor0_cls0, anchor0_cls1,
     *           anchor1_cx, anchor1_cy, anchor1_w, anchor1_h, anchor1_obj, anchor1_cls0, anchor1_cls1, ...]
     * 每个锚框组占v5Attrs个通道，共numAnchors个锚框组
     *
     * 坐标解码使用YOLOv5公式:
     *   cx = (sigmoid(raw_cx) * 2 - 0.5 + grid_x) * stride
     *   cy = (sigmoid(raw_cy) * 2 - 0.5 + grid_y) * stride
     *   w  = (sigmoid(raw_w) * 2)^2 * anchor_w
     *   h  = (sigmoid(raw_h) * 2)^2 * anchor_h
     *
     * @param blobHeight 输出blob的高度（网格行数），null时从numCols推断
     * @param blobWidth 输出blob的宽度（网格列数），null时从numCols推断
     */
    private fun decodeV5MultiAnchor(
        output: Array<FloatArray>,
        config: ModelConfig,
        confThresh: Float,
        numRows: Int,
        numCols: Int,
        blobHeight: Int?,
        blobWidth: Int?,
        overrideStride: Float = 0f,
        effectiveInputSize: Int = 0
    ): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val v5Attrs = 5 + config.numClasses
        val numAnchors = numRows / v5Attrs
        val numSpatial = numCols

        // 确定网格尺寸（h × w = numSpatial）
        val gridH = blobHeight ?: sqrt(numSpatial.toFloat()).toInt()
        val gridW = blobWidth ?: gridH

        // 计算stride：优先使用标准stride，回退到config.inputSize/gridH
        val stride = if (overrideStride > 0f) overrideStride
                     else (config.inputHeight.toFloat() / gridH + config.inputWidth.toFloat() / gridW) / 2f

        // 推断模型实际训练时的输入尺寸，用于锚框缩放和坐标映射
        val modelInputSize = if (effectiveInputSize > 0) effectiveInputSize
                             else config.inputWidth

        // 获取该stride对应的锚框尺寸（使用模型实际输入尺寸缩放）
        val anchors = getAnchorsForStride(stride, modelInputSize)

        // 坐标空间缩放因子：从模型实际输入空间映射到config输入空间
        // 当config.inputWidth != modelInputSize时需要缩放
        val coordScale = if (modelInputSize != config.inputWidth && modelInputSize > 0)
                             config.inputWidth.toFloat() / modelInputSize
                         else 1.0f

        // 扫描objectness通道的统计信息，判断objectness是否有效
        // 需要区分三种情况：
        //   1. objectness未训练/未使用：原始值聚集在很窄的范围内（只是偏置+噪声）
        //      例如：所有值都在-14~-12之间，range<3，说明通道对输入无响应
        //   2. objectness正常但无目标：原始值都很负，但不同位置有较大差异
        //      例如：值在-15~-5之间，range>5，模型对不同区域给出了不同的响应
        //   3. objectness正常且有目标：部分原始值为正，sigmoid后>0.5
        var maxRawObj = Float.NEGATIVE_INFINITY
        var minRawObj = Float.POSITIVE_INFINITY
        var sumRawObj = 0.0
        var objCount = 0
        for (a in 0 until numAnchors) {
            val objCh = a * v5Attrs + 4
            if (objCh >= numRows) continue
            for (i in 0 until minOf(numSpatial, output[objCh].size)) {
                val rawVal = output[objCh][i]
                if (rawVal > maxRawObj) maxRawObj = rawVal
                if (rawVal < minRawObj) minRawObj = rawVal
                sumRawObj += rawVal
                objCount++
            }
        }
        val maxObjSigmoid = sigmoid(maxRawObj)
        val avgRawObj = if (objCount > 0) sumRawObj / objCount else 0.0
        val objRange = maxRawObj - minRawObj

        // 跳过objectness的条件（满足全部条件才跳过）：
        // 条件1：maxObjSigmoid < 0.01 — 没有高置信度的objectness信号
        // 条件2（满足任一）：
        //   a. maxRawObj > -2.0 — 原始值接近0，通道从未被激活
        //   b. objRange < 5.0 — 原始值范围很窄，通道对输入无响应（只是偏置+噪声）
        // 不跳过的情况：maxRawObj很负且range很宽 → objectness正常工作，只是没有目标
        val skipObjectness = maxObjSigmoid < 0.01f && (maxRawObj > -2.0f || objRange < 5.0f)
        lastSkipObjectness = skipObjectness

        android.util.Log.i(TAG, "V5_MULTI_ANCHOR: numAnchors=$numAnchors, gridH=$gridH, gridW=$gridW, " +
                "stride=$stride, coordScale=$coordScale, modelInputSize=$modelInputSize, " +
                "numSpatial=$numSpatial, anchors=${anchors.toList()}, " +
                "maxRawObj=$maxRawObj, minRawObj=$minRawObj, avgRawObj=${"%.2f".format(avgRawObj)}, " +
                "objRange=${"%.2f".format(objRange)}, maxObjSigmoid=$maxObjSigmoid, skipObjectness=$skipObjectness")

        for (i in 0 until numSpatial) {
            val gridX = i % gridW
            val gridY = i / gridW

            for (a in 0 until numAnchors) {
                val base = a * v5Attrs

                // 读取类别概率并应用sigmoid
                var maxClassConf = 0f
                var maxClassId = 0
                for (c in 0 until config.numClasses) {
                    val clsCh = base + 5 + c
                    if (clsCh >= numRows || i >= output[clsCh].size) continue
                    val rawCls = output[clsCh][i]
                    val clsConf = sigmoid(rawCls)
                    if (clsConf > maxClassConf) {
                        maxClassConf = clsConf
                        maxClassId = c
                    }
                }

                // 计算最终置信度
                val finalConf: Float
                if (skipObjectness) {
                    // 无objectness模式：直接使用类别置信度
                    finalConf = maxClassConf
                } else {
                    // 标准V5模式：objectness × 类别置信度
                    val rawObj = output[base + 4][i]
                    val objConf = sigmoid(rawObj)
                    // 先用objectness预过滤，减少不必要的计算
                    if (objConf < confThresh * 0.1f) continue
                    finalConf = objConf * maxClassConf
                }

                // 当skipObjectness时使用宽松预过滤，全局自适应阈值在decode()中统一执行
        val preThresh = if (skipObjectness) 0.5f else confThresh
        if (finalConf < preThresh) continue

                // 解码坐标（YOLOv5公式）
                val rawCx = output[base + 0][i]
                val rawCy = output[base + 1][i]
                val rawW = output[base + 2][i]
                val rawH = output[base + 3][i]

                val cx = (sigmoid(rawCx) * 2f - 0.5f + gridX) * stride * coordScale
                val cy = (sigmoid(rawCy) * 2f - 0.5f + gridY) * stride * coordScale

                // 获取该锚框的宽高（anchors数组: [aw0, ah0, aw1, ah1, aw2, ah2]）
                val anchorW = if (a * 2 < anchors.size) anchors[a * 2] else stride
                val anchorH = if (a * 2 + 1 < anchors.size) anchors[a * 2 + 1] else stride
                val w = (sigmoid(rawW) * 2f).pow(2) * anchorW * coordScale
                val h = (sigmoid(rawH) * 2f).pow(2) * anchorH * coordScale

                detections.add(RawDetection(
                    cx = cx,
                    cy = cy,
                    w = w,
                    h = h,
                    classId = maxClassId,
                    confidence = finalConf
                ))
            }
        }

        android.util.Log.i(TAG, "V5_MULTI_ANCHOR decoded: ${detections.size} raw detections (before NMS), skipObj=$skipObjectness")
        return detections
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
        /** YOLOv5多锚框拼接: [numAnchors * v5Attrs, numDetections] */
        V5_MULTI_ANCHOR,
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

    override fun decode(
        output: Array<FloatArray>,
        config: ModelConfig,
        confidenceThreshold: Float,
        blobShapes: List<OutputBlobInfo>
    ): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold, blobShapes)
    }
}

/**
 * YOLOv7输出解码器（格式与v5相同）
 */
class YoloV7Decoder : YoloDecoder {
    private val unifiedDecoder = UnifiedYoloDecoder()

    override fun decode(
        output: Array<FloatArray>,
        config: ModelConfig,
        confidenceThreshold: Float,
        blobShapes: List<OutputBlobInfo>
    ): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold, blobShapes)
    }
}

/**
 * YOLOv8输出解码器（保留兼容，内部使用统一解码器）
 */
class YoloV8Decoder : YoloDecoder {
    private val unifiedDecoder = UnifiedYoloDecoder()

    override fun decode(
        output: Array<FloatArray>,
        config: ModelConfig,
        confidenceThreshold: Float,
        blobShapes: List<OutputBlobInfo>
    ): List<RawDetection> {
        return unifiedDecoder.decode(output, config, confidenceThreshold, blobShapes)
    }
}
