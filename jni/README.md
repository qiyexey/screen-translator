# jni — 自编的 native 运行时

这里只放 JNI 桥的源码。`libllama-android.so` 的产物在
`app/src/main/jniLibs/arm64-v8a/`（**产物入库、源码也入库**：设备内重编一次要
十几分钟，且需要先备好 llama.cpp 源码树）。

## 为什么自己编，而不是直接用 `org.codeshipping:llama-kotlin-android`

那个 AAR 里带的 `libllama-android.so` 是按 **armv8-a 基线**编译的 ——
反汇编确认 `sdot` / `smmla` / `fmlal` 指令数**全为 0**。
实测整句延迟因此是自编版本（armv8.6-a，901 个 sdot + 268 个 smmla）的
**2.25 倍**（2.25s vs 1.00s，12 句真实屏幕文本，见 `FIXES-1.17.0.md` §2.2）。

Kotlin API 层仍然用它的（`app/libs/llama-kotlin-android-0.1.7-classes.jar`，
MIT），因为本 .so 导出的是同一组 JNI 符号（`Java_org_codeshipping_llamakotlin_LlamaNative_native*`），
两边一一对应。

## 相对上游源码改了什么（共 3 处，都是为了适配新版 llama.cpp）

1. `llama_context_wrapper.cpp`：`llama_sampler_init_penalties` 新版多了
   第一个参数 `n_vocab` → 补 `llama_vocab_n_tokens(llama_model_get_vocab(model_))`。
2. `llama_context_wrapper.cpp`：`llama_model_params` 的 `use_mmap` / `use_mlock`
   两个布尔在新版被合并成 `enum llama_load_mode load_mode` → 按位组合映射到
   `LLAMA_LOAD_MODE_MMAP` / `_MLOCK` / `_MMAP_MLOCK` / `_NONE`。
3. 编译期必须定义 `LLAMA_AVAILABLE=1`（上游用 CMake 传，直接 clang 编时要自己带，
   否则整份 wrapper 实现被 `#if LLAMA_AVAILABLE` 编成空，链接出一个只有 90KB、
   缺实现的 .so）。

## 复现步骤（设备内，Termux 环境）

```bash
B=$PREFIX                      # /data/data/<pkg>/files/usr
export PATH="$B/bin:$PATH" LD_LIBRARY_PATH="$B/lib" TMPDIR=$HOME/tmp

# 1) llama.cpp 静态库（armv8.6-a，开 dotprod/i8mm/fp16 内核）
cd ~/work/llama.cpp            # 上游 4bc272f
DEFS="-DGGML_USE_DOTPROD -DGGML_USE_FP16_VECTOR_ARITHMETIC -DGGML_USE_MATMUL_INT8"
cmake -B build-android -G "Unix Makefiles" -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a \
  -DCMAKE_C_FLAGS="$DEFS" -DCMAKE_CXX_FLAGS="$DEFS"
cmake --build build-android -j4 --target llama

# 2) JNI 桥 → 单个 .so
JC=$PWD/build-android
clang++ -shared -fPIC -O3 -std=c++17 -fvisibility=hidden -fvisibility-inlines-hidden \
  -march=armv8.6-a -DLLAMA_AVAILABLE=1 -DLIBRARY_VERSION='"0.1.7"' $DEFS \
  -I include -I ggml/include -I <repo>/jni \
  -o libllama-android.so <repo>/jni/llama_jni.cpp <repo>/jni/llama_context_wrapper.cpp \
  $JC/src/libllama.a $JC/ggml/src/libggml.a $JC/ggml/src/libggml-base.a $JC/ggml/src/libggml-cpu.a \
  -llog -landroid -lm -ldl -Wl,--exclude-libs,ALL \
  -Wl,-z,max-page-size=16384 -Wl,-soname,libllama-android.so
```

> Termux 的 `cmake`/`ninja`/`make` 里烧死了 `/data/data/com.termux/...` 前缀，
> 在包名不同的环境里 spawn 一律 Error 127：cmake 要用 `-DCMAKE_MAKE_PROGRAM=<包装脚本>`，
> 包装脚本里 `exec make SHELL=$PREFIX/bin/sh "$@"`。

## 验收（编完必查）

```bash
nm -D --defined-only libllama-android.so | grep -c Java_org_codeshipping   # 13
nm -D --undefined-only libllama-android.so | grep -c LlamaContextWrapper   # 0
grep -a -c hunyuan-dense libllama-android.so                              # 1
objdump -d libllama-android.so | grep -cE '\bsdot\b'                      # >0（基线版是 0）
readelf -l libllama-android.so | grep LOAD                                # 对齐 0x4000
```

## 兼容性代价（必须知道）

`-march=armv8.6-a` 意味着**在不支持 dotprod/i8mm/fp16 的 CPU 上执行会 SIGILL**，
且**捕获不到**（不是异常，是进程直接死）。所以 App 里加了
`HyMtDeviceSupport`：加载前读 `/proc/cpuinfo` 预检，不支持就**整页禁用**并说明原因。

## 独立验收程序（不依赖装机）

`harness.cpp` 用**与 App 内完全相同的 wrapper 源码 + 同一批静态库**直接跑真实模型，
用来在没有设备控制授权（装不了 APK）时也能验收 native 路径：

```bash
JC=~/work/llama.cpp/build-android
clang++ -O3 -std=c++17 -march=armv8.6-a \
  -DLLAMA_AVAILABLE=1 -DLIBRARY_VERSION='"0.1.7"' \
  -DGGML_USE_DOTPROD -DGGML_USE_FP16_VECTOR_ARITHMETIC -DGGML_USE_MATMUL_INT8 \
  -I . -I ~/work/llama.cpp/include -I ~/work/llama.cpp/ggml/include \
  -o harness harness.cpp llama_context_wrapper.cpp \
  $JC/src/libllama.a $JC/ggml/src/libggml.a $JC/ggml/src/libggml-base.a $JC/ggml/src/libggml-cpu.a \
  -llog -lm -ldl -Wl,--exclude-libs,ALL
./harness ~/models/Hy-MT2-1.8B-Q4_K_M.gguf
```

它检查四件事：模型能否加载、`<｜hy_User｜>`/`<｜hy_Assistant｜>` 是否为单个 token、
BOS 是否重复、以及 13 句真实屏幕文本的译法与耗时。

> 注意：**不要**加 `-landroid`。在 Termux 里它会把 `/system/lib64/libunwindstack.so`
> 拉进依赖而启动失败（App 内有自己的 linker namespace，不受影响）。
