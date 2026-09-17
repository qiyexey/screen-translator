# 屏幕翻译（ScreenTranslator）

一个安卓端实时屏幕翻译 App：**11 种翻译方式**、**11 家云端引擎 + 1 个本地离线大模型**、端侧 OCR 拍照翻译。

| 模式 | 用法 | 原理 |
|------|------|------|
| 🌐 **全屏自动翻译** | 打开任意应用，屏幕变化自动翻译整屏 | `AccessibilityService` 监听窗口内容变化，DFS 遍历节点树 |
| ✋ **划词翻译** | 长按选中文字，0.5 秒后自动翻译 | `TYPE_VIEW_TEXT_SELECTION_CHANGED` 事件 + fromIndex/toIndex 截取 |
| 📋 **菜单划词** | 长按选中 → 菜单点「🌐 翻译」 | `PROCESS_TEXT` intent-filter，兜底 WebView 等不发选区事件的场景 |
| ⚽ **悬浮球定点** | 拖绿球到文字上松手，即翻译该处 | 松手取球心坐标，反查包含该坐标的最小面积文字节点 |
| 🔲 **框选翻译**（v1.5.0） | **双击悬浮球**，拖出矩形框，松手翻译框内全部文字 | 全屏遮罩拖画矩形，收集中心点落在选区内的文字节点，按阅读顺序拼接 |
| ⌨️ **输入翻译**（v1.6.0） | 打字停顿 0.6 秒即自动翻译 | 输入框防抖 + 复用全部翻译引擎；悬浮球**长按**也能进入 |
| 🎤 **语音翻译**（v1.6.0/1.7.0） | 对着手机说话，连续听写即时翻译 | 双引擎可切换：系统 `SpeechRecognizer` 或 **Whisper**（自建采集，不依赖 GMS，v1.7.0） |
| 🎧 **听视频翻译**（v1.6.0） | 看视频时屏幕顶部出实时字幕 | 内录（Android 10+）/麦克风采集 → 静音切句 → Whisper 转写 → 翻译 → 悬浮字幕条 |
| 📋 **复制即翻译** | 开关开启后，复制文字立即翻译 | 剪贴板监听（默认关） |
| 📷 **拍照翻译**（v1.11.0 / v1.14.0 盖住原文） | **点屏幕上的一行字就译那一处**，或按快门把整屏译文盖在各自原文上；**按住屏幕可看原文** | CameraX 取景 → **ML Kit 端侧 OCR**（本机识别、不依赖 GMS）→ 逐行翻译 → 按 OCR 框位置就地覆盖渲染译文；要上下文时另有「📄 全文」走整段一次翻译 |
| 🖼 **图片翻译**（v1.8.0） | 点主界面入口或**三击悬浮球**，截屏后拖框选区域 | `MediaProjection` 截屏 → 多模态模型直接识别并翻译 |
| 🔊 **朗读译文**（v1.8.0） | 翻译结果面板点「🔊 朗读」；也可开自动朗读 | 系统 TTS 引擎，语速/音调可调 |
| 📜 **翻译历史**（v1.8.0） | 主界面入口，可搜索/收藏/复制/朗读/导出 | 本地 JSON 存储，上限 500 条（收藏不被裁剪） |
| 💾 **翻译缓存**（v1.10.0） | 相同原文直接复用译文，不发请求 | LRU + 磁盘 JSON，按「引擎+端点+模型+目标语言+原文」判定 |

**保活防掉线**（v1.7.0）：无障碍服务被 ROM 杀掉是"每次都要重开"的根因。主界面权限卡片新增「防止服务被杀」：一键设置**忽略电池优化** + 各家 ROM 白名单指引；开机若发现无障碍被系统关掉，自动发通知一键直达重开页面。

悬浮球支持个性化（v1.5.0）：设置里可调**透明度**（20%~100%）、**大小**（36~64dp）、**颜色**（8 种预设），保存后即时生效。球的交互：**拖动**=定点翻译，**单击**=收起/展开结果面板，**双击**=进入框选模式，**长按**=输入翻译，**三击**=图片翻译（v1.8.0）。

翻译结果面板支持**背景透明度**调节（v1.5.1，40%~100%）——只调背景不调文字，面板变通透后译文依然清晰。

