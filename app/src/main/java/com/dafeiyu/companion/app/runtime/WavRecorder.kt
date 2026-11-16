package com.dafeiyu.companion.app.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

data class RecordedVoice(
    val file: File,
    val seconds: Int
)

/**
 * 16kHz / 单声道 / 16bit PCM 的 WAV 录音器，正好是 DeepSeek ASR 接受的格式。
 */
class WavRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var output: FileOutputStream? = null
    private var target: File? = null
    private var pcmBytes = 0
    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(): File {
        check(record == null) { "已经在录音了" }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "当前设备不支持录音" }
        val bufferSize = maxOf(minBuffer * 2, 8192)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            "麦克风不可用"
        }

        val dir = File(context.filesDir, "voices").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        try {
            val stream = FileOutputStream(file)
            stream.write(ByteArray(WAV_HEADER_SIZE))
            record = audioRecord
            output = stream
            target = file
            pcmBytes = 0
            running = true
            audioRecord.startRecording()
            thread = Thread {
                val buffer = ByteArray(bufferSize)
                while (running) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    synchronized(this) {
                        runCatching { stream.write(buffer, 0, read) }
                        pcmBytes += read
                    }
                }
            }.apply {
                name = "dafeiyu-recorder"
                start()
            }
            return file
        } catch (error: Exception) {
            running = false
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { output?.close() }
            record = null
            output = null
            target = null
            file.delete()
            throw error
        }
    }

    fun stop(): RecordedVoice? {
        val audioRecord = record ?: return null
        val file = target ?: return null
        running = false
        thread?.join(1500)
        thread = null
        runCatching { audioRecord.stop() }
        runCatching { audioRecord.release() }
        record = null
        val bytes = synchronized(this) {
            val count = pcmBytes
            runCatching { output?.flush() }
            runCatching { output?.close() }
            output = null
            writeHeader(file, count)
            count
        }
        target = null
        if (bytes <= 0) {
            file.delete()
            return null
        }
        return RecordedVoice(file, (bytes / (SAMPLE_RATE * 2)).coerceAtLeast(1))
    }

    private fun writeHeader(file: File, dataSize: Int) {
        val header = ByteArray(WAV_HEADER_SIZE)
        fun putString(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + dataSize)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        header[20] = 1
        header[21] = 0
        header[22] = 1
        header[23] = 0
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        header[32] = 2
        header[33] = 0
        header[34] = 16
        header[35] = 0
        putString(36, "data")
        putInt(40, dataSize)
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
    }
}
