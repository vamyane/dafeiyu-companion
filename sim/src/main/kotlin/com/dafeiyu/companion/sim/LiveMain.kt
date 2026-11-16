package com.dafeiyu.companion.sim

import com.dafeiyu.companion.card.CardCodec
import com.dafeiyu.companion.card.toProfile
import com.dafeiyu.companion.llm.DeepSeekChatClient
import com.dafeiyu.companion.llm.DeepSeekClient
import com.dafeiyu.companion.llm.DeepSeekConfig
import com.dafeiyu.companion.llm.DeepSeekFactExtractor
import com.dafeiyu.companion.llm.DeepSeekProactiveModel
import com.dafeiyu.companion.llm.DeepSeekReflectionGenerator
import com.dafeiyu.companion.memory.InMemoryMemoryStore
import com.dafeiyu.companion.memory.MemoryWriter
import com.dafeiyu.companion.memory.StoreMemorySource
import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.Clock
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.Signals
import com.dafeiyu.companion.proactive.ProactiveEngine
import com.dafeiyu.companion.state.StateEngine
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * 真实 DeepSeek 联调入口。
 *
 * 需要环境变量：
 * - DEEPSEEK_API_KEY
 * - CARD_PATH，默认 examples/dafeiyu.card.json
 * - USER_TEXT，默认“今天有点累。”
 * - DEEPSEEK_MODEL，默认 deepseek-flash
 * - DEEPSEEK_BASE_URL，默认 https://api.deepseek.com
 */
fun main() {
    val localEnv = readLocalEnv()
    val apiKey = envValue("DEEPSEEK_API_KEY", localEnv)?.trim().orEmpty()
    if (apiKey.isBlank()) {
        println("缺少 DEEPSEEK_API_KEY，无法联调。")
        println("可以在 companion-core/.env.local 写一行：DEEPSEEK_API_KEY=sk-...")
        println("或设置环境变量：\$env:DEEPSEEK_API_KEY=\"sk-...\"")
        exitProcess(1)
    }

    val zone = ZoneId.systemDefault()
    val clock = object : Clock {
        override fun now(): Instant = Instant.now()
    }

    try {
        val cardPath = envValue("CARD_PATH", localEnv) ?: "examples/dafeiyu.card.json"
        val cardFile = File(cardPath)
        if (!cardFile.exists()) {
            println("找不到角色卡：${cardFile.absolutePath}")
            exitProcess(1)
        }
        val card = CardCodec.decode(cardFile.readBytes(), cardFile.name)
        val profile = card.toProfile()
        val clinginess = envValue("CLINGINESS", localEnv)
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: profile.defaultClinginess

        val config = DeepSeekConfig(
            apiKey = apiKey,
            baseUrl = envValue("DEEPSEEK_BASE_URL", localEnv) ?: "https://api.deepseek.com",
            model = envValue("DEEPSEEK_MODEL", localEnv) ?: "deepseek-flash"
        )
        val client = DeepSeekClient(config)

        println("角色卡：${card.data.name}")
        println("依恋度：$clinginess")
        println("可用模型：${client.listModels().joinToString()}")

        val store = InMemoryMemoryStore()
        val writer = MemoryWriter(
            store = store,
            factExtractor = DeepSeekFactExtractor(client, clock),
            reflectionGenerator = DeepSeekReflectionGenerator(client, clock)
        )
        val chatClient = DeepSeekChatClient(client, card)
        val stateEngine = StateEngine(zone)
        val proactiveEngine = ProactiveEngine(
            model = DeepSeekProactiveModel(client, card),
            stateEngine = stateEngine,
            memory = StoreMemorySource(store, clock),
            zoneId = zone
        )

        val now = clock.now()
        val proactiveNow = LocalDate.now(zone).atTime(21, 0).atZone(zone).toInstant()
        val userText = envValue("USER_TEXT", localEnv) ?: "今天有点累。"
        val userMessage = ChatMessage("user", userText, now)

        val history = store.recent(profile.id, 10)
        val recalled = store.recall(profile.id, userText, 6, now).map { it.entry }
        val reply = chatClient.reply(history, recalled, userMessage, clinginess)
        writer.record(profile.id, userMessage)
        writer.record(profile.id, ChatMessage("assistant", reply, clock.now()))

        println()
        println("用户：$userText")
        println("${profile.name}：$reply")

        var state = CompanionState(
            companionId = profile.id,
            clinginess = clinginess,
            missing = 70,
            dayKey = now.atZone(zone).toLocalDate().toString(),
            lastTickAt = proactiveNow.minusSeconds(15 * 60),
            lastUserMessageAt = proactiveNow.minusSeconds(20 * 3600),
            recentOpeners = emptyList(),
            userActiveHistogram = ActiveHistogram.default()
        )
        val signals = Signals(now = proactiveNow)
        state = stateEngine.reduce(state, signals, profile)
        val tick = proactiveEngine.tick(state, signals, profile)
        println()
        println("主动决策：${tick.decision}")
        println("原因：${tick.reasons.joinToString()}")

        val facts = writer.endSession(profile.id)
        println()
        println("提取事实：${facts.joinToString("；") { it.text }}")

        val reflection = writer.reflect(profile.id, LocalDate.now(zone).toString())
        println("当日反思：${reflection?.text ?: "（无）"}")
    } catch (error: Exception) {
        println("联调失败：${error.message}")
        error.printStackTrace()
        exitProcess(1)
    }
}

internal fun envValue(key: String, local: Map<String, String>): String? =
    local[key]?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

internal fun readLocalEnv(): Map<String, String> {
    val file = File(".env.local")
    if (!file.exists()) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = trimmed.substring(0, index).trim()
        val value = trimmed.substring(index + 1).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        key to value
    }.toMap()
}
