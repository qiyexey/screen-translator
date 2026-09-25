#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.19.0 界面 M3 化 —— 步骤 3：8 个 android.widget.Spinner 换成 M3 下拉。

M3 没有 Spinner 的对应物。官方做法是
    TextInputLayout(style=...ExposedDropdownMenu)
      └ MaterialAutoCompleteTextView(android:inputType="none")
外观从"光秃秃一行文字 + 小三角"变成"带浮动标签的描边选择框 + 下拉箭头"，
下拉列表本身也换成 M3 的菜单样式。

为什么这一步最危险
------------------
Spinner 和 AutoCompleteTextView 有两个**同名但语义无关**的 API，
照搬必错：

  · Spinner.setSelection(pos)          = 选中第 pos 项
    AutoCompleteTextView.setSelection(pos) = 把文本光标/选区挪到第 pos 个字符
    → 直接搬过去，下拉框里什么都不显示。
    正确做法是 setText(第 pos 项, false)（false = 不触发过滤，
    否则适配器会按刚设进去的文本再过滤一遍，下拉里只剩一项）。

  · Spinner.selectedItemPosition       = 当前第几项
    AutoCompleteTextView 没有对应物（它是 EditText 子类）。
    → 统一走"按文本反查"，见 BaseActivity.selPos。

另外 setSimpleItems() 是 Material 给 ExposedDropdownMenu 准备的适配器入口，
比手搓 ArrayAdapter + android.R.layout.simple_spinner_dropdown_item 更合适：
后者的列表项是带单选圆点的 CheckedTextView，是 M2 的观感。

用法
----
    python scripts/m3-spinner.py [--dry-run]
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout")
UI = os.path.join(ROOT, "app", "src", "main", "java", "com", "hunter", "screentranslator", "ui")

DRY = "--dry-run" in sys.argv

SPINNER_RE = re.compile(r"(?s)^([ \t]*)<Spinner\b(.*?)/>[ \t]*$", re.M)
ATTR_RE = re.compile(r'([\w:]+)="([^"]*)"')

TIL = "com.google.android.material.textfield.TextInputLayout"
ACTV = "com.google.android.material.textfield.MaterialAutoCompleteTextView"

