# 拍照翻译 · v1.9.5 → v1.11.0

新增功能：**对着实物/菜单/路牌拍照 → 本机识别文字 → 翻译**。

---

## 为什么走"端侧 OCR + 文本引擎"，而不是复用图片翻译的多模态直译

现有的「图片翻译」是把整张图交给多模态模型直译（`Translator.translateImage`）。拍照翻译**没有沿用这条路**，原因：

| 问题 | 多模态直译 | 端侧 OCR + 文本引擎 |
|---|---|---|
| 端点要求 | 必须支持视觉，且**自定义中转常常没实现** | 任意 11 家引擎都能用 |
| 费用 | 每次上传整张图，按图片计费 | 只传识别出的文字 |
| 隐私 | **照片要上传到服务端** | 照片不出设备，只有文字出网 |
| 延迟 | 视模型而定，通常秒级 | OCR 百毫秒级（本机） |

第 1 条是决定性的：如果用户的端点是自定义中转，多模态路径可能"装上了也用不了"。

**顺带修好一个已有缺陷**：图片翻译存历史时原文一直是空的（模型只回译文）。
走 OCR 后原文就是真实文字，历史条目更完整，「朗读原文」对这类条目也终于可用。

---

## 新增文件

| 文件 | 作用 |
|---|---|
| `util/OcrEngine.kt` | ML Kit 端侧 OCR 封装（挂起函数，失败返回空列表不抛异常） |
| `ui/CameraTranslateActivity.kt` | 拍照翻译页：CameraX 取景 + 快门 + 结果卡片（程序化建 UI，与 `ImageTranslateActivity` 同风格，单文件自包含） |

## 改动文件

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 加 CameraX 1.3.4 ×4、ML Kit 16.0.1 ×2；`versionCode 21→22`、`versionName 1.9.5→1.11.0` |
| `AndroidManifest.xml` | 加 `CAMERA` 权限、`uses-feature camera required=false`、注册 `CameraTranslateActivity` |
| `res/layout/activity_main.xml` | 主界面新增「📷 拍照翻译」入口 |
| `ui/MainActivity.kt` | 接线入口 |

---

## 三个关键技术点

**1. `ImageProxy.toBitmap()` 不会应用旋转角** ⚠️ 最容易踩

已核对 CameraX 1.3.4 源码：`ImageUtil.createBitmapFromImageProxy()` 只做
YUV/JPEG/RGBA 格式转换，**没有任何 rotation 处理**，且 `toBitmap()` 的 javadoc
也没提。所以必须自己按 `imageInfo.rotationDegrees` 转正 ——
否则竖屏拍摄拿到的是一张横图，OCR 会整片失效（而且失败得很安静）。
见 `CameraTranslateActivity.bitmapFrom()`。

**2. ML Kit 用 bundled 变体，运行时不需要 GMS**

`com.google.mlkit:text-recognition*` 把模型打进 APK；`play-services-mlkit-*` 变体
则依赖 GMS 下载模型。本项目一贯照顾无 GMS 的国产 ROM（见 v1.7.0 的 Whisper 引擎），
所以选 bundled。代价是体积（中文模型约 4~8MB）。
中文识别器**同时支持拉丁字母与数字**，中英混排只需一个模型。

**3. 相机位图与 MediaProjection 无关**

这条路完全不碰截屏授权，因此没有"Android 14 起每次会话都要重新授权"的问题，
也不需要无障碍截图能力 —— 比"全屏 OCR 兜底"风险低得多。

---

## 验证

装新包后：主界面 → 「📷 拍照翻译」→ 授权相机 → 对准一段外文 → 按「拍摄并翻译」。

预期：
1. 拍完立刻出「识别文字中（本机完成，不联网）」，随后出原文 + 译文
2. **原文是真实文字**（这是与图片翻译的关键差别）
3. 历史里出现「📷 拍照翻译」条目，且**有原文**
4. 飞行模式下：识别仍能成功，只有翻译那一步会失败 —— 这恰好证明识别是本机的

若识别为空，会提示"靠近一点、避开反光、让文字占满取景框"，而不是静默无反应。

---

## 已知边界

- **只装了中文+拉丁模型**：日文/韩文暂不识别。要加就再加
  `com.google.mlkit:text-recognition-japanese` / `-korean` 并在 `OcrEngine` 里按语言选 client。
- **不做实时连续翻译**：按快门拍一张翻一张。逐帧连续 OCR + 翻译会持续消耗 API 额度，
  留作后续可选项。
- APK 体积会从 ~6MB 增到 ~15MB 量级（CameraX + 两个 OCR 模型）。
