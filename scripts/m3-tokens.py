#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.19.0 界面 M3 化 —— 步骤 4：语义色角色 / 滚动容器 / 分隔线。

三件小事，都是"结构对齐 M3"，不改视觉：

1. 旧颜色别名 → 主题属性
   `@color/text_primary` → `?attr/colorOnSurface` 等 6 个。
   两者**当前取值完全相同**（别名就指向同一个 M3 角色），所以这一步视觉零变化；
   意义在于布局里直接写明"这是 M3 语义角色"，而不是绕过一层别名。
   别名本身保留（Kotlin 侧有 6 处 R.color.* 还在用）。

2. 剩余 5 个 ScrollView → androidx.core.widget.NestedScrollView
   NestedScrollView 支持嵌套滚动，是 M3/AppBarLayout 时代的标准容器；
   而且和上面步骤 1 已经换掉的那批保持一致。

3. 5 处手写 <View android:background="@color/divider"/> → MaterialDivider
   注意：**不要**靠 style 里的 layout_height 定高。
   layout_* 写在 <style> 里本来就不可靠（ViewGroup.generateLayoutParams
   读的是原始 AttributeSet，不一定带上 style），好在 MaterialDivider 自己
   在 onMeasure 里按 dividerThickness（默认 1dp）强制高度，所以正确做法是
   让它自己管高度、样式里只留 dividerColor。

用法
----
    python scripts/m3-tokens.py [--dry-run]
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
LAYOUT = os.path.join(RES, "layout")
UI = os.path.join(ROOT, "app", "src", "main", "java", "com", "hunter", "screentranslator", "ui")

DRY = "--dry-run" in sys.argv

# 旧别名 → M3 主题属性。取值一一对应，见 values/colors.xml 的别名定义。
COLOR_MAP = {
    "text_primary": "?attr/colorOnSurface",
    "text_secondary": "?attr/colorOnSurfaceVariant",
    "accent": "?attr/colorPrimary",
    "bg_card": "?attr/colorSurfaceContainerLow",
    "bg_primary": "?attr/colorSurface",
    "divider": "?attr/colorOutlineVariant",
}

# <View ... android:background="@color/divider" ... />（整块，含可选 margin）
VIEW_DIV_RE = re.compile(
    r"(?s)^([ \t]*)<View\b(.*?)/>[ \t]*$", re.M
)
MATERIAL_DIV = """{ind}<com.google.android.material.divider.MaterialDivider
{ind}    style="@style/Widget.ScreenTranslator.Divider"
{ind}    android:layout_width="match_parent"{extra} />"""


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def main():
    stats = {}

    # ---------------- 1. 颜色别名 → ?attr/ ----------------
    ncolor = 0
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        orig = s
        for alias, attr in COLOR_MAP.items():
            s, c = re.subn(r"@color/%s\b" % alias, attr, s)
            ncolor += c
        if s != orig and not DRY:
            write(lay, s)
    stats["颜色别名 → ?attr"] = ncolor

    # ---------------- 2. ScrollView → NestedScrollView ----------------
    nscroll = 0
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        if "<ScrollView" not in s:
            continue
        orig = s
        s = s.replace("<ScrollView", "<androidx.core.widget.NestedScrollView")
        s = s.replace("</ScrollView>", "</androidx.core.widget.NestedScrollView>")
        nscroll += 1
        if not DRY:
            write(lay, s)
    stats["ScrollView → NestedScrollView"] = nscroll

    # ---------------- 3. 手写分隔线 → MaterialDivider ----------------
    ndiv = 0

    def div_repl(m):
        nonlocal ndiv
        ind, body = m.group(1), m.group(2)
        # 步骤 1 会把 @color/divider 换成 ?attr/colorOutlineVariant，
        # 两种写法都要认（这样 --dry-run 和重复执行的结果一致）
        if not re.search(
            r'android:background="(?:\?attr/colorOutlineVariant|@color/divider)"', body
        ):
            return m.group(0)  # 不是分隔线，放过
        margin = re.search(r'android:layout_marginTop="([^"]+)"', body)
        extra = ('\n%s    android:layout_marginTop="%s"' % (ind, margin.group(1))) if margin else ""
        ndiv += 1
        return MATERIAL_DIV.format(ind=ind, extra=extra)

    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        out = VIEW_DIV_RE.sub(div_repl, s)
        if out != s and not DRY:
            write(lay, out)
    stats["手写分隔线 → MaterialDivider"] = ndiv

    # ---------------- 4. Kotlin：ScrollView.FOCUS_DOWN ----------------
    nkt = 0
    p = os.path.join(UI, "TranslateInputActivity.kt")
    s = read(p)
    if "ScrollView.FOCUS_DOWN" in s:
        s = s.replace("ScrollView.FOCUS_DOWN", "View.FOCUS_DOWN")
        if "import android.view.View\n" not in s:
            s = s.replace(
                "import android.widget.TextView\n",
                "import android.view.View\nimport android.widget.TextView\n",
                1,
            )
        s = s.replace("import android.widget.ScrollView\n", "")
        nkt += 1
        if not DRY:
            write(p, s)
    stats["Kotlin 常量改用 View.FOCUS_DOWN"] = nkt

    # ---------------- 5. 分隔线样式去掉不可靠的 layout_height ----------------
    tp = os.path.join(RES, "values", "themes.xml")
    t = read(tp)
    old = """    <style name="Widget.ScreenTranslator.Divider" parent="Widget.Material3.Divider">
        <item name="android:layout_height">1dp</item>
        <item name="dividerColor">?attr/colorOutlineVariant</item>
    </style>"""
    new = """    <style name="Widget.ScreenTranslator.Divider" parent="Widget.Material3.Divider">
        <!--
          这里**故意不写** android:layout_height。
          layout_* 放在 <style> 里本来就不可靠 —— 父容器是通过
          ViewGroup.generateLayoutParams(AttributeSet) 读布局参数的，
          那条路径不保证带上 style；写了会有"某天突然失效"的隐患。
          MaterialDivider 自己在 onMeasure 里按 dividerThickness（默认 1dp）
          强制高度，所以让它自己管，样式只负责颜色。
        -->
        <item name="dividerColor">?attr/colorOutlineVariant</item>
    </style>"""
    if old in t:
        if not DRY:
            write(tp, t.replace(old, new, 1))
        stats["分隔线样式去掉 layout_height"] = 1
    else:
        stats["分隔线样式去掉 layout_height"] = 0

    for k, v in stats.items():
        print("  %-34s %s" % (k, v))
    if DRY:
        print("\n[dry-run] 未落盘")


if __name__ == "__main__":
    main()
