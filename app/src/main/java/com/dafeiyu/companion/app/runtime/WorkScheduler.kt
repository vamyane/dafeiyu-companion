package com.dafeiyu.companion.app.runtime

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val PERIODIC_WORK = "companion-periodic-tick"
    private const val ONCE_WORK = "companion-tick-now"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CompanionTickWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CompanionTickWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueReminderDelivery(context: Context, reminderId: String) {
        val request = OneTimeWorkRequestBuilder<CompanionTickWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder-$reminderId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
