#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.20.0 源语言贯通性静态校验。

本机没有 JDK / Android SDK，改完编译不了。而"源语言"这条链路横跨
接口签名 → 13 个实现 → 缓存键 → 13 个调用点 → 2 个下拉框，
任何一处漏改都不是编译错误（因为参数有默认值），而是**运行时静默失效**：
用户选了日语，某个引擎仍按自动识别走，译文错了却毫无提示。

所以这里把"必须成套出现"的东西逐条对账。

退出码 0 = 全部通过。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main")

GREEN = "OK  "
RED = "FAIL"

failures = []
checks = 0


def read(rel):
    with io.open(os.path.join(SRC, rel), encoding="utf-8") as f:
        return f.read()


def scan_balance(src):
    """
    逐字符剥离注释，返回"只剩代码"的文本。

    为什么不能直接用正则或 str.replace 去掉 // 和 /* */：
    字符串字面量里的 `//`（例如 URL "http://..."）会被误当成行注释，
    从那里往后整行都被吃掉，导致后面真正的代码"消失"——
    校验脚本于是给出假通过。这里按字符扫，维护 in_string / in_char /
    in_line_comment / in_block_comment 四个状态，只在代码态识别注释起始。

    与 scripts/verify-m3.py 里的 scan_balance 保持同一套写法。
    """
    out = []
    i, n = 0, len(src)
    in_line = in_block = in_str = in_char = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
            out.append(c)
        elif in_char:
            if c == "\\":
                i += 1
            elif c == "'":
                in_char = False
            out.append(c)
        else:
            if c == "/" and nxt == "/":
                in_line = True
                i += 1
            elif c == "/" and nxt == "*":
                in_block = True
                i += 1
            elif c == '"':
                in_str = True
                out.append(c)
            elif c == "'":
                in_char = True
                out.append(c)
            else:
                out.append(c)
        i += 1
    return "".join(out)


def read_code(rel):
    """只含代码、不含注释的版本 —— 用于"某标识符是否还存在"这类检查。"""
    return scan_balance(read(rel))


def check(label, cond, detail=""):
    global checks
    checks += 1
    if cond:
        print("%s %s" % (GREEN, label))
    else:
        print("%s %s%s" % (RED, label, ("  -> " + detail) if detail else ""))
        failures.append(label)


# ---------------------------------------------------------------- 1. 接口签名
translator = read("java/com/hunter/screentranslator/api/Translator.kt")

check(
    "Translator.translate 已接收 sourceLang",
    re.search(r"fun translate\(\s*text: String,\s*targetLang: String,\s*sourceLang: String", translator),
    "接口签名里找不到 sourceLang",
)
check(
    "Translator.translateImage 已接收 sourceLang（排在 hint 之后）",
    re.search(
        r"fun translateImage\([^)]*hint: String\? = null,\s*sourceLang: String = SOURCE_AUTO",
        translator,
        re.S,
    ),
    "translateImage 缺少 sourceLang 或参数顺序不对",
)
check("SOURCE_AUTO 已定义", 'const val SOURCE_AUTO = "auto"' in translator)

# ------------------------------------------------------ 2. 所有实现都改到签名
API_DIR = os.path.join(SRC, "java/com/hunter/screentranslator/api")
impls = []
for name in sorted(os.listdir(API_DIR)):
    if not name.endswith(".kt"):
        continue
    body = io.open(os.path.join(API_DIR, name), encoding="utf-8").read()
    for m in re.finditer(r"override suspend fun (translate|translateImage)\(", body):
        impls.append((name, m.start(), m.group(1)))

