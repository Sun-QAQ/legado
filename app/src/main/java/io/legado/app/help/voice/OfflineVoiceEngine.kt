package io.legado.app.help.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.util.concurrent.CancellationException

/** Implementations own audio capture. A future network recognizer can implement this contract. */
interface VoiceRecognitionEngine : Closeable {
    fun awaitWake(active: () -> Boolean)
    fun recognize(active: () -> Boolean, hotwords: List<String> = emptyList(), partial: (String) -> Unit): String
    fun stopCapture()
}

/** All inference and release calls run on the assistant's one dedicated worker thread. */
class OfflineVoiceEngine(context: Context) : VoiceRecognitionEngine {
    private val assets = context.applicationContext.assets
    private var spotter: KeywordSpotter? = null
    private var recognizer: OnlineRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    private val vocabulary by lazy {
        assets.open("voice_assistant/asr/tokens.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBeforeLast(' ') }.toSet()
        }
    }

    private fun model(kind: String, suffix: String): OnlineModelConfig {
        val dir = "voice_assistant/$kind"
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$dir/encoder-$suffix.int8.onnx",
                decoder = "$dir/decoder-$suffix.int8.onnx",
                joiner = "$dir/joiner-$suffix.int8.onnx"
            ),
            tokens = "$dir/tokens.txt",
            modelType = "zipformer",
            numThreads = 2
        )
    }

    private fun keywordSpotter(): KeywordSpotter = spotter ?: KeywordSpotter(
        assets, KeywordSpotterConfig(
            modelConfig = model("kws", "epoch-12-avg-2-chunk-16-left-64").apply {
                modelType = "zipformer2"
                numThreads = 1
            },
            keywordsFile = "voice_assistant/kws/keywords.txt"
        )
    ).also { spotter = it }

    private fun speechRecognizer(): OnlineRecognizer = recognizer ?: OnlineRecognizer(
        assets, OnlineRecognizerConfig(
            modelConfig = model("asr", "epoch-99-avg-1").apply { modelingUnit = "cjkchar" },
            decodingMethod = "modified_beam_search",
            hotwordsFile = "voice_assistant/asr/hotwords.txt",
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 6f, 0f),
                rule2 = EndpointRule(true, 0.9f, 0f),
                rule3 = EndpointRule(false, 0f, 15f)
            )
        )
    ).also { recognizer = it }

    fun prepare() { keywordSpotter(); speechRecognizer() }

    override fun awaitWake(active: () -> Boolean) {
        val kws = keywordSpotter()
        val stream = kws.createStream()
        try {
            capture(active) { samples ->
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (kws.isReady(stream) && active()) kws.decode(stream)
                kws.getResult(stream).keyword.isNotEmpty()
            }
        } finally {
            stream.release()
        }
    }

    internal fun createSpeechStream(hotwords: List<String> = emptyList()) = speechRecognizer().createStream(
        hotwords.asSequence().map(VoiceCommandParser::normalize)
            .filter { word -> word.length in 2..40 && word.none { it in "/:#@" } &&
                word.all { it.toString() in vocabulary } }
            .distinct().take(200).joinToString("/")
    )

    override fun recognize(active: () -> Boolean, hotwords: List<String>, partial: (String) -> Unit): String {
        val asr = speechRecognizer()
        val stream = createSpeechStream(hotwords)
        var text = ""
        val started = SystemClock.elapsedRealtime()
        try {
            capture(active) { samples ->
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (asr.isReady(stream) && active()) asr.decode(stream)
                val next = asr.getResult(stream).text.trim()
                if (next != text) { text = next; partial(text) }
                asr.isEndpoint(stream) || SystemClock.elapsedRealtime() - started >= 15_000
            }
            // Flush the last chunk so the last syllable is not discarded on timeout.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            stream.inputFinished()
            while (asr.isReady(stream) && active()) asr.decode(stream)
            return asr.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked again before every session.
    private fun capture(active: () -> Boolean, consume: (FloatArray) -> Boolean) {
        if (!active()) throw CancellationException()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "设备不支持语音录音" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 6400)
        )
        recorder = audio
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (!active()) throw CancellationException()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风不可用" }
            val buffer = ShortArray(1600)
            while (active()) {
                val count = audio.read(buffer, 0, buffer.size)
                if (!active()) throw CancellationException()
                check(count > 0) { "麦克风被占用或录音已中断" }
                if (consume(FloatArray(count) { buffer[it] / 32768f })) return
            }
            throw CancellationException()
        } finally {
            synchronized(this) {
                recorder = null
                runCatching { audio.stop() }
                audio.release()
            }
        }
    }

    @Synchronized
    override fun stopCapture() { runCatching { recorder?.stop() } }

    override fun close() {
        stopCapture()
        recognizer?.release()
        recognizer = null
        spotter?.release()
        spotter = null
    }

    companion object { const val SAMPLE_RATE = 16000 }
}
