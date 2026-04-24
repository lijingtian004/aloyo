plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aloyo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aloyo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 从环境变量或local.properties读取签名配置
            val localProps = rootProject.file("local.properties")
            if (localProps.exists()) {
                val props = java.util.Properties()
                props.load(localProps.inputStream())
                storeFile = file(props.getProperty("keystore.path", "keystore/release.jks"))
                storePassword = props.getProperty("keystore.password", "")
                keyAlias = props.getProperty("keystore.alias", "aloyo")
                keyPassword = props.getProperty("keystore.keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core-inference"))
    implementation(project(":core-capture"))
    implementation(project(":core-overlay"))
    implementation(project(":core-model"))
    implementation(project(":core-logger"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
}
