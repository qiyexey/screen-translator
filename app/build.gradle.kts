import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================================================
// 签名配置读取顺序（避免把密钥口令写进源码 / 提交到仓库）：
//   1. 环境变量 SCREEN_TRANSLATOR_STORE_PASSWORD / _KEY_PASSWORD
//   2. keystore.properties（推荐；不应提交，已加入 .gitignore）
//   3. local.properties 里的 storePassword / keyPassword
//
// v1.18.0 变更：**release 构建缺签名口令时直接失败**。
//
// 为什么必须改：v1.17.0 的写法是
//     signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("debug")
// —— 口令缺失时静默退回 debug 签名。结果是产出了一个「R8 未开、用 Android 调试
// 证书签名」的 release 包（证书主体 CN=Android Debug，serial=1）。它既上不了架，
// 又因为调试密钥全网公开而可以被同包名的恶意包冒名覆盖安装。
// 「悄悄降级」比「构建失败」危险得多，所以这里改成显式报错。
// ============================================================================
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    (System.getenv(name)
        ?: keystoreProps.getProperty(name)
        ?: localProps.getProperty(name))?.takeIf { it.isNotBlank() }

val storePass = secret("SCREEN_TRANSLATOR_STORE_PASSWORD") ?: secret("storePassword")
val keyPass = secret("SCREEN_TRANSLATOR_KEY_PASSWORD") ?: secret("keyPassword")
val keyAliasValue = secret("keyAlias") ?: "screentranslator"
val storeFilePath = secret("storeFile") ?: "../release.keystore"
val hasReleaseSigning = storePass != null && keyPass != null &&
    rootProject.file(storeFilePath.removePrefix("../")).exists()

android {
    namespace = "com.hunter.screentranslator"
    // v1.18.0：34 → 35（Android 15）。targetSdk 34 已经无法在 Google Play 更新，
    // 国内主流商店也在陆续跟进。AGP 相应升到 8.6.0（compileSdk 35 的最低要求）。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hunter.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 74
        versionName = "1.27.4"

        ndk {
            // v1.11.0：ML Kit 的 OCR 原生库每个 ABI 各带一份，四份合计约 41MB
            // （x86_64 11.6 + x86 11.6 + arm64-v8a 11.1 + armeabi-v7a 6.8）。
            // 真机只需要 arm 两种，滤掉两个 x86（那是给模拟器的）能省下约 23MB。
            //
            // v1.18.0：**只保留 arm64-v8a**。
            //
            // 依据：本工程自带的 libllama-android.so 本来就是 arm64-only
            // （jni/README.md：-march=armv8.6-a 且只在设备上编过 arm64），
            // 32 位设备上本地大模型一定不可用（HyMtDeviceSupport 已做预检）。
            // 保留 armeabi-v7a 只是白带一份 6.5MB 的 ML Kit OCR 库。
            //
            // ⚠️ 代价（如实说明）：APK 将不再包含任何 armeabi-v7a 原生库，
            // 而端侧 OCR 依赖 libmlkit_google_ocr_pipeline.so —— 因此**纯 32 位
            // 设备上 OCR 会直接加载失败，App 等于不可用**。如果你需要覆盖 32 位
            // 老机型，把下面这行换回 listOf("arm64-v8a", "armeabi-v7a") 即可，
            // 代价是 APK 大 6.5MB。
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(storeFilePath.removePrefix("../"))
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass

                // minSdk 26 > 24，v1（JAR 签名）没有存在意义 —— 它只服务于
                // Android 7 以下，开着反而多出一堆 META-INF 摘要文件。
                // v3 是必须补的：它支持密钥轮换，而 v1.17.0 只签了 v2。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // v1.18.0：这两项原来是 false / 未设。结果是 APK 里 15249 个类
            // 全部保留原名、包结构与 Kotlin 元数据，反编译即可还原接近原始
            // 源码的结构（连资源 ID 名 etClaudeKey / btnHyMtBench 都在）。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 调试包不动：不混淆、用自动生成的 debug.keystore，保持可断点调试。
            isMinifyEnabled = false
        }
    }

    // ------------------------------------------------------------------------
    // v1.18.0：清掉打进 release 包的两类"构建残留"
    //
    // 1) DebugProbesKt.bin —— kotlinx-coroutines 自带的调试探针元数据。
    //    它随 kotlinx-coroutines-core 一起进包，作用是让 DebugProbes.install()
    //    能 dump 协程。线上没有人会调它，留着只是白占体积、并把"协程可被调试"
    //    这条能力带进 release。
    //
    // 2) kotlin-tooling-metadata.json —— Kotlin 插件写的构建工具链版本清单。
    //    对运行毫无用处，只会对外暴露"用了哪个 Kotlin / Gradle 版本"。
    //
    // 注意：**不排除** META-INF/version-control-info.textproto。
    // 那个文件记录构建时的 Git 提交哈希，是"出问题能回溯到哪次提交"的依据，
    // 本工程已纳入 Git 并在 CI 里构建，它从"噪音"变成了有用的溯源信息。
    // ------------------------------------------------------------------------
    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
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

