package com.hunter.screentranslator.util

/**
 * 判断一段文字是不是已经是目标语言（v1.29.0「原文已是目标语言就不翻」）。
 *
 * 只按**文字系统**判断，不引入语言识别模型（ML Kit language-id 要多 1MB+，而且
 * 对 UI 里大量的两三个字的短标签同样不准）。代价是拉丁字母语言之间分不开：
 * 英 / 法 / 德 / 西都长得一样，所以目标语言是这几种时**一律不跳过**，宁可多翻也不漏翻。
 *
 * 能可靠判断的只有文字系统独有的语言：中文（汉字、无假名）、日文（有假名）、
 * 韩文（谚文）、俄文（西里尔字母）、泰文、阿拉伯文。
 *
 * 已知局限：目标是简体中文时，繁体原文也会被当成"已是中文"跳过（只看文字系统分不出简繁）。
 *
 * 计数口径：拉丁字母按**单词**计（连续字母算 1 个），汉字 / 假名 / 谚文按**字**计。
 * 否则中文界面里一个 "WiFi"、"Bluetooth" 就顶得上四五个汉字，比例被拉偏。
 */
object ScriptDetector {

    /** 少于这么多个"单位"时不下结论（单个汉字、单个英文单词 "OK" 判不准） */
    private const val MIN_UNITS = 2

    private data class Counts(
        val han: Int, val kana: Int, val hangul: Int, val cyrillicWords: Int, val latinWords: Int,
        val thai: Int, val arabicWords: Int
    ) {
        val total get() = han + kana + hangul + cyrillicWords + latinWords + thai + arabicWords
    }

    private fun count(text: String): Counts {
        var han = 0; var kana = 0; var hangul = 0; var cyr = 0; var lat = 0; var thai = 0; var ara = 0
        var inLatin = false; var inCyr = false; var inAra = false
        for (ch in text) {
            val block = Character.UnicodeScript.of(ch.code)
            val isLatin = block == Character.UnicodeScript.LATIN && ch.isLetter()
            val isCyr = block == Character.UnicodeScript.CYRILLIC && ch.isLetter()
            if (isLatin && !inLatin) lat++
            val isAra = block == Character.UnicodeScript.ARABIC && ch.isLetter()
            if (isCyr && !inCyr) cyr++
            if (isAra && !inAra) ara++
            inLatin = isLatin; inCyr = isCyr; inAra = isAra
            when (block) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                // 泰文词间不留空格，没法按词数，按字计
                Character.UnicodeScript.THAI -> thai++
                else -> Unit
            }
        }
        return Counts(han, kana, hangul, cyr, lat, thai, ara)
    }

    /**
     * [text] 是否**可以确定**已经是 [targetLang]。拿不准一律返回 false（照常翻译）。
     * 没有任何文字（纯数字、符号、表情）也返回 true —— 那种内容没什么可翻的。
     */
    fun isAlreadyIn(text: String, targetLang: String): Boolean {
        val c = count(text)
        if (c.total == 0) return text.isNotBlank()
        if (c.total < MIN_UNITS) return false
        return when (targetLang) {
            // 汉字为主、没有假名（有假名就是日文）
            "zh" -> c.kana == 0 && c.han * 10 >= c.total * 7
            // 有假名，且假名 + 汉字为主
            "ja" -> c.kana > 0 && (c.kana + c.han) * 10 >= c.total * 7
            "ko" -> c.hangul * 10 >= c.total * 7
            "ru" -> c.cyrillicWords * 10 >= c.total * 7
            "th" -> c.thai * 10 >= c.total * 7
            "ar" -> c.arabicWords * 10 >= c.total * 7
            // 繁体中文：只看文字系统分不出简繁，不跳过（简体内容也要转成繁体）
            // 英 / 法 / 德 / 西 / 越 / 印尼：都是拉丁字母，分不开，不跳过
            else -> false
        }
    }

    /**
     * 按行过滤掉已经是 [targetLang] 的行，只留需要翻译的部分（全屏翻译用）。
     * 全部都是目标语言时返回空串，调用方据此跳过这次请求。
     */
    fun dropLinesAlreadyIn(text: String, targetLang: String): String =
        text.lineSequence()
            .filter { line -> line.isNotBlank() && !isAlreadyIn(line, targetLang) }
            .joinToString("\n")
}
