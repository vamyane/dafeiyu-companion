package com.dafeiyu.companion.card

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal object PngChunks {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private const val MAX_CHUNK = 16 * 1024 * 1024

    data class Chunk(val type: String, val data: ByteArray)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)

    fun read(bytes: ByteArray): List<Chunk> {
        require(isPng(bytes)) { "不是 PNG 文件" }
        val chunks = mutableListOf<Chunk>()
        var pos = 8
        while (pos + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, pos, 4).int
            require(length in 0..MAX_CHUNK) { "PNG chunk 长度异常: $length" }
            if (pos + 12 + length > bytes.size) break
            val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            val data = bytes.copyOfRange(pos + 8, pos + 8 + length)
            chunks += Chunk(type, data)
            pos += 12 + length
            if (type == "IEND") break
        }
        return chunks
    }

    fun textMap(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (chunk in read(bytes)) {
            val parsed = when (chunk.type) {
                "tEXt" -> parseText(chunk.data)
                "iTXt" -> parseIText(chunk.data)
                else -> null
            } ?: continue
            result.putIfAbsent(parsed.first.lowercase(), parsed.second)
        }
        return result
    }

    fun withText(bytes: ByteArray, keyword: String, value: String): ByteArray {
        val chunks = read(bytes).filterNot { chunk ->
            val key = when (chunk.type) {
                "tEXt" -> parseText(chunk.data)?.first
                "iTXt" -> parseIText(chunk.data)?.first
                else -> null
            }
            key?.equals(keyword, ignoreCase = true) == true
        }.toMutableList()

        val textData = keyword.toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0) +
            value.toByteArray(StandardCharsets.ISO_8859_1)
        val newChunk = Chunk("tEXt", textData)
        val iend = chunks.indexOfFirst { it.type == "IEND" }
        if (iend >= 0) chunks.add(iend, newChunk) else chunks.add(newChunk)
        return build(chunks)
    }

    private fun parseText(data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0.toByte())
        if (zero <= 0) return null
        val key = String(data, 0, zero, StandardCharsets.ISO_8859_1)
        val text = String(data, zero + 1, data.size - zero - 1, StandardCharsets.ISO_8859_1)
        return key to text
    }

    private fun parseIText(data: ByteArray): Pair<String, String>? {
        val parts = mutableListOf<ByteArray>()
        var start = 0
        for (i in data.indices) {
            if (data[i] == 0.toByte()) {
                parts += data.copyOfRange(start, i)
                start = i + 1
                if (parts.size == 5) break
            }
        }
        if (parts.size < 5) return null
        val key = String(parts[0], StandardCharsets.ISO_8859_1)
        val compressionFlag = parts[1].firstOrNull()?.toInt() ?: 0
        if (compressionFlag != 0) return null
        val text = String(parts[4], StandardCharsets.UTF_8)
        return key to text
    }

    private fun build(chunks: List<Chunk>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        for (chunk in chunks) {
            val typeBytes = chunk.type.toByteArray(StandardCharsets.US_ASCII)
            out.write(ByteBuffer.allocate(4).putInt(chunk.data.size).array())
            out.write(typeBytes)
            out.write(chunk.data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(chunk.data)
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        return out.toByteArray()
    }
}
