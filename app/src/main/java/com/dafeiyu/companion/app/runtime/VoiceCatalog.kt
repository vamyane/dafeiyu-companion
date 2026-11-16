package com.dafeiyu.companion.app.runtime

data class VoiceOption(
    val id: String,
    val label: String
)

object VoiceCatalog {

    val voices = listOf(
        VoiceOption("stella", "海星"),
        VoiceOption("tide", "暗潮")
    )

    fun forProvider(provider: String): List<VoiceOption> = voices

    fun isValid(provider: String, voiceId: String): Boolean =
        voices.any { it.id == voiceId }
}
