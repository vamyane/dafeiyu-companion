package com.dafeiyu.companion.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

interface Clock {
    fun now(): Instant
}

enum class MoodEventType { PRAISE, CRITICISM, IGNORED, DEEP_TALK, CONFLICT }

data class MoodEvent(
    val type: MoodEventType,
    val magnitude: Double? = null
)

enum class EventKind { UPCOMING_EVENT, ANNIVERSARY, NEAR_MISS, STRONG_EMOTION }

data class ProactiveEvent(
    val kind: EventKind,
    val weight: Double,
    val label: String = "",
    val expiresAt: Instant? = null
)

data class Signals(
    val now: Instant,
    val appForeground: Boolean = false,
    val charging: Boolean = false,
    val onHomeWifi: Boolean = false,
    val userMessageSinceLastTick: Boolean = false,
    val openedSinceLastTick: Boolean = false,
    val unreadSinceLastTick: Boolean = false,
    val moodEvents: List<MoodEvent> = emptyList(),
    val proactiveEvents: List<ProactiveEvent> = emptyList()
)

enum class TimeWindow {
    MORNING, NOON, AFTERNOON, EVENING, NIGHT;

    companion object {
        fun of(instant: Instant, zoneId: ZoneId): TimeWindow =
            of(instant.atZone(zoneId).toLocalTime())

        fun of(time: LocalTime): TimeWindow = when (time.hour) {
            in 6..11 -> MORNING
            in 12..13 -> NOON
            in 14..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> NIGHT
        }
    }
}

enum class ReasonCode {
    QUIET_MODE,
    LATE_NIGHT,
    SAFETY_CAP,
    MIN_GAP,
    UNANSWERED_STREAK,
    NEAR_MISS,
    LOW_IMPULSE,
    MODEL_VETO,
    MODEL_ERROR,
    MODEL_BUDGET,
    NEXT_CHECK,
    DUPLICATE_OPENER,
    SAME_WINDOW_REPEAT,
    INTENT_REPEAT,
    DORMANT_EVENT_CAP,
    TOO_LONG,
    TOO_MANY_SENTENCES,
    FORBIDDEN_PHRASE
}

sealed interface Decision {
    data object Silence : Decision
    data class Speak(val candidate: SpeakCandidate) : Decision
}

data class SpeakCandidate(
    val intent: String,
    val text: String,
    val generatedBy: String,
    val nextCheckMinutes: Int?
)

data class TickResult(
    val state: CompanionState,
    val decision: Decision,
    val reasons: List<ReasonCode>
)

data class ScheduleWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val activity: Activity
) {
    fun contains(time: LocalTime): Boolean {
        val start = LocalTime.of(startHour, startMinute)
        val end = LocalTime.of(endHour, endMinute)
        return if (start <= end) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }
}

data class CharacterProfile(
    val id: String,
    val name: String,
    val schedule: List<ScheduleWindow> = emptyList(),
    val talkativeness: Double = 0.5,
    val defaultClinginess: Double = 0.5,
    val maxChars: Int = 60,
    val maxSentences: Int = 2,
    val forbiddenPhrases: List<String> = listOf("作为AI", "我是你的助手", "有什么可以帮你")
) {
    fun activityAt(time: LocalTime): Activity =
        schedule.firstOrNull { it.contains(time) }?.activity
            ?: if (time >= LocalTime.of(23, 30) || time < LocalTime.of(7, 30)) {
                Activity.SLEEPING
            } else {
                Activity.IDLE
            }

    fun inSchedule(time: LocalTime): Boolean = schedule.any { it.contains(time) }
}

data class ProactiveConfig(
    val impulseThreshold: Double = 0.30,
    val eventThreshold: Double = 0.25,
    val safetyDailyCap: Int = 6,
    val minGapMinutes: Long = 20,
    val hardQuietStart: LocalTime = LocalTime.of(0, 30),
    val hardQuietEnd: LocalTime = LocalTime.of(6, 30),
    val dailyModelCallCap: Int = 12,
    val quietHoursAfterUnanswered: Long = 48,
    val nextCheckMinutesRange: IntRange = 30..360,
    val duplicateSimilarityThreshold: Double = 0.8,
    val duplicateLookbackDays: Long = 14,
    val intentRepeatDays: Int = 3,
    val windowRepeatDays: Int = 3
)

data class ChatMessage(
    val role: String,
    val text: String,
    val at: Instant,
    val imagePath: String? = null,
    val voicePath: String? = null,
    val voice: Boolean = false,
    val voiceSeconds: Int = 0,
    val sticker: String? = null
)

data class ProactiveRequest(
    val now: Instant,
    val state: CompanionState,
    val profile: CharacterProfile,
    val impulse: Double,
    val feelingDescription: String,
    val tone: String,
    val memory: List<MemoryEntry> = emptyList(),
    val recentMessages: List<ChatMessage> = emptyList(),
    val repairOf: String? = null
)

data class ProactiveResponse(
    val shouldSpeak: Boolean,
    val intent: String = "",
    val message: String = "",
    val feeling: String = "",
    val nextCheckMinutes: Int? = null,
    val reason: String = "",
    val tone: String = ""
)

interface ProactiveModel {
    fun decide(request: ProactiveRequest): ProactiveResponse
}

interface MemorySource {
    fun recall(companionId: String, query: String, limit: Int): List<MemoryEntry>
    fun recent(companionId: String, limit: Int): List<ChatMessage>
}

object EmptyMemorySource : MemorySource {
    override fun recall(companionId: String, query: String, limit: Int): List<MemoryEntry> =
        emptyList()

    override fun recent(companionId: String, limit: Int): List<ChatMessage> =
        emptyList()
}
