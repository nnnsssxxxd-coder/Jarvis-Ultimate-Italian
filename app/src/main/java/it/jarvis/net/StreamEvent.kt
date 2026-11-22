package it.jarvis.net

import org.json.JSONObject

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class SearchState(val active: Boolean) : StreamEvent
    data class Citation(val url: String, val title: String?) : StreamEvent
    data class ToolCall(val callId: String, val name: String, val argumentsJson: String) : StreamEvent
    data class Completed(val responseId: String?, val inputTokens: Int?, val outputTokens: Int?) : StreamEvent
    data class Incomplete(val reason: String?) : StreamEvent
    data class Failed(val code: String?, val message: String) : StreamEvent
}

/** Parser SSE a righe (RFC: righe "data:", evento chiuso da riga vuota). */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** Restituisce il payload quando l'evento è completo, altrimenti null. */
    fun feed(line: String): String? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        if (line.startsWith("data:")) {
            var value = line.substring(5)
            if (value.startsWith(" ")) value = value.substring(1)
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    fun flush(): String? {
        if (!hasData) return null
        val out = data.toString()
        data.setLength(0)
        hasData = false
        return out
    }
}

/** Traduce gli eventi della Responses API in StreamEvent. Eventi sconosciuti: null (si ignorano). */
object StreamEventMapper {
    fun map(payload: String): StreamEvent? {
        if (payload == "[DONE]") return null
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            "response.output_text.delta" ->
                o.optString("delta").takeIf { it.isNotEmpty() }?.let { StreamEvent.TextDelta(it) }

            "response.web_search_call.in_progress", "response.web_search_call.searching" ->
                StreamEvent.SearchState(true)

            "response.web_search_call.completed" -> StreamEvent.SearchState(false)

            "response.output_text.annotation.added" -> {
                val a = o.optJSONObject("annotation")
                if (a != null && a.optString("type") == "url_citation" && a.optString("url").isNotEmpty()) {
                    StreamEvent.Citation(a.optString("url"), a.optString("title").ifBlank { null })
                } else null
            }

            "response.output_item.done" -> {
                val item = o.optJSONObject("item")
                if (item != null && item.optString("type") == "function_call") {
                    StreamEvent.ToolCall(
                        callId = item.optString("call_id"),
                        name = item.optString("name"),
                        argumentsJson = item.optString("arguments", "{}").ifBlank { "{}" },
                    )
                } else null
            }

            "response.completed" -> {
                val r = o.optJSONObject("response")
                val u = r?.optJSONObject("usage")
                StreamEvent.Completed(
                    responseId = r?.optString("id")?.ifBlank { null },
                    inputTokens = if (u != null && u.has("input_tokens")) u.optInt("input_tokens") else null,
                    outputTokens = if (u != null && u.has("output_tokens")) u.optInt("output_tokens") else null,
                )
            }

            "response.incomplete" ->
                StreamEvent.Incomplete(o.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason"))

            "response.failed" -> {
                val e = o.optJSONObject("response")?.optJSONObject("error")
                StreamEvent.Failed(e?.optString("code"), e?.optString("message").orEmpty().ifBlank { "Risposta fallita" })
            }

            "error" -> StreamEvent.Failed(o.optString("code").ifBlank { null }, o.optString("message").ifBlank { "Errore dal flusso" })

            else -> null
        }
    }
}
