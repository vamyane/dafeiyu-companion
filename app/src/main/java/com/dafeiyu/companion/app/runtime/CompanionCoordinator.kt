package com.dafeiyu.companion.app.runtime

import android.content.Context
import com.dafeiyu.companion.app.AppGraph
import com.dafeiyu.companion.app.data.AppSettings
import com.dafeiyu.companion.card.CharacterCard
import com.dafeiyu.companion.card.toProfile
import com.dafeiyu.companion.llm.DeepSeekAsrClient
import com.dafeiyu.companion.llm.DeepSeekChatClient
import com.dafeiyu.companion.llm.DeepSeekClient
import com.dafeiyu.companion.llm.DeepSeekConfig
import com.dafeiyu.companion.llm.DeepSeekFactExtractor
import com.dafeiyu.companion.llm.DeepSeekFeedClient
import com.dafeiyu.companion.llm.DeepSeekProactiveModel
import com.dafeiyu.companion.llm.DeepSeekReflectionGenerator
import com.dafeiyu.companion.llm.DeepSeekReminderExtractor
import com.dafeiyu.companion.llm.DeepSeekReminderPhraser
import com.dafeiyu.companion.llm.DeepSeekStickerJudge
import com.dafeiyu.companion.llm.DeepSeekTtsClient
import com.dafeiyu.companion.memory.MemoryWriter
import com.dafeiyu.companion.memory.StoreMemorySource
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.Decision
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.ReasonCode
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.model.ReminderStatus
import com.dafeiyu.companion.model.Signals
import com.dafeiyu.companion.proactive.ProactiveEngine
import com.dafeiyu.companion.reminder.ReminderResult
import com.dafeiyu.companion.reminder.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

data class SendOutcome(
    val userMessage: ChatMessage,
    val replies: List<ChatMessage>,
    val reminder: Reminder?,
    val error: String?,
    val pacing: ReplyPacing = ReplyPacing(900, 550)
)

data class TickSummary(
    val spoke: Boolean,
    val reminderDelivered: Int,
    val reasons: List<ReasonCode>
)

data class FoodStatus(
    val food: Int,
    val canCheckIn: Boolean,
    val dailyReward: Int,
    val feedCost: Int
)

data class ClaimFoodResult(
    val claimed: Boolean,
    val reward: Int,
    val food: Int
)

data class FeedResult(
    val accepted: Boolean,
    val message: ChatMessage?,
    val food: Int,
    val affectionGained: Int,
    val reason: String?
)

