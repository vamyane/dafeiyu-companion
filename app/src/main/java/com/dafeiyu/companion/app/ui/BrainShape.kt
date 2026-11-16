package com.dafeiyu.companion.app.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BrainShape(
    val minX: Float,
    val minY: Float,
    val spanX: Float,
    val spanY: Float,
    val points: List<Offset>
)

object BrainShapeLoader {

    fun load(context: Context): BrainShape? = runCatching {
        val text = context.assets.open("brain_shape.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        val points = root["boundary"]?.jsonArray?.mapNotNull { element ->
            val pair = element.jsonArray
            val x = pair.getOrNull(0)?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            val y = pair.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            Offset(x, y)
        }.orEmpty()
        if (points.size < 12) return@runCatching null

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY
        if (spanX <= 0f || spanY <= 0f) null else BrainShape(minX, minY, spanX, spanY, points)
    }.getOrNull()
}
