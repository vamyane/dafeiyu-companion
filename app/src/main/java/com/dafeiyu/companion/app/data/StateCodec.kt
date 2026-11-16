package com.dafeiyu.companion.app.data

import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode
import com.dafeiyu.companion.model.IntentRecord
import com.dafeiyu.companion.model.OpenerRecord
import com.dafeiyu.companion.model.TimeWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

object StateCodec {

    fun encode(state: CompanionState): String = buildJsonObject {
        put("energy", state.energy)
        put("mood", state.mood)
        put("affection", state.affection)
        put("missing", state.missing)
        put("clinginess", state.clinginess)
        put("activity", state.activity.name)
        put("contactMode", state.contactMode.name)
        put("lastUserMessageAt", state.lastUserMessageAt?.toString())
        put("lastCompanionMessageAt", state.lastCompanionMessageAt?.toString())
        put("lastOpenedAt", state.lastOpenedAt?.toString())
        put("lastProactiveAt", state.lastProactiveAt?.toString())
        put("unreadCount", state.unreadCount)
        put("unansweredStreak", state.unansweredStreak)
        put("quietUntil", state.quietUntil?.toString())
        put("todaySpokenCount", state.todaySpokenCount)
        put("todayModelCalls", state.todayModelCalls)
        put("userMessageTotal", state.userMessageTotal)
        put("dayKey", state.dayKey)
        put("lastTickAt", state.lastTickAt.toString())
        put("nextProactiveCheckAt", state.nextProactiveCheckAt?.toString())
        put("openers", buildJsonArray {
            state.recentOpeners.forEach { opener ->
                add(buildJsonObject {
                    put("at", opener.at.toString())
                    put("text", opener.text)
                    put("intent", opener.intent)
                    put("window", opener.timeWindow.name)
                })
            }
        })
        put("intents", buildJsonArray {
            state.recentIntents.forEach { intent ->
                add(buildJsonObject {
                    put("dayKey", intent.dayKey)
                    put("intent", intent.intent)
                })
            }
        })
        put("histogram", buildJsonArray {
            state.userActiveHistogram.copyBins().forEach {
                add(kotlinx.serialization.json.JsonPrimitive(it))
            }
        })
    }.toString()

    fun decode(
        companionId: String,
        raw: String,
        clinginess: Double,
        now: Instant
    ): CompanionState? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        val lastTick = root.instant("lastTickAt") ?: now
        CompanionState(
            companionId = companionId,
            energy = root.int("energy", 70),
            mood = root.int("mood", 10),
            affection = root.int("affection", 0),
            missing = root.int("missing", 0),
            clinginess = clinginess,
            activity = root.enumValue("activity", Activity.IDLE),
            contactMode = root.enumValue("contactMode", ContactMode.NORMAL),
            lastUserMessageAt = root.instant("lastUserMessageAt"),
            lastCompanionMessageAt = root.instant("lastCompanionMessageAt"),
            lastOpenedAt = root.instant("lastOpenedAt"),
            lastProactiveAt = root.instant("lastProactiveAt"),
            unreadCount = root.int("unreadCount", 0),
            unansweredStreak = root.int("unansweredStreak", 0),
            quietUntil = root.instant("quietUntil"),
            todaySpokenCount = root.int("todaySpokenCount", 0),
            todayModelCalls = root.int("todayModelCalls", 0),
            userMessageTotal = root.int("userMessageTotal", 0),
            dayKey = root.string("dayKey").orEmpty(),
            lastTickAt = if (lastTick.isAfter(now)) now else lastTick,
            nextProactiveCheckAt = root.instant("nextProactiveCheckAt"),
            recentOpeners = root["openers"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val at = obj.instant("at") ?: return@mapNotNull null
                OpenerRecord(
                    at = at,
                    text = obj.string("text").orEmpty(),
                    intent = obj.string("intent").orEmpty(),
                    timeWindow = obj.enumValue("window", TimeWindow.MORNING)
                )
            }.orEmpty(),
            recentIntents = root["intents"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                IntentRecord(
                    dayKey = obj.string("dayKey").orEmpty(),
                    intent = obj.string("intent").orEmpty()
                )
            }.orEmpty(),
            userActiveHistogram = root["histogram"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?.toDoubleArray()
                ?.takeIf { it.size == ActiveHistogram.DAYS * ActiveHistogram.SLOTS }
                ?.let { ActiveHistogram.of(it) }
                ?: ActiveHistogram.empty()
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String, fallback: Int): Int =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: fallback

    private fun JsonObject.instant(key: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String, fallback: T): T =
        string(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
