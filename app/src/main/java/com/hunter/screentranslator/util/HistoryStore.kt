package com.hunter.screentranslator.util

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.8.0 翻译历史存储。
 *
 * 设计取舍：
 * - **JSON 文件**而非 Room/SQLite：历史是纯追加+上限裁剪的简单结构，条数上限 500，
 *   引入 Room 会带来注解处理器、schema 迁移等一整套开销，与"小工具"定位不符。
 * - **内存缓存 + 懒加载**：列表页与落库都从内存读，避免每次滑动都读磁盘。
 * - **写入用单线程 executor 串行化**：多个入口（无障碍事件、语音、图片）可能同时落库，
 *   串行写避免并发覆盖导致数据丢失。
 * - **条数上限自动裁剪**：超出 MAX_ITEMS 时丢弃最旧的，防止文件无限增长。
 */
object HistoryStore {

    private const val TAG = "ScreenTranslator"
    private const val FILE_NAME = "translate_history.json"
    private const val MAX_ITEMS = 500

    /** 单条历史 */
    data class Item(
        val id: Long,
        val source: String,
        val translated: String,
        val mode: String,
        val targetLang: String,
        val engine: String,
        val timeMs: Long,
        val favorite: Boolean
    ) {
        /** 列表页显示用的时间文本 */
        fun timeText(): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMs))
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "st-history-io").apply { isDaemon = true }
    }

    @Volatile
    private var cache: MutableList<Item>? = null

    private var appCtx: Context? = null

    /** 单调递增的 id 计数器，保证同一毫秒内也不会重复 */
    private var idSeq: Long = 0L

    private fun nextId(): Long {
        idSeq += 1
        return System.currentTimeMillis() * 1000 + (idSeq % 1000)
    }

    /** 必须在 Application.onCreate 里调一次（或任意入口首次调用前） */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun file(): File? {
        val c = appCtx ?: return null
        return File(c.filesDir, FILE_NAME)
    }

    private fun loadLocked(): MutableList<Item> {
        cache?.let { return it }
        val list = mutableListOf<Item>()
        val f = file()
        if (f != null && f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Item(
                            id = o.optLong("id"),
                            source = o.optString("source"),
                            translated = o.optString("translated"),
                            mode = o.optString("mode"),
                            targetLang = o.optString("targetLang"),
                            engine = o.optString("engine"),
                            timeMs = o.optLong("timeMs"),
                            favorite = o.optBoolean("favorite", false)
                        )
                    )
                }
            }.onFailure { Log.e(TAG, "历史读取失败: $it") }
        }
        cache = list
        return list
    }

    /** 读取全部（新→旧），返回副本，调用方可安全遍历 */
    fun all(): List<Item> = synchronized(this) { loadLocked().toList() }

    fun favorites(): List<Item> = all().filter { it.favorite }

    /** 关键词搜索（原文/译文都匹配，忽略大小写） */
    fun search(keyword: String): List<Item> {
        val k = keyword.trim()
        if (k.isEmpty()) return all()
        return all().filter {
            it.source.contains(k, ignoreCase = true) || it.translated.contains(k, ignoreCase = true)
        }
    }

    /**
     * 追加一条历史。
     * 会自动跳过：空内容、占位/报错文案（这些不该进历史）。
     */
    fun add(source: String, translated: String, mode: String, targetLang: String, engine: String) {
        val s = source.trim()
        val t = translated.trim()
        if (t.isEmpty()) return
        // 过滤非译文：占位符与错误提示
        if (t.startsWith("正在翻译") || t.startsWith("⚠️") || t.startsWith("❌") || t.startsWith("📍")) return
        if (s.isEmpty() && t.isEmpty()) return

        synchronized(this) {
            val list = loadLocked()
            // 与上一条完全相同则跳过（防抖失败时的重复事件）
            val last = list.firstOrNull()
            if (last != null && last.source == s && last.translated == t) return

            val now = System.currentTimeMillis()
            list.add(
                0,
                Item(
                    // id 用自增计数器保证唯一：同一毫秒内连续落库时纯时间戳会撞 id，
                    // 而 id 是列表项定位与删除/收藏的依据，撞了就会误删。
                    id = nextId(),
                    source = s,
                    translated = t,
                    mode = mode,
                    targetLang = targetLang,
                    engine = engine,
                    timeMs = now,
                    favorite = false
                )
            )
            if (list.size > MAX_ITEMS) {
                // 裁剪时保留收藏项：从尾部移除未收藏的
                var i = list.size - 1
                while (list.size > MAX_ITEMS && i >= 0) {
                    if (!list[i].favorite) list.removeAt(i)
                    i--
                }
            }
            persistLocked(list)
        }
    }

    fun delete(id: Long) {
        synchronized(this) {
            val list = loadLocked()
            list.removeAll { it.id == id }
            persistLocked(list)
        }
    }

    fun toggleFavorite(id: Long) {
        synchronized(this) {
            val list = loadLocked()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val it0 = list[idx]
                list[idx] = it0.copy(favorite = !it0.favorite)
                persistLocked(list)
            }
        }
    }

    fun clearAll() {
        synchronized(this) {
            val list = loadLocked()
            list.clear()
            persistLocked(list)
        }
    }

    /** 清空除收藏外的全部记录 */
    fun clearUnfavorited() {
        synchronized(this) {
            val list = loadLocked()
            list.removeAll { !it.favorite }
            persistLocked(list)
        }
    }

    /**
     * 落盘。在调用方持有锁的前提下把当前列表快照丢给 IO 线程异步写，
     * 避免在主线程做文件 IO（落库点可能在无障碍事件回调里）。
     */
    private fun persistLocked(list: List<Item>) {
        val snapshot = list.toList()
        val f = file() ?: return
        io.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { it0 ->
                    arr.put(JSONObject().apply {
                        put("id", it0.id)
                        put("source", it0.source)
                        put("translated", it0.translated)
                        put("mode", it0.mode)
                        put("targetLang", it0.targetLang)
                        put("engine", it0.engine)
                        put("timeMs", it0.timeMs)
                        put("favorite", it0.favorite)
                    })
                }
                // 先写临时文件再原子替换，避免写一半崩溃导致历史文件损坏
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    // renameTo 在部分文件系统上会失败，退化为直接覆盖
                    f.writeText(arr.toString(), Charsets.UTF_8)
                    tmp.delete()
                }
            }.onFailure { Log.e(TAG, "历史写入失败: $it") }
        }
    }

    /** 导出为纯文本（分享/备份用），返回文件路径 */
    fun exportTxt(): File? {
        val c = appCtx ?: return null
        val list = all()
        if (list.isEmpty()) return null
        val f = File(c.cacheDir, "translate_history.txt")
        return runCatching {
            val sb = StringBuilder()
            sb.append("屏幕翻译历史导出\n")
            sb.append("导出时间：")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                .append("\n")
            sb.append("共 ${list.size} 条\n")
            sb.append("=".repeat(40)).append("\n\n")
            list.forEach { it0 ->
                sb.append("[").append(it0.timeText()).append("] ").append(it0.mode)
                if (it0.favorite) sb.append(" ★")
                sb.append("\n")
                if (it0.source.isNotBlank()) sb.append("原：").append(it0.source).append("\n")
                sb.append("译：").append(it0.translated).append("\n\n")
            }
            f.writeText(sb.toString(), Charsets.UTF_8)
            f
        }.getOrNull()
    }
}
