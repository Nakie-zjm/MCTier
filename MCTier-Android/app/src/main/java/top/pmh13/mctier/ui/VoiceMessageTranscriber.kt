package top.pmh13.mctier.ui

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

private data class DecodedSpeechAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

internal object VoiceMessageTranscriber {
    fun transcribe(context: Context, dataUrl: String, onResult: (Result<String>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(Result.failure(IllegalStateException("Android 13 or newer is required")))
            return
        }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val decoded = runCatching { decodeAudio(appContext, dataUrl) }
            withContext(Dispatchers.Main) {
                decoded.fold(
                    onSuccess = { recognizePcm(appContext, it, onResult) },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
        }
    }

    private fun decodeAudio(context: Context, dataUrl: String): DecodedSpeechAudio {
        val encoded = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        require(encoded.isNotEmpty() && encoded.size <= 2 * 1024 * 1024)
        val source = File.createTempFile("voice-source-", ".audio", context.cacheDir)
        source.writeBytes(encoded)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = inputFormat
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("Missing codec input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            require(output.size() + chunk.size <= 8 * 1024 * 1024)
                            output.write(chunk)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            require(outputEnded && output.size() > 0)
            return DecodedSpeechAudio(
                output.toByteArray(),
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            encoded.fill(0)
            source.delete()
        }
    }

    private fun recognizePcm(context: Context, audio: DecodedSpeechAudio, onResult: (Result<String>) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(Result.failure(IllegalStateException("Speech recognition is unavailable")))
            return
        }
        val source = File.createTempFile("voice-pcm-", ".raw", context.cacheDir).also { it.writeBytes(audio.pcm) }
        audio.pcm.fill(0)
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        var completed = false
        fun finish(result: Result<String>) {
            if (completed) return
            completed = true
            runCatching { recognizer.destroy() }
            runCatching { descriptor.close() }
            source.delete()
            onResult(result)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (text.isBlank()) finish(Result.failure(IllegalStateException("No speech recognized"))) else finish(Result.success(text))
            }
            override fun onError(error: Int) = finish(Result.failure(IllegalStateException("Speech recognition error $error")))
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audio.channels)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
        }
        runCatching { recognizer.startListening(intent) }.onFailure { finish(Result.failure(it)) }
    }
}
