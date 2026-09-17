# v1.17.0 — 本地大模型（腾讯 Hy-MT2-1.8B，端侧离线翻译）

这一版新增第 12 个翻译引擎：**腾讯混元 Hy-MT2-1.8B**，**完全在本机 CPU 上推理**，
无密钥、无额度、断网可用，译文不出设备。

## 1. 选型：为什么是 Hy-MT2-1.8B

| 候选 | 结论 |
|---|---|
| **Hy-MT2-1.8B** | ✅ 选它。Apache-2.0，33 语种，官方提供 GGUF（Q4_K_M/Q6_K/Q8_0 现成），1.13GB 能塞进手机 |
| Hy-MT2-7B / 30B-A3B | ❌ 手机上跑不动（7B Q4 约 4.5GB，30B 更不必说） |
| Hy-MT2-1.8B-1.25bit（461MB） | ❌ **暂时不可用**，见下 |

### 1.25bit 那档为什么不能用（重要）

官方宣传"1.8B 只需 440MB 存储"，靠的是 AngelSlim 的 **STQ1_0** 三元量化内核。
这个内核在 llama.cpp 的 [PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)，
**至今仍是 open 未合并**（2026-09-17 查 GitHub API：`merged=false`）。
本工程用的是主line llama.cpp（commit `4bc272f`），反汇编确认其二进制里
**没有 STQ1_0 内核**（`STQ1_0` 字符串数 0）。

所以设置页里**故意不列 1.25bit 档**：列出来只会让用户白下 461MB，
然后看到"模型加载失败"。等上游合并后再加，届时同尺寸下推理还快约 1.5×。

## 2. 实测数据（PLJ110，8 核，最高 4.21GHz，Android 16）

模型：`Hy-MT2-1.8B-Q4_K_M.gguf`，1,133,080,448 字节，sha256 校验通过。

### 2.1 吞吐（llama-bench，`-ngl 0`，冷却后）

| 编译目标 | 线程 | pp64 (tok/s) | tg32 (tok/s) |
|---|---|---|---|
| `-mcpu=native+dotprod+i8mm+sve+sme` | 6 | 49.27 ± 1.41 | **14.35 ± 0.25** |
| 同上 | 4 | 31.48 ± 8.13 | 11.51 ± 7.19 |
| 同上 | 8 | 44.56 ± 3.24 | 8.28 ± 1.93 |
| `-march=armv8-a`（= 第三方 AAR 预编译库的特征） | 6 | 19.60 ± 0.13 | 10.43 ± 0.93 |

> 线程数不是越多越好：**8 线程反而比 6 线程慢 40%**（小核拖累 + 调度开销）。
> 所以 App 里默认取"核数 - 2，夹在 2~6"。

### 2.2 整句延迟（llama-server，12 句真实屏幕文本，译成中文）

| 运行时 | 平均整句 | 预填充 | 生成 | 生成速度 |
|---|---|---|---|---|
| 基线内核（armv8-a，≈ 第三方 AAR 的 .so） | **2.25 s** | 1607 ms | 633 ms | 11.19 tok/s |
| 本工程自编（armv8.6-a + dotprod + i8mm + fp16） | **1.00 s** | 536 ms | 450 ms | 15.92 tok/s |

**差 2.25 倍**，而且瓶颈在**预填充**（短句也要 1.6s → 0.54s）。
这就是本工程**不用 AAR 里那个预编译 .so、改为自己编**的原因 ——
反汇编发现它里面 `sdot`/`smmla`/`fmlal` 指令数**全为 0**，是按 armv8-a 基线编的。

内存：模型常驻 **RSS 1.29 GB**（含 KV cache，上下文 2048）。

### 2.3 翻译质量

12 句全部正确，包含英→中、日→中、韩→中，且**没有多余解释、
没有"译文："前缀、没有原文对照**（符合官方提示词的要求）。示例：

| 原文 | 译文 |
|---|---|
| `Battery low. Please connect the charger.` | 电池电量低。请连接充电器。 |
| `昨日の会議は中止になりました。` | 昨天的会议被取消了。 |
| `설정에서 알림을 끌 수 있습니다.` | 在设置中可以关闭通知。 |
| `本商品は返品できません。` | 此商品不可退货。 |
| `Loading... 45%` | 加载中... 45% |

## 3. 实现要点

### 3.1 提示词必须用官方格式（与通用 LLM 习惯不同）

