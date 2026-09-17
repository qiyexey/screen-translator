// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    // v1.17.0：升到 2.0.21 —— 本地大模型运行时 llama-kotlin-android 是 Kotlin 2.0.21
    // 编译的，1.9 编译器读不了 2.0 的元数据（报 "compiled with an incompatible
    // version of Kotlin"）。AGP 8.5.2 + Gradle 8.7 这个组合支持 2.0.21。
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
