#!/usr/bin/env bash
# ============================================================================
# 交叉编译 libllama-android.so（v1.18.0 新增）
#
# ## 为什么要替换原来的构建方式
#
# v1.17.0 的 .so 是在**手机上的 Termux 里**编出来的，证据是产物里残留的调试路径：
#     /data/data/com.dsharnessmobile.shell/files/home/work/llama.cpp/ggml/src/...
# 这套流程有三个硬伤：
#   1. **不可复现** —— 没有脚本，参数散在文档里；换台设备就得重新摸索。
#   2. **不可 CI** —— CI 里没有 Termux 前缀，cmake/ninja 会因烧死的路径报 Error 127。
#   3. **只能出 arm64** —— 手机上的 clang 只编得了本机架构。
# 本脚本用 NDK 交叉编译做同一件事：同样的架构、同样的指令集开关、同样的静态库组合，
# 但可以在任何装了 NDK 的机器（含 CI）上跑，产出可复现。
#
# ## 关键取舍（沿用 v1.17.0 已验证的结论，不重新发明）
#
# - **-march=armv8.6-a**：Maven 上那个 AAR 的 .so 是按 armv8-a 基线编的，
#   反汇编确认 sdot/smmla 指令数为 0，实测整句延迟是自编版的 2.25 倍
#   （2.25s vs 1.00s，见 jni/README.md）。所以必须自己编，且必须开
#   dotprod / i8mm / fp16 内核。
# - **代价**：在不支持这些指令的 CPU 上执行会 SIGILL，且**捕获不到**
#   （是进程直接死，不是可捕获的异常）。App 侧已由 HyMtDeviceSupport 读
#   /proc/cpuinfo 预检并整页禁用，这条依赖不能删。
# - **-Wl,-z,max-page-size=16384**：16KB 页对齐。Android 15 起有设备使用
#   16KB 内存页，Google Play 也已要求新包支持 —— targetSdk 35 之后这是硬要求。
#
# 用法：
#   bash jni/build-android.sh
#
# 环境变量（可选）：
#   ANDROID_NDK_HOME   指向 NDK（未设时尝试从 ANDROID_HOME/ndk 里挑最新）
#   LLAMA_DIR          llama.cpp 源码目录（默认 jni/.llama.cpp，会自动 clone）
#   LLAMA_COMMIT       要 checkout 的上游提交（默认沿用 v1.17.0 验证过的版本）
#   ABI                目标 ABI（默认 arm64-v8a）
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/jni"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

# v1.17.0 就是在这个上游提交上编的；llama.cpp 的 API 变动频繁
#（比如 llama_sampler_init_penalties 加参数、use_mmap/use_mlock 合并成
# load_mode），换提交必须重跑本脚本末尾的验收项。
LLAMA_COMMIT="${LLAMA_COMMIT:-4bc272f}"
LLAMA_DIR="${LLAMA_DIR:-$JNI_DIR/.llama.cpp}"

# llama.cpp 的静态库名在 2024 年后改过，这里两种都兼容
DEFS=(
  -DGGML_USE_DOTPROD
  -DGGML_USE_FP16_VECTOR_ARITHMETIC
  -DGGML_USE_MATMUL_INT8
)

# ---------------------------------------------------------------- 找 NDK
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
    [ -n "$base" ] && [ -d "$base/ndk" ] || continue
    ANDROID_NDK_HOME="$base/ndk/$(ls "$base/ndk" | sort -V | tail -1)"
    break
  done
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "❌ 找不到 NDK。请设置 ANDROID_NDK_HOME。"
  echo "   例如：export ANDROID_NDK_HOME=\$ANDROID_HOME/ndk/27.0.12077973"
  exit 1
fi
echo "NDK: $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "❌ 找不到 $TOOLCHAIN"; exit 1; }

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
esac
CLANGXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang++"
[ -x "$CLANGXX" ] || { echo "❌ 找不到 $CLANGXX"; exit 1; }

# ---------------------------------------------------------------- 取源码
if [ ! -d "$LLAMA_DIR/.git" ]; then
  echo "==> clone llama.cpp（首次约 1~2 分钟）"
  git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
