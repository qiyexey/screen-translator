#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.19.0 界面 M3 化 —— 步骤 1：把 12 个布局手写的"返回栏"换成 MaterialToolbar。

背景
----
app 从 v1.9.0 起主题就是 Material 3，但 13 个布局里**没有一个**用 MaterialToolbar：
每个页面都手写一条返回栏，形态是「LinearLayout(horizontal) + 描边 MaterialButton
（文字"‹ 返回"）+ 标题 TextView」。它不是 M3 的 app bar：
  · 没有 app bar 的高度 / 内边距规范；
  · 返回键是**文字按钮**，白占半个屏宽；
  · 标题 18sp bold，与 M3 app bar 的 titleTextAppearance 无关。

本脚本按**根节点结构**分两种改法。这是关键：M3 app bar 必须通栏，
不能缩在内容区的 20dp 内边距里，所以不能就地替换、必须调整嵌套层级。

  A 型（8 个）根是 ScrollView → LinearLayout(padding=20dp) → 返回栏
      外层再包一层 LinearLayout(vertical)，Toolbar 放在 ScrollView **外面**；
      ScrollView 同时升级成 NestedScrollView（嵌套滚动兼容）；
      原内容容器 padding="20dp" 拆成 paddingHorizontal=20dp + 上下独立值，
      否则 Toolbar 会被这 20dp 挤成非通栏。

  B 型（4 个）根本来就是 LinearLayout(vertical)，返回栏是第一个子节点
      整个"顶栏"块换成 MaterialToolbar。其中：
        · translate_input  顶栏还有副标题(tvEngineInfo)和"清空"按钮
           → 副标题走 app:subtitle，清空按钮走 app:menu（Toolbar 的标准动作位）；
        · voice_translate  顶栏还有"引擎/识别"两个状态按钮
           → 它们的文字是**运行时**按当前引擎/语言刷新的（b.btnEngine.text = ...），
             塞进 app:menu 会因文字过长挤爆标题栏，因此下沉为 app bar 下方的胶囊按钮行。

Kotlin 侧同步改动见本脚本 phase 2（btnBack → topAppBar 等）。

用法
----
    python scripts/m3-toolbar.py            # 执行
    python scripts/m3-toolbar.py --dry-run  # 只报告将要改什么，不落盘
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout")
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "hunter", "screentranslator", "ui")
MENU = os.path.join(ROOT, "app", "src", "main", "res", "menu")

DRY = "--dry-run" in sys.argv

# ---------------------------------------------------------------- 标题映射
# A 型：标题取原返回栏里那个 TextView 的 android:text
FORMA_TITLE = {
    "activity_about.xml": "common_t02",                       # 关于与用法
    "activity_asr_settings.xml": "common_t08",                # 语音识别（听视频用）
    "activity_ball_style.xml": "common_t04",                  # 悬浮球与面板外观
    "activity_engine_settings.xml": "engine_settings_t35",    # 翻译引擎与密钥
    "activity_permission.xml": "common_t05",                  # 权限与保活
    # 原来是 common_btn_settings_content_description（一个"内容描述"字符串被拿来当可见标题），
    # 这里换成真正的标题串 activity_settings（值同样是"设置"）。
    "activity_settings.xml": "activity_settings",
    "activity_trigger_settings.xml": "common_t06",            # 翻译触发方式
    "activity_tts_settings.xml": "tts_settings_t03",          # 朗读译文
}

# B 型
FORMB_TITLE = {
    "activity_live_translate.xml": "common_t03",              # 实时屏幕翻译
    "activity_video_listen.xml": "video_listen_t07",          # 听视频翻译
    "activity_voice_translate.xml": "voice_translate_t02",    # 语音翻译
    "activity_translate_input.xml": "translate_input_t01",    # 输入翻译
}

# translate_input 的 app bar 副标题
FORMB_SUBTITLE = {
    "activity_translate_input.xml": "translate_input_tv_engine_info",
}

# translate_input 的 app bar 动作菜单（原顶栏里的"清空"按钮）
FORMB_MENU = {
    "activity_translate_input.xml": "menu_translate_input",
}

