package com.hunter.screentranslator.util

import android.content.Context
import android.util.Log
import com.hunter.screentranslator.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.10.0 翻译缓存。
 *
 * 为什么需要：
 * - 整屏自动翻译每次窗口变化都重发一次请求，而屏幕内容在滚动、切 Tab、来回切应用时
 *   是**大量重复**的；听视频的字幕、剪贴板监听同理（同一句话会反复出现）。
 * - 这些重复请求既费钱（LLM 按 token、MT 按字符计费）又慢（每次都要等一次网络往返）。
 *   命中缓存时本地直接返回，零延迟、零费用。
 *
 * 设计取舍：
 * - **命中判定是「引擎 + 端点 + 模型 + 目标语言 + 原文」五元组**，而不是只按原文。
 *   换引擎、换模型、换中转后译文会变，老译文不该被复用；同名模型挂在不同中转
 *   后端上甚至可能是完全不同的服务。
 * - **key 用 data class 而不是哈希值**：MD5/SHA 截断存在碰撞风险，而碰撞的后果是
 *   **返回错误译文**且极难排查。data class 的 equals/hashCode 由字段派生，零碰撞，
 *   代价只是一个额外的对象头。
 * - **LRU 淘汰，不做 TTL**：同样的输入永远该得到同样的译文，没有"过期"概念；
 *   真正需要的是把不常用的挤出去，所以按访问顺序淘汰。
 * - **写盘合并（防抖）**：整屏模式每秒可能产生多次写入，若每次都落盘，几百 KB 的文件
 *   会被反复重写，既伤闪存又没必要。这里把一段窗口内的多次写入合并成一次。
 * - **超长原文不入缓存**：整屏拼接出几万字符时，进缓存会把内存和文件一起撑大，
 *   而这类内容本来也几乎不会重复命中，直接放行去请求更划算。
 * - **先写临时文件再原子替换**，与 [HistoryStore] 一致：避免写一半被杀导致文件损坏。
 *
 * 容量由「设置 → 缓存条数上限」控制（[Prefs.cacheMaxEntries]），总开关是
 * [Prefs.cacheEnabled]；两者都有默认值，未接 UI 也能正常工作。
 */
object TranslationCache {

    private const val TAG = "ScreenTranslator"
    private const val FILE_NAME = "translate_cache.json"

    /** 单条原文超过这个长度就不缓存（整屏拼接的极端情况） */
    const val MAX_SOURCE_CHARS = 6000

    private const val DEFAULT_MAX_ENTRIES = 500
    private const val MIN_ENTRIES = 50
    private const val HARD_MAX_ENTRIES = 2000

    /** 写盘合并窗口：窗口内产生的多次写入合并成一次 */
    private const val PERSIST_MERGE_MS = 1500L

    /**
     * 缓存键。用 data class 而非字符串拼接或哈希，原因见类注释。
     * [scope] 形如 "deepseek|api.deepseek.com|deepseek-chat"。
     */
    data class Key(val scope: String, val targetLang: String, val source: String)

    private class Entry(val translated: String, val timeMs: Long, var hits: Int = 0)

    @Volatile
    private var maxEntries: Int = DEFAULT_MAX_ENTRIES

    @Volatile
    private var loaded: Boolean = false

