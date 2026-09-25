#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文案抽离后的自检：布局 XML 合法性 + 残留硬编码 + @string 引用完整性。"""
import glob
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
CJK = re.compile(r"[\u4e00-\u9fff]")
ATTR_RE = re.compile(r'android:(?:text|hint|contentDescription|title)\s*=\s*"([^"]*)"')
REF_RE = re.compile(r'@string/([\w.]+)')

fail = 0


def check(cond, msg):
    global fail
    print(("  [OK] " if cond else "  [FAIL] ") + msg)
    if not cond:
        fail += 1


# ---------- 1. 全部 XML 可解析 ----------
print("1) XML 合法性")
xml_files = sorted(glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True))
bad = []
for f in xml_files:
    try:
        ET.parse(f)
    except Exception as e:  # noqa: BLE001
        bad.append((os.path.relpath(f, RES), e))
check(not bad, f"{len(xml_files)} 个 XML 全部可解析")
for f, e in bad:
    print(f"        {f}: {e}")

# ---------- 2. 布局里不再有硬编码中文 ----------
print("\n2) 布局残留硬编码中文")
leftover = []
for f in sorted(glob.glob(os.path.join(RES, "layout", "*.xml"))):
    for v in ATTR_RE.findall(io.open(f, encoding="utf-8").read()):
        if CJK.search(v):
            leftover.append((os.path.basename(f), v[:30]))
check(not leftover, f"layout/*.xml 中 android:text/hint/contentDescription/title 已无中文")
for f, v in leftover[:10]:
    print(f"        {f}: {v}")

# ---------- 3. @string 引用完整性 ----------
print("\n3) @string 引用完整性")
defined = set()
for f in sorted(glob.glob(os.path.join(RES, "values*", "strings.xml"))):
    defined |= set(re.findall(r'<string name="([^"]+)"', io.open(f, encoding="utf-8").read()))
missing = []
for f in xml_files:
    if os.sep + "values" in f:
        continue
    for ref in REF_RE.findall(io.open(f, encoding="utf-8").read()):
        if ref not in defined:
            missing.append((os.path.relpath(f, RES), ref))
check(not missing, f"{len(defined)} 条字符串资源覆盖了全部布局/清单引用")
for f, r in missing[:10]:
    print(f"        {f} -> @string/{r}")

# ---------- 4. 资源名合法且不重复 ----------
print("\n4) 资源名合法性")
# 扫**所有** values*/strings.xml，不只 values/ —— 新增的翻译目录
# （values-en 等）里同名资源是合法的「同资源多语言」，但如果哪天有人在
# values-en/strings.xml 里手滑复制了一条、或在同一个文件里贴了两遍，
# 只有全扫才能发现。同名同目录才是错误。
name_owner = {}
for f in sorted(glob.glob(os.path.join(RES, "values*", "strings.xml"))):
    d = os.path.basename(os.path.dirname(f))
    for n in re.findall(r'<string name="([^"]+)"', io.open(f, encoding="utf-8").read()):
        name_owner.setdefault((d, n), []).append(f)

names = re.findall(
    r'<string name="([^"]+)"',
    io.open(os.path.join(RES, "values", "strings.xml"), encoding="utf-8").read(),
)
ill = [n for n in names if not re.fullmatch(r"[a-z0-9_]+", n)]
# **同一个文件内**重复才算错。这里必须报出**全部**重复项而不是第一条：
# v1.20.0 实测踩到「新增资源编号撞了已有编号」，脚本当时只报了其中一个，
# 改完第一个再跑才暴露第二个 —— 一次构建 3 分钟，来回两轮就是 6 分钟。
dup = sorted({n for n in names if names.count(n) > 1})
check(not ill, "资源名均为 [a-z0-9_]")
check(not dup, "无重复资源名")
for n in ill[:10]:
    print(f"        非法: {n}")
for n in dup[:20]:
    # 把每条重复的出现位置也打出来，省掉一次 grep
    where = [f"{os.path.relpath(p, RES)}" for (d, nm), ps in name_owner.items()
             if nm == n for p in ps]
    print(f"        重复: {n}  出现于 {'、'.join(sorted(set(where)))}")
