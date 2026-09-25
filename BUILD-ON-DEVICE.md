# 在设备上（Android/Termux）直接构建 APK

本文记录**不依赖电脑**、纯在手机内构建本项目的完整配方。
`tc/debs/` 里已经备好了全部离线包，所以只需一次联网拉 Android SDK。

> 为什么需要这份文档：`local.properties` 指向 `/root/android-sdk`，
> 但 `tc/root` 里其实**没有** android-sdk，Gradle 依赖缓存也是空的。
> 也就是说仓库里留了工具链的"料"，却没有把它装配起来的步骤。

---

## 0. 三个必须先知道的地基事实

这三条决定了后面每一步为什么这么写，不知道会反复踩：

1. **`/storage/emulated/0`（sdcard）是 `noexec`** —— 放在那的二进制**不能执行**，
   `.so` 也 `dlopen` 不了。所以 JDK、Gradle、SDK、工程副本**全都要放在
   `/data/data/<pkg>/files/home` 下**。
2. **`cmdline-tools` 里的 `aapt2` / `zipalign` 是 x86_64 ELF** —— aarch64 手机跑不了。
   `aapt2` 必须换成 Termux 的 aarch64 版；`zipalign` 用空实现顶掉（见 §4）。
3. **Gradle 自带的 `native-platform` 是 glibc 版**，Android(bionic) 加载不了 ——
   必须 `-Dorg.gradle.native=false`，否则报
   `Failed to load native library 'libnative-platform.so'`。

下面用 `H=$HOME` 代表 `/data/data/<pkg>/files/home`。

---

## 1. 装配 Termux 前缀（JDK17 + 依赖库）

```bash
H=$HOME
mkdir -p "$H/txroot"
cd /storage/emulated/0/Download/projects/tc/debs
for f in *.deb; do dpkg-deb -x "$f" "$H/txroot" 2>/dev/null; done
# JDK 在：$H/txroot/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
chmod -R u+rwX "$H/txroot"
```

> 为什么不能直接用 `tc/root`：那份解包**不完整**，缺 `libandroid-shmem.so` 等，
> 跑 java 会报 `dlopen failed: library "libandroid-shmem.so" not found`。
> 从 `tc/debs` 全量重新解包才有完整依赖。

**关键**：JDK 拷来拷去会**丢执行位**（sdcard 不保留），拷完必须 `chmod +x`。

---

## 2. 装 Android SDK

```bash
H=$HOME
export JAVA_HOME="$H/txroot/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
export LD_LIBRARY_PATH="$H/txroot/data/data/com.termux/files/usr/lib:/data/data/com.dsharnessmobile.shell/files/usr/lib"
export ANDROID_HOME="$H/android-sdk"

mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
unzip -q -o /storage/emulated/0/Download/projects/tc/debs/cmdline-tools.zip
mv cmdline-tools latest        # 规范成 cmdline-tools/latest/{bin,lib}

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" --install \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

装完应有 `platforms/android-34/android.jar`（约 26MB）。
若只有 15K，说明没下完。

---

## 3. 解 Gradle 8.7

```bash
mkdir -p "$H/tools" && cd "$H/tools"
unzip -q -o /storage/emulated/0/Download/projects/tc/debs/gradle-8.7-bin.zip
chmod -R u+rwX "$H/tools/gradle-8.7"
find "$H/tools/gradle-8.7" -name '*.so' -exec chmod +x {} \;
```

> `tc/debs/gradle-termux.deb` 里是 **Gradle 9.7.1**，与本项目的 AGP 8.5.2 不兼容
> （AGP 8.5 要 Gradle 8.7~8.x），所以用 `gradle-8.7-bin.zip` 这份。

---

## 4. 两个 build-tools 二进制的替换（最容易卡住的地方）

```bash
H=$HOME; AH="$H/android-sdk"

# aapt2 换成 Termux aarch64 版
mkdir -p "$H/tools/aapt2x"
dpkg-deb -x /storage/emulated/0/Download/projects/tc/debs/aapt2_16.0.0.4-2_aarch64.deb "$H/tools/aapt2x"
mkdir -p "$H/tools/bin"
cat > "$H/tools/bin/aapt2" <<'SH'
#!/system/bin/sh
export LD_LIBRARY_PATH="$HOME/txroot/data/data/com.termux/files/usr/lib:/data/data/com.dsharnessmobile.shell/files/usr/lib"
exec "$HOME/tools/aapt2x/data/data/com.termux/files/usr/bin/aapt2" "$@"
SH

# zipalign 空实现：对齐只是优化，未对齐的 APK 照样能装
ZP="$AH/build-tools/34.0.0/zipalign"
[ -f "$ZP.orig" ] || mv "$ZP" "$ZP.orig"
cat > "$ZP" <<'SH'
#!/system/bin/sh
last=""; prev=""
for a in "$@"; do prev="$last"; last="$a"; done
if [ -f "$prev" ] && [ -n "$last" ]; then cp "$prev" "$last"; fi
exit 0
SH

