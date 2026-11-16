package com.dafeiyu.companion.app.data

import android.content.Context
import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.CompanionState
import java.time.Instant
import java.time.ZoneId

class StateRepository(context: Context, private val zoneId: ZoneId = ZoneId.systemDefault()) {

    private val prefs = context.getSharedPreferences("companion_state", Context.MODE_PRIVATE)

    fun load(
        companionId: String,
        clinginess: Double,
        now: Instant = Instant.now()
    ): CompanionState {
        val stored = prefs.getString(key(companionId), null)
        val decoded = stored?.let {
            StateCodec.decode(companionId, it, clinginess, now)
        }
        if (decoded != null) return decoded.copy(clinginess = clinginess)

        return CompanionState(
            companionId = companionId,
            clinginess = clinginess,
            dayKey = now.atZone(zoneId).toLocalDate().toString(),
            lastTickAt = now,
            userActiveHistogram = ActiveHistogram.default()
        )
    }

    fun save(state: CompanionState) {
        prefs.edit().putString(key(state.companionId), StateCodec.encode(state)).apply()
    }

    /**
     * 读-改-写放在同一把锁里，避免 send / tick / feed 互相覆盖。
     */
    @Synchronized
    fun update(
        companionId: String,
        clinginess: Double,
        now: Instant = Instant.now(),
        transform: (CompanionState) -> CompanionState
    ): CompanionState {
        val next = transform(load(companionId, clinginess, now))
        save(next)
        return next
    }

    fun clear(companionId: String) {
        prefs.edit().remove(key(companionId)).apply()
    }

    private fun key(companionId: String): String = "state_$companionId"
}
