package com.dafeiyu.companion.llm

/**
 * 模型有时会把语音写成正文标记，例如 [voice]、[语音]、（语音）。
 * 统一识别并剥离，避免标记直接显示在气泡里。
 */
object VoiceMarkers {

    private val regex = Regex(
        "[\\[【（(]\\s*(voice|语音)\\s*[\\]】）)]",
        RegexOption.IGNORE_CASE
    )

    fun contains(text: String): Boolean = regex.containsMatchIn(text)

    fun strip(text: String): String = regex.replace(text, "").trim()
}
