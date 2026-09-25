# v1.20.0 — 源语言可选 + 免 Key 语音输入

承接用户提问：

> 现在翻译都是自动识别的，我需要加个选项框，让来源和译文都能选择自己想要的语言。另外，不用 openai 的 key 能实现语音输入吗

## 一、源语言改为可选

### 之前的状态

源语言是**全程写死**的，分布在 11 处：

| 位置 | 原写法 |
|---|---|
| `BaiduTranslator.kt:48` | `.add("from", "auto")` |
| `BingWebTranslator.kt:123` | `.add("fromLang", "auto-detect")` |
| `CaiyunTranslator.kt:33` | `val transType = "auto2$targetLang"` |
| `GoogleTranslator.kt` | 完全不发 `source` |
| `MicrosoftTranslator.kt` | 完全不发 `from` |
| `DeepLTranslatorEngine.kt` | 完全不发 `source_lang` |
| `OpenAICompatibleTranslator.kt:165` | 提示词写死「自动识别原文使用的语言」 |
| `OpenAICompatibleTranslator.kt:183` | 图片提示词没有源语言 |
| `ClaudeTranslator.kt:122` | `（自动识别源语言）` |
| `HyMtLocalTranslator.kt:155` | `buildHyMtPrompt` 完全没有源语言 |
| `Translator.kt:36` | `SOURCE_AUTO` 常量**定义了但零引用**（死代码） |

同时 `Prefs` 里只有 `ttsSourceLang`（只管朗读发音），**没有** `sourceLang`。

### 改动

**1. `Translator` 接口签名加一维**

```kotlin
suspend fun translate(text, targetLang, sourceLang = SOURCE_AUTO): Result<String>
suspend fun translateImage(imageBytes, mimeType, targetLang, hint = null, sourceLang = SOURCE_AUTO)
```

两处细节：

- **默认值 `SOURCE_AUTO`** 让所有既有实现与调用点无需改动即可编译，行为与 v1.19.0 逐字一致。
- `translateImage` 的 `sourceLang` **只能追加在尾部**，不能插到 `hint` 前面：那样
  `translateImage(bytes, mime, lang, "某说明")` 这类按位置传第 4 个参的旧调用会把
  说明当作源语言**静默传错**，编译期毫无提示。
- `CachingTranslator` 的 `override` **不重复写默认值** —— Kotlin 禁止
  overriding function 指定 default values，写了会直接编译报错。

**2. 缓存作用域并入源语言（关键）**

`CachingTranslator` 里新增 `scopeKey(sourceLang)`：源语言改变译文语义，
不并入缓存键的话，一次 `auto→en` 的缓存会被后来的 `ja→en` 请求命中，
用户改了源语言却拿到旧译文。

`auto` 时**原样返回旧 scope**，所以升级后不会凭空多出一份冷缓存。

**3. 11 个引擎逐一接入**

| 引擎 | 做法 |
|---|---|
| Baidu | `from` 走 `BAIDU_LANG` 白名单（ja→jp 等），未知代码报错 |
| BingWeb | `fromLang` 从 `auto-detect` 换成真实码；新增 `AUTO_DETECT` 常量 |
| Caiyun | `trans_type` 改为 `"${sourceLang}2$targetLang"` |
| Google | `auto` 时**整个参数略去**（v2 没有 auto 语言码，发了会 400） |
| Microsoft | 同上，`auto` 时略去 `from` |
| DeepL | `source_lang`，同样 auto 时略去 |
| OpenAI 兼容（文本） | 提示词第 1 条改由 `SOURCE_AUTO_LABEL` 占位符 + `replace` 生成 |
| OpenAI 兼容（图片） | 源语言说明拼在三引号块**之外**（同 `hint` 的处理） |
| Claude（文本/图片） | 同上 |
| Hy-MT 本地 | `buildHyMtPrompt` 追加「（原文语言是 X，请直接按 X 理解）」 |

