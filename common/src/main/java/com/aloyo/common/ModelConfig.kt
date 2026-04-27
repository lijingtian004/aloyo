package com.aloyo.common

/**
 * 模型配置数据类
 * 包含模型版本、输入尺寸、类别数、阈值等参数
 *
 * 注意：Gson反序列化时不使用Kotlin默认参数值，而是使用Java零值默认值
 * （如Float→0.0, Int→0, String→null）。因此对于有默认值的字段，
 * 需要在fromJson后通过validate()方法修正无效值。
 */
data class ModelConfig(
    val version: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val numClasses: Int,
    val confidenceThreshold: Float = 0.5f,
    val nmsThreshold: Float = 0.4f,
    val labels: List<String> = emptyList(),
    /**
     * 检测框缩放因子
     * 用于补偿锚框不匹配或模型回归偏差导致的检测框偏小问题
     * 1.0 = 不缩放，1.2 = 每边扩展20%（框面积增加约44%）
     * 推荐值：1.0~1.5，默认1.15（轻微扩展以完全框住目标）
     *
     * 重要：Gson反序列化时如果JSON中缺少此字段，会设为0.0而非1.15f，
     * 导致所有检测框被压缩为零尺寸点。validate()方法会修正此问题。
     */
    val boxScale: Float = 1.15f,
    /**
     * 是否使用YOLOv8风格的坐标解码
     * YOLOv5解码公式: sigmoid(raw) * 2 - 0.5（有-0.5到1.5的网格偏移）
     * YOLOv8解码公式: sigmoid(raw)（无额外偏移，中心点直接在网格内）
     *
     * 如果检测框系统性偏右偏下，说明模型训练时使用的是v8风格解码，
     * 应将此选项设为true。
     *
     * 默认false（兼容旧模型），Gson反序列化缺省时为false。
     */
    val useV8StyleDecode: Boolean = false
) {
    /**
     * 验证并修正Gson反序列化后的无效值
     * Gson不使用Kotlin默认参数值，而是使用Java零值默认值
     * @return 修正后的ModelConfig
     */
    fun validate(): ModelConfig {
        return copy(
            // boxScale=0.0是Gson反序列化的默认值（JSON中缺少此字段时），
            // 会导致所有检测框被压缩为零尺寸点
            // 修正为1.15（Kotlin默认值），使框稍微放大以完全框住目标
            boxScale = if (boxScale <= 0f) 1.15f else boxScale,
            // 其他可能有类似问题的字段
            confidenceThreshold = if (confidenceThreshold <= 0f) 0.5f else confidenceThreshold,
            nmsThreshold = if (nmsThreshold <= 0f) 0.4f else nmsThreshold
        )
    }
}
