#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
界面 M3 化改动的静态校验（v1.19.0）。

为什么需要这个脚本
------------------
本机没有 JDK / Android SDK / NDK，**改动无法编译、也无法跑起来看**。
AGP 的编译期检查（资源存在性、ViewBinding 字段、XML 合法性）拿不到，
只能自己静态复现一遍。下面每一条都对应一个"编译期本来会拦住、
但这里拦不住就会漏到用户手上"的失败模式：

  1. XML 合法性      —— 手改布局最容易留下不配对的标签。
  2. id ↔ Kotlin 绑定 —— ViewBinding 会给每个 @+id 生成字段。改了布局里 id、
                        忘了改 Kotlin（或反过来），是 100% 的编译错误。
                        双向都要查：Kotlin 用到但布局没有（编译失败），
                        布局有但 Kotlin 没用（提示，可能是历史遗留）。
  3. 资源引用完整性   —— @string/@color/@style/@drawable 写错一个名字就编译失败。
  4. style parent     —— parent 指向不存在的本地样式同样编译失败。
  5. 组件残留         —— 换完之后不该再出现 SeekBar / Spinner。
  6. Kotlin 结构      —— 括号平衡（脚本批量改代码时的兜底）。
  7. Slider 取值约束  —— M3 Slider 的 value 越界会**运行期抛异常**，
                        而 SeekBar 只会静默钳制。XML 里 value ≤ valueTo 必须成立。
  8. 下拉框结构       —— MaterialAutoCompleteTextView 必须是
                        TextInputLayout(ExposedDropdownMenu) 的子节点，
                        否则 Material 会直接抛异常，而且下拉弹不出来。
  9. 未使用 import    —— 只是警告，顺手清干净。

用法
----
    python scripts/verify-m3.py        # 0 退出码 = 全部通过
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
LAYOUT = os.path.join(RES, "layout")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

COMMENT = re.compile(r"<!--.*?-->", re.S)
FAILS = []


def fail(section, msg):
    FAILS.append((section, msg))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def rel(p):
    return os.path.relpath(p, ROOT).replace("\\", "/")


def no_comments(s):
    return COMMENT.sub("", s)


# ------------------------------------------------------------------ 1 XML
def check_xml():
    n = 0
    for f in sorted(
        glob.glob(RES + "/layout/*.xml")
        + glob.glob(RES + "/menu/*.xml")
        + glob.glob(RES + "/values*/*.xml")
        + glob.glob(RES + "/xml/*.xml")
        + glob.glob(RES + "/drawable/*.xml")
        + [os.path.join(ROOT, "app/src/main/AndroidManifest.xml")]
    ):
        try:
            ET.parse(f)
            n += 1
        except Exception as e:
            fail("XML 合法性", "%s -> %s" % (rel(f), e))
    return "%d 个 XML 文件解析通过" % n


# ------------------------------------------------------- 2 id ↔ Kotlin 绑定
def check_bindings():
    def lay_of(cls):
        base = cls[:-8] if cls.endswith("Activity") else cls
        return "activity_" + re.sub(r"(?<!^)(?=[A-Z])", "_", base).lower() + ".xml"

    pairs = 0
    for kt in sorted(glob.glob(JAVA + "/**/*Activity.kt", recursive=True)):
        cls = os.path.basename(kt)[:-3]
        lay = os.path.join(LAYOUT, lay_of(cls))
        if not os.path.exists(lay):
            continue
        # `::b.isInitialized` 是 Kotlin 对 lateinit 属性 b 的状态查询，
        # 不是 ViewBinding 里的 `b.isInitialized` 字段。原 regex 会把它误判成
        # 布局缺 id；排除 root 的同时也必须排除这个语言关键字属性。
        ids = set(re.findall(r"\bb\.([A-Za-z_]\w*)", read(kt))) - {"root", "isInitialized"}
        if not ids:
            continue
        have = set(re.findall(r'@\+id/([A-Za-z_]\w*)', read(lay)))
        pairs += 1
        for miss in sorted(ids - have):
            fail("绑定", "%s 用到 b.%s，但 %s 里没有这个 id" % (cls, miss, lay_of(cls)))

    # 反向：布局有 id 但 Kotlin 全项目都没提到（提示项）
    allkt = "\n".join(
        read(p) for p in glob.glob(JAVA + "/**/*.kt", recursive=True)
    )
    unused = []
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        for i in sorted(set(re.findall(r'@\+id/([A-Za-z_]\w*)', read(lay)))):
            if i not in allkt:
                unused.append("%s: %s" % (os.path.basename(lay), i))
    return "%d 个 Activity 的绑定全部命中；布局里 %d 个 id 未被任何 Kotlin 引用%s" % (
        pairs,
        len(unused),
        ("（" + "; ".join(unused) + "）") if unused else "",
    )


