import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置读取顺序（避免把密钥口令写进源码 / 提交到仓库）：
//   1. 环境变量 SCREEN_TRANSLATOR_STORE_PASSWORD / _KEY_PASSWORD
//   2. local.properties 里的 storePassword / keyPassword（local.properties 不应提交）
//   3. 都没有 → 不配置签名，release 构建产出未签名 APK（构建仍能成功）
//
// 注意：release.keystore 若已随仓库分发，其口令对任何拿到仓库的人都可见，
// 等同签名密钥已泄漏。APK 签名密钥无法轮换，正式发布前请更换为新的 keystore。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    (System.getenv(name) ?: localProps.getProperty(name))?.takeIf { it.isNotBlank() }

val storePass = secret("SCREEN_TRANSLATOR_STORE_PASSWORD") ?: secret("storePassword")
val keyPass = secret("SCREEN_TRANSLATOR_KEY_PASSWORD") ?: secret("keyPassword")

android {
    namespace = "com.hunter.screentranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hunter.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "1.14.0"

        ndk {
            // v1.11.0：ML Kit 的 OCR 原生库每个 ABI 各带一份，四份合计约 41MB
            // （x86_64 11.6 + x86 11.6 + arm64-v8a 11.1 + armeabi-v7a 6.8）。
            // 真机只需要 arm 两种，滤掉两个 x86（那是给模拟器的）能省下约 23MB。
            // 若确定只在自己的 arm64 手机上装，去掉 armeabi-v7a 还能再省 6.8MB。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        if (storePass != null && keyPass != null) {
            create("release") {
                storeFile = file("../release.keystore")
                storePassword = storePass
                keyAlias = secret("keyAlias") ?: "screentranslator"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 只有口令齐备时才挂签名配置，否则打到未签名包（不再因缺口令而构建失败）
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp for network calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // v1.11.0：拍照翻译 —— CameraX 取景。
    // 选 1.3.4 而非最新版：1.5/1.6 要求更高的 compileSdk，本工程停在 34。
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // v1.11.0：端侧 OCR。用 **bundled** 变体（模型打进 APK），运行时不需要 GMS ——
    // 与 v1.7.0 Whisper 引擎同样的取向：无 GMS 国产 ROM 也要能用。
    // play-services 变体体积小，但依赖 GMS 运行时下载模型，国行机器上可能直接不可用。
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
