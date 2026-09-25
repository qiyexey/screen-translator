# v1.19.0 — 界面 M3 化（TopAppBar / Slider / 下拉框 / 语义色角色）

这一版**不改任何翻译功能、不改任何业务逻辑**，只做界面层从"主题是 M3"到
"界面看起来是 M3"的补齐。

起因：主题从 v1.9.0 起就已经是 `Theme.Material3.*`、也建了语义色角色，但 13 个布局里
**没有一个**用 `MaterialToolbar` —— 每个页面都手写一条返回栏；13 个 `SeekBar`、
8 个 `Spinner` 还是 M2 观感的原生控件。也就是"底子换了，面子没换"。

顺带发现一个**品牌色自相矛盾**：`md_primary` 是绿色 `#006D3B`，但主题的
`colorPrimary` 指向的是蓝色 `accent` `#1A73E8`；而 `values-night` 里
`accent` 是蓝色 `#8AB4F8`、`md_primary` 是绿色 `#8FE0B0` —— 结果是
**白天蓝、夜里绿**。本版统一到蓝色 `#1A73E8`。

版本号：`versionCode 57 → 58`，`versionName 1.18.0 → 1.19.0`。

> **重要前提**：本机没有 JDK / Android SDK / NDK，**这一版的改动没有编译过、
> 也没有在任何设备或模拟器上跑过**。所有验证都是静态的
> （见第 8 节与 `scripts/verify-m3.py`）。首次构建请留意第 9 节的风险清单。

---

## 1. 重建蓝色调色板（P0）

### 问题

`values/colors.xml` 里同时存在两套"主色"，而且互相矛盾：

| 角色 | 白天 | 夜间 |
|---|---|---|
| `md_primary`（M3 语义） | `#006D3B` 绿 | `#8FE0B0` 绿 |
| `accent`（旧别名，被 `colorPrimary` 指向） | `#1A73E8` 蓝 | `#8AB4F8` 蓝 |

主题把 `colorPrimary` 挂在 `accent` 上，所以按钮、滑杆、焦点色都是蓝的；
但任何直接用 `md_primary` 的地方是绿的。更要命的是**夜间**：
`values-night` 只覆盖了颜色，没覆盖 `colorPrimary` 的指向，
于是夜间模式下"蓝色按钮 + 绿色图标"同时出现在一屏里。

`bg_primary` 也写死成 `#FFFFFF`，绕过了 M3 的色调化 `surface`，
整页的层级关系（surface / surfaceContainerLow / …）全部退化成纯白。

### 改动

按 Material Theme Builder 的蓝色种子重建整套角色，主色锚定 `#1A73E8`：

```xml
<!-- values/colors.xml -->
<color name="md_primary">#1A73E8</color>
<color name="md_on_primary">#FFFFFF</color>
<color name="md_primary_container">#D3E3FD</color>
<color name="md_on_primary_container">#041E49</color>
<color name="md_inverse_primary">#A8C7FA</color>
<color name="md_surface">#FAF9FD</color>          <!-- 不再是纯白 -->
<color name="md_surface_container_low">#F4F3F9</color>
<color name="md_surface_container">#EFEEF4</color>
<color name="md_surface_container_high">#E9E8EF</color>
<color name="md_surface_container_highest">#E3E2E9</color>
<color name="md_outline">#74777F</color>
<color name="md_outline_variant">#C4C6D0</color>
<color name="md_success">#1E8E3E</color>          <!-- 刻意保留绿色：成功状态不该跟主色走 -->
```

夜间（`values-night/colors.xml`）用同一套种子生成：
`md_primary #A8C7FA`、`md_on_primary #003062`、`md_primary_container #0842A0`、
`md_surface #111318`、`md_on_surface #E2E2E9`、`md_success #6DD58C`。

### 关键点：旧别名改成"资源别名"

```xml
<color name="text_primary">@color/md_on_surface</color>
<color name="text_secondary">@color/md_on_surface_variant</color>
<color name="bg_primary">@color/md_surface</color>
<color name="bg_card">@color/md_surface_container_low</color>
<color name="accent">@color/md_primary</color>
<color name="divider">@color/md_outline_variant</color>
```