**踩到的坑**：BingWeb 的自动检测字面量是 `"auto-detect"` 而不是 `"auto"`，
与接口层 `SOURCE_AUTO` 不是同一个值。这类「同一概念在不同上游叫法不同」的偏差
是串联源语言时最容易漏掉的一类 bug，所以专门加了 `AUTO_DETECT` 常量并在注释里点明。

**4. UI：三处**

- `EngineSettingsActivity` 新增 `spinnerSource` 下拉（在「目标语言」上方），
  列表 = `"自动识别（推荐）"` + 8 语言，与 `TtsSettingsActivity` 的原文语言下拉同构。
- `VoiceTranslateActivity` 的 `btnLang`（点一下循环切 8 种语言，要点 7 次才回上一项）
  换成真正的 `spinnerLang` 下拉框。`btnEngine` 保持按钮不变（只有两态，循环够用）。
- `MainActivity` 的语言对**真正生效**了：之前 `tvSrcLang` 从未被赋值（静态显示
  "自动检测"），`⇄` 只是跳设置页 —— 纯装饰。现在两端都读真实偏好，`⇄` 真的交换。

**5. 测试翻译刻意不传用户的源语言**

`EngineSettingsActivity` 的「测试翻译」按钮固定用英文样例，源语言**硬传 `SOURCE_AUTO`**
而不是 `App.prefs.sourceLang`。用户若把源语言设成日语，拿它去测英文样例会得到明显
错误的结果，从而误判"这个引擎坏了"。测试的目的是验证密钥/网络连通性。

## 二、免 Key 语音输入

**结论：可以，而且原来就已经有一条通路，只是被一行 `require` 挡住了。**

`WhisperClient` 原有：

```kotlin
require(apiKey.isNotBlank()) { "未配置语音识别 API Key（主界面 → 语音识别设置）" }
```

这行把**所有**免密钥方案都挡在门外 —— 而 `/v1/audio/transcriptions` 早已是事实标准，
本地 faster-whisper-server、whisper.cpp server 示例都实现了它且**都不校验密钥**。
用户即使把 `asrBaseUrl` 填成 `http://127.0.0.1:8000/v1` 也只会看到"未配置 API Key"。

改动：

- 去掉 `require`，改为 `require(wav.isNotEmpty())`（这个才是真的前置条件）。
- **Key 为空时整个不发送 `Authorization`**，而不是发一个空的 `Bearer `。
  空 Bearer 会被不少实现判成"提供了错误的凭据"而 401，比不提供凭据更糟。
- `VoiceTranslateActivity` / `VideoListenActivity` 的前置拦截从「不让开始」
  改成「弹窗确认后继续」，并在弹窗里显示即将连接的 baseUrl。
- `AsrSettingsActivity` 的 Key 标签补上"自建服务可留空"，说明文案补上本地地址示例。

**注意**：`jniLibs` 里只有 `libllama-android.so` + `libc++_shared.so`，
**没有 whisper.cpp 的 .so**，所以"完全离线、设备上直接跑 Whisper"仍需引入原生库
（要 NDK 交叉编译），本次未做。

另外，**系统 `SpeechRecognizer` 引擎本来就免 Key**，不需要任何改动；
它不是"退而求其次"的方案 —— 走的是厂商自带的离线/在线识别。

## 三、校验

源语言这条链路横跨接口签名 → 13 个实现 → 缓存键 → 14 个调用点 → 2 个下拉框，
**任何一处漏改都不是编译错误**（因为参数有默认值），而是运行时静默失效。
所以除了编译，另加 `scripts/verify-sourcelang.py`（32 项）逐条对账，
与 `verify-m3.py`、`verify-strings.py` 一起跑：

```
verify-m3.py          8/8     通过
verify-sourcelang.py  32/32   通过
verify-strings.py     5/5     通过
```