TOOLBAR_FMT = """    <!-- M3 TopAppBar（v1.19.0）：替代原来手写的"返回"文字按钮 + 标题 TextView -->
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/topAppBar"
        style="@style/Widget.ScreenTranslator.Toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        app:navigationIcon="@drawable/ic_arrow_back"
        app:navigationContentDescription="@string/common_btn_back_content_description"
        app:title="@string/{title}"{extra} />
"""

DIVIDER = """    <com.google.android.material.divider.MaterialDivider
        style="@style/Widget.ScreenTranslator.Divider"
        android:layout_width="match_parent" />
"""

# 原"返回栏"/"顶栏"块：注释 + 一个 LinearLayout。
# 注意**不能**用非贪婪正则找 `</LinearLayout>` —— translate_input 的顶栏里还嵌着
# 一层 LinearLayout（标题 + 副标题两行），非贪婪会匹配到内层闭合，把外层留下半个残块。
# 因此用标签深度计数精确定位整块。
def find_element_span(s, start):
    """s[start] 必须是 '<'；返回该元素闭合后的下一个字符位置。"""
    m = re.match(r"<([A-Za-z][\w.]*)", s[start:])
    if not m:
        raise ValueError("not an element at %d" % start)
    tag = m.group(1)
    depth = 0
    for t in re.finditer(r"</?([A-Za-z][\w.]*)\b", s[start:]):
        if t.group(1) != tag:
            continue
        closing = s[start + t.start() + 1] == "/"
        gt = s.index(">", start + t.end())
        selfclose = s[gt - 1] == "/"
        if closing:
            depth -= 1
            if depth == 0:
                return gt + 1
        elif not selfclose:
            depth += 1
    raise ValueError("unbalanced <%s>" % tag)


def find_commented_block(s, comment):
    """返回 (块起点, 块终点)，块含注释行与整个元素。起点含该行的缩进。"""
    ci = s.rfind("\n", 0, s.index(comment)) + 1
    lt = s.index("<", s.index(comment) + len(comment))
    return ci, find_element_span(s, lt)


# B 型顶栏下面紧跟的手写分隔线（一并换成 MaterialDivider）。
# 前面必须是 \s* 而不是 [ \t]*：块结束位置正好落在换行后，用 [ \t]* 匹配不上，
# 分隔线会被漏掉，于是同一页同时出现「MaterialDivider + 手写 View 分隔线」两条线。
VIEW_DIVIDER_RE = re.compile(
    r"\s*<View\b[^>]*?android:background=\"@color/divider\"[^>]*?/>\n",
    re.S,
)
# Form A 的内容容器开标签（4 空格缩进 = 根的直接子节点，唯一）
FORMA_CONTAINER_OLD = """    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">
"""
FORMA_CONTAINER_NEW = """        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingHorizontal="20dp"
            android:paddingTop="16dp"
            android:paddingBottom="20dp">
"""


# 根元素：<?xml ...?> 之后的第一个元素，捕获 (标签名, 属性串, 开标签结束位置)
ROOT_RE = re.compile(
    r"<\?xml[^>]*\?>\s*<([A-Za-z][\w.]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)>", re.S
)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def tidy(s):
    """删块之后会留下空行和行尾空格，统一收一下（只影响可读性，不改语义）。"""
    s = re.sub(r"[ \t]+\n", "\n", s)
    s = re.sub(r"\n[ \t]*\n(?:[ \t]*\n)+", "\n\n", s)
    return s


def toolbar(title, subtitle=None, menu=None):
    extra = ""
    if subtitle:
        extra += '\n        app:subtitle="@string/%s"' % subtitle
    if menu:
        extra += '\n        app:menu="@menu/%s"' % menu
    return TOOLBAR_FMT.format(title=title, extra=extra)


