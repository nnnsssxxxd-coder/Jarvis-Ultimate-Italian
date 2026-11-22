package it.jarvis.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import org.json.JSONArray
import org.json.JSONObject

sealed interface InputItem {
    fun toJson(): JSONObject

    /** Messaggio. role: "user" | "assistant" | "system". Le immagini sono data URL (data:image/jpeg;base64,...). */
    data class Message(val role: String, val text: String, val imageDataUrls: List<String> = emptyList()) : InputItem {
        override fun toJson(): JSONObject {
            val o = JSONObject().put("role", role)
            if (role == "user" && imageDataUrls.isNotEmpty()) {
                val parts = JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
                imageDataUrls.forEach { parts.put(JSONObject().put("type", "input_image").put("image_url", it)) }
                o.put("content", parts)
            } else {
                o.put("content", text)
            }
            return o
        }
    }

    /** Esito di uno strumento locale, da restituire al modello. */
    data class ToolOutput(val callId: String, val output: String) : InputItem {
        override fun toJson(): JSONObject =
            JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output)
    }
}

data class ChatRequest(
    val input: List<InputItem>,
    val instructions: String? = null,
    val tools: List<JSONObject> = emptyList(),
    val previousResponseId: String? = null,
    val store: Boolean = false,
    val maxOutputTokens: Int? = null,
    val model: String? = null,
) {
    fun toJson(stream: Boolean): JSONObject {
        val o = JSONObject()
        o.put("input", JSONArray().also { arr -> input.forEach { arr.put(it.toJson()) } })
        instructions?.let { o.put("instructions", it) }
        if (tools.isNotEmpty()) o.put("tools", JSONArray().also { arr -> tools.forEach { arr.put(it) } })
        previousResponseId?.let { o.put("previous_response_id", it) }
        o.put("stream", stream)
        o.put("store", store)
        maxOutputTokens?.let { o.put("max_output_tokens", it) }
        model?.let { o.put("model", it) }
        return o
    }
}

interface ChatBackend {
    fun chatStream(request: ChatRequest): Flow<StreamEvent>
}

/** Comodo per usi semplici (es. tastiera): raccoglie solo il testo. */
suspend fun ChatBackend.completeText(instructions: String, prompt: String, maxTokens: Int = 600): String {
    val sb = chatStream(
        ChatRequest(
            input = listOf(InputItem.Message("user", prompt)),
            instructions = instructions,
            maxOutputTokens = maxTokens,
        ),
    ).fold(StringBuilder()) { acc, ev ->
        if (ev is StreamEvent.TextDelta) acc.append(ev.text)
        acc
    }
    return sb.toString().trim()
}