翻译引擎支持 11 家云端接口 + 1 个本地离线模型（v1.4.0 起；v1.17.0 新增本地模型），源语言自动识别，目标语言可选：

**AI 类（LLM，翻译质量高、懂上下文）**

| 引擎 | 接口 | 费用/额度 | 大陆直连 |
|------|------|----------|---------|
| **DeepSeek** | OpenAI 兼容 | 按量计费（极便宜） | ✅ |
| **OpenAI GPT** | OpenAI 协议，可填中转 | 按量计费 | ❌（可走中转） |
| **Claude** | Anthropic Messages 协议 | 按量计费 | ❌ |
| **通义千问** | 阿里百炼 OpenAI 兼容 | 新用户免费 Token 额度 | ✅ |
| **智谱 GLM** | OpenAI 兼容 | **glm-4-flash 免费** | ✅ |
| **火山豆包** | 火山方舟 OpenAI 兼容 | 按量计费（有免费额度） | ✅ |

**本地类（完全不出网，v1.17.0）**

| 引擎 | 运行时 | 费用 | 说明 |
|------|--------|------|------|
| **腾讯 Hy-MT2-1.8B** | 端侧 llama.cpp（CPU） | **免费、无密钥、断网可用** | 需先下载模型（1.13GB 起）；比云端慢，是离线兜底 |

**传统 MT 类（快、稳定、按字符计费）**

| 引擎 | 接口 | 免费额度 | 大陆直连 |
|------|------|---------|---------|
| **Google 翻译** | Cloud Translation v2 | 按量计费 | ❌ |
| **微软翻译** | Azure Translator v3 | 每月 200 万字符 | ✅ |
| **DeepL** | DeepL API v2 | Free 每月 50 万字符 | ✅ |
| **百度翻译** | 通用翻译 API + MD5 签名 | 每月 5 万字符 | ✅ |
| **彩云小译** | 彩云 Interpreter API | 新用户 100 万字/月 | ✅ |

切换引擎：设置 → 🌐 翻译引擎 · 语言 · 密钥 → 下拉选择 → 填对应密钥 → 保存。切换立即生效，无需重启。

## 本地大模型（v1.17.0 · 腾讯 Hy-MT2-1.8B）

选「腾讯 Hy-MT2 1.8B（本地·离线）」后，**译文不出设备**：模型在手机 CPU 上推理，
无密钥、无额度、飞行模式也能用。代价是比云端慢、更耗电 —— 它是**离线兜底**，不是更快的那条路。

- **模型不进 APK**：首次使用在设置页点「下载模型」（默认 ModelScope 源，实测约 4MB/s，1.13GB 约 5 分钟），
  或「从文件导入 gguf」用自备模型。三个量化档可选：Q4_K_M 1.13GB / Q6_K 1.47GB / Q8_0 1.91GB。
- **下载支持断点续传**，下完自动校验 sha256；中断了再点一次「继续下载」即可。
- **内存**：模型常驻约 1.3GB。闲置 5 分钟、或系统内存吃紧时自动卸载；
  也可在设置页手动「预加载 / 卸载」。
- **实测速度**（PLJ110，6 线程）：短句约 **1.0 秒/句**（详见 `FIXES-1.17.0.md`）。
- **图片翻译用不了它**（纯文本模型），会自动走「本机 OCR 认字 → 本地翻译」。
- **单次长度上限约 3000 字**：更长的文本会明确报错（本机 CPU 上要等好几分钟），
  建议分段翻译或临时切云端引擎。超过 700 字的输入会自动**按行分块**逐块翻译再拼回。
- **设备要求**：仅 arm64，且 CPU 需支持 dotprod/i8mm/fp16 指令
  （不支持时会明确提示并禁用该引擎，而不是崩溃）。
- **自带基准与诊断**：设置页可「跑一次基准（5 句）」并「复制诊断信息」
  （设备指令集 / 线程与上下文 / 进程内存 / 上次延迟），方便反馈问题。

**语音识别（听视频翻译）**：走 OpenAI 兼容的 `/v1/audio/transcriptions` 接口（Whisper 系模型），在 设置 → 语音识别 里配置。支持 OpenAI 官方、自建中转、硅基流动（国内直连）等。注意 DeepSeek 没有 ASR 接口。