`verify-sourcelang.py` 里另写了一份 `scan_balance()` 剥注释（不复用 `verify-m3.py` 的），
用于「某标识符是否已彻底移除」这类检查 —— 注释里提到旧名字（如
"v1.20.0 从 [updateLangButton] 改名而来"）不应判为残留。
与 `verify-m3.py` 同名函数保持同一套字符扫描写法，不用正则，因为字符串里的
`http://` 会被误当行注释而在其处截断。

## 四、构建

本版**首次真正编译**（此前所有版本都只有源码级改动）。环境建在 D 盘：

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `D:\AndroidBuild\jdk-17.0.20.1+1`（Temurin） | **17**（`jvmTarget` 固定 17，换版本会挂） |
| Android SDK | `D:\AndroidBuild\sdk` | platform 35、build-tools 34.0.0 |
| Gradle | wrapper 自动下载 | 8.7 |

```bash
bash scripts/build.sh both     # 或 debug / release
```

### 编译期发现并修掉的问题

编译暴露了 3 个静态检查抓不到的问题，其中 **2 个是 v1.19.0 遗留的、潜伏了整整一个版本**：

1. **`engine_settings_t35` 等字符串 ID 与既有 ID 撞车**（本版新增的，已改 t48–t51）。
   教训：新字符串**顺序编号**，不要挑空号。
2. **`voice_translate_t08` 同样撞车**（已改 t09）。
3. **两个 AAPT2 资源名错误**：
   - `materialToolbarStyle` → 正确名是 **`toolbarStyle`**
   - `Widget.Material3.Divider` → 正确名是 **`Widget.Material3.MaterialDivider`**

   这类"属性/样式名是否存在"的错误**只有 AAPT2 能查出来**。
   `verify-m3.py` 只查 XML 语法和 id 引用，结构上抓不到 —— 这正是它潜伏一版的原因。

### 补上的防护

新增 `scripts/gen-material-names.py` 扫描三个来源生成白名单
`scripts/material-res-names.txt`，让 `verify-strings.py` 第 5 项能在**编译前**
发现"资源名不存在"：

| 来源 | attr | style |
|---|---|---|
| `material` AAR 1.12.0 | 148 | 771 |
| `appcompat` AAR 1.7.0 | 110 | 343 |
| 平台 `android-35` framework | 1920 | — |
| **合计** | **2115** | **1114** |

三个来源缺一不可：`toolbarStyle` / `titleTextAppearance` 这类**标准平台主题属性**
只在平台 framework 里声明，库只是在自带 style 中**引用**它们；
只扫 material 会大批误报（本版一度误报 14 条，就是漏了来源）。
脚本另加**回归自检**，确认已知的错误名（`materialToolbarStyle` /
`Widget.Material3.Divider`）不在白名单里 —— 否则白名单一旦退化成
"什么都能过"，这项检查会静默失效。

> 升级 `material` / `appcompat` 版本或改 `compileSdk` 后，需重跑
> `python scripts/gen-material-names.py`。

### 产物

| 文件 | 大小 | 说明 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 28 MB | debug 签名 |
| `app/build/outputs/apk/release/app-release.apk` | 23 MB | R8 + 资源压缩，release 签名 |

release 包签名经 `apksigner` 复核：**v1=false / v2=true / v3=true**，
证书 `CN=Screen Translator, OU=Release, O=ScreenTranslator, C=CN`，RSA **4096**。
**不是** Android debug 证书 —— 说明 v1.18.0 加的「禁止静默回退 debug 签名」守卫生效。
包内原生库只有 `arm64-v8a` 四个（与 `abiFilters` 一致），单个 `classes.dex`（R8 已合并）。

### 已知局限

~~`adb devices` 无已连接设备，**未做真机安装与运行测试**。~~
（此条已在下面的「五、真机闪退修复」中解决。）

## 五、真机闪退修复（v1.20.0 补丁）

