package com.aloyo.model

import android.content.Context
import android.content.res.AssetManager
import com.aloyo.common.IModelManager
import com.aloyo.common.ModelConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 模型管理器实现
 * 负责模型的加载、切换、导入和删除
 * 支持内置模型（assets）和外部模型（用户导入）
 */
class ModelManager(private val context: Context) : IModelManager {

    companion object {
        private const val TAG = "ModelManager"
        // 内置模型目录（assets中）
        private const val ASSETS_MODEL_DIR = "models"
        // 外部模型存储目录
        private const val EXTERNAL_MODEL_DIR = "imported_models"
        // 模型配置文件名
        private const val CONFIG_FILE_NAME = "config.json"
        // 模型参数文件名
        private const val PARAM_FILE_NAME = "model.param"
        // 模型权重文件名
        private const val BIN_FILE_NAME = "model.bin"
    }

    // JSON解析器
    private val gson = Gson()

    // 当前加载的模型名称
    @Volatile
    override var currentModelName: String? = null
        private set

    // 可用模型列表（内置 + 外部）
    @Volatile
    override var availableModels: List<String> = emptyList()
        private set

    // 模型配置缓存
    private val modelConfigCache = mutableMapOf<String, ModelConfig>()

    // 模型文件路径缓存
    private val modelPathCache = mutableMapOf<String, ModelPaths>()

    init {
        refreshModelList()
    }

    /**
     * 刷新可用模型列表
     * 扫描内置模型和外部模型目录
     */
    fun refreshModelList() {
        val models = mutableListOf<String>()

        // 扫描内置模型
        try {
            val assetModels = context.assets.list(ASSETS_MODEL_DIR) ?: emptyArray()
            for (modelDir in assetModels) {
                // 检查是否包含必要的模型文件
                val configPath = "$ASSETS_MODEL_DIR/$modelDir/$CONFIG_FILE_NAME"
                val paramPath = "$ASSETS_MODEL_DIR/$modelDir/$PARAM_FILE_NAME"
                val binPath = "$ASSETS_MODEL_DIR/$modelDir/$BIN_FILE_NAME"

                try {
                    context.assets.open(configPath).close()
                    context.assets.open(paramPath).close()
                    context.assets.open(binPath).close()
                    models.add(modelDir)
                } catch (e: IOException) {
                    android.util.Log.w(TAG, "Incomplete built-in model: $modelDir", e)
                }
            }
        } catch (e: IOException) {
            android.util.Log.e(TAG, "Error scanning built-in models", e)
        }

        // 扫描外部模型
        val externalDir = File(context.filesDir, EXTERNAL_MODEL_DIR)
        if (externalDir.exists()) {
            externalDir.listFiles()?.filter { it.isDirectory }?.forEach { modelDir ->
                val configFile = File(modelDir, CONFIG_FILE_NAME)
                val paramFile = File(modelDir, PARAM_FILE_NAME)
                val binFile = File(modelDir, BIN_FILE_NAME)

                if (configFile.exists() && paramFile.exists() && binFile.exists()) {
                    models.add(modelDir.name)
                }
            }
        }

        availableModels = models
        android.util.Log.i(TAG, "Available models: $models")
    }

    /**
     * 加载模型
     * 将模型文件复制到应用私有目录（如果是内置模型）
     * @param modelName 模型名称
     * @return 是否加载成功
     */
    override fun loadModel(modelName: String): Boolean {
        if (!availableModels.contains(modelName)) {
            android.util.Log.e(TAG, "Model not found: $modelName")
            return false
        }

        try {
            val paths = getModelPaths(modelName)
            if (paths == null) {
                android.util.Log.e(TAG, "Failed to get model paths for: $modelName")
                return false
            }

            // 加载模型配置
            val config = getModelConfig(modelName)
            if (config == null) {
                android.util.Log.e(TAG, "Failed to load model config for: $modelName")
                return false
            }

            currentModelName = modelName
            modelPathCache[modelName] = paths
            modelConfigCache[modelName] = config

            android.util.Log.i(TAG, "Model loaded: $modelName")
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading model: $modelName", e)
            return false
        }
    }

    override fun unloadModel() {
        currentModelName = null
        android.util.Log.i(TAG, "Model unloaded")
    }

    /**
     * 获取模型配置
     */
    override fun getModelConfig(modelName: String): ModelConfig? {
        // 先从缓存获取
        modelConfigCache[modelName]?.let { return it }

        try {
            val paths = getModelPaths(modelName) ?: return null
            val configJson = readFileContent(paths.configPath, paths.isAsset)
            if (configJson == null) {
                android.util.Log.e(TAG, "Failed to read config for: $modelName")
                return null
            }

            val config = parseModelConfig(configJson)
            if (config != null) {
                modelConfigCache[modelName] = config
            }
            return config
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting model config: $modelName", e)
            return null
        }
    }