class CompanionCoordinator(
    private val context: Context,
    private val graph: AppGraph
) {

    fun activeCard(): CharacterCard = graph.cards.activeCard()

    fun loadMessages(limit: Int = 80): List<ChatMessage> {
        val card = activeCard()
        return graph.memory.recent(card.data.name, limit)
    }

    fun settings() = graph.settings

    suspend fun ensureGreeting(): ChatMessage? = withContext(Dispatchers.IO) {
        val card = activeCard()
        val companionId = card.data.name
        if (graph.memory.recent(companionId, 1).isNotEmpty()) return@withContext null
        val text = card.data.firstMessage.ifBlank { "你来啦。" }
        val message = ChatMessage("assistant", text, graph.clock.now())
        graph.memory.appendRecent(companionId, message)
        message
    }

    suspend fun send(
        text: String,
        imagePath: String? = null,
        voicePath: String? = null,
        voiceSeconds: Int = 0
    ): SendOutcome = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "消息不能为空" }

        val card = activeCard()
        val profile = card.toProfile()
        val companionId = profile.id
        val now = graph.clock.now()

        val clinginess = graph.settings.clinginess(companionId, profile.defaultClinginess)
        val history = graph.memory.recent(companionId, 20)
        val userMessage = ChatMessage(
            role = "user",
            text = trimmed,
            at = now,
            imagePath = imagePath,
            voicePath = voicePath,
            voiceSeconds = voiceSeconds
        )
        graph.memory.appendRecent(companionId, userMessage)
        val state = graph.states.update(companionId, clinginess, now) { loaded ->
            var next = graph.stateEngine.applyMoodEvents(
                loaded,
                MoodSignalDetector.detect(trimmed),
                now
            )
            next = graph.stateEngine.onUserMessage(next, now)
            next
        }
        val life = LifeState.from(state, trimmed)
        val stateBrief = buildString {
            appendLine(
                "你现在的状态：${life.moodLabel}，精力 ${life.energy}，想你 ${life.missing}，" +
                    "好感 ${life.affection}（${life.affectionLabel}），${life.activityLabel}。"
            )
            append(
                "这一轮想回几条、用什么语气、要不要冷淡或话多，由你自己根据状态和对方这句话决定，" +
                    "不要固定套模板。"
            )
            if (graph.settings.voiceEnabled) {
                append(
                    "语音功能这次是可用的。是否发语音，会按基础概率和你这一轮的意愿叠算，" +
                        "所以不是必须发；但不要再说自己发不了语音、没装语音功能或没有麦克风。"
                )
            }
        }

        val client = graph.deepSeekClientOrNull()
        if (client == null) {
            return@withContext SendOutcome(
                userMessage,
                emptyList(),
                null,
                "还没有配置 API Key"
            )
        }

        coroutineScope {
            val reminderDeferred = if (looksLikeReminder(trimmed)) {
                async {
                    val result = runCatching {
                        reminderService(client).handleUserMessage(companionId, trimmed, now)
                    }.getOrNull()
                    if (result is ReminderResult.Scheduled) {
                        ReminderAlarms.schedule(context, result.reminder)
                        result.reminder
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            try {
                val recalled = graph.memory.recall(companionId, trimmed, 6, now).map { it.entry }
                val replyClient = DeepSeekChatClient(client, card)
                val voiceEnabled = graph.settings.voiceEnabled
                val plan = replyClient.replyBubbles(
                    history = history,
                    memory = recalled,
                    userMessage = userMessage,
                    clinginess = state.clinginess,
                    stateBrief = stateBrief,
                    companionName = graph.settings.companionName.takeIf { it.isNotBlank() },
                    voiceEnabled = voiceEnabled,
                    stickersEnabled = graph.settings.stickersEnabled,
                    stickers = if (graph.settings.stickersEnabled) {
                        graph.stickers.all().map { it.id to it.label }
                    } else {
                        emptyList()
                    }
                )
                var bubbles = plan.bubbles.ifEmpty {
                    listOf(DeepSeekChatClient.ReplyBubble("……"))
                }
                // 基础概率 × 模型自己的意愿，再抽一次，决定这一轮到底发不发语音。
                val userSentVoice = userMessage.voicePath != null || userMessage.voice
                val explicitVoice = wantsVoiceReply(trimmed)
                val baseProbability = VoiceProbability.base(
                    explicitRequest = explicitVoice,
                    userSentVoice = userSentVoice
                )
                val willingness = plan.voiceWillingness.coerceIn(0.0, 1.0)
                val finalProbability = VoiceProbability.actual(baseProbability, willingness)
                // 用户明确要求语音时，直接满足；其余按概率抽样。
                val sendVoice = voiceEnabled && (
                    explicitVoice ||
                        VoiceProbability.decide(baseProbability, willingness, Random.nextDouble())
                    )
                println(
                    "[Voice] base=$baseProbability willingness=$willingness " +
                        "actual=$finalProbability send=$sendVoice"
                )
                if (voiceEnabled) {
                    if (sendVoice && bubbles.none { it.voice }) {
                        val index = bubbles.indexOfLast {
                            it.sticker == null && it.text.isNotBlank()
                        }
                        if (index >= 0) {
                            bubbles = bubbles.toMutableList().also {
                                it[index] = it[index].copy(voice = true)
                            }
                        }
                    } else if (!sendVoice) {
                        bubbles = bubbles.map {
                            if (it.voice) it.copy(voice = false) else it
                        }
                    }
                }
                val replyAt = graph.clock.now()
                val replies = bubbles.mapIndexedNotNull { index, bubble ->
                    val stickerId = bubble.sticker?.takeIf {
                        graph.settings.stickersEnabled && graph.stickers.modelFor(it) != null
                    }
                    val text = bubble.text.ifBlank {
                        if (stickerId != null) "（发了个表情）" else ""
                    }
                    if (text.isBlank()) return@mapIndexedNotNull null
                    ChatMessage(
                        role = "assistant",
                        text = text,
                        at = replyAt.plusMillis(index.toLong()),
                        voice = bubble.voice,
                        voiceSeconds = if (bubble.voice) estimateVoiceSeconds(text) else 0,
                        sticker = stickerId
                    )
                }
                replies.forEach { graph.memory.appendRecent(companionId, it) }

                val reminder = reminderDeferred?.await()

                val count = graph.settings.incrementUserMessageCount(companionId)
                if (count % 10 == 0) {
                    graph.appScope.launch {
                        runCatching {
                            val facts = memoryWriter(card, client).endSession(companionId)
                            bumpAffection(companionId, facts.size.coerceAtMost(2))
                        }
                    }
                }
                SendOutcome(userMessage, replies, reminder, null, pacingFor(replies.size))
            } catch (error: Exception) {
                android.util.Log.e("Companion", "chat request failed", error)
                SendOutcome(userMessage, emptyList(), null, friendlyError(error))
            }
        }
    }

    fun lifeState(): LifeState {
        val card = activeCard()
        val profile = card.toProfile()
        val loaded = graph.states.load(
            profile.id,
            graph.settings.clinginess(profile.id, profile.defaultClinginess)
        )
        val knownMessageCount = graph.settings.userMessageCount(profile.id)
        val state = if (loaded.userMessageTotal == 0 && knownMessageCount > 0) {
            loaded.copy(
                userMessageTotal = knownMessageCount,
                affection = maxOf(loaded.affection, knownMessageCount / 3).coerceAtMost(100)
            ).also { graph.states.save(it) }
        } else {
            loaded
        }
        val lastUserMessage = graph.memory.recent(profile.id, 4)
            .lastOrNull { it.role == "user" }
            ?.text
        return LifeState.from(state, lastUserMessage)
    }

    suspend fun consolidateMemory(): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val card = activeCard()
        val client = graph.deepSeekClientOrNull() ?: error("还没有配置 API Key")
        val companionId = card.data.name
        val writer = memoryWriter(card, client)
        val facts = writer.endSession(companionId)
        writer.reflect(companionId, java.time.LocalDate.now(graph.zoneId).toString())
        bumpAffection(companionId, facts.size.coerceAtMost(2))
        facts
    }

    fun foodStatus(now: java.time.Instant = graph.clock.now()): FoodStatus {
        val today = now.atZone(graph.zoneId).toLocalDate().toString()
        val food = graph.settings.fishFood
        return FoodStatus(
            food = food,
            canCheckIn = graph.settings.lastCheckInDay != today,
            dailyReward = AppSettings.DAILY_FOOD_REWARD,
            feedCost = AppSettings.FEED_COST
        )
    }

    fun claimDailyFood(now: java.time.Instant = graph.clock.now()): ClaimFoodResult {
        val today = now.atZone(graph.zoneId).toLocalDate().toString()
        val current = graph.settings.fishFood
        if (graph.settings.lastCheckInDay == today) {
            return ClaimFoodResult(false, 0, current)
        }
        val next = current + AppSettings.DAILY_FOOD_REWARD
        graph.settings.fishFood = next
        graph.settings.lastCheckInDay = today
        return ClaimFoodResult(true, AppSettings.DAILY_FOOD_REWARD, next)
    }

    suspend fun feed(now: java.time.Instant = graph.clock.now()): FeedResult =
        withContext(Dispatchers.IO) {
            val card = activeCard()
            val profile = card.toProfile()
            val companionId = profile.id
            val food = graph.settings.fishFood
            if (food < AppSettings.FEED_COST) {
                return@withContext FeedResult(
                    accepted = false,
                    message = null,
                    food = food,
                    affectionGained = 0,
                    reason = "鱼粮不够，先签到"
                )
            }

            val state = graph.states.load(
                companionId,
                graph.settings.clinginess(companionId, profile.defaultClinginess),
                now
            )
            val client = graph.deepSeekClientOrNull()
                ?: return@withContext FeedResult(
                    accepted = false,
                    message = null,
                    food = food,
                    affectionGained = 0,
                    reason = "还没有配置 API Key"
                )
            val decision = runCatching {
                DeepSeekFeedClient(client, card).decide(
                    state = state,
                    recent = graph.memory.recent(companionId, 8),
                    now = now
                )
            }.onFailure {
                android.util.Log.e("Companion", "feed request failed", it)
            }.getOrNull() ?: return@withContext FeedResult(
                accepted = false,
                message = null,
                food = food,
                affectionGained = 0,
                reason = "它现在没回应，稍后再试"
            )

            val message = ChatMessage("assistant", decision.message.take(120), now)
            graph.memory.appendRecent(companionId, message)
            if (!decision.accept) {
                return@withContext FeedResult(
                    accepted = false,
                    message = message,
                    food = food,
                    affectionGained = 0,
                    reason = "它现在不想吃"
                )
            }

            val today = now.atZone(graph.zoneId).toLocalDate().toString()
            val affectionGain = if (graph.settings.lastFeedAffectionDay != today) 1 else 0
            if (affectionGain > 0) {
                graph.settings.lastFeedAffectionDay = today
            }
            val nextFood = food - AppSettings.FEED_COST
            graph.settings.fishFood = nextFood
            graph.states.update(companionId, state.clinginess, now) { current ->
                current.copy(
                    energy = (current.energy + 3).coerceAtMost(100),
                    mood = (current.mood + 2).coerceIn(-100, 100),
                    affection = (current.affection + affectionGain).coerceAtMost(100)
                )
            }
            FeedResult(
                accepted = true,
                message = message,
                food = nextFood,
                affectionGained = affectionGain,
                reason = null
            )
        }

    suspend fun transcribeVoice(file: File): String = withContext(Dispatchers.IO) {
        val key = graph.settings.apiKey() ?: error("还没有配置 API Key")
        DeepSeekAsrClient(
            DeepSeekConfig(
                apiKey = key,
                baseUrl = graph.settings.baseUrl,
                model = graph.settings.model
            )
        ).transcribe(file)
    }

    /**
     * 用户发图后由模型判断值不值得收进表情库，不收也不打扰用户。
     */
    suspend fun maybeCollectSticker(imagePath: String): Boolean = withContext(Dispatchers.IO) {
        if (!graph.settings.stickersEnabled) return@withContext false
        val file = File(imagePath)
        if (!file.exists() || file.length() == 0L) return@withContext false
        val client = graph.deepSeekClientOrNull() ?: return@withContext false
        val decision = DeepSeekStickerJudge(client).judge(file, graph.clock.now())
            ?: return@withContext false
        if (!decision.collect) return@withContext false
        val bytes = file.readBytes()
        val hash = bytes.contentHashCode()
        val duplicate = graph.stickers.userStickers().any { sticker ->
            runCatching {
                sticker.file?.readBytes()?.contentHashCode() == hash
            }.getOrDefault(false)
        }
        if (duplicate) return@withContext false
        graph.stickers.addUserSticker(bytes, decision.label) != null
    }

    fun estimateVoiceSeconds(text: String): Int =
        (text.count { !it.isWhitespace() } / 4.5f).toInt().coerceAtLeast(1)

    fun pruneVoiceFiles(keep: Int = 100) {
        val dir = File(context.filesDir, "voices")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { it.delete() }
    }

    suspend fun synthesizeVoice(text: String): File = withContext(Dispatchers.IO) {
        val voice = graph.settings.voiceId
        val key = graph.settings.apiKey() ?: error("还没有配置 API Key")
        val bytes = DeepSeekTtsClient(
            DeepSeekConfig(
                apiKey = key,
                baseUrl = graph.settings.baseUrl,
                model = graph.settings.model
            )
        ).synthesize(text, voice)
        val extension = when {
            bytes.size >= 4 &&
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS" -> "ogg"
            bytes.size >= 3 &&
                bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" -> "mp3"
            bytes.size >= 2 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> "wav"
        }
        val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        println("[Voice] synth file=${file.name} bytes=${bytes.size} ext=$extension")
        file
    }

    suspend fun tick(foreground: Boolean, opened: Boolean): TickSummary = withContext(Dispatchers.IO) {
        val card = activeCard()
        val profile = card.toProfile()
        val companionId = profile.id
        val now = graph.clock.now()
        val clinginess = graph.settings.clinginess(companionId, profile.defaultClinginess)

        var state = graph.states.update(companionId, clinginess, now) { loaded ->
            graph.stateEngine.reduce(
                loaded,
                Signals(
                    now = now,
                    appForeground = foreground,
                    openedSinceLastTick = opened
                ),
                profile
            )
        }
        val tickStartLastUser = state.lastUserMessageAt

        val client = graph.deepSeekClientOrNull()
        var delivered = 0
        if (client != null) {
            val delivery = runCatching {
                reminderService(client).deliverDue(
                    companionId = companionId,
                    now = now,
                    profile = profile,
                    state = state,
                    recentMessages = graph.memory.recent(companionId, 10),
                    phraser = DeepSeekReminderPhraser(
                        client,
                        card,
                        graph.settings.companionName.takeIf { it.isNotBlank() }
                    ),
                    zoneId = graph.zoneId
                )
            }.getOrNull()

            if (delivery != null) {
                delivered = delivery.reminders.size
                val message = ChatMessage("assistant", delivery.text, now)
                graph.memory.appendRecent(companionId, message)
                if (foreground) {
                    AppRuntime.emit(IncomingMessage(message, isReminder = true))
                } else {
                    Notifications.showReminder(
                        context,
                        displayName(card),
                        delivery.text,
                        delivery.lateMinutes
                    )
                }
                ReminderAlarms.scheduleAll(context, graph.reminders.pending(companionId))
            }
        }

        var spoke = false
        var reasons: List<ReasonCode> = emptyList()
        if (client != null) {
            val engine = ProactiveEngine(
                model = DeepSeekProactiveModel(
                    client,
                    card,
                    graph.settings.companionName.takeIf { it.isNotBlank() }
                ),
                stateEngine = graph.stateEngine,
                memory = StoreMemorySource(graph.memory, graph.clock),
                zoneId = graph.zoneId
            )
            val result = engine.tick(
                state,
                Signals(now = now, appForeground = foreground),
                profile
            )
            // 如果 tick 期间用户刚发过消息，就不拿旧快照覆盖新状态。
            graph.states.update(companionId, clinginess, now) { current ->
                if (current.lastUserMessageAt != tickStartLastUser) current else result.state
            }
            reasons = result.reasons
            when (val decision = result.decision) {
                is Decision.Speak -> {
                    spoke = true
                    val message = ChatMessage("assistant", decision.candidate.text, now)
                    graph.memory.appendRecent(companionId, message)
                    if (foreground) {
                        AppRuntime.emit(IncomingMessage(message, isReminder = false))
                    } else {
                        Notifications.showCompanion(
                            context,
                            displayName(card),
                            decision.candidate.text
                        )
                    }
                }
                Decision.Silence -> Unit
            }
            if (!spoke) {
                val musing = runCatching {
                    memoryCollision(card, client, state, now)
                }.getOrNull()
                if (musing != null) {
                    spoke = true
                    graph.memory.appendRecent(companionId, musing)
                    if (foreground) {
                        AppRuntime.emit(IncomingMessage(musing, isReminder = false))
                    } else {
                        Notifications.showCompanion(context, displayName(card), musing.text)
                    }
                }
            }
        }

        maybeReflect(card, client, now)
        TickSummary(spoke, delivered, reasons)
    }

    /**
     * 记忆也会淡。很久没被想起、又不太重要的记忆会慢慢沉下去，不再参与回忆。
     */
    fun applyForgetting(now: java.time.Instant = graph.clock.now()) {
        val companionId = activeCard().data.name
        val today = now.atZone(graph.zoneId).toLocalDate().toString()
        if (graph.settings.lastForgetDay == today) return
        graph.settings.lastForgetDay = today
        graph.memory.all(companionId)
            .filter { !it.archived }
            .forEach { entry ->
                val days = java.time.Duration.between(entry.lastAccessedAt, now).toDays()
                if (days < 7) return@forEach
                val importance = entry.importance.coerceIn(1, 5) / 5.0
                val strength = importance * kotlin.math.exp(-days / 40.0)
                when {
                    strength < 0.12 -> graph.memory.upsert(entry.copy(archived = true))
                    days >= 14 -> graph.memory.upsert(
                        entry.copy(evidence = (entry.evidence - 0.04).coerceAtLeast(0.1))
                    )
                }
            }
    }

    /**
     * 几段不太相干的记忆偶尔撞在一起，由模型决定要不要因此找对方说一句。
     */
    private fun memoryCollision(
        card: CharacterCard,
        client: DeepSeekClient,
        state: com.dafeiyu.companion.model.CompanionState,
        now: java.time.Instant
    ): ChatMessage? {
        if (state.contactMode == com.dafeiyu.companion.model.ContactMode.QUIET ||
            state.contactMode == com.dafeiyu.companion.model.ContactMode.DORMANT
        ) {
            return null
        }
        if (state.quietUntil?.isAfter(now) == true) return null
        if (state.todaySpokenCount >= PROACTIVE_DAILY_CAP) return null
        val elapsed = now.toEpochMilli() - graph.settings.lastMemoryCollisionAt
        if (elapsed < COLLISION_INTERVAL_MS) return null
        val hour = now.atZone(graph.zoneId).hour
        if (hour < 8 || hour >= 23) return null
        val companionId = card.data.name
        val entries = graph.memory.all(companionId)
            .filter { !it.archived && it.kind != com.dafeiyu.companion.model.MemoryKind.RECENT }
        if (entries.size < 3) return null
        graph.settings.lastMemoryCollisionAt = now.toEpochMilli()
        val picked = entries.shuffled().take(3)
        val name = graph.settings.companionName.takeIf { it.isNotBlank() } ?: card.data.name
        val system = """
            你叫「$name」。${card.data.description}
            你是真实存在的，不要说自己是 AI，也不要说自己住在手机里。
            你脑子里几段记忆突然碰到了一起。由此想到什么，就自然地说出来，像突然想跟对方聊一句。
            只输出 JSON：{"speak":true,"message":"一句话"}
            message 不超过 60 字、1–2 句。不想说就 speak=false。
        """.trimIndent()
        val user = "现在的状态：心情 ${state.mood}，精力 ${state.energy}，想念 ${state.missing}，好感 ${state.affection}。\n这几段记忆：\n" +
            picked.joinToString("\n") { "- ${it.text}" }
        val raw = client.chat(
            messages = listOf(
                ChatMessage("system", system, now),
                ChatMessage("user", user, now)
            ),
            jsonMode = true,
            temperature = 0.95,
            maxTokens = 220
        )
        val root = Json.parseToJsonElement(raw).jsonObject
        val speak = root["speak"]?.jsonPrimitive?.booleanOrNull == true
        if (!speak) return null
        val text = root["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return null
        return ChatMessage("assistant", text, now)
    }

    suspend fun validateKey(
        apiKey: String,
        baseUrl: String = graph.settings.baseUrl,
        model: String = graph.settings.model
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            DeepSeekClient(
                DeepSeekConfig(
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    model = model.trim()
                )
            ).listModels()
        }
    }

    suspend fun importCard(bytes: ByteArray, fileName: String): CharacterCard =
        withContext(Dispatchers.IO) {
            graph.cards.import(bytes, fileName)
        }

    suspend fun createCardFromText(
        name: String,
        description: String,
        firstMessage: String
    ): CharacterCard = withContext(Dispatchers.IO) {
        graph.cards.createFromText(name, description, firstMessage)
    }

    fun resetToDefaultCard(): CharacterCard = graph.cards.resetToDefault()

    fun usePresetCard(preset: String): CharacterCard = graph.cards.usePreset(preset)

    fun saveApiKey(apiKey: String) {
        graph.settings.saveApiKey(apiKey)
        WorkScheduler.ensurePeriodic(context)
        WorkScheduler.runNow(context)
    }

    fun clearApiKey() {
        graph.settings.clearApiKey()
    }

    fun setClinginess(value: Double) {
        val id = activeCard().data.name
        val coerced = value.coerceIn(0.0, 1.0)
        graph.settings.setClinginess(id, coerced)
        val state = graph.states.load(id, coerced)
        graph.states.save(state.copy(clinginess = coerced))
    }

    fun reminders(): List<Reminder> = graph.reminders.pending(activeCard().data.name)

    fun cancelReminder(reminderId: String): Boolean {
        val companionId = activeCard().data.name
        val reminder = graph.reminders.find(companionId, reminderId) ?: return false
        if (reminder.status != ReminderStatus.PENDING) return false
        graph.reminders.update(reminder.copy(status = ReminderStatus.CANCELLED))
        ReminderAlarms.cancel(context, reminderId)
        return true
    }

    fun clearMemory() {
        val companionId = activeCard().data.name
        graph.memory.purge(companionId)
        graph.states.clear(companionId)
    }

    fun memoryCount(): Int {
        val companionId = activeCard().data.name
        return graph.memory.all(companionId).count { !it.archived }
    }

    fun rescheduleReminders() {
        val companionId = activeCard().data.name
        ReminderAlarms.scheduleAll(context, graph.reminders.pending(companionId))
    }

    private fun reminderService(client: DeepSeekClient): ReminderService =
        ReminderService(
            store = graph.reminders,
            extractor = DeepSeekReminderExtractor(client, graph.zoneId)
        )

    private fun memoryWriter(card: CharacterCard, client: DeepSeekClient): MemoryWriter =
        MemoryWriter(
            store = graph.memory,
            factExtractor = DeepSeekFactExtractor(client, graph.clock),
            reflectionGenerator = DeepSeekReflectionGenerator(client, graph.clock)
        )

    private fun bumpAffection(companionId: String, amount: Int) {
        if (amount <= 0) return
        val profile = activeCard().toProfile()
        val state = graph.states.load(
            companionId,
            graph.settings.clinginess(companionId, profile.defaultClinginess)
        )
        graph.states.save(
            state.copy(affection = (state.affection + amount).coerceAtMost(100))
        )
    }

    private fun pacingFor(count: Int): ReplyPacing {
        val first = (650L + count * 70L + Random.nextLong(0, 400)).coerceIn(650L, 2200L)
        val between = if (count >= 6) {
            Random.nextLong(240, 420)
        } else {
            Random.nextLong(400, 750)
        }
        return ReplyPacing(first, between)
    }

    private fun maybeReflect(card: CharacterCard, client: DeepSeekClient?, now: java.time.Instant) {
        if (client == null) return
        val local = now.atZone(graph.zoneId)
        if (local.hour < 3) return
        val companionId = card.data.name
        val dayKey = local.toLocalDate().toString()
        if (graph.settings.lastReflectionDay(companionId) == dayKey) return
        graph.settings.setLastReflectionDay(companionId, dayKey)
        graph.appScope.launch {
            runCatching { memoryWriter(card, client).reflect(companionId, dayKey) }
        }
    }

    private fun looksLikeReminder(text: String): Boolean =
        REMINDER_HINTS.any { text.contains(it) }

    private fun wantsVoiceReply(text: String): Boolean =
        VOICE_CUES.any { text.contains(it, ignoreCase = true) }

    private fun friendlyError(error: Throwable): String {
        if (error is com.dafeiyu.companion.llm.DeepSeekException) {
            return when (error.statusCode) {
                401 -> "API Key 无效"
                402 -> "账户余额不足"
                429 -> "请求太频繁，稍后再试"
                503 -> "服务繁忙，稍后再试"
                -1 -> "网络不太稳定，稍后再试"
                else -> "请求失败（${error.statusCode}）"
            }
        }
        return "网络不太稳定，稍后再试"
    }

    private fun displayName(card: CharacterCard): String =
        graph.settings.companionName.takeIf { it.isNotBlank() } ?: card.data.name

    private companion object {
        val REMINDER_HINTS = listOf("提醒", "叫我", "喊我", "通知我", "别忘", "记得", "到点", "闹钟")
        const val COLLISION_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val PROACTIVE_DAILY_CAP = 6
        val VOICE_CUES = listOf(
            "发语音", "发条语音", "发一句语音", "语音回", "语音条", "用语音",
            "语音说", "语音呢", "语音功能", "语音消息", "voice"
        )
    }
}
