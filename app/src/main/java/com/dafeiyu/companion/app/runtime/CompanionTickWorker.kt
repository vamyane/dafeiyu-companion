package com.dafeiyu.companion.app.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dafeiyu.companion.app.CompanionApp

class CompanionTickWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CompanionApp ?: return Result.success()
        if (!app.graph.settings.hasApiKey()) return Result.success()
        return try {
            app.graph.coordinator().tick(
                foreground = AppRuntime.isForeground,
                opened = false
            )
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
