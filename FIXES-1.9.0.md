# 界面重做说明 · v1.8.0 → v1.9.0

按 **Material Design 3** 规范重做界面：主题体系、语义配色、组件样式、夜间模式。
未改动任何功能逻辑 —— 本次纯视觉层。

---

## 诊断：为什么原来不好看

先看清问题，再动手。逐项对照代码后确认了四个根因：

| 问题 | 原状 | M3 规范 |
|---|---|---|
| **主题体系落后** | `Theme.MaterialComponents`（**M2**，2014 年体系） | `Theme.Material3.*` |
| **主色对比度不足** | `#4CAF50` 配白字仅 **2.78:1**（WCAG 需 ≥4.5） | primary 必须承载白字 |
| **无语义色角色** | 只有 6 个裸色值，同一色既当按钮又当状态色 | 角色制（primary/on-primary/surface…） |
| **夜间模式是坏的** | `values-night/themes.xml` 只换了主色，背景仍从浅色取 | 需要完整深色角色集 |

**最关键的一条**：`#4CAF50` 的对比度只有 2.78 —— 这就是旧界面「发飘」的直接原因。

---

## 一、配色：绿色系的 M3 化

保留品牌绿，但按 M3 的做法重新分配角色（这是保住绿色又不失质感的关键）：

```
原 #4CAF50  ──下沉──→  primary-container（浅绿底，配深绿字）
新增 #006D3B ──────→  primary（深绿，承载白字，对比 6.46:1 ✓）
```

对比度实测（WCAG 相对亮度公式计算）：

| 组合 | 对比度 | 判定 |
|---|---|---|
| 白字 on `#4CAF50`（旧） | **2.78** | ✗ 不达标 |
| 白字 on `#006D3B`（新 primary） | **6.46** | ✓ |
| `#00391F` 字 on `#A8F0C0` 容器（新） | **9.90** | ✓ |

**新增 37 个语义色**，白天/夜间各一套，覆盖：
`primary / on-primary / primary-container / secondary / tertiary / error / surface 家族（5 级）/ outline / outline-variant` 等。

> 旧色名（`bg_primary` / `accent` / …）保留为**别名**指向新角色，
> 这样 82 处布局引用无需逐个改写，改主题即全局生效 —— 降低了这次改动的风险面。

---

## 二、夜间模式：从「坏的」到完整可用

**修复的关键 bug**：`values-night/themes.xml` 原本 `parent="Theme.MaterialComponents..."`，
而白天主题已升级到 M3。**夜间主题会覆盖白天主题**，于是夜间模式实际跑在 M2 上 ——
本次升级会在夜间完全失效。已统一为 M3。

深色角色按 M3 规范给出：primary 在深色下**变浅**（`#8FE0B0`）以便在深底上可读并承载深字；
surface 家族整体下沉（`#111411` → `#333633`），仍以**色调**表达层级而非阴影。

状态栏也跟随主题：白天浅底深图标，夜间深底浅图标。

---

## 三、组件样式：M2 → M3

| 组件 | 改动 |
|---|---|
| 按钮 | `Widget.MaterialComponents.*` → M3 胶囊形（`cornerRadius=22dp`） |
| 输入框 | `TextInputLayout.OutlinedBox` → `Widget.Material3.TextInputLayout.OutlinedBox`（**43 处**） |
| 卡片 | 圆角 14dp → **12dp**（M3 corner-medium）；阴影 2dp → **0dp**（M3 用色调表达层级） |
| 新增 | M3 文字层级样式（TitleMedium / BodyMedium / BodySmall / LabelLarge） |
| 新增 | `dimens.xml`：M3 shape scale（4/8/12/16/28dp）+ 8dp 栅格间距 |

共迁移 **43 处** M2 样式引用到 M3，全项目已无 M2 残留。

---

## 四、悬浮窗与代码构建页面

- **翻译结果面板**：圆角 14→16dp，描边由白 25%→18%（M3 浮层用轻描边，原描边偏亮显得脏）
- **图片翻译页 / 历史页**（代码构建 UI）：原先把颜色写死在 Kotlin 里
  （`0xFF121212` 等 12 处）→ 改为从主题解析 M3 语义色，**现在会跟随明暗主题**

> 悬浮面板刻意**不跟随主题**：它覆盖在任意应用之上，跟随主题会在浅色 App 上失去对比度。

---

## 验证

- 全部 Kotlin 括号配平、全部 XML 合法
- **33 个颜色引用**在白天/夜间两套资源中**均已定义**（夜间不会掉回默认色）
- 主题引用的 color 角色全部存在；dimens / 自定义 style 引用全部有定义
- 布局中**零硬编码色**（82 处全走 `@color/` 语义名）
- 排除注释后，全项目**无真实 M2 引用**
- 备份文件已移出 `res/`（`.bak` 放在 res 下会被 AAPT 当资源处理而报错）

## ⚠️ 仍未编译验证

本机无 JDK / SDK / Gradle，**未做真机编译与视觉确认**。以上均为静态校验。

**首次编译若报错，最可能在**：
1. `Widget.Material3.TextInputLayout.OutlinedBox` 等 M3 样式名（已对官方文档核对，但版本差异需实测）
2. `themeColor()` 里 `com.google.android.material.R.attr.*` 的引用路径
3. M3 主题属性名（如 `colorSurfaceContainerLow`）在 material 1.12.0 中的可用性

**建议**：先 `./gradlew assembleDebug` 跑一次，把报错发我，我来修。
