# 修复说明 · v1.7.0 → v1.7.1

本次只做**「最小档」**修复：3 个必现 bug + 2 个资源泄漏 + 3 个严重项。
**不做**协程/异常体系重构（`Call.await()` + 结构化错误），那属于跨 8 引擎的行为性改动，
建议单独一轮验证后再做。

---

## 修改清单

### 1. OkHttpClient 泄漏（新增 `api/HttpClients.kt`）
**问题**：每个引擎各自 `private val client = OkHttpClient.Builder()...build()`，而
`TranslatorFactory.current()` 每次翻译都新建引擎实例 → 每次翻译泄漏一个 ConnectionPool
+ 一个 Dispatcher 线程池。`WhisperClient` 最严重：它在构造函数里建 client，而
`VideoListenService` **每个音频分段**都新建一个。

**改法**：新增 `HttpClients` 单例，按用途分三池（`standard` / `llm` / `asr`），
并用 `newBuilder()` 派生以共享底层连接池与线程池。8 个引擎改为构造函数注入
（带默认值，`ClaudeTranslator()` 这类无参调用仍然可用）。

顺带补上缺失的 `callTimeout`：`readTimeout` 只是两次读之间的间隔，没有总时长上限时
慢速滴流的响应可以无限期挂着。

| 文件 | 池 |
|---|---|
| Google / Microsoft / DeepL / 百度 / 彩云 | `HttpClients.standard` |
| OpenAI 兼容 / Claude | `HttpClients.llm` |
| WhisperClient | `HttpClients.asr` |

---

### 2. 悬浮球拖到屏幕边缘 → 定点翻译永久失效
**问题 A（坐标）**：`OverlayBallView` 拖动时 `wmParams.x = (rawX - width/2)` 无钳制，
手指在左/上边缘松手得到负坐标，球被推出屏幕，下游以球心反查文字时拿到负值。

**问题 B（状态锁死，更严重）**：
```kotlin
if (translatingAt) return
translatingAt = true                    // ← 在 launch 之前置位
scope.launch { try { ... } finally { translatingAt = false } }
```
`finally` 只在协程体**开始执行后**才生效。若协程排队期间作用域被取消
（`onDestroy → scope.cancel()`），协程体根本不执行 → 标志永远停在 `true`
→ **定点翻译永久失效，用户无任何提示**。

**改法**：
- 新增 `clampToScreen()`，在拖动与改尺寸两处钳制坐标（用 API 30+ 的 `WindowMetrics`，
  低版本回退 `getRealMetrics`）
- `translatingAt` 改为 `AtomicBoolean` + `compareAndSet`（原子 check-and-set）
- 补 `catch`（原来只有 `finally`，异常会冒泡成未捕获协程异常并提示用户）
- 加 `job.invokeOnCompletion` 兜底：协程在开始执行前被取消时也复位标志

**边界验证**（7 个用例，全部球心落在屏幕内）：
```
正常中间   (500,1000) -> (500,1000)  ✔
左边缘     ( -40,1000) -> (  0,1000)  ✔
上边缘     (500, -60) -> (500,   0)  ✔
左上角     (-100,-100) -> (  0,   0)  ✔
右边缘     (1400,1000) -> (942,1000)  ✔
下边缘     (500,2500) -> (500,2262)  ✔
完全移出   (-9999,-9999) -> (0,  0)  ✔
```

---

### 3. 剪贴板监听器注销无效 → Service 永久泄漏
**问题**：
```kotlin
clipboard?.addPrimaryClipChangedListener { ... }   // 匿名 SAM，实例 A
cm.removePrimaryClipChangedListener { }            // 另一个匿名 SAM，实例 B
```
两个匿名实例 `equals` 永不相等 → 监听器永久驻留系统 `ClipboardManager` 并持有
Service 引用 → Service 泄漏。

**改法**：提成字段 `private val clipListener`，注册/注销用同一实例。

> ⚠️ **产品层面提醒**：`getPrimaryClip()` 在 Android 10+ 对后台应用（含无障碍服务）
> 被系统限制为返回 null。**「复制即翻译」这个开关实质已不可用**，属平台限制而非缺陷。
> 建议产品上确认：删除该开关，或保留但在 UI 标注"系统限制，可能无效"。

