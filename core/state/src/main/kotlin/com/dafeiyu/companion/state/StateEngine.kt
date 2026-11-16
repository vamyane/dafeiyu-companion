package com.dafeiyu.companion.state

import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode
import com.dafeiyu.companion.model.IntentRecord
import com.dafeiyu.companion.model.MoodEvent
import com.dafeiyu.companion.model.MoodEventType
import com.dafeiyu.companion.model.OpenerRecord
import com.dafeiyu.companion.model.ProactiveConfig
import com.dafeiyu.companion.model.Signals
import com.dafeiyu.companion.model.SpeakCandidate
import com.dafeiyu.companion.model.TimeWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.roundToInt

class StateEngine(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    fun reduce(state: CompanionState, signals: Signals, profile: CharacterProfile): CompanionState {
        val now = signals.now
        val local = now.atZone(zoneId)
        val s = rollDay(state, local.toLocalDate().toString())
        val dtHours = (Duration.between(s.lastTickAt, now).toMinutes().coerceAtLeast(0) / 60.0)
            .coerceIn(0.0, 24.0)
        if (dtHours <= 0.0) return s

        val activity = profile.activityAt(local.toLocalTime())
        val energy = drift(
            current = s.energy.toDouble(),
            target = energyTarget(activity),
            dtHours = dtHours,
            tauHours = 4.0
        ).roundToInt().coerceIn(0, 100)

        val delta = s.moodEventsDelta(signals.moodEvents)
        val mood = drift(
            current = s.mood.toDouble(),
            target = 10.0,
            dtHours = dtHours,
            tauHours = 24.0
        ).plus(delta).roundToInt().coerceIn(-100, 100)

        val hoursSinceUser = s.lastUserMessageAt
            ?.let { Duration.between(it, now).toMinutes() / 60.0 }
            ?: 24.0
        val missing = if (hoursSinceUser > 6.0) {
            (s.missing + s.missingRatePerHour * dtHours).roundToInt().coerceIn(0, 100)
        } else {
            s.missing
        }

        var histogram = s.userActiveHistogram
        if (signals.userMessageSinceLastTick || signals.openedSinceLastTick) {
            histogram = histogram.bump(local.dayOfWeek.value - 1, slotOf(local.hour, local.minute))
        }

        val quietUntil = s.quietUntil
        val contactMode = if (
            s.contactMode == ContactMode.QUIET &&
            quietUntil != null &&
            !now.isBefore(quietUntil)
        ) {
            ContactMode.DORMANT
        } else {
            s.contactMode
        }

        return s.copy(
            energy = energy,
            mood = mood,
            missing = missing,
            activity = activity,
            contactMode = contactMode,
            lastOpenedAt = if (signals.openedSinceLastTick) now else s.lastOpenedAt,
            lastTickAt = now,
            userActiveHistogram = histogram
        )
    }

    fun onUserMessage(state: CompanionState, now: Instant): CompanionState {
        val local = now.atZone(zoneId)
        val s = rollDay(state, local.toLocalDate().toString())
        val histogram = s.userActiveHistogram
            .bump(local.dayOfWeek.value - 1, slotOf(local.hour, local.minute))
        val total = s.userMessageTotal + 1
        val affectionGain = if (total % 3 == 0) 1 else 0
        return s.copy(
            missing = (s.missing - 25).coerceAtLeast(0),
            unansweredStreak = 0,
            contactMode = ContactMode.NORMAL,
            quietUntil = null,
            unreadCount = 0,
            lastUserMessageAt = now,
            affection = (s.affection + affectionGain).coerceIn(0, 100),
            userMessageTotal = total,
            userActiveHistogram = histogram
        )
    }

    /**
     * 把用户消息里的情绪信号写回状态。只处理明确指向角色的表扬/批评和深聊，
     * 不根据普通抱怨随意改变角色心情。
     */
    fun applyMoodEvents(
        state: CompanionState,
        events: List<MoodEvent>,
        now: Instant
    ): CompanionState {
        if (events.isEmpty()) return state
        val local = now.atZone(zoneId)
        val s = rollDay(state, local.toLocalDate().toString())
        val delta = s.moodEventsDelta(events)
        val affectionDelta = events.fold(0) { acc, event ->
            acc + when (event.type) {
                MoodEventType.PRAISE -> 1
                MoodEventType.DEEP_TALK -> 1
                MoodEventType.CRITICISM -> -1
                else -> 0
            }
        }
        return s.copy(
            mood = (s.mood + delta).roundToInt().coerceIn(-100, 100),
            affection = (s.affection + affectionDelta).coerceIn(0, 100)
        )
    }

    fun onProactiveSent(
        state: CompanionState,
        now: Instant,
        config: ProactiveConfig,
        candidate: SpeakCandidate
    ): CompanionState {
        val local = now.atZone(zoneId)
        val s = rollDay(state, local.toLocalDate().toString())
        val lastProactive = s.lastProactiveAt
        val lastUser = s.lastUserMessageAt
        val previousUnanswered = lastProactive != null &&
            (lastUser == null || lastUser.isBefore(lastProactive))
        val streak = if (previousUnanswered) s.unansweredStreak + 1 else 1
        val coldBackoff = s.clinginess < 0.35
        val shouldQuiet = coldBackoff || streak >= 2
        val quietUntil = if (shouldQuiet) {
            now.plus(config.quietHoursAfterUnanswered, ChronoUnit.HOURS)
        } else {
            s.quietUntil
        }
        val mode = if (shouldQuiet) ContactMode.QUIET else ContactMode.CAUTIOUS
        val openers = (s.recentOpeners + OpenerRecord(
            at = now,
            text = candidate.text,
            intent = candidate.intent,
            timeWindow = TimeWindow.of(now, zoneId)
        )).takeLast(50)
        val intents = (s.recentIntents + IntentRecord(
            dayKey = local.toLocalDate().toString(),
            intent = candidate.intent
        )).takeLast(30)

        return s.copy(
            unansweredStreak = streak,
            contactMode = mode,
            quietUntil = quietUntil,
            lastProactiveAt = now,
            lastCompanionMessageAt = now,
            todaySpokenCount = s.todaySpokenCount + 1,
            nextProactiveCheckAt = now.plus(config.minGapMinutes, ChronoUnit.MINUTES),
            recentOpeners = openers,
            recentIntents = intents
        )
    }

    fun onModelCall(state: CompanionState, now: Instant): CompanionState {
        val local = now.atZone(zoneId)
        val s = rollDay(state, local.toLocalDate().toString())
        return s.copy(todayModelCalls = s.todayModelCalls + 1)
    }

    fun withNextCheck(state: CompanionState, until: Instant): CompanionState =
        state.copy(nextProactiveCheckAt = until)

    private fun rollDay(state: CompanionState, dayKey: String): CompanionState =
        if (state.dayKey == dayKey) {
            state
        } else {
            state.copy(dayKey = dayKey, todaySpokenCount = 0, todayModelCalls = 0)
        }

    private fun CompanionState.moodEventsDelta(events: List<MoodEvent>): Double {
        val raw = events.sumOf { event ->
            val base = when (event.type) {
                MoodEventType.PRAISE -> 8.0
                MoodEventType.CRITICISM -> -10.0
                MoodEventType.IGNORED -> -4.0 * rejectionSensitivity
                MoodEventType.DEEP_TALK -> 5.0
                MoodEventType.CONFLICT -> -12.0
            }
            base * (event.magnitude ?: 1.0)
        }
        return raw.coerceIn(-15.0, 15.0)
    }

    private fun drift(current: Double, target: Double, dtHours: Double, tauHours: Double): Double =
        current + (target - current) * (1.0 - exp(-dtHours / tauHours))

    private fun energyTarget(activity: Activity): Double = when (activity) {
        Activity.SLEEPING -> 95.0
        Activity.WORKING -> 45.0
        Activity.COMMUTING -> 55.0
        Activity.EATING -> 65.0
        Activity.PLAYING -> 60.0
        Activity.IDLE -> 70.0
    }

    private fun slotOf(hour: Int, minute: Int): Int = hour * 4 + minute / 15
}
