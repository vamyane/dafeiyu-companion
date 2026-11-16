package com.dafeiyu.companion.app.runtime

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import java.io.File

object VoicePlayer {

    private var player: MediaPlayer? = null

    fun durationSeconds(file: File): Int? {
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            millis?.let { ((it + 999) / 1000).toInt().coerceAtLeast(1) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun play(
        file: File,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        release()
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        println("[Voice] play ${file.name} exists=${file.exists()} size=${file.length()}")
        runCatching {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener {
                println("[Voice] prepared duration=${it.duration}ms")
                it.start()
            }
            mediaPlayer.setOnCompletionListener {
                println("[Voice] complete")
                release()
                onComplete()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                println("[Voice] error what=$what extra=$extra")
                release()
                onError("语音播放失败")
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure {
            println("[Voice] prepare exception ${it.message}")
            release()
            onError("语音播放失败：${it.message}")
        }
    }

    fun release() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