首次上真机（PLJ110 / Android 16 / arm64-v8a）时**启动即闪退**。真机日志定位到：

```
FATAL EXCEPTION: main
  java.lang.RuntimeException: Unable to start activity
    ComponentInfo{.../ui.MainActivity}: android.view.InflateException:
    Binary XML file line #113 in layout/activity_main:
    You must supply a layout_height attribute.
  Caused by: java.lang.UnsupportedOperationException:
    ... You must supply a layout_height attribute.
    at ViewGroup$LayoutParams.setBaseAttributes
    at LinearLayout$LayoutParams.<init>
    at LinearLayout.generateLayoutParams
    at LayoutInflater.rInflate
    at MainActivity.onCreate(MainActivity.kt:55)
```

### 根因：一个**时序上就是错的**设计判断

`Widget.ScreenTranslator.Divider` 样式里原本写着这样一段注释（v1.19.0 我写的）：

> 这里**故意不写** `android:layout_height`。`layout_*` 放在 `<style>` 里本来就
> 不可靠……`MaterialDivider` 自己在 `onMeasure` 里按 `dividerThickness`
> 强制高度，所以让它自己管。

**前提是对的，推论是错的。** 正确时序是：

```
LayoutInflater 构造 View
  → 父容器 generateLayoutParams() 构造 LayoutParams
  → setBaseAttributes()  **立刻**读 layout_height
  → 读不到（值为 -1）→ 抛 UnsupportedOperationException
onMeasure 在这之后很久才跑 —— MaterialDivider 根本没机会"自己管高度"
```

所以结论要拆成两句，之前被混为一谈：

| 位置 | 结论 |
|---|---|
| 写在 `<style>` 里 | ❌ 没用。父容器走 `generateLayoutParams(AttributeSet)`，那条路径不保证带上 style |
| 写在布局里 | ✅ **必须**。这是唯一正确的位置 |

### 影响面：17 处，13 个页面

不是只有主页 —— **所有含分隔线的页面都会崩**。逐个补齐后：

| 文件 | 处数 |
|---|---|
| `activity_about` / `asr_settings` / `engine_settings` / `live_translate` / `settings` / `translate_input` / `trigger_settings` / `video_listen` | 各 1 |
| `activity_main` | 1 |
| `activity_ball_style` / `tts_settings` | 各 2 |
| `activity_permission` | 3 |
| **合计** | **17** |

### 修复

1. 17 处 `MaterialDivider` 全部补上 `android:layout_height="1dp"`（走布局，不走样式）。
2. 重写 `themes.xml` 里那段误导性注释，把错误结论和正确时序都写清楚，
   避免后人照着旧注释再犯。
3. **给 `verify-strings.py` 加了第 6 项检查**：扫描所有布局，
   任何 View 缺 `layout_width`/`layout_height` 即报错并指出文件与行号。

### 为什么前三道防线全都没拦住

| 防线 | 为什么漏了 |
|---|---|
| `verify-m3.py` | 只查 XML 语法合法性与 id 引用 —— **缺个属性不影响语法合法** |
| AAPT2 编译 | 不报错 —— 它只在**运行期 inflate** 时才抛 |
| 三套静态检查 | 同上，都是"编译期可见"的检查 |

也就是说这类错误**编译能过、静态检查能过、只有真跑起来才炸**。
这正是第 6 项检查存在的意义 —— 它把"运行期才暴露"的问题提前到了静态阶段。

### 真机验证结果（已实测，非推测）

在 PLJ110（Android 16 / API 36 / arm64-v8a）上实测：

| 项目 | debug 包 | release 包（R8） |
|---|---|---|
| 冷启动 | ✅ `LaunchState: COLD` / `Status: ok` | ✅ 同左 |
| 崩溃堆栈 | ✅ 无 | ✅ 无 |
| 逐页打开 | ✅ **16/16 页面全部通过** | ✅ **10/10 页面全部通过** |
| 界面渲染 | ✅ 正常（见下） | ✅ 正常 |

