package it.jarvis.assistant

import it.jarvis.core.FailureCode
import it.jarvis.core.JarvisException
import it.jarvis.core.JarvisFailure
import it.jarvis.net.ChatBackend
import it.jarvis.net.ChatRequest
import it.jarvis.net.InputItem
import it.jarvis.net.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

object JarvisPrompts {
    const val SYSTEM = """Sei JARVIS, l'assistente personale dell'utente su Android. Rispondi in italiano, in modo diretto e conciso, salvo richiesta diversa.
Distingui sempre il tipo di richiesta:
1) informativa: rispondi tu;
2) ricerca: usa web_search e indica le fonti;
3) apertura di un'app: usa open_app;
4) azione Android: usa set_timer, open_url o get_device_status;
5) operazione che richiede autorizzazione: l'app chiede conferma all'utente; se rifiuta non insistere;
6) non supportata: dillo chiaramente e proponi un'alternativa.
Regole di onestà: non dire mai di aver eseguito un'azione se l'esito dello strumento non è "executed". Con "refused", "unavailable" o "failed" riferisci l'esito con parole semplici. Non inventare dati del dispositivo, app installate o risultati di ricerca. Se non sai, dillo."""
}

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class Searching(val active: Boolean) : AgentEvent
    data class ToolStarted(val name: String, val kind: ToolKind) : AgentEvent
    data class ToolFinished(val name: String, val kind: ToolKind, val outcome: ToolOutcome) : AgentEvent
    data class Sources(val items: List<StreamEvent.Citation>) : AgentEvent
    data class Finished(val responseId: String?, val fullText: String) : AgentEvent
}

/**
 * Ciclo agente: invia -> riceve in streaming -> se il modello chiede strumenti locali li esegue (con conferma)
 * e rimanda gli esiti -> ripete (max `maxRounds`). Si interrompe annullando la coroutine che raccoglie il Flow.
 * Gli errori arrivano come JarvisException.
 */
class JarvisAgent(
    private val backend: ChatBackend,
    private val dispatcher: ToolDispatcher,
    private val maxRounds: Int = 5,
    private val serverTools: List<JSONObject> = listOf(ToolRegistry.webSearchTool()),
) {
    fun run(history: List<InputItem>, instructions: String = JarvisPrompts.SYSTEM): Flow<AgentEvent> = flow {
        val tools = serverTools + ToolRegistry.functionTools()
        val fullText = StringBuilder()
        val sources = LinkedHashMap<String, StreamEvent.Citation>()
        var input: List<InputItem> = history
        var previousId: String? = null
        var round = 0

        while (true) {
            round++
            val calls = ArrayList<StreamEvent.ToolCall>()
            var responseId: String? = null
            // store=true: i turni successivi mandano solo gli esiti degli strumenti con previous_response_id.
            backend.chatStream(
                ChatRequest(
                    input = input,
                    instructions = if (previousId == null) instructions else null,
                    tools = tools,
                    previousResponseId = previousId,
                    store = true,
                ),
            ).collect { ev ->
                when (ev) {
                    is StreamEvent.TextDelta -> { fullText.append(ev.text); emit(AgentEvent.TextDelta(ev.text)) }
                    is StreamEvent.SearchState -> emit(AgentEvent.Searching(ev.active))
                    is StreamEvent.Citation -> sources.putIfAbsent(ev.url, ev)
                    is StreamEvent.ToolCall -> calls += ev
                    is StreamEvent.Completed -> responseId = ev.responseId
                    is StreamEvent.Incomplete -> Unit
                    is StreamEvent.Failed -> throw JarvisException(JarvisFailure(FailureCode.OPENAI_UNAVAILABLE, ev.message))
                }
            }

            if (calls.isEmpty()) {
                if (sources.isNotEmpty()) emit(AgentEvent.Sources(sources.values.toList()))
                emit(AgentEvent.Finished(responseId, fullText.toString()))
                return@flow
            }
            if (round >= maxRounds) {
                throw JarvisException(JarvisFailure(FailureCode.TOOL_LOOP_LIMIT, FailureCode.TOOL_LOOP_LIMIT.userMessage))
            }
            val id = responseId ?: throw JarvisException(
                JarvisFailure(FailureCode.MALFORMED_RESPONSE, "Il server non ha restituito l'id della risposta."),
            )

            val outputs = ArrayList<InputItem>()
            for (c in calls) {
                val kind = ToolRegistry.classify(c.name)
                emit(AgentEvent.ToolStarted(c.name, kind))
                val outcome = dispatcher.dispatch(ToolCall(c.callId, c.name, c.argumentsJson))
                emit(AgentEvent.ToolFinished(c.name, kind, outcome))
                outputs += InputItem.ToolOutput(c.callId, outcome.toOutputJson().toString())
            }
            previousId = id
            input = outputs
        }
    }
}
