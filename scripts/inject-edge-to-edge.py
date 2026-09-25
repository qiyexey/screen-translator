#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一次性脚本（v1.18.0）：给所有 Activity 的 setContentView 之后注入
`EdgeToEdge.install(this)`，并补上对应 import。

为什么用脚本而不是手工改 17 个文件：注入点唯一且明确（setContentView 那一行），
脚本化能保证 17 处完全一致，也便于复核（脚本会打印每一处改动）。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
UI_DIR = os.path.join(ROOT, "app", "src", "main", "java", "com", "hunter", "screentranslator", "ui")

IMPORT_LINE = "import com.hunter.screentranslator.util.EdgeToEdge"
CALL_LINE = "EdgeToEdge.install(this)"

# 两类 Activity 跳过：
# - BaseActivity.kt 是抽象基类，自己不调 setContentView；
# - ProcessTextActivity.kt 是透明跳板（抓完文字立刻 finish），没有内容视图要避让系统栏。
SKIP = {"ProcessTextActivity.kt", "BaseActivity.kt"}


def insert_import(text: str) -> tuple[str, bool]:
    if IMPORT_LINE in text:
        return text, False
    lines = text.split("\n")
    idxs = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not idxs:
        raise RuntimeError("找不到 import 块")
    pos = None
    for i in idxs:
        if lines[i] > IMPORT_LINE:
            pos = i
            break
    if pos is None:
        pos = idxs[-1] + 1
    lines.insert(pos, IMPORT_LINE)
    return "\n".join(lines), True


def insert_call(text: str) -> tuple[str, bool]:
    if CALL_LINE in text:
        return text, False
    lines = text.split("\n")
    hits = [i for i, l in enumerate(lines) if "setContentView(" in l and not l.strip().startswith("//")]
    if len(hits) != 1:
        raise RuntimeError(f"setContentView 命中 {len(hits)} 处，无法安全注入")
    i = hits[0]
    indent = re.match(r"\s*", lines[i]).group(0)
    lines.insert(i + 1, indent + CALL_LINE)
    return "\n".join(lines), True


def main() -> int:
    changed = 0
    for name in sorted(os.listdir(UI_DIR)):
        if not name.endswith("Activity.kt") or name in SKIP:
            continue
        path = os.path.join(UI_DIR, name)
        src = io.open(path, encoding="utf-8").read()
        out, a = insert_import(src)
        out, b = insert_call(out)
        if a or b:
            io.open(path, "w", encoding="utf-8", newline="\n").write(out)
            changed += 1
            print(f"[OK] {name}: import={'+' if a else '='} call={'+' if b else '='}")
        else:
            print(f"[--] {name}: 已是目标状态")
    print(f"\n共修改 {changed} 个文件")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
