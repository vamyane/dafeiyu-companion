package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.model.MoodEvent
import com.dafeiyu.companion.model.MoodEventType

object MoodSignalDetector {

    fun detect(text: String): List<MoodEvent> {
        val events = mutableListOf<MoodEvent>()
        if (text.containsAny("喜欢你", "爱你", "可爱", "好棒", "想你")) {
            events += MoodEvent(MoodEventType.PRAISE)
        }
        if (text.containsAny("讨厌你", "滚开", "闭嘴", "不想理你", "烦死你")) {
            events += MoodEvent(MoodEventType.CRITICISM)
        }
        if (text.length >= 30 &&
            text.containsAny("最近", "其实", "我觉得", "压力", "难过")
        ) {
            events += MoodEvent(MoodEventType.DEEP_TALK)
        }
        return events
    }

    private fun String.containsAny(vararg words: String): Boolean =
        words.any { contains(it) }
}
