package com.hunter.screentranslator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptDetectorTest {

    @Test
    fun chineseIsChinese() {
        assertTrue(ScriptDetector.isAlreadyIn("今天天气很好，我们去公园吧", "zh"))
        // 中文界面里夹几个英文单词，仍然是中文
        assertTrue(ScriptDetector.isAlreadyIn("打开蓝牙和WiFi以连接设备", "zh"))
    }

    @Test
    fun japaneseIsNotChinese() {
        assertFalse(ScriptDetector.isAlreadyIn("今日はいい天気ですね", "zh"))
        assertTrue(ScriptDetector.isAlreadyIn("今日はいい天気ですね", "ja"))
    }

    @Test
    fun chineseIsNotJapanese() {
        // 没有假名的纯汉字不能判成日文，否则中文会被当成"已是日文"漏翻
        assertFalse(ScriptDetector.isAlreadyIn("今天天气很好", "ja"))
    }

    @Test
    fun englishIsNotChinese() {
        assertFalse(ScriptDetector.isAlreadyIn("The quick brown fox jumps over the lazy dog", "zh"))
    }

    @Test
    fun latinTargetsNeverSkip() {
        // 英 / 法 / 德 / 西分不开，目标是它们时一律照常翻译
        assertFalse(ScriptDetector.isAlreadyIn("Hello world, how are you", "en"))
        assertFalse(ScriptDetector.isAlreadyIn("Bonjour tout le monde", "en"))
    }

    @Test
    fun koreanAndRussian() {
        assertTrue(ScriptDetector.isAlreadyIn("안녕하세요 반갑습니다", "ko"))
        assertFalse(ScriptDetector.isAlreadyIn("안녕하세요 반갑습니다", "zh"))
        assertTrue(ScriptDetector.isAlreadyIn("Привет, как дела", "ru"))
    }

    @Test
    fun thaiArabicAndTraditional() {
        assertTrue(ScriptDetector.isAlreadyIn("สวัสดีครับ", "th"))
        assertTrue(ScriptDetector.isAlreadyIn("مرحبا بالعالم", "ar"))
        assertFalse(ScriptDetector.isAlreadyIn("مرحبا بالعالم", "zh"))
        // 简繁分不出，目标是繁体时一律照常翻译
        assertFalse(ScriptDetector.isAlreadyIn("今天天气很好", "zh-TW"))
        assertFalse(ScriptDetector.isAlreadyIn("Xin chào các bạn", "vi"))
    }

    @Test
    fun tooShortIsUndecided() {
        assertFalse(ScriptDetector.isAlreadyIn("中", "zh"))
        // 两个汉字已经足够判断（"设置" 这种中文标签不用再翻）
        assertTrue(ScriptDetector.isAlreadyIn("设置", "zh"))
        assertFalse(ScriptDetector.isAlreadyIn("OK", "zh"))
    }

    @Test
    fun symbolsOnlyAreSkipped() {
        assertTrue(ScriptDetector.isAlreadyIn("12:30  85%", "zh"))
        assertFalse(ScriptDetector.isAlreadyIn("   ", "zh"))
    }

    @Test
    fun dropLinesKeepsOnlyForeignLines() {
        val screen = "设置\n今天天气很好\nThe weather is nice today\n12:30\n今日はいい天気"
        assertEquals(
            "The weather is nice today\n今日はいい天気",
            ScriptDetector.dropLinesAlreadyIn(screen, "zh")
        )
        assertEquals("", ScriptDetector.dropLinesAlreadyIn("今天天气很好\n我们去公园吧", "zh"))
    }
}