用 `@color/x` 而不是复制一份十六进制值，是为了**结构上消灭一类 bug**：
旧版是各写各的字面量，夜间目录漏掉一个就会"深底浅字 / 浅底深字"。
改成别名之后，6 个别名自动跟随夜间目录，想漏都漏不掉。

这 6 个别名保留还有一个具体原因：Kotlin 侧有 6 处 `R.color.*` 引用它们
（`md_success` ×4、`text_secondary` ×2、`md_warning` ×2、`text_primary`/`bg_primary`/`bg_card` 各 1），
直接删掉别名会编译失败。

### 对比度

`#1A73E8` 配白字 = **4.50:1**，刚好达到 WCAG AA 的正文阈值（4.5:1），没有余量。
如果想留余量，把 `md_primary` 换成 `#0B57D0`（配白字 7.0:1），
只改 `values/colors.xml` 一行，其余全部自动跟随。

---

## 2. 主题补齐组件样式挂钩（P0）

`values/themes.xml` 补了这些角色与挂钩（原来缺一半）：

- `colorPrimaryInverse`、`colorSurfaceInverse` / `colorOnSurfaceInverse`
  —— 原来 `colorSurfaceInverse` 指向的是 `md_on_surface`（一个**文字色**），
  在浅色主题下恰好能用，但语义是错的，Snackbar / Tooltip 一换模式就翻车；
- `colorSurfaceContainerLowest / Low / Container / High / Highest` 五档
  —— M3 靠色调而不是阴影表达层级，缺了这几档，卡片和页面背景会退化成同一个色；
- `materialToolbarStyle` 挂钩 —— 布局里不写 `style` 的 `MaterialToolbar` 也自动是 M3 观感。

`Theme.ScreenTranslator.Transparent`（划词菜单用）的 parent 从
`Theme.Material3.Light.NoActionBar` 改成 `Theme.ScreenTranslator`。
原来它**不继承**本主题，既拿不到语义角色，也拿不到夜间覆盖
（`values-night` 只重定义了 `Theme.ScreenTranslator`，影响不到这条独立分支）——
划词翻译面板在夜间会跑在浅色主题上。

---

## 3. 12 条手写返回栏 → MaterialToolbar（P0）

### 问题

13 个布局里 12 个各手写一条返回栏：

```xml
<!-- 每个页面都抄一遍 -->
<LinearLayout android:orientation="horizontal" android:gravity="center_vertical">
    <com.google.android.material.button.MaterialButton
        style="@style/Widget.ScreenTranslator.Button.Outlined"
        android:text="@string/common_btn_back"   <!-- "‹ 返回" -->
        android:textColor="@color/accent" android:textSize="13sp" />
    <TextView android:layout_weight="1" android:text="@string/xxx"
        android:textSize="18sp" android:textStyle="bold" />
</LinearLayout>
```

三个问题：① 不是 M3 的 app bar 结构，没有高度/内边距规范；
② 返回键是**文字按钮**，白占半个屏宽；③ 标题 18sp bold，与 M3 app bar 无关。

### 改动

统一换成 `MaterialToolbar` + `navigationIcon`（图标返回）+ `?attr/actionBarSize`。
分两种结构改法 —— **这是本步的关键**：M3 app bar 必须**通栏**，
不能缩在内容区的 20dp 内边距里，所以不能就地替换、必须调整嵌套层级。

**A 型（8 个页面）**：根是 `ScrollView → LinearLayout(padding=20dp) → 返回栏`。
外层再包一层 `LinearLayout(vertical)`，Toolbar 放在 `ScrollView` **外面**（固定不滚动），
`ScrollView` 同时升级为 `NestedScrollView`；原内容容器的 `padding="20dp"`
拆成 `paddingHorizontal=20dp` + 上下独立值。

**B 型（4 个页面）**：根本来就是 `LinearLayout(vertical)`，直接换第一个子节点。
其中两处需要额外处理：

- `activity_translate_input.xml` 顶栏还有副标题（`tvEngineInfo`）和"清空"按钮
  → 副标题走 `app:subtitle`，清空按钮走 `app:menu`（M3 里工具栏动作的标准位置，
  新建 `res/menu/menu_translate_input.xml`）；