**语音输入翻译**双引擎（v1.7.0）：默认用系统自带语音识别（GMS 或厂商引擎，零配置）；系统不可用时一键切换 **Whisper 引擎**——自建麦克风采集 + 静音切句 + Whisper API 转写，不依赖任何系统服务，无 GMS 的国产 ROM 也能用。


## 翻译缓存（v1.10.0）

屏幕内容在滚动、切 Tab、来回切应用时是**大量重复**的，而此前每次变化都会重新请求一次
翻译接口——既费钱（LLM 按 token、MT 按字符计费）又慢。加缓存后相同的原文直接复用译文，
零延迟、零费用。

- **命中判定**：引擎 + 端点 + 模型 + 目标语言 + 原文。换引擎、换模型、换中转之后会重新
  请求（同名模型挂在不同中转后端上可能是完全不同的服务，不能复用老译文）。
- **淘汰策略**：LRU（按访问顺序），条数上限默认 500，超出自动挤掉最久未用的。
  上限由 `Prefs.cacheMaxEntries` 控制（内部夹到 50~2000）。
- **不做 TTL**：同样的输入永远该得到同样的译文，没有"过期"概念。
- **超长原文不入缓存**：单条超过 `TranslationCache.MAX_SOURCE_CHARS`（6000 字符）直接放行请求。
- **失败不缓存**：断网 / 额度耗尽 / 鉴权错误都不会进缓存，重试仍然会真的重试。
- **开关**：`Prefs.cacheEnabled`（默认开）。关掉即完全走网络，便于排查"译文不对是不是缓存串了"。
- **文件**：`filesDir/translate_cache.json`，先写临时文件再原子替换；写入有 1.5s 合并窗口，
  避免整屏模式每秒重写整个文件。

缓存的挂载点是 `TranslatorFactory.current()` —— 7 个翻译调用点（无障碍读屏 / 听视频 /
图片翻译 / 输入翻译 / 语音翻译 / 菜单划词 / 主界面测试按钮）全都从这里取引擎，因此在工厂
外包一层 `CachingTranslator` 就一次性覆盖了所有入口，调用点零改动。

> ⚠️ `CachingTranslator` 必须显式转发 `translateImage`：接口里它有默认实现（返回"当前引擎
> 不支持图片"），漏掉转发会让图片翻译被静默废掉，而且报错文案会误导用户去换引擎。

## 首次启动引导（v1.13.0）

这个 App 不给无障碍 + 悬浮窗就完全不能用，所以首次启动会自动走一遍引导：

- 每一步都带一个**能直接跳去开权限的按钮**（无障碍 / 悬浮窗 / 电池优化 / 引擎密钥）
- 从系统设置返回时**实时回报状态**（✅ 已开 / ❌ 未开），不必自己判断"开好了没"
- **只自动出现一次**：跳过与走完都算完成；想再看走 设置 → 关于与用法 →「重新查看引导」

## 拍照翻译：拍哪译哪，译文盖住原文（v1.14.0）

对着菜单 / 路牌拍照后，**译文直接盖在原文上**（像谷歌拍照翻译），而不是堆在底部卡片里：

- **点哪译哪**：取景时直接点屏幕上的一行字 → 只翻那一行，译文就地盖住它；冻结帧上可以一行一行继续点
- **按快门**：整屏逐行翻译，每行原文都被各自译文盖住（状态栏显示 `已就地翻译 n/m 行`）
- **按住屏幕**：所有译文消失、露出原文，**松手恢复** —— 用来核对原文
- **📄 全文**：需要上下文时点它 —— 整屏原文**一次**发给引擎，译文更连贯（一次请求，省额度）
- 还没翻的行只画一圈细白框，正在翻的行高亮蓝色；「🔄 重拍」回到取景
- 贴片底色按原文底色自适应：浅底菜单走浅色块 + 深色字，深底招牌反过来

> 一行 = 一次请求，这是"位置绝不错位"的代价（整段译文无法可靠地拆回各行）。
> 同文行会去重、并发限 4、并复用 v1.10.0 翻译缓存；行数多又想省额度，
> 就点单行翻译，或直接用「📄 全文」。

## 界面结构（v1.12.0 起）