---

### 4. 必现 bug：baseUrl 带尾斜杠 → 404
**问题**：`OpenAICompatibleTranslator.buildChatEndpoint` 用 `baseUrl.trimEnd()` ——
Kotlin 的 `trimEnd()` **只去空白，不去 `/`**。用户填 `https://api.deepseek.com/`
得到 `https://api.deepseek.com//v1/chat/completions`（双斜杠）→ 网关 404。
默认值不带斜杠所以默认能用，**用户一改就崩**。

**改法**：`baseUrl.trim().trimEnd('/')`，并加空值校验。
（`WhisperClient` 原本就是 `trimEnd('/')`，两处规则现已统一。）

**验证**（7 个用例，含尾斜杠/前后空格/各类版本后缀，均无双斜杠）：全部通过。

---

### 5. 必现 bug：百度翻译漏映射 `es → spa`
**问题**：只映射了 `ja→jp`、`ko→kor`、`fr→fra`，`es` 被透传。而 `LANG_DISPLAY`
明确提供「Español (es)」选项 → 百度收到 `to=es`（非法代码）→ **必现失败**。

**改法**：改用白名单 `BAIDU_LANG` 覆盖 `LANG_DISPLAY` 全部 8 种语言，
未知语言**直接报错**而非透传（透传是这一族 bug 的根因）。

顺带修 `md5()`：`"%02x".format(it)` 对 `Byte` 依赖 Formatter 特殊处理（侥幸正确），
改成显式 `it.toInt() and 0xFF`，避免日后有人"修 warning"改成 `.toInt()` 后得到
`ffffffff`、签名全错且难排查。

---

### 6. `activeRoot()` 包名比较失效
**问题**：
```kotlin
w.root?.packageName != packageName
```
`packageName` 是 `CharSequence?`，与 `String` 用 `!=` 比较时**类型不等恒为 true** ——
这个过滤完全没生效，兜底路径可能选中自家悬浮窗窗口，读到"🌐 屏幕翻译"自己的文本。

**改法**：显式 `.toString()` 后比较（同文件 121/148 行本来就是这个写法），
并把 `w.root` 取出一次复用，避免重复走 Binder。

---

### 7. 签名口令移出源码
**问题**：`app/build.gradle.kts` 明文写死 `storePassword`/`keyPassword`，
`README.md` 也公开了同一口令 —— 配合已随仓库分发的 `release.keystore`，
**任何拿到仓库的人都能签出可覆盖安装的"正版更新"**。

**改法**：口令改从环境变量或 `local.properties` 读取（按优先级），
两者都没有时**仍可构建**（产出未签名 APK，不因缺口令而失败）。README 同步更新。

> APK 签名密钥**无法轮换**。正式发布前建议生成新 keystore。

---

## 未做的事（有意保留）

以下问题**已定位但本次未改**，建议下一轮单独处理：

| 项 | 原因 |
|---|---|
| `Call.await()` + 结构化异常 + 429/5xx 退避重试 | 跨 8 引擎的行为性改动，需独立验证 |
| `BaseHttpTranslator` 重构（781→420 行） | 大重构，风险高 |
| API Key 改加密存储（`EncryptedSharedPreferences`） | 涉及数据迁移 |
| `allowBackup="true"` + 备份排除规则 | 需确认是否影响用户换机迁移 |
| `AudioSegmenter` 噪声底只降不升 | 影响切句，需真机长测 |
| 事件洪泛节流（`onAccessibilityEvent` 无包名过滤） | 需真机验证节流参数 |
| DFS 深度 60 → 30 | 需真机确认不丢文字 |
| Moshi 依赖白引 / `SOURCE_AUTO` 死代码 | 清理项 |

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

> 本次**未在本机编译验证** —— 该设备无 JDK / Android SDK / Gradle，
> 且可用内存仅约 200 MB（Gradle daemon 需 2~4 GB）。已完成的验证是
> 逐项静态检查 + 边界用例逻辑复刻测试（见上文各处"验证"小节）。
> 首次编译若有语法问题，最可能出现在引擎构造函数改动处。
