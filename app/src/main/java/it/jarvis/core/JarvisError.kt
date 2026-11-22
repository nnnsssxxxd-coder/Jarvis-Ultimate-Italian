package it.jarvis.core

import org.json.JSONObject

/** Codici d'errore condivisi con la Edge Function (campo "wire") più quelli generati lato client. */
enum class FailureCode(val wire: String, val retryable: Boolean, val userMessage: String) {
    BAD_REQUEST("bad_request", false, "Richiesta non valida."),
    PAYLOAD_TOO_LARGE("payload_too_large", false, "Richiesta troppo grande."),
    METHOD_NOT_ALLOWED("method_not_allowed", false, "Metodo non consentito."),
    AUTH_INVALID("auth_invalid", false, "Sessione non valida o scaduta."),
    AUTH_UNAVAILABLE("auth_unavailable", true, "Servizio di autenticazione Supabase non raggiungibile."),
    FORBIDDEN("forbidden", false, "Operazione non consentita."),
    RATE_LIMITED("rate_limited", true, "Troppe richieste: riprova tra poco."),
    QUOTA_EXCEEDED("quota_exceeded", false, "Quota o credito OpenAI esaurito."),
    SECRET_MISSING("secret_missing", false, "Secret OpenAI mancante sul server."),
    OPENAI_KEY_REJECTED("openai_key_rejected", false, "OpenAI ha rifiutato la chiave configurata sul server."),
    MODEL_NOT_FOUND("model_not_found", false, "Modello OpenAI non disponibile."),
    OPENAI_UNREACHABLE("openai_unreachable", true, "OpenAI non raggiungibile dal server."),
    OPENAI_TIMEOUT("openai_timeout", true, "OpenAI non ha risposto in tempo."),
    OPENAI_UNAVAILABLE("openai_unavailable", true, "OpenAI temporaneamente non disponibile."),
    UPSTREAM_BAD_REQUEST("upstream_bad_request", false, "OpenAI ha rifiutato la richiesta."),
    SERVER_MISCONFIGURED("server_misconfigured", false, "Configurazione del server incompleta."),
    INTERNAL_ERROR("internal_error", false, "Errore interno del server."),

    // --- generati solo dal client ---
    NETWORK_OFFLINE("network_offline", true, "Nessuna connessione a Internet."),
    NETWORK_TIMEOUT("network_timeout", true, "Il server non risponde in tempo."),
    FUNCTION_NOT_FOUND("function_not_found", false, "La Edge Function jarvis-ai non risulta distribuita (HTTP 404)."),
    PLATFORM_UNAVAILABLE("platform_unavailable", true, "Supabase non riesce ad avviare la funzione (HTTP 503/546)."),
    MALFORMED_RESPONSE("malformed_response", false, "Risposta del server non interpretabile."),
    TOOL_LOOP_LIMIT("tool_loop_limit", false, "Troppi passaggi consecutivi di strumenti."),
    CANCELLED("cancelled", false, "Operazione interrotta."),
    UNKNOWN("unknown", false, "Errore sconosciuto.");

    companion object {
        fun fromWire(wire: String?): FailureCode = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

data class JarvisFailure(
    val code: FailureCode,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    val requestId: String? = null,
)

class JarvisException(val failure: JarvisFailure) : Exception(failure.message)

/** Verdetto mostrato nel pannello Diagnostica. */
enum class Verdict(val label: String) {
    CONNECTED("Connessione riuscita"),
    SECRET_MISSING("Secret mancante"),
    AUTH_INVALID("Autenticazione non valida"),
    OPENAI_UNREACHABLE("OpenAI non raggiungibile"),
    OPENAI_KEY_REJECTED("Chiave OpenAI rifiutata"),
    RATE_LIMIT("Limite API raggiunto"),
    BACKEND_DOWN("Backend non disponibile"),
    OFFLINE("Dispositivo offline"),
    INTERNAL_ERROR("Errore interno"),
}

fun verdictOf(code: FailureCode?): Verdict = when (code) {
    null -> Verdict.CONNECTED
    FailureCode.SECRET_MISSING -> Verdict.SECRET_MISSING
    FailureCode.AUTH_INVALID, FailureCode.FORBIDDEN -> Verdict.AUTH_INVALID
    FailureCode.OPENAI_UNREACHABLE, FailureCode.OPENAI_TIMEOUT, FailureCode.OPENAI_UNAVAILABLE -> Verdict.OPENAI_UNREACHABLE
    FailureCode.OPENAI_KEY_REJECTED -> Verdict.OPENAI_KEY_REJECTED
    FailureCode.RATE_LIMITED, FailureCode.QUOTA_EXCEEDED -> Verdict.RATE_LIMIT
    FailureCode.NETWORK_OFFLINE, FailureCode.NETWORK_TIMEOUT -> Verdict.OFFLINE
    FailureCode.FUNCTION_NOT_FOUND, FailureCode.PLATFORM_UNAVAILABLE, FailureCode.AUTH_UNAVAILABLE -> Verdict.BACKEND_DOWN
    else -> Verdict.INTERNAL_ERROR
}

/** Trasforma una risposta HTTP non-2xx in un errore tipizzato. Pura: testabile su JVM. */
object FailureMapper {
    fun fromHttp(status: Int, body: String?, retryAfterHeaderSeconds: Long? = null): JarvisFailure {
        val parsed = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val err = parsed?.optJSONObject("error")
        if (err != null && err.has("code")) {
            val code = FailureCode.fromWire(err.optString("code"))
            val retryMs = err.optLong("retry_after_ms", -1L).takeIf { it >= 0 }
                ?: retryAfterHeaderSeconds?.let { it * 1000 }
            return JarvisFailure(
                code = code,
                message = err.optString("message").ifBlank { code.userMessage },
                httpStatus = status,
                retryAfterMs = retryMs,
                requestId = err.optString("request_id").ifBlank { null },
            )
        }
        // Risposta NON prodotta dalla nostra funzione: gateway Supabase, proxy, captive portal...
        val snippet = body.orEmpty().trim().replace(Regex("\\s+"), " ").take(160)
        fun f(code: FailureCode, extra: String? = null) = JarvisFailure(
            code = code,
            message = listOfNotNull(code.userMessage, extra).joinToString(" "),
            httpStatus = status,
            retryAfterMs = retryAfterHeaderSeconds?.let { it * 1000 },
        )
        return when (status) {
            401 -> f(FailureCode.AUTH_INVALID, "(rifiutato dal gateway)")
            403 -> f(FailureCode.FORBIDDEN)
            404 -> f(FailureCode.FUNCTION_NOT_FOUND)
            413 -> f(FailureCode.PAYLOAD_TOO_LARGE)
            429 -> f(FailureCode.RATE_LIMITED)
            503, 546 -> f(FailureCode.PLATFORM_UNAVAILABLE, snippet.ifBlank { null })
            in 500..599 -> f(FailureCode.INTERNAL_ERROR, snippet.ifBlank { null })
            else -> f(FailureCode.UNKNOWN, "HTTP $status")
        }
    }
}
