plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aloyo.inference"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        // CMake配置 - 编译JNI桥接库
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++14")
                arguments("-DANDROID_STL=c++_static")
            }
        }

        // 支持的ABI
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // CMake构建配置（仅在NCNN SDK存在时启用）
    val ncnnDir = file("libs/ncnn-android-vulkan")
    if (ncnnDir.exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation("androidx.core:core-ktx:1.12.0")
    // NCNN Android SDK - CI环境自动下载，本地开发需手动放置
    val ncnnAar = file("libs/ncnn-android.aar")
    if (ncnnAar.exists()) {
        implementation(files("libs/ncnn-android.aar"))
    }
}
