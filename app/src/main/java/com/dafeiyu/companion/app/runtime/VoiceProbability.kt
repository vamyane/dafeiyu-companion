package com.dafeiyu.companion.app.runtime

/**
 * 语音回复概率：基础概率 × 模型这一轮的意愿，再抽一次随机数决定是否真的发语音。
 * 基础概率：用户发语音 99%，用户发文字 10%，用户明确要求 100%。
 */
object VoiceProbability {

    const val BASE_USER_VOICE = 0.99
    const val BASE_TEXT = 0.10
    const val BASE_REQUESTED = 1.0

    fun base(explicitRequest: Boolean, userSentVoice: Boolean): Double = when {
        explicitRequest -> BASE_REQUESTED
        userSentVoice -> BASE_USER_VOICE
        else -> BASE_TEXT
    }

    fun actual(base: Double, willingness: Double): Double =
        (base * willingness).coerceIn(0.0, 1.0)

    fun decide(base: Double, willingness: Double, roll: Double): Boolean =
        roll < actual(base, willingness)
}
