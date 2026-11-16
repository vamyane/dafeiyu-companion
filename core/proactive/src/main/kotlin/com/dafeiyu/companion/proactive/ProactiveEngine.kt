package com.dafeiyu.companion.proactive

import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode
import com.dafeiyu.companion.model.Decision
import com.dafeiyu.companion.model.EmptyMemorySource
import com.dafeiyu.companion.model.MemorySource
import com.dafeiyu.companion.model.ProactiveConfig
import com.dafeiyu.companion.model.ProactiveModel
import com.dafeiyu.companion.model.ProactiveRequest
import com.dafeiyu.companion.model.ReasonCode
import com.dafeiyu.companion.model.Signals
import com.dafeiyu.companion.model.SpeakCandidate
import com.dafeiyu.companion.model.TickResult
import com.dafeiyu.companion.model.TimeWindow
import com.dafeiyu.companion.state.StateEngine
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

data class ImpulseBreakdown(
    val impulse: Double,
    val impulseFinal: Double,
    val timeFit: Double,
    val idlePressure: Double,
    val missingNorm: Double,
    val eventBoost: Double,
    val lateNight: Boolean,
    val penalties: Double
)

class ImpulseCalculator(private val zoneId: ZoneId) {

    fun compute(
        state: CompanionState,
        signals: Signals,
        profile: CharacterProfile,
        config: ProactiveConfig
    ): ImpulseBreakdown {
        val now = signals.now
        val local = now.atZone(zoneId)
        val histogram = state.userActiveHistogram.normalized(
            local.dayOfWeek.value - 1,
            local.hour * 4 + local.minute / 15
        )
        val scheduleFit = if (profile.inSchedule(local.toLocalTime())) 1.0 else 0.3
        val timeFit = 0.7 * histogram + 0.3 * scheduleFit

        val hoursSince = state.lastUserMessageAt
            ?.let { Duration.between(it, now).toMinutes() / 60.0 }
            ?: 24.0
        val idlePressure = ((hoursSince - 2.0) / state.absenceToleranceHours).coerceIn(0.0, 1.0)
        val missingNorm = state.missing / 100.0
        val eventBoost = signals.proactiveEvents
            .filter { it.expiresAt == null || now.isBefore(it.expiresAt) }
            .sumOf { it.weight }
            .coerceAtMost(0.4)
        val eventNorm = (eventBoost / 0.4).coerceIn(0.0, 1.0)
        val lonelyMood = ((20 - state.mood) / 60.0).coerceIn(0.0, 1.0)

        val c = state.clinginess.coerceIn(0.0, 1.0)
        val wTime = 0.38 - 0.18 * c
        val wIdle = 0.16 + 0.06 * c
        val wMissing = 0.20 + 0.20 * c
        val wEvent = 0.15
        val wLonely = 0.11 - 0.08 * c
        val willingness = 0.40 + 0.60 * c

        val impulse = ((
            wTime * timeFit +
                wIdle * idlePressure +
                wMissing * missingNorm +
                wEvent * eventNorm +
                wLonely * lonelyMood
            ) * willingness).coerceIn(0.0, 1.0)

        val unreadPenalty = min(0.30 + 0.10 * c, (0.10 + 0.15 * c) * state.unreadCount)
        val unansweredPenalty = min(0.40 + 0.20 * c, (0.12 + 0.15 * c) * state.unansweredStreak)
        val lateNight = isHardQuiet(local.toLocalTime(), config)
        val latePenalty = if (lateNight && histogram < 0.2) {
            (0.50 - 0.30 * c).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val openedAt = state.lastOpenedAt
        val lastUser = state.lastUserMessageAt
        val nearMiss = if (
            openedAt != null &&
            Duration.between(openedAt, now).toMinutes() <= 15 &&
            (lastUser == null || lastUser.isBefore(openedAt))
        ) {
            0.20
        } else {
            0.0
        }
        val overuse = if (state.todaySpokenCount >= 5) 0.50 else 0.0
        val penalties = (unreadPenalty + unansweredPenalty + latePenalty + nearMiss + overuse)
            .coerceIn(0.0, 0.9)

        return ImpulseBreakdown(
            impulse = impulse,
            impulseFinal = (impulse - penalties).coerceIn(0.0, 1.0),
            timeFit = timeFit,
            idlePressure = idlePressure,
            missingNorm = missingNorm,
            eventBoost = eventBoost,
            lateNight = lateNight,
            penalties = penalties
        )
    }

    private fun isHardQuiet(time: LocalTime, config: ProactiveConfig): Boolean {
        val start = config.hardQuietStart
        val end = config.hardQuietEnd
        return if (start <= end) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }
}

data class ValidationResult(
    val ok: Boolean,
    val normalized: String,
    val reasons: List<ReasonCode>
)

class MessageValidator(
    private val config: ProactiveConfig,
    private val zoneId: ZoneId
) {

    fun validate(
        state: CompanionState,
        profile: CharacterProfile,
        raw: String,
        intent: String,
        now: Instant
    ): ValidationResult {
        val text = raw.trim()
        val reasons = mutableListOf<ReasonCode>()

        if (text.isBlank()) reasons += ReasonCode.MODEL_ERROR
        if (text.codePointCount(0, text.length) > profile.maxChars) {
            reasons += ReasonCode.TOO_LONG
        }
        if (sentenceCount(text) > profile.maxSentences) {
            reasons += ReasonCode.TOO_MANY_SENTENCES
        }
        val lower = text.lowercase()
        if (profile.forbiddenPhrases.any { lower.contains(it.lowercase()) }) {
            reasons += ReasonCode.FORBIDDEN_PHRASE
        }

        val day = now.atZone(zoneId).toLocalDate()
        val recent = state.recentOpeners.filter {
            val d = it.at.atZone(zoneId).toLocalDate()
            !d.isBefore(day.minusDays(config.duplicateLookbackDays)) && !d.isAfter(day)
        }
        if (recent.any { similarity(it.text, text) > config.duplicateSimilarityThreshold }) {
            reasons += ReasonCode.DUPLICATE_OPENER
        }

        val intentDays = state.recentIntents
            .filter { it.intent == intent }
            .map { it.dayKey }
            .toSet()
        var repeatedIntentDays = 0
        for (back in 1 until config.intentRepeatDays) {
            if (intentDays.contains(day.minusDays(back.toLong()).toString())) {
                repeatedIntentDays++
            }
        }
        if (repeatedIntentDays >= config.intentRepeatDays - 1) {
            reasons += ReasonCode.INTENT_REPEAT
        }

        val window = TimeWindow.of(now, zoneId)
        var repeatedWindowDays = 0
        for (back in 1 until config.windowRepeatDays) {
            val target = day.minusDays(back.toLong())
            val hit = state.recentOpeners.any {
                it.at.atZone(zoneId).toLocalDate() == target && it.timeWindow == window
            }
            if (hit) repeatedWindowDays++
        }
        if (repeatedWindowDays >= config.windowRepeatDays - 1) {
            reasons += ReasonCode.SAME_WINDOW_REPEAT
        }

        return ValidationResult(reasons.isEmpty(), text, reasons)
    }

    fun similarity(a: String, b: String): Double {
        val left = trigrams(a)
        val right = trigrams(b)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { right.contains(it) }
        val union = left.size + right.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun trigrams(value: String): Set<String> {
        val normalized = value.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.length < 3) return setOf(normalized)
        return (0..normalized.length - 3)
            .map { normalized.substring(it, it + 3) }
            .toSet()
    }

    private fun sentenceCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.split('。', '！', '？', '!', '?', '；', ';', '\n')
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
    }
}