# Kotlin
ADAPTER_RE = re.compile(
    r"b\.(spinner\w+)\.adapter\s*=\s*ArrayAdapter\(\s*this\s*,\s*"
    r"android\.R\.layout\.simple_spinner_dropdown_item\s*,\s*",
    re.S,
)
POS_RE = re.compile(r"b\.(spinner\w+)\.selectedItemPosition")
# 匿名 OnItemSelectedListener → setOnItemClickListener
# 捕获 4 组：语句缩进 / 控件名 / override 行缩进 / 回调体。
# 语句缩进必须单独捕获 —— 闭合括号要回到语句缩进，而不是 override 行的缩进，
# 否则改完每个监听器的右括号都会多缩进一级。
ANON_RE = re.compile(
    r"^([ \t]*)b\.(spinner\w+)\.onItemSelectedListener\s*=\s*object\s*:\s*"
    r"AdapterView\.OnItemSelectedListener\s*\{\s*\n"
    r"([ \t]*)override fun onItemSelected\([^)]*\)\s*\{(.*?)\n\3\}\s*\n"
    r"[ \t]*override fun onNothingSelected\([^)]*\)\s*\{\}\s*\n[ \t]*\}",
    re.S | re.M,
)
# simpleListener { pos -> ... }
SIMPLE_RE = re.compile(
    r"b\.(spinner\w+)\.onItemSelectedListener\s*=\s*simpleListener\s*\{\s*pos\s*->\s*(.*?)\n([ \t]*)\}",
    re.S,
)
SIMPLE_DEF_RE = re.compile(
    r"\n[ \t]*private fun simpleListener\(onSelected: \(Int\) -> Unit\) = "
    r"object : AdapterView\.OnItemSelectedListener \{\n"
    r"[ \t]*override fun onItemSelected\([^)]*\) = onSelected\(pos\)\n"
    r"[ \t]*override fun onNothingSelected\([^)]*\) \{\}\n[ \t]*\}\n"
)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def match_paren(s, open_idx):
    """s[open_idx] == '('；返回配对右括号的下一个位置。"""
    depth = 0
    i = open_idx
    in_str = None
    while i < len(s):
        c = s[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == in_str:
                in_str = None
        elif c in "\"'":
            in_str = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("括号不配对")


def convert_layout(src):
    n = 0

    def repl(m):
        nonlocal n
        n += 1
        ind, body = m.group(1), m.group(2)
        inner = ind + "    "
        inner2 = inner + "    "
        attrs = ATTR_RE.findall(body)
        til_attrs, actv_attrs = [], []
        for name, val in attrs:
            if name == "android:id":
                actv_attrs.append(inner2 + 'android:id="%s"' % val)
            elif name in ("android:layout_width", "android:layout_height",
                          "android:layout_marginTop", "android:minWidth"):
                # 外框继承 Spinner 的占位尺寸（宽/高/上边距/最小宽）
                til_attrs.append(inner + '%s="%s"' % (name, val))
                if name == "android:layout_width":
                    actv_attrs.append(inner2 + 'android:layout_width="match_parent"')
                elif name == "android:layout_height":
                    actv_attrs.append(inner2 + 'android:layout_height="wrap_content"')
            else:
                til_attrs.append(inner + '%s="%s"' % (name, val))
        actv_attrs.append(inner2 + 'android:inputType="none"')
        return (
            ind + "<" + TIL + "\n"
            + inner + 'style="@style/Widget.ScreenTranslator.DropdownLayout"\n'
            + "\n".join(til_attrs) + ">\n\n"
            + inner + "<" + ACTV + "\n"
            + "\n".join(actv_attrs) + " />\n"
            + ind + "</" + TIL + ">"
        )

    return SPINNER_RE.sub(repl, src), n


def reindent(body, indent):
    """去掉 body 的共同左侧空白，再整体缩进到 indent。保留相对缩进。"""
    lines = [l.rstrip() for l in body.strip("\n").splitlines()]
    lines = [l for l in lines]
    if not lines:
        return ""
    base = min((len(l) - len(l.lstrip()) for l in lines if l.strip()), default=0)
    return "\n".join((indent + l[base:]) if l.strip() else "" for l in lines)


def convert_kotlin(src, fname):
    stats = {}

    # 1) ArrayAdapter(this, simple_spinner_dropdown_item, ITEMS) → setSimpleItems(ITEMS)
    out, cnt, pos = [], 0, 0
    while True:
        m = ADAPTER_RE.search(src, pos)
        if not m:
            out.append(src[pos:])
            break
        # 第三个实参可能自带括号（如 .map { if (it == 0) "自动" else "$it" }），
        # 所以用括号配对找 ArrayAdapter( 的收尾，不能用非贪婪正则
        arg_start = m.end()
        k = arg_start
        while src[k] in " \t\n":
            k += 1
        depth, i, in_str = 1, arg_start, None
        while i < len(src):
            c = src[i]
            if in_str:
                if c == "\\":
                    i += 2
                    continue
                if c == in_str:
                    in_str = None
            elif c in "\"'":
                in_str = c
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        third = src[k:i].rstrip().rstrip(",").rstrip()
        out.append(src[pos:m.start()])
        out.append("b.%s.setSimpleItems(%s.toTypedArray())" % (m.group(1), third))
        pos = i + 1
        cnt += 1
    src = "".join(out)
    stats["adapter→setSimpleItems"] = cnt

    # 2) setSelection(x) → setSel(v, x)
    #    必须用括号配对而不是单行正则：EngineSettings 里有 4 处是
    #    b.spinnerX.setSelection(\n    长表达式\n) 的多行写法，
    #    只认单行会漏掉它们 —— 而漏掉的那几处语义是错的（见文件头说明），
    #    不会报错、只会静默变成空下拉，属于最难发现的一类 bug。
    SEL_HEAD = re.compile(r"b\.(spinner\w+)\.setSelection\(")
    out, cnt, pos = [], 0, 0
    while True:
        m = SEL_HEAD.search(src, pos)
        if not m:
            out.append(src[pos:])
            break
        end = match_paren(src, m.end() - 1)
        arg = src[m.end():end - 1].strip()
        out.append(src[pos:m.start()])
        out.append("setSel(b.%s, %s)" % (m.group(1), arg))
        pos = end
        cnt += 1
    src = "".join(out)
    stats["setSelection"] = cnt

    # 3) selectedItemPosition → selPos(v)
    src, c = POS_RE.subn(lambda m: "selPos(b.%s)" % m.group(1), src)
    stats["selectedItemPosition"] = c

    # 4) 匿名 OnItemSelectedListener → setOnItemClickListener
    src, c = ANON_RE.subn(
        lambda m: "%sb.%s.setOnItemClickListener { _, _, pos, _ ->\n%s\n%s}"
        % (m.group(1), m.group(2), reindent(m.group(4), m.group(1) + "    "), m.group(1)),
        src,
    )
    stats["匿名监听器"] = c

    # 5) simpleListener { pos -> ... } → setOnItemClickListener
    src, c = SIMPLE_RE.subn(
        lambda m: "b.%s.setOnItemClickListener { _, _, pos, _ ->\n%s\n%s}"
        % (m.group(1), reindent(m.group(2), m.group(3) + "    "), m.group(3)),
        src,
    )
    stats["simpleListener 用法"] = c

    # 6) 删掉不再需要的 simpleListener 定义
    src, c = SIMPLE_DEF_RE.subn("", src)
    stats["simpleListener 定义"] = c

    # 7) 清掉变成未使用的 import（Kotlin 里只是警告，但顺手清干净）。
    #    判断"还有没有用到"时必须先摘掉 import 行本身，
    #    否则 import 里的类名会让判断永远为真、永远删不掉。
    for cls in ("AdapterView", "ArrayAdapter"):
        line = "import android.widget.%s\n" % cls
        if line in src:
            stripped = src.replace(line, "", 1)
            if cls not in stripped:
                src = stripped
    return src, stats


def main():
    lay, kt = [], []
    total = 0
    for fn in sorted(os.listdir(LAYOUT)):
        p = os.path.join(LAYOUT, fn)
        s = read(p)
        if "<Spinner" not in s:
            continue
        out, n = convert_layout(s)
        total += n
        lay.append("%s (%d)" % (fn, n))
        if not DRY:
            write(p, out)

    for fn in ["EngineSettingsActivity.kt", "TtsSettingsActivity.kt"]:
        p = os.path.join(UI, fn)
        s = read(p)
        out, st = convert_kotlin(s, fn)
        kt.append("%s  %s" % (fn, ", ".join("%s=%d" % kv for kv in st.items())))
        if not DRY:
            write(p, out)

    print("布局：%d 个 Spinner → M3 下拉" % total)
    for c in lay:
        print("   ", c)
    print("Kotlin：")
    for c in kt:
        print("   ", c)
    if DRY:
        print("\n[dry-run] 未落盘")


if __name__ == "__main__":
    main()
