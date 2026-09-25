# v1.26.0 / v1.27.0 — 发布前的"别人能不能用"改造

承接用户两个问题：

> 如果把这个 app 发 github，那其他人下载后能语音翻译吗

> 你可能没给出什么提示，别人也不知道怎么弄

第一问的答案是**不能开箱即用**，第二问指出的是"不能"之后**没人告诉他怎么办**。
两版合起来解决同一件事：**把"要用之前得先做什么"从隐性知识变成界面上看得见的话**。

---

# 一、先回答：为什么别人下载后不能直接用

语音翻译不是"一个功能"，是**两段各自需要凭据的链路**拼起来的：

| 环节 | 干什么 | 免密钥的可能 |
|---|---|---|
| ① **听写（ASR）** | 把你说的话变成文字 | 手机系统识别（零配置）／自建 Whisper（Key 可留空） |
| ② **翻译** | 把文字变成目标语言 | **只有「必应网页版」和本地模型** |

App 原先的默认组合是「**系统听写 + DeepSeek**」。而 DeepSeek 必须填 Key ——
于是别人 clone 下来装上，`shared_prefs` 里没有任何密钥，
**语音能识别、译文永远空白**，日志里反复出现：

```
行翻译失败: 未配置 DeepSeek API Key
```

这不是 bug，是"不把作者的额度烧给所有下载者"的必然结果。
但**用户不可能自己推导出这一点** —— 他看到的现象是"语音翻译坏了"。

> ⚠️ 绝不能把作者自己的 Key 打包进仓库。APK 里的字符串可以被 `apktool` 直接取出，
> 一个公开仓库配上公开 Key，等于把账单交给整个互联网。
> 所以唯一的正确做法是**把它讲清楚**，并给出免密钥的替代路径。

---

# 二、v1.26.0：把判据收敛到一处 + 在正确的时机拦住

## 2.1 引擎"配没配好"原来散落成 12 个分支

`OnboardingActivity.anyEngineKeyFilled()` 里是一段按引擎枚举的 `when`，
每加一家引擎就要改一处，且**别的页面要用同一判断时只能复制**。
复制两份判据必然漂移 —— 引导页说"配好了"，语音页说"没配好"是最坏的结果。

收敛为 `api/Translator.kt` 里的单一入口：

```kotlin
sealed interface EngineReadiness {
    data object Ready : EngineReadiness
    /** @param reason 给用户看的中文说明，直接拼进提示文案 */
    data class NotReady(val reason: String) : EngineReadiness
}

fun engineReadiness(
    engine: TranslationEngine,
    valueOf: (String) -> String,     // 取值器，见下
): EngineReadiness = when (engine) {
    TranslationEngine.BING_WEB -> EngineReadiness.Ready   // 免密钥
    TranslationEngine.HYMT_LOCAL -> EngineReadiness.Ready // 免密钥（另判模型文件）
    TranslationEngine.DEEPSEEK ->
        if (valueOf("api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 DeepSeek 的 API Key")
    // …其余 10 家同理
}
```

**为什么取值器是参数而不是直接读 `App.prefs`**：判据要能在 `OnboardingActivity`
（读加密存储）和纯逻辑测试里复用，把 IO 留在调用侧。

配套给 `Prefs` 加了 `fun raw(key: String)`：

```kotlin
fun raw(key: String): String = if (key in SENSITIVE_KEYS) getSecret(key) else sp.getString(key, "") ?: ""
```

**敏感键必须走 `getSecret`**（`AndroidKeyStore` 解密），走 `sp.getString` 读到的
是加密后的密文，非空但不是密钥 —— 用它判空会**恒为真**，
于是"没配 Key"被误判成"配好了"。这是本类改动里最容易写错的一处。

## 2.2 语音页：**开录之前**就拦住

原来没有预检，用户会走完整个流程 —— 授权麦克风、说一整句话、等识别返回，
最后在 `translate()` 里收到「翻译失败：未配置 DeepSeek API Key」。

