package it.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface Heard {
    data class Text(val text: String) : Heard
    /** Silenzio o nessuna frase riconosciuta: non è un errore. */
    object Silence : Heard
    data class Failed(val message: String) : Heard
}

/** Riconoscimento vocale (SpeechRecognizer di sistema). Va usato dal thread principale tramite listen(). */
class JarvisEars(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    private val _partial = MutableStateFlow("")

    /** Livello del microfono 0..1, per animare il nucleo. */
    val level: StateFlow<Float> get() = _level
    /** Testo parziale mentre l'utente parla. */
    val partial: StateFlow<String> get() = _partial

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listen(): Heard = withContext<Heard>(Dispatchers.Main.immediate) {
        if (!isAvailable) return@withContext Heard.Failed("Il riconoscimento vocale non è disponibile su questo telefono.")
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            fun finish(r: Heard) {
                _level.value = 0f
                _partial.value = ""
                runCatching { recognizer.destroy() }
                if (cont.isActive) cont.resume(r)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { _level.value = 0f }
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(Heard.Silence)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            finish(Heard.Failed("Manca il permesso del microfono."))
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            finish(Heard.Failed("Il riconoscimento vocale richiede Internet."))
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            finish(Heard.Failed("Il riconoscimento vocale è occupato, riprova."))
                        else -> finish(Heard.Failed("Errore del riconoscimento vocale (codice $error)."))
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    finish(if (text.isEmpty()) Heard.Silence else Heard.Text(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            cont.invokeOnCancellation {
                // Il listener può essere ancora attivo: distruggere sul thread principale.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    _level.value = 0f
                    _partial.value = ""
                    runCatching { recognizer.destroy() }
                }
            }
            recognizer.startListening(intent)
        }
    }
}
