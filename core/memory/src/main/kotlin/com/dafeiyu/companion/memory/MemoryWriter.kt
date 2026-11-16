package com.dafeiyu.companion.memory

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind

interface FactExtractor {
    fun extract(companionId: String, recent: List<ChatMessage>): List<MemoryEntry>
}

interface ReflectionGenerator {
    fun reflect(
        companionId: String,
        dayKey: String,
        facts: List<MemoryEntry>,
        recent: List<ChatMessage>
    ): MemoryEntry?
}

class MemoryWriter(
    private val store: MemoryStore,
    private val factExtractor: FactExtractor,
    private val reflectionGenerator: ReflectionGenerator
) {

    fun record(companionId: String, message: ChatMessage) {
        store.appendRecent(companionId, message)
    }

    fun endSession(companionId: String): List<MemoryEntry> {
        val recent = store.recent(companionId, 40)
        if (recent.isEmpty()) return emptyList()
        val facts = factExtractor.extract(companionId, recent)
        facts.forEach(store::upsert)
        return facts
    }

    fun reflect(companionId: String, dayKey: String): MemoryEntry? {
        val facts = store.all(companionId)
            .filter { it.kind == MemoryKind.FACT && !it.archived }
        val recent = store.recent(companionId, 40)
        val reflection = reflectionGenerator.reflect(companionId, dayKey, facts, recent)
            ?: return null
        store.upsert(reflection)
        return reflection
    }
}
