package it.jarvis.assistant

import org.json.JSONObject

/** Tipo di richiesta, per distinguere sempre cosa sta facendo JARVIS. */
enum class ToolKind { INFO, SEARCH, OPEN_APP, ANDROID_ACTION, SENSITIVE, UNSUPPORTED }

data class ToolCall(val callId: String, val name: String, val argumentsJson: String)

/** Esito onesto di uno strumento: il modello lo riceve così com'è e non può "fingere" di aver completato. */
sealed interface ToolOutcome {
    val status: String

    data class Executed(val summary: String, val data: JSONObject? = null) : ToolOutcome { override val status = "executed" }
    data class Refused(val reason: String) : ToolOutcome { override val status = "refused" }
    data class Unavailable(val reason: String) : ToolOutcome { override val status = "unavailable" }
    data class Failed(val reason: String) : ToolOutcome { override val status = "failed" }

    fun toOutputJson(): JSONObject {
        val o = JSONObject().put("status", status)
        when (this) {
            is Executed -> { o.put("summary", summary); data?.let { o.put("data", it) } }
            is Refused -> o.put("reason", reason)
            is Unavailable -> o.put("reason", reason)
            is Failed -> o.put("reason", reason)
        }
        return o
    }
}

data class ToolSpec(val name: String, val kind: ToolKind, val requiresConfirmation: Boolean, val json: JSONObject)

data class ConfirmationRequest(val toolName: String, val description: String)

object ToolRegistry {
    private fun spec(name: String, kind: ToolKind, confirm: Boolean, description: String, params: String) =
        ToolSpec(
            name, kind, confirm,
            JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject(params)),
        )

    val specs: List<ToolSpec> = listOf(
        spec(
            "open_app", ToolKind.OPEN_APP, false,
            "Apre un'app installata sul telefono dell'utente, cercandola per nome visibile (es. WhatsApp) o per package.",
            """{"type":"object","properties":{"name":{"type":"string","description":"Nome o package dell'app"}},"required":["name"]}""",
        ),
        spec(
            "get_device_status", ToolKind.INFO, false,
            "Legge lo stato reale del telefono: batteria, RAM, memoria interna. Usalo solo se l'utente lo chiede.",
            """{"type":"object","properties":{}}""",
        ),
        spec(
            "set_timer", ToolKind.ANDROID_ACTION, true,
            "Imposta un timer nell'app Orologio. Richiede la conferma dell'utente.",
            """{"type":"object","properties":{"seconds":{"type":"integer","description":"Durata in secondi (1-86400)"},"label":{"type":"string","description":"Etichetta opzionale"}},"required":["seconds"]}""",
        ),
        spec(
            "open_url", ToolKind.ANDROID_ACTION, true,
            "Apre un indirizzo http o https nel browser. Richiede la conferma dell'utente.",
            """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""",
        ),
    )

    private val byName = specs.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]

    fun classify(name: String): ToolKind = when {
        name == "web_search" || name == "web_search_preview" -> ToolKind.SEARCH
        else -> byName[name]?.kind ?: ToolKind.UNSUPPORTED
    }

    /** Ricerca web lato server (OpenAI). Se l'API rifiuta "web_search" prova "web_search_preview". */
    fun webSearchTool(type: String = "web_search"): JSONObject = JSONObject().put("type", type)

    fun functionTools(): List<JSONObject> = specs.map { it.json }
}

sealed interface ActionResult {
    data class Done(val message: String) : ActionResult
    data class NotAvailable(val reason: String) : ActionResult
    data class Failed(val reason: String) : ActionResult
}

/** Operazioni Android reali: implementate in AndroidActionsImpl, sostituibili nei test. */
interface AndroidActions {
    fun openApp(query: String): ActionResult
    fun deviceStatus(): JSONObject
    fun setTimer(seconds: Int, label: String?): ActionResult
    fun openUrl(url: String): ActionResult
}

class ToolDispatcher(
    private val actions: AndroidActions,
    private val confirm: suspend (ConfirmationRequest) -> Boolean,
) {
    suspend fun dispatch(call: ToolCall): ToolOutcome {
        val spec = ToolRegistry.find(call.name)
            ?: return ToolOutcome.Unavailable("Strumento '${call.name}' non supportato da questa versione di JARVIS.")
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return ToolOutcome.Failed("Argomenti dello strumento non validi.")

        if (spec.requiresConfirmation) {            val ok = confirm(ConfirmationRequest(spec.name, describe(spec.name, args)))
            if (!ok) return ToolOutcome.Refused("L'utente ha negato l'autorizzazione.")
        }
        return try {
            when (spec.name) {
                "open_app" -> {
                    val name = args.optString("name").trim()
                    if (name.isEmpty()) ToolOutcome.Failed("Nome app mancante.") else actions.openApp(name).toOutcome()
                }
                "get_device_status" -> ToolOutcome.Executed("Stato del dispositivo letto.", actions.deviceStatus())
                "set_timer" -> {
                    val s = args.optInt("seconds", -1)
                    if (s !in 1..86_400) ToolOutcome.Failed("Durata non valida (1-86400 secondi).")
                    else actions.setTimer(s, args.optString("label").ifBlank { null }).toOutcome()
                }
                "open_url" -> {
                    val url = args.optString("url").trim()
                    if (!(url.startsWith("https://") || url.startsWith("http://"))) ToolOutcome.Failed("Sono ammessi solo indirizzi http/https.")
                    else actions.openUrl(url).toOutcome()
                }
                else -> ToolOutcome.Unavailable("Strumento non implementato.")
            }
        } catch (e: SecurityException) {
            ToolOutcome.Unavailable("Permesso Android mancante per questa azione.")
        } catch (e: Exception) {
            ToolOutcome.Failed("Errore durante l'esecuzione (${e.javaClass.simpleName}).")
        }
    }

    private fun ActionResult.toOutcome(): ToolOutcome = when (this) {
        is ActionResult.Done -> ToolOutcome.Executed(message)
        is ActionResult.NotAvailable -> ToolOutcome.Unavailable(reason)
        is ActionResult.Failed -> ToolOutcome.Failed(reason)
    }

    private fun describe(name: String, args: JSONObject): String = when (name) {
        "set_timer" -> {
            val s = args.optInt("seconds", 0)
            "Impostare un timer da ${if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"}?"
        }
        "open_url" -> "Aprire ${args.optString("url")} nel browser?"
        else -> "Eseguire $name?"
    }
}
