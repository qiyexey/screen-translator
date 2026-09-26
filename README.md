# 屏幕翻译（ScreenTranslator）

一个安卓端实时屏幕翻译 App：**11 种翻译方式**、**11 家云端引擎 + 1 个本地离线大模型**、端侧 OCR 拍照翻译。

---

## ⚠️ 使用前必读

### 本 App **不含任何内置密钥**，开箱即用是做不到的

仓库里没有、也不会有作者自己的 API Key（13 项密钥经 `AndroidKeyStore` 加密后只存在**你自己的设备**上，
见文末「隐私」）。下载安装后，**翻译这一步在你配置之前一定失败** —— 这不是 bug，
是"不把别人的额度烧给你"的代价。

### 为什么必须自己配：翻译和"说话"是两件事

很多人以为"语音翻译装完就能说话"，其实它有**两段**、各自独立：

| 环节 | 干什么 | 密钥要求 |
|---|---|---|
| ① **听写（ASR）** | 把你说的话变成文字 | 用**手机系统识别** → **免密钥**；用 Whisper → 看你接的服务 |
| ② **翻译** | 把文字变成目标语言 | **默认引擎必须有密钥**，唯一例外见下 |

**① 是通顺的**：默认走手机自带识别（GMS 或厂商引擎），零配置。
**② 才是卡点**：默认引擎是 DeepSeek，没填 Key 时开录能听写、翻译必失败。
v1.26.0 起 App 会在**开录之前**就弹窗拦住（而不是让你说完一整句话才报错），
提示条也会写明"当前引擎没有配置"，并直接给「去配置引擎」按钮。

### 零成本跑通的最小路径（推荐先这样试）

想一分钱不花先把语音翻译跑起来，两步：

1. **翻译引擎**：设置 → 🌐 翻译引擎 · 语言 · 密钥 → 选 **「必应网页版」** → 保存。
   它免密钥、免注册，走的是必应网页版的自用接口。
   > 代价要讲清楚：**服务端随时可能改，属于"能用多久看运气"**，且**只能翻文字、不接受图片**。
   > 长期使用建议换成下面的任一正式引擎。
2. **语音识别**：设置 → 🎧 语音识别 → 保持 **「手机系统」**（默认值）。
   若机型查不到系统语音服务（部分国产 ROM 会偷偷把识别服务对第三方 App 隐藏），
   界面上会提示，这时才需要配 Whisper。

**另有第三条路：完全离线** —— 选「腾讯 Hy-MT2 1.8B（本地·离线）」，
模型在你手机 CPU 上跑，无密钥、无额度、飞行模式可用（首次需下载 1.13GB 模型，详见下方专章）。

### 要换成正式引擎的话，申请入口

