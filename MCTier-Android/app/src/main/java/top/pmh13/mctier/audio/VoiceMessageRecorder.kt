package top.pmh13.mctier.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded PCM recording in memory; microphone samples never touch disk. */
class VoiceMessageRecorder {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recording = false
    @Volatile var seconds: Double = 0.0
        private set

    @Synchronized
    fun start() {
        check(record == null)
        val size = maxOf(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val source = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (source.state != AudioRecord.STATE_INITIALIZED) { source.release(); error("Microphone unavailable") }
        try { source.startRecording() } catch (error: Exception) { source.release(); throw error }
        record = source
        pcm.reset()
        seconds = 0.0
        recording = true
        worker = Thread {
            val buffer = ByteArray(size)
            try {
                while (recording && pcm.size() < 16000 * 2 * 60) {
                    val count = source.read(buffer, 0, minOf(buffer.size, 16000 * 2 * 60 - pcm.size()))
                    if (count <= 0) break
                    pcm.write(buffer, 0, count)
                    seconds = pcm.size() / 32000.0
                }
            } catch (_: Exception) {
                // Device removal and permission revocation must not crash the app.
            } finally {
                recording = false
            }
        }.apply { start() }
    }

    @Synchronized
    fun finish(cancel: Boolean): ByteArray? {
        recording = false
        val source = record ?: return null
        runCatching { source.stop() }
        worker?.join(1000)
        source.release()
        record = null
        worker = null
        if (cancel || seconds < 0.5) { pcm.reset(); return null }
        val samples = pcm.toByteArray()
        pcm.reset()
        val wav = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(samples.size).put(samples)
        return wav.array()
    }
}
