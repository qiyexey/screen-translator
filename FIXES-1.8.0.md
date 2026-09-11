# 版本说明 · v1.7.1 → v1.8.0

新增三个功能：**朗读译文**、**图片翻译**、**翻译历史**。全部为增量改动，未破坏既有功能。

代码规模：4196 行 → **5959 行**（+1763），Kotlin 文件 27 → 33。

---

## 一、🔊 朗读译文

用系统 TTS 引擎朗读译文，翻译结果面板上直接有按钮。

**新增/改动**
| 文件 | 说明 |
|---|---|
| `util/Speaker.kt` | **新增** TTS 单例封装 |
| `util/Prefs.kt` | 新增 `ttsAutoSpeak` / `ttsRate` / `ttsPitch` |
| `overlay/OverlayView.kt` | 标题行加 **🔊 朗读** + **👁 原文** 按钮；支持自动朗读 |
| `ui/MainActivity.kt` | 设置区新增朗读卡片（开关 / 语速 / 音调 / 试听） |
| `res/layout/activity_main.xml` | 对应布局区块 |
| `service/OverlayService.kt` | 服务销毁时停止朗读 |

**设计要点**
- **单例**：`TextToSpeech` 构造会绑定系统服务，悬浮窗反复新建会泄漏 TtsService 连接
- **异步就绪处理**：TTS 初始化是异步的，未就绪时 `speak()` 会**静默失败**。这里缓存首个请求，`onInit` 后自动补播
- **语言缺失如实告知**：系统没装中文语音包时，降级到默认语言**并上报错误**，不假装成功
- **自动朗读默认关**，且过滤「正在翻译…」「⚠️ ❌ 📍」等非译文内容
- **设置即时生效**：语速/音调拖动即写入 Prefs，不必等「保存」

---

## 二、🖼 图片翻译

截图 → 框选 → 交给多模态模型**直接识别并翻译**（不经 OCR）。

**新增/改动**
| 文件 | 说明 |
|---|---|
| `util/ScreenCapture.kt` | **新增** MediaProjection 抓帧 |
| `ui/ImageTranslateActivity.kt` | **新增** 授权 + 框选 + 裁切 + 翻译 |
| `api/Translator.kt` | 接口新增 `translateImage()` + `visionCapable` 标记 |
| `api/OpenAICompatibleTranslator.kt` | 实现 OpenAI 兼容多模态 |
| `api/ClaudeTranslator.kt` | 实现 Claude 多模态（**格式不同**） |
| `overlay/OverlayBallView.kt` | 新增三击手势（单击/双击/三击统一计数判定） |
| `service/OverlayService.kt` | 三击 → 打开图片翻译 |
| `res/layout/activity_main.xml` | 主界面入口按钮 |
| `AndroidManifest.xml` | 注册 Activity |

**为什么不用 OCR**：直接把图交给 VLM 一步出译文，识别率更高（能懂版式/上下文/手写），
且**不引入 Tesseract / ML Kit 等额外依赖**——ML Kit 依赖 Google Play 服务，
在无 GMS 的国产 ROM 上不可用，与本项目"不依赖 GMS"的定位冲突。

**授权模式：按需**。用户点击时才申请 `MediaProjection`，抓一帧后**立即 stop()**，
不常驻服务、不持续耗电。

> ⚠️ **平台限制**：Android 14 (API 34) 起 MediaProjection 授权**每次会话都需重新弹窗**，
> 系统不允许长期持有。这是平台约束，无法绕过。

**多模态请求的两个易错点**（照官方要求实现，否则会"静默失败"）：
1. `content` 必须是**数组**而非字符串 —— 传字符串时图片会被**静默忽略**，模型只基于提示词瞎编
2. 必须显式设 `max_tokens`，否则视觉请求的响应可能被截断

**Claude 与 OpenAI 格式不同，不可混用**：
```
OpenAI: {"type":"image_url","image_url":{"url":"data:..."}}
Claude: {"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}
```

### 实测：DeepSeek 支持图片输入

用你配置的 key 实测（2026-09-10，官方 `api.deepseek.com`）：