bad = []
for name, pos, fn in impls:
    body = io.open(os.path.join(API_DIR, name), encoding="utf-8").read()
    # 取到参数表结束的那个 ")" —— 用括号配平扫，别用正则，参数跨行会截错
    i = body.index("(", pos)
    depth, j = 0, i
    while j < len(body):
        if body[j] == "(":
            depth += 1
        elif body[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    params = body[i + 1: j]
    if "sourceLang" not in params:
        bad.append("%s::%s" % (name, fn))

check(
    "13 个 translate/translateImage 覆写全部带上 sourceLang",
    not bad,
    "漏改：" + ", ".join(bad),
)
print("       共发现 %d 个覆写（应为 13：11 个 translate + 2 个 translateImage）" % len(impls))
check("覆写数量符合预期（13）", len(impls) == 13, "实际 %d 个" % len(impls))

# ------------------------------------------------- 3. 缓存作用域已并入源语言
caching = read("java/com/hunter/screentranslator/api/CachingTranslator.kt")
check(
    "CachingTranslator 把源语言并入缓存作用域",
    "scopeKey(sourceLang)" in caching and "src=$sourceLang" in caching,
    "缓存键没带源语言 -> auto 下的旧译文会被显式源语言的请求命中",
)
check(
    "auto 时沿用旧作用域（升级后不产生冷缓存）",
    re.search(r'if \(sourceLang == SOURCE_AUTO\) scope else', caching),
)

# ----------------------------------------------------- 4. 各引擎真的用了它
engine_expect = {
    "BaiduTranslator.kt": [".add(\"from\", from)"],
    "BingWebTranslator.kt": [".add(\"fromLang\", from)", "AUTO_DETECT"],
    "CaiyunTranslator.kt": ['"${sourceLang}2$targetLang"'],
    "GoogleTranslator.kt": ['addQueryParameter("source", googleLangCode(sourceLang))'],
    "MicrosoftTranslator.kt": ['append("&from=")'],
    "DeepLTranslatorEngine.kt": ['.add("source_lang"'],
    "OpenAICompatibleTranslator.kt": ["sourceNameOf(sourceLang)", "SOURCE_AUTO_LABEL"],
    "ClaudeTranslator.kt": ["srcNoteOf(sourceLang)", "srcHint"],
    "HyMtLocalTranslator.kt": ["buildHyMtPrompt(text, targetName, sourceName)"],
}
for fname, needles in engine_expect.items():
    body = io.open(os.path.join(API_DIR, fname), encoding="utf-8").read()
    missing = [n for n in needles if n not in body]
    check("%s 已接入源语言" % fname, not missing, "缺少：" + ", ".join(missing))

# ---------------------------------------------------- 5. 调用点都传了值
missing_calls = []
call_files = [
    "java/com/hunter/screentranslator/service/LiveTranslateService.kt",
    "java/com/hunter/screentranslator/service/ScreenReaderService.kt",
    "java/com/hunter/screentranslator/ui/CameraTranslateActivity.kt",
    "java/com/hunter/screentranslator/ui/EngineSettingsActivity.kt",
    "java/com/hunter/screentranslator/ui/ImageTranslateActivity.kt",
    "java/com/hunter/screentranslator/ui/ProcessTextActivity.kt",
    "java/com/hunter/screentranslator/ui/TranslateInputActivity.kt",
    "java/com/hunter/screentranslator/ui/VoiceTranslateActivity.kt",
]
call_count = 0
for rel in call_files:
    body = read(rel)
    short = os.path.basename(rel)
    for m in re.finditer(r"\.(translate|translateImage)\(", body):
        # 只统计生产调用点：跳过 CachingTranslator 的 delegate 转发
        line_start = body.rfind("\n", 0, m.start()) + 1
        line = body[line_start: m.start()]
        if "delegate" in line:
            continue
        call_count += 1
        # 向后取到本次调用的收尾括号，检查实参里有没有源语言
        i = m.end() - 1
        depth, j = 0, i
        while j < len(body):
            if body[j] == "(":
                depth += 1
            elif body[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        args = body[i + 1: j]
        if "sourceLang" not in args and "SOURCE_AUTO" not in args:
            ln = body.count("\n", 0, m.start()) + 1
            missing_calls.append("%s:%d %s" % (short, ln, m.group(1)))

check(
    "全部生产调用点都显式传入源语言",
    not missing_calls,
    "漏传：" + "; ".join(missing_calls),
)
print("       共发现 %d 个生产调用点（应为 13）" % call_count)
# EngineSettingsActivity 有两个调用点：测试翻译和 Hy-MT 引擎自检。
check("调用点数量符合预期（13）", call_count == 13, "实际 %d 个" % call_count)

# -------------------------------------------------------- 6. Prefs 与 UI
prefs = read("java/com/hunter/screentranslator/util/Prefs.kt")
check("Prefs.sourceLang 已定义且默认 SOURCE_AUTO",
      re.search(r"var sourceLang: String\s*\n\s*get\(\) = sp\.getString\(KEY_SOURCE_LANG, SOURCE_AUTO\)", prefs))
check("KEY_SOURCE_LANG 常量已定义", 'KEY_SOURCE_LANG = "source_lang"' in prefs)

lay = read("res/layout/activity_engine_settings.xml")
check("引擎设置页有 spinnerSource 下拉框", 'android:id="@+id/spinnerSource"' in lay)
check("spinnerSource 使用 M3 DropdownLayout",
      re.search(r'DropdownLayout[\s\S]{0,400}?@\+id/spinnerSource', lay))

eng = read("java/com/hunter/screentranslator/ui/EngineSettingsActivity.kt")
check("EngineSettingsActivity 绑定 spinnerSource",
      "b.spinnerSource.setSimpleItems" in eng and "App.prefs.sourceLang = srcCodes[selPos(b.spinnerSource)]" in eng)

voice_lay = read("res/layout/activity_voice_translate.xml")
# v1.24.0：语言下拉框**回归**（这是绕了三圈后的最终结论 —— 见
# VoiceTranslateActivity 里 listenLangs 上方长注释：语音引擎必须知道语言，
# 否则按系统语言硬解，日语会出罗马音 konichiwa 而不是 こんにちは）。
check("语音页有识别语言下拉框 spinnerLang（v1.24.0 回归）",
      'android:id="@+id/spinnerLang"' in voice_lay and "btnLang" not in voice_lay)
check("语音页下拉框用 M3 DropdownLayout",
      "DropdownLayout" in voice_lay)

voice = read("java/com/hunter/screentranslator/ui/VoiceTranslateActivity.kt")
voice_code = read_code("java/com/hunter/screentranslator/ui/VoiceTranslateActivity.kt")
check("语音页绑定 spinnerLang 并同步 langIndex",
      "b.spinnerLang.setSimpleItems" in voice and "langIndex = pos" in voice)
check("语音页记忆上次选择（App.prefs.voiceListenLang）",
      "App.prefs.voiceListenLang = listenLangs[pos]" in voice
      and "voiceListenLang" in voice)
# v1.24.0 核心断言：必须把语言下发给引擎，不能像 v1.23.0 那样完全不下发。
# 完全不下发 → 引擎按系统语言硬解 → 日语输出罗马音（真机实测 konichiwa）。
check("两处识别请求都下发 EXTRA_LANGUAGE（否则外语出罗马音）",
      voice_code.count("RecognizerIntent.EXTRA_LANGUAGE,") >= 2)
check("设备端与系统默认识别器使用各自的中文语言标签",
      "cmn-Hans-CN" in voice_code
      and "java.util.Locale.SIMPLIFIED_CHINESE.toLanguageTag()" in voice_code
      and "if (usingOnDeviceRecognizer) listenLangs[langIndex] else systemLanguageTag()" in voice_code)
recognizer_setup = voice_code.split("private fun ensureRecognizer(): Boolean", 1)[1].split("private fun startSystem()", 1)[0]
check("系统默认识别器优先，设备端只作兜底",
      recognizer_setup.index("SpeechRecognizer.createSpeechRecognizer(this)")
      < recognizer_setup.index("SpeechRecognizer.createOnDeviceSpeechRecognizer(this)"))
ready_callback = voice_code.split("override fun onReadyForSpeech", 1)[1].split("override fun onBeginningOfSpeech", 1)[0]
check("仅收到识别结果才清除网络错误计数，避免无限重连",
      "consecutiveErrors = 0" not in ready_callback)
check("不再使用 Android14 语言检测 API（实测需本地语言包，本机不可用）",
      "EXTRA_ENABLE_LANGUAGE_DETECTION" not in voice_code)
check("语音页已无 updateLangButton 残留（只看代码，注释里提到不算）",
      "updateLangButton" not in voice_code)

main = read("java/com/hunter/screentranslator/ui/MainActivity.kt")
check("主界面语言对已真正生效（refreshLangRow）",
      "refreshLangRow()" in main and "App.prefs.sourceLang = tgt" in main)

# -------------------------------------------------------- 7. 免 Key 语音输入
whisper = read("java/com/hunter/screentranslator/api/WhisperClient.kt")
whisper_code = read_code("java/com/hunter/screentranslator/api/WhisperClient.kt")
check("WhisperClient 不再硬性 require API Key（只看代码）",
      "require(apiKey.isNotBlank())" not in whisper_code)
check("WhisperClient 空 Key 时整个不发送 Authorization（而非空 Bearer）",
      re.search(r'if \(apiKey\.isNotBlank\(\)\) header\("Authorization"', whisper))

check("语音页 Key 缺失改为确认而非拦截",
      "reallyStartWhisper()" in voice and 'setPositiveButton("继续")' in voice)

# ------------------------------------------- 8. v1.25.0 译文原位覆盖（共用引擎）
#
# 这一节防的是"两个页面各写一份贴片逻辑，改了一个忘了另一个"——
# 那类问题只有真机上分别点过两个页面才能发现，所以必须在静态检查里对账。
engine = read("java/com/hunter/screentranslator/util/LineOverlayEngine.kt")
check("共享引擎 LineOverlayEngine 已存在",
      "class LineOverlayEngine" in engine)
check("引擎提供译文原位覆盖（showChip + 宽高都不小于原文框）",
      "fun showChip(" in engine and "原文被真正盖住" in engine)
check("引擎提供背景明暗自适应（isLightAround）",
      "fun isLightAround(" in engine)
check("引擎提供点选判定（pickLineAt）",
      "fun pickLineAt(" in engine)
check("引擎的坐标换算是 CENTER_CROP（max 缩放，与 FILL_CENTER 一致）",
      "maxOf(vw / bmp.width, vh / bmp.height)" in engine)
check("引擎提供逐行翻译调度（同文去重 + 并发上限）",
      "fun translateLines(" in engine and "Semaphore(MAX_PARALLEL)" in engine)
check("按住看原文的手势抽成了共享基类 PeekLineLayer",
      "abstract class PeekLineLayer" in engine and "PEEK_DELAY_MS" in engine)

# 两个页面都必须用共享引擎 —— 任何一个自己留着旧实现就是"将来必然漂移"
camera = read("java/com/hunter/screentranslator/ui/CameraTranslateActivity.kt")
img = read("java/com/hunter/screentranslator/ui/ImageTranslateActivity.kt")
check("拍照翻译已改用共享引擎", "LineOverlayEngine" in camera)
check("图片翻译已接入共享引擎", "LineOverlayEngine" in img and "ovEngine.translateLines" in img)
check("图片翻译的默认模式是「译文原位覆盖」",
      "MODE_OVERLAY = \"overlay\"" in img and "var mode = MODE_OVERLAY" in img)
check("图片翻译保留框选模式（可切换）",
      "MODE_REGION = \"region\"" in img and "toggleMode()" in img)
check("切换的两种模式都持久化（App.prefs.imageTranslateMode）",
      "App.prefs.imageTranslateMode = mode" in img and "App.prefs.imageTranslateMode" in img)

prefs = read("java/com/hunter/screentranslator/util/Prefs.kt")
check("Prefs.imageTranslateMode 已定义且默认 OVERLAY",
      'ImageTranslateMode.normalize(sp.getString(KEY_IMAGE_MODE, ImageTranslateMode.OVERLAY))' in prefs)
check("KEY_IMAGE_MODE 常量已定义", 'KEY_IMAGE_MODE = "image_translate_mode"' in prefs)
check("ImageTranslateMode.normalize 只认 region，其余回落 overlay",
      'if (v == REGION) REGION else OVERLAY' in prefs)

# 图片页的贴片几何必须与引擎同源：它用的是自己那套 CENTER_CROP 换算，
# 一旦改成 FIT_CENTER（min 缩放）贴片就会整体偏移一圈留白。
check("图片页框选/显示几何也是 CENTER_CROP（与引擎同源）",
      "maxOf(vw / bw, vh / bh)" in img and "ScaleType.CENTER_CROP" in img)

strings = read("res/values/strings.xml")
for sid in ["image_translate_mode_overlay", "image_translate_mode_region",
            "image_translate_t18", "image_translate_t19", "image_translate_t20"]:
    check("字符串 %s 已定义" % sid, ('name="%s"' % sid) in strings)
check("带参数的文案用位置参数（%1$d）而非裸 %d（多语言会错位）",
      '%1$d/%2$d 行' in strings and '%1$d 行失败' in strings)


# --------------------------- 9. v1.26.0 发布前预检：引擎未配置要"开录前"就拦
#
# 这一节防的是"说了半天话才被告知用不了"——那是用户视角最糟的失败模式。
# 判据必须**只有一份**（api 包里），引导页与语音页都调它；
# 两边各写一份 12 分支 when，新增引擎时必然漏掉一边。
translator_kt = read("java/com/hunter/screentranslator/api/Translator.kt")
check("共享判据 EngineReadiness 已定义",
      "sealed interface EngineReadiness" in translator_kt)
check("共享判据 engineReadiness() 覆盖全部引擎（按 key 取值器）",
      "fun engineReadiness(" in translator_kt and "valueOf(\"api_key\")" in translator_kt)
check("免密钥引擎用 keyless 属性统一表达（不再散落 if）",
      "val keyless: Boolean" in translator_kt and "this == BING_WEB || this == HYMT_LOCAL" in translator_kt)
check("免密钥引擎在判据里直接 Ready（选它不该被提示填密钥）",
      "TranslationEngine.BING_WEB -> EngineReadiness.Ready" in translator_kt)

prefs2 = read("java/com/hunter/screentranslator/util/Prefs.kt")
check("Prefs.raw(key) 已提供（判据的唯一取值入口）",
      "fun raw(key: String): String" in prefs2 and "getSecret(key)" in prefs2)
check("raw() 对敏感键走加密读取（否则与设置页读数不一致）",
      "if (key in SENSITIVE_KEYS) getSecret(key)" in prefs2)
check("Prefs.readiness() 统一检查凭据与本地模型完整性",
      "fun readiness(" in prefs2 and "engineReadiness(" in prefs2
      and "HyMtModelStore.status(" in prefs2 and "valueOf = ::raw" in prefs2)

onb = read("java/com/hunter/screentranslator/ui/OnboardingActivity.kt")
check("引导页改用共享判据（删掉自己的 12 分支 when）",
      "App.prefs.readiness(" in onb and "TranslationEngine.CAIYUN -> App.prefs.caiyunToken" not in onb)
check("引导页说明里点出「本 App 不含内置密钥」与免密钥出路",
      "不含任何内置密钥" in onb and "必应网页版" in onb)

voice_code2 = read_code("java/com/hunter/screentranslator/ui/VoiceTranslateActivity.kt")
check("语音页在**开录前**预检引擎（否则用户说完整句才看到失败）",
      "App.prefs.readiness(engine)" in voice_code2 and "warnEngineNotReady(engine)" in voice_code2)
check("语音页「处理」按钮随故障类型切换动作（引擎 / 听写）",
      "SpeechFix.ENGINE" in voice_code2 and "SpeechFix.SPEECH" in voice_code2
      and "EngineSettingsActivity::class.java" in voice_code2)
check("语音页提示条同时覆盖「引擎没配」与「听写不可用」两种故障",
      "还不能翻译：当前引擎" in voice_code2)

print("-" * 72)
if failures:
    print("%d 项未通过：" % len(failures))
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("全部 %d 项检查通过。源语言链路、免 Key 语音输入、译文原位覆盖、发布前引擎预检均已成套落地。" % checks)
sys.exit(0)