chmod +x "$H/tools/bin/aapt2" "$ZP"
```

> 取舍说明：`zipalign` 空实现意味着产出的 APK **未做 4 字节对齐**。
> 功能、安装、运行都不受影响（对齐只影响 mmap 加载效率）；
> 但**上架应用商店要求对齐**，正式发布请在有电脑的环境构建。

---

## 5. 构建

工程**拷到 home** 再构建（sdcard 上既慢又 noexec）：

```bash
H=$HOME
rm -rf "$H/build/ScreenTranslator"; mkdir -p "$H/build"
cp -r /storage/emulated/0/Download/projects/st/screen-translator "$H/build/ScreenTranslator"
rm -rf "$H/build/ScreenTranslator/.git"
printf 'sdk.dir=%s\n' "$H/android-sdk" > "$H/build/ScreenTranslator/local.properties"
```

`$H/run_gradle.sh`：

```sh
#!/system/bin/sh
H=$HOME
TMPD="$H/tmp"; mkdir -p "$TMPD"
export HOME="$H"                      # Termux JDK 的 user.home 被烘死成
export TMPDIR="$TMPD"                 # /data/data/com.termux/files/home，
export GRADLE_USER_HOME="$H/.gradle"  # 不覆盖会去创建不存在的目录
export JAVA_HOME="$H/txroot/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
export PATH="$H/tools/bin:/data/data/com.dsharnessmobile.shell/files/usr/bin:/system/bin"
export LD_LIBRARY_PATH="$H/txroot/data/data/com.termux/files/usr/lib:/data/data/com.dsharnessmobile.shell/files/usr/lib"
export ANDROID_HOME="$H/android-sdk"; export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="-Djava.io.tmpdir=$TMPD"
JVMARGS="-Xmx3072m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPD"

cd "$H/build/ScreenTranslator"
exec /system/bin/sh "$H/tools/gradle-8.7/bin/gradle" \
  -p "$H/build/ScreenTranslator" \
  -Dorg.gradle.native=false \
  -Djava.io.tmpdir="$TMPD" \
  -Duser.home="$H" \
  -Dorg.gradle.fileSystemWatching.enabled=false \
  -Pandroid.aapt2FromMavenOverride="$H/tools/bin/aapt2" \
  -Dorg.gradle.jvmargs="$JVMARGS" \
  --console=plain --no-daemon "$@"
```

```bash
"$H/run_gradle.sh" assembleDebug
# 产物：$H/build/ScreenTranslator/app/build/outputs/apk/debug/app-debug.apk
```

首次构建约 5 分钟（拉依赖），之后增量约 1~3 分钟。

---

## 6. 故障对照表（都实测过）

| 报错 | 原因 | 解法 |
|---|---|---|
| `Failed to load native library 'libnative-platform.so'` | Gradle 自带 native 库是 glibc 版 | `-Dorg.gradle.native=false` |
| `Failed to create parent directory '/data/data/com.termux'` | Termux JDK 把 `user.home` 烘死 | 设 `HOME` / `GRADLE_USER_HOME` / `-Duser.home` |
| `java.io.tmpdir is set to a directory that doesn't exist` | 同上，tmpdir 也指着 Termux 前缀 | `-Djava.io.tmpdir=$H/tmp` + `GRADLE_OPTS` |
| `permission denied` 执行 java | sdcard 不保留执行位 | `chmod +x`，且把工具链放 home |
| `dlopen failed: library "libandroid-shmem.so" not found` | `tc/root` 解包不全 | 从 `tc/debs` 全量重新解包 |
| aapt2 报 `cannot execute binary file` | 用的是 x86_64 版 | `-Pandroid.aapt2FromMavenOverride=` 指向 Termux aarch64 aapt2 |
| `'if' must have both main and 'else' branches if used as an expression` | `Result.fold` 的返回类型是泛型推断，lambda 末位不能是不带 else 的 if | 改成显式 `if (out != null) ... else ...` 语句块 |

---

## 7. 签名

`build.gradle.kts` 按 **环境变量 → `keystore.properties` → `local.properties`**
的顺序读口令（v1.18.0 起加了第二条）：

```bash
export SCREEN_TRANSLATOR_STORE_PASSWORD=...
export SCREEN_TRANSLATOR_KEY_PASSWORD=...
"$H/run_gradle.sh" assembleRelease
```

**没有口令时**：`assembleRelease` 会**直接报错中止**（v1.18.0 起）。
报错信息里带着生成密钥与配置口令的完整命令，照抄即可。

> v1.17.0 的行为是静默改用 Android 调试证书签名。那种包既上不了架，
> 又因为调试密钥全网公开而可以被同包名的恶意包冒名覆盖安装 ——
> 所以 v1.18.0 把"悄悄降级"改成了"显式失败"。原因详见 `FIXES-1.18.0.md` §1。

设备上生成密钥（如果 Termux 里有 `keytool`）：

```bash
keytool -genkeypair -v -keystore release.keystore -alias screentranslator \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

`assembleDebug` 不受签名检查影响，仍用自动生成的 `$HOME/.android/debug.keystore` 签名，
产物可安装；但它的签名与正式包（`release.keystore`）**不同**，
覆盖安装已装的正式版会失败，需要先卸载。

> v1.18.0 起 `assembleRelease` 还会开启 R8 混淆 + 资源收缩，
> 设备上构建会明显更慢、更吃内存。产物旁边会多一个 `mapping.txt`，
> **归档保存**，用来还原线上崩溃堆栈。
