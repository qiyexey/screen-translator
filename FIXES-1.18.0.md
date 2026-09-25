# v1.18.0 — 发布工程整备（签名 / 混淆 / 密钥加密 / targetSdk 35 / 可复现构建）

这一版**不改任何翻译功能**，全部是"发布工程"的补齐。
起因是对 v1.17.0 的 APK 做了一次完整静态分析（见 `屏幕翻译_v1.17.0_逆向分析报告.html`），
结论是：功能与架构做得相当好，但发布工程是短板 ——
**用 Android 调试证书签名、代码零混淆、密钥明文存储、targetSdk 停在 34**。
这四项都是"不改功能、只改配置"就能解决的，本版把它们连同 6 项次要问题一并处理掉。

版本号：`versionCode 56 → 57`，`versionName 1.17.0 → 1.18.0`。

---

## 1. 换掉调试证书签名（P0）

### 问题

v1.17.0 的签名证书主体是 `CN=Android Debug, O=Android, C=US`，`serial=1` ——
这是 AOSP 公开的调试密钥，**全网开发者通用**。同时 `debuggable=false`、R8 已开启，
说明这是一次 **release 构建但签名配置漏了**，不是 debug 包。

后果有两层：

1. 无法上架任何应用商店（Google Play、华为、小米、应用宝都会拒）；
2. 调试密钥是公开的 —— 任何人都能用同一把密钥签一个同包名的恶意 APK，
   在已安装用户机器上被当作「正常升级」覆盖安装。

### 根因

```kotlin
// v1.17.0 的写法
signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("debug")
```

口令缺失时**静默退回** debug 签名。`?:` 这个回退是整个问题的源头。

### 改动

- `app/build.gradle.kts`：**删掉回退**，改为显式失败 ——

  ```kotlin
  val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
  if (wantsRelease && !hasReleaseSigning) {
      throw GradleException("release 构建缺少正式签名，已中止。…")
  }
  ```

  报错信息里直接给出生成密钥和配置口令的完整命令，不用去翻文档。
  `assembleDebug` 不受影响。

- 口令查找顺序：环境变量 → `keystore.properties` → `local.properties`。
  `keystore.properties` 已加入 `.gitignore`。
- 签名方案：`enableV1Signing = false`（minSdk 26 > 24，v1 没有意义）、
  `enableV2Signing = true`、`enableV3Signing = true`（**v3 是补上的**，v1.17.0 只签了 v2，
  而 v3 才支持密钥轮换）。
- 新增 `scripts/gen-keystore.sh`：RSA 4096 / PKCS12 / 10000 天，
  **拒绝覆盖已存在的密钥**，写完 `keystore.properties` 后打印证书指纹。
- `.gitignore` 追加 `keystore.properties`、`mapping.txt`、`seeds.txt`、`usage.txt`、`configuration.txt`。

> 「悄悄降级」比「构建失败」危险得多。这条是本次改动里最该有的一条。

---

## 2. 开启 R8 混淆与资源收缩（P0）

### 问题

v1.17.0 的 `minifyEnabled` / `shrinkResources` 都是 false。结果是 APK 里
15,249 个类全部保留原名，`com.hunter.screentranslator.*` 的类名、方法名、包结构完全可读，
`kotlin.Metadata` 注解也在 —— 反编译后能还原出接近原始 Kotlin 源码的结构。
资源 ID 名（`etClaudeKey`、`btnHyMtBench`…）同样未混淆，UI 结构一览无余。
对一个有明确产品设计与提示词工程的 App 来说，这等于把实现思路全部公开。

### 改动

**`app/build.gradle.kts`**：`isMinifyEnabled = true`、`isShrinkResources = true`。

**`app/proguard-rules.pro` 重写**。原来的规则只有一条
`-keep class com.hunter.screentranslator.** { *; }` —— 等于什么都没混淆。
新规则基于对 APK 的全量扫描结果，逐项给 keep 理由：

