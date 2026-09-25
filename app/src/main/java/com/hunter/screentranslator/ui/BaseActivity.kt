package com.hunter.screentranslator.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.service.OverlayService

/**
 * v1.12.0 设置页拆分后，各页共用的工具方法。
 *
 * 拆分前这些都挤在 MainActivity 里（当时的 MainActivity 有 668 行，同时管着
 * 权限状态、引擎密钥、朗读、悬浮球外观、语音识别五件互不相干的事）。抽到基类
 * 避免每个页面各抄一份，也保证"判定无障碍是否开启"这类逻辑只有一处实现。
 */
abstract class BaseActivity : AppCompatActivity() {

    protected fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 本应用的无障碍服务当前是否在运行 */
    protected fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    /** 权限齐全时自动拉起悬浮窗服务（双保险：无障碍服务连接时也会拉起） */
    protected fun maybeStartOverlay() {
        if (!App.prefs.overlayEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        runCatching { OverlayService.start(this) }
    }

    /**
     * 从当前主题解析语义色。
     *
     * 状态文字不能写死颜色：夜间模式下亮绿配深底、纯红配深底都不合 M3 规范，
     * 而本次 M3 改造的目的就是"颜色走角色"。按 M3 属性解析；自定义角色
     * （`md_success` 这类不是 M3 标准属性的）走 [resColor]。
     */
    protected fun themeColor(attrRes: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) {
            ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    /**
     * 取自定义语义色（`md_success` / `md_warning` / `bg_primary` 这类非 M3 标准角色）。
     *
     * v1.18.0：**参数从"资源名"改成资源 ID**。
     *
     * 原来是 `resColor("md_success")`，内部走 `resources.getIdentifier(name, "color", ...)`。
     * 这在运行时按名字查表，编译期完全看不见 —— 于是 `md_success` / `md_warning` /
     * `bg_primary` / `bg_card` / `text_primary` / `text_secondary` 这 6 个颜色
     * 在 XML 里没有任何静态引用。一旦开启 `shrinkResources`（v1.18.0 已开），
     * 它们会被判定为"无人使用"直接删掉，取色返回 0 → 走到 fallback 的硬编码浅色
     * → **引导页在深色模式下静默变成浅底浅字**，而且不报任何错。
     *
     * 换成资源 ID 后：编译期就能校验存在性，资源收缩也能正确看到引用，
     * 顺带省掉每次取色的字符串查表开销。
     */
    protected fun resColor(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    /**
     * 一键申请忽略电池优化（防 ROM 杀后台导致无障碍掉线）。
     * 不支持直接弹窗的 ROM 退回列表页；两者都失败则如实告知，不假装成功。
     */
    protected fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.base_t02))
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast(getString(R.string.base_t01)) }
        }
    }

    protected fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // ==================== M3 下拉框的两个小工具（v1.19.0）====================

    /**
     * 读「当前选中的是第几项」。
     *
     * v1.19.0 把 8 个 android.widget.Spinner 换成了
     * TextInputLayout(ExposedDropdownMenu) + MaterialAutoCompleteTextView。
     * 换完之后第一处 API 对不上：Spinner 有 selectedItemPosition，
     * AutoCompleteTextView 没有对应物（它继承自 EditText，只有"文本选区的起止"）。
     *
     * 这里按**文本反查**位置。可靠性来自 ExposedDropdownMenu 的硬性要求：
     * 子控件必须 inputType="none"，用户没法手打，文本框内容只可能是
     * 适配器里的某一项（初始化走 [setSel]，点选走 onItemClickListener）。
     * 找不到就退回 0 —— 和 Spinner 的默认值一致，不会崩。
     */
    protected fun selPos(v: MaterialAutoCompleteTextView): Int {
        val a = v.adapter ?: return 0
        val text = v.text?.toString().orEmpty()
        for (i in 0 until a.count) {
            if (a.getItem(i)?.toString() == text) return i
        }
        return 0
    }

    /**
     * 选中第 [pos] 项。
     *
     * 第二处 API 对不上：Spinner.setSelection(pos) 是"选第几项"，
     * 而 AutoCompleteTextView.setSelection(pos) 是"把文本光标/选区放到第几个字符"
     * —— 名字一样、语义完全无关，照搬会得到一个空下拉。
     * 正确做法是 setText(第 pos 项, false)，第二个参数 false 表示不要触发过滤，
     * 否则适配器会按新文本过滤，下拉里只剩一项。
     *
     * 越界统一钳制：Spinner 会静默忽略非法下标，这里显式 coerceIn，
     * 避免调用方漏掉 coerceAtLeast 时抛 IndexOutOfBounds。
     */
    protected fun setSel(v: MaterialAutoCompleteTextView, pos: Int) {
        val a = v.adapter ?: return
        if (a.count == 0) return
        val p = pos.coerceIn(0, a.count - 1)
        v.setText(a.getItem(p).toString(), false)
    }
}
