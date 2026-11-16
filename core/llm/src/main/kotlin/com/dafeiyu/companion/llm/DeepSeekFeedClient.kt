package com.dafeiyu.companion.llm

import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.CompanionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

data class FeedDecision(
    val accept: Boolean,
    val message: String
)

class DeepSeekFeedClient(
    private val client: DeepSeekClient,
    private val card: CharacterCard
) {

    fun decide(
        state: CompanionState,
        recent: List<ChatMessage>,
        now: Instant
    ): FeedDecision {
        val system = """
            你是「${card.data.name}」。

            ${card.data.description}
            性格：${card.data.personality}
            场景：${card.data.scenario}

            你现在的状态：心情 ${state.mood}，精力 ${state.energy}，想念 ${state.missing}，
            好感 ${state.affection}，联系模式 ${state.contactMode}，当前活动 ${state.activity}。

            用户递给你一条小鱼干。吃不吃、怎么回应，由你自己根据此刻的情绪和你们的关系决定。
            不要套固定台词，不要每次都一样；可以吃，也可以拒绝，拒绝也不需要解释太多。
            只输出 JSON：
            {"accept": true/false, "message": "1–2 句角色化的回应"}
            不要解释规则，不要客服腔。
        """.trimIndent()

        val history = recent.takeLast(8).joinToString("\n") { "${it.role}: ${it.text}" }
        val user = "最近对话：\n$history\n\n他递过来一条小鱼干。"
        val content = client.chat(
            messages = listOf(
                ChatMessage("system", system, now),
                ChatMessage("user", user, now)
            ),
            jsonMode = true,
            temperature = 1.0,
            maxTokens = 200
        )
        val root = runCatching { Json.parseToJsonElement(content).jsonObject }
            .getOrElse { throw DeepSeekException(-1, "投喂回应解析失败", it) }
        val accept = root["accept"]?.jsonPrimitive?.booleanOrNull ?: false
        val message = root["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (message.isBlank()) throw DeepSeekException(-1, "投喂回应为空")
        return FeedDecision(accept, message)
    }
}
