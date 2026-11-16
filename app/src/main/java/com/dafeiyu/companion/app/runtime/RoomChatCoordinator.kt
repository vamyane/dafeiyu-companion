package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.app.AppGraph
import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.room.RoomPromptBuilder
import com.dafeiyu.companion.room.RoomScheduler
import com.dafeiyu.companion.room.RoomSpeaker
import com.dafeiyu.companion.room.RoomTurn
import kotlinx.coroutines.delay
import kotlin.random.Random

data class RoomReply(
    val speakerId: String,
    val speakerName: String,
    val text: String,
    val at: Long
)

data class RoomSendResult(
    val replies: List<RoomReply>,
    val error: String?
)

/**
 * 群聊运行时：写消息 → 调度发言者 → 用角色卡生成这一句 → 落库并回调给界面。
 * 调度规则在 core:room 里，这里只负责模型调用与持久化。
 */
class RoomChatCoordinator(private val graph: AppGraph) {

    private val scheduler = RoomScheduler(Random.Default)
    private val random = Random.Default

    suspend fun send(
        roomId: Long,
        text: String,
        replyTo: String? = null,
        maxReplies: Int = 3,
        onTyping: (String?) -> Unit = {},
        onReply: (RoomReply) -> Unit = {}
    ): RoomSendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RoomSendResult(emptyList(), "消息不能为空")
        val room = graph.rooms.room(roomId) ?: return RoomSendResult(emptyList(), "房间不存在")
        val memberRecords = graph.rooms.members(roomId)
        if (memberRecords.isEmpty()) return RoomSendResult(emptyList(), "房间里还没有角色")

        val cards = memberRecords.associate { record ->
            val card = graph.cards.loadCard(record.cardPath) ?: fallbackCard(record.displayName)
            record.characterId to card
        }
        val speakers = memberRecords.map { record ->
            val card = cards[record.characterId]
            RoomSpeaker(
                id = record.characterId,
                name = record.displayName,
                talkativeness = record.talkativeness,
                keywords = card?.let { cardKeywords(it) } ?: emptySet()
            )
        }
        val nameById = memberRecords.associate { it.characterId to it.displayName }

        fun currentTurns(): List<RoomTurn> = graph.rooms.messages(roomId, 30).map { message ->
            RoomTurn(
                role = message.role,
                speakerId = message.characterId,
                speakerName = message.characterId?.let { nameById[it] } ?: message.characterId,
                text = message.text
            )
        }

        val now = graph.clock.now().toEpochMilli()
        graph.rooms.appendMessage(roomId, null, "user", trimmed, now, replyTo)

        if (NO_REPLY_CUES.any { trimmed.contains(it) }) {
            return RoomSendResult(emptyList(), null)
        }

        val client = graph.deepSeekClientOrNull()
            ?: return RoomSendResult(emptyList(), "还没有配置 API Key")

        val mentions = parseMentions(trimmed, memberRecords.map { it.displayName })
        val replies = mutableListOf<RoomReply>()

        repeat(maxReplies) { index ->
            val turns = currentTurns()
            val speaker = if (index == 0) {
                scheduler.ensureHumanGetsReply(speakers, turns, mentions, replyTo)
            } else {
                scheduler.pickNext(speakers, turns, mentions, replyTo)
            }
            if (speaker == null) return@repeat

            onTyping(speaker.name)
            delay(random.nextLong(420, 900))

            val card = cards[speaker.id] ?: fallbackCard(speaker.name)
            val mentioned = mentions.any { it == speaker.name || it == speaker.id }
            val system = RoomPromptBuilder.systemPrompt(
                card = card,
                self = speaker,
                roomName = room.name,
                members = speakers,
                transcript = turns,
                replyToName = replyTo?.let { nameById[it] ?: it },
                mentioned = mentioned
            )
            val prompt = listOf(
                ChatMessage("system", system, graph.clock.now()),
                ChatMessage("user", "（群里刚聊到这里，现在轮到你说话。）", graph.clock.now())
            )
            val raw = runCatching {
                client.chat(prompt, jsonMode = false, temperature = 0.92, maxTokens = 220)
            }
            val replyText = raw.getOrNull()?.let { RoomPromptBuilder.sanitize(it, speaker.name) }.orEmpty()

            onTyping(null)
            if (replyText.isBlank()) return@repeat

            val at = graph.clock.now().toEpochMilli()
            graph.rooms.appendMessage(
                roomId = roomId,
                characterId = speaker.id,
                role = "assistant",
                text = replyText,
                at = at,
                replyTo = null
            )
            val reply = RoomReply(speaker.id, speaker.name, replyText, at)
            replies += reply
            onReply(reply)
            delay(random.nextLong(260, 620))
        }

        return RoomSendResult(replies, null)
    }

    fun parseMentions(text: String, names: List<String>): List<String> {
        if (!text.contains("@")) return emptyList()
        return names
            .sortedByDescending { it.length }
            .filter { name -> text.contains("@$name") || text.contains("@ $name") }
    }

    private fun cardKeywords(card: CharacterCard): Set<String> {
        val tags = card.data.tags.joinToString(" ")
        return RoomScheduler.keywordsOf(
            card.data.description,
            card.data.personality,
            card.data.scenario,
            tags,
            card.data.name
        )
    }

    private fun fallbackCard(name: String): CharacterCard = CharacterCard(
        spec = null,
        data = com.dafeiyu.companion.card.CardData(
            name = name,
            description = "一个普通的群友，说话简短。",
            personality = "",
            scenario = "多人群聊",
            firstMessage = "我来了。",
            exampleMessages = "",
            systemPrompt = "",
            postHistoryInstructions = "",
            alternateGreetings = emptyList(),
            creatorNotes = "",
            tags = emptyList(),
            creator = "",
            characterVersion = "",
            worldInfo = emptyList(),
            extensions = kotlinx.serialization.json.buildJsonObject { },
            companion = null
        ),
        raw = kotlinx.serialization.json.buildJsonObject { }
    )

    private companion object {
        val NO_REPLY_CUES = listOf("别回", "先别回", "别回复", "不用回", "别理我", "别说话")
    }
}
