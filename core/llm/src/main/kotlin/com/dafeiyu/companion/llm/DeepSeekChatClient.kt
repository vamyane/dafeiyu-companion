package com.dafeiyu.companion.llm

import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeepSeekChatClient(
    private val client: DeepSeekClient,
    private val card: CharacterCard
) {

    data class ReplyBubble(
        val text: String,
        val voice: Boolean = false,
        val sticker: String? = null
    )

    data class ReplyPlan(
        val bubbles: List<ReplyBubble>,
        val voiceWillingness: Double
    )

    fun reply(
        history: List<ChatMessage>,
        memory: List<MemoryEntry>,
        userMessage: ChatMessage,
        clinginess: Double = card.data.companion?.clinginess ?: 0.5,
        extraInstruction: String? = null,
        companionName: String? = null
    ): String {
        val base = PromptTemplates.chatSystemPrompt(
            card,
            memory,
            userMessage.text,
            clinginess,
            companionName
        )
        val system = if (extraInstruction.isNullOrBlank()) {
            base
        } else {
            base + "\n\n本轮回复方式（优先遵守）：\n" + extraInstruction
        }
        val messages = buildList {
            add(ChatMessage("system", system, userMessage.at))
            addAll(history.takeLast(30))
            add(userMessage)
        }
        return client.chat(
            messages = messages,
            jsonMode = false,
            temperature = 0.9,
            maxTokens = 600,
            includeImageInLastMessage = userMessage.imagePath != null
        )
    }

    /**
     * 让模型自己决定这一轮回几条、什么语气、哪几条用语音或表情，只保留 10 条上限。
     */
    fun replyBubbles(
        history: List<ChatMessage>,
        memory: List<MemoryEntry>,
        userMessage: ChatMessage,
        clinginess: Double,
        stateBrief: String,
        companionName: String? = null,
        voiceEnabled: Boolean = true,
        stickersEnabled: Boolean = false,
        stickers: List<Pair<String, String>> = emptyList(),
        maxBubbles: Int = 10
    ): ReplyPlan {
        val userSentVoice = userMessage.voicePath != null || userMessage.voice
        val base = PromptTemplates.chatSystemPrompt(
            card,
            memory,
            userMessage.text,
            clinginess,
            companionName
        )
        val voiceRules = if (voiceEnabled) {
            """
            语音：可以用 voice=true 让这条以语音条发出去。
            - 基础概率：对方发语音时 99% 会考虑用语音；对方发文字时只有 10%；对方明确要求语音时 100%。
            - 这些只是基础概率，最终还要叠加你这一轮自己的意愿，所以不是每次都一定发。
            - 对方明确要求你发语音时，voice_willingness 至少给 0.9。
            - 对方发语音时，通常给 0.7–1.0；对方发文字时，通常给 0–0.4，除非你特别想用声音说。
            - voice_willingness 是你这轮想用语音的意愿，0 到 1，不是最终概率。
            """.trimIndent()
        } else {
            "语音：这次关掉了语音，所有消息的 voice 必须为 false，voice_willingness 为 0。"
        }
        val stickerRules = if (stickersEnabled && stickers.isNotEmpty()) {
            """
            表情：可以在某一条里带 "sticker" 发一张表情，这时 text 可以留空或只配一句很短的话。发不发、发哪张由你决定。
            一般聊 3–5 轮发一张就够；对方连着发表情时可以跟着斗两三轮；不要每轮都发，也不要一次连发多张。
            可用表情：${stickers.joinToString("；") { (id, label) -> "$id=$label" }}
            """.trimIndent()
        } else {
            "表情：这次没有可用表情，sticker 留空。"
        }
        val system = """
            $base

            $stateBrief

            这一轮回几条、是冷淡、简短、话多还是撒娇，由你自己根据状态和对方这句话决定，
            不要固定套模板。对方这一条发的是${if (userSentVoice) "语音" else "文字"}。
            $voiceRules
            $stickerRules

            只输出 JSON：
            {"messages": [{"text": "消息1", "voice": false, "sticker": ""}, {"text": "", "voice": false, "sticker": "happy"}], "voice_willingness": 0.7, "feeling": "你此刻的感受"}
            messages 为 1–$maxBubbles 条，每条尽量短，像微信连发；不想多说就少说。
            不要解释规则，不要输出 Markdown。
        """.trimIndent()
        val messages = buildList {
            add(ChatMessage("system", system, userMessage.at))
            addAll(history.takeLast(30))
            add(userMessage)
        }
        val raw = runCatching {
            client.chat(
                messages = messages,
                jsonMode = true,
                temperature = 0.95,
                maxTokens = 1200,
                includeImageInLastMessage = userMessage.imagePath != null
            )
        }.getOrElse {
            val plain = reply(
                history,
                memory,
                userMessage,
                clinginess,
                companionName = companionName
            )
            return ReplyPlan(
                splitBubble(ReplyBubble(plain.trim())).filter { it.text.isNotEmpty() },
                DEFAULT_WILLINGNESS
            )
        }
        val root = runCatching {
            Json.parseToJsonElement(cleanJson(raw)).jsonObject
        }.getOrNull()
        val willingness = root?.get("voice_willingness")
            ?.jsonPrimitive
            ?.doubleOrNull
            ?.coerceIn(0.0, 1.0)
            ?: DEFAULT_WILLINGNESS
        val parsed = runCatching {
            root?.get("messages")
                ?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element as? kotlinx.serialization.json.JsonObject
                    if (obj != null) {
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val sticker = obj["sticker"]?.jsonPrimitive?.contentOrNull
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        if (text.isEmpty() && sticker == null) return@mapNotNull null
                        ReplyBubble(
                            text = text,
                            voice = voiceEnabled &&
                                obj["voice"]?.jsonPrimitive?.contentOrNull == "true",
                            sticker = sticker
                        )
                    } else {
                        val text = element.jsonPrimitive.contentOrNull?.trim().orEmpty()
                        if (text.isEmpty()) null else ReplyBubble(text)
                    }
                }
                ?.take(maxBubbles)
                .orEmpty()
        }.getOrDefault(emptyList())
        val expanded = parsed.flatMap { splitBubble(it) }.take(maxBubbles)
        if (expanded.isNotEmpty()) {
            return ReplyPlan(expanded, if (voiceEnabled) willingness else 0.0)
        }
        // 模型没按 JSON 返回时，也照样按句子拆开，不能把一大段塞进一个气泡。
        val fallback = splitBubble(ReplyBubble(fallbackText(raw)))
            .filter { it.text.isNotEmpty() }
            .take(maxBubbles)
        return ReplyPlan(fallback, if (voiceEnabled) willingness else 0.0)
    }

    /**
     * 兜底时不要直接把 JSON 原文丢给用户；尽量取出已经生成的中文文本。
     */
    private fun fallbackText(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("\"messages\"")) return trimmed
        val cleaned = cleanJson(trimmed)
        val matches = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return matches.joinToString("\n").ifBlank { "……" }
    }

    /**
     * 容忍模型把 JSON 包在 ```json 围栏里，或前面加一句“好的：”。
     */
    private fun cleanJson(raw: String): String {
        val withoutFence = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            withoutFence.substring(start, end + 1)
        } else {
            withoutFence
        }
    }

    /**
     * 一条气泡只放一个意思：模型偶尔会把两句话塞进同一条，这里按换行和句号/问号/叹号拆开。
     */
    private fun splitBubble(bubble: ReplyBubble): List<ReplyBubble> {
        var voice = bubble.voice
        var text = bubble.text
        if (VoiceMarkers.contains(text)) {
            voice = true
            text = VoiceMarkers.strip(text).ifBlank { "……" }
        }
        if (bubble.sticker != null || voice) {
            return listOf(bubble.copy(text = text, voice = voice))
        }
        val parts = ReplySplitter.split(text, maxBubbles = 10)
        if (parts.size <= 1) return listOf(bubble.copy(text = text))
        return parts.map { bubble.copy(text = it) }
    }

    private companion object {
        const val DEFAULT_WILLINGNESS = 0.7
    }
}