    /**
     * 从外部导入模型
     */
    override fun importModel(paramPath: String, binPath: String, configPath: String, modelName: String): Boolean {
        try {
            val externalDir = File(context.filesDir, EXTERNAL_MODEL_DIR)
            val modelDir = File(externalDir, modelName)
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // 复制模型文件到应用私有目录
            copyFile(File(paramPath), File(modelDir, PARAM_FILE_NAME))
            copyFile(File(binPath), File(modelDir, BIN_FILE_NAME))
            copyFile(File(configPath), File(modelDir, CONFIG_FILE_NAME))

            // 刷新模型列表
            refreshModelList()

            android.util.Log.i(TAG, "Model imported: $modelName")
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error importing model: $modelName", e)
            return false
        }
    }

    /**
     * 删除模型
     * 内置模型不可删除
     */
    override fun deleteModel(modelName: String): Boolean {
        // 内置模型不可删除
        if (isBuiltInModel(modelName)) {
            android.util.Log.w(TAG, "Cannot delete built-in model: $modelName")
            return false
        }

        try {
            val externalDir = File(context.filesDir, EXTERNAL_MODEL_DIR)
            val modelDir = File(externalDir, modelName)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }

            modelConfigCache.remove(modelName)
            modelPathCache.remove(modelName)

            if (currentModelName == modelName) {
                currentModelName = null
            }

            refreshModelList()

            android.util.Log.i(TAG, "Model deleted: $modelName")
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error deleting model: $modelName", e)
            return false
        }
    }

    /**
     * 获取当前模型的文件路径
     * @return ModelPaths或null
     */
    fun getCurrentModelPaths(): ModelPaths? {
        val name = currentModelName ?: return null
        return getModelPaths(name)
    }

    /**
     * 获取模型文件路径
     */
    private fun getModelPaths(modelName: String): ModelPaths? {
        // 先从缓存获取
        modelPathCache[modelName]?.let { return it }

        // 检查是否为内置模型
        if (isBuiltInModel(modelName)) {
            // 内置模型需要先复制到私有目录（NCNN需要文件路径）
            val copiedPaths = copyAssetModelToFiles(modelName)
            if (copiedPaths != null) {
                modelPathCache[modelName] = copiedPaths
                return copiedPaths
            }
        }

        // 检查外部模型
        val externalDir = File(context.filesDir, EXTERNAL_MODEL_DIR)
        val modelDir = File(externalDir, modelName)
        if (modelDir.exists()) {
            val paths = ModelPaths(
                paramPath = File(modelDir, PARAM_FILE_NAME).absolutePath,
                binPath = File(modelDir, BIN_FILE_NAME).absolutePath,
                configPath = File(modelDir, CONFIG_FILE_NAME).absolutePath,
                isAsset = false
            )
            modelPathCache[modelName] = paths
            return paths
        }

        return null
    }

    /**
     * 判断是否为内置模型
     */
    private fun isBuiltInModel(modelName: String): Boolean {
        return try {
            context.assets.list(ASSETS_MODEL_DIR)?.contains(modelName) == true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 将内置模型从assets复制到应用私有目录
     * NCNN需要文件路径，无法直接从assets读取
     */
    private fun copyAssetModelToFiles(modelName: String): ModelPaths? {
        try {
            val modelDir = File(context.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val assetBase = "$ASSETS_MODEL_DIR/$modelName"

            // 复制param文件
            copyAssetFile("$assetBase/$PARAM_FILE_NAME", File(modelDir, PARAM_FILE_NAME))
            // 复制bin文件
            copyAssetFile("$assetBase/$BIN_FILE_NAME", File(modelDir, BIN_FILE_NAME))
            // 复制config文件
            copyAssetFile("$assetBase/$CONFIG_FILE_NAME", File(modelDir, CONFIG_FILE_NAME))

            return ModelPaths(
                paramPath = File(modelDir, PARAM_FILE_NAME).absolutePath,
                binPath = File(modelDir, BIN_FILE_NAME).absolutePath,
                configPath = File(modelDir, CONFIG_FILE_NAME).absolutePath,
                isAsset = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error copying asset model: $modelName", e)
            return null
        }
    }

    /**
     * 从assets复制单个文件
     */
    private fun copyAssetFile(assetPath: String, destFile: File) {
        // 如果目标文件已存在且大小相同，跳过复制
        if (destFile.exists()) {
            val assetSize = context.assets.openFd(assetPath).length
            if (destFile.length() == assetSize) return
        }

        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 读取文件内容
     */
    private fun readFileContent(path: String, isAsset: Boolean): String? {
        return try {
            if (isAsset) {
                context.assets.open(path).bufferedReader().use { it.readText() }
            } else {
                File(path).readText()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error reading file: $path", e)
            null
        }
    }

    /**
     * 解析模型配置JSON
     */
    private fun parseModelConfig(json: String): ModelConfig? {
        return try {
            // Gson反序列化后调用validate()修正无效默认值
            // Gson不使用Kotlin默认参数值，而是使用Java零值（如Float→0.0）
            // 这会导致boxScale=0.0，将所有检测框压缩为零尺寸点
            gson.fromJson(json, ModelConfig::class.java)?.validate()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing model config", e)
            null
        }
    }

    /**
     * 复制文件
     */
    private fun copyFile(src: File, dest: File) {
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

/**
 * 模型文件路径数据类
 */
data class ModelPaths(
    val paramPath: String,
    val binPath: String,
    val configPath: String,
    val isAsset: Boolean
)
