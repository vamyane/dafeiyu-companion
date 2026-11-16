package com.dafeiyu.companion.reminder

import com.dafeiyu.companion.model.ReminderRepeat
import com.dafeiyu.companion.model.ReminderStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderServiceTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val due: Instant = now.plus(2, ChronoUnit.HOURS)

    @Test
    fun schedulesAndDeliversOnce() {
        val service = service(ReminderDraft("面试", due))
        val result = service.handleUserMessage("companion", "两小时后提醒我面试", now)
        assertTrue(result is ReminderResult.Scheduled)
        assertEquals(1, service.pending("companion").size)

        assertTrue(service.due("companion", now).isEmpty())
        val delivered = service.due("companion", due.plusSeconds(60))
        assertEquals(1, delivered.size)
        assertEquals("面试", delivered.first().text)
        assertEquals(ReminderStatus.DELIVERED, delivered.first().status)
        assertTrue(service.due("companion", due.plusSeconds(120)).isEmpty())
    }

    @Test
    fun duplicateReminderIsNotScheduledTwice() {
        val service = service(ReminderDraft("面试", due))
        val first = service.handleUserMessage("companion", "提醒我面试", now)
        val second = service.handleUserMessage("companion", "提醒我面试", now)
        assertTrue(first is ReminderResult.Scheduled)
        assertTrue(second is ReminderResult.Scheduled)
        assertTrue((second as ReminderResult.Scheduled).duplicate)
        assertEquals(1, service.pending("companion").size)
    }

    @Test
    fun rejectsPastDue() {
        val service = service(ReminderDraft("面试", now.minusSeconds(3600)))
        val result = service.handleUserMessage("companion", "提醒我面试", now)
        assertTrue(result is ReminderResult.Rejected)
    }

    @Test
    fun cancelWorks() {
        val service = service(ReminderDraft("面试", due))
        val scheduled = service.handleUserMessage("companion", "提醒我面试", now)
        val reminder = (scheduled as ReminderResult.Scheduled).reminder
        assertTrue(service.cancel("companion", reminder.id))
        assertTrue(service.pending("companion").isEmpty())
        assertFalse(service.cancel("companion", reminder.id))
    }

    @Test
    fun dailyReminderReschedules() {
        val service = service(ReminderDraft("吃药", due, ReminderRepeat.DAILY))
        service.handleUserMessage("companion", "每天提醒我吃药", now)
        val delivered = service.due("companion", due)
        assertEquals(1, delivered.size)
        val pending = service.pending("companion")
        assertEquals(1, pending.size)
        assertEquals(due.plus(1, ChronoUnit.DAYS), pending.first().dueAt)
        assertEquals(ReminderRepeat.DAILY, pending.first().repeat)
    }

    @Test
    fun reminderDeliveryHasNoStateAndIgnoresQuietHours() {
        // 提醒服务不接收 CompanionState，因此安静时段、每日上限、最小间隔都不参与。
        val night = Instant.parse("2026-01-02T03:00:00Z")
        val service = service(ReminderDraft("吃药", night))
        service.handleUserMessage("companion", "凌晨三点提醒我吃药", now)
        val delivered = service.due("companion", night)
        assertEquals(1, delivered.size)
    }

    @Test
    fun notAReminderReturnsNotAReminder() {
        val service = ReminderService(
            store = InMemoryReminderStore(),
            extractor = object : ReminderExtractor {
                override fun extract(companionId: String, userText: String, now: Instant): ReminderDraft? = null
            }
        )
        assertEquals(
            ReminderResult.NotAReminder,
            service.handleUserMessage("companion", "今天有点累", now)
        )
    }

    private fun service(draft: ReminderDraft): ReminderService = ReminderService(
        store = InMemoryReminderStore(),
        extractor = object : ReminderExtractor {
            override fun extract(companionId: String, userText: String, now: Instant): ReminderDraft = draft
        }
    )
}