代价不只是浪费时间，还有**归因错误**：错误出现在"译文"栏里，
看起来像"识别失败"，用户会去折腾麦克风权限，方向完全错。

```kotlin
private fun reallyStart() {
    val engine = TranslationEngine.fromKey(App.prefs.engine)
    if (engineReadiness(engine) { App.prefs.raw(it) } is EngineReadiness.NotReady) {
        warnEngineNotReady(engine)
        return          // ← 关键：拦住，不进 startSystem/startWhisper
    }
    if (useWhisper) startWhisper() else startSystem()
}
```

## 2.3 提示条要能同时报**两种**故障

语音页的故障有两类，**原因和修法完全不同**：

| 故障 | 现象 | 修法 |
|---|---|---|
| 引擎没配 | 能识别、译文空 | 配密钥 / 切必应 |
| 听写不可用 | 点开始没反应 | 装语音服务 / 下语言包 / 切 Whisper |

原来只报第二种。配了引擎却被叫去装语音服务、或反过来，都真实发生过。
现在 `refreshEngineUi()` 同时判两者，**优先级先引擎**（更容易修、且影响所有翻译功能），
并让同一个「处理」按钮按 `SpeechFix` 枚举切动作：

```kotlin
private enum class SpeechFix { SPEECH, ENGINE }
private var speechFixAction = SpeechFix.SPEECH
// 点击时：ENGINE → 跳引擎设置；SPEECH → showSpeechHelp()
```

---

# 三、v1.27.0：用户说"没有提示"，问题出在信息的位置

## 3.1 诊断原文对，但**选项是裸标题**

用户原话：「你可能没给出什么提示，别人也不知道怎么弄」。

看原来的 `showSpeechHelp`，「处理」弹出来的是一列**没有说明的选项**：

```
下载识别语言包
安装 Google 语音服务
打开系统语音设置
改用 Whisper 引擎
复制诊断信息
```

问题不是信息少，而是**位置错了**：
- 诊断详情（识别器几家、语言包有没有）全塞在对话框正文，又长又技术；
- 选项只有名字，不说"能解决什么、代价是什么"。

用户读完仍然不知道点哪个，只能挨个试。**选择所需的信息没有放在选择旁边。**

## 3.2 拆成两层：一句人话结论 + 带副标题的方案

**第一层 —— 正文只留"我该点哪个"：**

```
系统听写服务不可用（手动进入故障处理）。

最省事：选第一项「用系统语音输入」，它绕开出问题的服务，
说完一句回本页就能看到译文 —— 代价是每句都要点一次。

下面是全部可选项，每项都写了能解决什么和代价：
————
本机检测详情：          ← 技术详情降级到后半段，仍然给，但不占首位
isRecognitionAvailable=false
RecognitionService=0 个
…
```

**第二层 —— 每个选项配一句副标题：**

```
① 用系统语音输入识别一次
   立刻可用。绕开出问题的听写服务，走手机输入法的语音功能。
   代价：说完一句要手动回本页，不能连续听写。
② 改用 Whisper（不依赖系统服务）
   推荐长期方案。需要先在设置里配好语音识别地址；
   自建服务（faster-whisper / whisper.cpp）可以完全不填 Key。
③ 下载语音识别语言包
   根治 Google 语音服务这条路。语言包不在系统设置里，只能从这里跳去下载页。
```

**排序也改了**：`改用 Whisper` 提到第一位（没有系统听写通道时它排第一）。
理由是这个方法**要处理的场景本身就是"系统服务不可用"** ——
把最可能生效的那条放最前，比按知识分类排更贴近用户此刻的需求。

## 3.3 `showActionSheet` 加副标题能力

原实现每项就是一个 `MaterialButton`。`MaterialButton` 只吃单行文本，
塞两行要靠 HTML 且行距不可控，所以新增一条自绘分支：

