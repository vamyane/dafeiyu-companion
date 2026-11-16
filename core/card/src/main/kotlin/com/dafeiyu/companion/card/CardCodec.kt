package com.dafeiyu.companion.card

import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.ScheduleWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object CardCodec {

    fun decode(bytes: ByteArray, fileName: String = ""): CharacterCard {
        val json = if (PngChunks.isPng(bytes)) {
            val texts = PngChunks.textMap(bytes)
            val encoded = texts["ccv3"] ?: texts["chara"]
                ?: throw CardParseException("PNG 中没有 chara 或 ccv3 角色卡数据")
            try {
                String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw CardParseException("角色卡 Base64 解码失败", e)
            }
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
        return decodeJson(json)
    }

    fun decodeJson(text: String): CharacterCard = try {
        parse(Json.parseToJsonElement(text).jsonObject)
    } catch (e: CardParseException) {
        throw e
    } catch (e: Exception) {
        throw CardParseException("角色卡 JSON 解析失败: ${e.message}", e)
    }

    fun encodeJson(card: CharacterCard): String = card.raw.toString()

    fun encodePng(originalPng: ByteArray, card: CharacterCard): ByteArray {
        val encoded = Base64.getEncoder()
            .encodeToString(encodeJson(card).toByteArray(StandardCharsets.UTF_8))
        return PngChunks.withText(originalPng, "chara", encoded)
    }

    private fun parse(root: JsonObject): CharacterCard {
        val spec = root.stringOrNull("spec")
        val data = root.objOrNull("data") ?: root
        val name = data.string("name").ifBlank { data.string("char_name") }
        if (name.isBlank()) throw CardParseException("角色卡缺少 name")

        val extensions = data.objOrNull("extensions") ?: JsonObject(emptyMap())
        val companionObj = extensions.objOrNull("x_companion")
            ?: data.objOrNull("x_companion")
            ?: root.objOrNull("x_companion")
        val companion = companionObj?.let(::parseCompanion)

        return CharacterCard(
            spec = spec,
            data = CardData(
                name = name,
                description = data.string("description"),
                personality = data.string("personality"),
                scenario = data.string("scenario"),
                firstMessage = data.string("first_mes").ifBlank { data.string("first_message") },
                exampleMessages = data.string("mes_example"),
                systemPrompt = data.string("system_prompt"),
                postHistoryInstructions = data.string("post_history_instructions"),
                alternateGreetings = data.stringList("alternate_greetings"),
                creatorNotes = data.string("creator_notes"),
                tags = data.stringList("tags"),
                creator = data.string("creator"),
                characterVersion = data.string("character_version"),
                worldInfo = parseWorldInfo(data),
                extensions = extensions,
                companion = companion
            ),
            raw = root
        )
    }

    private fun parseWorldInfo(data: JsonObject): List<WorldInfoEntry> {
        val book = data.objOrNull("character_book")
            ?: data.objOrNull("characterBook")
            ?: return emptyList()
        val entries = book.arrayOrNull("entries") ?: return emptyList()
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.boolOrNull("enabled") == false) return@mapNotNull null
            val keys = obj.stringList("keys")
            val content = obj.string("content")
            if (keys.isEmpty() || content.isBlank()) return@mapNotNull null
            WorldInfoEntry(keys, content, obj.boolOrNull("constant") ?: false)
        }
    }

    private fun parseCompanion(obj: JsonObject): CompanionExtension {
        val personality = obj.objOrNull("personality")
        val speechObj = obj.objOrNull("speech")
        val defaultForbidden = SpeechConfig().forbidden
        val speech = SpeechConfig(
            maxChars = speechObj?.intOrNull("maxChars") ?: 60,
            maxSentences = speechObj?.intOrNull("maxSentences") ?: 2,
            forbidden = speechObj?.stringList("forbidden")
                ?.takeIf { it.isNotEmpty() }
                ?: defaultForbidden
        )
        val schedule = obj.arrayOrNull("schedule")
            ?.mapNotNull { (it as? JsonObject)?.let(::parseScheduleWindow) }
            ?: emptyList()
        val triggers = obj.arrayOrNull("triggers")
            ?.mapNotNull { (it as? JsonObject)?.let(::parseTrigger) }
            ?: emptyList()

        return CompanionExtension(
            version = obj.intOrNull("version") ?: 1,
            clinginess = personality?.doubleOrNull("clinginess")
                ?: obj.doubleOrNull("clinginess"),
            schedule = schedule,
            triggers = triggers,
            speech = speech
        )
    }

    private fun parseScheduleWindow(obj: JsonObject): ScheduleWindow? {
        val start = parseTime(obj.string("start")) ?: return null
        val end = parseTime(obj.string("end")) ?: return null
        val activity = runCatching {
            Activity.valueOf(obj.string("activity").uppercase())
        }.getOrDefault(Activity.IDLE)
        return ScheduleWindow(start.first, start.second, end.first, end.second, activity)
    }

    private fun parseTrigger(obj: JsonObject): CompanionTrigger = CompanionTrigger(
        type = obj.string("type").ifBlank { "event" },
        match = obj.stringOrNull("match"),
        month = obj.intOrNull("month"),
        day = obj.intOrNull("day"),
        offsetHours = obj.intOrNull("offsetHours"),
        intent = obj.stringOrNull("intent")
    )

    private fun parseTime(value: String): Pair<Int, Int>? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.objOrNull(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    this[key] as? JsonArray

private fun JsonObject.stringList(key: String): List<String> =
    arrayOrNull(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
