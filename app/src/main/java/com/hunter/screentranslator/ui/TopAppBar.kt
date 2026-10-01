package com.hunter.screentranslator.ui

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.hunter.screentranslator.R

/**
 * 给「代码构建 UI」的页面套上与 XML 页面一致的 TopAppBar + 分隔线（v1.29.0）。
 *
 * XML 页面写的是 MaterialToolbar(Widget.ScreenTranslator.Toolbar) + MaterialDivider；
 * 图片翻译这类在 Kotlin 里拼界面的页面原来没有顶栏，只能靠系统返回手势离开，
 * 顶部观感也和其它页不一致。这里用同一套样式在代码里拼出来。
 *
 * 写成扩展函数而非放进 [BaseActivity]：图片翻译直接继承 AppCompatActivity，
 * 且自带同名的 dp()/themeColor()，改继承会引入签名冲突。
 */
fun Activity.withTopAppBar(title: CharSequence, content: View): View {
    val bar = MaterialToolbar(this, null, androidx.appcompat.R.attr.toolbarStyle).apply {
        this.title = title
        navigationIcon = ContextCompat.getDrawable(this@withTopAppBar, R.drawable.ic_arrow_back)
        navigationContentDescription = getString(R.string.common_btn_back_content_description)
        setNavigationOnClickListener { finish() }
    }
    val divider = MaterialDivider(this).apply {
        dividerColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    }
    val tv = TypedValue()
    val barHeight = if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) {
        TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    } else {
        (56 * resources.displayMetrics.density).toInt()
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        addView(bar, LinearLayout.LayoutParams(-1, barHeight))
        addView(divider, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density).toInt().coerceAtLeast(1)))
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
