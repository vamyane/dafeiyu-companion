package com.dafeiyu.companion.app.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dafeiyu.companion.app.MainActivity
import com.dafeiyu.companion.app.R
import java.util.concurrent.atomic.AtomicInteger

object Notifications {

    const val CHANNEL_COMPANION = "companion_messages"
    const val CHANNEL_REMINDERS = "companion_reminders"
    const val EXTRA_OPEN_CHAT = "open_chat"

    private val nextId = AtomicInteger(1000)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COMPANION,
                "陪伴消息",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "角色主动发来的消息"
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "你让角色记下的提醒"
                setShowBadge(true)
            }
        )
    }

    fun showCompanion(context: Context, name: String, text: String) {
        show(context, CHANNEL_COMPANION, name, text, 0)
    }

    fun showReminder(context: Context, name: String, text: String, lateMinutes: Long) {
        show(context, CHANNEL_REMINDERS, name, text, lateMinutes)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun show(
        context: Context,
        channel: String,
        title: String,
        text: String,
        lateMinutes: Long
    ) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
        if (lateMinutes > 2) builder.setSubText("补发")
        NotificationManagerCompat.from(context).notify(nextId.incrementAndGet(), builder.build())
    }
}