截图确认主页渲染正确：标题栏（新 MaterialToolbar）、语言行「自动检测 ⇄ 中文」、
输入卡片、实时屏幕翻译卡片、听视频入口、底部权限状态、**分隔线正常显示**。
语音识别设置页也确认了两处本版新文案生效（「自建服务可留空」提示与
`http://127.0.0.1:8000/v1` 示例）。

### 教训

**「控件自己会在 onMeasure 里管某属性」不能作为「布局里不写该属性」的理由 ——
LayoutParams 的读取发生在 inflate 阶段，早于任何 measure。** 布局参数
（`layout_*`）永远属于布局，不属于样式，也不属于控件的自我管理范围。


---

# 六、v1.20.1 / v1.20.2 —— 主页语言下拉 & 语音输入

## 6.1 语言选择从设置页搬到主页（按用户要求）

用户原话：

> 「为什么要把语言选择框放在设置页呢，不应该是自动选择和中文那个地方吗」

完全正确 —— 主页那一行「自动检测 ⇄ 中文」看起来就像一个能点的地方，
语言对又是最常用的设置，藏在设置页里是设计失误。

改造：

| 位置 | 改造前 | 改造后 |
|---|---|---|
| 主页语言行 | 两个纯 `TextView`（装饰） | 两个 `MaterialAutoCompleteTextView`，可下拉 |
| 候选列表 | 无 | 由 `LANG_DISPLAY` **派生**（源语言多一项"自动检测"） |
| 设置页 | 唯一能改的地方 | 保留（作为备用入口），不再是唯一 |

**候选列表必须派生、不能在 XML 里写死**：写死会让 `app:simpleItems` 的数组
与 Kotlin 的 `LANG_DISPLAY` 变成两份要手工同步的列表，顺序错位就会出现
"显示的语言名"与"下发的语言代码"对不上 —— 这种 bug 静默且难查。

## 6.2 ⚠️ 踩坑：`boxBackgroundMode=none` 与下拉箭头**互斥**

为了实现"无边框但能下拉"的观感，我第一版写了：

```xml
<style name="Widget.ScreenTranslator.DropdownLayout.Borderless"
    parent="Widget.Material3.TextInputLayout.FilledBox.ExposedDropdownMenu">
    <item name="boxBackgroundMode">none</item>   <!-- ❌ 致命 -->
    <item name="boxStrokeWidth">0dp</item>
</style>
```

结果**首页一 inflate 就崩**：

```
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to start activity .../ui.MainActivity:
  android.view.InflateException: Binary XML file line #102 in layout/activity_main:
  Error inflating class com.google.android.material.textfield.TextInputLayout
Caused by: java.lang.reflect.InvocationTargetException
Caused by: java.lang.IllegalStateException:
  The current box background mode 0 is not supported by the end icon mode 3
  at EndCompoundLayout.setEndIconMode(EndCompoundLayout.java:377)
  at EndCompoundLayout.initEndIconView(EndCompoundLayout.java:259)
  at TextInputLayout.<init>(TextInputLayout.java:690)
```

反编译 `material-1.12.0.aar` 的两个类，机制一目了然：

```java
// EndCompoundLayout.setEndIconMode(int) —— 在 **构造函数期** 就被调用
if (!delegate.isBoxBackgroundModeSupported(til.getBoxBackgroundMode()))
    throw new IllegalStateException("The current box background mode " + mode
                                  + " is not supported by the end icon mode " + mode);

// DropdownMenuEndIconDelegate.isBoxBackgroundModeSupported(int mode)
// 字节码:  iload_1 / ifeq -> iconst_0 / iconst_1 / ireturn
return mode != 0;      // 0 = BOX_BACKGROUND_NONE → 直接 false
```

