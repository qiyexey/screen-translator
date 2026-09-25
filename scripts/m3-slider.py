#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.19.0 界面 M3 化 —— 步骤 2：13 个 android.widget.SeekBar 换成 M3 Slider。

为什么必须换
------------
SeekBar 是 M2 时代的控件：细轨道 + 小圆点 + 无反馈。M3 的 Slider 是
粗轨道 + 大圆点 + 拖拽时浮出的数值气泡（label），并且整条轨道都是可点区域。
视觉差距一眼可见，属于"看着就不像 M3"的主因之一。

三个不能踩的坑
--------------
1. **API 不同名**：SeekBar 用 max/progress，Slider 用 valueFrom/valueTo/value，
   而且全是 float。`android:max` 在 Slider 上不是有效属性（会被静默忽略，
   滑块永远停在 0..1），必须显式改写成 valueFrom/valueTo。

2. **Slider.value 越界会抛异常，SeekBar 只会静默钳制**。
   SeekBar：`progress = 999` → 自动变成 max。
   Slider ：`value = 999f`  → IllegalStateException: Slider value must be
            greater or equal to valueFrom and less or equal to valueTo。
   原代码里多处是 `(App.prefs.xxx * 100).toInt() - 20` 这种换算，
   一旦 Prefs 里存了一个超出预期范围的历史值，升级后**一进页面就崩**。
   因此所有 Kotlin 侧的赋值统一包一层 `.coerceIn(0f, <该滑杆的 valueTo>)`。

3. **赋值顺序**：必须先设 valueTo 再设 value。原代码恰好就是这个顺序
   （`b.seekX.max = N` 在 `b.seekX.progress = ...` 之前），保持不动即可。

用法
----
    python scripts/m3-slider.py [--dry-run]
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout")
UI = os.path.join(ROOT, "app", "src", "main", "java", "com", "hunter", "screentranslator", "ui")

DRY = "--dry-run" in sys.argv

# 每个滑杆的档位上限（= 布局里的 android:max = Slider 的 valueTo）。
# 用于 Kotlin 侧赋值时的 coerceIn 上界 —— 这张表必须和布局里的 valueTo 一致，
# 脚本结束前会自己交叉核对一遍。
MAX = {
    "seekBallAlpha": 80, "seekBallSize": 28, "seekPanelAlpha": 60,
    "seekInterval": 46, "seekDiff": 19, "seekAlpha": 85,
    "seekPanelW": 100, "seekPanelH": 40, "seekTextScale": 120,
    "seekNudgeX": 160, "seekNudgeY": 160,
    "seekTtsRate": 15, "seekTtsPitch": 15,
}

SLIDER_STYLE = 'style="@style/Widget.ScreenTranslator.Slider"'
SLIDER_TAG = "com.google.android.material.slider.Slider"

SEEKBAR_RE = re.compile(r"(?s)^([ \t]*)<SeekBar\b(.*?)/>[ \t]*$", re.M)
ATTR_RE = re.compile(r'([\w:]+)="([^"]*)"')

# Kotlin
ASSIGN_RE = re.compile(r"^([ \t]*)b\.(seek\w+)\.(progress|max)\s*=\s*(.*)$", re.M)
READ_RE = re.compile(r"b\.(seek\w+)\.progress(?!\s*=)")
LISTENER_RE = re.compile(
    r"b\.(seek\w+)\.setOnSeekBarChangeListener\(object : "
    r"(SeekBar\.OnSeekBarChangeListener|SimpleSeekListener\(\)) \{\n(.*?)\n([ \t]*)\}\)",
    re.S,
)
PROGRESS_OVERRIDE_RE = re.compile(
    r"override fun onProgressChanged\(sb: SeekBar\?, (\w+): Int, fromUser: Boolean\) \{"
)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


# ------------------------------------------------------------------ 布局
def convert_layout(src):
    n = 0

    def repl(m):
        nonlocal n
        n += 1
        ind, body = m.group(1), m.group(2)
        inner = ind + "    "
        attrs = ATTR_RE.findall(body)
        out = []
        for name, val in attrs:
            if name == "android:max":
                # SeekBar 的 max → Slider 的 valueTo（valueFrom 固定 0，与 SeekBar 语义一致）
                out.append(inner + 'android:valueFrom="0"')
                out.append(inner + 'android:valueTo="%s"' % val)
            elif name == "android:progress":
                out.append(inner + 'android:value="%s"' % val)
            else:
                out.append(inner + '%s="%s"' % (name, val))
                if name == "android:id":
                    out.append(inner + SLIDER_STYLE)
        out[-1] += " />"
        return ind + "<" + SLIDER_TAG + "\n" + "\n".join(out)

    return SEEKBAR_RE.sub(repl, src), n


# ------------------------------------------------------------------ Kotlin
def fix_assign(m):
    ind, name, prop, rhs = m.groups()
    code, sep, comment = rhs.partition("//")
    code = code.rstrip()
    tail = ("  " + sep + comment.rstrip()) if sep else ""
    if prop == "max":
        return "%sb.%s.valueTo = (%s).toFloat()%s" % (ind, name, code, tail)
    mx = MAX[name]
    return "%sb.%s.value = (%s).toFloat().coerceIn(0f, %sf)%s" % (ind, name, code, mx, tail)


