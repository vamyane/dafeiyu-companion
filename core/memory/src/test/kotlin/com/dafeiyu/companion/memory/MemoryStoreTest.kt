package com.dafeiyu.companion.memory

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryStoreTest {

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun recallRanksMatchingFactFirst() {
        val store = InMemoryMemoryStore()
        store.upsert(fact("1", "用户明天上午十点有面试", "用户", "有安排", "明天上午十点面试", 5, 0.8))
        store.upsert(fact("2", "用户喜欢喝冰美式", "用户", "喜欢", "冰美式", 3, 0.6))
        store.upsert(fact("3", "用户住在杭州", "用户", "住在", "杭州", 2, 0.5))

        val result = store.recall("companion", "面试", 3, now)
        assertEquals("1", result.first().entry.id)
        assertTrue(result.first().relevance > 0.0)
    }

    @Test
    fun contradictoryFactLowersOldEvidence() {
        val store = InMemoryMemoryStore()
        store.upsert(fact("1", "用户住在杭州", "用户", "住在", "杭州", 3, 0.8))
        store.upsert(fact("2", "用户住在上海", "用户", "住在", "上海", 3, 0.8))

        assertEquals(0.6, store.get("companion", "1")?.evidence ?: 0.0, 1e-9)
        assertEquals(0.8, store.get("companion", "2")?.evidence ?: 0.0, 1e-9)
    }

    @Test
    fun recentMessagesAreTrimmed() {
        val store = InMemoryMemoryStore()
        repeat(90) { index ->
            store.appendRecent(
                "companion",
                ChatMessage("user", "消息 $index", now.plusSeconds(index.toLong()))
            )
        }
        val recent = store.recent("companion", 40)
        assertEquals(40, recent.size)
        assertEquals("消息 89", recent.last().text)
    }

    @Test
    fun staleLowImportanceFactGetsArchived() {
        val store = InMemoryMemoryStore()
        val old = now.minusSeconds(400L * 24 * 3600)
        store.upsert(
            MemoryEntry(
                id = "old",
                companionId = "companion",
                kind = MemoryKind.FACT,
                text = "很久以前的小事",
                importance = 1,
                evidence = 0.2,
                createdAt = old,
                lastAccessedAt = old
            )
        )
        store.upsert(fact("fresh", "最近发生的事", "用户", "发生", "最近", 3, 0.5))

        assertEquals(1, store.archiveStale("companion", now))
        assertTrue(store.get("companion", "old")?.archived == true)
        assertFalse(store.recall("companion", "小事", 10, now).any { it.entry.id == "old" })
    }

    @Test
    fun exportContainsEntries() {
        val store = InMemoryMemoryStore()
        store.upsert(fact("1", "用户明天有面试", "用户", "有", "面试", 5, 0.8))
        val json = store.exportJson("companion")
        assertTrue(json.contains("companion"))
        assertTrue(json.contains("用户明天有面试"))
    }

    private fun fact(
        id: String,
        text: String,
        subject: String,
        predicate: String,
        obj: String,
        importance: Int,
        evidence: Double
    ) = MemoryEntry(
        id = id,
        companionId = "companion",
        kind = MemoryKind.FACT,
        text = text,
        subject = subject,
        predicate = predicate,
        obj = obj,
        importance = importance,
        confidence = 0.8,
        evidence = evidence,
        createdAt = now,
        lastAccessedAt = now
    )
}
