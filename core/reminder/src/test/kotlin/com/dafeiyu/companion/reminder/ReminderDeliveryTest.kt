package com.dafeiyu.companion.reminder

import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.CompanionState
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReminderDeliveryTest {

    private val now: Instant = Instant.parse("2026-01-02T01:00:00Z")
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val profile = CharacterProfile(id = "companion", name = "大肥鱼")
    private val state = CompanionState(
        companionId = "companion",
        clinginess = 0.5,
        lastTickAt = now,
        dayKey = "2026-01-02",
        userActiveHistogram = ActiveHistogram.default()
    )

    @Test
    fun modelPhraseIsUsedWhenAcceptable() {
        val service = service(ReminderDraft("面试", now.plus(30, ChronoUnit.MINUTES)))
        service.handleUserMessage("companion", "九点半提醒我面试", now)
        val delivery = service.deliverDue(
            companionId = "companion",
            now = now.plus(30, ChronoUnit.MINUTES),
            profile = profile,
            state = state,
            recentMessages = emptyList(),
            phraser = object : ReminderPhraser {
                override fun phrase(batch: ReminderBatch): String = "九点半了。该起了。"
            },
            zoneId = zone
        )
        assertNotNull(delivery)
        assertEquals("九点半了。该起了。", delivery.text)
        assertEquals("model", delivery.generatedBy)
    }

    @Test
    fun fallbackKeepsTimeAndContentWhenModelFails() {
        val service = service(ReminderDraft("面试", now.plus(30, ChronoUnit.MINUTES)))
        service.handleUserMessage("companion", "九点半提醒我面试", now)
        val delivery = service.deliverDue(
            companionId = "companion",
            now = now.plus(30, ChronoUnit.MINUTES),
            profile = profile,
            state = state,
            recentMessages = emptyList(),
            phraser = object : ReminderPhraser {
                override fun phrase(batch: ReminderBatch): String? = null
            },
            zoneId = zone
        )
        assertNotNull(delivery)
        assertEquals("fallback", delivery.generatedBy)
        assertTrue(delivery.text.contains("09:30"))
        assertTrue(delivery.text.contains("面试"))
    }

    @Test
    fun multipleDueRemindersMergeIntoOneMessage() {
        val service = service(
            ReminderDraft("面试", now.plus(30, ChronoUnit.MINUTES)),
            ReminderDraft("带简历", now.plus(30, ChronoUnit.MINUTES))
        )
        service.handleUserMessage("companion", "九点半提醒我面试", now)
        service.handleUserMessage("companion", "九点半提醒我带简历", now)
        val delivery = service.deliverDue(
            companionId = "companion",
            now = now.plus(30, ChronoUnit.MINUTES),
            profile = profile,
            state = state,
            recentMessages = emptyList(),
            phraser = null,
            zoneId = zone
        )
        assertNotNull(delivery)
        assertEquals(2, delivery.reminders.size)
        assertTrue(delivery.text.contains("面试"))
        assertTrue(delivery.text.contains("带简历"))
    }

    @Test
    fun lateDeliveryMentionsLatenessInFallback() {
        val service = service(ReminderDraft("面试", now.plus(30, ChronoUnit.MINUTES)))
        service.handleUserMessage("companion", "九点半提醒我面试", now)
        val delivery = service.deliverDue(
            companionId = "companion",
            now = now.plus(60, ChronoUnit.MINUTES),
            profile = profile,
            state = state,
            recentMessages = emptyList(),
            phraser = null,
            zoneId = zone
        )
        assertNotNull(delivery)
        assertTrue(delivery.text.contains("晚了"))
    }

    @Test
    fun systemLanguageIsRejectedAndFallbackUsed() {
        val service = service(ReminderDraft("面试", now.plus(30, ChronoUnit.MINUTES)))
        service.handleUserMessage("companion", "九点半提醒我面试", now)
        val delivery = service.deliverDue(
            companionId = "companion",
            now = now.plus(30, ChronoUnit.MINUTES),
            profile = profile,
            state = state,
            recentMessages = emptyList(),
            phraser = object : ReminderPhraser {
                override fun phrase(batch: ReminderBatch): String = "提醒您：九点半面试"
            },
            zoneId = zone
        )
        assertNotNull(delivery)
        assertEquals("fallback", delivery.generatedBy)
        assertTrue(!delivery.text.contains("提醒您"))
    }

    private fun service(vararg drafts: ReminderDraft): ReminderService {
        val queue = drafts.toMutableList()
        return ReminderService(
            store = InMemoryReminderStore(),
            extractor = object : ReminderExtractor {
                override fun extract(companionId: String, userText: String, now: Instant): ReminderDraft? =
                    if (queue.isEmpty()) null else queue.removeAt(0)
            }
        )
    }
}