```kotlin
val view: View = if (sub.isNullOrBlank()) {
    MaterialButton(...).apply { text = label }          // 无副标题，走原路
} else {
    LinearLayout(...).apply {                            // 有副标题，自绘卡片
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), resolveCtxColor(colorOutlineVariant))
            setColor(resolveCtxColor(android.R.color.background))
        }
        addView(TextView(...).apply { text = label; textSize = 14f })
        addView(TextView(...).apply { text = sub;  textSize = 12f })   // 次要色
    }
}
```

配 `resolveCtxColor(attr)` 从主题取色 —— **硬编码色值在暗色模式下会变成"深底深字"**。

## 3.4 默认引擎改成**跟着本机能力**走

这是同一类问题里更根本的一处：默认值本身选错了。

原来 `Prefs.voiceEngine` 默认 `"system"`。但本 App 要对付的典型设备
恰恰是"ROM 把听写服务对三方 App 藏了"那一类（见 FIXES-1.20.0 §6.4），
**这类设备上 system 是一进页面就撞墙的**。

改为存空串表示"用户还没选过"，由界面按实际能力推断：

```kotlin
var voiceEngine: String                                     // Prefs.kt
    get() = sp.getString(KEY_VOICE_ENGINE, "") ?: ""
    set(value) = sp.edit().putString(KEY_VOICE_ENGINE, value).apply()

val voiceEngineChosen: Boolean get() = voiceEngine.isNotBlank()
```

```kotlin
useWhisper = if (App.prefs.voiceEngineChosen) {              // VoiceTranslateActivity
    App.prefs.voiceEngine == "whisper"
} else {
    !hasSystemRecognizer()      // ← 系统不可用就直接给 Whisper，不让人白撞一次
}
```

**留意 `Prefs` 里其他字段不要学这个写法**：只有"默认值取决于运行时状态"的字段
才该用"空串 + 调用侧推断"。有确定默认值的字段（如 `targetLang = "zh"`）
仍然应该直接给默认值，否则那个字段的所有读取点都得处理空串。

## 3.5 每个卡点都给**一步到位**的出路

- **`warnEngineNotReady` 加了「改用必应网页版（免密钥）」**：一键切过去**并当场重新开始聆听**。
  用户说"翻译完全做不了"时，他要的是"让它赶紧能用"，
  而不是被导航到另一个设置页去研究。能一步做完就别做两步。
  （同时保留「去配置引擎」给想长期用正式引擎的人。）
- **Whisper 的"没配 Key"对话框加了「改用系统识别」**：用户点 Whisper 是听说它好用，
  但如果本机系统识别其实是好的，让他为了用语音翻译去申请 ASR 服务是绕远路。
  只在 `hasSystemRecognizer()` 为真时才出现这个按钮 —— 不给出走不通的选项。

---

# 四、README：把"使用前必读"放在最显眼处

新增段落置于标题正下方、所有功能表之前，讲清三件事：

1. **本 App 不含任何内置密钥**，翻译这一步在配置前一定失败，这不是 bug；
2. **为什么**：语音翻译 = 听写 + 翻译两段，各自独立；
3. **零成本最小路径**：引擎切「必应网页版」+ 识别用「手机系统」。
   同时**如实写出必应网页版的代价**（服务端随时可能改、只能翻文字不接受图片）。

另附 9 家引擎的申请入口表，以及 Whisper "Key 可留空"的说明。

`使用步骤` 与 `安装到手机` 两处也同步改了 —— 原来第 3 步直接写"填入 DeepSeek API Key"，
等于把所有新用户默认推向那条要花钱的路。

---

# 五、静态检查（v1.26.0 加了第 9 节）

`scripts/verify-sourcelang.py` **58 → 69 项**，新增 11 条盯住本次改动：