```
将以下文本翻译为 {目标语言全称}，注意只需要输出翻译后的结果，不要额外解释：

{原文}
```

两个坑：
1. **目标语言要用全称**（"中文"/"英语"/"日语"），传 `zh`/`en` 会明显掉质量。
   App 的 `LANG_DISPLAY` 是界面用的（"English"/"日本語"），所以本地引擎另建了
   `HYMT_TARGET_NAMES` 映射，没有复用。
2. **要套对话模板**，但这里有个坑：llama.cpp **核心库**的
   `llama_chat_apply_template` 只有**老式启发式解析器**（只认识 chatml / llama2 /
   gemma 等固定几种），Hy-MT2 的 jinja 模板不在其中 → 走 fallback 分支，
   **把 `<｜hy_User｜>` 放到了正文之后**。实测字节：

   ```
   官方格式（llama-server 的 jinja 路径）: BOS + <｜hy_User｜> + 正文 + <｜hy_Assistant｜>
   wrapper 走核心库 API 的实际产出        : BOS + 正文 + <｜hy_User｜>
   ```

   jinja 引擎只在 `common/` 层（llama-cli / llama-server 用的那层），核心库没有。
   所以本工程**手工拼官方格式**，并在加载后校验模型模板里确实含这两个标记，
   不含就退回 `applyChatTemplate`（换模型/导入别的 gguf 时不硬闯）。

   证据（同一模型、同一采样参数、12 句对比）：13 句里 10 句输出完全相同，
   3 句是同义改写，而**手工格式那 3 句每次都跟 llama-server（jinja 参考实现）一致**：
   `Hello, how are you?` → 官方格式 `嗨，你好吗？`（老解析器是 `你好，你怎么样？`）、
   `Press and hold…` → `长按以录制语音消息`、`本商品は返品できません。` → `此商品不可退货。`

   分词侧验证：手工格式下 `<｜hy_User｜>` → 单个 token id **120006**、
   `<｜hy_Assistant｜>` → **120007**；`generate()` 内部是 `parse_special=true`，
   所以特殊 token 会被正确识别为独立 token 而不是被拆成普通文本。
   另外确认 `add_special=true` 不会重复加 BOS（文本已以 BOS 开头时只出现 1 次）。

采样参数用官方给的 1.8B 推荐值：`temperature 0.7 / top_p 0.6 / top_k 20 / repeat_penalty 1.05`。

### 3.2 模型不进 APK，首次使用时下载或导入

三个量化档可选（Q4_K_M 1.13GB / Q6_K 1.47GB / Q8_0 1.91GB），
下载到 app 私有目录 `files/models/`（sdcard 是 noexec）。

- **下载源**：默认 ModelScope（本机实测 **4.0 MB/s**，1.13GB 约 5 分钟）；
  备选 HuggingFace（本机实测 **0.34 MB/s**，约 55 分钟）。同一份文件，速度差 12 倍。
- **断点续传**：`.part` 文件 + HTTP Range。1.13GB 在手机网络上是"大概率会中断"的量级。
- **完整性**：先校验字节数，再校验 sha256（官方 LFS 记录值，逐字节）。
- **JSON 转义自己写**：那层 wrapper 是**手写 JSON 解析器**，只认识
  `\"` / `\n` / `\t` / `\\` 四种转义，其它按"去掉反斜杠的字面量"处理。
  而 Android 的 `JSONObject.quote` 会对 0x7F~0x9F、U+2028/2029 等输出 `\uXXXX`，
  被还原成字面量 `uXXXX` —— 屏幕文本里的"…"很容易踩到。所以自己转义，只产出它认识的那几种。
- **导入自备模型**：SAF 选 gguf，**不校验 sha256**（这正是导入存在的意义 ——
  用户可能有自己量化/微调的模型），只提示大小与官方不一致。
- **下载挂在 App 级作用域**，不是 Activity 的 lifecycleScope：
  1.13GB 要下 5 分钟，用户退出设置页不该让下载归零。

### 3.3 内存：加载慢一点没关系，被杀进程不行

模型常驻 1.29GB，而这个进程里还跑着**读屏服务、悬浮窗、实时翻译**。
两条自动卸载路径：

- `App.onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` → 主动卸掉模型
- 闲置 5 分钟（最后一次使用后）→ 自动卸载

另外设置页提供「预加载 / 卸载」两个按钮：把几秒的加载耗时挪到用户主动点击时。

### 3.4 取消必须传进 native，否则会堵死整个引擎

