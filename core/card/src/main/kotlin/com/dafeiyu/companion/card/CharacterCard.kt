package com.dafeiyu.companion.card

import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.ScheduleWindow
import kotlinx.serialization.json.JsonObject

data class CharacterCard(
    val spec: String?,
    val data: CardData,
    val raw: JsonObject
)

data class CardData(
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val exampleMessages: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val alternateGreetings: List<String>,
    val creatorNotes: String,
    val tags: List<String>,
    val creator: String,
    val characterVersion: String,
    val worldInfo: List<WorldInfoEntry>,
    val extensions: JsonObject,
    val companion: CompanionExtension?
)

data class WorldInfoEntry(
    val keys: List<String>,
    val content: String,
    val constant: Boolean
)

data class CompanionExtension(
    val version: Int = 1,
    val clinginess: Double? = null,
    val schedule: List<ScheduleWindow> = emptyList(),
    val triggers: List<CompanionTrigger> = emptyList(),
    val speech: SpeechConfig = SpeechConfig()
)

data class CompanionTrigger(
    val type: String,
    val match: String? = null,
    val month: Int? = null,
    val day: Int? = null,
    val offsetHours: Int? = null,
    val intent: String? = null
)

data class SpeechConfig(
    val maxChars: Int = 60,
    val maxSentences: Int = 2,
    val forbidden: List<String> = listOf("作为AI", "我是你的助手", "有什么可以帮你")
)

fun CharacterCard.toProfile(id: String = data.name): CharacterProfile {
    val speech = data.companion?.speech ?: SpeechConfig()
    return CharacterProfile(
        id = id,
        name = data.name,
        schedule = data.companion?.schedule ?: emptyList(),
        talkativeness = 0.5,
        defaultClinginess = data.companion?.clinginess ?: 0.5,
        maxChars = speech.maxChars,
        maxSentences = speech.maxSentences,
        forbiddenPhrases = speech.forbidden
    )
}

class CardParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
