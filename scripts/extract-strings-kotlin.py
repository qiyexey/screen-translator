#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Kotlin 里的 UI 文案抽到 strings.xml（v1.18.0）。

只处理**纯字面量**，即字符串里不含 `$`（插值）也不含 `\\`（转义序列）。
含插值的要改成 getString(id, args)，参数顺序必须人工确认，脚本盲改会出错；
含 `\\n` 的虽然可以照搬，但同一批里混着改更容易漏，索性一起留给人工。

覆盖 4 种写法：
    toast("已保存")                    -> toast(getString(R.string.x))
    xxx.text = "未授予相机权限"         -> xxx.text = getString(R.string.x)
    xxx.setText("已保存")              -> xxx.setText(R.string.x)
    Toast.makeText(this, "翻译失败", T) -> Toast.makeText(this, getString(R.string.x), T)

非 Activity（自定义 View，如 RoiPickerView）用 context.getString(...)，
因为 View 本身没有 getString。

命名：优先复用 strings.xml 里已有的同文案资源；否则跨文件共用 → common_*，
单文件独占 → <文件简称>_t<序号>。

安全性：匹配前先把 `//` 行注释、`/* */` 块注释按**等长空格**遮蔽，
因此"注释里恰好写了一句 .text = \"中文\""不会被误改；替换按原始偏移倒序进行。
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
JAVA_DIR = os.path.join(ROOT, "app", "src", "main", "java")
STRINGS_XML = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

CJK = re.compile(r"[\u4e00-\u9fff]")
SAFE = r'[^"\\$]*'          # 纯字面量：不含转义、不含插值

PATTERNS = [
    re.compile(r'\btoast\("(' + SAFE + r')"\)'),
    re.compile(r'\.setText\("(' + SAFE + r')"\)'),
    re.compile(r'\.text\s*=\s*"(' + SAFE + r')"'),
    re.compile(r'Toast\.makeText\(([^,()]+),\s*"(' + SAFE + r')"'),
]


