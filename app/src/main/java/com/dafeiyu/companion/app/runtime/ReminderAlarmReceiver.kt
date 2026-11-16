package com.dafeiyu.companion.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderAlarms.EXTRA_ID).orEmpty()
        if (reminderId.isBlank()) return
        WorkScheduler.enqueueReminderDelivery(context, reminderId)
    }
}