本 App 的读屏/实时链路在"屏幕又变了"时会 `cancel()` 上一个 job
（`ScreenReaderService.fullscreenJob?.cancel()`、`LiveTranslateService.loopJob?.cancel()`）。
但 native 解码循环不知道 Kotlin 协程被取消了，会**继续算到 maxTokens 才停** ——
按 16 tok/s、上限 1024 token 算就是一分多钟，而这期间推理 mutex 一直被占，
后面所有翻译请求全排在后面。用户看到的现象是"本地引擎卡死"。

改法：在 `generate` 里挂 `coroutineContext[Job].invokeOnCompletion`，
一旦因取消而结束就调 `model.cancelGeneration()` 让 native 立刻收尾。

**装机后实测到的第一个 bug 就在这条线上**（用户反馈截图）：
译文出来之前有时会闪一句 `翻译失败：StandaloneCoroutine was cancelled`。
根因不是 native，而是**我这里的 `runCatching` 把 `CancellationException` 吞掉并
转成了 `Result.failure`** —— 于是"上一次翻译被取消"被当成"翻译失败"显示出来。
云端引擎看不到这个现象，是因为它们的阻塞式 OkHttp 调用在 `runCatching` **之外**
才撞上取消检查。

改法：`generate` / `preload` / `importFrom` 一律 **原样抛出 CancellationException**，
让取消走结构化并发的正常路径（调用方的协程直接结束，由新的一次翻译接管界面）。
`importFrom` 那条尤其要修：用户退出设置页会取消协程，若当成失败，轻则误导，
重则此时 Activity 已销毁、弹窗抛 `BadTokenException` 崩掉。

取消要真的打断 native，还得靠"哨兵"子协程：`launch(start = UNDISPATCHED) {
awaitCancellation() } finally { if (调用方已取消) cancelGeneration() }` ——
因为协程取消**打断不了**正在执行的 native 解码循环，`invokeOnCompletion` 那种
写法要等协程体结束才触发，等于没有用。哨兵只判"调用方是否真的被取消"，
正常跑完不会误设取消标志（native 每次 generate 开头也会重置该标志）。

同理，**模型加载**（1.13GB，1~2 秒）用 `withContext(NonCancellable)` 包住：
调用方（Activity/Service）在加载途中被销毁时，不能让 native 侧留下半初始化的
context 而 mutex 已经释放 —— 保证"要么完整加载，要么完整不加载"。

### 3.5 图片翻译会自动降级，不需要改那些调用点

Hy-MT2 是**纯文本**模型（`visionCapable = false`）。工程里原本就有按
`visionCapable` 分支的逻辑：

- `LiveTranslateService`：不读图，改走「本机 OCR 认字 → 文本翻译」
- `ImageTranslateActivity`：同样走 OCR 链路

所以实时屏幕翻译、拍照翻译都能用本地引擎，**只有"把整张图交给模型"那条路用不了**。

### 3.6 兼容性处理

| 问题 | 处理 |
|---|---|
| 第三方 AAR 是 Kotlin 2.0.21 编译的，工程是 1.9.24 | Kotlin 插件升到 2.0.21（AGP 8.5.2 + Gradle 8.7 支持） |
| AAR 经 `androidx.core:core-ktx:1.17.0` 要求 compileSdk 36 + AGP 8.9.1+ | **改为 vendored classes.jar**（`app/libs/`），传递依赖消失，不需要强行降版 |
| **自编 .so 用 armv8.6-a，老 CPU 上会 SIGILL（崩进程，捕获不到）** | `HyMtDeviceSupport` 读 `/proc/cpuinfo` 预检 `asimddp`/`i8mm`/`asimdhp`，不支持则整页禁用并说明原因 |
| ABI：.so 只有 arm64-v8a | 同上守卫的一部分：非 arm64 设备直接禁用该引擎 |

### 3.7 自编 native 运行时的必要性

`app/src/main/jniLibs/arm64-v8a/libllama-android.so` 由本工程自己编译：

- llama.cpp 上游 `4bc272f`（支持 `hunyuan-dense` 架构）
- JNI 桥用 llama-kotlin-android 的 `llama_jni.cpp` + `llama_context_wrapper.cpp`
  （MIT，源码已入库 `jni/`）
- 编译目标 `-march=armv8.6-a` + `-DGGML_USE_DOTPROD/-DGGML_USE_FP16_VECTOR_ARITHMETIC/-DGGML_USE_MATMUL_INT8`
- 复现步骤见 `jni/README.md`

