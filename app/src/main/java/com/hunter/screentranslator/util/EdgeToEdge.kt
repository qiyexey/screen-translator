package com.hunter.screentranslator.util

import android.graphics.Color
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.hunter.screentranslator.R

/**
 * Android 15（API 35）edge-to-edge 适配（v1.18.0）。
 *
 * ## 为什么必须做这件事
 *
 * targetSdk 从 34 升到 35 之后，Android 15 会**强制**所有 Activity 进入 edge-to-edge：
 * 应用再也无法通过 `android:statusBarColor` / `android:navigationBarColor`
 * 把状态栏和导航栏"推"到内容之外 —— 系统直接忽略这两个属性，
 * 窗口铺满整个屏幕，内容会**画到状态栏和手势条下面**。
 *
 * 本工程原来的 13 个布局**没有一个**设置 `fitsSystemWindows`，
 * 也没有任何 WindowInsets 处理代码（全量搜索 `WindowInsets` 命中 0 处）。
 * 也就是说：如果不做适配，升到 targetSdk 35 后，每个页面的标题栏都会被
 * 状态栏压住一截 —— 这是"升 SDK"最常见的翻车点。
 *
 * ## 做法
 *
 * 1. [enableSystemBars] 用 AndroidX 官方的 `enableEdgeToEdge()` 打开 edge-to-edge，
 *    并把状态栏/导航栏都设为**全透明**，同时按当前是否深色模式设置图标明暗。
 *    用官方 API 而不是自己拼 `setDecorFitsSystemWindows` + `SYSTEM_UI_FLAG_*`：
 *    AppCompat 的 subDecor 自带 `fitsSystemWindows="true"`，手写很容易踩到
 *    "insets 被上层吃掉"的坑；`enableEdgeToEdge()` 内部已经处理了各 API 差异
 *    （含 26~28 的导航栏 scrim、29+ 的 `isNavigationBarContrastEnforced`、
 *    刘海屏的 `layoutInDisplayCutoutMode`）。
 *
 * 2. [padContent] 把系统栏高度作为 **padding** 加到内容根视图上。
 *    这里刻意选 padding 而不是 margin：padding 画在背景**之内**，
 *    而所有布局的根节点都带 `android:background="@color/bg_primary"`，
 *    于是根视图的背景会自然延伸到状态栏/导航栏底下，视觉上完全无缝，
 *    不需要给 13 个布局逐个改 XML。
 *
 *    底部同时取 `systemBars` 与 `ime` 的较大值：开启 edge-to-edge 后系统不再
 *    替应用做键盘避让（`adjustResize` 失效），输入框页面的内容必须自己躲键盘。
 *
 * ## 用法
 *
 * 在 Activity 的 `onCreate` 里、`setContentView(...)` **之后**调用一行：
 *
 * ```kotlin
 * setContentView(b.root)
 * EdgeToEdge.install(this)
 * ```
 */
object EdgeToEdge {

    /** 打开 edge-to-edge 并把内容根视图按系统栏/键盘内边距让开。幂等，可重复调用。 */
    fun install(activity: ComponentActivity) {
        enableSystemBars(activity)
        padContent(activity)
    }

    /**
     * 状态栏与导航栏全透明 —— 让根视图的 `bg_primary` 直接透上来。
     *
     * `SystemBarStyle.auto(lightScrim, darkScrim)` 会按 `uiMode` 自动判断深浅：
     * 浅色模式用浅色底配深色图标，深色模式反之。传 `Color.TRANSPARENT` 即
     * 不要任何 scrim，完全交给内容背景。
     */
    fun enableSystemBars(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
    }

    /**
     * 给 `android.R.id.content` 的第一个子视图（即布局根节点）挂 insets 监听。
     *
     * 用 `View.setTag(int, Object)` 打标记做幂等保护：`onCreate` 之外若有人
     * 重复调用（或后续改成在 `onContentChanged()` 里调用），不会叠加 padding。
     */
    fun padContent(activity: ComponentActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        val root = content.getChildAt(0) ?: return
        if (root.getTag(R.id.tag_edge_to_edge) == true) return
        root.setTag(R.id.tag_edge_to_edge, true)

        // 布局根节点自带的 padding 要先记下来，避免被 insets 覆盖掉。
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
        // 监听器挂上时 insets 可能已经派发过一轮，主动请求一次补发。
        ViewCompat.requestApplyInsets(root)
    }
}
