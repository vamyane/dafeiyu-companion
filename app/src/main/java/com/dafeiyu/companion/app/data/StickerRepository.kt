package com.dafeiyu.companion.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

data class StickerOption(
    val id: String,
    val label: String,
    val assetPath: String? = null,
    val file: File? = null
) {
    val model: Any?
        get() = file ?: assetPath?.let { "file:///android_asset/$it" }
}

class StickerRepository(
    private val context: Context,
    private val settings: AppSettings
) {

    private val userDir: File
        get() = File(context.filesDir, "stickers").apply { mkdirs() }

    val builtins = listOf(
        StickerOption("happy", "开心、吃到好吃的", assetPath = "stickers/sticker_happy.jpg"),
        StickerOption("excited", "兴奋、想玩", assetPath = "stickers/sticker_excited.jpg"),
        StickerOption("lazy", "懒、晒太阳、不想动", assetPath = "stickers/sticker_lazy.jpg"),
        StickerOption("miss", "发呆、想你、有点安静", assetPath = "stickers/sticker_miss.jpg"),
        StickerOption("smug", "得意、逞强", assetPath = "stickers/sticker_smug.jpg"),
        StickerOption("shy", "害羞、装乖", assetPath = "stickers/sticker_shy.jpg"),
        StickerOption("bored", "无聊、放空、有点低落", assetPath = "stickers/sticker_bored.jpg"),
        StickerOption("cozy", "想被陪、暖乎乎", assetPath = "stickers/sticker_cozy.jpg")
    )

    fun userStickers(): List<StickerOption> {
        val stored = runCatching {
            Json.parseToJsonElement(settings.stickerIds ?: "[]")
                .jsonArray
                .mapNotNull { element ->
                    val obj = element as? kotlinx.serialization.json.JsonObject
                    if (obj != null) {
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val label = obj["label"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: "对方收藏的表情"
                        id to label
                    } else {
                        val id = element.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                        id to "对方收藏的表情"
                    }
                }
        }.getOrDefault(emptyList())
        return stored.mapNotNull { (id, label) ->
            val file = File(userDir, "$id.jpg")
            if (!file.exists()) null else StickerOption(id, label, file = file)
        }
    }

    fun all(): List<StickerOption> = builtins + userStickers()

    fun modelFor(id: String): Any? = all().firstOrNull { it.id == id }?.model

    fun labelOf(id: String): String = all().firstOrNull { it.id == id }?.label ?: "表情"

    fun addUserSticker(bytes: ByteArray, label: String = "对方收藏的表情"): StickerOption? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = 360
        val scale = side.toFloat() / minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val sideBitmap = Bitmap.createBitmap(
            scaled,
            ((width - side) / 2).coerceAtLeast(0),
            ((height - side) / 2).coerceAtLeast(0),
            minOf(side, width),
            minOf(side, height)
        )
        val id = "user_${System.currentTimeMillis()}"
        val file = File(userDir, "$id.jpg")
        val ok = runCatching {
            FileOutputStream(file).use { stream ->
                sideBitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            }
        }.isSuccess
        if (bitmap !== scaled) bitmap.recycle()
        if (scaled !== sideBitmap) scaled.recycle()
        sideBitmap.recycle()
        if (!ok) return null
        val entries = userStickers().map { it.id to it.label } + (id to label)
        settings.stickerIds = buildJsonArray {
            entries.forEach { (stickerId, stickerLabel) ->
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", stickerId)
                        put("label", stickerLabel)
                    }
                )
            }
        }.toString()
        return StickerOption(id, label, file = file)
    }
}
