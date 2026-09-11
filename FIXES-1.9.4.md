# 语音识别误报修复 · v1.9.3 → v1.9.4

修一个 bug：**打开「语音翻译」页立刻弹「此设备没有系统语音服务」，但手机其实装了识别引擎。**

改动只有一处，在 `AndroidManifest.xml` 的 `<queries>` 里补一行 —— 但是**必需的一行**。

---

## 症状

进入「语音翻译」（`VoiceTranslateActivity.onCreate` → `refreshEngineUi()`）时：

```
此设备没有系统语音服务
语音输入默认用系统自带识别（Google/厂商引擎），这台设备没有。
```

手机明明有语音服务（Google 语音服务 / 厂商识别引擎），但系统模式永远不可用，
只能被逼着切到 Whisper 引擎。

---

## 根因：包可见性过滤，v1.9.2 只修了一半

`SpeechRecognizer.isRecognitionAvailable()` 的内部实现就是一次包查询：

```java
// AOSP: android.speech.SpeechRecognizer
public static boolean isRecognitionAvailable(final Context context) {
    final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
            new Intent(RecognitionService.SERVICE_INTERFACE), 0);
    return list != null && list.size() != 0;
}
```

而 `RecognitionService.SERVICE_INTERFACE == "android.speech.RecognitionService"`。

本项目 `targetSdk = 34`（≥ 30），受 Android 11+ **包可见性过滤**约束：
`queryIntentServices()` 默认只能看到自己和自己声明过的包。识别引擎是**别的包**，
没声明就被过滤掉 → 列表为空 → `isRecognitionAvailable()` **恒返回 false**。

v1.9.2 修的是同一类问题的 **TTS 那一半**（`<queries>` 里加了 `TTS_SERVICE`），
识别这一半当时漏了，于是朗读好了、语音输入照旧误报。

### 影响不止那句误报

过滤不只影响判断，还会让 `SpeechRecognizer` **绑定不到识别服务**：
`createSpeechRecognizer()` 内部同样靠这次查询找服务再 bind。

所以这是硬阻塞 —— **只改判断逻辑（比如"探测失败也放行"）没用**，
必须把声明补上，系统才允许我们看到并绑定那个服务。

参考 <https://developer.android.com/training/package-visibility>

---

## 改动

`app/src/main/AndroidManifest.xml`：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
    <!-- v1.9.4 新增 -->
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

`app/build.gradle.kts`：`versionCode 19 → 20`、`versionName "1.9.3" → "1.9.4"`。

> 没有动任何 Kotlin 逻辑。判断与降级路径（无识别服务时引导切 Whisper）保持原样，
> 只是让它**在一台真有识别引擎的手机上答对**。

---

## 验证

装新包后进「语音翻译」，预期：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 手机有识别引擎 | 弹「没有系统语音服务」 | 不弹，直接可听写 |
| 手机确实没识别引擎 | 弹框引导切 Whisper | 不变（仍弹框，行为正确） |

命令行旁证（看系统里到底有没有识别服务）：

```bash
adb shell cmd package query-services -a android.speech.RecognitionService
# 有输出（如 com.google.android.googlequicksearchbox/...RecognitionService）即证明手机有
```

---

## 影响面

- 只影响 `AndroidManifest.xml` 的合并结果，新增一个 `<queries>` 条目；
  不申请任何新权限，不改变运行时行为。
- 顺带确认：`Speaker.installedEngines()` 用的 `TTS_SERVICE` 查询已在 v1.9.2 声明，无需再动。
