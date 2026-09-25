# ============================================================================
# R8 / ProGuard 规则 —— v1.18.0
#
# v1.17.0 这里只有一行：
#     -keep class com.hunter.screentranslator.** { *; }
# 它把整个 App 的类名、方法名、字段名全部保住，等于**关掉了混淆**：
# 反编译后包结构、类名、方法名与源码一一对应，配合 kotlin.Metadata
# 能还原出接近原始 Kotlin 源码的结构。这次删掉它，改成"只保留真正需要保留的"。
#
# 判断依据（逐条核对过源码，不是照抄模板）：
#   · 工程内 0 处反射调用（无 Class.forName / getDeclaredMethod / getDeclaredField）
#   · 工程内 0 处自定义 View 出现在 layout XML 里（悬浮球/叠层全是代码创建）
#   · 工程内 0 处 moshi/gson 之类按字段名反序列化的库（JSON 一律用 org.json，
#     v1.18.0 已把没用到却一直挂在依赖里的 moshi 移除）
#   · 工程内 0 处 System.loadLibrary（native 库由 vendored jar 内部加载）
# 所以需要人工保留的只有下面 4 类。
#
# 关于 Kotlin 元数据：AGP 8.x 默认使用 R8 full mode，full mode 下**默认剥掉
# 未被显式保留的注解**，kotlin.Metadata 会随之消失 —— 这正是我们要的。
# 因此这里刻意**不写** -keepattributes RuntimeVisibleAnnotations，
# 也不写 -keep class kotlin.Metadata；写了等于把原始类名又塞回 dex，白混淆。
# ============================================================================

# ---------------------------------------------------------------------------
# 1. JNI 入口
#
# libllama-android.so 导出的符号形如：
#     Java_org_codeshipping_llamakotlin_LlamaNative_native*
# native 方法是**按名字**绑定的，类名和包名都编在符号里，混淆任何一个都会
# UnsatisfiedLinkError。llama-kotlin-android-0.1.7-classes.jar 是本工程
# vendored 的裸 jar，不带 consumer 规则，必须自己写。
# ---------------------------------------------------------------------------
-keep class org.codeshipping.llamakotlin.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 2. 被"字符串点名"反射加载的类
#
# Firebase Components 的发现机制：扫描 <meta-data> 里 name 以
# "com.google.firebase.components:" 开头的项，把 **value 当类名反射加载**。
# DataTransport 同理，类名藏在 meta-data 的 name 里（"backend:...CctBackendFactory"）。
#
# R8 看不到这层引用。这些类一旦被改名/删除，ML Kit 初始化阶段就会崩，
# 而且崩在启动路径上、堆栈完全指不到真实原因。AAR 一般自带规则，但依赖版本
# 变动可能让规则静默失效，所以这里逐个点名（一共 4 个类，体积代价可忽略）。
# ---------------------------------------------------------------------------
-keep class com.google.mlkit.vision.text.internal.TextRegistrar { *; }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { *; }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { *; }
-keep class com.google.android.datatransport.cct.CctBackendFactory { *; }
# 兜底：任何 ComponentRegistrar 实现都不许改名
-keepnames class * implements com.google.firebase.components.ComponentRegistrar

# ---------------------------------------------------------------------------
# 3. 系统按类名实例化的组件
#
# Activity / Service / Receiver / Provider / Application 由 framework 反射创建，
# 且名字写在清单里。AGP 会依据清单生成 keep 规则（aapt_rules.txt），这里显式
# 再写一遍是为了"清单里没有、但由代码 startService 的"情况也不出错 ——
# 本工程恰好一个都没有（4 个服务全在清单里），所以这段其实可以删；
# 留着是因为它只覆盖少数几个类，成本极低而漏掉一次的代价是线上崩溃。
# ---------------------------------------------------------------------------
-keep class * extends android.app.Activity { <init>(); }
-keep class * extends android.app.Service { <init>(); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class * extends android.app.Application { <init>(); }

# ---------------------------------------------------------------------------
# 4. 泛型签名
#
# R8 full mode 下 Signature 属性只对显式 keep 的成员保留。本工程没用
# Gson/TypeToken 这类依赖，但 ML Kit 的 Task API 与 CameraX 的回调带泛型，
# 保留这个属性的代价很小（少量常量池条目）。
# 注意：**不要**顺带保留 RuntimeVisibleAnnotations —— 那会把 kotlin.Metadata
# 一起留下，混淆效果打折。
# ---------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---------------------------------------------------------------------------
# 5. 编译期告警压制（这些类由运行环境/可选依赖提供，不在 classpath 上）
# ---------------------------------------------------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.parcelize.**

# ---------------------------------------------------------------------------
# 6. 行号与源文件名
#
# 混淆后栈轨迹里的行号是定位线上崩溃的唯一线索。代价是 dex 里多一份
# LineNumberTable（很小）。配合构建时产出的 mapping.txt 才能把线上堆栈
# 还原成可读调用栈 —— CI 已把 mapping.txt 作为构建产物归档
# （见 .github/workflows/android.yml）。
# ---------------------------------------------------------------------------
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