if dup:
    print(f"        （共 {len(dup)} 个重复名；新增资源前先跑一次本脚本可避免撞号）")

# ---------- 5. 主题里引用的 style parent / attr 是否真实存在 ----------
#
# v1.20.0 新增。动机：v1.19.0 的 M3 改造里写错了两个名字，
#   · `<item name="materialToolbarStyle">`  —— 该 attr 不存在（真名 toolbarStyle）
#   · `parent="Widget.Material3.Divider"`   —— 该 style 不存在（真名 Widget.Material3.MaterialDivider）
# 两者都只在 **AAPT2 链接期** 报错，本地没有 JDK/SDK 时完全看不出来。
# 结果第一次真编译就在这里卡住（见 FIXES-1.20.0.md）。
#
# 白名单来自 scripts/material-res-names.txt —— 由 gen-material-names.py
# 从 material AAR 机械抽取（148 attr / 752 style）。**不要手写白名单**：
# 初版手写时立刻误报 14 条（Widget.Material3.Toolbar、TextAppearance.Material3.*
# 等其实全都存在），因为靠人列 900 个名字必然漏。
print("\n5) 主题里的 Material 资源名")
NAMES_FILE = os.path.join(ROOT, "scripts", "material-res-names.txt")
MATERIAL_ATTRS, MATERIAL_STYLES = set(), set()
if os.path.exists(NAMES_FILE):
    for line in io.open(NAMES_FILE, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        kind, _, name = line.partition(" ")
        (MATERIAL_ATTRS if kind == "A" else MATERIAL_STYLES).add(name)
else:
    print("  [FAIL] 缺少 scripts/material-res-names.txt")
    print("        先跑：python scripts/gen-material-names.py")
    fail += 1

theme_files = sorted(glob.glob(os.path.join(RES, "values*", "themes.xml")))
suspect_attr, suspect_style = [], []
own_styles = set()
for f in theme_files:
    own_styles |= set(re.findall(r'<style name="([^"]+)"', io.open(f, encoding="utf-8").read()))

# android: 开头的走 framework，不在这里校验；本工程自己的 style 也算已存在
def known_style(n):
    return (n in own_styles or n in MATERIAL_STYLES
            or n.startswith("@style/") or n.startswith("android:"))

for f in theme_files:
    rel = os.path.relpath(f, RES)
    body = io.open(f, encoding="utf-8").read()
    # <item name="xxx">@style/yyy</item>
    for m in re.finditer(r'<item name="([^"]+)"\s*>\s*@style/([\w.]+)', body):
        attr, target = m.group(1), m.group(2)
        if not attr.startswith("android:") and attr not in MATERIAL_ATTRS:
            suspect_attr.append((rel, attr))
        if not known_style(target):
            suspect_style.append((rel, target))
    # parent="..."
    for m in re.finditer(r'<style name="[^"]+"\s+parent="([^"]+)"', body):
        p = m.group(1)
        if not known_style(p):
            suspect_style.append((rel, p))
    # 所有 <item name="attr"> 都要核对 attr 名本身是否存在。
    #
    # v1.20.2 新增：原来只查了 value 形如 @style/xxx 的那些 item，
    # 漏掉了「attr 名写错」这一半 —— 而它同样是 AAPT2 链接期才报错。
    # 每次都要把 <item name="..."> 的 name 拿去 MATERIAL_ATTRS 里对。
    for m in re.finditer(r'<item\s+name="([^"]+)"', body):
        a = m.group(1)
        if a.startswith("android:") or a in MATERIAL_ATTRS:
            continue
        # 本工程自己声明的 attr（如有 res/values/attrs.xml）也算数
        suspect_attr.append((rel, a))

check(bool(MATERIAL_ATTRS) and not suspect_attr,
      "theme 里引用的 attr 名均存在（%d 个已核对：material + appcompat + 平台 framework）"
      % len(MATERIAL_ATTRS))
for rel, a in suspect_attr[:15]:
    print(f"        {rel}: attr「{a}」不存在 —— AAPT2 会报 'style attribute not found'")
    print(f"          若确认拼写没错，可能是白名单漏了来源库："
          f"往 gen-material-names.py 的 TARGETS 里补一行再重跑")
check(not suspect_style,
      "theme 里引用的 style 名（含 parent）均存在")
for rel, s in suspect_style[:15]:
    print(f"        {rel}: style「{s}」不存在 —— 常见笔误：Divider 应为 MaterialDivider")

# 自检：确认这套校验真的能抓到 v1.19.0 踩过的两个坑。
# 否则 whitelist 变成"什么都能过"时，本检查会静默退化成永远 OK。
if MATERIAL_ATTRS and MATERIAL_STYLES:
    probe_ok = ("materialToolbarStyle" not in MATERIAL_ATTRS
                and "Widget.Material3.Divider" not in MATERIAL_STYLES)
    check(probe_ok,
          "自检：已知的错误名（materialToolbarStyle / Widget.Material3.Divider）不在白名单里")

# ---------- 6. 布局里每个 View 是否都写了 layout_width / layout_height ----------
#
# v1.20.0 新增。动机是一次**线上级闪退**：13 个页面的 MaterialDivider 都没写
# layout_height，因为我在样式注释里想当然地写了「MaterialDivider 自己会在
# onMeasure 里管高度」。结果启动即崩：
#
#     FATAL EXCEPTION: main
#     InflateException: Binary XML file line #113 ... 
#     Caused by: UnsupportedOperationException:
#       You must supply a layout_height attribute.
#       at ViewGroup$LayoutParams.setBaseAttributes
#       at LinearLayout.generateLayoutParams
#
# 关键：LayoutParams 是在 **inflate 阶段**构造的，早于 onMeasure ——
# 所以「控件自己能管高度」根本救不了「缺 layout_* 就直接抛异常」。
#
# 为什么之前 3 套校验都没抓到：
#   · verify-m3.py 查 XML 语法合法性与 id 引用 —— 缺属性不影响语法合法；
#   · AAPT2 编译也不报错 —— 它是**运行期** inflate 时才炸。
# 也就是说这类错误**编译能过、静态检查能过，只有真跑起来才炸**。
# 所以必须在静态检查里专门补一条。
print("\n6) 布局里每个 View 的 layout_width / layout_height")
SKIP_TAGS = {"merge", "include", "requestFocus", "item", "layout", "data",
             "variable", "import"}
bad_lp = []
for f in sorted(glob.glob(os.path.join(RES, "layout*", "*.xml"))):
    rel = os.path.relpath(f, RES)
    src = io.open(f, encoding="utf-8").read()
    body = re.sub(r'<!--.*?-->', '', src, flags=re.S)   # 去掉注释，避免示例被误判
    for m in re.finditer(r'<([A-Za-z_][\w.]*)\b([^>]*?)(/?)>', body, re.S):
        tag, attrs = m.group(1), m.group(2)
        if tag in SKIP_TAGS:
            continue
        line = body[:m.start()].count("\n") + 1
        miss = []
        if not re.search(r'android:layout_width\s*=', attrs):
            miss.append("layout_width")
        if not re.search(r'android:layout_height\s*=', attrs):
            miss.append("layout_height")
        if miss:
            bad_lp.append((rel, line, tag, miss))

check(not bad_lp, f"布局中 {len(glob.glob(os.path.join(RES, 'layout*', '*.xml')))} 个布局的 View 布局参数完整")
for rel, line, tag, miss in bad_lp[:20]:
    print(f"        {rel}:{line}  <{tag}> 缺 {', '.join(miss)}")
    print(f"           → 运行期会抛 UnsupportedOperationException，App 启动即闪退")
if len(bad_lp) > 20:
    print(f"        …另有 {len(bad_lp) - 20} 处")

print("\n" + ("全部通过 ✅" if fail == 0 else f"{fail} 项未通过 ❌"))
raise SystemExit(1 if fail else 0)
