#!/usr/bin/env bash
# ============================================================================
# 屏幕翻译 App —— 本机构建环境（v1.20.0 建立）
#
# 为什么需要这个文件：这台机器原本**没有 JDK / Android SDK / Gradle**，
# 而工程以往只在别的设备上编过。所有构建路径都是绝对路径且写死在 D 盘，
# 所以统一收进这一个文件，避免每次敲一长串 export 时漏掉某一项
# —— 漏 JAVA_HOME 会让 AGP 报"找不到 Java"，漏 ANDROID_HOME 会让它
# 试图去 C 盘找 SDK 并重新下一份。
#
# 用法：
#   source scripts/env.sh          # 在当前 shell 里生效
#   bash   scripts/env.sh          # 只打印配置（用于检查）
#
# 配套：scripts/build.sh 直接调它。
# ============================================================================

# ---- 路径（本机固定）----
export ANDROID_BUILD_ROOT="/d/AndroidBuild"
export JAVA_HOME="$ANDROID_BUILD_ROOT/jdk-17.0.20.1+1"
export ANDROID_HOME="$ANDROID_BUILD_ROOT/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# ---- AGP / Kotlin 需要的 JDK 版本必须是 17 ----
# 高了会踩 jvmTarget 校验（Gradle 8.7 起对 JDK 21+ 的 Kotlin jvmTarget
# 有更严的兼容性检查），低了则读不了 class 文件 61.0。
"$JAVA_HOME/bin/java.exe" -version 2>&1 | head -1

# ---- 把 SDK 位置写进 local.properties ----
# 不写的话 AGP 会按默认顺序找（$ANDROID_HOME 也在其列），但显式落盘更稳：
# 某些 IDE / CI 场景下环境变量不继承，AGP 会报 "SDK location not found"。
PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${WRITE_LOCAL_PROPS:-}" ]; then
  # Windows 路径写法（反斜杠要转义，properties 文件里 \ 是转义符）
  SDK_WIN="$(cygpath -w "$ANDROID_HOME" 2>/dev/null || echo "$ANDROID_HOME")"
  printf 'sdk.dir=%s\n' "$(printf '%s' "$SDK_WIN" | sed 's/\\/\\\\/g')" > "$PROJ_DIR/local.properties"
  echo "已写入 local.properties: sdk.dir=$SDK_WIN"
fi
