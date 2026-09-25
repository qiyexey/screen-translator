#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 res/layout/*.xml 里硬编码的 android:text / hint / contentDescription / title
抽到 res/values/strings.xml（v1.18.0）。

命名规则（两遍扫描，兼顾"名字可读"和"同一句只翻译一次"）
--------------------------------------------------------
第一遍先统计每条中文出现在几个布局里，再决定名字：

  · 只出现在 1 个布局 →  <布局简称>_<控件 id 蛇形>      如 settings_btn_back
                        没有 id 时 →  <布局简称>_t<序号>  如 settings_t03

  · 出现在 ≥2 个布局 →  common_<控件 id 蛇形>            如 common_btn_back
                        没有 id 时 →  common_t<序号>      如 common_t01

为什么这么绕：如果简单地"谁先出现就用谁的布局名"，settings 页会出现
`@string/about_btn_back` 这种跨文件引用，读代码的人会以为改错了文件；
但如果完全不去重，同一句「‹ 返回」会在 13 个布局里各留一份资源，
翻译要翻 13 遍，也违背了"统一校对"的初衷。所以：共用文案单独归到 common_ 组。

用法
----
  python scripts/extract-strings.py --dry-run    # 只报告，不写文件
  python scripts/extract-strings.py              # 真正落盘