| keep 项 | 理由 |
|---|---|
| `org.codeshipping.llamakotlin.**` + `native <methods>` | JNI 符号按名字绑定（`Java_org_codeshipping_llamakotlin_LlamaNative_native*`），改名字就链接不上 |
| 4 个具名 Firebase Components 注册器 | 框架读 `meta-data` 的**值**当类名反射加载 |
| `ComponentRegistrar` 的 `-keepnames` | 同上，名字即契约 |
| Activity / Service / Receiver / Application | 清单里按名字引用 |
| `-keepattributes Signature, InnerClasses, EnclosingMethod` | 泛型与内部类信息，序列化/反射场景需要 |
| `dontwarn` 清单 | 只声明不使用的可选依赖 |
| `-keepattributes SourceFile, LineNumberTable` + `-renamesourcefileattribute SourceFile` | 保留行号以便还原堆栈，但把源文件名抹成 `SourceFile`，不泄露文件名 |

**刻意不保留 `kotlin.Metadata`** —— 保留它等于把 Kotlin 结构信息白送。

**扫描确认可以放心混淆的依据**（这三条是"敢开 R8"的前提）：

- 全工程**零反射**：没有 `Class.forName` / `getDeclaredMethod` / `getDeclaredField`；
- 布局里**零自定义 View**：没有 `com.hunter.screentranslator.overlay.*` 出现在 XML 中；
- **零 `System.loadLibrary` 字符串**：so 的加载走 `LlamaNative` 的静态初始化。

### 顺手挖出的资源收缩地雷（重要）

`BaseActivity.resColor()` 原来是按**资源名字符串**取色的：

```kotlin
resColor("md_success")   // 内部走 resources.getIdentifier(name, "color", pkg)
```

`getIdentifier` 是运行时查表，**编译期完全看不见**。于是
`md_success` / `md_warning` / `bg_primary` / `bg_card` / `text_primary` / `text_secondary`
这 6 个颜色在 XML 里没有任何静态引用 —— 一旦开启 `shrinkResources`，
它们会被判定为"无人使用"**直接删掉**，取色返回 0，然后静默走到硬编码的浅色 fallback。
表现出来就是「引导页在深色模式下变成浅底浅字」，而且**不报任何错**。

改成传资源 ID：

```kotlin
protected fun resColor(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)
// 调用处：resColor("md_success") → resColor(R.color.md_success)
```

编译期即可校验存在性，资源收缩也能正确看到引用，顺带省掉每次取色的字符串查表开销。
涉及 `BaseActivity` + 4 个 UI 文件。

---

## 3. API Key 加密存储 + 关闭备份泄露（P0）

### 问题

`Prefs.kt` 用普通 `SharedPreferences` 存 13 项用户付费密钥（DeepSeek / OpenAI / Claude /
千问 / GLM / 豆包 / 百度 / 彩云 / Whisper…），全量字符串扫描确认**没有任何加密存储痕迹**。
同时 `allowBackup=true` 且未配置 `dataExtractionRules` ——
设备 root 或通过备份通道导出，密钥就是明文泄露。用户拿自己的钱在承担风险。

### 改动

**新增 `util/SecretStore.kt`** —— `AndroidKeyStore` + AES-GCM 封装：

- 密钥由系统 TEE 托管，**不出安全硬件**，应用拿不到裸密钥；
- 12 字节随机 IV（GCM 标准长度，不重用）；
- 密文带 `enc:v1:` 前缀 —— 用于区分"已加密"和"历史明文"，也是将来换算法时的迁移锚点；
- `decrypt` 失败**不会**把 `unavailable` 置位：解密失败的原因可能是换机/数据损坏，
  不等于密钥库坏了，两者要分开处理，否则一次数据异常就会把整个存储标记为不可用。

**`util/Prefs.kt` 改造**：