    /**
     * accessOrder=true 的 LRU：读取会把条目移到队尾，淘汰时先扔队首（最久未用）。
     * 迭代顺序即"最久未用 → 最近使用"，按此顺序落盘再读回可保持 LRU 语义。
     */
    private val lru = object : LinkedHashMap<Key, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean =
            size > maxEntries
    }

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val pending = AtomicBoolean(false)

    private var appCtx: Context? = null

    /** 显式锁对象：避免在匿名 lambda 里对 `this` 的指向产生歧义 */
    private val lock = Any()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "st-cache-io").apply { isDaemon = true }
    }

    /** 缓存统计，供设置页展示（清空后归零） */
    data class Stats(val entries: Int, val hits: Long, val misses: Long, val fileBytes: Long) {
        val hitRate: Double
            get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses).toDouble()
    }

    /**
     * 在 Application.onCreate 里调一次。
     * 除记录 Context 外还会**后台预载**：调用点可能在主线程上（协程的默认调度器
     * 不保证是 IO），预载把读盘时机挪到启动阶段，避免第一次翻译时在调用线程上读文件。
     */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        io.execute {
            runCatching { synchronized(lock) { loadLocked() } }
                .onFailure { Log.e(TAG, "缓存预载失败: $it") }
        }
    }

    private fun file(): File? {
        val c = appCtx ?: return null
        return File(c.filesDir, FILE_NAME)
    }

    private fun loadLocked() {
        if (loaded) return
        // 还没 init（拿不到 filesDir）时不标记已载，等 init 之后那次调用再载盘，
        // 否则会永久跳过磁盘缓存、只剩纯内存缓存。
        val f = file() ?: return
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = o.optString("source")
                    val translated = o.optString("translated")
                    if (src.isEmpty() || translated.isEmpty()) continue
                    val key = Key(
                        scope = o.optString("scope"),
                        targetLang = o.optString("targetLang"),
                        source = src
                    )
                    lru[key] = Entry(translated, o.optLong("timeMs"), o.optInt("hits", 0))
                }
            }.onFailure { Log.e(TAG, "缓存读取失败: $it") }
        }
        loaded = true
        maxEntries = currentMax()
        // 文件里可能超过当前上限（用户调小了上限），立刻裁剪
        while (lru.size > maxEntries) {
            val first = lru.keys.firstOrNull() ?: break
            lru.remove(first)
        }
        Log.i(TAG, "翻译缓存已载入 ${lru.size} 条")
    }

    private fun currentMax(): Int = runCatching {
        App.prefs.cacheMaxEntries.coerceIn(MIN_ENTRIES, HARD_MAX_ENTRIES)
    }.getOrDefault(DEFAULT_MAX_ENTRIES)

    /**
     * 查缓存：命中返回译文，未命中返回 null。
     * 未配置 Context 时退化为"纯内存缓存"，不会崩。
     */
    fun get(scope: String, targetLang: String, source: String): String? = synchronized(lock) {
        loadLocked()
        val e = lru[Key(scope, targetLang, source)]
        if (e == null) {
            misses.incrementAndGet()
            Log.d(TAG, "[缓存] 未命中 ${source.length} 字符")
            null
        } else {
            e.hits += 1
            val n = hits.incrementAndGet()
            Log.d(TAG, "[缓存] 命中 ${source.length} 字符（累计命中 $n / 未命中 ${misses.get()}）")
            e.translated
        }
    }

    /** 写入缓存。只应写入**成功的译文**（失败/占位文案不进缓存）。 */
    fun put(scope: String, targetLang: String, source: String, translated: String) {
        val t = translated.trim()
        if (t.isEmpty() || scope.isEmpty() || source.isEmpty()) return
        if (source.length > MAX_SOURCE_CHARS) return
        synchronized(lock) {
            loadLocked()
            maxEntries = currentMax()
            lru[Key(scope, targetLang, source)] = Entry(t, System.currentTimeMillis())
        }
        schedulePersist()
    }

    /** 清空缓存（含统计与磁盘文件） */
    fun clear() {
        synchronized(lock) {
            lru.clear()
            hits.set(0)
            misses.set(0)
            loaded = true
        }
        io.execute { runCatching { writeNow() }.onFailure { Log.e(TAG, "缓存清空失败: $it") } }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            entries = lru.size,
            hits = hits.get(),
            misses = misses.get(),
            fileBytes = file()?.length() ?: 0L
        )
    }

    /**
     * 触发一次（合并后的）落盘。
     *
     * 只有"当前没有排队任务"时才入队；队列里的任务醒来时对**当时的**内存快照落盘，
     * 因此合并窗口内产生的所有写入都会被一并保存，不会丢尾巴。
     */
    private fun schedulePersist() {
        if (pending.getAndSet(true)) return
        io.execute {
            try {
                Thread.sleep(PERSIST_MERGE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                pending.set(false)
                return@execute
            }
            pending.set(false)
            runCatching { writeNow() }.onFailure { Log.e(TAG, "缓存写入失败: $it") }
        }
    }

    private fun writeNow() {
        val f = file() ?: return
        val arr = JSONArray()
        synchronized(lock) {
            lru.forEach { (k, v) ->
                arr.put(JSONObject().apply {
                    put("scope", k.scope)
                    put("targetLang", k.targetLang)
                    put("source", k.source)
                    put("translated", v.translated)
                    put("timeMs", v.timeMs)
                    put("hits", v.hits)
                })
            }
        }
        val text = arr.toString()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            // renameTo 在部分文件系统上会失败，退化为直接覆盖
            f.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
