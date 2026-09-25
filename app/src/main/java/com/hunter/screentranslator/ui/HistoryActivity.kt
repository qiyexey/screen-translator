package com.hunter.screentranslator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.Speaker

/**
 * v1.8.0 翻译历史。
 *
 * 用代码构建 UI + ScrollView 而非 RecyclerView：上限 500 条、且屏幕翻译的历史
 * 是"回看最近几条"的用法，为一屏列表引入 Adapter/ViewHolder 样板不划算。
 * 若日后要支持上千条或复杂过滤，再换 RecyclerView 更合适。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private var onlyFavorites = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "翻译历史"
        setContentView(buildUi())
        EdgeToEdge.install(this)
        refresh()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // 搜索框
        etSearch = EditText(this).apply {
            hint = "搜索原文或译文…"
            setSingleLine()
            textSize = 14f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(etSearch, LinearLayout.LayoutParams(-1, -2))

        // 工具行：只看收藏 / 导出 / 清空
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        val btnFav = Button(this).apply {
            text = "只看收藏"
            setOnClickListener {
                onlyFavorites = !onlyFavorites
                text = if (onlyFavorites) "显示全部" else "只看收藏"
                refresh()
            }
        }
        val btnExport = Button(this).apply {
            text = "导出"
            setOnClickListener { exportHistory() }
        }
        val btnClear = Button(this).apply {
            text = "清空"
            setOnClickListener { confirmClear() }
        }
        tools.addView(btnFav, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(btnExport, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tools)

        tvEmpty = TextView(this).apply {
            text = "还没有翻译记录。\n\n开启无障碍服务或使用悬浮球翻译后，结果会自动记在这里。"
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        root.addView(tvEmpty)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll)

        return root
    }

    private fun refresh() {
        val keyword = etSearch.text?.toString().orEmpty()
        val items = if (onlyFavorites) {
            HistoryStore.favorites().filter { matches(it, keyword) }
        } else {
            HistoryStore.search(keyword)
        }

        listBox.removeAllViews()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            tvEmpty.text = if (onlyFavorites) "还没有收藏的记录。\n\n点条目右侧的 ☆ 可加入收藏。"
            else if (keyword.isNotBlank()) "没有匹配「$keyword」的记录。"
            else "还没有翻译记录。\n\n开启无障碍服务或使用悬浮球翻译后，结果会自动记在这里。"
            return
        }

        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }

            // 头行：时间 + 模式 + 收藏星
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvMeta = TextView(this).apply {
                text = "${item.timeText()} · ${item.mode}"
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 11f
            }
            val btnStar = TextView(this).apply {
                text = if (item.favorite) "★" else "☆"
                textSize = 20f
                setTextColor(if (item.favorite) themeColor(com.google.android.material.R.attr.colorTertiary) else themeColor(com.google.android.material.R.attr.colorOutline))
                setPadding(dp(10), 0, dp(4), 0)
                setOnClickListener {
                    HistoryStore.toggleFavorite(item.id)
                    refresh()
                }
            }
            head.addView(tvMeta, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(btnStar)
            card.addView(head)

            if (item.source.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "原：${item.source}"
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    textSize = 12f
                    maxLines = 3
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val tvTrans = TextView(this).apply {
                text = item.translated
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                textSize = 14f
                setPadding(0, dp(4), 0, dp(6))
            }
            card.addView(tvTrans)

            // 操作行：复制 / 朗读译文 / 朗读原文 / 删除
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(smallBtn("复制") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("译文", item.translated))
                toast(getString(R.string.history_t01))
            })
            actions.addView(smallBtn("🔊 译文") {
                // v1.9.3：历史条目各自记录了自己的目标语言（item.targetLang），
                // 与"当前"目标语言可能不同，所以这里显式指定，不跟随全局设置。
                Speaker.speak(this, item.translated, item.targetLang)
            })
            // 只有存了原文的条目才提供朗读原文
            if (item.source.isNotBlank()) {
                actions.addView(smallBtn("🔊 原文") {
                    // 原文语言若设为"自动判断"，按这条记录的内容粗判
                    val lang = Speaker.resolveSourceLang(App.prefs.ttsSourceLang, item.source)
                    Speaker.speak(this, item.source, lang)
                })
            }
            actions.addView(smallBtn("删除") {
                HistoryStore.delete(item.id)
                refresh()
            })
            card.addView(actions)

            listBox.addView(card)
        }
    }

    private fun matches(item: HistoryStore.Item, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return true
        return item.source.contains(k, ignoreCase = true) ||
            item.translated.contains(k, ignoreCase = true)
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(4), dp(18), dp(4))
            setOnClickListener { onClick() }
        }

    private fun exportHistory() {
        val f = HistoryStore.exportTxt()
        if (f == null) {
            toast(getString(R.string.history_t03))
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "导出翻译历史"
                )
            )
        }.onFailure {
            toast("导出失败：${it.message}")
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("将删除全部翻译记录（收藏项会保留）。此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                HistoryStore.clearUnfavorited()
                refresh()
                toast(getString(R.string.history_t02))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** 从当前主题解析 M3 语义色（这样代码构建的界面也能跟随明暗主题） */
    private fun themeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