def convert_form_a(name, src):
    """ScrollView → LinearLayout(vertical)[Toolbar + Divider + NestedScrollView]"""
    title = FORMA_TITLE[name]

    m = re.search(r"(<\?xml[^>]*\?>\s*)<ScrollView\b(.*?)>", src, re.S)
    if not m:
        raise SystemExit("!! %s: 找不到 ScrollView 根节点" % name)
    head, attrs = m.group(1), m.group(2)
    bg = re.search(r'android:background="([^"]+)"', attrs)
    bg = bg.group(1) if bg else "@color/bg_primary"
    if 'xmlns:app' not in attrs:
        raise SystemExit("!! %s: 根节点缺 xmlns:app" % name)

    if src.count(FORMA_CONTAINER_OLD) != 1:
        raise SystemExit("!! %s: 内容容器开标签不是预期形态" % name)

    out = head + (
        '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:app="http://schemas.android.com/apk/res-auto"\n'
        '    android:layout_width="match_parent"\n'
        '    android:layout_height="match_parent"\n'
        '    android:background="%s"\n'
        '    android:orientation="vertical">\n'
        '\n' % bg
    ) + toolbar(title) + "\n" + DIVIDER + (
        '\n    <androidx.core.widget.NestedScrollView\n'
        '        android:layout_width="match_parent"\n'
        '        android:layout_height="match_parent"\n'
        '        android:fillViewport="true">\n'
        '\n'
    )
    # 砍掉原来的 ScrollView 根开标签
    out += src[m.end():]
    # 内容容器：padding 拆分
    out = out.replace(FORMA_CONTAINER_OLD, FORMA_CONTAINER_NEW, 1)
    # 删掉返回栏
    bi, be = find_commented_block(out, "<!-- 返回栏 -->")
    out = out[:bi] + out[be:]
    # 收尾：</ScrollView> → 关 NestedScrollView + 关外层
    if out.count("</ScrollView>") != 1:
        raise SystemExit("!! %s: </ScrollView> 不唯一" % name)
    out = out.replace(
        "</ScrollView>",
        "    </androidx.core.widget.NestedScrollView>\n</LinearLayout>",
        1,
    )
    return tidy(out)


def convert_form_b(name, src):
    """LinearLayout(vertical) 的第一个子节点"顶栏" → MaterialToolbar"""
    title = FORMB_TITLE[name]
    sub = FORMB_SUBTITLE.get(name)

    rm = ROOT_RE.search(src)
    if not rm:
        raise SystemExit("!! %s: 找不到根节点" % name)
    if "xmlns:app" not in rm.group(2):
        # 根 LinearLayout 没声明 app 命名空间 —— 在标签名后另起一行补上，
        # 并吃掉紧随的空白，避免和后面的 xmlns:android 挤在同一行
        pos = rm.start(1) + len(rm.group(1))
        j = pos
        while j < len(src) and src[j] in " \t":
            j += 1
        src = (
            src[:pos]
            + '\n    xmlns:app="http://schemas.android.com/apk/res-auto"\n    '
            + src[j:]
        )
        rm = ROOT_RE.search(src)
        if "xmlns:app" not in rm.group(2):
            raise SystemExit("!! %s: xmlns:app 注入失败" % name)

    hi, he = find_commented_block(src, "<!-- 顶栏 -->")
    header = src[hi:he]

    # 顶栏里的动作按钮（voice_translate 的 引擎/识别）
    btns = re.findall(
        r'<com\.google\.android\.material\.button\.MaterialButton\b.*?/>', header, re.S
    )
    keep = [b for b in btns if ("btnEngine" in b or "btnLang" in b)]

    # 顶栏 + 紧随的手写分隔线，一起换掉
    tail = src[he:]
    dv = VIEW_DIVIDER_RE.match(tail)
    span_end = he + dv.end() if dv else he

    block = toolbar(title, sub, FORMB_MENU.get(name)) + "\n"
    if keep:
        # 下沉成 app bar 下方的一行胶囊按钮，id / 样式 / 点击监听全部保持不变
        block += (
            '\n    <!-- 引擎 / 识别：文字是运行时刷新的，放 app bar 会挤爆标题，'
            '下沉为工具栏下方的胶囊按钮行 -->\n'
            '    <LinearLayout\n'
            '        android:layout_width="match_parent"\n'
            '        android:layout_height="wrap_content"\n'
            '        android:orientation="horizontal"\n'
            '        android:gravity="center_vertical"\n'
            '        android:paddingHorizontal="16dp"\n'
            '        android:paddingBottom="8dp">\n'
            '\n'
        )
        for b in keep:
            bl = b.splitlines()
            # 标签行 8 空格、属性行 12 空格（原来在顶栏里就是这个相对缩进）
            block += "        " + bl[0].strip() + "\n"
            for ln in bl[1:]:
                block += "            " + ln.strip() + "\n"
            block += "\n"
        block = block.rstrip("\n") + "\n"
        block += "    </LinearLayout>\n"
    block += "\n" + DIVIDER

    return tidy(src[:hi] + block + src[span_end:])


