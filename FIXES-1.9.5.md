# 语速/音调被"保存"改写的修复 · v1.9.4 → v1.9.5

修一个 bug：**语速滑块设成 1.0×，一点「保存」实际就变成 1.5×，念得飞快，而标签仍显示 1.0×。**

---

## 症状

用户反馈："用 TTS 时语速不稳定，有时会很快，但我速度设的是 1。"

---

## 根因：同一个 SeekBar 有两套互不相同的换算

`seekTtsRate` / `seekTtsPitch` 都是 `android:max="15"`、min=0，
正确映射是 `rate = (progress + 5) / 10`（0.5× ～ 2.0×）。

但两处换算写法不一致：

| 位置 | 代码 | progress=5 的结果 |
|---|---|---|
| 拖动监听 `onProgressChanged` | `(p + 5) / 10f` | **1.0** ✓ |
| 保存按钮 `btnSave` | `(progress + 10) / 10f` | **1.5** ✗ |

于是：

1. 拖动滑块到 1.0× → 监听写入 `ttsRate = 1.0`，标签显示 "1.0×" → 朗读正常
2. 点「保存」 → `ttsRate = (5+10)/10 = 1.5` → 实际快 50%，
   而标签**不会刷新**，仍显示 1.0× —— 用户看到的就是"设的 1 却念得飞快"

### 它会自我锁死

保存后存的是 1.5，下次进入设置页反向计算出 `progress = 1.5*10 - 5 = 10`（显示 1.5×）；
用户拖回 1.0 再保存，又被改写成 1.5 —— **1.0 永远存不进去**。

音调 `ttsPitch` 是同一处、同一个 bug。

---

## 改动

`ui/MainActivity.kt` 的 `btnSave` 回调，两行：

```kotlin
// 修前
App.prefs.ttsRate  = (b.seekTtsRate.progress  + 10) / 10f
App.prefs.ttsPitch = (b.seekTtsPitch.progress + 10) / 10f
// 修后（与拖动监听、与 progress = rate*10 - 5 三者自洽）
App.prefs.ttsRate  = (b.seekTtsRate.progress  + 5) / 10f
App.prefs.ttsPitch = (b.seekTtsPitch.progress + 5) / 10f
```

`app/build.gradle.kts`：`versionCode 20 → 21`、`versionName "1.9.4" → "1.9.5"`。

> 未改动 `Speaker.applyTuning()` 等朗读路径 —— 每句朗读前都会重读偏好并 `setSpeechRate`，
> 逻辑本身没问题；出问题的只有"保存时把偏好写错"这一步。

---

## 验证

装新包后：把语速拖到 **1.0×** → 点「保存」→ 再念，应保持正常语速；
退出重进设置页，滑块与标签应仍是 **1.0×**（修复前会被改成 1.5×）。

---

## 影响面

- 只改 2 行数值换算，不涉及引擎、语言、队列任何逻辑。
- 旧版本已把偏好写成 1.5 的用户，装新版后首次进设置页会看到 1.5×，
  拖回 1.0× 并保存即可正常存住（修复前存不住）。
