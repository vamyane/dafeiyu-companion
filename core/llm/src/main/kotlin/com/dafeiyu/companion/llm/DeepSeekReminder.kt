package com.dafeiyu.companion.llm

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.ReminderRepeat
import com.dafeiyu.companion.reminder.ReminderBatch
import com.dafeiyu.companion.reminder.ReminderDraft
import com.dafeiyu.companion.reminder.ReminderExtractor
import com.dafeiyu.companion.reminder.ReminderFallback
import com.dafeiyu.companion.reminder.ReminderPhraser
import com.dafeiyu.companion.card.CharacterCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DeepSeekReminderExtractor(
    private val client: DeepSeekClient,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ReminderExtractor {

    override fun extract(companionId: String, userText: String, now: Instant): ReminderDraft? {
        val content = client.chat(
            messages = listOf(
                ChatMessage("system", "你是提醒提取器，只输出 JSON。", now),
                ChatMessage("user", PromptTemplates.reminderExtractionPrompt(now, userText, zoneId), now)
            ),
            jsonMode = true,
            temperature = 0.0,
            maxTokens = 300
        )
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        if (root["is_reminder"]?.jsonPrimitive?.booleanOrNull != true) return null

        val text = root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val dueRaw = root["due_at"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank() || dueRaw.isBlank()) return null
        val dueAt = parseDue(dueRaw) ?: return null
        if (!dueAt.isAfter(now)) return null

        val repeat = when (root["repeat"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
            "DAILY" -> ReminderRepeat.DAILY
            "WEEKLY" -> ReminderRepeat.WEEKLY
            else -> ReminderRepeat.NONE
        }
        return ReminderDraft(text = text, dueAt = dueAt, repeat = repeat)
    }

    private fun parseDue(raw: String): Instant? =
        runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(zoneId).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atTime(9, 0).atZone(zoneId).toInstant() }.getOrNull()
}

class DeepSeekReminderPhraser(
    private val client: DeepSeekClient,
    private val card: CharacterCard,
    private val companionName: String? = null
) : ReminderPhraser {

    override fun phrase(batch: ReminderBatch): String? {
        val content = client.chat(
            messages = listOf(
                ChatMessage(
                    role = "system",
                    text = PromptTemplates.reminderPhrasePrompt(card, batch, companionName),
                    at = batch.now
                ),
                ChatMessage("user", "到点了。", batch.now)
            ),
            jsonMode = false,
            temperature = 0.8,
            maxTokens = 200
        ).trim().removeSurrounding("\"").trim()

        return content.takeIf { ReminderFallback.isAcceptable(it) }
    }
}
