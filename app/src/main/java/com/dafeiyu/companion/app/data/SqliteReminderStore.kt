package com.dafeiyu.companion.app.data

import android.content.ContentValues
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.model.ReminderRepeat
import com.dafeiyu.companion.model.ReminderStatus
import com.dafeiyu.companion.reminder.ReminderStore
import java.time.Instant

class SqliteReminderStore(private val helper: CompanionDb) : ReminderStore {

    override fun add(reminder: Reminder) {
        helper.writableDatabase.insertWithOnConflict(
            "reminders",
            null,
            values(reminder),
            4
        )
    }

    override fun pending(companionId: String): List<Reminder> = query(
        "companion_id = ? AND status = ?",
        arrayOf(companionId, ReminderStatus.PENDING.name),
        "due_at ASC"
    )

    override fun find(companionId: String, id: String): Reminder? = query(
        "companion_id = ? AND id = ?",
        arrayOf(companionId, id),
        null
    ).firstOrNull()

    override fun update(reminder: Reminder) {
        helper.writableDatabase.update(
            "reminders",
            values(reminder),
            "id = ?",
            arrayOf(reminder.id)
        )
    }

    override fun all(companionId: String): List<Reminder> = query(
        "companion_id = ?",
        arrayOf(companionId),
        "due_at ASC"
    )

    private fun query(selection: String, args: Array<String>, order: String?): List<Reminder> {
        val result = mutableListOf<Reminder>()
        helper.readableDatabase.query(
            "reminders",
            null,
            selection,
            args,
            null,
            null,
            order
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Reminder(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    companionId = cursor.getString(cursor.getColumnIndexOrThrow("companion_id")),
                    text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    dueAt = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("due_at"))),
                    createdAt = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))),
                    status = runCatching {
                        ReminderStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    }.getOrDefault(ReminderStatus.PENDING),
                    repeat = runCatching {
                        ReminderRepeat.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("repeat_rule")))
                    }.getOrDefault(ReminderRepeat.NONE),
                    sourceText = cursor.getString(cursor.getColumnIndexOrThrow("source_text"))
                )
            }
        }
        return result
    }

    private fun values(reminder: Reminder): ContentValues = ContentValues().apply {
        put("id", reminder.id)
        put("companion_id", reminder.companionId)
        put("text", reminder.text)
        put("due_at", reminder.dueAt.toEpochMilli())
        put("created_at", reminder.createdAt.toEpochMilli())
        put("status", reminder.status.name)
        put("repeat_rule", reminder.repeat.name)
        put("source_text", reminder.sourceText)
    }
}