# --------------------------------------------------------- 3 资源引用完整性
def collect_values():
    out = {}
    for f in glob.glob(RES + "/values*/*.xml"):
        for c in ET.parse(f).getroot():
            if c.get("name"):
                out.setdefault(c.tag, set()).add(c.get("name"))
    return out


def collect_files():
    files = {}
    for d in os.listdir(RES):
        p = os.path.join(RES, d)
        if os.path.isdir(p):
            for fn in os.listdir(p):
                files.setdefault(d.split("-")[0], set()).add(os.path.splitext(fn)[0])
    return files


# Material / AppCompat 提供的样式，本地查不到是正常的
LIB_PREFIX = (
    "Theme.Material3", "Widget.Material3", "TextAppearance.Material3",
    "Theme.MaterialComponents", "Widget.MaterialComponents",
    "TextAppearance.MaterialComponents", "Theme.AppCompat", "Widget.AppCompat",
    "TextAppearance.AppCompat", "Base.", "ThemeOverlay.",
)


def check_resources():
    V, F = collect_values(), collect_files()
    ref = re.compile(
        r"@(?:\+)?(string|color|dimen|style|drawable|menu|layout|mipmap|bool|integer|array|xml|anim)"
        r"/([A-Za-z_][\w.]*)"
    )
    kt = re.compile(
        r"(?<!android\.)\bR\.(string|color|dimen|style|drawable|menu|layout|mipmap|bool|integer|array|xml|anim)"
        r"\.([A-Za-z_]\w*)"
    )
    targets = (
        glob.glob(RES + "/layout/*.xml")
        + glob.glob(RES + "/menu/*.xml")
        + glob.glob(RES + "/values*/*.xml")
        + glob.glob(RES + "/xml/*.xml")
        + glob.glob(JAVA + "/**/*.kt", recursive=True)
        + [os.path.join(ROOT, "app/src/main/AndroidManifest.xml")]
    )
    seen = set()
    total = 0
    for t in targets:
        s = no_comments(read(t))
        for m in list(ref.finditer(s)) + list(kt.finditer(s)):
            typ, name = m.group(1), m.group(2)
            pool = V.get(typ) if typ in V else F.get(typ)
            if pool is None:
                continue
            total += 1
            if name not in pool and not name.startswith(LIB_PREFIX):
                key = (typ, name)
                if key not in seen:
                    seen.add(key)
                    fail("资源引用", "找不到 @%s/%s（首次出现在 %s）" % (typ, name, rel(t)))

    # style parent
    parents = 0
    for f in glob.glob(RES + "/values*/*.xml"):
        for st in ET.parse(f).getroot().findall("style"):
            par = st.get("parent") or ""
            if not par or par.startswith("android") or par.startswith(LIB_PREFIX):
                continue
            parents += 1
            if par not in V.get("style", ()):
                fail("style parent", "%s 的 parent=%s 不存在" % (rel(f), par))
    return "%d 处引用、%d 个本地 style parent 全部可解析" % (total, parents)


# ------------------------------------------------------------- 4 组件残留
def check_components():
    stat = {}
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        for tag, key in (
            ("<SeekBar", "SeekBar"),
            ("<Spinner", "Spinner"),
            ("<ImageButton", "ImageButton"),
        ):
            c = s.count(tag)
            if c:
                stat[key] = stat.get(key, 0) + c
                fail("组件残留", "%s 还有 %d 个 %s" % (os.path.basename(lay), c, key))
    return "无 SeekBar / Spinner / ImageButton 残留" if not stat else "见上"


# ------------------------------------------------------------ 5 Kotlin 结构
def scan_balance(src):
    """
    逐字符扫描统计括号。**不能**用"先删 // 注释、再删字符串"的正则做法：
    // 的剥离必须发生在字符串之后，否则 "https://..." 这类字面量会被当成注释，
    整行剩下的部分（含右括号）一起消失，于是所有带 URL 的文件都报"括号不平衡"
    —— 那是校验器的假阳性，不是代码的问题。
    """
    counts = {"{": 0, "}": 0, "(": 0, ")": 0}
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c == "/" and src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):          # 原始字符串
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"' or c == "'":              # 普通字符串 / 字符字面量
            quote = c
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if c in counts:
            counts[c] += 1
        i += 1
    return counts