- `activity_voice_translate.xml` 顶栏还有"引擎/识别"两个状态按钮
  → 它们的文字是**运行时**按当前引擎/语言刷新的（`b.btnEngine.text = ...`），
  塞进 `app:menu` 会因文字过长挤爆标题栏，因此下沉为 app bar 下方的一行胶囊按钮，
  id / 样式 / 点击监听全部保持不变。

顺带修掉一个语义错误：`activity_settings.xml` 的标题用的是
`@string/common_btn_settings_content_description`（一个"内容描述"字符串被拿来当可见标题），
换成真正的标题串 `@string/activity_settings`（值同样是"设置"）。

Kotlin 侧 12 处同步改成 `b.topAppBar.setNavigationOnClickListener { finish() }`。

---

## 4. 13 个 SeekBar → M3 Slider（P0）

### 为什么必须换

`SeekBar` 是 M2 时代控件：细轨道 + 小圆点 + 无反馈。M3 `Slider` 是粗轨道 +
大圆点 + 拖拽时浮出的数值气泡，整条轨道都可点。视觉差距一眼可见。

### 三个坑（这一步的风险全在这里）

**① API 不同名。** `SeekBar` 用 `max`/`progress`，`Slider` 用
`valueFrom`/`valueTo`/`value`，且都是 float。`android:max` 在 `Slider` 上
**不是有效属性**（会被静默忽略，滑块永远停在 0..1），必须显式改写。

**② `Slider.value` 越界会抛异常，`SeekBar` 只会静默钳制。** 这是最危险的一条：

| 写法 | `SeekBar` | `Slider` |
|---|---|---|
| `progress = 999`（max=80） | 静默变成 80 | `IllegalStateException: Slider value must be greater or equal to valueFrom and less or equal to valueTo` |

原代码里多处是 `(App.prefs.xxx * 100).toInt() - 20` 这种换算，
一旦 Prefs 里存了一个超出预期范围的历史值（或用户改过、或跨版本升级带过来的），
升级后**一进页面就崩**。因此所有 Kotlin 侧赋值统一包一层 `.coerceIn(0f, <该滑杆的 valueTo>)`。

**③ 赋值顺序。** 必须先设 `valueTo` 再设 `value`。原代码恰好就是这个顺序
（`b.seekX.max = N` 在 `b.seekX.progress = ...` 之前），保持不动即可。

### 另一个决定：`stepSize = 1`

`Widget.ScreenTranslator.Slider` 里显式给了 `android:stepSize = 1`。
默认 0 是连续模式，拖拽气泡会显示成 "65.3" 这种小数；
而 app 里 13 个滑杆全部是"整数档位 → 真实值"的映射（位置 0..N），
给 1 既让气泡显示整数，也顺带在编译期约束了 `value` 必须是整数。

副作用：`stepSize` 非 0 时 Material 会校验 `(value - valueFrom) % stepSize == 0`，
非整数步长同样会抛异常 —— 这一条被写进了 `verify-m3.py` 的检查项。

### 13 个滑杆的档位表

| 控件 | 档位 | 映射 |
|---|---|---|
| `seekBallAlpha` | 0..80 | 20%..100% |
| `seekBallSize` | 0..28 | 36..64 dp |
| `seekPanelAlpha` | 0..60 | 40%..100% |
| `seekInterval` | 0..46 | 400..5000 ms（步进 100） |
| `seekDiff` | 0..19 | 阈值 1..20 |
| `seekAlpha` | 0..85 | 15%..100% |
| `seekPanelW` / `seekPanelH` | 0..100 / 0..40 | 面板宽高百分比（0 = 自动） |
| `seekTextScale` | 0..120 | 0.6×..1.8× |
| `seekNudgeX` / `seekNudgeY` | 0..160 | -80..+80 dp |
| `seekTtsRate` / `seekTtsPitch` | 0..15 | 0.5..2.0 |

`LiveTranslateActivity` 里那个"只关心 `onProgressChanged`、省掉另外两个空回调"的
`SimpleSeekListener` 基类，改成 `SimpleSliderListener : Slider.OnChangeListener`
—— `Slider.OnChangeListener` 只有一个抽象方法，本来就是要的最小接口。

---

## 5. 8 个 Spinner → M3 下拉（P0，本版风险最高）

