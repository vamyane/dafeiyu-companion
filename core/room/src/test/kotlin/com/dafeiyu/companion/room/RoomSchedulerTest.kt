package com.dafeiyu.companion.room

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSchedulerTest {

    private val alice = RoomSpeaker("alice", "小鲸鱼", talkativeness = 0.4, keywords = RoomScheduler.keywordsOf("海边 唱歌 撒娇"))
    private val bob = RoomSpeaker("bob", "大肥鱼", talkativeness = 0.8, keywords = RoomScheduler.keywordsOf("吃饭 摸鱼 吐槽"))
    private val carol = RoomSpeaker("carol", "阿团", talkativeness = 0.6, keywords = RoomScheduler.keywordsOf("睡觉 打滚"))

    private fun userTurn(text: String) = RoomTurn("user", null, null, text)
    private fun assistantTurn(speaker: RoomSpeaker, text: String) =
        RoomTurn("assistant", speaker.id, speaker.name, text)

    @Test
    fun `被 @ 的角色优先发言`() {
        val turns = listOf(userTurn("今天吃什么"), assistantTurn(bob, "吃鱼"))
        val scheduler = RoomScheduler(Random(42))
        val picked = scheduler.pickNext(
            members = listOf(alice, bob, carol),
            turns = turns,
            mentions = listOf("小鲸鱼")
        )
        assertEquals(alice, picked)
    }

    @Test
    fun `回复某角色时即使它在冷却也优先`() {
        val turns = listOf(
            assistantTurn(alice, "我先说"),
            assistantTurn(bob, "我也说"),
            assistantTurn(alice, "我再说")
        )
        val scheduler = RoomScheduler(Random(7))
        val picked = scheduler.pickNext(
            members = listOf(alice, bob, carol),
            turns = turns,
            replyTo = "小鲸鱼"
        )
        // alice 已连续 1 条（最后一条是 alice），仍可再发言
        assertEquals(alice, picked)
    }

    @Test
    fun `同一角色连续两条后必须换人`() {
        val turns = listOf(
            assistantTurn(bob, "第一句"),
            assistantTurn(bob, "第二句")
        )
        val scheduler = RoomScheduler(Random(11))
        val picked = scheduler.pickNext(
            members = listOf(alice, bob, carol),
            turns = turns,
            replyTo = "大肥鱼"
        )
        assertNotEquals(bob, picked)
        assertNotNull(picked)
    }

    @Test
    fun `连续两条达到上限后即使被 @ 也不再发言`() {
        val turns = listOf(
            assistantTurn(alice, "第一句"),
            assistantTurn(alice, "第二句")
        )
        val scheduler = RoomScheduler(Random(3))
        val picked = scheduler.pickNext(
            members = listOf(alice, bob),
            turns = turns,
            mentions = listOf("小鲸鱼")
        )
        // 只剩 bob 可发言；若 bob 也被限制则为 null
        assertEquals(bob, picked)
    }

    @Test
    fun `所有成员都在冷却时仍保证真人消息得到回应`() {
        val turns = listOf(userTurn("在吗"))
        val scheduler = RoomScheduler(Random(5))
        val picked = scheduler.ensureHumanGetsReply(
            members = listOf(alice, bob, carol),
            turns = turns
        )
        assertNotNull(picked)
    }

    @Test
    fun `没有成员时不发言`() {
        val scheduler = RoomScheduler(Random(9))
        assertNull(scheduler.pickNext(emptyList(), listOf(userTurn("你好"))))
    }

    @Test
    fun `相关度让同话题角色权重更高`() {
        val scheduler = RoomScheduler(Random(1))
        val fishScore = scheduler.relatedness("今晚去吃鱼", bob)
        val sleepScore = scheduler.relatedness("今晚去吃鱼", carol)
        assertTrue(fishScore >= sleepScore)
    }

    @Test
    fun `模型输出会去掉名字前缀并截断`() {
        val long = "大肥鱼：" + "今天这条鱼特别大。".repeat(20)
        val cleaned = RoomPromptBuilder.sanitize(long, "大肥鱼", maxChars = 60)
        assertTrue(!cleaned.startsWith("大肥鱼："))
        assertTrue(cleaned.length <= 61)
    }
}
