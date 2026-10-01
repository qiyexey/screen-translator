package com.hunter.screentranslator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityHistoryBinding
import com.hunter.screentranslator.databinding.ItemHistoryBinding
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.Speaker

/**
 * v1.8.0 翻译历史。
 *
 * v1.29.0：界面从「Kotlin 里拼 View」改成 XML + M3，与其它二级页同一套结构
 * （TopAppBar + 分隔线 + 卡片）。原来这页没有返回栏、用原生灰按钮、卡片底色
 * 写死白色，是全 App 唯一一个和整体风格脱节、且深色模式下看不清的页面。
 *
 * 列表仍用 NestedScrollView + 逐条 inflate 而非 RecyclerView：上限 500 条、
 * 且屏幕翻译的历史是"回看最近几条"的用法，为一屏列表引入 Adapter/ViewHolder
 * 样板不划算。若日后要支持上千条或复杂过滤，再换 RecyclerView 更合适。
 */
class HistoryActivity : BaseActivity() {

    private lateinit var b: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)

        b.topAppBar.setNavigationOnClickListener { finish() }
        b.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> { exportHistory(); true }
                R.id.action_clear -> { confirmClear(); true }
                else -> false
            }
        }
        b.etSearch.addTextChangedListener { refresh() }
        b.chipFavorites.setOnCheckedChangeListener { _, _ -> refresh() }

        refresh()
    }

    private fun refresh() {
        val keyword = b.etSearch.text?.toString().orEmpty()
        val onlyFavorites = b.chipFavorites.isChecked
        val items = if (onlyFavorites) {
            HistoryStore.favorites().filter { matches(it, keyword) }
        } else {
            HistoryStore.search(keyword)
        }

        b.listBox.removeAllViews()
        val empty = items.isEmpty()
        b.emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        b.scroll.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvCount.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            b.tvEmpty.text = when {
                keyword.isNotBlank() -> getString(R.string.history_empty_search, keyword)
                onlyFavorites -> getString(R.string.history_empty_favorites)
                else -> getString(R.string.history_empty_all)
            }
            return
        }
        b.tvCount.text = getString(R.string.history_count, items.size)
        items.forEach { b.listBox.addView(buildItem(it)) }
    }

    private fun buildItem(item: HistoryStore.Item): View {
        val v = ItemHistoryBinding.inflate(layoutInflater, b.listBox, false)

        v.tvMeta.text = "${item.timeText()} · ${item.mode}"
        bindStar(v, item.favorite)
        v.btnStar.setOnClickListener {
            HistoryStore.toggleFavorite(item.id)
            refresh()
        }

        v.tvSource.visibility = if (item.source.isBlank()) View.GONE else View.VISIBLE
        v.tvSource.text = item.source
        v.tvTranslated.text = item.translated

        v.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("译文", item.translated))
            toast(getString(R.string.history_t01))
        }
        v.btnSpeakTrans.setOnClickListener {
            // v1.9.3：历史条目各自记录了自己的目标语言（item.targetLang），
            // 与"当前"目标语言可能不同，所以这里显式指定，不跟随全局设置。
            Speaker.speak(this, item.translated, item.targetLang)
        }
        // 只有存了原文的条目才提供朗读原文
        v.btnSpeakSource.visibility = if (item.source.isBlank()) View.GONE else View.VISIBLE
        v.btnSpeakSource.setOnClickListener {
            // 原文语言若设为"自动判断"，按这条记录的内容粗判
            val lang = Speaker.resolveSourceLang(App.prefs.ttsSourceLang, item.source)
            Speaker.speak(this, item.source, lang)
        }
        v.btnDelete.setOnClickListener {
            HistoryStore.delete(item.id)
            refresh()
        }
        return v.root
    }

    private fun bindStar(v: ItemHistoryBinding, favorite: Boolean) {
        v.btnStar.icon = ContextCompat.getDrawable(
            this, if (favorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        v.btnStar.iconTint = ColorStateList.valueOf(
            themeColor(
                if (favorite) com.google.android.material.R.attr.colorTertiary
                else com.google.android.material.R.attr.colorOutline
            )
        )
        v.btnStar.contentDescription = getString(
            if (favorite) R.string.history_btn_unfavorite else R.string.history_btn_favorite
        )
    }

    private fun matches(item: HistoryStore.Item, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return true
        return item.source.contains(k, ignoreCase = true) ||
            item.translated.contains(k, ignoreCase = true)
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
}