### 为什么必须换

M3 没有 `Spinner` 的对应物。官方做法是
`TextInputLayout(ExposedDropdownMenu)` + `MaterialAutoCompleteTextView`，
外观从"光秃秃一行文字 + 小三角"变成"带描边的选择框 + 下拉箭头"，
下拉列表本身也换成 M3 菜单样式。

### 两个"同名但语义无关"的 API（照搬必错）

**① `setSelection`**

| 类 | 语义 |
|---|---|
| `Spinner.setSelection(pos)` | 选中第 pos 项 |
| `AutoCompleteTextView.setSelection(pos)` | 把**文本光标/选区**挪到第 pos 个字符 |

直接搬过去，下拉框里什么都不显示（而且是静默的，不报错）。
正确做法是 `setText(第 pos 项, false)` —— 第二个参数 `false` 表示不要触发过滤，
否则适配器会按刚设进去的文本再过滤一遍，下拉里只剩一项。

**② `selectedItemPosition`**

`AutoCompleteTextView` 继承自 `EditText`，根本没有这个概念。
统一走"按文本反查"：因为 `ExposedDropdownMenu` 硬性要求子控件
`inputType="none"`（用户没法手打），文本框内容只可能是适配器里的某一项。

这两件事都收进 `BaseActivity` 的 `selPos()` / `setSel()` 两个工具方法里，
`EngineSettingsActivity`（6 个下拉）和 `TtsSettingsActivity`（2 个下拉）共用。

### 其他

- `ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)`
  → `setSimpleItems(items.toTypedArray())`。
  前者的列表项是带单选圆点的 `CheckedTextView`，是 M2 观感；
  `setSimpleItems` 是 Material 给 `ExposedDropdownMenu` 准备的适配器入口。
- `onItemSelectedListener` → `setOnItemClickListener`。
  行为差异：`Spinner` 的监听器在**程序化 setSelection 时也会触发**，
  `setOnItemClickListener` 只在用户点选时触发。
  逐处核对过：TTS 页在设置完之后本来就显式调了一次
  `applyTtsSourceLangVisibility()`，不依赖监听器回调；其余各处同理。
- `EngineSettingsActivity` 的 `simpleListener { pos -> ... }` 辅助方法随之删除
  （`setOnItemClickListener` 的 lambda 已经够短）。

---

## 6. 语义色角色 / 滚动容器 / 分隔线（P1）

- **205 处**旧颜色别名 → `?attr/` 主题属性
  （`text_primary→colorOnSurface`、`text_secondary→colorOnSurfaceVariant`、
  `accent→colorPrimary`、`bg_card→colorSurfaceContainerLow`、
  `bg_primary→colorSurface`、`divider→colorOutlineVariant`）。
  两者**当前取值完全相同**（别名就指向同一个 M3 角色），所以这一步视觉零变化；
  意义在于布局里直接写明"这是 M3 语义角色"，而不是绕过一层别名。
- **5 个**剩余 `ScrollView` → `androidx.core.widget.NestedScrollView`
  （支持嵌套滚动；依赖 `androidx.core:core-ktx` 已在 build.gradle.kts 里显式声明）。
  随之 `TranslateInputActivity` 的 `ScrollView.FOCUS_DOWN` 改为 `View.FOCUS_DOWN`。
- **5 处**手写 `<View android:background="@color/divider"/>` → `MaterialDivider`。
- `Widget.ScreenTranslator.Divider` 样式里**故意不写** `android:layout_height`：
  `layout_*` 放在 `<style>` 里本来就不可靠（父容器通过
  `ViewGroup.generateLayoutParams(AttributeSet)` 读布局参数，那条路径不保证带上 style）。
  好在 `MaterialDivider` 自己在 `onMeasure` 里按 `dividerThickness`（默认 1dp）
  强制高度，所以让它自己管、样式只负责颜色。

---

## 7. 图标资源（P1）

- 新增 `res/drawable/ic_arrow_back.xml`（24dp vector，M3 返回箭头），
  替代原来 4 处 `@android:drawable/ic_menu_revert`（系统自带的"撤销"图标，
  语义和造型都不是"返回"）。
