package com.aloyo.common

/**
 * 模型管理接口
 * 定义模型加载、切换、配置等操作
 */
interface IModelManager {

    // 获取当前加载的模型名称
    val currentModelName: String?

    // 获取可用的模型列表
    val availableModels: List<String>

    /**
     * 加载模型
     * @param modelName 模型名称
     * @return 是否加载成功
     */
    fun loadModel(modelName: String): Boolean

    /**
     * 卸载当前模型
     */
    fun unloadModel()

    /**
     * 获取模型配置
     * @param modelName 模型名称
     * @return 模型配置，不存在返回null
     */
    fun getModelConfig(modelName: String): ModelConfig?

    /**
     * 从外部文件导入模型
     * @param paramPath 参数文件路径
     * @param binPath 权重文件路径
     * @param configPath 配置文件路径
     * @param modelName 模型名称
     * @return 是否导入成功
     */
    fun importModel(paramPath: String, binPath: String, configPath: String, modelName: String): Boolean

    /**
     * 删除模型
     * @param modelName 模型名称
     * @return 是否删除成功
     */
    fun deleteModel(modelName: String): Boolean
}
