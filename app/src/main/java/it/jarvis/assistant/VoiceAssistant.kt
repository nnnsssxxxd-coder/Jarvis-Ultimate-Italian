package it.jarvis.assistant

import android.content.Context
import it.jarvis.core.JarvisException
import it.jarvis.net.ChatBackend
import it.jarvis.net.InputItem
import it.jarvis.ui.home.CoreState
import it.jarvis.voice.Heard
import it.jarvis.voice.JarvisEars
import it.jarvis.voice.JarvisVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Who { USER, JARVIS, SYSTEM }
data class Line(val id: Long, val who: Who, val text: String)

/**
 * Il "Jarvis vero": ascolta -> capisce -> agisce -> risponde a voce.
 * Ordine: comando locale (istantaneo) -> agente AI con strumenti e ricerca web (backend Supabase/OpenAI).
 * Va creato e usato dal thread principale.
 */
class VoiceAssistant(
    context: Context,
    private val scope: CoroutineScope,
    private val backend: () -> ChatBackend?,
    actions: AndroidActions,
    private val ears: JarvisEars,
    private val voice: JarvisVoice,
) {
    private val local = LocalCommands(context, actions)
    private val dispatcher = ToolDispatcher(actions) { req -> askYesNo(req.description) }

    private val _core = MutableStateFlow(CoreState.IDLE)
    private val _transcript = MutableStateFlow<List<Line>>(emptyList())
    private val _continuous = MutableStateFlow(false)
    private var nextId = 1L
    private var job: Job? = null
    private val memory = ArrayList<InputItem.Message>()

    val coreState: StateFlow<CoreState> get() = _core
    val micLevel: StateFlow<Float> get() = ears.level
    val partial: StateFlow<String> get() = ears.partial
    val transcript: StateFlow<List<Line>> get() = _transcript
    val continuous: StateFlow<Boolean> get() = _continuous

    private fun add(who: Who, text: String): Long {
        val id = nextId++
        _transcript.update { (it + Line(id, who, text)).takeLast(40) }
        return id
    }

    private fun replace(id: Long, text: String) {
        _transcript.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
    }

    // ---------------------------------------------------------------- ingressi

    /** Tocco sul nucleo: se Jarvis sta lavorando lo interrompe, altrimenti ascolta una richiesta. */
    fun onCoreTap() {
        if (job?.isActive == true) interrupt() else listenOnce()
    }

    fun listenOnce() = start {
        _core.value = CoreState.LISTENING
        when (val h = ears.listen()) {
            is Heard.Text -> handle(h.text)
            Heard.Silence -> add(Who.SYSTEM, "Non ti ho sentito.")
            is Heard.Failed -> fail(h.message)
        }
    }

    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        start { handle(t) }
    }

    /** Ascolto continuo: risponde solo alle frasi che iniziano con "Jarvis" / "Ehi Jarvis". */
    fun setContinuous(on: Boolean) {
        _continuous.value = on
        if (on) {
            start {
                while (currentCoroutineContext().isActive && _continuous.value) {
                    _core.value = CoreState.LISTENING
                    when (val h = ears.listen()) {
                        is Heard.Text -> {
                            val cmd = WakeWord.strip(h.text)
                            when {
                                cmd == null -> Unit // non rivolto a Jarvis
                                cmd.isBlank() -> { add(Who.JARVIS, "Dimmi."); speakAndWait("Dimmi.") }
                                else -> handle(cmd)
                            }
                        }
                        Heard.Silence -> Unit
                        is Heard.Failed -> { _continuous.value = false; fail(h.message) }
                    }
                    delay(350)
                }
            }
        } else interrupt()
    }

    fun interrupt() {
        job?.cancel()
        job = null
        voice.stop()
        _core.value = CoreState.IDLE
    }

    fun shutdown() {
        _continuous.value = false
        job?.cancel()
        voice.shutdown()
    }

    // ---------------------------------------------------------------- logica

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        voice.stop()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Errore imprevisto (${e.javaClass.simpleName}).")
            } finally {
                // Solo se questo è ancora il job corrente (un job annullato non deve spegnere quello nuovo).
                if (!_continuous.value && job === currentCoroutineContext()[Job]) _core.value = CoreState.IDLE
            }
        }
    }

    private suspend fun handle(text: String) {
        add(Who.USER, text)
        _core.value = CoreState.THINKING

        // 1) comandi locali
        local.tryHandle(text)?.let { reply ->
            if (reply.isEmpty()) { voice.stop(); return }
            add(Who.JARVIS, reply)
            remember(text, reply)
            speakAndWait(reply)
            return
        }

        // 2) agente AI
        val be = backend()
        if (be == null) {
            val msg = "Il collegamento al cervello AI non è configurato. Posso eseguire comandi locali: ora, batteria, timer, sveglia e apertura app."
            add(Who.JARVIS, msg); speakAndWait(msg); return
        }

        val lineId = add(Who.JARVIS, "…")
        val full = StringBuilder()
        val chunker = SentenceChunker { sentence -> _core.value = CoreState.SPEAKING; voice.enqueue(sentence) }
        val history: List<InputItem> = memory.takeLast(12) + InputItem.Message("user", text)
        try {
            JarvisAgent(be, dispatcher).run(history).collect { ev ->
                when (ev) {
                    is AgentEvent.TextDelta -> { full.append(ev.text); replace(lineId, full.toString()); chunker.feed(ev.text) }
                    is AgentEvent.Searching -> if (ev.active) _core.value = CoreState.THINKING
                    is AgentEvent.ToolStarted -> _core.value = CoreState.THINKING
                    is AgentEvent.ToolFinished -> Unit
                    is AgentEvent.Sources -> Unit
                    is AgentEvent.Finished -> Unit
                }
            }
            chunker.flush()
            if (full.isBlank()) { replace(lineId, "Nessuna risposta dal modello."); speakAndWait("Nessuna risposta dal modello."); return }
            remember(text, full.toString())
            voice.awaitIdle()
        } catch (e: JarvisException) {
            chunker.flush()
            val msg = e.failure.message.ifBlank { e.failure.code.userMessage }
            replace(lineId, if (full.isBlank()) msg else full.toString())
            _core.value = CoreState.ERROR
            if (full.isBlank()) voice.enqueue(msg)
            voice.awaitIdle()
        }
    }

    private fun remember(user: String, assistant: String) {
        memory += InputItem.Message("user", user)
        memory += InputItem.Message("assistant", assistant)
        while (memory.size > 24) memory.removeAt(0)
    }

    private suspend fun speakAndWait(text: String) {
        _core.value = CoreState.SPEAKING
        voice.enqueue(text)
        voice.awaitIdle()
    }

    private fun fail(message: String) {
        add(Who.SYSTEM, message)
        _core.value = CoreState.ERROR
    }

    /** Conferma a voce per le azioni sensibili (timer, apertura link). Silenzio o dubbio = no. */
    private suspend fun askYesNo(question: String): Boolean {
        add(Who.JARVIS, question)
        speakAndWait(question)
        _core.value = CoreState.LISTENING
        return when (val h = ears.listen()) {
            is Heard.Text -> {
                add(Who.USER, h.text)
                YesNo.parse(h.text) == true
            }
            else -> false
        }.also { _core.value = CoreState.THINKING }
    }
}

