package com.dafeiyu.companion.app.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.model.ReminderStatus

object ReminderAlarms {

    const val ACTION_DUE = "com.dafeiyu.companion.REMINDER_DUE"
    const val EXTRA_ID = "reminder_id"

    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.status != ReminderStatus.PENDING) return
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = pendingIntent(context, reminder.id)
        val triggerAt = reminder.dueAt.toEpochMilli()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_DUE
                putExtra(EXTRA_ID, reminderId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            manager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun scheduleAll(context: Context, reminders: List<Reminder>) {
        reminders.forEach { schedule(context, it) }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pendingIntent(context: Context, reminderId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_DUE
                putExtra(EXTRA_ID, reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
