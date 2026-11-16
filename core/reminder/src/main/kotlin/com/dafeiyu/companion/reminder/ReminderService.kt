package com.dafeiyu.companion.reminder

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.model.ReminderRepeat
import com.dafeiyu.companion.model.ReminderStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

interface ReminderStore {
    fun add(reminder: Reminder)
    fun pending(companionId: String): List<Reminder>
    fun find(companionId: String, id: String): Reminder?
    fun update(reminder: Reminder)
    fun all(companionId: String): List<Reminder>
}

class InMemoryReminderStore : ReminderStore {

    private val map = mutableMapOf<String, MutableMap<String, Reminder>>()

    override fun add(reminder: Reminder) {
        map.getOrPut(reminder.companionId) { mutableMapOf() }[reminder.id] = reminder
    }

    override fun pending(companionId: String): List<Reminder> =
        map[companionId]?.values
            ?.filter { it.status == ReminderStatus.PENDING }
            ?.sortedBy { it.dueAt }
            ?: emptyList()

    override fun find(companionId: String, id: String): Reminder? =
        map[companionId]?.get(id)

    override fun update(reminder: Reminder) {
        map.getOrPut(reminder.companionId) { mutableMapOf() }[reminder.id] = reminder
    }

    override fun all(companionId: String): List<Reminder> =
        map[companionId]?.values?.sortedBy { it.dueAt } ?: emptyList()
}

data class ReminderDraft(
    val text: String,
    val dueAt: Instant,
    val repeat: ReminderRepeat = ReminderRepeat.NONE
)

interface ReminderExtractor {
    fun extract(companionId: String, userText: String, now: Instant): ReminderDraft?
}

data class ReminderConfig(
    val maxPendingPerCompanion: Int = 50,
    val maxDaysAhead: Long = 366,
    val minLeadMinutes: Long = 1,
    val maxTextLength: Int = 200
)

sealed interface ReminderResult {
    data class Scheduled(val reminder: Reminder, val duplicate: Boolean = false) : ReminderResult
    data class Rejected(val reason: String) : ReminderResult
    data object NotAReminder : ReminderResult
}

data class ReminderBatch(
    val reminders: List<Reminder>,
    val profile: CharacterProfile,
    val state: CompanionState,
    val recentMessages: List<ChatMessage>,
    val now: Instant
)

interface ReminderPhraser {
    fun phrase(batch: ReminderBatch): String?
}

data class ReminderDelivery(
    val reminders: List<Reminder>,
    val text: String,
    val generatedBy: String,
    val lateMinutes: Long
)

object ReminderFallback {

    private val forbidden = listOf("提醒您", "待办事项", "温馨提示", "系统通知")

    fun isAcceptable(text: String): Boolean {
        val value = text.trim()
        return value.isNotBlank() &&
            value.length <= 120 &&
            forbidden.none { value.contains(it) }
    }

    fun phrase(batch: ReminderBatch, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val first = batch.reminders.minByOrNull { it.dueAt } ?: return ""
        val time = first.dueAt.atZone(zoneId)
        val hhmm = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
        val items = batch.reminders.joinToString("；") { it.text }
        val late = batch.reminders.any {
            Duration.between(it.dueAt, batch.now).toMinutes() > 2
        }
        val prefix = if (late) "我刚看到，晚了点。" else ""
        val c = batch.state.clinginess
        val body = when {
            c < 0.35 -> "$hhmm 了。$items。"
            c <= 0.7 -> "$hhmm 了，$items。别忘了。"
            else -> "$hhmm 啦，$items！别忘了！"
        }
        return prefix + body
    }
}

