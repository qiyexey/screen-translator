#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从本机 Gradle 缓存里的 AAR 抽出全部 attr / style 名，写到
scripts/material-res-names.txt，供 verify-strings.py 做"资源名是否存在"校验。

为什么要机器生成而不是手写白名单：
v1.20.0 先写了份手写白名单，立刻误报 14 条 —— 因为 material 有 100+ 个 attr、
700+ 个 style，靠人列必然漏。而漏报的代价是一次 3 分钟的构建失败 + 一轮排查，
误报则只是浪费时间核对。机器生成的清单一次性解决两头。

为什么要扫多个 AAR（重要）：
theme 里能用到的属性并不都由 material 自己"声明"。例如：
    <item name="toolbarStyle">...</item>       ← 声明在 appcompat 里
    <item name="titleTextAppearance">...</item> ← 声明在 appcompat 里
material 只是**引用**它们（`<item name="toolbarStyle">` 出现在 material 的
style 定义体中），并不 `<attr>` 声明。只扫 material 就会把这些名字判成
"不存在"，产生误报。所以 material + appcompat 必须一起扫。

为什么要扫 res/values*/ 下的所有 xml：
AAPT2 按"配置限定符"把资源拆成 values.xml / values-night.xml / values-v21.xml
等多份，只读 res/values/values.xml 会漏。

为什么要扫平台 SDK（重要）：
有些 attr 既不声明在 material、也不声明在 appcompat，而是 **Android 平台
标准主题属性** —— 比如 toolbarStyle、titleTextAppearance、colorPrimary 等。
库只是在自带的 style 里 `<item name="toolbarStyle">` 引用它们，真正的
`<attr>` 声明在平台的 framework-res 里。
AAPT2 之所以能解析，是因为 compileSdk 提供了它们。
所以必须把 $ANDROID_HOME/platforms/android-XX/data/res/values/attrs*.xml
也扫进来，否则这些最常见的属性会被误报成"不存在"。

用法：
    python scripts/gen-material-names.py

