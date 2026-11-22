package it.jarvis.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import it.jarvis.core.JarvisException
import it.jarvis.net.completeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tastiera JARVIS: servizio separato (InputMethodService) con interfaccia Compose.
 * L'attivazione la fa l'utente da Impostazioni (vedi ImeSetup). Nei campi password: niente AI, niente microfono,
 * niente apprendimento delle parole, niente invio di testo al backend.
 */
class JarvisInputMethodService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var ui by mutableStateOf(KeyboardUiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var aiJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var suggester: WordSuggester

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val prefs = getSharedPreferences("jarvis_ime", Context.MODE_PRIVATE)
        suggester = WordSuggester(WordSuggester.deserialize(prefs.getString("learned", null)))
        ui = ui.copy(compact = prefs.getBoolean("compact", false))
    }

    override fun onCreateInputView(): View {
        val decor = window.window!!.decorView
        ViewTreeLifecycleOwner.set(decor, this)
        ViewTreeViewModelStoreOwner.set(decor, this)
        ViewTreeSavedStateRegistryOwner.set(decor, this)
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { JarvisKeyboard(ui, ::onAction) }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val sensitive = isSensitive(info)
        ui = ui.copy(sensitiveField = sensitive, status = KbStatus.Idle, shift = autoShift(info), symbols = false)
        refreshSuggestions()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopListening()
        aiJob?.cancel()
        persistLearned()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ azioni

    private fun onAction(a: KeyAction) {
        val ic = currentInputConnection
        when (a) {
            is KeyAction.Type -> if (ic != null) {
                ic.commitText(a.text, 1)
                if (a.text.length == 1 && !a.text[0].isLetter()) learnLastWord(ic)
                if (ui.shift == Shift.ONCE) ui = ui.copy(shift = Shift.OFF)
                if (ui.status is KbStatus.Error) ui = ui.copy(status = KbStatus.Idle)
            }
            KeyAction.Space -> if (ic != null) { learnLastWord(ic); ic.commitText(" ", 1) }
            KeyAction.Backspace -> if (ic != null) {
                if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
            }
            KeyAction.Enter -> pressEnter(ic)
            KeyAction.ToggleShift -> ui = ui.copy(
                shift = when (ui.shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF },
            )
            KeyAction.ToggleSymbols -> ui = ui.copy(symbols = !ui.symbols)
            KeyAction.ToggleCompact -> {
                ui = ui.copy(compact = !ui.compact)
                getSharedPreferences("jarvis_ime", Context.MODE_PRIVATE).edit().putBoolean("compact", ui.compact).apply()
            }
            KeyAction.SwitchKeyboard -> ImeSetup.showPicker(this)
            KeyAction.Mic -> toggleMic()
            KeyAction.AiRewrite -> aiRewrite()
            is KeyAction.PickSuggestion -> if (ic != null) {
                val partial = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty().takeLastWhile { it.isLetter() }
                ic.beginBatchEdit()
                ic.deleteSurroundingText(partial.length, 0)
                ic.commitText(a.word + " ", 1)
                ic.endBatchEdit()
            }
        }
        refreshSuggestions()
    }

    private fun pressEnter(ic: android.view.inputmethod.InputConnection?) {
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (ic != null) learnLastWord(ic)
        if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else ic?.performEditorAction(action)
    }

    private fun refreshSuggestions() {
        if (ui.sensitiveField) { if (ui.suggestions.isNotEmpty()) ui = ui.copy(suggestions = emptyList()); return }
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() }
        val next = if (word.isEmpty()) emptyList() else suggester.suggest(word, 3)
        if (next != ui.suggestions) ui = ui.copy(suggestions = next)
    }

    private fun learnLastWord(ic: android.view.inputmethod.InputConnection) {
        if (ui.sensitiveField) return
        val word = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty().takeLastWhile { it.isLetter() }
        if (word.isNotEmpty()) suggester.learn(word)
    }

    private fun persistLearned() {
        if (ui.sensitiveField) return
        getSharedPreferences("jarvis_ime", Context.MODE_PRIVATE).edit().putString("learned", suggester.serialize()).apply()
    }

    private fun autoShift(info: EditorInfo?): Shift =
        if (info != null && info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        ) Shift.ONCE else Shift.OFF

    private fun isSensitive(info: EditorInfo?): Boolean {
        val t = info?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // ------------------------------------------------------------------ AI

    private fun aiRewrite() {
        if (ui.sensitiveField) return
        val backend = JarvisServices.backend
        if (backend == null) { showError("Backend JARVIS non pronto: apri prima l'app."); return }
        val before = currentInputConnection?.getTextBeforeCursor(1500, 0)?.toString().orEmpty()
        if (before.isBlank()) { showError("Scrivi prima del testo da migliorare."); return }
        aiJob?.cancel()
        ui = ui.copy(status = KbStatus.AiBusy)
        aiJob = scope.launch {
            try {
                val out = backend.completeText(REWRITE_INSTRUCTIONS, before)
                val ic = currentInputConnection
                // Se nel frattempo l'utente ha cambiato testo o campo, non si sovrascrive nulla.
                val stillSame = ic?.getTextBeforeCursor(1500, 0)?.toString() == before
                if (ic != null && stillSame && out.isNotBlank()) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(before.length, 0)
                    ic.commitText(out, 1)
                    ic.endBatchEdit()
                    ui = ui.copy(status = KbStatus.Idle)
                } else {
                    showError(if (out.isBlank()) "Nessun testo restituito." else "Il testo è cambiato: operazione annullata.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: JarvisException) {
                showError(e.failure.message)
            } catch (e: Exception) {
                showError("Errore AI (${e.javaClass.simpleName}).")
            }
            refreshSuggestions()
        }
    }

    private fun showError(msg: String) {
        ui = ui.copy(status = KbStatus.Error(msg))
    }

    // ------------------------------------------------------------------ voce (indicatore sempre visibile)

    private fun toggleMic() {
        if (ui.status is KbStatus.Listening) { stopListening(); return }
        if (ui.sensitiveField) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Concedi il microfono dall'app JARVIS.")
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { showError("Riconoscimento vocale non disponibile."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) currentInputConnection?.commitText("$text ", 1)
                finishListening()
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Non ho sentito nulla."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permesso microfono mancante."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Riconoscimento vocale: rete assente."
                    else -> "Errore riconoscimento vocale ($error)."
                }
                releaseRecognizer()
                showError(msg)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        ui = ui.copy(status = KbStatus.Listening)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
        )
    }

    private fun finishListening() {
        releaseRecognizer()
        ui = ui.copy(status = KbStatus.Idle)
    }

    private fun stopListening() {
        if (recognizer != null) { releaseRecognizer(); if (ui.status is KbStatus.Listening) ui = ui.copy(status = KbStatus.Idle) }
    }

    private fun releaseRecognizer() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private companion object {
        const val REWRITE_INSTRUCTIONS =
            "Correggi ortografia e grammatica del testo e rendilo chiaro e naturale, mantenendo lingua, tono e significato. " +
                "Rispondi SOLO con il testo riscritto, senza commenti, virgolette o spiegazioni."
    }
}
