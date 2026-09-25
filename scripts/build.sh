#!/usr/bin/env bash
# ============================================================================
# 屏幕翻译 App —— 一键构建脚本（v1.20.0 建立）
#
# 用法：
#   bash scripts/build.sh debug     # 出调试包（不需要签名）
#   bash scripts/build.sh release   # 出正式包（需要 release.keystore）
#   bash scripts/build.sh both      # 两个都出
#
# 产物位置：
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk
#
# 这个脚本存在的原因：本机没装 Gradle，靠 gradlew 走 wrapper（会自动下
# Gradle 8.7 到 ~/.gradle）。直接把 JAVA_HOME / ANDROID_HOME 传清楚，
# 并且**强制串行**（--no-parallel）—— 这台机器要同时供多个任务用，
# 并行构建会抢内存导致 Kotlin 编译器被 OOM Killer 干掉，报错还是
# 莫名其妙的 "Daemon disappeared"。
# ============================================================================
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ_DIR"

WHAT="${1:-debug}"
GRADLE_OPTS_BUILD="-Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.daemon=false"

run_task() {
  local task="$1"
  echo
  echo "============================================================"
  echo " 构建 $task"
  echo "============================================================"
  # 用 **shell 版** gradlew 而不是 gradlew.bat：
  # 工程路径里有空格（"...\WorkBuddy AI\..."），而 .bat 版在解析
  # %~dp0 时不会加引号，cmd 会把 "D:\WorkBuddy" 当成命令名，
  # 报「'D:\WorkBuddy' 不是内部或外部命令」。Git Bash 下走 shell 版没有这个问题。
  ./gradlew "$task" --no-daemon --no-parallel --stacktrace \
      -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m" 2>&1
  return $?
}

case "$WHAT" in
  debug)
    run_task assembleDebug
    ;;
  release)
    run_task assembleRelease
    ;;
  both)
    run_task assembleDebug || exit 1
    run_task assembleRelease
    ;;
  *)
    echo "用法: $0 {debug|release|both}"
    exit 2
    ;;
esac

rc=$?
echo
echo "============================================================"
echo " gradle 退出码: $rc"
echo "============================================================"
echo "APK 产物："
find app/build/outputs/apk -name "*.apk" -exec ls -lh {} \; 2>/dev/null || echo "  (无产物)"
exit $rc