| 引擎 | 申请入口 | 备注 |
|---|---|---|
| **DeepSeek**（默认） | [platform.deepseek.com](https://platform.deepseek.com) | 按量计费、极便宜、大陆直连 |
| 通义千问 | [bailian.console.aliyun.com](https://bailian.console.aliyun.com) | 新用户有免费额度 |
| 智谱 GLM | [open.bigmodel.cn](https://open.bigmodel.cn) | `glm-4-flash` 免费 |
| 火山豆包 | [console.volcengine.com/ark](https://console.volcengine.com/ark) | 需同时填模型名（如 `doubao-1.5-lite-32k`） |
| 微软翻译 | [portal.azure.com](https://portal.azure.com) | 每月 200 万字符免费 |
| DeepL | [deepl.com/pro-api](https://www.deepl.com/pro-api) | Free 档每月 50 万字符 |
| 百度翻译 | [fanyi-api.baidu.com](https://fanyi-api.baidu.com) | 需填 AppID + 密钥（两个都要） |
| 彩云小译 | [dashboard.caiyunapp.com](https://dashboard.caiyunapp.com) | 新用户 100 万字/月 |
| OpenAI / Claude / Google | 各自官网 | 大陆需自备网络或中转 |

### 语音识别（可选，只有系统识别不可用时才需要）

走 OpenAI 兼容的 `/v1/audio/transcriptions`。**Key 可以留空** ——
Key 为空时 App 完全不发送 `Authorization` 头，所以自建的
`faster-whisper-server` / `whisper.cpp server` 之类免鉴权服务可直接用
（v1.20.0 起，地址填成非默认值即可）。

---

| 模式 | 用法 | 原理 |
|------|------|------|
| 🌐 **全屏自动翻译** | 打开任意应用，屏幕变化自动翻译整屏 | `AccessibilityService` 监听窗口内容变化，DFS 遍历节点树 |
| ✋ **划词翻译** | 长按选中文字，0.5 秒后自动翻译 | `TYPE_VIEW_TEXT_SELECTION_CHANGED` 事件 + fromIndex/toIndex 截取 |
| 📋 **菜单划词** | 长按选中 → 菜单点「🌐 翻译」 | `PROCESS_TEXT` intent-filter，兜底 WebView 等不发选区事件的场景 |
| ⚽ **悬浮球定点** | 拖绿球到文字上松手，即翻译该处 | 松手取球心坐标，反查包含该坐标的最小面积文字节点 |
| 🔲 **框选翻译**（v1.5.0） | **双击悬浮球**，拖出矩形框，松手翻译框内全部文字 | 全屏遮罩拖画矩形，收集中心点落在选区内的文字节点，按阅读顺序拼接 |
| ⌨️ **输入翻译**（v1.6.0） | 打字停顿 0.6 秒即自动翻译 | 输入框防抖 + 复用全部翻译引擎；悬浮球**长按**也能进入 |
| 🎤 **语音翻译**（v1.6.0/1.7.0） | 对着手机说话，连续听写即时翻译 | 可切换系统 `SpeechRecognizer`、**Whisper** 或输入法听写 |
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

**Whisper 语音识别**：语音翻译页选 Whisper 时，走 OpenAI 兼容的 `/v1/audio/transcriptions` 接口，在 设置 → Whisper 语音识别 里配置。支持 OpenAI 官方、自建中转等。注意 DeepSeek 没有 ASR 接口。

**语音输入翻译**可选三种输入方式：手机系统识别、Whisper（自建麦克风采集 + 静音切句 + API 转写）和输入法听写。输入法模式可使用已安装的微信输入法，需手动点键盘麦克风。


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

缓存的挂载点是 `TranslatorFactory.current()` —— 各翻译调用点（无障碍读屏 /
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
├─ 🎤 Whisper 语音识别（语音翻译用）
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
├── scripts/
│   ├── gen-keystore.sh              # 生成 release 签名密钥（v1.18.0）
│   ├── inject-edge-to-edge.py       # 批量为 Activity 注入 edge-to-edge（v1.18.0）
│   ├── extract-strings.py           # 布局文案 → strings.xml（v1.18.0）
│   ├── extract-strings-kotlin.py    # Kotlin 文案 → strings.xml（v1.18.0）
│   └── verify-strings.py            # 文案抽离自检（v1.18.0）
├── jni/
│   ├── build-android.sh             # NDK 交叉编译 libllama-android.so（v1.18.0）
│   ├── llama_jni.cpp
│   ├── llama_context_wrapper.{cpp,h}
│   └── harness.cpp
├── .github/workflows/android.yml    # CI：签名 + 混淆 + 产物校验（v1.18.0）
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/hunter/screentranslator/
        │   ├── App.kt                      # Application 入口
        │   ├── api/                        # 翻译引擎层（接口 + 工厂 + 各实现）
        │   │   ├── Translator.kt            # 翻译接口 + 语言表
        │   │   ├── TranslatorFactory.kt     # 引擎工厂
        │   │   ├── DeepSeekTranslator.kt    # DeepSeek 实现
        │   │   └── WhisperClient.kt         # 语音识别（OpenAI 兼容 /v1/audio/transcriptions）
        │   ├── overlay/                    # 悬浮球 / 结果面板 / 框选
        │   ├── service/                    # 无障碍 / 悬浮窗 / 实时翻译服务及音频分段器
        │   ├── ui/                         # 功能与设置页面
        │   └── util/
        │       ├── Prefs.kt                 # 偏好设置封装
        │       ├── SecretStore.kt           # API Key 的 AndroidKeyStore 加密存储（v1.18.0）
        │       ├── CrashLog.kt              # 本地崩溃记录（v1.18.0）
        │       ├── EdgeToEdge.kt            # Android 15 edge-to-edge 适配（v1.18.0）
        │       └── WavUtils.kt              # PCM→WAV 打包
        └── res/
            ├── layout/                      # 13 个布局（文案已全部走 @string）
            ├── drawable/                    # 悬浮窗背景、应用图标
            ├── values/{strings,colors,themes,ids}.xml
            ├── values-night/{colors,themes}.xml   # 暗色模式
            ├── mipmap-anydpi-v26/           # 自适应图标
            └── xml/
                ├── accessibility_service_config.xml
                ├── backup_rules.xml         # 排除密钥文件的备份规则（v1.18.0）
                └── data_extraction_rules.xml
```

## 使用步骤

> 动手前先看上面的 **⚠️ 使用前必读** —— 本 App 不含任何密钥，
> 想零成本先跑通就切「必应网页版」，别直接照下面第 3 步填 DeepSeek。

1. 用 Android Studio 打开 `screen-translator/` 目录，等 Gradle 同步完成
2. 连接手机（开启 USB 调试）或启动模拟器，点击 Run
3. 在 App 内配置引擎（二选一）：
   - **零成本**：设置 → 🌐 翻译引擎 → 选「必应网页版」→ 保存
   - **正式**：选 DeepSeek 等引擎，填对应 API Key（入口见上表）
   - 无论哪种，再选好**目标语言**（默认中文）
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
| `SYSTEM_ALERT_WINDOW` | 显示翻译悬浮窗 |
| `BIND_ACCESSIBILITY_SERVICE` | 读取屏幕文字 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 保活悬浮窗服务 |
| `RECORD_AUDIO` | 语音翻译的系统听写和 Whisper 收音 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 实时屏幕翻译的画面采集 |
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

> v1.18.0 起，**release 构建必须提供正式签名，缺签名会直接失败**（原因见 `FIXES-1.18.0.md` §1）。
> 只想装到手机上试：用 `./gradlew assembleDebug`，不受这条限制。

### 1. 准备签名密钥

本仓库**不含签名密钥** —— 否则任何人 clone 下来都能签出「可覆盖安装到你手机上」的更新。

```bash
bash scripts/gen-keystore.sh
```

脚本会生成 `release.keystore`（RSA 4096 / PKCS12 / 有效期 10000 天）、
写好 `keystore.properties`、并打印证书指纹。它**不会覆盖已存在的密钥**。
**务必备份这把密钥和它的口令**：丢了就再也无法给已安装的用户推送更新。

<details>
<summary>不想用脚本，手动生成也可以</summary>

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias screentranslator \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```
</details>

### 2. 配置签名口令

口令**不写入源码**，按下面的顺序查找（命中即用）：

1. 环境变量 `SCREEN_TRANSLATOR_STORE_PASSWORD` / `SCREEN_TRANSLATOR_KEY_PASSWORD`
2. `keystore.properties`（推荐，已被 `.gitignore` 排除）
3. `local.properties` 里的 `storePassword` / `keyPassword`

`keystore.properties` 内容：

```properties
storeFile=release.keystore
storePassword=你的keystore口令
keyAlias=screentranslator
keyPassword=你的key口令
```

或改用环境变量：

```bash
export SCREEN_TRANSLATOR_STORE_PASSWORD=...
export SCREEN_TRANSLATOR_KEY_PASSWORD=...
```

**三种方式都没有时，`assembleRelease` 会直接报错中止**，不会退回调试证书。
（v1.17.0 的行为是静默改用 Android 调试证书签名 —— 那种包既上不了架，
又因为调试密钥全网公开而可以被同包名的恶意包冒名覆盖安装。
"悄悄降级"比"构建失败"危险得多，所以改成显式失败。）

### 3. 构建

```bash
./gradlew assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 产物: app/build/outputs/apk/release/app-release.apk
```

环境要求：**JDK 17**、Android SDK **Platform 35**、**Build-Tools 35.0.0**、**AGP 8.6.0+**。

release 构建会开启 R8 混淆 + 资源收缩，产出三样东西：

| 产物 | 用途 |
|---|---|
| `app-release.apk` | 安装包（v2 + v3 签名，无 v1） |
| `mapping.txt` | 混淆映射表，**归档保存**，用来还原线上崩溃堆栈 |
| `output-metadata.json` | AGP 生成的产物元数据 |

Gradle 仓库已配置阿里云镜像优先（`settings.gradle.kts`），国内网络可直接构建。

### 4. 重新编译本地推理库（可选）

`libllama-android.so` 的产物已入库，正常构建**不需要**重编。
只有在改 `jni/` 下的源码、或要换 llama.cpp 版本时才需要：

```bash
ANDROID_NDK_HOME=/path/to/ndk bash jni/build-android.sh
```

脚本会在 `$HOME/.cache/llama.cpp-android` 下 clone 指定 commit 的 llama.cpp、
用 NDK 交叉编译，并跑 5 项验收检查（JNI 符号数 / 未解析符号 / 内核指令数 / 16KB 页对齐）。

## 安装到手机

APK 下载后直接安装（需允许"未知来源应用"）：
1. 传到手机（微信/QQ文件传输、USB、网盘均可）
2. 点击 APK → 允许安装
3. 打开 App → **首次启动会走一遍引导**，逐步把无障碍、悬浮窗、电池优化开好
4. 走引导第 ④ 步「配置翻译引擎」—— **这一步不能跳**：要么切「必应网页版」（免密钥），
   要么填任一家正式引擎的 Key。没配好会一直显示 ❌ 并写明缺什么。

## 隐私

- **API Key 加密落盘**：13 项密钥（各家 API Key / Secret / Token）经
  `AndroidKeyStore` 的 AES-GCM 加密后存放，密钥本身由系统 TEE 托管、不出安全硬件。
  旧版本留下的明文密钥在首次启动时自动迁移并抹除（`SecretStore.kt`）。
- **密钥不进备份**：`allowBackup` 保持开启（用户的历史与设置仍能被系统备份），
  但 `backup_rules.xml` / `data_extraction_rules.xml` 已把密钥文件
  同时排除在**云备份**和**设备间迁移**之外。
- **崩溃日志只在本机**：`CrashLog.kt` 把崩溃堆栈写到应用私有目录
  （最多留 5 份），**不联网、不上报**。是否发给开发者完全由用户在设置页手动决定。
- 屏幕文字仅在用户开启「自动翻译」时被收集，直接发往用户配置的翻译端点
- 本 App 不收集任何用户数据，无埋点、无广告、无自建服务器