- 新增第二个 SharedPreferences 文件 `screen_translator_secrets` 专放密文；
- `getSecret()` 四级回退：密钥文件 → 解密 → 明文 → 旧文件（保证升级不丢配置）；
- `putSecret()` 写密文并**删除旧文件里的明文键**；
- `migrateSecrets()` 幂等迁移，在 `App.onCreate` 里跑一次；
- 13 项敏感字段全部改走 `getSecret` / `putSecret`。

> 实现细节：`SENSITIVE_KEYS` 集合声明在 companion object 的**末尾**。
> Kotlin 的 object 属性按书写顺序初始化，放在 `KEY_*` 常量之前会在运行时拿到 null ——
> 这是个很容易踩的坑，代码里留了注释说明。

**备份规则**：

- 新增 `res/xml/backup_rules.xml`（API ≤30 的 `fullBackupContent`）
- 新增 `res/xml/data_extraction_rules.xml`（API 31+，**云备份**与**设备间迁移**分别声明）
- 两者都把 `screen_translator_secrets.xml` 排除

**保留 `allowBackup=true`**，而不是报告里建议的改成 false ——
这样用户换机时翻译历史与设置仍能恢复，只把密钥排除掉。更细也更安全。

---

## 4. targetSdk 34 → 35，并适配 Android 15 行为变更（P1）

### 4.1 SDK 与工具链

`compileSdk` / `targetSdk` 34 → 35；AGP `8.5.2 → 8.6.0`（compileSdk 35 的最低要求，
8.5.x 上限是 34）；Gradle 相应到 8.7。

### 4.2 edge-to-edge（本次最容易被忽略的坑）

targetSdk 到 35 后，Android 15 **强制**所有 Activity 进入 edge-to-edge：
`android:statusBarColor` / `android:navigationBarColor` 被系统**直接忽略**，
窗口铺满全屏，内容会画到状态栏和手势条下面。

而本工程原来：

- 13 个布局**没有一个**设置 `fitsSystemWindows`；
- 全量搜索 `WindowInsets` / `setDecorFitsSystemWindows` / `WindowCompat` —— **命中 0 处**。

也就是说，如果只改 SDK 版本号不做事，**每个页面的标题栏都会被状态栏压住一截**。

**新增 `util/EdgeToEdge.kt`**，做两件事：

1. `enableSystemBars()` 调 AndroidX 官方的 `enableEdgeToEdge()`
   （`androidx.activity` 1.8+，本工程 activity-ktx 1.9.1 已具备），
   状态栏/导航栏设为全透明，图标明暗按当前深浅模式运行时判定。
   **没有手写** `setDecorFitsSystemWindows` + `SYSTEM_UI_FLAG_*` ——
   AppCompat 的 subDecor 自带 `fitsSystemWindows="true"`，手写很容易踩到
   "insets 被上层吃掉"；官方 API 内部已经处理了 26~28 的导航栏 scrim、
   29+ 的 `isNavigationBarContrastEnforced`、刘海屏的 `layoutInDisplayCutoutMode`。

2. `padContent()` 把系统栏高度作为 **padding** 加到内容根视图上。
   选 padding 而不是 margin 是有意的：padding 画在背景**之内**，
   而 13 个布局的根节点都带 `android:background="@color/bg_primary"`，
   于是根视图背景会自然延伸到状态栏/导航栏底下，视觉完全无缝 ——
   **不用给 13 个布局逐个改 XML**。
   底部同时取 `systemBars` 与 `ime` 的较大值：开启 edge-to-edge 后系统不再替应用做键盘避让，
   输入框页面必须自己躲键盘。

注入方式：`scripts/inject-edge-to-edge.py` 在 17 个 Activity 的 `setContentView(...)` 之后
插入一行 `EdgeToEdge.install(this)`。`ProcessTextActivity` 跳过（透明跳板，无内容视图）。

主题侧同步清理：`values/themes.xml` 与 `values-night/themes.xml` 里删掉写死的
`statusBarColor` / `navigationBarColor` / `windowLightStatusBar`（已由代码接管），
改为关掉系统的强制对比度 scrim（`enforceStatusBarContrast` / `enforceNavigationBarContrast` = false）。