| 模型 | 结果 |
|---|---|
| `deepseek-v4-flash-vision-exp` | ✅ 正确读图 |
| `deepseek-v4-flash` | ✅ 正确读图 |
| `deepseek-chat` | ✅ 正确读图 |

验证方式用的是**无法靠猜的测试图**（黑白条纹数、条纹方向）。`deepseek-chat`
答出"纵向+4"，与生成图完全一致，证明**真的处理了像素**而非幻觉。

三个模型回显的 `model` 字段**均为 `deepseek-flash`**，说明服务端统一路由，
**模型名不决定视觉能力**。

> ⚠️ 但**自定义 baseUrl（中转/代理商）的能力本 App 无法验证**，取决于中转方实现。
> 因此图片翻译失败时会给出可操作的提示，而不是断言"不支持"。

---

## 三、📜 翻译历史

自动记录每次成功翻译，支持搜索 / 收藏 / 复制 / 朗读 / 删除 / 导出。

**新增/改动**
| 文件 | 说明 |
|---|---|
| `util/HistoryStore.kt` | **新增** JSON 存储 |
| `ui/HistoryActivity.kt` | **新增** 列表页 |
| `res/xml/file_paths.xml` | **新增** FileProvider 路径配置 |
| `service/ScreenReaderService.kt` | 在唯一汇聚点 `translateAndShow()` 落库 |
| `ui/ImageTranslateActivity.kt` | 图片翻译也落库 |
| `App.kt` | 初始化存储 |
| `AndroidManifest.xml` | 注册 Activity + FileProvider |
| `res/layout/activity_main.xml` | 入口按钮 |

**设计取舍**
- **JSON 文件而非 Room/SQLite**：纯追加 + 上限裁剪的简单结构，上限 500 条，
  引入 Room 要付注解处理器 + schema 迁移的代价，与小工具定位不符
- **单点落库**：`translateAndShow()` 是全部 8 个翻译入口（无障碍/划词/定点/框选/输入/语音/视频/剪贴板）的
  唯一汇聚点，在这里写一次即可全覆盖
- **写入串行化**：多入口可能并发落库，用单线程 executor 避免并发覆盖
- **原子写盘**：先写临时文件再 rename，避免写一半崩溃损坏历史文件
- **裁剪保留收藏**：超出上限时从尾部移除**未收藏**项
- **id 用自增计数器**：纯时间戳在同一毫秒内会撞 id，而 id 是删除/收藏的依据，撞了会误删
- **过滤非译文**：占位符与错误提示不入库
- **导出用 FileProvider**：不能用 `file://` URI（Android 7+ 会抛 FileUriExposedException）

---

## 如何构建

```bash
# 1) 配置签名口令（二选一）
#    a. local.properties
echo "storePassword=你的口令" >> local.properties
echo "keyPassword=你的口令"   >> local.properties
#    b. 环境变量
export SCREEN_TRANSLATOR_STORE_PASSWORD=...
export SCREEN_TRANSLATOR_KEY_PASSWORD=...

# 2) 构建
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

---

## ⚠️ 未编译验证

本次改动**未在本机编译**——该设备无 JDK / Android SDK / Gradle，可用内存仅约 200MB
（Gradle daemon 需 2~4 GB）。

已完成的验证：
- 全部 Kotlin 括号配平、全部 XML 合法
- MainActivity 引用的 69 个控件 id 与布局**逐一配对**
- 7 个 Activity 全部已在 Manifest 注册
- 所有跨文件 API 调用（`translateImage` / `captureOnce` / `Speaker.*` / `HistoryStore.*`）确认存在
- 三击手势状态机、图片翻译裁切边界、TTS 语言映射的逻辑复核

**首次编译若报错，最可能出现在**：新增的三个 Activity 的 Android API 调用细节
（`MediaProjection` / `ImageReader` 的参数），以及 `Speaker.kt` 的 TTS 回调签名。

## 已知限制（如实说明）

1. **图片翻译需联网**，且依赖所选模型的多模态能力（中转商可能不支持）
2. **Android 14+ 每次图片翻译都要点一次系统授权框**（平台限制）
3. **TTS 需要系统语音引擎**，部分精简 ROM 可能未内置中文语音包（会提示，不静默失败）
4. **历史保存在应用私有目录**，卸载即丢失；导出功能可备份为 txt
