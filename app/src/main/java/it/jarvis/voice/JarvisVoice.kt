package it.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Sintesi vocale. Le frasi si accodano; awaitIdle() attende la fine di tutto il parlato in coda. */
class JarvisVoice(context: Context) {

    private val ready = MutableStateFlow(false)
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val counter = AtomicLong()
    private var tts: TextToSpeech? = null

    /** true se il motore TTS è pronto con la lingua italiana. */
    val isReady: StateFlow<Boolean> get() = ready

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(Locale.ITALIAN)
                ready.value = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(1.0f)
                engine.setPitch(0.9f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finish(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
                })
            }
        }
    }

    private fun finish(id: String?) {
        if (id != null) pending.update { it - id }
    }

    /** Accoda una frase da pronunciare (senza interrompere quelle in corso). */
    fun enqueue(text: String) {
        val clean = text.trim()
        val engine = tts
        if (clean.isEmpty() || engine == null || !ready.value) return
        val id = "jv_${counter.incrementAndGet()}"
        pending.update { it + id }
        val r = engine.speak(clean, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    /** Sospende finché la coda è vuota. Se il TTS non è disponibile ritorna subito. */
    suspend fun awaitIdle() {
        pending.first { it.isEmpty() }
    }

    fun stop() {
        tts?.stop()
        pending.value = emptySet()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        pending.value = emptySet()
    }
}
