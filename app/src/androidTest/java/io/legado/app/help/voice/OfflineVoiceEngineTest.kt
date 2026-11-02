package io.legado.app.help.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads both shipped models on the actual Android ABI, without network or microphone access. */
@RunWith(AndroidJUnit4::class)
class OfflineVoiceEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledModelsLoadAndSilenceDoesNotWakeOrProduceCommands() {
        OfflineVoiceEngine(context).use { engine ->
            engine.prepare()
            val kwsField = OfflineVoiceEngine::class.java.getDeclaredField("spotter").apply { isAccessible = true }
            val asrField = OfflineVoiceEngine::class.java.getDeclaredField("recognizer").apply { isAccessible = true }
            val kws = kwsField.get(engine) as KeywordSpotter
            val asr = asrField.get(engine) as OnlineRecognizer
            val wakeStream = kws.createStream()
            val speechStream = asr.createStream()
            try {
                repeat(30) {
                    wakeStream.acceptWaveform(FloatArray(1600), 16000)
                    speechStream.acceptWaveform(FloatArray(1600), 16000)
                    while (kws.isReady(wakeStream)) kws.decode(wakeStream)
                    while (asr.isReady(speechStream)) asr.decode(speechStream)
                }
                assertEquals("", kws.getResult(wakeStream).keyword)
                assertEquals("", asr.getResult(speechStream).text.trim())
            } finally {
                wakeStream.release()
                speechStream.release()
            }
        }
    }

    @Test fun cancelledSessionNeverOpensMicrophone() {
        OfflineVoiceEngine(context).use { engine ->
            engine.prepare()
            try {
                engine.recognize({ false }) { fail("Cancelled session emitted text") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
        }
    }

    @Test fun recognizesSyntheticChineseWakeWordAndCommandsOffline() {
        OfflineVoiceEngine(context).use { engine ->
            engine.prepare()
            val kws = OfflineVoiceEngine::class.java.getDeclaredField("spotter").apply { isAccessible = true }
                .get(engine) as KeywordSpotter
            val asr = OfflineVoiceEngine::class.java.getDeclaredField("recognizer").apply { isAccessible = true }
                .get(engine) as OnlineRecognizer
            val stream = kws.createStream()
            var detected = ""
            try {
                for (chunk in samples("wake.wav").asList().chunked(1600)) {
                    stream.acceptWaveform(chunk.toFloatArray(), 16000)
                    while (kws.isReady(stream)) kws.decode(stream)
                    kws.getResult(stream).keyword.takeIf { it.isNotEmpty() }?.let { detected = it }
                }
                assertEquals("你好小悦", detected)
            } finally { stream.release() }
            for ((file, expected) in listOf<Pair<String, VoiceCommand?>>(
                "next_page.wav" to VoiceCommand.TurnPage(true),
                "discovery.wav" to VoiceCommand.OpenPage(true),
                "open_book.wav" to VoiceCommand.OpenBook("遮天"),
                "negated.wav" to null
            )) {
                val speech = engine.createSpeechStream(listOf("遮天"))
                try {
                    for (chunk in samples(file).asList().chunked(1600)) {
                        speech.acceptWaveform(chunk.toFloatArray(), 16000)
                        while (asr.isReady(speech)) asr.decode(speech)
                    }
                    speech.inputFinished()
                    while (asr.isReady(speech)) asr.decode(speech)
                    val text = asr.getResult(speech).text
                    assertEquals("$file -> $text", expected, VoiceCommandParser.parse(text))
                } finally { speech.release() }
            }
        }
    }

    private fun samples(file: String): FloatArray {
        val data = InstrumentationRegistry.getInstrumentation().context.assets.open("voice/$file").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= data.size) {
            val tag = String(data, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (tag == "data") {
                // One second of trailing silence flushes both streaming decoders.
                return FloatArray(size / 2 + 16000) { index ->
                    if (index < size / 2) buffer.getShort(offset + 8 + index * 2) / 32768f else 0f
                }
            }
            offset += 8 + size + (size and 1)
        }
        error("Missing WAV data: $file")
    }
}