### 4.3 前台服务类型 —— 复查结论：**原本就是对的，未改动**

逐项核对了 `AndroidManifest.xml` 与各服务的 `startForeground` 调用：

| 服务 | 声明类型 | 代码传入 |
|---|---|---|
| `OverlayService` | `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` ✅ |
| `VideoListenService` | `mediaProjection\|microphone` | 按模式二选一 ✅ |
| `LiveTranslateService` | `mediaProjection` | `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` ✅ |

对应权限（`FOREGROUND_SERVICE_SPECIAL_USE` / `_MEDIA_PROJECTION` / `_MICROPHONE`）齐全，
`POST_NOTIFICATIONS` 的申请流程在 `MainActivity` 里也有。**这部分不用动。**

---

## 5. 体积瘦身（P1）

v1.17.0 的 34.8 MB 里，ML Kit OCR 相关占约 19.4 MB（56%）：
arm64 的 `libmlkit_google_ocr_pipeline.so` 10.6 MB、**armeabi-v7a 的同名库 6.5 MB**、
OCR 模型资源 2.4 MB。而 `libllama-android.so`（4.8 MB）**只有 arm64-v8a 一份**。

矛盾点在于：本地离线翻译本来就只在 arm64 上可用（代码里已有
`HyMtDeviceSupport` 预检并提示"本机不是 arm64 设备"），却仍然为 32 位设备
打包了 6.5 MB 的 OCR 原生库。

**改动**：`abiFilters` 只保留 `arm64-v8a`。预期 APK 34.8 MB → 约 26 MB（-20%）。

> ⚠️ **代价必须说清**：APK 将不再包含任何 armeabi-v7a 原生库，而端侧 OCR 依赖
> `libmlkit_google_ocr_pipeline.so` —— 因此**纯 32 位设备上 OCR 会直接加载失败**。
> 如果你要覆盖 32 位老机型，把 `abiFilters` 换回 `listOf("arm64-v8a", "armeabi-v7a")` 即可，
> 代价是 APK 大 6.5 MB。这行注释也写在 `build.gradle.kts` 里了。

**另外**：移除 `moshi` + `moshi-kotlin` 依赖。全工程 **0 处引用**
（JSON 解析一律走平台自带的 `org.json`，共 18 处），
它是 dex 里 `kotlin/reflect/jvm/internal/impl/**` 那 1822 条引用的**唯一来源**
（即整个 `kotlin-reflect`）。移除后 dex 明显变小，R8 也不再需要为反射留规则。

CameraX 的版本**刻意没跟着升** —— 换版本会引入行为差异，与本次"安全合规"目标无关，
留作独立一次改动单独验证。

---

## 6. 崩溃上报：选择了本地记录，而不是接第三方 SDK（P1）

报告的原始建议是"接入一个可关闭的、用户首次启动时征得同意的崩溃上报（Sentry 自建或国内合规方案）"。

**本版没有接任何上报 SDK**，而是新增 `util/CrashLog.kt` —— 本地崩溃记录：

- 零依赖、零网络、零权限；
- 写入应用私有目录 `filesDir/crash/`，最多保留 5 份；
- 提供 `recentReport()` / `allReports()` / `count()` / `clear()`；
- 在 `App.onCreate` 里**最先**初始化（早于所有其他初始化）。

理由（这是本次唯一偏离报告建议的地方，说明一下）：

- Crashlytics 依赖 GMS，本工程的定位恰恰是"无 GMS 国产 ROM 也要能用"；
- Bugly 会额外引入一整条统计 SDK，与"零埋点"的现状冲突；
- Sentry 需要 endpoint，等于要维护一个服务端。

