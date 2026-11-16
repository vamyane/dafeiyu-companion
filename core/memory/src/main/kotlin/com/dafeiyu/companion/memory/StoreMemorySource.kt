package com.dafeiyu.companion.memory

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.Clock
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemorySource

class StoreMemorySource(
    private val store: MemoryStore,
    private val clock: Clock
) : MemorySource {

    override fun recall(companionId: String, query: String, limit: Int): List<MemoryEntry> =
        store.recall(companionId, query, limit, clock.now()).map { it.entry }

    override fun recent(companionId: String, limit: Int): List<ChatMessage> =
        store.recent(companionId, limit)
}