而 `endIconMode=3` 就是父样式 `...FilledBox.ExposedDropdownMenu` 给的
`endIconMode=dropdown_menu`（下拉箭头）。于是：

> **"无底色(0)" 与 "下拉箭头(3)" 是硬互斥的，必须在两者里选一个。**

**正确做法：保下拉箭头，观感改用"颜色透明"实现**，而不是"关掉盒子"：

```xml
<style name="Widget.ScreenTranslator.DropdownLayout.Borderless"
    parent="Widget.Material3.TextInputLayout.FilledBox.ExposedDropdownMenu">
    <!-- 不写 boxBackgroundMode，继承 FilledBox 的 filled(1) -->
    <item name="boxBackgroundColor">@android:color/transparent</item>
    <item name="boxStrokeColor">@android:color/transparent</item>
    <item name="boxStrokeErrorColor">@android:color/transparent</item>
    <item name="boxCollapsedPaddingTop">0dp</item>
    <item name="hintEnabled">false</item>
</style>
```

FilledBox 仍然占着盒子的位置，但底色与描边全透明，在 surface 底上看就是一行纯文字。

### 为什么三层静态防御都没拦住

| 防线 | 为什么失效 |
|---|---|
| `verify-m3.py` | 只做 XML 语法 + id 引用检查 |
| AAPT2 编译 | attr/style **名字**都存在（我逐一对过 `R.txt`），链接期无错 |
| 资源名检查 | 同上 —— 这是**值的组合**不被支持，不是名字不存在 |

**这属于"名字全对、组合非法"的一类**，静态检查天然覆盖不到，只能靠真机跑。
唯一能提前发现的办法是加一条"已知互斥组合"的黑名单规则 —— 已记入遗留项。

## 6.3 工具链修正：白名单数据源换成了 `R.txt`（重要）

排查上面那个坑时需要确认 attr 名是否真实存在，结果发现
`gen-material-names.py` **一直在读一个残缺的数据源**：

- 它原来 regex 扫 AAR 里的 `res/values/values.xml`
- 而那是 AAPT2 **部分展开**的成品文件：只包含**没有 format** 的 styleable 局部 attr
- material 1.12.0 的 `values.xml` 里只有 **103** 个 attr；
  `boxBackgroundColor` / `boxStrokeColor` / `hintEnabled` / `boxCollapsedPaddingTop`
  这些带 format 的**根本不出现**
- 于是白名单残缺，会把大量真实存在的属性判成"不存在"

**改为直接读 AAR 内的 `R.txt`** —— 那是 AAPT2 自己产出的最终符号表，
列全了 `int attr` / `int style`，一个不漏。

效果（数字即证据）：

| 指标 | 修正前 | 修正后 |
|---|---|---|
| material attr | 148 | **1161** |
| 总 attr（+appcompat+平台） | 2115 | **2799** |

注意一个细节：`R.txt` 里 style 名的点被换成了下划线
（`Widget_Material3_Toolbar`），必须还原成 `Widget.Material3.Toolbar` 才是真名。

同时 `verify-strings.py` 的检查 #5 也补了一半：**原来只核对 value 形如
`@style/xxx` 的 item**，漏掉了"`<item name="attr">` 里 attr 名本身写错"这一半。
现在扩到**所有 item 的 name**。

## 6.4 语音输入"无效"的真因：**不是 App 的 bug**

用户反馈「语音输入依旧无效」。在 PLJ110（Android 16 / ColorOS）上实测，
证据链完整且可复现：

```bash
# 1) 系统里能查到 Google 的识别服务已注册
$ adb shell dumpsys package com.google.android.googlequicksearchbox | grep -A2 RecognitionService
      android.speech.RecognitionService:
        com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService

# 2) 包也没被禁用
$ adb shell cmd package list packages -e com.google.android.googlequicksearchbox
package:com.google.android.googlequicksearchbox

# 3) 但三方 App 的角度查不到任何服务
$ adb shell cmd package query-services -a android.speech.RecognitionService
No services found                      ← 关键

# 4) 系统也没设默认识别服务
$ adb shell settings get secure voice_recognition_service
null
```

