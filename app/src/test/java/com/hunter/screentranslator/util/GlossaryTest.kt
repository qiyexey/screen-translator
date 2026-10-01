package com.hunter.screentranslator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {

    private val entries = Glossary.parse(
        """
        # 游戏术语
        Ash = 小智
        Dark Knight => 黑暗骑士
        Dark → 暗黑
        ポケモン＝宝可梦
        """.trimIndent()
    )

    @Test
    fun parsesAllSeparatorsAndSkipsComments() {
        assertEquals(4, entries.size)
        // 长词排在前面
        assertEquals("Dark Knight", entries.first().source)
    }

    @Test
    fun latinTermsMatchWholeWordsOnly() {
        assertEquals("小智 and Ashley", Glossary.preReplace("Ash and Ashley", entries))
        assertEquals("小智!", Glossary.preReplace("ash!", entries))
    }

    @Test
    fun longerTermWins() {
        assertEquals("The 黑暗骑士 is 暗黑", Glossary.preReplace("The Dark Knight is Dark", entries))
    }

    @Test
    fun cjkTermsMatchAsSubstring() {
        assertEquals("宝可梦センター", Glossary.preReplace("ポケモンセンター", entries))
    }

    @Test
    fun promptOnlyListsTermsThatAppear() {
        val p = Glossary.promptSection("Ash meets the Dark Knight", entries)
        assertTrue("Ash → 小智" in p)
        assertTrue("Dark Knight → 黑暗骑士" in p)
        assertTrue("ポケモン" !in p)
        assertEquals("", Glossary.promptSection("nothing here", entries))
    }
}
