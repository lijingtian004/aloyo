# ALOYO 项目开发经验总结

## 一、Android 多模块项目架构

### 1.1 模块划分原则
- **common 模块**：只放接口和数据类，不放任何实现逻辑，所有 core-* 模块依赖 common，core-* 之间不互相依赖
- **接口驱动设计**：`IInferenceEngine`、`ICaptureSource`、`IOverlayRenderer`、`ILogger`、`IModelManager` 五大核心接口定义在 common 中，各模块提供实现
- **模块间通信**：通过接口回调（如 `ICaptureSource.FrameCallback`），不要在 core 模块间创建直接依赖

### 1.2 典型踩坑
- **重复接口定义**：`CaptureManager` 中定义了自己的 `FrameCallback` 接口，与 `ICaptureSource.FrameCallback` 类型不匹配导致编译失败。**教训：所有跨模块接口统一定义在 common 中，实现模块不要重复定义**
- **递归调用**：`CaptureManager` 实现 `ICaptureSource` 接口时，`stopCapture()` 方法调用了自身导致无限递归。**教训：实现接口方法时注意方法签名，内部实现用 `private` 方法隔离**

---

## 二、NCNN 集成

### 2.1 NCNN SDK 选择
- NCNN 提供两种预编译包：**静态库（.a）** 和动态库（.so）
- Android Vulkan 版本下载地址：`https://github.com/Tencent/ncnn/releases`
- 最新版（20260113）文件名格式：`ncnn-20260113-android-vulkan.zip`（注意带版本号前缀）
- SDK 目录结构：每个 ABI 一个子目录（`armeabi-v7a/`、`arm64-v8a/`、`x86/`、`x86_64/`），内含 `include/` 和 `lib/`

### 2.2 静态库 vs 动态库
- **静态库方案（推荐）**：将 NCNN 静态链接到自定义 JNI 库中，最终只需加载一个 `libaloyo_inference.so`
  - 优点：部署简单，不依赖外部 .so 文件
  - 缺点：需要额外链接 glslang 等依赖库，APK 体积略大
- **动态库方案**：分别加载 `libncnn.so` 和 `libaloyo_inference.so`
  - 优点：链接简单
  - 缺点：需要 AAR 打包 .so 文件，AGP 8.x 不允许 AAR 依赖 AAR

### 2.3 CMake 链接 NCNN 静态库的关键配置

```cmake
# 必须链接的 glslang 系列库（Vulkan 着色器编译所需）
add_library(glslang STATIC IMPORTED)
add_library(SPIRV STATIC IMPORTED)
add_library(MachineIndependent STATIC IMPORTED)
add_library(OSDependent STATIC IMPORTED)
add_library(GenericCodeGen STATIC IMPORTED)
add_library(glslang-default-resource-limits STATIC IMPORTED)

# 链接顺序很重要：被依赖的库放后面
target_link_libraries(aloyo_inference
    glslang SPIRV MachineIndependent OSDependent
    GenericCodeGen glslang-default-resource-limits
    ncnn                    # ncnn 依赖上面的 glslang 库
    ${log-lib} ${vulkan-lib} ${android-lib} ${z-lib}
    -fopenmp -static-openmp # OpenMP 支持
)
```

### 2.4 NDK 版本必须匹配
- NCNN 20260113 版本使用 **NDK 27** 编译
- 如果使用 NDK 25 编译 JNI 桥接库，会出现 `std::__ndk1` 相关的链接错误
- **解决方案**：在 `build.gradle.kts` 中指定 `ndkVersion = "27.0.12077973"`，CI 中也安装对应版本

### 2.5 STL 选择
- 使用 `c++_shared` 而非 `c++_static`，因为 NCNN Vulkan 版本依赖动态链接的 libc++
- 在 CMake arguments 中设置：`arguments("-DANDROID_STL=c++_shared")`

---

## 三、GitHub Actions CI/CD

### 3.1 NCNN SDK 下载
- **不要用 wget**：GitHub release 下载需要跟随重定向，wget 默认不跟随
- **推荐用 `gh release download`**：GitHub Actions runner 预装了 gh CLI，认证自动配置
```yaml
- name: 下载NCNN SDK
  env:
    GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: |
    gh release download 20260113 --repo Tencent/ncnn \
      --pattern "ncnn-20260113-android-vulkan.zip" --dir /tmp --clobber
```
- **注意文件名**：release 资源名带版本号前缀，如 `ncnn-20260113-android-vulkan.zip`，不是 `ncnn-android-vulkan.zip`

