package com.dafeiyu.companion.room

import com.dafeiyu.companion.card.CharacterCard

/**
 * 把单个角色卡放进群聊语境：人设 + 群成员 + 最近时间线，只让它写自己这一句。
 */
object RoomPromptBuilder {

    fun systemPrompt(
        card: CharacterCard,
        self: RoomSpeaker,
        roomName: String,
        members: List<RoomSpeaker>,
        transcript: List<RoomTurn>,
        replyToName: String? = null,
        mentioned: Boolean = false
    ): String {
        val data = card.data
        val others = members.filter { it.id != self.id }.joinToString("、") { it.name }
        val recent = transcript.takeLast(12).joinToString("\n") { turn ->
            val name = if (turn.isUser) "主人" else (turn.speakerName ?: "某人")
            "$name：${turn.text}"
        }.ifBlank { "（还没人说话）" }
        val hint = buildString {
            if (replyToName != null) appendLine("你正在回应「$replyToName」刚才那句。")
            if (mentioned) appendLine("刚才有人 @ 了你，你要接话。")
        }
        return """
            你叫「${self.name}」，现在在一个叫「$roomName」的多人群聊里，群里还有：$others。
            这是群聊，不是一对一私聊：你说的话群里所有人都能看到，别人也会插话。

            你的人设：
            ${data.description}

            性格：${data.personality}
            当前场景：${data.scenario}
            ${if (data.systemPrompt.isNotBlank()) "附加设定：${data.systemPrompt}" else ""}

            最近的群聊记录：
            $recent

            $hint
            只写「${self.name}」这一句要说的话：
            - 像真人在群里聊天，1–2 句，尽量不超过 60 字；
            - 可以回应别人、吐槽、接梗，但不要替别人发言，也不要一次写好几个人的对话；
            - 不要写“${self.name}：”这样的名字前缀；
            - 不要说自己是 AI、助手、程序，也不要解释规则；
            - 不要输出 Markdown、JSON 或括号里的动作说明。
            直接输出这句话本身。
        """.trimIndent()
    }

    /** 把模型输出清理成一条能直接显示的气泡。 */
    fun sanitize(raw: String, selfName: String, maxChars: Int = 120): String {
        var text = raw.trim()
        text = text.removePrefix("```").removeSuffix("```").trim()
        // 去掉模型自作主张加的名字前缀，例如“大肥鱼：”
        val prefixes = listOf("$selfName：", "$selfName:", "「$selfName」：", "**$selfName**：")
        prefixes.forEach { prefix -> if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim() }
        text = text.trim().trim('"').trim()
        if (text.length > maxChars) {
            text = text.take(maxChars).trimEnd() + "…"
        }
        return text
    }
}