| 检查 | 防的是什么 |
|---|---|
| `EngineReadiness` 已定义 + `engineReadiness()` 覆盖全部引擎 | 新增引擎时漏接判据 |
| `Prefs.raw(key)` 走 `getSecret` | 用密文判空导致恒为真（§2.1 那个坑） |
| 引导页改用共享判据、**无 12 分支** | 判据又被复制回各页面 |
| 引导页文案含"不含任何内置密钥"/"必应网页版" | 文案被改回去、用户又看不到出路 |
| 语音页**开录前**预检 `warnEngineNotReady(engine)` | 预检被挪到翻译环节，用户白说一句话 |
| `SpeechFix.ENGINE` / `SpeechFix.SPEECH` 都在 | 「处理」按钮动作不再随故障切换 |
| 提示条含"还不能翻译：当前引擎" | 只报听写故障、不报引擎故障 |

```
verify-m3.py           8/8     通过
verify-strings.py      6/6     通过
verify-sourcelang.py   69/69   通过
```

---

# 六、构建

```bash
bash scripts/build.sh both
```

| 产物 | 大小 | 签名 |
|---|---|---|
| `app-debug.apk` | 28 MB | debug |
| `app-release.apk` | 23 MB | `CN=Screen Translator, OU=Release`，RSA 4096 |

release 签名经 `apksigner` 复核，证书 SHA-256
`94e41e4473eff732483f3aa00d796ffe92f50fcd457c0fb5cc81e2da0b6d1b09` ——
**不是 Android debug 证书**（v1.18.0 加的"禁止静默回退 debug 签名"守卫生效）。

版本：`versionCode 70` / `versionName 1.27.0`，`minSdk 26` / `targetSdk 35`。

---

# 七、真机验证（PLJ110 / Android 16 / arm64-v8a）

| 项目 | 结果 |
|---|---|
| 引擎没配时的提示条 | ✅ 显示「还不能翻译：当前引擎「DeepSeek（AI）」没有配置…」，按钮为「去配置」 |
| 点「开始聆听」 | ✅ **弹出预检对话框，未进入聆听状态**（`uiautomator` 中无「聆听」文本） |
| 预检对话框文案 | ✅ 「还没有填 DeepSeek 的 API Key。/ 语音翻译 =「听写」+「翻译」两步…」 |
| 「去配置引擎」跳转 | ✅ 落到 `EngineSettingsActivity` |
| **零密钥端到端** | ✅ **实测跑通** —— 说 `what's your name` → 原文 `what's your name`，译文 `你叫什么名字` |

最后一行是本次最有价值的验证：**引擎切「必应网页版」、识别用「手机系统」、
全程不填任何密钥，语音翻译真的能用** —— README 里承诺的"零成本路径"不是纸上推演。

## 一个测试方法上的坑（不是 App 的 bug）

用 `run-as` + `sed` 改 `shared_prefs` 时，**必须先 `am force-stop`**。
否则 App 进程里的 `SharedPreferences` 内存副本会在下一次 flush 时
**覆盖掉刚写进去的文件** —— 表现为"我明明改成了 deepseek，一重启又变回 bingweb"，
很容易误判成"App 自己在改偏好"。正确顺序：

```bash
adb shell am force-stop com.hunter.screentranslator        # 1. 先杀进程
adb push prefs.xml /data/local/tmp/prefs.xml               # 2. 再从本地推送
adb shell "run-as com.hunter.screentranslator cp /data/local/tmp/prefs.xml \
           /data/data/com.hunter.screentranslator/shared_prefs/screen_translator.xml"
adb shell am start -n com.hunter.screentranslator/.ui.MainActivity
```

另注意 `adb push` 在 Git Bash 下会因 MSYS 路径改写报
`cannot stat`，需要 `export MSYS_NO_PATHCONV=1` 并用绝对路径。

---

# 八、遗留

- **release 包未上真机跑一遍**（只验了签名与版本号）。debug 包已验证。
- 必应网页版是**不受控接口**，本次真机通了不代表长期可用；
  长期使用仍应引导用户配正式引擎。
- 完全离线语音（设备上直接跑 Whisper）仍缺原生库，未做（同 FIXES-1.20.0 §二的说明）。