结论：服务**已注册、包未禁用**，但 **ROM 对第三方 App 隐藏了它**
（国产 ROM 为自家语音助手所做的限制）。

所以 `SpeechRecognizer.isRecognitionAvailable()` 返回 `false`
是**完全正确的判断**，App 没有误判、也没有 bug。这类设备上
**Whisper 引擎是唯一可用的方案**。

### Whisper 链路本身是好的

代码检查确认 v1.20.0 的"放开 Key 校验"已正确落地 ——
只有 Key **非空**时才加 `Authorization` 头，自建免鉴权服务可直接用。
设备上失败的真正原因是**用户从未配置**：
`shared_prefs` 里 `asr_api_key` / `asr_base_url` / `voice_engine` 三个键**一个都没有**。

### 顺手修掉一个 UX 死循环

原逻辑用 `App.prefs.asrBaseUrl.isNotBlank()` 判断"配置过没有"。
但 `asrBaseUrl` 的**默认值就是** `https://api.openai.com/v1`（见 `Prefs.kt`），
**它永远非空** —— 于是"没配过"被误判成"配好了"，用户被切到一个
指向 OpenAI 官方、却没有 Key 的端点，录完一整段话才拿到 401。

修正为：

```kotlin
// 真正的"配过"标志：填了 Key，或把地址改成了非默认值
val defaultAsrUrl = "https://api.openai.com/v1"
val configured = App.prefs.asrApiKey.isNotBlank() ||
        App.prefs.asrBaseUrl.trim().trimEnd('/') != defaultAsrUrl
```

并把两处弹窗改得更有出路：

1. **「此设备没有系统语音服务」** —— 未配置时按钮从「切换到 Whisper」改成
   **「去配置」**，点了**直接跳 `AsrSettingsActivity`**，不再让用户停在
   一个"切了引擎却依然不可用"的页面上。
2. **「没有填写语音识别 API Key」** —— 区分两种"没 Key"：
   - 地址还是 OpenAI 默认值 → **不给「继续」**，只给「去配置」（继续必定 401）
   - 地址是自建的 → 保留「继续」（可能是免鉴权服务）

## 6.5 真机验证结果

| 项目 | 结果 |
|---|---|
| 首页冷启动 | ✅ 无崩溃（0 条 FATAL EXCEPTION） |
| 主页语言行 | ✅ 渲染正常，`tvSrcLang`/`tvTargetLang` 下出现 `text_input_end_icon`（下拉箭头），**用户确认可切换** |
| 语音页 | ✅ 正常进入，新弹窗文案与按钮生效 |
| 「去配置」跳转 | ✅ 实测落到 `AsrSettingsActivity`，配置页三字段渲染正确 |
| 静态校验 | ✅ `verify-strings.py` 6 项全绿（attr 核对数 2799） |
| 构建 | ✅ `gradle 退出码 0`，debug 28 MB |

## 6.6 教训

1. **`boxBackgroundMode` 与 `endIconMode` 是组合约束，不是独立属性。**
   设置前要查该 delegate 的 `isBoxBackgroundModeSupported()`，
   而不是凭"看起来该这样"去拼样式。
2. **观感问题优先用"颜色/尺寸"解决，不要用"功能开关"解决。**
   想让盒子看不见，应该把颜色设透明；把模式关掉会连带砍掉依赖该模式的功能。
3. **判断"用户配过没有"不能看字段非空** —— 先确认该字段有没有默认值。
   有默认值的字段，`isNotBlank()` 恒为真，用它做判据必然误判。
4. **工具的数据源要选权威的那一份。** 同样是"从 AAR 抽资源名"，
   `values.xml`（部分展开）与 `R.txt`（最终符号表）差了 684 个 attr。
