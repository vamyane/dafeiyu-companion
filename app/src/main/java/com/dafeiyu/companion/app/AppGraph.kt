package com.dafeiyu.companion.app

import android.content.Context
import com.dafeiyu.companion.app.data.AppSettings
import com.dafeiyu.companion.app.data.AvatarRepository
import com.dafeiyu.companion.app.data.CardRepository
import com.dafeiyu.companion.app.data.CompanionDb
import com.dafeiyu.companion.app.data.ImageRepository
import com.dafeiyu.companion.app.data.SqliteMemoryStore
import com.dafeiyu.companion.app.data.SqliteReminderStore
import com.dafeiyu.companion.app.data.RoomStore
import com.dafeiyu.companion.app.data.StateRepository
import com.dafeiyu.companion.app.data.StickerRepository
import com.dafeiyu.companion.app.runtime.CompanionCoordinator
import com.dafeiyu.companion.app.runtime.RoomChatCoordinator
import com.dafeiyu.companion.llm.DeepSeekClient
import com.dafeiyu.companion.llm.DeepSeekConfig
import com.dafeiyu.companion.model.Clock
import com.dafeiyu.companion.state.StateEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import java.time.ZoneId

class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext
    val settings = AppSettings(appContext)
    val cards = CardRepository(appContext, settings)
    val avatars = AvatarRepository(appContext, settings)
    val images = ImageRepository(appContext)
    val stickers = StickerRepository(appContext, settings)
    val db = CompanionDb(appContext)
    val memory = SqliteMemoryStore(db)
    val reminders = SqliteReminderStore(db)
    val rooms = RoomStore(db)
    val states = StateRepository(appContext)
    val zoneId: ZoneId = ZoneId.systemDefault()
    val clock = object : Clock {
        override fun now(): Instant = Instant.now()
    }
    val stateEngine = StateEngine(zoneId)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun deepSeekClientOrNull(): DeepSeekClient? {
        val key = settings.apiKey() ?: return null
        return DeepSeekClient(
            DeepSeekConfig(
                apiKey = key,
                baseUrl = settings.baseUrl,
                model = settings.model
            )
        )
    }

    fun coordinator(): CompanionCoordinator = CompanionCoordinator(appContext, this)

    fun roomCoordinator(): RoomChatCoordinator = RoomChatCoordinator(this)
}