// ---------------------------------------------------------------------------
// release 构建缺正式签名 → 立即失败（见文件头的说明）。
// 只想出调试包时用 assembleDebug，不会触发这条。
// ---------------------------------------------------------------------------
val wantsRelease = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}
if (wantsRelease && !hasReleaseSigning) {
    throw GradleException(
        """
        |============================================================
        | release 构建缺少正式签名，已中止。
        |
        | 原因：没有找到 release keystore 或口令。v1.17.0 在这种情况下会
        | 静默退回 debug 签名，产出一个「上不了架、且可被冒名覆盖安装」的包。
        | 现在改为直接失败，避免再次误发。
        |
        | 生成密钥（只做一次，之后务必备份）：
        |     bash scripts/gen-keystore.sh
        |
        | 然后把口令写进 keystore.properties（不要提交）：
        |     storeFile=release.keystore
        |     storePassword=...
        |     keyAlias=screentranslator
        |     keyPassword=...
        |
        | 或者用环境变量：
        |     export SCREEN_TRANSLATOR_STORE_PASSWORD=...
        |     export SCREEN_TRANSLATOR_KEY_PASSWORD=...
        |
        | 只想出可安装的调试包：./gradlew assembleDebug
        |============================================================
        """.trimMargin()
    )
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

    // v1.18.0：移除 moshi + moshi-kotlin。
    // 全工程 0 处引用（JSON 解析一律走平台自带的 org.json，共 18 处）。
    // 它是 dex 里 kotlin/reflect/jvm/internal/impl/** 那 1822 条引用的唯一来源，
    // 即 kotlin-reflect 整个包 —— 移除后 dex 明显变小，R8 也不再需要为反射留规则。

    // v1.11.0：拍照翻译 —— CameraX 取景。
    // v1.18.0：compileSdk 已升到 35，CameraX 的版本上限不再是约束，
    // 但这里**暂不跟着升** —— 换版本会引入行为差异，与本次"安全合规"目标无关，
    // 留作独立一次改动单独验证。
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // v1.11.0：端侧 OCR。用 **bundled** 变体（模型打进 APK），运行时不需要 GMS ——
    // 与 v1.7.0 Whisper 引擎同样的取向：无 GMS 国产 ROM 也要能用。
    // play-services 变体体积小，但依赖 GMS 运行时下载模型，国行机器上可能直接不可用。
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // v1.15.15：日文模型。用途是"端侧 OCR + 免费文本翻译"这条链路
    //（实时屏幕翻译若走视觉模型要按次计费；改成本机认字 + 必应翻译则零费用）。
    // 与中文模型同样是 bundled 变体，运行时不需要 GMS。
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // v1.17.0：本地大模型（腾讯 Hy-MT2-1.8B）的推理运行时。
    //
    // 这里只引入 llama-kotlin-android 的 **Kotlin API 层**（app/libs 下的 classes.jar），
    // native 的 libllama-android.so 由本工程自编（app/src/main/jniLibs/，源码见 jni/）。
    //
    // 为什么不直接依赖 Maven 上的 AAR：它带的 .so 是按 armv8-a 基线编的
    // （反汇编确认 sdot/smmla 指令数为 0），实测整句延迟是自编版的 2.25 倍
    // （2.25s vs 1.00s）。另外它的 POM 会经 androidx.core:core-ktx:1.17.0
    // 把工程现用的 1.13.1 顶上去，进而要求 compileSdk 36 + AGP 8.9.1+ ——
    // 换成 vendored jar 后这个传递依赖也随之消失。
    implementation(files("libs/llama-kotlin-android-0.1.7-classes.jar"))
}
