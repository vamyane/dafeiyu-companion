package com.dafeiyu.companion.app.data

import android.content.Context
import com.dafeiyu.companion.card.CardCodec
import com.dafeiyu.companion.card.CharacterCard
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class CardRepository(
    private val context: Context,
    private val settings: AppSettings
) {

    data class CardSource(
        val id: String,
        val name: String,
        val path: String?,
        val card: CharacterCard,
        val builtin: Boolean
    )

    private val dir: File
        get() = File(context.filesDir, "cards").apply { mkdirs() }

    fun activeCard(): CharacterCard {
        val path = settings.activeCardPath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                runCatching { CardCodec.decode(file.readBytes(), file.name) }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        return defaultCard()
    }

    fun defaultCard(): CharacterCard {
        val file = File(dir, "default.json")
        val needsUpgrade = settings.defaultCardVersion < DEFAULT_VERSION
        val bytes = if (!file.exists() || needsUpgrade) {
            context.assets.open(DEFAULT_ASSET).use { it.readBytes() }.also { file.writeBytes(it) }
        } else {
            file.readBytes()
        }
        val card = CardCodec.decode(bytes, DEFAULT_ASSET)
        settings.defaultCardVersion = DEFAULT_VERSION
        settings.activeCardPath = file.absolutePath
        return card
    }

    fun import(bytes: ByteArray, fileName: String): CharacterCard {
        val card = CardCodec.decode(bytes, fileName)
        val file = File(dir, "card_${card.data.name.hashCode()}.json")
        file.writeText(CardCodec.encodeJson(card), Charsets.UTF_8)
        settings.activeCardPath = file.absolutePath
        return card
    }

    fun createFromText(
        name: String,
        description: String,
        firstMessage: String
    ): CharacterCard {
        val trimmedName = name.trim()
        val trimmedDescription = description.trim()
        require(trimmedName.isNotEmpty()) { "角色名不能为空" }
        require(trimmedDescription.isNotEmpty()) { "角色设定不能为空" }

        val json = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", buildJsonObject {
                put("name", trimmedName)
                put("description", trimmedDescription)
                put("personality", "")
                put("scenario", "")
                put("first_mes", firstMessage.trim())
                put("mes_example", "")
                put("system_prompt", "")
                put("post_history_instructions", "")
                put("alternate_greetings", buildJsonArray { })
                put("creator_notes", "用户在 App 内用文字创建")
                put("tags", buildJsonArray { add(JsonPrimitive("手写")) })
                put("creator", "user")
                put("character_version", "1.0")
                put("extensions", buildJsonObject {
                    put("x_companion", buildJsonObject {
                        put("version", 1)
                        put("personality", buildJsonObject { put("clinginess", 0.5) })
                        put("speech", buildJsonObject {
                            put("maxChars", 60)
                            put("maxSentences", 2)
                            put("forbidden", buildJsonArray {
                                add(JsonPrimitive("作为AI"))
                                add(JsonPrimitive("我是你的助手"))
                                add(JsonPrimitive("有什么可以帮你"))
                            })
                        })
                    })
                })
            })
        }.toString()

        return import(json.toByteArray(Charsets.UTF_8), "$trimmedName.json")
    }

    fun resetToDefault(): CharacterCard {
        settings.activeCardPath = null
        settings.companionName = ""
        return defaultCard()
    }

    /**
     * 房间创建时可选的整套角色卡：默认卡、小鲸鱼预设、以及用户导入/手写的卡。
     * 这里只读不切换当前角色，避免建群顺手改掉单聊对象。
     */
    fun availableCards(): List<CardSource> {
        val out = LinkedHashMap<String, CardSource>()
        fun offer(card: CharacterCard, path: String?, builtin: Boolean) {
            out.putIfAbsent(
                card.data.name,
                CardSource(
                    id = card.data.name,
                    name = card.data.name,
                    path = path,
                    card = card,
                    builtin = builtin
                )
            )
        }
        runCatching {
            offer(CardCodec.decode(assetsBytes(DEFAULT_ASSET), DEFAULT_ASSET), materialize(DEFAULT_ASSET, "default.json"), true)
        }
        runCatching {
            offer(CardCodec.decode(assetsBytes(PRESET_XIAO_JINGYU), PRESET_XIAO_JINGYU), materialize(PRESET_XIAO_JINGYU, "preset_xiao_jingyu.json"), true)
        }
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching { CardCodec.decode(file.readBytes(), file.name) }
                    .getOrNull()
                    ?.let { offer(it, file.absolutePath, false) }
            }
        return out.values.toList()
    }

    fun loadCard(path: String?): CharacterCard? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { CardCodec.decode(file.readBytes(), file.name) }.getOrNull()
    }

    private fun assetsBytes(asset: String): ByteArray =
        context.assets.open(asset).use { it.readBytes() }

    private fun materialize(asset: String, fileName: String): String {
        val file = File(dir, fileName)
        if (!file.exists()) file.writeBytes(assetsBytes(asset))
        return file.absolutePath
    }

    fun usePreset(preset: String): CharacterCard {
        if (preset == PRESET_DEFAULT) return resetToDefault()
        val asset = PRESET_XIAO_JINGYU
        val file = File(dir, "preset_xiao_jingyu.json")
        val bytes = context.assets.open(asset).use { it.readBytes() }
        file.writeBytes(bytes)
        settings.activeCardPath = file.absolutePath
        settings.companionName = ""
        return CardCodec.decode(bytes, asset)
    }

    private companion object {
        const val DEFAULT_ASSET = "dafeiyu.card.json"
        const val DEFAULT_VERSION = 2
        const val PRESET_DEFAULT = "default"
        const val PRESET_XIAO_JINGYU = "xiao_jingyu.card.json"
    }
}
