package com.dafeiyu.companion.memory

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

data class ScoredMemory(
    val entry: MemoryEntry,
    val score: Double,
    val relevance: Double,
    val recency: Double,
    val importance: Double,
    val evidence: Double
)

interface MemoryStore {
    fun appendRecent(companionId: String, message: ChatMessage)
    fun recent(companionId: String, limit: Int = 40): List<ChatMessage>
    fun upsert(entry: MemoryEntry)
    fun get(companionId: String, id: String): MemoryEntry?
    fun recall(companionId: String, query: String, limit: Int, now: Instant): List<ScoredMemory>
    fun all(companionId: String): List<MemoryEntry>
    fun delete(companionId: String, id: String): Boolean
    fun purge(companionId: String)
    fun exportJson(companionId: String): String
    fun archiveStale(
        companionId: String,
        now: Instant,
        staleDays: Long = 365,
        minImportance: Int = 1
    ): Int
}

class InMemoryMemoryStore : MemoryStore {

    private val recentMap = mutableMapOf<String, MutableList<ChatMessage>>()
    private val entryMap = mutableMapOf<String, MutableMap<String, MemoryEntry>>()

    override fun appendRecent(companionId: String, message: ChatMessage) {
        val list = recentMap.getOrPut(companionId) { mutableListOf() }
        list += message
        if (list.size > 80) {
            repeat(list.size - 80) { list.removeAt(0) }
        }
    }

    override fun recent(companionId: String, limit: Int): List<ChatMessage> =
        recentMap[companionId]?.takeLast(limit) ?: emptyList()

    override fun upsert(entry: MemoryEntry) {
        val map = entryMap.getOrPut(entry.companionId) { mutableMapOf() }
        if (entry.kind == MemoryKind.FACT) {
            val conflict = map.values.firstOrNull {
                it.kind == MemoryKind.FACT &&
                    !it.archived &&
                    it.subject != null &&
                    entry.subject != null &&
                    it.subject == entry.subject &&
                    it.predicate == entry.predicate &&
                    it.obj != entry.obj
            }
            if (conflict != null) {
                map[conflict.id] = conflict.copy(
                    evidence = (conflict.evidence - 0.2).coerceAtLeast(0.0)
                )
            }
        }
        map[entry.id] = entry
    }

    override fun get(companionId: String, id: String): MemoryEntry? =
        entryMap[companionId]?.get(id)

    override fun recall(
        companionId: String,
        query: String,
        limit: Int,
        now: Instant
    ): List<ScoredMemory> {
        val candidates = entryMap[companionId]?.values
            ?.filter { !it.archived && it.kind != MemoryKind.RECENT }
            ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()

        val docs = candidates.map { Tokenizer.tokenize(it.text) }
        val bm25 = Bm25(docs)
        val queryTokens = Tokenizer.tokenize(query)
        val rawScores = candidates.indices.map { bm25.score(queryTokens, it) }
        val maxRaw = rawScores.maxOrNull() ?: 0.0

        return candidates.mapIndexed { index, entry ->
            val relevance = if (maxRaw > 0.0) rawScores[index] / maxRaw else 0.0
            val days = Duration.between(entry.createdAt, now).toMinutes()
                .coerceAtLeast(0) / 1440.0
            val recency = exp(-days / 30.0)
            val importance = entry.importance.coerceIn(0, 5) / 5.0
            val evidence = entry.evidence.coerceIn(0.0, 1.0)
            ScoredMemory(
                entry = entry,
                score = 0.45 * relevance + 0.25 * recency + 0.20 * importance + 0.10 * evidence,
                relevance = relevance,
                recency = recency,
                importance = importance,
                evidence = evidence
            )
        }.sortedByDescending { it.score }.take(limit).also { result ->
            val map = entryMap[companionId] ?: return@also
            result.forEach { scored ->
                if (Duration.between(scored.entry.lastAccessedAt, now).toHours() >= 1) {
                    map[scored.entry.id] = scored.entry.copy(lastAccessedAt = now)
                }
            }
        }
    }

    override fun all(companionId: String): List<MemoryEntry> =
        entryMap[companionId]?.values?.toList() ?: emptyList()

    override fun delete(companionId: String, id: String): Boolean =
        entryMap[companionId]?.remove(id) != null

    override fun purge(companionId: String) {
        recentMap.remove(companionId)
        entryMap.remove(companionId)
    }

    override fun exportJson(companionId: String): String = buildJsonObject {
        put("companionId", companionId)
        put("entries", buildJsonArray {
            for (entry in all(companionId)) {
                add(buildJsonObject {
                    put("id", entry.id)
                    put("kind", entry.kind.name)
                    put("text", entry.text)
                    put("subject", entry.subject)
                    put("predicate", entry.predicate)
                    put("object", entry.obj)
                    put("importance", entry.importance)
                    put("confidence", entry.confidence)
                    put("evidence", entry.evidence)
                    put("createdAt", entry.createdAt.toString())
                    put("lastAccessedAt", entry.lastAccessedAt.toString())
                    put("archived", entry.archived)
                })
            }
        })
    }.toString()

    override fun archiveStale(
        companionId: String,
        now: Instant,
        staleDays: Long,
        minImportance: Int
    ): Int {
        val map = entryMap[companionId] ?: return 0
        var count = 0
        for ((id, entry) in map) {
            if (entry.archived) continue
            val days = Duration.between(entry.lastAccessedAt, now).toDays()
            if (days >= staleDays && entry.importance <= minImportance) {
                map[id] = entry.copy(archived = true)
                count++
            }
        }
        return count
    }
}
