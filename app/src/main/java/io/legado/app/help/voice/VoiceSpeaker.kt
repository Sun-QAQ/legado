package io.legado.app.help.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Locale

/** Never selects a network voice. Missing Chinese TTS uses bundled prompts plus visible text. */
class VoiceSpeaker(private val context: Context) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val initialized = CompletableDeferred<Boolean>()
    private var utterance: CompletableDeferred<Boolean>? = null
    private var serial = 0L
    private var closed = false
    private var player: MediaPlayer? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        handler.post { configure(status) }
    }

    private fun configure(status: Int) {
        if (closed) return
        val voice = if (status == TextToSpeech.SUCCESS) runCatching {
            tts.voices?.firstOrNull {
                it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired
            }
        }.getOrNull() else null
        val ready = voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = complete(utteranceId, true)
            @Deprecated("Legacy callback")
            override fun onError(utteranceId: String?) = complete(utteranceId, false)
            private fun complete(id: String?, success: Boolean) {
                handler.post { if (id == serial.toString()) utterance?.complete(success) }
            }
        })
        initialized.complete(ready)
    }

    suspend fun speak(text: String) {
        if (text == "我在听") { play(R.raw.voice_listening); return }
        val ready = withTimeoutOrNull(2500) { initialized.await() } == true
        if (!ready || closed) {
            if (!closed) play(when {
                text == "已取消" || text == "已取消选择" -> R.raw.voice_cancelled
                text.startsWith("已") && !text.startsWith("已请求") -> R.raw.voice_completed
                else -> R.raw.voice_notice
            })
            return
        }
        val completion = CompletableDeferred<Boolean>()
        utterance = completion
        serial++
        try {
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, serial.toString())
            val spoken = result != TextToSpeech.ERROR && withTimeoutOrNull(
                (text.length * 250L).coerceIn(5000, 45_000)
            ) { completion.await() } == true
            if (!spoken) { tts.stop(); play(R.raw.voice_notice) }
        } finally {
            tts.stop()
            utterance = null
        }
    }

    private suspend fun play(resource: Int) {
        if (closed) return
        val media = runCatching { MediaPlayer.create(context.applicationContext, resource) }.getOrNull()
        if (media == null) { tone(); return }
        player = media
        val completion = CompletableDeferred<Unit>()
        try {
            media.setOnCompletionListener { completion.complete(Unit) }
            media.setOnErrorListener { _, _, _ -> completion.complete(Unit); true }
            media.start()
            withTimeoutOrNull(6000) { completion.await() }
        } finally {
            if (player === media) { media.release(); player = null }
        }
    }

    private suspend fun tone() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull() ?: return
        try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); delay(160) }
        finally { tone.release() }
    }

    override fun close() {
        closed = true
        player?.release()
        player = null
        utterance?.complete(false)
        tts.stop()
        tts.shutdown()
    }
}
