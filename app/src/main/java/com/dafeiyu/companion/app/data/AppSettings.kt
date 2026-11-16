package com.dafeiyu.companion.app.data

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("companion_settings", Context.MODE_PRIVATE)
    private val secure = SecureKeyStore(context)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("base_url", value).apply()

    var activeCardPath: String?
        get() = prefs.getString("active_card_path", null)
        set(value) = prefs.edit().putString("active_card_path", value).apply()

    var experimentalEnabled: Boolean
        get() = prefs.getBoolean("experimental_enabled", false)
        set(value) = prefs.edit().putBoolean("experimental_enabled", value).apply()

    var notificationAsked: Boolean
        get() = prefs.getBoolean("notification_asked", false)
        set(value) = prefs.edit().putBoolean("notification_asked", value).apply()

    var lastUiTickAt: Long
        get() = prefs.getLong("last_ui_tick_at", 0L)
        set(value) = prefs.edit().putLong("last_ui_tick_at", value).apply()

    fun apiKey(): String? = secure.loadApiKey()?.takeIf { it.isNotBlank() }

    fun hasApiKey(): Boolean = apiKey() != null

    fun saveApiKey(value: String) = secure.saveApiKey(value.trim())

    fun clearApiKey() = secure.clear()

    fun clinginess(companionId: String, fallback: Double): Double =
        prefs.getFloat("clinginess_$companionId", fallback.toFloat()).toDouble()

    fun setClinginess(companionId: String, value: Double) {
        prefs.edit().putFloat("clinginess_$companionId", value.toFloat()).apply()
    }

    fun userMessageCount(companionId: String): Int =
        prefs.getInt("user_message_count_$companionId", 0)

    fun incrementUserMessageCount(companionId: String): Int {
        val next = userMessageCount(companionId) + 1
        prefs.edit().putInt("user_message_count_$companionId", next).apply()
        return next
    }

    fun lastReflectionDay(companionId: String): String? =
        prefs.getString("last_reflection_day_$companionId", null)

    fun setLastReflectionDay(companionId: String, dayKey: String) {
        prefs.edit().putString("last_reflection_day_$companionId", dayKey).apply()
    }

    fun avatarRef(companionId: String): String? =
        prefs.getString("avatar_$companionId", null)

    fun setAvatarRef(companionId: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove("avatar_$companionId") else putString("avatar_$companionId", value)
        }.apply()
    }

    var fishFood: Int
        get() = prefs.getInt("fish_food", 0)
        set(value) = prefs.edit().putInt("fish_food", value.coerceAtLeast(0)).apply()

    var lastCheckInDay: String?
        get() = prefs.getString("last_check_in_day", null)
        set(value) = prefs.edit().putString("last_check_in_day", value).apply()

    var lastFeedAffectionDay: String?
        get() = prefs.getString("last_feed_affection_day", null)
        set(value) = prefs.edit().putString("last_feed_affection_day", value).apply()

    var voiceProvider: String
        get() = prefs.getString("voice_provider", VOICE_DEEPSEEK) ?: VOICE_DEEPSEEK
        set(value) = prefs.edit().putString("voice_provider", value).apply()

    var voiceId: String
        get() = prefs.getString("voice_id", "stella") ?: "stella"
        set(value) = prefs.edit().putString("voice_id", value).apply()

    var voiceLocalUrl: String
        get() = prefs.getString("voice_local_url", "http://127.0.0.1:5050")
            ?: "http://127.0.0.1:5050"
        set(value) = prefs.edit().putString("voice_local_url", value.trim()).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", true)
        set(value) = prefs.edit().putBoolean("voice_enabled", value).apply()

    var stickersEnabled: Boolean
        get() = prefs.getBoolean("stickers_enabled", true)
        set(value) = prefs.edit().putBoolean("stickers_enabled", value).apply()

    var companionName: String
        get() = prefs.getString("companion_name", "").orEmpty()
        set(value) = prefs.edit().putString("companion_name", value.trim()).apply()

    var lastForgetDay: String?
        get() = prefs.getString("last_forget_day", null)
        set(value) = prefs.edit().putString("last_forget_day", value).apply()

    var lastMemoryCollisionAt: Long
        get() = prefs.getLong("last_memory_collision_at", 0L)
        set(value) = prefs.edit().putLong("last_memory_collision_at", value).apply()

    var defaultCardVersion: Int
        get() = prefs.getInt("default_card_version", 0)
        set(value) = prefs.edit().putInt("default_card_version", value).apply()

    var stickerIds: String?
        get() = prefs.getString("sticker_ids", null)
        set(value) = prefs.edit().putString("sticker_ids", value).apply()

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DAILY_FOOD_REWARD = 20
        const val FEED_COST = 5
        const val VOICE_DEEPSEEK = "deepseek"
        const val VOICE_LOCAL = "local"
        val MODELS = listOf("deepseek-flash", "deepseek-v4-pro")
    }
}
