package com.dafeiyu.companion.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

class ImageRepository(private val context: Context) {

    suspend fun saveChatImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: error("无法读取图片")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是有效的图片" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("图片解码失败")
        val scale = minOf(
            1f,
            MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        )
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val bitmap = if (width != decoded.width || height != decoded.height) {
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)
        }
        bitmap.recycle()
        file.absolutePath
    }

    private companion object {
        const val MAX_EDGE = 1280
    }
}