class ReminderService(
    private val store: ReminderStore,
    private val extractor: ReminderExtractor,
    private val config: ReminderConfig = ReminderConfig()
) {

    fun handleUserMessage(companionId: String, userText: String, now: Instant): ReminderResult {
        val draft = extractor.extract(companionId, userText, now)
            ?: return ReminderResult.NotAReminder
        val text = draft.text.trim()
        if (text.isBlank()) return ReminderResult.Rejected("提醒内容为空")
        if (text.length > config.maxTextLength) return ReminderResult.Rejected("提醒内容过长")
        if (!draft.dueAt.isAfter(now.plus(config.minLeadMinutes, ChronoUnit.MINUTES))) {
            return ReminderResult.Rejected("提醒时间必须晚于现在")
        }
        if (draft.dueAt.isAfter(now.plus(config.maxDaysAhead, ChronoUnit.DAYS))) {
            return ReminderResult.Rejected("提醒时间太远")
        }
        val pending = store.pending(companionId)
        if (pending.size >= config.maxPendingPerCompanion) {
            return ReminderResult.Rejected("待提醒数量已达上限")
        }
        val duplicate = pending.firstOrNull {
            it.text == text && abs(Duration.between(it.dueAt, draft.dueAt).toMinutes()) <= 1
        }
        if (duplicate != null) {
            return ReminderResult.Scheduled(duplicate, duplicate = true)
        }

        val reminder = Reminder(
            id = UUID.randomUUID().toString(),
            companionId = companionId,
            text = text,
            dueAt = draft.dueAt,
            createdAt = now,
            repeat = draft.repeat,
            sourceText = userText
        )
        store.add(reminder)
        return ReminderResult.Scheduled(reminder)
    }

    /**
     * 提醒的送达保证：不接收 CompanionState，不参与安静时段、每日上限、最小间隔。
     * 表达层由 [ReminderPhraser] 负责，失败时用 [ReminderFallback] 兜底。
     */
    fun deliverDue(
        companionId: String,
        now: Instant,
        profile: CharacterProfile,
        state: CompanionState,
        recentMessages: List<ChatMessage>,
        phraser: ReminderPhraser? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ReminderDelivery? {
        val due = pendingDue(companionId, now)
        if (due.isEmpty()) return null

        val batch = ReminderBatch(due, profile, state, recentMessages, now)
        val phrased = runCatching { phraser?.phrase(batch) }
            .getOrNull()
            ?.trim()
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeIf { ReminderFallback.isAcceptable(it) }
        val text = phrased ?: ReminderFallback.phrase(batch, zoneId)
        markDelivered(due, now)
        val lateMinutes = due.maxOfOrNull {
            Duration.between(it.dueAt, now).toMinutes().coerceAtLeast(0)
        } ?: 0
        return ReminderDelivery(
            reminders = due,
            text = text,
            generatedBy = if (phrased != null) "model" else "fallback",
            lateMinutes = lateMinutes
        )
    }

    fun due(companionId: String, now: Instant): List<Reminder> {
        val due = pendingDue(companionId, now)
        markDelivered(due, now)
        return due.map { it.copy(status = ReminderStatus.DELIVERED) }
    }

    fun cancel(companionId: String, id: String): Boolean {
        val reminder = store.find(companionId, id) ?: return false
        if (reminder.status != ReminderStatus.PENDING) return false
        store.update(reminder.copy(status = ReminderStatus.CANCELLED))
        return true
    }

    fun pending(companionId: String): List<Reminder> = store.pending(companionId)

    fun all(companionId: String): List<Reminder> = store.all(companionId)

    private fun pendingDue(companionId: String, now: Instant): List<Reminder> =
        store.pending(companionId).filter { !it.dueAt.isAfter(now) }

    private fun markDelivered(reminders: List<Reminder>, now: Instant) {
        for (reminder in reminders) {
            store.update(reminder.copy(status = ReminderStatus.DELIVERED))
            if (reminder.repeat != ReminderRepeat.NONE) {
                var next = when (reminder.repeat) {
                    ReminderRepeat.DAILY -> reminder.dueAt.plus(1, ChronoUnit.DAYS)
                    ReminderRepeat.WEEKLY -> reminder.dueAt.plus(7, ChronoUnit.DAYS)
                    ReminderRepeat.NONE -> reminder.dueAt
                }
                val step = if (reminder.repeat == ReminderRepeat.DAILY) 1L else 7L
                while (!next.isAfter(now)) {
                    next = next.plus(step, ChronoUnit.DAYS)
                }
                store.add(
                    reminder.copy(
                        id = UUID.randomUUID().toString(),
                        dueAt = next,
                        createdAt = now,
                        status = ReminderStatus.PENDING
                    )
                )
            }
        }
    }
}