什么时候需要重跑：**升级 material / appcompat 依赖版本，或改动 compileSdk 之后**。
"""
import glob
import io
import os
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "scripts", "material-res-names.txt")

MODULES_ROOT = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# 要扫的依赖：group id + artifact 名（group id 是一整级目录，点号不拆）。
# 加新依赖时往这里补一行；凡是 theme 里可能引用其 attr 的库都要列上。
TARGETS = [
    ("com.google.android.material", "material"),
    ("androidx.appcompat", "appcompat"),
]


def android_sdk_dir():
    """依次尝试 local.properties / ANDROID_HOME / ANDROID_SDK_ROOT。"""
    lp = os.path.join(ROOT, "local.properties")
    if os.path.exists(lp):
        for line in io.open(lp, encoding="utf-8"):
            line = line.strip()
            if line.startswith("sdk.dir="):
                # .properties 里 Windows 路径写成 D:/x 或 D:\\x
                return line.split("=", 1)[1].replace("\\\\", "\\")
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v:
            return v
    return None


def find_aars(group_id, artifact):
    """缓存目录结构 <group>/<artifact>/<version>/<sha1>/<file>。
    注意 group 是**一整级目录**，点号不拆开：
        files-2.1/com.google.android.material/material/1.12.0/<sha1>/material-1.12.0.aar
    版本目录下还有一层 sha1 目录，层数不固定，所以用 os.walk 递归找。"""
    base = os.path.join(MODULES_ROOT, group_id, artifact)
    found = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for fn in filenames:
            if fn.endswith(".aar") and fn.startswith(artifact + "-"):
                found.append(os.path.join(dirpath, fn))
    return found


def scan_aar(path):
    """返回 (attrs, styles, n_value_files, version)

    v1.20.2 修正：**以 AAR 内的 R.txt 为准**，而不是 regex 扫 values*.xml。

    为什么必须改：AAR 里的 res/values/values.xml 是"部分展开"的成品文件，
    只包含**没有 format 的** styleable 局部 attr（material 1.12.0 里只有 103 个，
    如 boxBackgroundMode / endIconMode），而带 format 的 attr 声明
    —— boxBackgroundColor、boxStrokeColor、hintEnabled、boxCollapsedPaddingTop……
    —— 在 values.xml 里**根本不出现**。
    于是 regex 扫出来的清单是残缺的，会把大量真实存在的属性判成"不存在"。

    而 R.txt 是 AAR 的最终符号表（AAPT2 自己产出的），列全了
    int attr / int style / int color / int dimen …… 一个不漏，且有类型前缀可区分。
    所以这里改成读 R.txt，并用 `attr` / `style` 前缀分别归类。
    """
    z = zipfile.ZipFile(path)
    attrs, styles = set(), set()
    n_files = 0

    if "R.txt" in z.namelist():
        txt = z.read("R.txt").decode("utf-8", "replace")
        for line in txt.splitlines():
            # 形如:  int attr boxBackgroundColor 0x7f0400a1
            #       int style Widget_Material3_Toolbar 0x7f1302c3
            m = re.match(r'\s*int\s+(attr|style)\s+(\S+)\s', line)
            if not m:
                continue
            kind, name = m.group(1), m.group(2)
            # R.txt 里名字里的点被换成下划线，还原真实资源名
            if kind == "attr":
                attrs.add(name)
            else:
                styles.add(name.replace("_", "."))
        n_files = 1

    if not attrs and not styles:
        # 兜底：R.txt 缺失时才回到 regex 扫 values*.xml（不完整，仅应急）
        for name in z.namelist():
            if not name.startswith("res/") or not name.endswith(".xml"):
                continue
            parts = name.split("/")
            if len(parts) != 3 or not parts[1].startswith("values"):
                continue
            n_files += 1
            try:
                xml = z.read(name).decode("utf-8")
            except UnicodeDecodeError:
                continue
            attrs.update(re.findall(r'<attr name="([^"]+)"', xml))
            styles.update(re.findall(r'<style name="([^"]+)"', xml))

    m = re.search(r"-(\d[^-]*)\.aar$", os.path.basename(path))
    return attrs, styles, n_files, (m.group(1) if m else "?")


all_attrs, all_styles = set(), set()
provenance = []  # (label, version, n_attrs, n_styles, n_files)

for group_id, artifact in TARGETS:
    aars = find_aars(group_id, artifact)
    if not aars:
        print(f"[WARN] 找不到 {group_id}:{artifact} 的 AAR，跳过。")
        print("       先跑一次构建把依赖下下来：bash scripts/build.sh debug")
        continue
    # release 变体带 -release 后缀，取版本号最高的
    aars.sort()
    aar = aars[-1]
    a, s, nf, ver = scan_aar(aar)
    all_attrs |= a
    all_styles |= s
    provenance.append((artifact, ver, len(a), len(s), nf))
    print(f"{artifact}: v{ver} — attr {len(a)} / style {len(s)}（扫了 {nf} 个 values 文件）")

if not all_attrs and not all_styles:
    print("一个 AAR 都没扫到，无法生成白名单。")
    sys.exit(1)

# ---- 平台 SDK：framework 的 attr 声明 ----
sdk = android_sdk_dir()
if sdk:
    platforms = sorted(glob.glob(os.path.join(sdk, "platforms", "android-*")))
    if platforms:
        # 取编号最大的平台
        plat = max(platforms, key=lambda p: int(re.search(r"android-(\d+)", p).group(1) \
                                                if re.search(r"android-(\d+)", p) else 0))
        attrs_dir = os.path.join(plat, "data", "res", "values")
        platform_attrs = set()
        n_files = 0
        if os.path.isdir(attrs_dir):
            for fn in os.listdir(attrs_dir):
                if not fn.endswith(".xml"):
                    continue
                n_files += 1
                try:
                    xml = io.open(os.path.join(attrs_dir, fn), encoding="utf-8").read()
                except (UnicodeDecodeError, OSError):
                    continue
                platform_attrs.update(re.findall(r'<attr name="([^"]+)"', xml))
        if platform_attrs:
            all_attrs |= platform_attrs
            provenance.append((os.path.basename(plat) + ":framework",
                               "-", len(platform_attrs), 0, n_files))
            print(f"平台 {os.path.basename(plat)}: framework attr {len(platform_attrs)} 个"
                  f"（扫了 {n_files} 个文件）")
        else:
            print(f"[WARN] 平台 attrs 目录为空或不存在: {attrs_dir}")
    else:
        print(f"[WARN] 在 {sdk}/platforms 下没找到 android-* 平台。")
else:
    print("[WARN] 定位不到 Android SDK，跳过平台 attr。")
    print("       （可设 ANDROID_HOME，或确保 local.properties 里有 sdk.dir=）")

attrs = sorted(all_attrs)
styles = sorted(all_styles)

with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("# 由 scripts/gen-material-names.py 生成 —— 不要手改\n")
    for artifact, ver, na, ns, nf in provenance:
        f.write(f"# 来源: {artifact}:{ver}（attr {na} / style {ns}，{nf} 个 values 文件）\n")
    f.write(f"# 合计: attr {len(attrs)} 个 / style {len(styles)} 个\n")
    f.write("# 格式: A <attr名>  /  S <style名>\n")
    for a in attrs:
        f.write("A " + a + "\n")
    for s in styles:
        f.write("S " + s + "\n")

print(f"\n已写入 {os.path.relpath(OUT, ROOT)}：attr {len(attrs)}、style {len(styles)}")
print("升级 material / appcompat 依赖后记得重跑本脚本。")