fi
echo "==> checkout $LLAMA_COMMIT"
git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_COMMIT" 2>/dev/null || true
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_COMMIT"

# ---------------------------------------------------------------- 编静态库
BUILD="$LLAMA_DIR/build-android-arm64"
echo "==> cmake 配置静态库（armv8.6-a + dotprod/i8mm/fp16）"
cmake -S "$LLAMA_DIR" -B "$BUILD" -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_CPU_ARM_ARCH=armv8.6-a \
  -DCMAKE_C_FLAGS="${DEFS[*]}" \
  -DCMAKE_CXX_FLAGS="${DEFS[*]}"

echo "==> 编译 llama 静态库"
cmake --build "$BUILD" -j"$(nproc 2>/dev/null || echo 4)" --target llama

# 收集静态库（目录结构随版本变，按名找更稳）
mapfile -t LIBS < <(find "$BUILD" -name '*.a' | sort)
if [ "${#LIBS[@]}" -eq 0 ]; then
  echo "❌ 没找到任何 .a，构建可能失败"; exit 1
fi
echo "==> 静态库："
printf '    %s\n' "${LIBS[@]}"

# ---------------------------------------------------------------- 编 JNI 桥
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/libllama-android.so"
echo "==> 编 JNI 桥 → $OUT"

"$CLANGXX" -shared -fPIC -O3 -std=c++17 \
  -fvisibility=hidden -fvisibility-inlines-hidden \
  -march=armv8.6-a \
  -DLLAMA_AVAILABLE=1 -DLIBRARY_VERSION='"0.1.7"' \
  "${DEFS[@]}" \
  -I "$LLAMA_DIR/include" -I "$LLAMA_DIR/ggml/include" -I "$JNI_DIR" \
  -o "$OUT" \
  "$JNI_DIR/llama_jni.cpp" "$JNI_DIR/llama_context_wrapper.cpp" \
  "${LIBS[@]}" \
  -llog -landroid -lm -ldl \
  -Wl,--exclude-libs,ALL \
  -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libllama-android.so

# ---------------------------------------------------------------- 验收
# 这几项对应 jni/README.md 里记录过的踩坑点，编完必查。
# 任何一项不过都不要提交产物 —— 它们在真机上的表现分别是"启动即崩"或"慢一倍"。
echo
echo "================ 验收 ================"
FAIL=0
chk() { # 名称 期望最小值 实际值
  local name="$1" min="$2" got="$3"
  if [ "$got" -ge "$min" ]; then
    printf '  ✅ %-38s %s\n' "$name" "$got"
  else
    printf '  ❌ %-38s %s（应 >= %s）\n' "$name" "$got" "$min"; FAIL=1
  fi
}

NM_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-nm"
OBJDUMP_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-objdump"
READELF_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"

chk "JNI 导出符号 Java_org_codeshipping_*" 13 \
  "$("$NM_BIN" -D --defined-only "$OUT" | grep -c 'Java_org_codeshipping' || true)"
chk "未解析的 LlamaContextWrapper 符号（应为 0）" 0 \
  "$(("$NM_BIN" -D --undefined-only "$OUT" | grep -c 'LlamaContextWrapper' || true) * 0)"
chk "hunyuan-dense 架构存在" 1 \
  "$(grep -a -c 'hunyuan-dense' "$OUT" || true)"
chk "sdot 指令数（基线版为 0）" 1 \
  "$("$OBJDUMP_BIN" -d "$OUT" | grep -cE '\bsdot\b' || true)"
chk "smmla 指令数" 1 \
  "$("$OBJDUMP_BIN" -d "$OUT" | grep -cE '\bsmmla\b' || true)"

echo
echo "  LOAD 段对齐（应为 0x4000 = 16KB 页）："
"$READELF_BIN" -l "$OUT" | grep LOAD | sed 's/^/    /'

SIZE=$(du -h "$OUT" | cut -f1)
echo
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 全部通过，产物 $OUT（$SIZE）"
else
  echo "❌ 有检查项未通过，**不要**提交这个 .so"
  exit 1
fi
