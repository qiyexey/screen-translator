package com.hunter.screentranslator.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictEntryTest {

    @Test
    fun singleWordDetection() {
        assertTrue(DictEntry.isSingleWord("apple"))
        assertTrue(DictEntry.isSingleWord("  well-known "))
        assertTrue(DictEntry.isSingleWord("don't"))
        assertTrue(DictEntry.isSingleWord("café"))
        assertTrue(DictEntry.isSingleWord("привет"))
        assertFalse(DictEntry.isSingleWord("apple pie"))
        assertFalse(DictEntry.isSingleWord("a"))
        assertFalse(DictEntry.isSingleWord("苹果"))
        assertFalse(DictEntry.isSingleWord("abc123"))
    }

    @Test
    fun parsesJsonWrappedInCodeFence() {
        val raw = """
            好的：
            ```json
            {"word":"apple","phonetic":"/ˈæp.əl/","senses":[{"pos":"n.","meanings":["苹果","苹果树"]}]}
            ```
        """.trimIndent()
        val e = DictEntry.parseAiJson(raw, "apple")
        assertEquals("apple", e.word)
        assertEquals("/ˈæp.əl/", e.phonetic)
        assertEquals(listOf(DictSense("n.", listOf("苹果", "苹果树"))), e.senses)
        assertEquals("apple  /ˈæp.əl/\nn. 苹果；苹果树", e.toPlainText())
    }

    @Test
    fun emptyPhoneticBecomesNull() {
        val e = DictEntry.parseAiJson("""{"word":"x","phonetic":"","senses":[{"pos":"n.","meanings":["a"]}]}""", "x")
        assertNull(e.phonetic)
    }

    @Test(expected = RuntimeException::class)
    fun rejectsNonJson() {
        DictEntry.parseAiJson("我不知道这个词", "foo")
    }
}