主页只回答两件事 ——「用什么」和「能不能用」：六个功能入口 + 无障碍/悬浮窗两个状态灯。
所有配置按主题分成七页：

```
⚙️ 设置
├─ 🌐 翻译引擎 · 语言 · 密钥
├─ ⚡ 翻译触发方式      悬浮球 / 划词 / 全屏 / 复制 / 结果面板
├─ 🔊 朗读译文（TTS）
├─ 🎧 语音识别（听视频用）
├─ ⚽ 悬浮球与面板外观
├─ 🔋 权限与保活
└─ ℹ️ 关于与用法
```

## 项目结构

```
screen-translator/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/hunter/screentranslator/
        │   ├── App.kt                      # Application 入口
        │   ├── api/
        │   │   ├── Translator.kt            # 翻译接口 + 语言表
        │   │   ├── DeepSeekTranslator.kt   # DeepSeek 实现
        │   │   └── WhisperClient.kt        # 语音识别（OpenAI 兼容 /v1/audio/transcriptions）
        │   ├── overlay/
        │   │   ├── OverlayView.kt          # 翻译结果面板（可拖动/折叠）
        │   │   ├── OverlayBallView.kt      # 悬浮球（样式可定制，拖动/单击/双击/长按四种手势）
        │   │   ├── RegionSelectView.kt    # 框选遮罩（拖出矩形选区）
        │   │   └── SubtitleOverlayView.kt # 听视频悬浮字幕条
        │   ├── service/
        │   │   ├── ScreenReaderService.kt   # 无障碍服务（全屏/划词/定点/框选/剪贴板）
        │   │   ├── OverlayService.kt        # 悬浮窗前台服务（管面板+球+框选遮罩）
        │   │   ├── VideoListenService.kt   # 听视频翻译（音频采集+切句+转写+翻译）
        │   │   └── AudioSegmenter.kt       # 静音检测切句器
        │   ├── ui/
        │   │   ├── MainActivity.kt         # 配置主界面
        │   │   ├── ProcessTextActivity.kt  # 系统菜单「🌐 翻译」入口
        │   │   ├── TranslateInputActivity.kt # 输入翻译（v1.6.0）
        │   │   ├── VoiceTranslateActivity.kt # 语音输入翻译（v1.6.0）
        │   │   └── VideoListenActivity.kt   # 听视频翻译控制页（v1.6.0）
        │   └── util/
        │       ├── Prefs.kt                 # 偏好设置封装
        │       └── WavUtils.kt             # PCM→WAV 打包（v1.6.0）
        │   ├── ui/
        │   │   ├── MainActivity.kt         # 配置主界面
        │   │   └── ProcessTextActivity.kt  # 系统菜单「🌐 翻译」入口
        │   └── util/
        │       └── Prefs.kt                 # 偏好设置封装
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/                   # 悬浮窗背景、应用图标
            ├── values/{strings,colors,themes}.xml
            ├── values-night/themes.xml     # 暗色模式
            ├── mipmap-anydpi-v26/          # 自适应图标
            └── xml/accessibility_service_config.xml
```

## 使用步骤

