package com.dafeiyu.companion.llm

import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.ProactiveModel
import com.dafeiyu.companion.model.ProactiveRequest
import com.dafeiyu.companion.model.ProactiveResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeepSeekProactiveModel(
    private val client: DeepSeekClient,
    private val card: CharacterCard,
    private val companionName: String? = null
) : ProactiveModel {

    override fun decide(request: ProactiveRequest): ProactiveResponse {
        val messages = listOf(
            ChatMessage(
                role = "system",
                text = PromptTemplates.proactiveSystemPrompt(card, request, companionName),
                at = request.now
            ),
            ChatMessage(
                role = "user",
                text = PromptTemplates.proactiveUserPrompt(request),
                at = request.now
            )
        )
        val content = client.chat(
            messages = messages,
            jsonMode = true,
            temperature = 0.9,
            maxTokens = 400
        )
        val root = Json.parseToJsonElement(content).jsonObject
        return ProactiveResponse(
            shouldSpeak = root["should_speak"]?.jsonPrimitive?.booleanOrNull ?: false,
            intent = root["intent"]?.jsonPrimitive?.contentOrNull ?: "",
            message = root["message"]?.jsonPrimitive?.contentOrNull ?: "",
            feeling = root["feeling"]?.jsonPrimitive?.contentOrNull ?: "",
            nextCheckMinutes = root["next_check_minutes"]?.jsonPrimitive?.intOrNull,
            reason = root["reason"]?.jsonPrimitive?.contentOrNull ?: "",
            tone = ""
        )
    }
}
