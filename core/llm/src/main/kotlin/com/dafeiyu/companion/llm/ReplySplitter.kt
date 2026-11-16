package com.dafeiyu.companion.llm

/**
 * 把模型的一段回复拆成微信式短消息。
 * 一条只放一个意思：按换行和句号/问号/叹号/分号拆，过短的片段和下一句合并。
 */
object ReplySplitter {

    private val separators = charArrayOf(
        '。', '！', '？', '!', '?', '；', ';', '\n'
    )

    fun split(text: String, maxBubbles: Int = 3): List<String> {
        val trimmed = text
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
            .trim()
        if (trimmed.isEmpty() || maxBubbles <= 0) return emptyList()

        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in trimmed) {
            buffer.append(ch)
            if (ch in separators) {
                val value = buffer.toString().trim()
                if (value.isNotEmpty()) sentences += value
                buffer.clear()
            }
        }
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty()) sentences += tail

        val merged = mutableListOf<String>()
        for (sentence in sentences) {
            if (merged.isNotEmpty() && merged.last().length < 3) {
                merged[merged.lastIndex] = merged.last() + sentence
            } else {
                merged += sentence
            }
        }
        if (merged.size <= maxBubbles) return merged

        val head = merged.take(maxBubbles - 1).toMutableList()
        head += merged.drop(maxBubbles - 1).joinToString("")
        return head
    }
}
