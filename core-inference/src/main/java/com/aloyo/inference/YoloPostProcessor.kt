package com.aloyo.inference

import com.aloyo.common.Detection
import com.aloyo.common.ModelConfig

/**
 * YOLO后处理器
 * 负责将模型原始输出解码为检测结果，并执行NMS（非极大值抑制）
 * 支持不同版本YOLO模型的解码器
 */
class YoloPostProcessor(
    private val decoder: YoloDecoder,
    private val config: ModelConfig,
    private var confidenceThreshold: Float,
    private var nmsThreshold: Float
) {
    companion object {
        private const val TAG = "YoloPostProcessor"
        // 时序过滤：检测框需要在连续多帧中出现才被保留
        // 这可以有效过滤单帧假阳（如背景纹理被误检）
        private const val TEMPORAL_FRAMES = 2
        private const val TEMPORAL_IOU_THRESHOLD = 0.5f
    }

    // 后处理诊断信息（供NcnnInferenceEngine读取并写入应用日志）
    @Volatile
    var lastDiagInfo: String = ""
        private set

    // 时序过滤状态：记录最近几帧的检测结果
    private val detectionHistory = ArrayDeque<List<Detection>>()
    private var frameCounter = 0

    /**
     * 处理模型输出
     * 1. 使用对应版本的解码器解码原始输出
     * 2. 将坐标从模型空间映射回原图空间
     * 3. 过滤退化检测框（零宽/零高/极小的框）
     * 4. 执行NMS去除重叠检测
     * @param output 模型原始输出
     * @param srcWidth 原图宽度
     * @param srcHeight 原图高度
     * @param blobShapes 每个输出blob的形状信息列表，为空时自动推断
     * @param actualTargetWidth 实际预处理目标宽度，0时使用config.inputWidth
     *                          当截屏区域小于config.inputSize时，实际目标宽度可能小于config.inputWidth
     * @param actualTargetHeight 实际预处理目标高度，0时使用config.inputHeight
     * @return 最终的检测结果列表
     */
    fun process(output: Array<FloatArray>, srcWidth: Int, srcHeight: Int, blobShapes: List<OutputBlobInfo> = emptyList(), actualTargetWidth: Int = 0, actualTargetHeight: Int = 0): List<Detection> {
        val targetW = if (actualTargetWidth > 0) actualTargetWidth else config.inputWidth
        val targetH = if (actualTargetHeight > 0) actualTargetHeight else config.inputHeight
        val rawDetections = decoder.decode(output, config, confidenceThreshold, blobShapes, targetW)
        return processRawDetections(rawDetections, srcWidth, srcHeight, actualTargetWidth, actualTargetHeight)
    }

    /**
     * 处理已解码的原始检测结果（避免重复解码）
     */
    fun processRawDetections(rawDetections: List<RawDetection>, srcWidth: Int, srcHeight: Int, actualTargetWidth: Int = 0, actualTargetHeight: Int = 0): List<Detection> {
        val targetW = if (actualTargetWidth > 0) actualTargetWidth else config.inputWidth
        val targetH = if (actualTargetHeight > 0) actualTargetHeight else config.inputHeight

        if (rawDetections.isEmpty()) return emptyList()

        // 计算坐标映射参数（使用实际预处理目标尺寸）
        val preProcessor = YoloPreProcessor(targetW, targetH)
        val scaleFactors = preProcessor.getScaleFactors(srcWidth, srcHeight)

        // 将坐标从模型空间映射回原图空间
        val mappedDetections = rawDetections.map { raw ->
            // 从中心坐标+宽高转换为左上角+右下角坐标
            var x1 = (raw.cx - raw.w / 2f - scaleFactors.padX) / scaleFactors.scale
            var y1 = (raw.cy - raw.h / 2f - scaleFactors.padY) / scaleFactors.scale
            var x2 = (raw.cx + raw.w / 2f - scaleFactors.padX) / scaleFactors.scale
            var y2 = (raw.cy + raw.h / 2f - scaleFactors.padY) / scaleFactors.scale

            // 应用检测框缩放因子（boxScale）
            // 以框中心为基准向外扩展，补偿锚框不匹配或模型回归偏差
            // boxScale必须>0才有效：0.0会将框压缩为零尺寸点（Gson反序列化时默认值可能是0.0）
            if (config.boxScale > 0f && config.boxScale != 1.0f) {
                val cx = (x1 + x2) / 2f
                val cy = (y1 + y2) / 2f
                val halfW = (x2 - x1) / 2f * config.boxScale
                val halfH = (y2 - y1) / 2f * config.boxScale
                x1 = cx - halfW
                y1 = cy - halfH
                x2 = cx + halfW
                y2 = cy + halfH
            }

            // 裁剪坐标到图像范围内
            val clampedX1 = x1.coerceIn(0f, srcWidth.toFloat())
            val clampedY1 = y1.coerceIn(0f, srcHeight.toFloat())
            val clampedX2 = x2.coerceIn(0f, srcWidth.toFloat())
            val clampedY2 = y2.coerceIn(0f, srcHeight.toFloat())

            // 获取标签名称
            val label = if (raw.classId < config.labels.size) config.labels[raw.classId] else "class_${raw.classId}"

            Detection(
                x1 = clampedX1,
                y1 = clampedY1,
                x2 = clampedX2,
                y2 = clampedY2,
                label = label,
                confidence = raw.confidence,
                classId = raw.classId
            )
        }

        // 过滤退化检测框：宽或高极小的框通常是解码噪声产生的假阳性
        // 使用相对于源图像尺寸的百分比阈值，适应不同分辨率的输入
        // 1%阈值：对于256×256的小尺寸输入，锚框缩放后可能很小(4-13px)
        // 之前5%阈值(12.8px)和2%阈值(5.12px)都导致零检测，进一步降低到1%
        val minDim = minOf(srcWidth, srcHeight).toFloat()
        val minBoxSize = maxOf(2.0f, minDim * 0.01f)

        // 宽高比约束：排除异常形状的检测框（如极瘦长或极扁平的框）
        // 正常人体/目标宽高比通常在0.2~5.0之间
        // 超出此范围的框通常是解码噪声或假阳性
        val minAspectRatio = 0.15f
        val maxAspectRatio = 6.5f

        val validDetections = mappedDetections.filter { det ->
            val w = det.x2 - det.x1
            val h = det.y2 - det.y1
            val aspectRatio = if (h > 0f) w / h else 0f
            val passSize = w >= minBoxSize && h >= minBoxSize
            val passAspect = aspectRatio in minAspectRatio..maxAspectRatio
            passSize && passAspect
        }

        // 构建后处理诊断信息（每次推理都记录，方便排查零检测问题）
        val filteredBySize = mappedDetections.size - validDetections.size
        val diagBuilder = StringBuilder()
        diagBuilder.append("PostProc: srcSize=${srcWidth}x${srcHeight}, targetSize=${targetW}x${targetH}, " +
                "scale=${scaleFactors.scale}, pad=(${scaleFactors.padX},${scaleFactors.padY}), " +
                "minBoxSize=$minBoxSize, boxScale=${config.boxScale}, " +
                "aspectRange=${minAspectRatio}~${maxAspectRatio}, useV8Style=${config.useV8StyleDecode}, " +
                "rawCount=${rawDetections.size}, mappedCount=${mappedDetections.size}, " +
                "validCount=${validDetections.size}, filteredBySize=$filteredBySize")
        // 记录前5个原始检测的坐标和映射后坐标
        rawDetections.take(5).forEachIndexed { idx, raw ->
            val mx1 = (raw.cx - raw.w / 2f - scaleFactors.padX) / scaleFactors.scale
            val my1 = (raw.cy - raw.h / 2f - scaleFactors.padY) / scaleFactors.scale
            val mx2 = (raw.cx + raw.w / 2f - scaleFactors.padX) / scaleFactors.scale
            val my2 = (raw.cy + raw.h / 2f - scaleFactors.padY) / scaleFactors.scale
            // 应用boxScale后的坐标
            var bx1 = mx1
            var by1 = my1
            var bx2 = mx2
            var by2 = my2
            if (config.boxScale != 1.0f) {
                val bcx = (mx1 + mx2) / 2f
                val bcy = (my1 + my2) / 2f
                val bhalfW = (mx2 - mx1) / 2f * config.boxScale
                val bhalfH = (my2 - my1) / 2f * config.boxScale
                bx1 = bcx - bhalfW
                by1 = bcy - bhalfH
                bx2 = bcx + bhalfW
                by2 = bcy + bhalfH
            }
            // 裁剪后的坐标
            val cx1 = bx1.coerceIn(0f, srcWidth.toFloat())
            val cy1 = by1.coerceIn(0f, srcHeight.toFloat())
            val cx2 = bx2.coerceIn(0f, srcWidth.toFloat())
            val cy2 = by2.coerceIn(0f, srcHeight.toFloat())
            val bw = cx2 - cx1
            val bh = cy2 - cy1
            val passSize = bw >= minBoxSize && bh >= minBoxSize
            diagBuilder.append("\n  raw[$idx]: cx=${"%.1f".format(raw.cx)}, cy=${"%.1f".format(raw.cy)}, " +
                    "w=${"%.1f".format(raw.w)}, h=${"%.1f".format(raw.h)}, " +
                    "mapped=(${ "%.1f".format(mx1)},${"%.1f".format(my1)})-(${ "%.1f".format(mx2)},${"%.1f".format(my2)}), " +
                    "clamped=(${ "%.1f".format(cx1)},${"%.1f".format(cy1)})-(${ "%.1f".format(cx2)},${"%.1f".format(cy2)}), " +
                    "boxSize=${"%.1f".format(bw)}x${"%.1f".format(bh)}, passSize=$passSize, conf=${"%.4f".format(raw.confidence)}")
        }
        lastDiagInfo = diagBuilder.toString()

        // 高置信度聚类过滤：当多个检测的置信度过于接近时，可能是假阳
        // 假阳特征：多个检测的置信度差异极小（<0.001），且位置分散
        // 真实目标：置信度有明显分层，或只有一个高置信度检测
        val clustered = applyConfidenceClustering(validDetections)

        // 按类别执行NMS
        val afterNMS = applyNMS(clustered)

        // 跨类别去重：同一目标可能被检测为多个类别（如Head和Body）
        // 当不同类别的检测框高度重叠时，只保留置信度最高的那个
        // 这避免了同一角色同时显示"Head 93%"和"Body 83%"两个标签
        val deduplicated = applyCrossClassDedup(afterNMS)

        // 时序一致性过滤：检测框需要在连续多帧中出现才被保留
        // 这可以有效过滤单帧假阳（如背景纹理、光影变化被误检）
        val temporallyFiltered = applyTemporalFiltering(deduplicated)

        return temporallyFiltered
    }

    /**
     * 置信度聚类过滤
     * 当多个检测的置信度过于接近（差异 < 阈值）时，只保留最可靠的一个
     *
     * 假阳特征分析：
     * - 假阳通常来自同一锚点/网格的多个重叠预测
     * - 这些预测的置信度差异极小（如0.9983 vs 0.9984）
     * - 真实目标的置信度通常有明显分层（如0.95 vs 0.85）
     *
     * 策略：
     * 1. 按置信度降序排序
     * 2. 如果相邻检测的置信度差异 < 阈值，认为是同一簇
     * 3. 每个簇只保留置信度最高的一个检测
     */
    private fun applyConfidenceClustering(detections: List<Detection>): List<Detection> {
        if (detections.size <= 1) return detections

        // 置信度聚类阈值：差异小于此值认为是同一簇
        // 0.001 = 0.1% 置信度差异
        val clusterThreshold = 0.001f

        val sorted = detections.sortedByDescending { it.confidence }
        val clusters = mutableListOf<MutableList<Detection>>()
        var currentCluster = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val prevConf = sorted[i - 1].confidence
            val currConf = sorted[i].confidence
            if (prevConf - currConf < clusterThreshold) {
                // 同一簇
                currentCluster.add(sorted[i])
            } else {
                // 新簇
                clusters.add(currentCluster)
                currentCluster = mutableListOf(sorted[i])
            }
        }
        clusters.add(currentCluster)

        // 每个簇只保留一个检测：优先保留面积最大的（通常更可靠）
        val result = clusters.map { cluster ->
            cluster.maxByOrNull { det ->
                (det.x2 - det.x1) * (det.y2 - det.y1)
            } ?: cluster[0]
        }

        if (result.size < detections.size) {
            android.util.Log.i(TAG, "Confidence clustering: before=${detections.size}, after=${result.size}, clusters=${clusters.size}")
        }

        return result
    }

    /**
     * 时序一致性过滤
     * 检测框需要在连续多帧中稳定出现才被保留
     * 原理：真实目标在连续帧中位置相对稳定，假阳通常只出现1帧
     *
     * @param detections 当前帧的检测结果
     * @return 经过时序过滤后的检测结果
     */
    private fun applyTemporalFiltering(detections: List<Detection>): List<Detection> {
        frameCounter++

        // 将当前帧结果加入历史
        detectionHistory.addLast(detections)
        if (detectionHistory.size > TEMPORAL_FRAMES) {
            detectionHistory.removeFirst()
        }

        // 历史帧不足时，直接返回当前结果（但限制数量避免首帧假阳过多）
        if (detectionHistory.size < TEMPORAL_FRAMES) {
            return detections.take(5)
        }

        // 只保留在当前帧和至少一帧历史帧中都出现的检测
        // 使用IoU匹配：如果当前框与历史框IoU>阈值，认为是同一目标
        val stableDetections = mutableListOf<Detection>()
        for (det in detections) {
            var foundInHistory = false
            for (historyFrame in detectionHistory.dropLast(1)) {
                for (histDet in historyFrame) {
                    // 同一类别且IoU足够大
                    if (det.classId == histDet.classId && computeIoU(det, histDet) > TEMPORAL_IOU_THRESHOLD) {
                        foundInHistory = true
                        break
                    }
                }
                if (foundInHistory) break
            }
            if (foundInHistory) {
                stableDetections.add(det)
            }
        }

        // 如果时序过滤后没有检测结果，回退到当前帧结果（避免闪烁）
        // 但只保留置信度最高的3个，减少假阳
        return if (stableDetections.isNotEmpty()) {
            stableDetections
        } else {
            detections.sortedByDescending { it.confidence }.take(3)
        }
    }

    /**
     * 跨类别检测去重
     * 同一目标可能被模型输出为多个类别（如Head和Body），
     * 这些检测框位置相近但类别不同。标准NMS只在同类别内去重，
     * 无法处理跨类别的重复检测。
     *
     * 策略：对所有检测框按置信度降序排序，
     * 如果一个低置信度检测与已保留的高置信度检测高度重叠（IoU > 阈值），则丢弃。
     */
    private fun applyCrossClassDedup(detections: List<Detection>): List<Detection> {
        if (detections.size <= 1) return detections

        // 跨类别去重阈值：降低为0.3，更积极地合并Head和Body
        // Head框通常是Body框的上半部分，IoU大约在0.3~0.6之间
        // 之前0.5的阈值太高，导致Head和Body无法被去重
        val crossClassThreshold = 0.3f

        // 按置信度降序排序
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<Detection>()

        for (det in sorted) {
            var shouldKeep = true
            for (keptDet in kept) {
                // 不同类别才需要跨类别去重（同类的已经在NMS中处理过）
                if (det.classId != keptDet.classId && computeIoU(det, keptDet) > crossClassThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                kept.add(det)
            }
        }

        return kept
    }

    /**
     * 执行非极大值抑制（NMS）
     * 对每个类别分别执行NMS，去除重叠的检测框
     */
    private fun applyNMS(detections: List<Detection>): List<Detection> {
        // 按类别分组
        val groupedByClass = detections.groupBy { it.classId }
        val result = mutableListOf<Detection>()

        for ((_, classDetections) in groupedByClass) {
            // 按置信度降序排序
            val sorted = classDetections.sortedByDescending { it.confidence }
            val kept = mutableListOf<Detection>()

            for (det in sorted) {
                var shouldKeep = true
                for (keptDet in kept) {
                    if (computeIoU(det, keptDet) > nmsThreshold) {
                        shouldKeep = false
                        break
                    }
                }
                if (shouldKeep) {
                    kept.add(det)
                }
            }
            result.addAll(kept)
        }

        return result
    }

    /**
     * 计算两个检测框的IoU（交并比）
     */
    private fun computeIoU(a: Detection, b: Detection): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)

        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        if (interArea == 0f) return 0f

        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * 更新置信度阈值
     */
    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold
    }

    /**
     * 更新NMS阈值
     */
    fun setNmsThreshold(threshold: Float) {
        nmsThreshold = threshold
    }
}
