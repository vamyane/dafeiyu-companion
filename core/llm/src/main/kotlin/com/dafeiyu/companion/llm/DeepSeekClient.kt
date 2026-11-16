package com.dafeiyu.companion.llm

import com.dafeiyu.companion.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

data class DeepSeekConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val timeoutSeconds: Long = 20
) {
    init {
        require(apiKey.isNotBlank()) { "DeepSeek API Key 不能为空" }
    }

    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"

    val modelsUrl: String
        get() = "${baseUrl.trimEnd('/')}/models"
}

class DeepSeekException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 使用 HttpURLConnection，保证同一份内核既能跑 JVM 模拟器，也能跑 Android。
 * Android 上没有 java.net.http.HttpClient。
 */
class DeepSeekClient(private val config: DeepSeekConfig) {

    fun chat(
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
        temperature: Double = 0.9,
        maxTokens: Int = 800,
        includeImageInLastMessage: Boolean = false
    ): String {
        var lastError: DeepSeekException? = null
        repeat(2) { attempt ->
            val content = try {
                requestChat(messages, jsonMode, temperature, maxTokens, includeImageInLastMessage)
            } catch (error: DeepSeekException) {
                if (error.statusCode in 500..599 && attempt == 0) {
                    lastError = error
                    return@repeat
                }
                throw error
            }
            if (content.isNotBlank()) return content
            lastError = DeepSeekException(-1, "DeepSeek 返回空内容")
        }
        throw lastError ?: DeepSeekException(-1, "DeepSeek 调用失败")
    }

    private fun requestChat(
        messages: List<ChatMessage>,
        jsonMode: Boolean,
        temperature: Double,
        maxTokens: Int,
        includeImageInLastMessage: Boolean
    ): String {
        val payload = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray {
                messages.forEachIndexed { index, message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        val imagePath = message.imagePath
                        if (includeImageInLastMessage &&
                            index == messages.lastIndex &&
                            imagePath != null
                        ) {
                            val file = File(imagePath)
                            if (file.exists()) {
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", message.text.ifBlank { "请看这张图。" })
                                    })
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", imageDataUrl(file))
                                        })
                                    })
                                })
                            } else {
                                put("content", message.text)
                            }
                        } else {
                            put("content", message.text)
                        }
                    })
                }
            })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }.toString()

        val (status, body) = execute("POST", config.chatCompletionsUrl, payload)
        if (status !in 200..299) {
            throw DeepSeekException(status, "DeepSeek 请求失败: $body")
        }
        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: throw DeepSeekException(-1, "响应缺少 choices[0].message.content")
        }.getOrElse { error ->
            if (error is DeepSeekException) throw error
            throw DeepSeekException(-1, "DeepSeek 响应解析失败", error)
        }
    }

    fun listModels(): List<String> {
        val (status, body) = execute("GET", config.modelsUrl, null)
        if (status !in 200..299) {
            throw DeepSeekException(status, "DeepSeek Key 校验失败: $body")
        }
        val root = Json.parseToJsonElement(body).jsonObject
        return root["data"]?.jsonArray?.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.content
        } ?: emptyList()
    }

    private fun imageDataUrl(file: File): String {
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        return "data:$mime;base64,$encoded"
    }

    private fun execute(method: String, url: String, body: String?): Pair<Int, String> {
        val timeoutMs = (config.timeoutSeconds * 1000).toInt().coerceAtLeast(1000)
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } catch (error: Exception) {
            if (error is DeepSeekException) throw error
            throw DeepSeekException(-1, "DeepSeek 网络请求失败: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }
}
