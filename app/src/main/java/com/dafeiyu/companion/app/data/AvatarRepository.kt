package com.dafeiyu.companion.app.data

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

class AvatarRepository(
    private val context: Context,
    private val settings: AppSettings
) {

    fun builtins(): List<String> = BUILTINS

    fun modelFor(companionId: String): Any {
        val ref = settings.avatarRef(companionId) ?: DEFAULT_REF
        return when {
            ref.startsWith(CUSTOM_PREFIX) -> {
                val file = File(avatarDir(), ref.removePrefix(CUSTOM_PREFIX))
                if (file.exists()) file else assetModel(DEFAULT_ASSET)
            }
            ref.startsWith(BUILTIN_PREFIX) -> {
                val asset = ref.removePrefix(BUILTIN_PREFIX)
                if (asset in BUILTINS) assetModel(asset) else assetModel(DEFAULT_ASSET)
            }
            else -> assetModel(DEFAULT_ASSET)
        }
    }

    fun selectedBuiltin(companionId: String): String? {
        val ref = settings.avatarRef(companionId) ?: return DEFAULT_ASSET
        return ref.takeIf { it.startsWith(BUILTIN_PREFIX) }?.removePrefix(BUILTIN_PREFIX)
    }

    fun isCustom(companionId: String): Boolean =
        settings.avatarRef(companionId)?.startsWith(CUSTOM_PREFIX) == true

    fun selectBuiltin(companionId: String, asset: String) {
        require(asset in BUILTINS) { "未知的内置头像" }
        deleteCustom(companionId)
        settings.setAvatarRef(companionId, "$BUILTIN_PREFIX$asset")
    }

    fun setCustom(companionId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES) { "图片太大，请选择 20MB 以内的图片" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "不是有效的图片" }

        val dir = avatarDir().apply { mkdirs() }
        val file = File(dir, "custom_${companionId.hashCode()}_${System.currentTimeMillis()}.img")
        file.writeBytes(bytes)
        deleteCustom(companionId, keep = file)
        settings.setAvatarRef(companionId, "$CUSTOM_PREFIX${file.name}")
    }

    fun reset(companionId: String) {
        deleteCustom(companionId)
        settings.setAvatarRef(companionId, DEFAULT_REF)
    }

    private fun deleteCustom(companionId: String, keep: File? = null) {
        val prefix = "custom_${companionId.hashCode()}_"
        avatarDir().listFiles()
            ?.filter { it.name.startsWith(prefix) && it.absolutePath != keep?.absolutePath }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun avatarDir(): File = File(context.filesDir, "avatars")

    private fun assetModel(asset: String): String = "file:///android_asset/$asset"

    companion object {
        const val DEFAULT_ASSET = "avatars/avatar_1.jpg"
        val BUILTINS = listOf(
            "avatars/avatar_1.jpg",
            "avatars/avatar_2.jpg",
            "avatars/avatar_3.jpg",
            "avatars/avatar_4.jpg",
            "avatars/avatar_5.jpg"
        )

        private const val DEFAULT_REF = "builtin:avatars/avatar_1.jpg"
        private const val BUILTIN_PREFIX = "builtin:"
        private const val CUSTOM_PREFIX = "custom:"
        private const val MAX_BYTES = 20 * 1024 * 1024
    }
}
