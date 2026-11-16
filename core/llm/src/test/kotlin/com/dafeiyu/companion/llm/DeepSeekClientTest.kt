package com.dafeiyu.companion.llm

import com.dafeiyu.companion.card.CardCodec
import com.dafeiyu.companion.card.toProfile
import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ProactiveRequest
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.reminder.ReminderBatch
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekClientTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val card = CardCodec.decodeJson(
        """
        {
          "spec": "chara_card_v2",
          "data": {
            "name": "大肥鱼",
            "description": "一条会聊天的鱼",
            "personality": "嘴硬心软",
            "scenario": "手机里",
            "first_mes": "你来啦。",
            "mes_example": "",
            "system_prompt": "",
            "post_history_instructions": "",
            "alternate_greetings": [],
            "extensions": {
              "x_companion": {
                "personality": {"clinginess": 0.5},
                "speech": {"maxChars": 40}
              }
            }
          }
        }
        """.trimIndent()
    )

    @Test
    fun chatSendsMessagesAndReturnsContent() {
        var requestBody = ""
        withServer(
            chatResponse = chatContent("你好"),
            onRequest = { requestBody = it }
        ) { baseUrl ->
            val content = client(baseUrl).chat(
                messages = listOf(ChatMessage("user", "在吗", now)),
                jsonMode = true
            )
            assertEquals("你好", content)
            assertTrue(requestBody.contains("deepseek-flash"))
            assertTrue(requestBody.contains("在吗"))
            assertTrue(requestBody.contains("response_format"))
        }
    }

    @Test
    fun listModelsReturnsIds() {
        withServer(chatResponse = chatContent("ok")) { baseUrl ->
            assertEquals(
                listOf("deepseek-flash", "deepseek-v4-pro"),
                client(baseUrl).listModels()
            )
        }
    }

    @Test
    fun httpErrorBecomesDeepSeekException() {
        withServer(chatResponse = """{"error":"unauthorized"}""", chatStatus = 401) { baseUrl ->
            val error = assertFailsWith<DeepSeekException> {
                client(baseUrl).chat(listOf(ChatMessage("user", "在吗", now)))
            }
            assertEquals(401, error.statusCode)
        }
    }

    @Test
    fun proactiveModelParsesJsonDecision() {
        val inner = """
            {"should_speak":true,"intent":"check_in","message":"在忙吗。","feeling":"有点想你","next_check_minutes":120}
        """.trimIndent()
        withServer(chatResponse = chatContent(inner)) { baseUrl ->
            val state = CompanionState(
                companionId = "dafeiyu",
                clinginess = 0.5,
                missing = 60,
                lastTickAt = now.minusSeconds(900),
                lastUserMessageAt = now.minusSeconds(20L * 3600),
                dayKey = "2026-01-01",
                userActiveHistogram = ActiveHistogram.default()
            )
            val request = ProactiveRequest(
                now = now,
                state = state,
                profile = card.toProfile(),
                impulse = 0.6,
                feelingDescription = "有点想找她",
                tone = "自然"
            )
            val response = DeepSeekProactiveModel(client(baseUrl), card).decide(request)
            assertTrue(response.shouldSpeak)
            assertEquals("check_in", response.intent)
            assertEquals("在忙吗。", response.message)
        }
    }

    @Test
    fun reminderExtractorParsesDueAt() {
        val inner = """
            {"is_reminder":true,"text":"面试","due_at":"2026-01-02T09:30:00+08:00","repeat":"NONE"}
        """.trimIndent()
        withServer(chatResponse = chatContent(inner)) { baseUrl ->
            val draft = DeepSeekReminderExtractor(client(baseUrl), ZoneId.of("Asia/Shanghai"))
                .extract("dafeiyu", "明天九点半提醒我面试", now)
            assertEquals("面试", draft?.text)
            assertEquals(Instant.parse("2026-01-02T01:30:00Z"), draft?.dueAt)
        }
    }

    @Test
    fun reminderExtractorReturnsNullWhenNotAReminder() {
        withServer(chatResponse = chatContent("""{"is_reminder":false}""")) { baseUrl ->
            val draft = DeepSeekReminderExtractor(client(baseUrl), ZoneId.of("Asia/Shanghai"))
                .extract("dafeiyu", "今天有点累", now)
            assertNull(draft)
        }
    }

    @Test
    fun reminderPhraserUsesModelVoice() {
        withServer(chatResponse = chatContent("九点半了。该起了。")) { baseUrl ->
            val batch = reminderBatch()
            val phrase = DeepSeekReminderPhraser(client(baseUrl), card).phrase(batch)
            assertEquals("九点半了。该起了。", phrase)
        }
    }

    @Test
    fun reminderPhraserRejectsSystemLanguage() {
        withServer(chatResponse = chatContent("提醒您：九点半面试")) { baseUrl ->
            val batch = reminderBatch()
            assertNull(DeepSeekReminderPhraser(client(baseUrl), card).phrase(batch))
        }
    }

    private fun reminderBatch(): ReminderBatch {
        val reminder = Reminder(
            id = "r1",
            companionId = "dafeiyu",
            text = "面试",
            dueAt = now,
            createdAt = now
        )
        return ReminderBatch(
            reminders = listOf(reminder),
            profile = card.toProfile(),
            state = CompanionState(
                companionId = "dafeiyu",
                clinginess = 0.5,
                lastTickAt = now,
                dayKey = "2026-01-01",
                userActiveHistogram = ActiveHistogram.default()
            ),
            recentMessages = emptyList(),
            now = now
        )
    }

    private fun client(baseUrl: String): DeepSeekClient = DeepSeekClient(
        DeepSeekConfig(
            apiKey = "test-key",
            baseUrl = baseUrl,
            model = "deepseek-flash",
            timeoutSeconds = 5
        )
    )

    private fun chatContent(content: String): String =
        """{"choices":[{"message":{"content":${JsonPrimitive(content)}}}]}"""

    private fun withServer(
        chatResponse: String,
        chatStatus: Int = 200,
        onRequest: (String) -> Unit = {},
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            onRequest(body)
            respond(exchange, chatStatus, chatResponse)
        }
        server.createContext("/models") { exchange ->
            respond(exchange, 200, """{"data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}""")
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
