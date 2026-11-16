package com.dafeiyu.companion.app

import android.app.Application
import com.dafeiyu.companion.app.runtime.Notifications
import com.dafeiyu.companion.app.runtime.ReminderAlarms
import com.dafeiyu.companion.app.runtime.WorkScheduler
import kotlinx.coroutines.launch

class CompanionApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        WorkScheduler.ensurePeriodic(this)
        graph.appScope.launch {
            runCatching {
                val companionId = graph.cards.activeCard().data.name
                ReminderAlarms.scheduleAll(this@CompanionApp, graph.reminders.pending(companionId))
            }
        }
    }
}
