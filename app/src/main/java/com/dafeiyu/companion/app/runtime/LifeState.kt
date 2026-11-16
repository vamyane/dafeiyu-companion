package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode

data class LifeState(
    val mood: Int,
    val energy: Int,
    val missing: Int,
    val affection: Int,
    val moodLabel: String,
    val affectionLabel: String,
    val activityLabel: String,
    val contactLabel: String?,
    val thought: String
) {
    companion object {
        fun from(state: CompanionState, lastUserMessage: String? = null): LifeState {
            val moodLabel = when {
                state.mood >= 50 -> "心情很好"
                state.mood >= 15 -> "心情不错"
                state.mood > -15 -> "还算平静"
                state.mood > -40 -> "有点低落"
                else -> "很难过"
            }
            val activityLabel = when (state.activity) {
                Activity.SLEEPING -> "在睡觉"
                Activity.WORKING -> "在忙"
                Activity.COMMUTING -> "在路上"
                Activity.EATING -> "在吃东西"
                Activity.PLAYING -> "在玩"
                Activity.IDLE -> "在发呆"
            }
            val affectionLabel = affectionLabel(state.affection)
            val contactLabel = when (state.contactMode) {
                ContactMode.NORMAL -> null
                ContactMode.CAUTIOUS -> "有点小心"
                ContactMode.QUIET -> "安静中"
                ContactMode.DORMANT -> "很久没开口"
            }
            val thought = when {
                state.contactMode == ContactMode.QUIET ||
                    state.contactMode == ContactMode.DORMANT ->
                    "有点安静，怕打扰你。"
                lastUserMessage != null &&
                    lastUserMessage.containsAny("累", "难受", "不开心", "烦", "压力") ->
                    "你说累，我有点担心你。"
                lastUserMessage != null &&
                    lastUserMessage.containsAny("开心", "高兴", "顺利", "好消息") ->
                    "你好像心情不错，我也跟着高兴。"
                lastUserMessage != null && lastUserMessage.contains("面试") ->
                    "你还惦记着面试，我也替你紧张。"
                state.missing >= 75 -> "很想你，但不确定你现在忙不忙。"
                state.mood <= -35 -> "有点难过，想被你注意到。"
                state.energy <= 30 -> "有点累，想先歇一会儿。"
                state.mood >= 45 -> "今天心情不错，想多和你说两句。"
                state.activity == Activity.SLEEPING -> "困了，还惦记着你。"
                else -> "在想你现在在做什么。"
            }
            return LifeState(
                mood = state.mood,
                energy = state.energy,
                missing = state.missing,
                affection = state.affection,
                moodLabel = moodLabel,
                affectionLabel = affectionLabel,
                activityLabel = activityLabel,
                contactLabel = contactLabel,
                thought = thought
            )
        }

        private fun String.containsAny(vararg words: String): Boolean =
            words.any { contains(it) }

        fun affectionLabel(value: Int): String = when {
            value >= 60 -> "深爱"
            value >= 30 -> "依赖"
            value >= 15 -> "亲近"
            value >= 5 -> "熟悉"
            else -> "陌生"
        }
    }
}
