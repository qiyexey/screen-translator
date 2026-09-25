// Top-level build file
plugins {
    // v1.18.0：8.5.2 → 8.6.0。唯一原因是要用 compileSdk 35（Android 15）——
    // AGP 8.5.x 的 compileSdk 上限是 34，写 35 会直接报不支持。
    // 8.6.0 要求 Gradle 8.7+，本工程正是 8.7，wrapper 不用动。
    id("com.android.application") version "8.6.0" apply false
    // v1.17.0：升到 2.0.21 —— 本地大模型运行时 llama-kotlin-android 是 Kotlin 2.0.21
    // 编译的，1.9 编译器读不了 2.0 的元数据（报 "compiled with an incompatible
    // version of Kotlin"）。AGP 8.6.0 + Gradle 8.7 这个组合同样支持 2.0.21。
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