"""
import glob
import io
import os
import re
import sys
from collections import OrderedDict

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
LAYOUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "layout")
STRINGS_XML = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

CJK = re.compile(r"[\u4e00-\u9fff]")
TAG_RE = re.compile(r"<([A-Za-z_][\w.:-]*)((?:\s+[\w.:-]+\s*=\s*(?:\"[^\"]*\"|'[^']*'))*)\s*/?>")
ID_RE = re.compile(r'android:id\s*=\s*"@\+?id/([\w.]+)"')
TEXT_ATTR_RE = re.compile(r'(android:(?:text|hint|contentDescription|title))\s*=\s*"([^"]*)"')

XML_UNESCAPE = [("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'"), ("&amp;", "&")]


def unescape_xml_attr(s: str) -> str:
    for a, b in XML_UNESCAPE:
        s = s.replace(a, b)
    return s


def escape_android_string(s: str) -> str:
    s = s.replace("&", "&amp;")      # 必须最先做
    s = s.replace("<", "&lt;")
    s = s.replace(">", "&gt;")
    s = s.replace("'", "\\'")
    s = s.replace('"', '\\"')
    if s.startswith("?"):
        s = "\\" + s
    return s


def snake(name: str) -> str:
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", name)
    s = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", s)
    s = re.sub(r"[^A-Za-z0-9_]", "_", s)
    return s.lower().strip("_")


def layout_slug(path: str) -> str:
    base = os.path.basename(path)[:-4]
    return base[len("activity_"):] if base.startswith("activity_") else base


def scan(path: str):
    """返回 [(literal, elem_id, attr_suffix)]，attr_suffix 为空表示 android:text。"""
    src = io.open(path, encoding="utf-8").read()
    found = []
    for m in TAG_RE.finditer(src):
        attrs = m.group(2)
        if not TEXT_ATTR_RE.search(attrs):
            continue
        id_m = ID_RE.search(attrs)
        cid = id_m.group(1) if id_m else None
        for am in TEXT_ATTR_RE.finditer(attrs):
            attr, raw = am.group(1), am.group(2)
            if raw.startswith("@") or raw.startswith("?"):
                continue
            value = unescape_xml_attr(raw)
            if not CJK.search(value):
                continue
            suffix = "" if attr == "android:text" else snake(attr.split(":")[1])
            found.append((value, cid, suffix))
    return found


def main() -> int:
    dry = "--dry-run" in sys.argv
    layouts = sorted(glob.glob(os.path.join(LAYOUT_DIR, "*.xml")))

    # ---------- 第一遍：统计 ----------
    per_file = {p: scan(p) for p in layouts}
    files_of = {}
    for p, items in per_file.items():
        for value, _, _ in items:
            files_of.setdefault(value, set()).add(p)

    existing = io.open(STRINGS_XML, encoding="utf-8").read()
    used_names = set(re.findall(r'<string name="([^"]+)"', existing))

    # ---------- 第二遍：定名 ----------
    literal_to_name = {}
    name_to_value = OrderedDict()
    name_owner = {}
    counters = {}

    # 先处理多文件共用的，再处理单文件的；同组内按"有 id 优先"排序，名字更可读
    def plan_for(value, candidates):
        """candidates: [(slug, cid, suffix)] → 资源名（不含去重后缀）"""
        shared = len(files_of[value]) >= 2
        prefix = "common" if shared else candidates[0][0]
        with_id = [c for c in candidates if c[1]]
        if with_id:
            slug, cid, suffix = with_id[0]
            base = snake(cid) + (f"_{suffix}" if suffix else "")
        else:
            counters[prefix] = counters.get(prefix, 0) + 1
            base = f"t{counters[prefix]:02d}"
        return f"{prefix}_{base}"

    ordered_literals = sorted(files_of.keys(), key=lambda v: (-len(files_of[v]), v))
    for value in ordered_literals:
        candidates = []
        for p in layouts:
            for lit, cid, suffix in per_file[p]:
                if lit == value:
                    candidates.append((layout_slug(p), cid, suffix))
        cand = plan_for(value, candidates)
        name = cand
        n = 2
        while name in used_names:
            name = f"{cand}_{n}"
            n += 1
        used_names.add(name)
        literal_to_name[value] = name
        name_to_value[name] = value
        name_owner[name] = os.path.basename(sorted(files_of[value])[0])

    # ---------- 第三遍：改写布局 ----------
    touched = []
    problems = []
    for path in layouts:
        src = io.open(path, encoding="utf-8").read()

        def repl_tag(m: re.Match) -> str:
            attrs = m.group(2)
            if not TEXT_ATTR_RE.search(attrs):
                return m.group(0)

            def repl_attr(am: re.Match) -> str:
                attr, raw = am.group(1), am.group(2)
                if raw.startswith("@") or raw.startswith("?"):
                    return am.group(0)
                value = unescape_xml_attr(raw)
                if not CJK.search(value):
                    return am.group(0)
                name = literal_to_name[value]
                for ch in ("%", "@", "$", "{", "}"):
                    if ch in value:
                        problems.append((os.path.basename(path), name, ch, value[:40]))
                return f'{attr}="@string/{name}"'

            new_attrs = TEXT_ATTR_RE.sub(repl_attr, attrs)
            return m.group(0).replace(attrs, new_attrs, 1) if new_attrs != attrs else m.group(0)

        out = TAG_RE.sub(repl_tag, src)
        if out != src:
            cnt = sum(1 for v, _, _ in per_file[path])
            touched.append((os.path.basename(path), cnt))
            if not dry:
                io.open(path, "w", encoding="utf-8", newline="\n").write(out)

    shared = [v for v in files_of if len(files_of[v]) >= 2]
    print(f"布局 {len(touched)} 个 / 抽出 {sum(c for _, c in touched)} 处 / "
          f"新增资源 {len(name_to_value)} 条（其中多页共用 {len(shared)} 条 → common_*）\n")
    for f, c in touched:
        print(f"  {f:34s} {c:3d} 条")

    if problems:
        print("\n需要人工确认的字符：")
        for f, name, ch, sample in problems:
            print(f"  [{ch}] {name} ({f}) {sample}")

    if dry:
        print("\n[dry-run] 未写入任何文件")
        return 0

    block = ["\n    <!-- ==================== 界面文案（v1.18.0 从 layout 抽离）=================== -->",
             "    <!-- 命名：单页独占 → <布局简称>_<id|序号>；多页共用 → common_<id|序号>。 -->"]
    cur = None
    for name, value in name_to_value.items():
        owner = name_owner[name]
        if owner != cur:
            cur = owner
            block.append(f"\n    <!-- {owner} -->")
        block.append(f'    <string name="{name}">{escape_android_string(value)}</string>')

    assert existing.rstrip().endswith("</resources>")
    body = existing.rstrip()[: -len("</resources>")].rstrip("\n")
    io.open(STRINGS_XML, "w", encoding="utf-8", newline="\n").write(
        body + "\n" + "\n".join(block) + "\n\n</resources>\n"
    )
    print(f"\n已写入 {len(name_to_value)} 条到 strings.xml")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
