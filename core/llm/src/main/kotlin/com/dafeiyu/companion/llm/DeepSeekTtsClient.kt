package com.dafeiyu.companion.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class DeepSeekTtsClient(private val config: DeepSeekConfig) {

    fun synthesize(text: String, voiceId: String = "stella"): ByteArray {
        val payload = buildJsonObject {
            put("text", text.take(10_000))
            put("voice_id", voiceId)
            put("format", "wav")
            put("filter_markdown", true)
        }.toString()

        val connection = (URI.create("${config.baseUrl.trimEnd('/')}/v1/tools/tts")
            .toURL()
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = (config.timeoutSeconds * 1000).toInt()
            readTimeout = (config.timeoutSeconds * 1000).toInt()
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(payload.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw DeepSeekException(status, "语音合成失败: $error")
            }
            val contentType = connection.contentType.orEmpty()
            val bytes = connection.inputStream.use { it.readBytes() }
            println(
                "[DeepSeekTts] status=$status type=$contentType bytes=${bytes.size} " +
                    "head=${hexHead(bytes)}"
            )
            return ensureWav(bytes)
        } catch (error: Exception) {
            if (error is DeepSeekException) throw error
            throw DeepSeekException(-1, "语音合成网络失败: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 官方返回的 wav 是边合成边下发的，data 长度可能写 0 或 0xFFFFFFFF；部分播放器会因此拒绝播放。
     * 这里补全 RIFF/data 长度；如果拿到的其实是裸 PCM，就自己加一个 24kHz 单声道 16bit 的头。
     */
    private fun ensureWav(bytes: ByteArray): ByteArray {
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WAVE"
        ) {
            val dataOffset = findDataChunk(bytes) ?: return bytes
            val payload = bytes.size - dataOffset
            writeIntLE(bytes, 4, bytes.size - 8)
            writeIntLE(bytes, dataOffset - 4, payload)
            return bytes
        }
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            throw DeepSeekException(-1, "语音合成返回了错误信息: " +
                bytes.toString(StandardCharsets.UTF_8).take(200))
        }
        if (looksLikeContainer(bytes)) return bytes
        return wrapPcm(bytes)
    }

    private fun looksLikeContainer(bytes: ByteArray): Boolean {
        if (bytes.size >= 4) {
            val magic = bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII)
            if (magic == "OggS" || magic.startsWith("ID3")) return true
        }
        return bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xE0) == 0xE0
    }

    private fun findDataChunk(bytes: ByteArray): Int? {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 4).toString(StandardCharsets.US_ASCII)
            val size = readIntLE(bytes, offset + 4)
            if (id == "data") return offset + 8
            if (size <= 0) return null
            offset += 8 + size
        }
        return null
    }

    private fun wrapPcm(pcm: ByteArray): ByteArray {
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            value.toByteArray(StandardCharsets.US_ASCII).copyInto(header, offset)
        }
        putString(0, "RIFF")
        writeIntLE(header, 4, 36 + pcm.size)
        putString(8, "WAVE")
        putString(12, "fmt ")
        writeIntLE(header, 16, 16)
        header[20] = 1
        header[22] = 1
        writeIntLE(header, 24, 24_000)
        writeIntLE(header, 28, 24_000 * 2)
        header[32] = 2
        header[34] = 16
        putString(36, "data")
        writeIntLE(header, 40, pcm.size)
        return header + pcm
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun hexHead(bytes: ByteArray): String =
        bytes.take(12).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}
