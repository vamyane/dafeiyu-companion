package com.dafeiyu.companion.llm

import com.dafeiyu.companion.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

/**
 * 用户发来图片后，让模型自己判断这张图值不值得收进表情库，并给一个简短备注。
 */
class DeepSeekStickerJudge(private val client: DeepSeekClient) {

    data class Decision(
        val collect: Boolean,
        val label: String
    )

    fun judge(image: File, now: Instant): Decision? {
        if (!image.exists()) return null
        val system = """
            你在看对方刚发来的一张图。判断它适不适合当成以后聊天可以用的表情包：
            - 有明确情绪、好笑、能接梗、适合吐槽或撒娇的图，值得收。
            - 普通照片、截图、聊天记录、文字图、二维码、风景、没有情绪信息的图，不收。
            - 同一张图不要重复收。
            只输出 JSON：{"collect":true,"label":"12 个字内的中文备注"}
            备注要说明这张图表达什么情绪或梗，方便以后在合适的时候发出去。
        """.trimIndent()
        val raw = runCatching {
            client.chat(
                messages = listOf(
                    ChatMessage("system", system, now),
                    ChatMessage("user", "这张图有意思吗？", now, image.absolutePath)
                ),
                jsonMode = true,
                temperature = 0.4,
                maxTokens = 160,
                includeImageInLastMessage = true
            )
        }.getOrNull() ?: return null
        val root = runCatching {
            Json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return null
        val collect = root["collect"]?.jsonPrimitive?.booleanOrNull ?: false
        val label = root["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return Decision(collect, label.ifBlank { "对方收藏的表情" })
    }
}