def check_kotlin_syntax():
    n = 0
    for kt in sorted(glob.glob(JAVA + "/**/*.kt", recursive=True)):
        c = scan_balance(read(kt))
        n += 1
        for o, cl in (("{", "}"), ("(", ")")):
            if c[o] != c[cl]:
                fail("Kotlin 结构", "%s %s%s 不平衡 %s=%d %s=%d"
                     % (rel(kt), o, cl, o, c[o], cl, c[cl]))
    return "%d 个 Kotlin 文件括号平衡" % n


# -------------------------------------------------------- 6 Slider 取值约束
def check_slider():
    n = 0
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        for m in re.finditer(
            r"<com\.google\.android\.material\.slider\.Slider\b(.*?)/>", s, re.S
        ):
            blk = m.group(1)
            n += 1
            i = re.search(r'@\+id/(\w+)', blk)
            i = i.group(1) if i else "?"
            f = re.search(r'android:valueFrom="([-\d.]+)"', blk)
            t = re.search(r'android:valueTo="([-\d.]+)"', blk)
            v = re.search(r'android:value="([-\d.]+)"', blk)
            if not t:
                fail("Slider", "%s(%s) 缺 valueTo —— 会退化成 0..1，滑杆基本不动" % (lay, i))
                continue
            lo = float(f.group(1)) if f else 0.0
            hi = float(t.group(1))
            if lo >= hi:
                fail("Slider", "%s(%s) valueFrom=%s 不小于 valueTo=%s" % (lay, i, lo, hi))
            if v:
                val = float(v.group(1))
                if not (lo <= val <= hi):
                    fail("Slider", "%s(%s) value=%s 不在 [%s, %s] 内 —— 运行期会抛异常"
                         % (lay, i, val, lo, hi))
                if abs((val - lo) % 1.0) > 1e-6:
                    fail("Slider", "%s(%s) value=%s 不是 stepSize=1 的整数倍" % (lay, i, val))
    return "%d 个 Slider 的取值约束成立" % n


# ------------------------------------------------------------ 7 下拉框结构
def check_dropdown():
    n = 0
    for lay in sorted(glob.glob(LAYOUT + "/*.xml")):
        s = read(lay)
        for m in re.finditer(
            r"<com\.google\.android\.material\.textfield\.TextInputLayout\b(.*?)</com\.google\.android\.material\.textfield\.TextInputLayout>",
            s,
            re.S,
        ):
            blk = m.group(1)
            if "MaterialAutoCompleteTextView" not in blk:
                continue
            n += 1
            if "Widget.ScreenTranslator.DropdownLayout" not in blk:
                fail("下拉框", "%s 的 TextInputLayout 没套 ExposedDropdownMenu 样式" % lay)
            if 'android:inputType="none"' not in blk:
                # inputType=none 是 ExposedDropdownMenu 的硬性要求：
                # 不设的话软键盘会弹出来，而且 selPos 的"按文本反查"前提就不成立了
                fail("下拉框", "%s 的 MaterialAutoCompleteTextView 缺 inputType=none" % lay)
    return "%d 个 M3 下拉结构正确" % n


# ---------------------------------------------------------- 8 未使用 import
def check_unused_imports():
    bad = []
    for kt in sorted(glob.glob(JAVA + "/**/*.kt", recursive=True)):
        s = read(kt)
        body = re.sub(r"^import .*$", "", s, flags=re.M)
        for m in re.finditer(r"^import\s+([\w.]+)$", s, flags=re.M):
            cls = m.group(1).split(".")[-1]
            if not re.search(r"\b" + re.escape(cls) + r"\b", body):
                bad.append("%s: %s" % (os.path.basename(kt), m.group(1)))
    return "无未使用 import" if not bad else "未使用：" + "; ".join(bad)


SECTIONS = [
    ("1. XML 合法性", check_xml),
    ("2. id ↔ Kotlin 绑定", check_bindings),
    ("3. 资源引用完整性", check_resources),
    ("4. 组件残留", check_components),
    ("5. Kotlin 括号平衡", check_kotlin_syntax),
    ("6. Slider 取值约束", check_slider),
    ("7. M3 下拉结构", check_dropdown),
    ("8. 未使用 import", check_unused_imports),
]


def main():
    print("=" * 72)
    print("界面 M3 化 静态校验")
    print("=" * 72)
    for name, fn in SECTIONS:
        before = len(FAILS)
        try:
            summary = fn()
        except Exception as e:
            fail(name, "校验器自身异常：%r" % (e,))
            summary = "(校验器异常)"
        mark = "OK  " if len(FAILS) == before else "FAIL"
        print("%s %-22s %s" % (mark, name, summary))

    print("-" * 72)
    if FAILS:
        print("发现 %d 个问题：" % len(FAILS))
        for sec, msg in FAILS:
            print("  [%s] %s" % (sec, msg))
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