而报告真正想解决的痛点是"线上出问题开发者手里没数据"。本地记录 + 已有的
「诊断信息导出」入口已经能覆盖这个诉求：用户在设置页一键导出，
里面就包含最近的崩溃堆栈（`EngineSettingsActivity.buildDiagExtras()` 已接上）。
**是否发给开发者完全由用户决定**，不产生任何隐式数据外流。

如果将来确实需要自动上报，`CrashLog` 的接口是现成的挂载点。

---

## 7. 文案抽离到 strings.xml（P2）

### 规模

| 来源 | 改写处数 | 新增资源 |
|---|---|---|
| 13 个 layout 的 `android:text` / `hint` / `contentDescription` / `title` | 210 | 174 |
| Kotlin 里的 `toast(...)` / `.text = "…"` / `.setText(...)` / `Toast.makeText(...)` | 75 | 57（其中 6 条复用已有资源） |
| **合计** | **285** | **248**（含原有 17 条） |

抽离后 `layout/*.xml` 里**已无任何硬编码中文**（自检脚本会校验这一条）。

### 命名规则（这是本项最花心思的地方）

第一遍先统计每条中文出现在几个布局里，再决定名字：

- 只出现在 1 个布局 → `<布局简称>_<控件 id 蛇形>`，无 id 时 `<布局简称>_t<序号>`
- 出现在 ≥2 个布局 → `common_<控件 id 蛇形>` / `common_t<序号>`
- Kotlin 侧序号**接续**同前缀已有的最大序号，避免撞出 `voice_translate_t01_2` 这种名字

为什么不简单点：

- 如果"谁先出现就用谁的布局名"，`activity_settings.xml` 里会出现
  `@string/about_btn_back` 这种跨文件引用 —— 读代码的人会以为改错了文件；
- 如果完全不去重，同一句「‹ 返回」会在 13 个布局里各留一份资源，翻译要翻 13 遍，
  也违背了报告里说的"统一校对"。

所以：**共用文案单独归到 `common_*` 组**。自检确认跨页引用（非 `common_`）为 **0**。

### 只抽"纯字面量"