- 7 个已有图标（`ic_camera`/`ic_edit`/`ic_headphones`/`ic_history`/`ic_image`/`ic_mic`/`ic_settings`）
  的 `android:fillColor="#1A73E8"` 改成 `@color/md_primary` ——
  白天取值不变，夜间自动变 `#A8C7FA`，否则深色底上那个蓝色几乎看不见。

---

## 8. 静态校验（因为编译不了，只能静态复现一遍编译期检查）

本机没有 JDK / Android SDK / NDK，**这一版的改动没有编译过**。
AGP 本来会在编译期拦住的几类问题，改由 `scripts/verify-m3.py` 静态复现：

| 检查项 | 对应哪个编译期失败 |
|---|---|
| 1. XML 合法性（35 个文件） | 手改布局留下的标签不配对 |
| 2. id ↔ Kotlin 绑定（双向） | ViewBinding 给每个 `@+id` 生成字段，对不上就是编译错误 |
| 3. 资源引用完整性（550 处） | `@string`/`@color`/`@style`/`@drawable` 写错名字 |
| 4. `style` parent 可解析 | parent 指向不存在的本地样式 |
| 5. 组件残留 | 换完之后不该再有 `SeekBar`/`Spinner`/`ImageButton` |
| 6. Kotlin 括号平衡（61 个文件） | 脚本批量改代码的兜底 |
| 7. Slider 取值约束（13 个） | `value` 越界 → **运行期抛异常**（第 4 节的坑②） |
| 8. M3 下拉结构（8 个） | 缺 `inputType="none"` 会让 `selPos()` 的前提不成立 |

**当前结果：8 项全部通过。**

写这个脚本的过程中还修掉了校验器自己的两个假阳性，值得记一笔：

- "先删 `//` 注释、再删字符串"的括号统计法，会把 `"https://platform.deepseek.com"`
  里的 `//` 当成注释，整行剩下的部分（含右括号）一起消失 ——
  于是所有带 URL 的文件都报"括号不平衡"。必须改成逐字符扫描、注释在字符串之后处理。
- 用非贪婪正则找 `</LinearLayout>` 来切块，遇到 `activity_translate_input.xml`
  顶栏里嵌套的那层 `LinearLayout`（标题 + 副标题两行）会匹配到**内层**闭合，
  留下半个残块。必须改成标签深度计数。

### 顺带清掉的死代码

- `TranslateInputActivity` 里 `import android.view.View`（改动前就已未使用，历史遗留）。
- `EngineSettingsActivity` / `TtsSettingsActivity` 里变成未使用的
  `android.widget.AdapterView` / `ArrayAdapter` import。
- 删掉 `Widget.ScreenTranslator.Dropdown` 样式：本来打算给内层
  `MaterialAutoCompleteTextView` 配 `textSize`，最后决定不加 ——
  外层 `ExposedDropdownMenu` 已经把 `materialThemeOverlay` 指向
  `ThemeOverlay.Material3.AutoCompleteTextView.OutlinedBox`，内层再覆盖一层
  等于在两条互相覆盖的路径上做同一件事，而本机无法预览、出了问题看不出来。
  所以严格按 Material 文档的写法：内层只写 `android:inputType="none"`。

---

## 9. 没做的事 / 遗留

### ① 字号没有收敛（刻意）

13 个布局里还有 **198 处**硬编码 `android:textSize`，横跨 **11 档**
（10/11/12/13/14/15/16/17/18/20/24sp）。`values/themes.xml` 里已经备好 7 档命名样式
（`TextAppearance.ScreenTranslator.Headline/Title/Subtitle/Body/BodyLarge/Caption/Label/LabelSmall`），
但**目前没有任何引用**。

没顺手做的原因很具体：现有字号有 11 档，命名样式只有 7 档，
中间那 4 档（14/16/17/24sp）要么新增样式、要么把字号改到最近的档位 ——
**后者是设计决策，不是机械重构**：改字号会改变换行位置、行高、卡片高度，
整页的垂直节奏都会动。本机无法编译也无法预览，盲改的风险不可控。
另外 `MaterialButton`（21 处）和 `TextInputEditText`（25 处）的 `textSize`
需要单独判断：前者受自身 style 的 `textAppearance` 影响，
后者要和 `TextInputLayout` 的浮动标签协调。