def mask_comments(src: str) -> str:
    """把注释换成等长空格，保持所有偏移不变。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if src[i] == '"':          # 跳过普通字符串，避免把 "http://" 里的 // 当注释
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        i += 1
    return "".join(out)


def slug_of(path: str) -> str:
    base = os.path.basename(path)[:-3]
    for suf in ("Activity", "View"):
        if base.endswith(suf):
            base = base[: -len(suf)]
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", base)
    return re.sub(r"[^A-Za-z0-9_]", "_", s).lower().strip("_")


def unescape_android(s: str) -> str:
    return s.replace("\\'", "'").replace('\\"', '"').replace("&lt;", "<") \
            .replace("&gt;", ">").replace("&amp;", "&")


def escape_android_string(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    return ("\\" + s) if s.startswith("?") else s


def receiver_for(path: str) -> str:
    return "" if os.path.basename(path).endswith("Activity.kt") else "context."


def build_replacement(pat_idx: int, m: re.Match, recv: str, name: str) -> str:
    if pat_idx == 0:
        return f'toast({recv}getString(R.string.{name}))'
    if pat_idx == 1:
        return f'.setText(R.string.{name})'
    if pat_idx == 2:
        return f'.text = {recv}getString(R.string.{name})'
    return f'Toast.makeText({m.group(1)}, {recv}getString(R.string.{name})'


def main() -> int:
    dry = "--dry-run" in sys.argv
    files = sorted(glob.glob(os.path.join(JAVA_DIR, "**", "*.kt"), recursive=True))

    existing = io.open(STRINGS_XML, encoding="utf-8").read()
    used = set(re.findall(r'<string name="([^"]+)"', existing))
    val2name = {}   # 已有资源：文案 -> 资源名（复用，避免同句两处定义）
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', existing, re.S):
        val2name.setdefault(unescape_android(m.group(2)), m.group(1))

    # ---------- 第一遍：找出所有可改点，统计文案分布 ----------
    plan = {}       # path -> [(pat_idx, start, end, literal, match)]
    files_of = {}
    for p in files:
        src = io.open(p, encoding="utf-8").read()
        masked = mask_comments(src)
        got = []
        for idx, pat in enumerate(PATTERNS):
            for m in pat.finditer(masked):
                lit = m.group(m.lastindex)
                if not CJK.search(lit):
                    continue
                got.append((idx, m.start(), m.end(), lit, m))
                files_of.setdefault(lit, set()).add(p)
        if got:
            plan[p] = got

    if not files_of:
        print("没有找到可安全抽离的纯字面量")
        return 0

    # ---------- 定名 ----------
    lit2name = {}
    name2val = OrderedDict()
    counters = {}
    reused = 0

    def next_index(prefix: str) -> int:
        """
        接续该前缀已有的最大序号，而不是从 01 重新开始。

        否则布局抽离已经占用了 voice_translate_t01…t10，Kotlin 段又从头数，
        会撞出一堆 `voice_translate_t01_2` 这种名字 —— 能跑，但读起来像事故现场。
        """
        if prefix not in counters:
            mx = 0
            for n in used:
                m = re.fullmatch(re.escape(prefix) + r"_t(\d+)", n)
                if m:
                    mx = max(mx, int(m.group(1)))
            counters[prefix] = mx
        counters[prefix] += 1
        return counters[prefix]

    for lit in sorted(files_of, key=lambda v: (-len(files_of[v]), v)):
        if lit in val2name:                      # 已有同文案资源 → 直接复用
            lit2name[lit] = val2name[lit]
            reused += 1
            continue
        prefix = "common" if len(files_of[lit]) >= 2 else slug_of(sorted(files_of[lit])[0])
        cand = f"{prefix}_t{next_index(prefix):02d}"
        name, n = cand, 2
        while name in used:
            name = f"{cand}_{n}"
            n += 1
        used.add(name)
        lit2name[lit] = name
        name2val[name] = lit

    # ---------- 第二遍：按偏移倒序替换 ----------
    touched = []
    for p, got in plan.items():
        src = io.open(p, encoding="utf-8").read()
        recv = receiver_for(p)
        edits = []
        for idx, s, e, lit, m in got:
            if lit not in lit2name:
                continue
            edits.append((s, e, build_replacement(idx, m, recv, lit2name[lit])))
        edits.sort(key=lambda x: -x[0])
        out, last_start = src, len(src) + 1
        for s, e, rep in edits:
            if e > last_start:       # 与上一次替换重叠，跳过（防御）
                continue
            out = out[:s] + rep + out[e:]
            last_start = s
        if out == src:
            continue
        if not dry:
            if "import com.hunter.screentranslator.R" not in out:
                lines = out.split("\n")
                idxs = [i for i, l in enumerate(lines) if l.startswith("import ")]
                pos = next((i for i in idxs if lines[i] > "import com.hunter.screentranslator.R"),
                           idxs[-1] + 1)
                lines.insert(pos, "import com.hunter.screentranslator.R")
                out = "\n".join(lines)
            io.open(p, "w", encoding="utf-8", newline="\n").write(out)
        touched.append((os.path.relpath(p, JAVA_DIR).replace("\\", "/"), len(edits)))

    shared = sum(1 for v in files_of.values() if len(v) >= 2)
    print(f"文件 {len(touched)} 个 / 改写 {sum(c for _, c in touched)} 处 / "
          f"新增资源 {len(name2val)} 条（复用已有 {reused} 条，多文件共用 {shared} 条）\n")
    for f, c in sorted(touched, key=lambda x: -x[1]):
        print(f"  {c:3d}  {f}")

    if dry:
        print("\n[dry-run] 未写入任何文件")
        return 0

    if name2val:
        block = ["\n    <!-- ==================== Kotlin 文案（v1.18.0 抽离）=================== -->",
                 "    <!-- 仅含纯字面量；带 $ 插值或 \\n 转义的仍留在代码里，需人工改 getString(id, args)。 -->"]
        for name, value in name2val.items():
            block.append(f'    <string name="{name}">{escape_android_string(value)}</string>')
        assert existing.rstrip().endswith("</resources>")
        body = existing.rstrip()[: -len("</resources>")].rstrip("\n")
        io.open(STRINGS_XML, "w", encoding="utf-8", newline="\n").write(
            body + "\n" + "\n".join(block) + "\n\n</resources>\n"
        )
    print(f"\n已写入 {len(name2val)} 条到 strings.xml")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