Kotlin 侧只处理不含 `$`（插值）也不含 `\`（转义）的字符串。
含插值的要改成 `getString(id, args)`，**参数顺序必须人工确认，脚本盲改会出错**；
含 `\n` 的虽然能照搬，但混在一批里改更容易漏，索性一起留给人工。
代码里带 `$` 或 `\n` 的 UI 文案仍有约 40 处，已在 `strings.xml` 的注释里注明。

### 三个脚本

| 脚本 | 作用 |
|---|---|
| `scripts/extract-strings.py` | 布局文案抽离（两遍扫描定名） |
| `scripts/extract-strings-kotlin.py` | Kotlin 文案抽离（注释遮蔽 + 偏移替换） |
| `scripts/verify-strings.py` | 自检：XML 合法性 / 残留中文 / `@string` 引用完整性 / 资源名合法且不重复 |

安全性细节：Kotlin 脚本匹配前先把 `//` 行注释和 `/* */` 块注释按**等长空格**遮蔽，
因此"注释里恰好写了一句 `.text = "中文""` 不会被误改；替换按原始偏移倒序进行。

---

## 8. llama.cpp 改为 NDK 交叉编译并脚本化（P2）

### 问题

v1.17.0 的 `libllama-android.so` 调试路径显示它是在
`/data/data/com.dsharnessmobile.shell/files/home/work/llama.cpp/` 下编译的 ——
也就是**在手机上的 Termux 里**编出来的。好处是 llama.cpp 版本很新；
代价是**构建过程不可复现、无法进 CI、也没法给别人用**。

### 改动

新增 `jni/build-android.sh`，在电脑上跑：

1. 从 `ANDROID_NDK_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` 自动定位 NDK；
2. clone llama.cpp 并 checkout 固定 commit `4bc272f`（**版本可复现**）；
3. `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DGGML_CPU_ARM_ARCH=armv8.6-a`，
   开 `dotprod / i8mm / fp16` 三个内核开关；
4. 链成单个 so，带 `-Wl,-z,max-page-size=16384`（满足 Android 15 的 16KB 页要求）；
5. 跑 **5 项验收**，任何一项不过就直接失败，不留半成品：
   - `Java_org_codeshipping*` 符号数 ≥ 13
   - 未解析的 `LlamaContextWrapper` 符号数 = 0
   - `hunyuan-dense` 字符串存在
   - `sdot` 指令数 > 0（基线版是 0）
   - `smmla` 指令数 > 0

`jni/README.md` 的"复现步骤"已改为以脚本为主，原 Termux 流程折进 `<details>` 保留备查。

---

## 9. 纳入 Git、建 CI、清理构建元数据（P2）

- 新增 `.github/workflows/android.yml`：JDK 17 + Android SDK 35 + Gradle 8.7，
  从 secrets 取 keystore，构建签名 release，上传 APK 与**私有**的 R8 mapping，
  并做两项硬校验 ——
  1. **签名不是调试证书**（否则直接失败）；
  2. **R8 确实混淆了**（否则直接失败）。

  这两条把本次修的 P0 问题变成了 CI 的回归防线，避免将来又悄悄退化。

- `app/build.gradle.kts` 新增 `packaging.resources.excludes`：

  | 排除项 | 原因 |
  |---|---|
  | `DebugProbesKt.bin` | `kotlinx-coroutines` 的协程调试探针元数据，线上无人调用，只白占体积 |
  | `kotlin-tooling-metadata.json` | Kotlin 插件写的工具链版本清单，对运行无用，只暴露构建环境 |

  **刻意不排除** `META-INF/version-control-info.textproto` ——
  它记录构建时的 Git 提交哈希。v1.17.0 里它写着 `NO_SUPPORTED_VCS_FOUND`
  （因为当时不在 Git 里），现在工程已纳入 Git 并在 CI 构建，它从"噪音"变成了
  有用的溯源信息（出问题能回溯到哪次提交）。

---

## 10. 必应网页引擎：标注失效风险（P2）

`BingWebTranslator` 抓的是 `bing.com/translator` 网页自用的非公开接口。
代码里已经写了三种失败分支，说明作者清楚风险；界面上也已有
"代价是非公开接口 —— 微软改版或限流时会失败"的说明。

**复查结论**：它**不是默认引擎**（默认是 `DEEPSEEK`），所以"默认关闭"这一条本来就满足。

本次只补强文案：

- 引擎列表显示名：`必应网页（免费 · 仅文字）` → `必应网页（免费 · 仅文字 · 随时可能失效）`
- 详情说明改为以警告开头：`⚠️ 非公开接口，随时可能失效：…只适合当兜底选项，不建议当主力引擎。`

---

## 11. 顺手修掉的两个报告里没提的 Bug

### 11.1 暗色模式下文字与卡片同色（真·看不见）

`values-night/colors.xml` 里的 6 个旧色别名**直接照抄了白天的值**：

```xml
<!-- v1.17.0 的 values-night/colors.xml -->
<color name="bg_primary">#FFFFFF</color>     <!-- 夜间页面底 = 纯白 -->
<color name="text_primary">#202124</color>   <!-- 夜间主文字 = 深灰 -->
```

后果不是"观感一般"，而是文字直接看不见：

- 13 个布局的根节点都写 `android:background="@color/bg_primary"` → **夜间页面底色仍是纯白**；
- 卡片走的是 M3 角色 `cardBackgroundColor=@color/md_surface_container_low` → **夜间卡片是深色**；
- 卡片里的文字是 `@color/text_primary` → **夜间仍是深色**。

最终是「白底 + 深卡 + 深字」，卡片上的字与卡片底色几乎同色。

根因是 v1.9.0 引入 M3 角色体系、v1.15.23 换强调色时，都只改了 `values/colors.xml`，
没有同步 `values-night/colors.xml` 里的旧别名。

**修复**：把 6 个别名映射到对应的夜间 M3 角色（`bg_primary ← md_surface` 等），
`accent` 取 `#8AB4F8`（白天 `#1A73E8` 的深色对应色，对比约 9:1）。
另外 `md_success` 夜间由 `#1E8E3E` 提到 `#6DD58C` —— 它只当文字色用，
原来压在夜间底色上对比度只有约 3.3:1，低于 WCAG AA 的 4.5:1。

