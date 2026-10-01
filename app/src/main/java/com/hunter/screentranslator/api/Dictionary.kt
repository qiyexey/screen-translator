package com.hunter.screentranslator.api

import org.json.JSONObject

/**
 * 单词查词（v1.29.0）。
 *
 * 选中的是单个单词时，比起一个干巴巴的译文，用户更想看到音标、词性和几个常用义项。
 * 能查词的引擎实现 [DictionaryLookup]：AI 引擎让模型按 JSON 给释义，
 * 必应走它网页端的词典接口。其余引擎不实现，调用方照常翻译。
 */
interface DictionaryLookup {
    suspend fun lookup(word: String, targetLang: String, sourceLang: String = SOURCE_AUTO): Result<DictEntry>
}

/** 一个词性下的若干义项 */
data class DictSense(val pos: String, val meanings: List<String>)

data class DictEntry(
    val word: String,
    /** 音标；必应词典不提供，为 null */
    val phonetic: String?,
    val senses: List<DictSense>
) {
    /** 纯文本形式：复制、写历史、给不认识富文本的地方用 */
    fun toPlainText(): String = buildString {
        append(word)
        if (!phonetic.isNullOrBlank()) append("  ").append(phonetic)
        for (s in senses) {
            append('\n').append(s.pos).append(' ').append(s.meanings.joinToString("；"))
        }
    }

    companion object {
        const val MAX_SENSES = 4
        const val MAX_MEANINGS = 3

        /**
         * 判断是不是"一个单词"：只认拉丁 / 西里尔字母组成的单个词（允许连字符和撇号），
         * 2 ~ 32 个字符。中日韩没有词边界，选两个字到底算词还是短语说不清，不进查词。
         */
        fun isSingleWord(text: String): Boolean {
            val t = text.trim()
            if (t.length !in 2..32) return false
            return SINGLE_WORD.matches(t)
        }

        private val SINGLE_WORD = Regex("[\\p{IsLatin}\\p{IsCyrillic}]+(?:['’\\-][\\p{IsLatin}\\p{IsCyrillic}]+)*")

        /** AI 引擎的查词提示词：要求只回 JSON，便于解析 */
        fun prompt(targetName: String): String = """
            你是一本双语词典。用户会给你一个单词，请用【$targetName】给出释义，严格只输出下面这种 JSON，不要任何其它文字：
            {"word":"原词","phonetic":"音标（英文用 IPA，如 /ˈæp.əl/；没有就填空串）","senses":[{"pos":"词性缩写，如 n. / v. / adj.","meanings":["释义1","释义2"]}]}
            最多 $MAX_SENSES 个词性，每个词性最多 $MAX_MEANINGS 条释义，按常用程度排序；释义要短，像词典词条，不要例句。
        """.trimIndent()

        /**
         * 解析 AI 返回的 JSON。模型偶尔会包一层 ```json 代码块或在前后加一句话，
         * 所以取第一个 `{` 到最后一个 `}` 之间的部分。
         */
        fun parseAiJson(raw: String, fallbackWord: String): DictEntry {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw RuntimeException("模型没有按词典格式回答")
            val o = JSONObject(raw.substring(start, end + 1))
            val arr = o.optJSONArray("senses") ?: throw RuntimeException("模型没有给出释义")
            val senses = ArrayList<DictSense>()
            for (i in 0 until minOf(arr.length(), MAX_SENSES)) {
                val s = arr.optJSONObject(i) ?: continue
                val ms = s.optJSONArray("meanings") ?: continue
                val meanings = (0 until minOf(ms.length(), MAX_MEANINGS))
                    .map { ms.optString(it).trim() }
                    .filter { it.isNotEmpty() }
                if (meanings.isNotEmpty()) senses += DictSense(s.optString("pos").trim(), meanings)
            }
            if (senses.isEmpty()) throw RuntimeException("模型没有给出释义")
            return DictEntry(
                word = o.optString("word").trim().ifEmpty { fallbackWord },
                phonetic = o.optString("phonetic").trim().ifEmpty { null },
                senses = senses
            )
        }
    }
}
