# app/libs

## llama-kotlin-android-0.1.7-classes.jar

`org.codeshipping:llama-kotlin-android:0.1.7` 的 **classes.jar**（Kotlin API 层），
MIT 许可，版权归 Prasoon（见 `LICENSE-llama-kotlin-android.txt`）。

为什么不直接用 Maven 依赖：那个 AAR 里带的 `libllama-android.so` 是按
**armv8-a 基线**编译的（反汇编确认：sdot / smmla / fmlal 指令数为 0），
在实测中整句延迟比本工程自编的 armv8.6-a 版本慢 **2.25 倍**
（2.25s → 1.00s，见 FIXES-1.17.0.md）。所以：

- 保留它的 Kotlin API（本 jar，二进制与 native 的 JNI 签名一一对应）
- `libllama-android.so` 改为本工程自编（源码见 `jni/`，llama.cpp 上游 + 官方 JNI 桥）

另外，直接依赖那个 AAR 还会经 `androidx.core:core-ktx:1.17.0` 把工程现用的
1.13.1 顶上去，进而要求 compileSdk 36 + AGP 8.9.1+ —— 改用本地 jar 后这个
传递依赖消失，build.gradle.kts 里那条 resolutionStrategy.force 也不再需要。