### 3.2 Gradle Wrapper JAR 缺失
- 项目只创建了 `gradle-wrapper.properties` 但没有 `gradle-wrapper.jar`
- GitHub Actions runner 上没有 `gradle` 命令，无法用 `gradle wrapper` 生成
- **解决方案**：在 CI 中下载 Gradle 发行版，用其生成 wrapper
```yaml
- name: 下载Gradle Wrapper JAR
  run: |
    if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
      curl -L "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -o /tmp/gradle.zip
      unzip -q /tmp/gradle.zip -d /tmp/gradle-download
      /tmp/gradle-download/gradle-8.5/bin/gradle wrapper --gradle-version 8.5
    fi
```

### 3.3 CMake 和 NDK 安装
- GitHub Actions 默认的 CMake 版本可能不匹配，需要手动安装
```yaml
- name: 安装CMake和NDK
  run: |
    echo "y" | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \
      "cmake;3.22.1" "ndk;27.0.12077973"
```

### 3.4 AGP 8.x 注意事项
- **BuildConfig 默认不生成**：需要在 `buildFeatures` 中显式启用 `buildConfig = true`
- **AAR 不能依赖 AAR**：`implementation(files("xxx.aar"))` 在库模块中会报错，应改为静态链接或使用 Maven 本地仓库
- **Kotlin DSL 中使用 Properties**：需要 `import java.util.Properties`，不能直接用 `java.util.Properties()`

---

## 四、Android 系统级 API

### 4.1 MediaProjection 截屏
- 必须在**前台服务**中运行，Android 14+ 需要声明 `foregroundServiceType="mediaProjection"`
- 前台服务需要通知渠道（Android 8.0+）
- `ImageReader` 获取的 Image 需要手动处理行填充（rowPadding）
- **截屏帧回调在后台线程**，UI 更新需要切换到主线程

### 4.2 悬浮窗
- 需要 `SYSTEM_ALERT_WINDOW` 权限，Android 6.0+ 需要引导用户到设置页面手动授权
- Android 8.0+ 使用 `TYPE_APPLICATION_OVERLAY`，旧版本使用 `TYPE_PHONE`
- 悬浮窗 View 的 `onDraw` 在主线程执行，绘制逻辑要尽量轻量
- 支持拖拽移动需要处理 `MotionEvent` 和 `WindowManager.LayoutParams` 更新

### 4.3 权限管理清单
| 权限 | 用途 | 获取方式 |
|------|------|----------|
| SYSTEM_ALERT_WINDOW | 悬浮窗 | 引导用户到设置页 |
| FOREGROUND_SERVICE | 截屏前台服务 | 声明即可 |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | Android 14+ 截屏 | 声明即可 |
| RECORD_AUDIO | MediaProjection 需要 | 运行时请求 |

---

## 五、XML 资源踩坑

### 5.1 中文引号导致 XML 解析失败
```xml
<!-- 错误：中文引号会被XML解析器当作属性分隔符 -->
android:text="点击"开始"按钮"

<!-- 正确：使用转义或去掉引号 -->
android:text="点击开始按钮"
```

### 5.2 mipmap 资源缺失
- `AndroidManifest.xml` 中引用 `@mipmap/ic_launcher` 但没有对应资源会导致 AAPT 错误
- 临时方案：使用系统图标 `@android:drawable/ic_menu_crop`
- 正式方案：用 Android Studio 的 Image Asset Studio 生成全套 mipmap 资源

---

## 六、Kotlin 代码规范

### 6.1 companion object 重复
- 一个类中只能有一个 `companion object`，如果不小心创建了两个会编译失败
- 合并时注意把所有静态成员放在同一个 companion object 中

### 6.2 JNI 库加载
- 库加载（`System.loadLibrary`）应放在 companion object 的 init 块中，确保只加载一次
- 加载失败时用 `try-catch` 捕获 `UnsatisfiedLinkError`，设置标志位而非让应用崩溃
- 如果 NCNN 静态链接到 JNI 库中，只需加载一个库：`System.loadLibrary("aloyo_inference")`

---

## 七、项目构建配置速查

### 7.1 推荐版本组合
| 组件 | 版本 |
|------|------|
| AGP | 8.2.2 |
| Kotlin | 1.9.22 |
| Gradle | 8.5 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| NCNN | 20260113 |
| Java | 17 |

### 7.2 关键 build.gradle.kts 配置
```kotlin
android {
    ndkVersion = "27.0.12077973"
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++14", "-fopenmp")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    buildFeatures {
        buildConfig = true  // AGP 8.x 必须显式启用
    }
}
```

---

## 八、安全最佳实践

1. **Token 不要明文写在代码或对话中**：使用 GitHub Repository Secrets 管理
2. **CI 中使用 `${{ secrets.GITHUB_TOKEN }}`**：自动提供，无需手动配置
3. **签名密钥用 Secrets 管理**：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD` 等
4. **local.properties 加入 .gitignore**：包含本地路径和签名信息
5. **Token 泄露后立即撤销**：GitHub Settings → Developer Settings → Personal Access Tokens