1. 用 Android Studio 打开 `screen-translator/` 目录，等 Gradle 同步完成
2. 连接手机（开启 USB 调试）或启动模拟器，点击 Run
3. 在 App 内：
   - 填入 **DeepSeek API Key**（在 [platform.deepseek.com](https://platform.deepseek.com) 获取）
   - 选择**目标语言**（默认中文）
   - 点击「保存配置」
4. 开启**无障碍服务**：点界面里的「开启」按钮，在系统设置列表里找到「屏幕翻译」并打开
5. 开启**悬浮窗权限**：点界面里的「开启」按钮，在弹出的权限页打开开关
6. 两个权限都为 ✅ 后，切换到任意含外文的 App（浏览器、Twitter、YouTube 等）
7. 屏幕内容变化时，悬浮窗会自动出现翻译结果
8. **拖动**悬浮窗可移动位置，**单击**折叠/展开

## 自定义

- **更换模型**：在界面里改 Base URL 和 Model。例如用 OpenAI 兼容的任何中转，把 Base URL 改成你的中转地址即可
- **改防抖时间**：`ScreenReaderService.kt` 里 `DEBOUNCE_MS`
- **改悬浮窗样式**：`res/drawable/overlay_bg.xml`、`OverlayView.kt`
- **改成截图 OCR 模式**：把 `ScreenReaderService` 替换成截图 + MLKit Text Recognition，保留 `OverlayService` 不变即可

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 调用翻译 / 语音识别 API |
| `SYSTEM_ALERT_WINDOW` | 显示翻译悬浮窗与字幕条 |
| `BIND_ACCESSIBILITY_SERVICE` | 读取屏幕文字 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 保活悬浮窗服务 |
| `RECORD_AUDIO` | 语音输入 / 听视频麦克风模式 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 听视频内录模式（Android 10+） |
| `FOREGROUND_SERVICE_MICROPHONE` | 听视频麦克风模式（Android 11+） |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |

## 故障排查（重要）

**装好没反应？按这个顺序查：**

1. **Android 13+ 的"受限设置"（最常见）**：APK 侧载安装的应用，无障碍开关默认被系统锁定，提示「因安全原因，无法使用此应用」。
   解决：系统设置 → 应用 → 屏幕翻译 → 右上角 **⋮ 菜单 → 允许受限设置** → 再去无障碍设置里开启。

2. **验证 API Key**：App 主界面点「测试翻译（真调 API 验证 Key）」，会真实调用一次 DeepSeek 并弹窗显示结果。Key 错误/没余额/网络不通都会给出具体原因。

3. **看悬浮窗状态**：正常开启后悬浮窗会先显示「✅ 翻译服务就绪」。切到浏览器等文字界面 1~2 秒后应出现译文。
   - 显示「⚠️ 当前界面没有读到文字」= 无障碍读不到该应用（视频/游戏/Canvas 绘制类正常现象，换浏览器或文字类 App 测试）
   - 显示「翻译失败」+ 原因 = API 配置问题，看提示逐项排查

4. **日志排查**：`adb logcat -s ScreenTranslator` 可以看到完整链路日志（服务连接、事件触发、收集字符数、翻译结果）。

5. **无障碍能读什么**：标准 UI 控件的文字都能读（浏览器、微信聊天、Twitter、新闻 App）。**读不到**：视频画面、游戏、图片里的文字 —— 这类场景由 **📷 拍照翻译**（端侧 OCR）和 **🖼 图片翻译**（截屏 + 多模态）覆盖。

## 本地打包 APK

### 1. 准备签名密钥

本仓库**不含签名密钥** —— 否则任何人 clone 下来都能签出「可覆盖安装到你手机上」的更新。
自己生成一把即可：

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias screentranslator \
  -keyalg RSA -keysize 2048 -validity 10000
```

### 2. 配置签名口令

口令**不写入源码**。放到 `local.properties`（该文件已被 `.gitignore` 排除）：

```properties
# local.properties
sdk.dir=/path/to/android-sdk
storePassword=你的keystore口令
keyPassword=你的key口令
keyAlias=screentranslator
```

或改用环境变量：

```bash
export SCREEN_TRANSLATOR_STORE_PASSWORD=...
export SCREEN_TRANSLATOR_KEY_PASSWORD=...
```

两种方式都不提供时，`assembleRelease` 仍能构建，只是产出**未签名** APK（不会因缺口令而失败）。

### 3. 构建

```bash
./gradlew assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 产物: app/build/outputs/apk/release/app-release.apk
```

环境要求：**JDK 17**、Android SDK **Platform 34**、**Build-Tools 34.0.0**。

Gradle 仓库已配置阿里云镜像优先（`settings.gradle.kts`），国内网络可直接构建。

## 安装到手机

APK 下载后直接安装（需允许"未知来源应用"）：
1. 传到手机（微信/QQ文件传输、USB、网盘均可）
2. 点击 APK → 允许安装
3. 打开 App → **首次启动会走一遍引导**，逐步把无障碍、悬浮窗、电池优化开好
4. 到 设置 → 翻译引擎 · 语言 · 密钥，填任一家密钥并保存

## 隐私

- API Key 仅保存在本机 SharedPreferences，不上传任何服务器
- 屏幕文字仅在用户开启「自动翻译」时被收集，直接发往用户配置的 DeepSeek 端点
- 本 App 不收集任何用户数据
