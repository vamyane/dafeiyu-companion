package com.dafeiyu.companion.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplySplitterTest {

    @Test
    fun splitsAtSentenceEnd() {
        val parts = ReplySplitter.split(
            "紧张也正常，谁面试前不紧张啊。不过你今晚别熬太晚，睡不够明天更慌。"
        )
        assertEquals(2, parts.size)
        assertTrue(parts[0].endsWith("。"))
        assertTrue(parts[1].endsWith("。"))
    }

    @Test
    fun capsAtRequestedBubbles() {
        val parts = ReplySplitter.split(
            "第一句。第二句。第三句。第四句。第五句。",
            maxBubbles = 3
        )
        assertEquals(3, parts.size)
        assertTrue(parts.last().contains("第三句"))
        assertTrue(parts.last().contains("第五句"))
    }

    @Test
    fun mergesVeryShortLeadingSentence() {
        val parts = ReplySplitter.split("嗯。我在。你慢慢说。")
        assertEquals(2, parts.size)
        assertEquals("嗯。我在。", parts[0])
    }

    @Test
    fun splitsParagraphsAndLiteralNewlines() {
        val parts = ReplySplitter.split("小鲸鱼，蓝白那条\n\n平时干饭为主\n被叫大肥鱼会否认")
        assertEquals(3, parts.size)
        assertEquals("小鲸鱼，蓝白那条", parts[0])
    }

    @Test
    fun blankReturnsEmpty() {
        assertTrue(ReplySplitter.split("   ").isEmpty())
    }
}
