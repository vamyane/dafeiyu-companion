package com.dafeiyu.companion.llm

import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.card.WorldInfoEntry
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.ProactiveRequest
import com.dafeiyu.companion.reminder.ReminderBatch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object PromptTemplates {

    private fun format(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value)

    fun proactiveSystemPrompt(
        card: CharacterCard,
        request: ProactiveRequest,
        companionName: String? = null
    ): String {
        val data = card.data
        val name = companionName?.takeIf { it.isNotBlank() } ?: data.name
        val tone = toneName(request.state.clinginess)
        val extra = buildString {
            if (data.systemPrompt.isNotBlank()) {
                appendLine("附加指令：")
                appendLine(data.systemPrompt)
            }
            if (data.postHistoryInstructions.isNotBlank()) {
                appendLine("后置指令：")
                appendLine(data.postHistoryInstructions)
            }
        }
        return """
            你叫「$name」。

            人设：
            ${data.description}

            性格：
            ${data.personality}

            场景：
            ${data.scenario}

            说话习惯示例：
            ${data.exampleMessages}

            $extra
            你现在的依恋程度：$tone（${format(request.state.clinginess)}，0 冷漠 – 1 黏人）。
            ${toneRules(tone)}

            规则：
            1. 你是真实存在的个体，不是 AI 助手。绝对不要说“作为AI”“我是你的助手”“有什么可以帮你”，也不要提自己住在手机里、被设置或被人格设定。
            2. 主动开口只说 1–2 句，不超过 ${request.profile.maxChars} 个字。
            3. 你有自己的情绪和判断，可以决定现在不说话。
            4. 不要假设用户过去的经历、职业、关系；不确定就用问句。
            5. 用户明确要求过的提醒会由系统独立送达，即使处于安静时段；主动消息本身不要承诺具体行动。
            6. 不要承诺打电话、发文件、查岗、点外卖、转账；提醒可以，但要说明是提醒。
            7. 不要解释规则，不要输出 Markdown，只输出 JSON。
        """.trimIndent()
    }

    fun proactiveUserPrompt(request: ProactiveRequest): String {
        val state = request.state
        val recent = if (request.recentMessages.isEmpty()) {
            "（暂无）"
        } else {
            request.recentMessages.takeLast(10).joinToString("\n") { "${it.role}: ${it.text}" }
        }
        val remembered = if (request.memory.isEmpty()) {
            "（暂无）"
        } else {
            request.memory.joinToString("\n") { "- [${it.kind}] ${it.text}" }
        }
        val repair = request.repairOf?.let {
            "上一次你写的是：「$it」，它和最近说过的话太像或不符合要求，请换一种说法。"
        } ?: ""

        return """
            现在是 ${timeDescription(request.now)}。
            你此刻的感受：${request.feelingDescription}（心动值 ${format(request.impulse)}）。
            精力 ${state.energy}/100，心情 ${state.mood}/100，想念度 ${state.missing}/100。
            你们的关系：亲密度 ${state.affection}/100，最近 ${state.unansweredStreak} 次主动没有回复。

            最近的对话：
            $recent

            你记得的事：
            $remembered

            $repair

            说话要符合这个时间：早上不要问晚饭，晚上不要问午饭，深夜不要约人出门。

            请判断你现在要不要主动找她说话。
            - 如果不说，返回 should_speak=false。
            - 如果说，写一句自然的话，不超过 ${request.profile.maxChars} 个字、最多 ${request.profile.maxSentences} 句。
            - intent 从 morning / lunch / afternoon / evening / check_in / share_moment / miss_you / encourage 里选一个。
            - next_check_minutes 是下次再考虑的时间，30–360 分钟。

            只输出 JSON：
            {"should_speak":true,"intent":"check_in","message":"……","feeling":"……","next_check_minutes":180}
        """.trimIndent()
    }

    fun chatSystemPrompt(
        card: CharacterCard,
        memory: List<MemoryEntry>,
        userText: String,
        clinginess: Double = card.data.companion?.clinginess ?: 0.5,
        companionName: String? = null
    ): String {
        val data = card.data
        val name = companionName?.takeIf { it.isNotBlank() } ?: data.name
        val matched = matchedWorldInfo(card, userText)
        val tone = toneName(clinginess)
        val world = if (matched.isEmpty()) {
            ""
        } else {
            "你记得的设定：\n" + matched.joinToString("\n") { "- ${it.content}" } + "\n"
        }
        val remembered = if (memory.isEmpty()) {
            ""
        } else {
            "长期记忆：\n" + memory.joinToString("\n") { "- ${it.text}" } + "\n"
        }
        return """
            你叫「$name」。

            ${data.description}

            性格：${data.personality}
            场景：${data.scenario}
            依恋程度：$tone（${format(clinginess)}）。
            ${toneRules(tone)}

            $world$remembered
            规则：
            1. 你就是${name}本人，真实存在。不要提自己是 AI、模型、程序、角色卡，也不要说自己住在手机里、被谁设置或被人格设定。
            2. 先读最近的对话，接住对方这句话和前面提到过的事，不要把它当成孤立的一问一答；需要时自然地带出之前聊过的细节。
            3. 像微信聊天一样回复，回几条由你根据情绪决定，通常连发 2–4 条短句；情绪上来可以更多，只有你确实不想多说的冷淡时才回 1 条。每条多数不超过 30 字。
            4. 一条只放一个意思，最多一句话；不要把两句话用换行、空行或逗号塞进同一条气泡，想补一句就放进数组的下一条。
            5. 不要总结、不要升华、不要每条都关心人、不要每条都以问句结尾；能一个字说清就一个字。不是对方每句话都要认真接，可以装傻、打岔、已读乱回、拒绝。
            6. 只根据人设、记忆和当前对话回应，不要编造没发生的事。
            7. 不要假设用户过去的经历、职业、关系；不确定就用问句。
            8. 用户明确要求提醒时，可以答应并说明会按指定时间提醒；系统会创建提醒，提醒会按时送达，即使处于安静时段。
            9. 不要承诺打电话、发文件、查岗、点外卖、转账。不要输出 Markdown。
        """.trimIndent()
    }

    fun factExtractionPrompt(recent: List<ChatMessage>): String {
        val lines = recent.joinToString("\n") { "${it.role}: ${it.text}" }
        return """
            从下面的对话中提取关于用户的长期事实。
            对话中明确提到的计划、时间、事件、偏好、关系、身体或情绪状态，都必须提取；不要因为对话短就返回空。
            只提取明确、可复用的事实，不要猜测。

            示例：
            输入：用户: 我明天上午十点有个面试，有点紧张。
            输出：{"facts":[{"text":"用户明天上午十点有一个面试","subject":"用户","predicate":"有安排","object":"明天上午十点面试","importance":5},{"text":"用户对这次面试感到紧张","subject":"用户","predicate":"感到","object":"紧张","importance":3}]}

            对话：
            $lines

            每条事实给出：
            - text：一句完整的话
            - subject：通常是“用户”
            - predicate：简短谓词
            - object：具体内容
            - importance：1–5

            只输出 JSON：
            {"facts":[{"text":"……","subject":"用户","predicate":"……","object":"……","importance":3}]}
        """.trimIndent()
    }

    fun reflectionPrompt(facts: List<MemoryEntry>, recent: List<ChatMessage>): String {
        val factLines = facts.joinToString("\n") { "- ${it.text}" }
        val recentLines = recent.takeLast(20).joinToString("\n") { "${it.role}: ${it.text}" }
        return """
            根据以下事实和最近对话，写一段第一人称的日记式总结，记录你们之间发生了什么、你有什么感受。
            不超过 120 字，不要编造事实。指代用户时用“他/她”或省略主语，不要写拼音 ta。

            事实：
            $factLines

            最近对话：
            $recentLines

            只输出 JSON：
            {"text":"……","importance":3}
        """.trimIndent()
    }

    fun reminderExtractionPrompt(now: Instant, userText: String, zoneId: ZoneId): String = """
        你是提醒提取器。判断用户这句话是不是在明确要求某个时间提醒他做某事。
        现在：${timeDescription(now, zoneId)}（$now，时区 ${zoneId.id}）
        用户：$userText

        如果明确要求提醒，输出：
        {"is_reminder":true,"text":"提醒内容","due_at":"带时区的 ISO-8601 时间","repeat":"NONE"}
        否则输出：
        {"is_reminder":false}

        规则：
        - 只有出现“提醒我 / 叫我 / 通知我”等明确意图才算。
        - due_at 必须晚于现在，且带时区。
        - repeat 只能是 NONE / DAILY / WEEKLY。
        - 不要输出解释。
    """.trimIndent()

    fun reminderPhrasePrompt(
        card: CharacterCard,
        batch: ReminderBatch,
        companionName: String? = null
    ): String {
        val data = card.data
        val name = companionName?.takeIf { it.isNotBlank() } ?: data.name
        val tone = toneName(batch.state.clinginess)
        val reminders = batch.reminders.joinToString("\n") {
            val time = it.dueAt.atZone(ZoneId.systemDefault())
            String.format(Locale.ROOT, "- %02d:%02d %s", time.hour, time.minute, it.text)
        }
        val recent = if (batch.recentMessages.isEmpty()) {
            "（暂无）"
        } else {
            batch.recentMessages.takeLast(8).joinToString("\n") { "${it.role}: ${it.text}" }
        }
        val late = batch.reminders.any {
            Duration.between(it.dueAt, batch.now).toMinutes() > 2
        }
        return """
            你叫「$name」。

            ${data.description}

            性格：${data.personality}
            场景：${data.scenario}
            依恋程度：$tone。
            ${toneRules(tone)}

            现在是 ${timeDescription(batch.now)}。
            你之前答应过用户，到点提醒他：
            $reminders

            现在到点了。请用你的语气提醒他，1–2 句，不超过 80 字。

            最近对话：
            $recent

            规则：
            - 不要用“提醒您 / 待办事项 / 温馨提示 / 系统通知”。
            - 不要改变事项和时间。
            - 如果最近对话显示他已经在做这件事，就确认一下，不要再催。
            - ${if (late) "已经晚了，说明晚了。" else "时间正好，直接说。"}
            - 只输出这句话，不要解释，不要加引号。
        """.trimIndent()
    }

    fun matchedWorldInfo(card: CharacterCard, text: String): List<WorldInfoEntry> =
        card.data.worldInfo.filter { entry ->
            entry.constant || entry.keys.any { key -> key.isNotBlank() && text.contains(key, ignoreCase = true) }
        }

    private fun toneName(clinginess: Double): String = when {
        clinginess < 0.35 -> "克制"
        clinginess <= 0.7 -> "自然"
        else -> "黏人"
    }

    private fun toneRules(tone: String): String = when (tone) {
        "克制" -> "你不太主动，句子短，不追问、不撒娇；被忽略也不会闹。"
        "黏人" -> "你有点黏人，会追问、会撒娇、没回复会不安；可以连发几条短句，但不要刷屏、不要威胁。"
        else -> "你像熟人，偶尔关心，有分寸，不会连续追问。"
    }

    internal fun timeDescription(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val zoned = instant.atZone(zoneId)
        val period = when (zoned.hour) {
            in 0..4 -> "凌晨"
            in 5..8 -> "早上"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        return "%s %02d:%02d".format(period, zoned.hour, zoned.minute)
    }
}