**前置条件**：先能出包（有 JDK + Android SDK，或能接设备跑 `gradle installDebug` 看真机效果）。

### ② 主色对比度没有余量

`#1A73E8` 配白字 4.50:1，刚好压线。要余量就换成 `#0B57D0`（7.0:1），改一行。

### ③ 没动的地方

- `activity_main.xml` 没有手写返回栏，因此没有 TopAppBar（它是首屏，结构不同）。
  本次只把它的 `ScrollView` 换成 `NestedScrollView`、分隔线换成 `MaterialDivider`、
  颜色换成 `?attr/`。首页要不要也加一条 app bar（比如放设置入口），是个产品决定。
- `activity_live_translate.xml` 顶栏原来的 `drawableStart="@drawable/ic_image"`
  装饰图标在换成 `MaterialToolbar` 时去掉了（Toolbar 的标题区没法直接挂前置图标，
  而 `app:logo` 会跑到最左侧、和导航图标打架）。标题文字本身已经说清楚了。
- 布局里 2 个 id 从未被任何 Kotlin 引用：`activity_live_translate.xml` 的 `tvLog`、
  `activity_main.xml` 的 `tvSrcLang`。不是本次引入的，未处理。
- 项目里 7 处未使用的 import（`BaiduTranslator`、`GoogleTranslator`、`RoiPickerView`、
  `ScreenReaderService`、`HistoryActivity`、`ProcessTextActivity`×2），本次未涉及这些文件。

### ④ 首次构建请重点看这几处

1. `BaseActivity.selPos()` 里 `v.adapter as? ...` 的取值路径 ——
   如果某个下拉的适配器不是 `String` 列表，反查会退回 0（不崩，但会选错项）。
2. 8 个 M3 下拉的实际弹出行为（`setSimpleItems` + `ExposedDropdownMenu` 的组合
   在真机上的表现，尤其是 TTS 页那两个 `wrap_content` + `minWidth=150dp` 的窄下拉）。
3. 13 个 Slider 的初始位置是否与 Prefs 里的存量值一致
   （`coerceIn` 保证了不崩，但如果被钳制了，说明 Prefs 里存的是越界值）。

---

## 10. 本次新增的脚本

都放在 `scripts/`，都是幂等/可 dry-run 的，改错了可以重跑：

| 脚本 | 作用 |
|---|---|
| `m3-toolbar.py` | 12 条手写返回栏 → `MaterialToolbar`（按根节点结构分 A/B 两型） |
| `m3-slider.py` | 13 个 `SeekBar` → M3 `Slider`，含 `coerceIn` 与档位表自检 |
| `m3-spinner.py` | 8 个 `Spinner` → M3 下拉，含 `setSelection`/`selectedItemPosition` 语义修正 |
| `m3-tokens.py` | 颜色别名 → `?attr/`、`ScrollView` → `NestedScrollView`、分隔线 → `MaterialDivider` |
| `verify-m3.py` | 第 8 节的 8 项静态校验，0 退出码 = 全部通过 |

---

## 11. 改动文件清单

**资源（17 个）**
- `values/colors.xml`、`values-night/colors.xml` —— 重建调色板 + 别名改资源别名
- `values/themes.xml`、`values-night/themes.xml` —— 补角色、补组件样式、修夜间 parent
- `drawable/ic_arrow_back.xml`（新增）+ 7 个已有图标改 `fillColor`
- `menu/menu_translate_input.xml`（新增）
- 13 个 `layout/activity_*.xml` —— 全部改过

**Kotlin（15 个）**
- `BaseActivity.kt` —— 新增 `selPos()` / `setSel()`
- 12 个 Activity —— `btnBack` → `topAppBar.setNavigationOnClickListener`
- `BallStyleActivity.kt` / `LiveTranslateActivity.kt` / `TtsSettingsActivity.kt` —— Slider 监听器
- `EngineSettingsActivity.kt` / `TtsSettingsActivity.kt` —— 下拉适配器与监听器
- `TranslateInputActivity.kt` —— 副标题 + 菜单动作

**构建**
- `app/build.gradle.kts` —— `versionCode 58`、`versionName 1.19.0`
