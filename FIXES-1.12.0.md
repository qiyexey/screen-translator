# 界面瘦身与设置分层 · v1.11.0 → v1.12.0

把"什么都塞在一页"的主界面拆成 **主页（P1）→ 设置列表（P2）→ 各配置页（P3）** 三层。

---

## 改造前的量化问题

`activity_main.xml` **1423 行、74 个控件**，一张页面同时承担四件使用频率差一个数量级的事：

| 区块 | 行数 | 性质 |
|---|---|---|
| 权限状态卡片 | ~158 | 出问题才来看 |
| **翻译引擎与密钥** | **~890** | 低频、一次性配置 |
| 翻译触发方式 | ~150 | 偶尔调 |
| 朗读（TTS） | ~176 | 偶尔调 |
| 悬浮球外观 | ~157 | 纯偏好 |
| 语音识别 | ~76 | 低频 |
| 底部说明 | ~10 | 只读一次 |

其中「翻译引擎与密钥」一张卡就占了整页 **62%**（11 家引擎各带 Key 输入框 + 长段说明）。

---

## 改造后

**P1 主页 `activity_main.xml`：1423 行 → 240 行**

只回答两件事：
- **「用什么」**：语音 / 输入 / 听视频 / 图片 / 拍照 / 历史，六宫格
- **「能不能用」**：无障碍 + 悬浮窗 两个状态灯，点一下直接去权限页

**P2 设置 `SettingsActivity`：七个分组**

```
🌐 翻译引擎 · 语言 · 密钥    →  EngineSettingsActivity
⚡ 翻译触发方式              →  TriggerSettingsActivity
🔊 朗读译文（TTS）           →  TtsSettingsActivity
🎧 语音识别（听视频用）      →  AsrSettingsActivity
⚽ 悬浮球与面板外观          →  BallStyleActivity
🔋 权限与保活                →  PermissionActivity
ℹ️ 关于与用法                →  AboutActivity
```

**P3 各页** 均带「‹ 返回」，主题是 `NoActionBar`，不依赖系统标题栏。

---

## 两个实现上的关键决定

**1. 布局是"切块搬迁"，不是重画**

各 P3 页面的 XML 是从原 `activity_main.xml` 按行区间**原样搬过来的**（引擎页 = 原 L281-818，
触发页 = L820-973，朗读页 = L975-1150…），所以 Material3 样式、配色、间距全部原样保留，
不存在"重构后变丑"。11 家引擎的配置区仍整体保留，沿用**原有的显隐机制**一次只显示一组 ——
这样切引擎时已填的输入框不会丢内容。

**2. 各子页只写自己那一摊**

原来是一个「保存配置」按钮 `saveAllEngineConfigs()` 写全部字段（含 ASR）。拆页后每个页面
只写自己负责的偏好 —— 否则在朗读页点保存会把引擎页正在编辑的内容一起覆盖掉。
原 `saveAllEngineConfigs()` 里的 ASR 三个字段已移入 `AsrSettingsActivity`。

**顺带修掉一处隐性 bug**：朗读页的「试听 / 检测引擎」原来靠主页的 `spinnerTarget`
反查语言码，而那个下拉现在在引擎页 —— 已改为直接用 `App.prefs.targetLang`，语义等价。

---

## 文件清单

**新增布局（9）**：`activity_main`(重写) / `activity_settings` / `activity_engine_settings` /
`activity_trigger_settings` / `activity_tts_settings` / `activity_ball_style` /
`activity_asr_settings` / `activity_permission` / `activity_about`

**新增 Kotlin（9）**：`BaseActivity`（公共工具：状态色解析、无障碍判定、拉悬浮窗）+
`SettingsActivity` + 七个 P3 页

**改动**：`MainActivity`（668 行 → 93 行，只留入口与状态灯）、`AndroidManifest.xml`（注册 8 个新页面）

---

## 验证

1. 打开 App：应只看到 **状态卡 + 六个功能入口 + 一个设置按钮**，不再需要长滚动
2. 点「⚙️ 设置」→ 七个分组 → 逐个进入，每页只有该主题的配置
3. 引擎页：切换引擎下拉，配置区应随之切换；填 Key → 保存 → 测试翻译应正常
4. 各页返回后，主页状态灯（无障碍 / 悬浮窗）应正确刷新
5. 朗读页：语速拖到 1.0× 保存 → 退出重进应仍是 1.0×（v1.9.5 的修复在新页面上继续有效）
