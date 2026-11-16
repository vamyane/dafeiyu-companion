package com.dafeiyu.companion.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

class DeepSeekAsrClient(private val config: DeepSeekConfig) {

    fun transcribe(wav: File): String {
        require(wav.exists()) { "录音文件不存在" }
        require(wav.length() <= MAX_BYTES) { "录音太大了，说短一点" }
        val boundary = "----dafeiyu${UUID.randomUUID().toString().replace("-", "")}"
        val fileName = wav.name
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

        val connection = (URI.create("${config.baseUrl.trimEnd('/')}/v1/tools/asr")
            .toURL()
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = (config.timeoutSeconds * 1000).toInt()
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(head.size.toLong() + wav.length() + tail.size)
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(head)
                wav.inputStream().use { it.copyTo(stream) }
                stream.write(tail)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw DeepSeekException(status, "语音识别失败: $error")
            }
            val raw = connection.inputStream
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            return runCatching {
                Json.parseToJsonElement(raw)
                    .jsonObject["text"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .trim()
            }.getOrDefault("")
        } catch (error: Exception) {
            if (error is DeepSeekException) throw error
            throw DeepSeekException(-1, "语音识别网络失败: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024L
    }
}
