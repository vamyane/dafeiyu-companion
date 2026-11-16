package com.dafeiyu.companion.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dafeiyu.companion.app.CompanionApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        WorkScheduler.ensurePeriodic(context)
        WorkScheduler.runNow(context)
        val app = context.applicationContext as? CompanionApp ?: return
        runCatching {
            val companionId = app.graph.cards.activeCard().data.name
            ReminderAlarms.scheduleAll(context, app.graph.reminders.pending(companionId))
        }
    }
}