class ProactiveEngine(
    private val model: ProactiveModel,
    private val stateEngine: StateEngine,
    private val config: ProactiveConfig = ProactiveConfig(),
    private val memory: MemorySource = EmptyMemorySource,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val impulseCalculator = ImpulseCalculator(zoneId)
    private val validator = MessageValidator(config, zoneId)

    fun tick(state: CompanionState, signals: Signals, profile: CharacterProfile): TickResult {
        val now = signals.now
        val local = now.atZone(zoneId)
        var s = state

        s.nextProactiveCheckAt?.let {
            if (now.isBefore(it)) {
                return TickResult(s, Decision.Silence, listOf(ReasonCode.NEXT_CHECK))
            }
        }

        if (s.contactMode == ContactMode.QUIET) {
            val until = s.quietUntil
            if (until != null && now.isBefore(until)) {
                return TickResult(s, Decision.Silence, listOf(ReasonCode.QUIET_MODE))
            }
            s = s.copy(contactMode = ContactMode.DORMANT)
        }

        val breakdown = impulseCalculator.compute(s, signals, profile, config)

        if (s.contactMode == ContactMode.DORMANT) {
            val last = s.lastProactiveAt
            val cooldownOk = last == null ||
                Duration.between(last, now).toMinutes() / 1440.0 >= s.dormantCooldownDays
            if (breakdown.eventBoost < config.eventThreshold || !cooldownOk) {
                return TickResult(s, Decision.Silence, listOf(ReasonCode.QUIET_MODE))
            }
        }

        if (breakdown.lateNight) {
            return TickResult(s, Decision.Silence, listOf(ReasonCode.LATE_NIGHT))
        }

        if (s.todaySpokenCount >= config.safetyDailyCap) {
            return TickResult(s, Decision.Silence, listOf(ReasonCode.SAFETY_CAP))
        }
        val lastProactive = s.lastProactiveAt
        if (lastProactive != null &&
            Duration.between(lastProactive, now).toMinutes() < config.minGapMinutes
        ) {
            return TickResult(s, Decision.Silence, listOf(ReasonCode.MIN_GAP))
        }

        val shouldAsk = breakdown.impulseFinal >= config.impulseThreshold ||
            breakdown.eventBoost >= config.eventThreshold
        if (!shouldAsk) {
            return TickResult(s, Decision.Silence, listOf(ReasonCode.LOW_IMPULSE))
        }
        if (s.todayModelCalls >= config.dailyModelCallCap) {
            return TickResult(s, Decision.Silence, listOf(ReasonCode.MODEL_BUDGET))
        }

        s = stateEngine.onModelCall(s, now)
        val request = request(s, profile, breakdown, now, repairOf = null)
        val response = try {
            model.decide(request)
        } catch (e: Exception) {
            return TickResult(
                s.copy(nextProactiveCheckAt = now.plus(60, ChronoUnit.MINUTES)),
                Decision.Silence,
                listOf(ReasonCode.MODEL_ERROR)
            )
        }

        if (!response.shouldSpeak) {
            val minutes = (response.nextCheckMinutes ?: 120).coerceIn(config.nextCheckMinutesRange)
            return TickResult(
                s.copy(nextProactiveCheckAt = now.plus(minutes.toLong(), ChronoUnit.MINUTES)),
                Decision.Silence,
                listOf(ReasonCode.MODEL_VETO)
            )
        }

        var chosen = response
        var validation = validator.validate(s, profile, response.message, response.intent, now)
        if (!validation.ok && s.todayModelCalls < config.dailyModelCallCap) {
            s = stateEngine.onModelCall(s, now)
            val repair = try {
                model.decide(request(s, profile, breakdown, now, repairOf = response.message))
            } catch (e: Exception) {
                null
            }
            if (repair != null && repair.shouldSpeak) {
                val second = validator.validate(s, profile, repair.message, repair.intent, now)
                if (second.ok) {
                    chosen = repair
                    validation = second
                }
            }
        }

        if (!validation.ok) {
            val reasons = validation.reasons.ifEmpty { listOf(ReasonCode.MODEL_ERROR) }
            return TickResult(
                s.copy(nextProactiveCheckAt = now.plus(60, ChronoUnit.MINUTES)),
                Decision.Silence,
                reasons
            )
        }

        val candidate = SpeakCandidate(
            intent = chosen.intent.ifBlank { "check_in" },
            text = validation.normalized,
            generatedBy = "model",
            nextCheckMinutes = chosen.nextCheckMinutes
        )
        val after = stateEngine.onProactiveSent(s, now, config, candidate)
        return TickResult(after, Decision.Speak(candidate), emptyList())
    }

    private fun request(
        state: CompanionState,
        profile: CharacterProfile,
        breakdown: ImpulseBreakdown,
        now: Instant,
        repairOf: String?
    ): ProactiveRequest {
        val tone = when {
            state.clinginess < 0.3 -> "克制"
            state.clinginess <= 0.7 -> "自然"
            else -> "黏人"
        }
        val feeling = when {
            breakdown.impulseFinal < 0.35 -> "只是有点想起她"
            breakdown.impulseFinal < 0.55 -> "有一点想找她，但不确定合不合适"
            breakdown.impulseFinal < 0.75 -> "挺想找她说话的"
            else -> "很想找她，已经忍了一会儿"
        }
        val recent = try {
            memory.recent(state.companionId, 10)
        } catch (e: Exception) {
            emptyList()
        }
        val query = recent.lastOrNull { it.role == "user" }?.text
            ?: recent.lastOrNull()?.text
            ?: ""
        val recalled = try {
            memory.recall(state.companionId, query, 6)
        } catch (e: Exception) {
            emptyList()
        }
        return ProactiveRequest(
            now = now,
            state = state,
            profile = profile,
            impulse = breakdown.impulseFinal,
            feelingDescription = feeling,
            tone = tone,
            memory = recalled,
            recentMessages = recent,
            repairOf = repairOf
        )
    }
}
