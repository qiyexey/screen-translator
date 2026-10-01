package com.hunter.screentranslator.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityAppFilterBinding
import com.hunter.screentranslator.databinding.ItemAppFilterBinding
import com.hunter.screentranslator.util.AppFilterMode
import com.hunter.screentranslator.util.EdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.29.0：按 App 设置自动翻译（黑名单 / 白名单）。
 *
 * 只列有桌面入口的 App（见 Manifest 的 `<queries>`），不申请 QUERY_ALL_PACKAGES。
 * 勾选 / 切换模式即时保存，没有「保存」按钮 —— 这一页就是一张清单，
 * 改完直接返回最符合直觉。
 */
class AppFilterActivity : BaseActivity() {

    private lateinit var b: ActivityAppFilterBinding

    private data class AppItem(val pkg: String, val label: String)

    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private val picked = HashSet<String>()
    private val iconCache = HashMap<String, Drawable?>()
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppFilterBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        picked += App.prefs.appFilterPackages
        b.rgMode.check(
            when (App.prefs.appFilterMode) {
                AppFilterMode.BLOCK -> R.id.rbBlock
                AppFilterMode.ALLOW -> R.id.rbAllow
                else -> R.id.rbOff
            }
        )
        b.rgMode.setOnCheckedChangeListener { _, id ->
            App.prefs.appFilterMode = when (id) {
                R.id.rbBlock -> AppFilterMode.BLOCK
                R.id.rbAllow -> AppFilterMode.ALLOW
                else -> AppFilterMode.OFF
            }
            refreshEnabled()
        }

        b.listApps.adapter = adapter
        b.listApps.setOnItemClickListener { _, _, pos, _ ->
            val item = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (!picked.remove(item.pkg)) picked += item.pkg
            App.prefs.appFilterPackages = picked
            adapter.notifyDataSetChanged()
            refreshCount()
        }
        b.etSearch.doAfterTextChanged { applySearch() }

        refreshEnabled()
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to it }
                    .distinctBy { it.first }
                    .filter { it.first != packageName }
                    .map { (pkg, ri) -> AppItem(pkg, ri.loadLabel(pm).toString()) }
                    // 勾过的排前面，方便回来改
                    .sortedWith(compareBy<AppItem> { it.pkg !in picked }.thenBy { it.label.lowercase() })
            }
            applySearch()
        }
    }

    private fun applySearch() {
        val q = b.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        shown = if (q.isEmpty()) all
        else all.filter { q in it.label.lowercase() || q in it.pkg.lowercase() }
        adapter.notifyDataSetChanged()
        refreshCount()
    }

    /** 「不限制」时名单不起作用，列表变灰提示一下 */
    private fun refreshEnabled() {
        val on = App.prefs.appFilterMode != AppFilterMode.OFF
        b.listApps.alpha = if (on) 1f else 0.45f
        b.searchLayout.isEnabled = on
        // 行的可点状态（isEnabled）随模式变，要让 ListView 重新取
        adapter.notifyDataSetChanged()
        refreshCount()
    }

    private fun refreshCount() {
        if (all.isEmpty()) return
        b.tvCount.text = if (App.prefs.appFilterMode == AppFilterMode.ALLOW && picked.isEmpty()) {
            getString(R.string.app_filter_allow_empty)
        } else {
            getString(R.string.app_filter_count, all.size, picked.count { p -> all.any { it.pkg == p } })
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemAppFilterBinding.bind(it) }
                ?: ItemAppFilterBinding.inflate(layoutInflater, parent, false)
            val item = shown[position]
            row.tvLabel.text = item.label
            row.tvPackage.text = item.pkg
            row.cbPicked.isChecked = item.pkg in picked
            // 图标按需加载并缓存：一次性全加载几百个 App 的图标会卡住列表首屏
            val icon = iconCache.getOrPut(item.pkg) {
                runCatching { packageManager.getApplicationIcon(item.pkg) }.getOrNull()
            }
            row.ivIcon.setImageDrawable(icon)
            row.root.isEnabled = App.prefs.appFilterMode != AppFilterMode.OFF
            return row.root
        }

        // 「不限制」模式下点了也没意义，整行不可点
        override fun isEnabled(position: Int) = App.prefs.appFilterMode != AppFilterMode.OFF
        override fun areAllItemsEnabled() = false
    }
}