### 11.2 夜间主题的 `windowLightStatusBar` 写反了

`values-night/themes.xml` 里注释写着"夜间：…浅色图标（windowLightStatusBar 设 false）"，
**代码写的却是 `true`** —— 即夜间模式下仍然要求状态栏画深色图标，配深色状态栏等于看不见。
该属性现已交由 `EdgeToEdge` 运行时判定，主题不再参与。

### 11.3 一处**没有改**的不一致（如实说明）

白天主题 `colorPrimary = @color/accent`（蓝 `#1A73E8`），
夜间主题 `colorPrimary = @color/md_primary`（绿 `#8FE0B0`）——
同一个 App 的强调色在深浅模式之间从蓝变绿。这看起来也是 v1.15.23"换肤"漏了夜间，
但改它需要重新定义一整套蓝色的 M3 色板（primary / onPrimary / primaryContainer /
onPrimaryContainer），属于视觉设计决策而非缺陷修复，**本版不擅自改动**，
留给你确认要不要统一。

---

## 12. 没做的事 / 遗留

1. **没有编译验证**。这台机器上没有 JDK、Android SDK、NDK，也没有 `keytool`，
   所以本次全部改动都是**源码级**的，没有跑过 `assembleRelease`。
   已做的静态校验见 §13。**首次构建请留意 edge-to-edge 与文案抽离这两块。**
2. **Kotlin 侧仍有约 40 处带插值或 `\n` 的 UI 文案未抽离**（见 §7）。
3. **32 位设备不再支持端侧 OCR**（见 §5 的代价说明）。
4. **CameraX 版本未升级**（刻意，见 §5）。
5. **未接入自动崩溃上报**（刻意，见 §6）。
6. `README.md` 里"翻译引擎支持 11 家云端接口"等描述未逐条复核。

---

## 13. 验证方式

本机可跑的自检：

```bash
python scripts/verify-strings.py
```

输出（已通过）：

```
1) XML 合法性
  [OK] 34 个 XML 全部可解析
2) 布局残留硬编码中文
  [OK] layout/*.xml 中 android:text/hint/contentDescription/title 已无中文
3) @string 引用完整性
  [OK] 248 条字符串资源覆盖了全部布局/清单引用
4) 资源名合法性
  [OK] 资源名均为 [a-z0-9_] / 无重复资源名
```

另外单独核对过：

- Kotlin 里 63 个不同的 `R.string.*` 引用，**0 个缺失定义**；
- 新增 `getString(R.string.…)` 调用 75 处，与改写处数一致；
- 布局之间的跨页 `@string` 引用（非 `common_*`）为 **0**；
- 17 个 Activity 均已在 `setContentView` 之后调用 `EdgeToEdge.install(this)`。

**改动规模**：改动 47 个文件（+1555 / -436 行），新增 14 个文件 ——
`SecretStore.kt`、`CrashLog.kt`、`EdgeToEdge.kt`、`ids.xml`、`backup_rules.xml`、
`data_extraction_rules.xml`、`scripts/`（5 个脚本）、`jni/build-android.sh`、
`.github/workflows/android.yml`。

**构建后建议立刻核对的三件事**：

1. `apksigner verify --print-certs app-release.apk` —— 证书主体不应再是 `CN=Android Debug`；
2. `unzip -l app-release.apk | grep DebugProbes` —— 应为空；
3. 真机（Android 15）跑一遍设置页、引导页、引擎设置页 —— 看标题栏有没有被状态栏压住，
   以及深色模式下卡片文字是否可读。