object YesNo {
    private val yes = setOf("sì", "si", "certo", "ok", "okay", "va bene", "confermo", "conferma", "procedi", "fallo", "esatto", "yes")
    private val no = setOf("no", "annulla", "negativo", "lascia stare", "non farlo", "ferma", "stop", "niente")

    fun parse(text: String): Boolean? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        val words = t.split(Regex("[\\s,]+"))
        if (words.any { it in no } || t in no) return false
        if (t in yes || words.any { it in yes }) return true
        return null
    }
}

/** Spezza il testo in streaming in frasi complete, così Jarvis inizia a parlare prima che la risposta sia finita. */
class SentenceChunker(private val onSentence: (String) -> Unit) {
    private val buf = StringBuilder()

    fun feed(delta: String) {
        buf.append(delta)
        while (true) {
            val idx = indexOfBoundary(buf)
            if (idx < 0) return
            val sentence = buf.substring(0, idx + 1).trim()
            buf.delete(0, idx + 1)
            if (sentence.isNotEmpty()) onSentence(sentence)
        }
    }

    fun flush() {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isNotEmpty()) onSentence(rest)
    }

    private fun indexOfBoundary(sb: CharSequence): Int {
        for (i in sb.indices) {
            val c = sb[i]
            val isEnd = c == '.' || c == '!' || c == '?' || c == '\n'
            if (!isEnd) continue
            // "3.5", "e.g." : il punto tra cifre non chiude la frase; serve uno spazio/fine dopo
            if (c == '.' && i + 1 < sb.length && !sb[i + 1].isWhitespace()) continue
            if (c == '.' && i + 1 >= sb.length) return -1 // potrebbe continuare ("3" "." "5")
            if (i < 12 && c != '\n') continue // evita frammenti minuscoli ("1. ", "Sì.")
            return i
        }
        return -1
    }
}

object WakeWord {
    private val wake = Regex("""^\s*(?:ehi|hey|ok|ciao|salve)?[\s,]*(?:jarvis|giarvis|garvis|jarves|jarvi)\b[\s,.:!]*""", RegexOption.IGNORE_CASE)

    /** Comando senza la parola di attivazione; "" se c'è solo il nome; null se non è rivolto a Jarvis. */
    fun strip(text: String): String? {
        val m = wake.find(text) ?: return null
        return text.substring(m.range.last + 1).trim()
    }
}