# ---------------------------------------------------------------- phase 2: Kotlin
KT_BACK = ("b.btnBack.setOnClickListener { finish() }",
           "b.topAppBar.setNavigationOnClickListener { finish() }")

KT_TRANSLATE_INPUT = [
    (
        """        b.tvEngineInfo.text = "${engine.displayName} → ${LANG_DISPLAY[App.prefs.targetLang] ?: App.prefs.targetLang}"

        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener {
            b.etInput.setText("")
            b.tvResult.text = getString(R.string.common_tv_result)
        }""",
        """        // v1.19.0：引擎信息从顶栏里的 TextView 改为 M3 app bar 的副标题
        b.topAppBar.subtitle =
            "${engine.displayName} → ${LANG_DISPLAY[App.prefs.targetLang] ?: App.prefs.targetLang}"

        b.topAppBar.setNavigationOnClickListener { finish() }
        // v1.19.0："清空"从顶栏按钮改为 app bar 的菜单动作（M3 工具栏动作的标准位置）
        b.topAppBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear) {
                b.etInput.setText("")
                b.tvResult.text = getString(R.string.common_tv_result)
                true
            } else {
                false
            }
        }""",
    )
]

MENU_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  输入翻译页的 app bar 动作（v1.19.0）。
  原来"清空"是顶栏里的一个 MaterialButton，占着一整块宽度；
  M3 里工具栏动作用 menu 表达，文字按钮直接显示在标题栏右侧。
-->
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <item
        android:id="@+id/action_clear"
        android:title="@string/translate_input_btn_clear"
        app:showAsAction="always" />

</menu>
"""


def main():
    changed = []

    for name in sorted(list(FORMA_TITLE) + list(FORMB_TITLE)):
        p = os.path.join(LAYOUT, name)
        src = read(p)
        out = convert_form_a(name, src) if name in FORMA_TITLE else convert_form_b(name, src)
        if out != src:
            changed.append(os.path.relpath(p, ROOT))
            if not DRY:
                write(p, out)

    # ---- Kotlin
    kt = []
    for fn in sorted(os.listdir(JAVA)):
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(JAVA, fn)
        s = read(p)
        orig = s
        if fn == "TranslateInputActivity.kt":
            for a, b in KT_TRANSLATE_INPUT:
                if a not in s:
                    raise SystemExit("!! TranslateInputActivity.kt: 锚点没找到")
                s = s.replace(a, b, 1)
        elif KT_BACK[0] in s:
            s = s.replace(KT_BACK[0], KT_BACK[1], 1)
        if s != orig:
            kt.append(fn)
            if not DRY:
                write(p, s)

    # ---- menu 资源
    mp = os.path.join(MENU, "menu_translate_input.xml")
    if not os.path.exists(mp):
        kt.append("res/menu/menu_translate_input.xml (新建)")
        if not DRY:
            os.makedirs(MENU, exist_ok=True)
            write(mp, MENU_XML)

    print("布局改写 %d 个：" % len(changed))
    for c in changed:
        print("   ", c)
    print("Kotlin / 资源改写 %d 个：" % len(kt))
    for c in kt:
        print("   ", c)
    if DRY:
        print("\n[dry-run] 未落盘")


if __name__ == "__main__":
    main()
