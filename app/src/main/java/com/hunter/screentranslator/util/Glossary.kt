package com.hunter.screentranslator.util

/**
 * 自定义术语表（v1.29.0）：人名、技能名、专业词指定固定译法。
 *
 * 格式是纯文本，一行一条 `原文 = 译文`（也认 `=>`、`→`、全角 `＝`、Tab），
 * `#` 开头的行是注释。用纯文本而不是表格编辑器：几十上百条术语时，
 * 从别处整段粘贴进来比一条条点「添加」快得多。
 *
 * 两种用法，对应两类引擎：
 *  · AI 引擎（[promptSection]）：只把**这段原文里出现了的**术语写进提示词，
 *    不出现的不发 —— 术语表可能很长，全塞进去既费 token 又干扰模型。
 *  · 传统机翻 / 必应 / 本地模型（[preReplace]）：它们不接受指令，只能在送出前
 *    把原文里的术语直接换成译文。机翻遇到已经是目标语言的片段基本会原样保留，
 *    这是最稳的做法（换成占位符的话，各家引擎对 `__T0__` 之类的处理都不一致）。
 *
 * 匹配规则：长词优先（避免 "Dark" 抢走 "Dark Knight"）；含拉丁字母的词按整词、
 * 不区分大小写匹配（"Ash" 不会命中 "Ashley"）；中日韩词按子串匹配（它们没有词边界）。
 */
object Glossary {

    data class Entry(val source: String, val target: String)

    /** 条数上限：再多基本是误粘贴，而且每次翻译都要扫一遍 */
    const val MAX_ENTRIES = 500

    private val SEPARATORS = listOf("=>", "→", "＝", "=", "\t")

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedEntries: List<Entry> = emptyList()

    /** 解析术语表文本；同一份文本只解析一次 */
    fun parse(raw: String): List<Entry> {
        if (raw == cachedRaw) return cachedEntries
        val out = ArrayList<Entry>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val sep = SEPARATORS.firstOrNull { it in t } ?: continue
            val src = t.substringBefore(sep).trim()
            val dst = t.substringAfter(sep).trim()
            if (src.isNotEmpty() && dst.isNotEmpty()) out += Entry(src, dst)
            if (out.size >= MAX_ENTRIES) break
        }
        // 长词优先
        val sorted = out.distinctBy { it.source.lowercase() }.sortedByDescending { it.source.length }
        cachedRaw = raw
        cachedEntries = sorted
        return sorted
    }

    private fun hasLatin(s: String) = s.any { it in 'A'..'Z' || it in 'a'..'z' }

    private fun regexOf(e: Entry): Regex {
        val q = Regex.escape(e.source)
        return if (hasLatin(e.source)) {
            // 整词：前后不能紧挨着字母或数字
            Regex("(?<![\\p{L}\\p{N}])$q(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        } else {
            Regex(q)
        }
    }

    /** 这段原文里出现了哪些术语 */
    fun matching(text: String, entries: List<Entry>): List<Entry> =
        if (entries.isEmpty() || text.isBlank()) emptyList()
        else entries.filter { regexOf(it).containsMatchIn(text) }

    /**
     * 把原文里的术语换成指定译文（传统机翻用）。
     * 长词先换；换进去的译文不会再被短词二次替换（按原文位置一次性拼装）。
     */
    fun preReplace(text: String, entries: List<Entry>): String {
        val hits = matching(text, entries)
        if (hits.isEmpty()) return text
        // 记录每个字符是否已被更长的术语占用
        val taken = BooleanArray(text.length)
        val spans = ArrayList<Triple<Int, Int, String>>()
        for (e in hits) {
            for (m in regexOf(e).findAll(text)) {
                val r = m.range
                if ((r.first..r.last).any { taken[it] }) continue
                for (i in r) taken[i] = true
                spans += Triple(r.first, r.last + 1, e.target)
            }
        }
        if (spans.isEmpty()) return text
        val sb = StringBuilder()
        var pos = 0
        for ((start, end, target) in spans.sortedBy { it.first }) {
            sb.append(text, pos, start).append(target)
            pos = end
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    /**
     * 写进 AI 引擎提示词的一段；这段原文没用到任何术语时返回空串（提示词保持原样）。
     * 以换行开头，直接拼在 system 提示词末尾。
     */
    fun promptSection(text: String, entries: List<Entry>): String {
        val hits = matching(text, entries)
        if (hits.isEmpty()) return ""
        return buildString {
            append("\n\n【术语表】以下词语必须严格使用指定译法，不要意译或音译成别的写法：\n")
            for (e in hits) append(e.source).append(" → ").append(e.target).append('\n')
        }.trimEnd()
    }

    /** 图片翻译拿不到原文，没法挑"出现了的"，只能整表给（截断到前 [limit] 条） */
    fun promptSectionAll(entries: List<Entry>, limit: Int = 60): String {
        if (entries.isEmpty()) return ""
        return buildString {
            append("\n\n【术语表】画面里出现以下词语时，必须使用指定译法：\n")
            for (e in entries.take(limit)) append(e.source).append(" → ").append(e.target).append('\n')
        }.trimEnd()
    }

    /** 缓存作用域用：术语表一改，旧译文就不该再命中 */
    fun fingerprint(entries: List<Entry>): String =
        if (entries.isEmpty()) "" else "g" + entries.hashCode().toUInt().toString(16)
}
