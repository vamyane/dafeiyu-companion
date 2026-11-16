package com.dafeiyu.companion.sim

import com.dafeiyu.companion.card.CardCodec
import com.dafeiyu.companion.card.toProfile
import com.dafeiyu.companion.llm.DeepSeekClient
import com.dafeiyu.companion.llm.DeepSeekConfig
import com.dafeiyu.companion.llm.DeepSeekReminderExtractor
import com.dafeiyu.companion.llm.DeepSeekReminderPhraser
import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.reminder.InMemoryReminderStore
import com.dafeiyu.companion.reminder.ReminderResult
import com.dafeiyu.companion.reminder.ReminderService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.system.exitProcess

fun main() {
    val local = readLocalEnv()
    val apiKey = envValue("DEEPSEEK_API_KEY", local)?.trim().orEmpty()
    if (apiKey.isBlank()) {
        println("缺少 DEEPSEEK_API_KEY，无法联调提醒。")
        println("可以在 companion-core/.env.local 写一行：DEEPSEEK_API_KEY=sk-...")
        exitProcess(1)
    }

    val zone = ZoneId.systemDefault()
    try {
        val cardPath = envValue("CARD_PATH", local) ?: "examples/test-cards/lan.json"
        val cardFile = File(cardPath)
        if (!cardFile.exists()) {
            println("找不到角色卡：${cardFile.absolutePath}")
            exitProcess(1)
        }
        val card = CardCodec.decode(cardFile.readBytes(), cardFile.name)
        val profile = card.toProfile()
        val clinginess = envValue("CLINGINESS", local)
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: profile.defaultClinginess

        val client = DeepSeekClient(
            DeepSeekConfig(
                apiKey = apiKey,
                baseUrl = envValue("DEEPSEEK_BASE_URL", local) ?: "https://api.deepseek.com",
                model = envValue("DEEPSEEK_MODEL", local) ?: "deepseek-flash"
            )
        )
        val service = ReminderService(
            store = InMemoryReminderStore(),
            extractor = DeepSeekReminderExtractor(client, zone)
        )
        val phraser = DeepSeekReminderPhraser(client, card)

        val now = Instant.now()
        val reminderText = envValue("REMINDER_TEXT", local) ?: "明天上午九点半提醒我面试"
        when (val result = service.handleUserMessage(profile.id, reminderText, now)) {
            is ReminderResult.Scheduled -> {
                val reminder = result.reminder
                println("角色卡：${card.data.name}")
                println("依恋度：$clinginess")
                println("用户请求：$reminderText")
                println("提取结果：${reminder.text} @ ${reminder.dueAt.atZone(zone)}")

                val state = CompanionState(
                    companionId = profile.id,
                    clinginess = clinginess,
                    missing = 40,
                    lastTickAt = now,
                    dayKey = now.atZone(zone).toLocalDate().toString(),
                    userActiveHistogram = ActiveHistogram.default()
                )
                val recent = listOf(
                    ChatMessage("user", "我先去准备一下，明早记得叫我。", now.minusSeconds(3600))
                )
                val delivery = service.deliverDue(
                    companionId = profile.id,
                    now = reminder.dueAt,
                    profile = profile,
                    state = state,
                    recentMessages = recent,
                    phraser = phraser,
                    zoneId = zone
                )
                println("到点表达：${delivery?.text}")
                println("生成方式：${delivery?.generatedBy}")
            }
            is ReminderResult.Rejected -> println("提醒被拒绝：${result.reason}")
            ReminderResult.NotAReminder -> println("没有识别到提醒")
        }
    } catch (error: Exception) {
        println("提醒联调失败：${error.message}")
        error.printStackTrace()
        exitProcess(1)
    }
}
