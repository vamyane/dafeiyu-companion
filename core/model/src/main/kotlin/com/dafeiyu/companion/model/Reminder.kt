package com.dafeiyu.companion.model

import java.time.Instant

enum class ReminderStatus { PENDING, DELIVERED, CANCELLED }

enum class ReminderRepeat { NONE, DAILY, WEEKLY }

data class Reminder(
    val id: String,
    val companionId: String,
    val text: String,
    val dueAt: Instant,
    val createdAt: Instant,
    val status: ReminderStatus = ReminderStatus.PENDING,
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    val sourceText: String = ""
)
