#!/usr/bin/env bash
# ============================================================================
# 启动崩溃诊断脚本 —— v1.20.0
#
# 用途：App 打开就闪退时，用它抓真实崩溃堆栈。
#
# 为什么需要"抓日志"而不是继续读代码：
# 启动即闪退（还没进界面就退出）的可能原因非常分散 —— 主题属性缺失、
# 类初始化异常、Application.onCreate 抛错、签名冲突、架构不匹配 …… 
# 这些在**源码层面**看都可能是对的（本工程的静态检查 3 套全绿、R8 也
# 正确保留了启动类），只有真实堆栈能指出是哪一行。
#
# 用法：
#   bash scripts/diag-crash.sh              # 装 debug 包并盯着日志
#   bash scripts/diag-crash.sh release      # 装 release 包
#
# 会做三件事：
#   1. 检查设备连接、架构、已装版本
#   2. 卸载旧包（避免签名冲突）→ 装指定包
#   3. 清日志 → 拉起 App → 抓 AndroidRuntime / FATAL 相关堆栈
# ============================================================================
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ_DIR"

WHAT="${1:-debug}"
case "$WHAT" in
    debug)   APK="app/build/outputs/apk/debug/app-debug.apk" ;;
    release) APK="app/build/outputs/apk/release/app-release.apk" ;;
    *) echo "用法: bash scripts/diag-crash.sh [debug|release]"; exit 2 ;;
esac
PKG="com.hunter.screentranslator"
ADB="$ANDROID_HOME/platform-tools/adb.exe"

if [ ! -f "$APK" ]; then
    echo "[X] 找不到 APK: $APK"
    echo "    先构建：bash scripts/build.sh $WHAT"
    exit 1
fi
echo "使用 APK: $APK  ($(du -h "$APK" | cut -f1))"
echo

# ---------------------------------------------------------------- 1. 设备
echo "==================== 1. 设备状态 ===================="
DEVICES="$("$ADB" devices | grep -v "List of devices" | grep -c "device$" || true)"
if [ "$DEVICES" -eq 0 ]; then
    echo "[X] 没有已连接的设备。"
    echo
    echo "请先："
    echo "  1) 手机用 USB 连上电脑"
    echo "  2) 打开「开发者选项 → USB 调试」"
    echo "  3) 手机上弹出「允许 USB 调试吗？」→ 点「允许」（建议勾选「一律允许」）"
    echo "  4) 重新运行本脚本"
    echo
    echo "  也可以无线调试：手机「开发者选项 → 无线调试」里配对后"
    echo "     adb pair <IP>:<端口>"
    echo "     adb connect <IP>:<端口>"
    exit 1
fi
echo "[OK] 已连接："
"$ADB" devices -l | grep "device " | sed 's/^/     /'
echo
echo "设备架构（必须是 arm64-v8a，本包只含 64 位 ARM）："
"$ADB" shell getprop ro.product.cpu.abilist 2>/dev/null | tr ',' '\n' | sed 's/^/     /'
echo
echo "系统版本："
"$ADB" shell getprop ro.build.version.release 2>/dev/null | sed 's/^/     Android /'
"$ADB" shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/     API /'
echo

# ---------------------------------------------------------------- 2. 安装
echo "==================== 2. 安装 ===================="
echo "先卸载旧版本（debug 与 release 签名不同，直接覆盖装会失败）："
"$ADB" uninstall "$PKG" 2>&1 | sed 's/^/     /'

echo "安装中……"
"$ADB" install -r -t "$APK" 2>&1 | sed 's/^/     /'
if ! "$ADB" shell pm list packages 2>/dev/null | grep -q "$PKG"; then
    echo "[X] 安装失败。常见原因："
    echo "    · 手机没有开启「USB 安装」/「允许安装未知来源」"
    echo "    · 手机上弹了确认框没点"
    echo "    · CPU 不是 arm64（本包只有 arm64-v8a）"
    exit 1
fi
echo "[OK] 安装成功"
echo

# ---------------------------------------------------------------- 3. 抓日志
echo "==================== 3. 启动并抓取崩溃堆栈 ===================="
"$ADB" logcat -c 2>/dev/null
echo "清空日志缓冲完成。正在拉起 App……"
"$ADB" shell am start -n "$PKG/.ui.MainActivity" 2>&1 | sed 's/^/     /'
echo
echo "等待 6 秒收集日志……"
sleep 6

echo
echo "---------- 崩溃堆栈（AndroidRuntime / FATAL）----------"
"$ADB" logcat -d 2>/dev/null | grep -A 40 -E "FATAL EXCEPTION|AndroidRuntime.*Process: $PKG" | head -60

echo
echo "---------- 与本应用相关的错误（E 级）----------"
"$ADB" logcat -d 2>/dev/null | grep -E "^E.*$PKG|^E/AndroidRuntime" | grep -v "^$" | head -30

echo
echo "---------- 应用进程是否还活着 ----------"
if "$ADB" shell pidof "$PKG" 2>/dev/null | grep -q "[0-9]"; then
    echo "[OK] 进程存活 —— App 没有闪退，可能只是安装/launch 问题"
else
    echo "[X] 进程已不在 —— 确认发生闪退，请看上面的堆栈"
fi

echo
echo "---------- 应用自己的崩溃记录（CrashLog 写的文件）----------"
echo "如果上面堆栈不全，可以把这段导出来："
echo "     \$ADB shell run-as $PKG ls files/crash/"
echo "     \$ADB shell run-as $PKG cat files/crash/<最新文件名>"
echo
echo "也可以直接用完整的 logcat："
echo "     \"$ADB\" logcat -d > crash-logcat.txt"