相对上游 JNI 源码改了 **3 处**（都是新版 llama.cpp 的 API 漂移）：

| # | 改动 | 不改的后果 |
|---|---|---|
| 1 | 定义 `LLAMA_AVAILABLE=1` | 整份 wrapper 实现被 `#if` 编成空，链接出 90KB 的 .so，运行时找不到实现 |
| 2 | `llama_sampler_init_penalties` 补第一个参数 `n_vocab` | 编译不过 |
| 3 | `use_mmap` / `use_mlock` 两个布尔 → `enum llama_load_mode` | 编译不过（新版把两个字段合并了） |

验收产物（编完必查，命令见 `jni/README.md`）：

| 指标 | AAR 预编译版 | 本工程自编版 |
|---|---|---|
| `sdot` 指令数 | 0 | **901** |
| `smmla` 指令数 | 0 | **268** |
| `hunyuan-dense` 支持 | 有 | 有 |
| JNI 导出符号 | 13 | 13 |
| 未定义的 wrapper 符号 | 0 | 0 |
| LOAD 段对齐 | 0x1000 | **0x4000（16KB 页友好）** |

## 4. 独立验收（不依赖装机）

由于本机没有设备控制授权（无法自行安装 APK），另外做了一层**不依赖装机**的验收：
用**与 APK 内完全相同的 wrapper 源码 + 同一批静态库**编了一个 native 验收程序
（`jni/` 里的源码，链接同一份 `libllama.a`），直接跑真实模型：

| 验收项 | 结果 |
|---|---|
| 模型加载（`hunyuan-dense`） | ✅ 通过 |
| 官方格式 prompt 分词 | ✅ `<｜hy_User｜>`→120006、`<｜hy_Assistant｜>`→120007 各为单个 token |
| BOS 是否重复 | ✅ 只 1 次 |
| 13 句真实屏幕文本 → 中文 | ✅ 全部正确，3 句与 llama-server 参考输出逐字一致 |
| 单句耗时（干净状态） | ✅ ≈0.83~1.03 s（与 llama-server 的 1.00s 一致） |

> 注：后一轮 A/B 对比跑在**热机状态**（前面连续编译 + 跑模型），出现 2~7s 的
> 异常值，那是热降频/调度抖动，不作为速度结论 —— 速度结论取 llama-bench 冷却后
> 与 llama-server 独立会话的数据（§2.1/§2.2）。

## 5. 已知限制

1. **速度**：1.00 s/句（短句）。它不是"更快"，是"离线可用"。
   实时屏幕翻译每次画面变化都翻，用本地模型会比云端慢，也更耗电。
2. **首次加载**：换量化档/首次使用要等几秒加载模型（可选预加载缓解）。
3. **1.25bit 档不可用**（上游内核未合并，见 §1）。
4. **无 SVE 优化**：本版只用了 dotprod/i8mm/fp16。上游支持按架构分发的多变体
   （`GGML_CPU_ALL_VARIANTS`），可作为后续优化。
5. **图片翻译用不了**（纯文本模型），会走 OCR 链路。

## 6. 验收清单

- [x] 设备内编译 llama.cpp CLI，确认支持 `hunyuan-dense`
- [x] 模型下载 + sha256 校验
- [x] 12 句真实文本端到端实测（延迟 + 质量）
- [x] 量化差距量化（基线 2.25s vs 自编 1.00s）
- [x] App 代码集成 + 编译通过 + APK 产出（debug 与 release 均出包）
- [x] 自编 native 运行时替换 AAR 的基线 .so（901 sdot vs 0）
- [x] 设备能力守卫（arm64 + dotprod/i8mm/fp16），不支持则禁用而非崩溃
- [ ] **装机实测**：本机没有 ADB/无障碍授权，无法自行安装与点击；
      需要手动安装 APK 后跑一遍「测试翻译」与实时屏幕翻译。

> 装机后建议的验证顺序：
> 1. 设置 → 翻译引擎与密钥 → 选「腾讯 Hy-MT2 1.8B（本地·离线）」
> 2. 点「下载模型」（Q4_K_M，约 5 分钟）→ 等「模型已就绪」
> 3. 点「测试翻译」→ 应显示 `Hello! This is a translation test.` 的中文译文
> 4. 点「预加载」量一下加载耗时；再进「实时屏幕翻译」看整句延迟与内存占用