def fix_listener(m):
    name, base, body, close_ind = m.groups()
    base = "SimpleSliderListener()" if base.startswith("SimpleSeek") else "Slider.OnChangeListener"
    out = []
    for ln in body.splitlines():
        t = ln.strip()
        if PROGRESS_OVERRIDE_RE.match(t):
            lead = ln[: len(ln) - len(ln.lstrip())]
            pname = PROGRESS_OVERRIDE_RE.match(t).group(1)
            out.append(lead + "override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {")
            # 把 float 的 value 折回原来的整数 progress，后面整段 body 都不用动
            out.append(lead + "    val %s = value.toInt()" % pname)
        elif t.startswith("override fun onStartTrackingTouch") or t.startswith(
            "override fun onStopTrackingTouch"
        ):
            continue  # Slider 没有"按下/抬起"这两个回调，M3 用 onStartTrackingTouch 语义由触摸监听承担
        else:
            out.append(ln)
    return "b.%s.addOnChangeListener(object : %s {\n%s\n%s})" % (
        name, base, "\n".join(out), close_ind,
    )


def convert_kotlin(src, fname):
    # 1) import
    if "import android.widget.SeekBar" not in src:
        raise SystemExit("!! %s: 找不到 SeekBar import" % fname)
    src = src.replace("import android.widget.SeekBar\n", "", 1)
    src = re.sub(
        r"(^import com\.hunter\.screentranslator\.)",
        "import com.google.android.material.slider.Slider\n\\1",
        src,
        count=1,
        flags=re.M,
    )
    # 2) 赋值：先处理 .progress= / .max=（否则会被下面的"读取"正则误伤）
    src = ASSIGN_RE.sub(fix_assign, src)
    # 3) 读取
    src = READ_RE.sub(lambda m: "b.%s.value.toInt()" % m.group(1), src)
    # 4) 监听器
    src, nlist = LISTENER_RE.subn(fix_listener, src)
    # 5) LiveTranslate 的"只关心 onProgressChanged"基类改名
    src = src.replace(
        "    /** SeekBar 的\"只关心 onProgressChanged\"基类，省掉另外两个空回调 */\n"
        "    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {\n"
        "        override fun onStartTrackingTouch(sb: SeekBar?) = Unit\n"
        "        override fun onStopTrackingTouch(sb: SeekBar?) = Unit\n"
        "    }",
        "    /**\n"
        "     * Slider 的\"只关心 onValueChange\"基类。\n"
        "     *\n"
        "     * 原来是 SeekBar.OnSeekBarChangeListener（要写满 onProgressChanged /\n"
        "     * onStartTrackingTouch / onStopTrackingTouch 三个回调，其中两个是空的）；\n"
        "     * Slider.OnChangeListener 只有一个抽象方法，直接就是想要的最小接口。\n"
        "     */\n"
        "    private abstract class SimpleSliderListener : Slider.OnChangeListener",
    )
    return src, nlist


def main():
    lay_changed, kt_changed = [], []
    lay_total = 0
    converted = {}  # 文件名 → 转换后的内容（自检用，dry-run 时磁盘上还是旧的）

    for fn in sorted(os.listdir(LAYOUT)):
        p = os.path.join(LAYOUT, fn)
        s = read(p)
        if "<SeekBar" not in s:
            continue
        out, n = convert_layout(s)
        lay_total += n
        lay_changed.append("%s (%d)" % (fn, n))
        converted[fn] = out
        if not DRY:
            write(p, out)

    for fn in sorted(os.listdir(UI)):
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(UI, fn)
        s = read(p)
        if "SeekBar" not in s:
            continue
        out, n = convert_kotlin(s, fn)
        kt_changed.append("%s (%d 个监听器)" % (fn, n))
        if not DRY:
            write(p, out)

    print("布局：%d 个 SeekBar → Slider" % lay_total)
    for c in lay_changed:
        print("   ", c)
    print("Kotlin：")
    for c in kt_changed:
        print("   ", c)

    # ---- 自检：布局里的 valueTo 必须和 MAX 表一致，否则 coerceIn 上界就错了
    print("\n=== 自检：布局 valueTo 与 MAX 表一致性 ===")
    bad, seen = 0, 0
    for fn, s in converted.items():
        for m in re.finditer(r'android:id="@\+id/(seek\w+)"(.*?)/>', s, re.S):
            name, rest = m.group(1), m.group(2)
            seen += 1
            vt = re.search(r'android:valueTo="(\d+)"', rest)
            v = re.search(r'android:value="(\d+)"', rest)
            if not vt:
                print("   !! %s: 没有 valueTo" % name)
                bad += 1
                continue
            if name not in MAX:
                print("   !! %s: MAX 表里没有" % name)
                bad += 1
            elif int(vt.group(1)) != MAX[name]:
                print("   !! %s: 布局 valueTo=%s 但 MAX 表=%d" % (name, vt.group(1), MAX[name]))
                bad += 1
            if v and int(v.group(1)) > int(vt.group(1)):
                print("   !! %s: XML 初始 value=%s 超过 valueTo=%s（会崩）" % (name, v.group(1), vt.group(1)))
                bad += 1
    print("   共 %d 个滑杆，不一致 %d 处" % (seen, bad))
    if DRY:
        print("\n[dry-run] 未落盘")


if __name__ == "__main__":
    main()
