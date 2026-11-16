package com.dafeiyu.companion.llm

import com.dafeiyu.companion.memory.FactExtractor
import com.dafeiyu.companion.memory.ReflectionGenerator
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.Clock
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class DeepSeekFactExtractor(
    private val client: DeepSeekClient,
    private val clock: Clock
) : FactExtractor {

    override fun extract(companionId: String, recent: List<ChatMessage>): List<MemoryEntry> {
        if (recent.isEmpty()) return emptyList()
        val now = clock.now()
        val content = client.chat(
            messages = listOf(
                ChatMessage("system", "你是记忆提取器，只输出 JSON。", now),
                ChatMessage("user", PromptTemplates.factExtractionPrompt(recent), now)
            ),
            jsonMode = true,
            temperature = 0.0,
            maxTokens = 700
        )
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }
            .getOrNull() ?: return emptyList()
        val facts = root["facts"]?.jsonArray ?: return emptyList()

        return facts.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            MemoryEntry(
                id = UUID.randomUUID().toString(),
                companionId = companionId,
                kind = MemoryKind.FACT,
                text = text,
                subject = obj["subject"]?.jsonPrimitive?.contentOrNull,
                predicate = obj["predicate"]?.jsonPrimitive?.contentOrNull,
                obj = obj["object"]?.jsonPrimitive?.contentOrNull,
                importance = (obj["importance"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 5),
                confidence = 0.7,
                evidence = 0.5,
                createdAt = now,
                lastAccessedAt = now,
                sourceMessageIds = recent.map { it.at.toString() }
            )
        }
    }
}

class DeepSeekReflectionGenerator(
    private val client: DeepSeekClient,
    private val clock: Clock
) : ReflectionGenerator {

    override fun reflect(
        companionId: String,
        dayKey: String,
        facts: List<MemoryEntry>,
        recent: List<ChatMessage>
    ): MemoryEntry? {
        if (facts.isEmpty() && recent.isEmpty()) return null
        val now = clock.now()
        val content = client.chat(
            messages = listOf(
                ChatMessage("system", "你负责写第一人称日记式总结，只输出 JSON。", now),
                ChatMessage("user", PromptTemplates.reflectionPrompt(facts, recent), now)
            ),
            jsonMode = true,
            temperature = 0.5,
            maxTokens = 400
        )
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }
            .getOrNull() ?: return null
        val text = root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return null

        return MemoryEntry(
            id = UUID.randomUUID().toString(),
            companionId = companionId,
            kind = MemoryKind.REFLECTION,
            text = text,
            importance = (root["importance"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 5),
            confidence = 0.6,
            evidence = 0.5,
            createdAt = now,
            lastAccessedAt = now,
            sourceMessageIds = facts.map { it.id }
        )
    }
}
