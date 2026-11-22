package it.jarvis

import it.jarvis.assistant.ActionResult
import it.jarvis.assistant.AgentEvent
import it.jarvis.assistant.AndroidActions
import it.jarvis.assistant.JarvisAgent
import it.jarvis.assistant.ToolCall
import it.jarvis.assistant.ToolDispatcher
import it.jarvis.assistant.ToolKind
import it.jarvis.assistant.ToolOutcome
import it.jarvis.assistant.ToolRegistry
import it.jarvis.core.FailureCode
import it.jarvis.core.JarvisException
import it.jarvis.net.ChatBackend
import it.jarvis.net.ChatRequest
import it.jarvis.net.InputItem
import it.jarvis.net.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeActions : AndroidActions {
    val calls = mutableListOf<String>()
    var openResult: ActionResult = ActionResult.Done("avvio inviato")
    var throwSecurity = false
    override fun openApp(query: String): ActionResult { calls += "open:$query"; if (throwSecurity) throw SecurityException("no"); return openResult }
    override fun deviceStatus(): JSONObject { calls += "status"; return JSONObject().put("battery_percent", 80) }
    override fun setTimer(seconds: Int, label: String?): ActionResult { calls += "timer:$seconds"; return ActionResult.Done("timer inviato") }
    override fun openUrl(url: String): ActionResult { calls += "url:$url"; return ActionResult.Done("url inviato") }
}

class ToolDispatcherTest {
    private val actions = FakeActions()
    private fun dispatcher(confirm: Boolean = true, asked: MutableList<String>? = null) =
        ToolDispatcher(actions) { req -> asked?.add(req.description); confirm }

    private fun call(name: String, args: String) = ToolCall("c1", name, args)

    @Test fun `apre un'app senza chiedere conferma`() = runBlocking {
        val asked = mutableListOf<String>()
        val out = dispatcher(asked = asked).dispatch(call("open_app", """{"name":"WhatsApp"}"""))
        assertTrue(out is ToolOutcome.Executed)
        assertEquals(listOf("open:WhatsApp"), actions.calls)
        assertTrue(asked.isEmpty())
    }

    @Test fun `timer: se l'utente nega non si esegue nulla`() = runBlocking {
        val out = dispatcher(confirm = false).dispatch(call("set_timer", """{"seconds":300}"""))
        assertTrue(out is ToolOutcome.Refused)
        assertTrue(actions.calls.isEmpty())
        assertEquals("refused", out.toOutputJson().getString("status"))
    }

    @Test fun `timer confermato viene eseguito e la domanda e leggibile`() = runBlocking {
        val asked = mutableListOf<String>()
        val out = dispatcher(asked = asked).dispatch(call("set_timer", """{"seconds":300}"""))
        assertTrue(out is ToolOutcome.Executed)
        assertEquals(listOf("timer:300"), actions.calls)
        assertTrue(asked.single().contains("5 min"))
    }

    @Test fun `strumento sconosciuto e non supportato, mai finto`() = runBlocking {
        val out = dispatcher().dispatch(call("send_sms", """{"to":"1"}"""))
        assertTrue(out is ToolOutcome.Unavailable)
        assertEquals(ToolKind.UNSUPPORTED, ToolRegistry.classify("send_sms"))
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun `argomenti non validi`() = runBlocking {
        assertTrue(dispatcher().dispatch(call("open_app", "{rotto")) is ToolOutcome.Failed)
        assertTrue(dispatcher().dispatch(call("open_app", """{"name":"  "}""")) is ToolOutcome.Failed)
        assertTrue(dispatcher().dispatch(call("set_timer", """{"seconds":0}""")) is ToolOutcome.Failed)
        assertTrue(dispatcher().dispatch(call("open_url", """{"url":"javascript:alert(1)"}""")) is ToolOutcome.Failed)
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun `app non trovata resta unavailable`() = runBlocking {
        actions.openResult = ActionResult.NotAvailable("Nessuna app corrisponde")
        val out = dispatcher().dispatch(call("open_app", """{"name":"Boh"}"""))
        assertTrue(out is ToolOutcome.Unavailable)
    }

    @Test fun `permesso mancante diventa unavailable`() = runBlocking {
        actions.throwSecurity = true
        assertTrue(dispatcher().dispatch(call("open_app", """{"name":"X"}""")) is ToolOutcome.Unavailable)
    }

    @Test fun `classificazione dei tipi di richiesta`() {
        assertEquals(ToolKind.SEARCH, ToolRegistry.classify("web_search"))
        assertEquals(ToolKind.OPEN_APP, ToolRegistry.classify("open_app"))
        assertEquals(ToolKind.ANDROID_ACTION, ToolRegistry.classify("set_timer"))
        assertEquals(ToolKind.INFO, ToolRegistry.classify("get_device_status"))
        assertEquals(4, ToolRegistry.functionTools().size)
    }
}

private class FakeBackend(private val scripts: List<List<StreamEvent>>) : ChatBackend {
    val requests = mutableListOf<ChatRequest>()
    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        requests += request
        scripts.getOrElse(requests.size - 1) { scripts.last() }.forEach { emit(it) }
    }
}

class JarvisAgentTest {
    private val actions = FakeActions()
    private fun agent(b: FakeBackend, confirm: Boolean = true, rounds: Int = 5) =
        JarvisAgent(b, ToolDispatcher(actions) { confirm }, maxRounds = rounds)
    private val user = listOf<InputItem>(InputItem.Message("user", "ciao"))

    @Test fun `risposta di solo testo, una richiesta, strumenti dichiarati`() = runBlocking {
        val b = FakeBackend(listOf(listOf(StreamEvent.TextDelta("Ci"), StreamEvent.TextDelta("ao"), StreamEvent.Completed("resp_1", 1, 1))))
        val ev = agent(b).run(user).toList()
        assertEquals("Ciao", (ev.last() as AgentEvent.Finished).fullText)
        assertEquals(1, b.requests.size)
        val types = b.requests[0].tools.map { it.getString("type") }
        assertTrue("web_search" in types)
        assertEquals(4, types.count { it == "function" })
        assertTrue(b.requests[0].store)
    }

    @Test fun `strumento locale eseguito e esito rimandato con previous_response_id`() = runBlocking {
        val b = FakeBackend(
            listOf(
                listOf(StreamEvent.ToolCall("call_1", "open_app", """{"name":"WhatsApp"}"""), StreamEvent.Completed("resp_1", 1, 1)),
                listOf(StreamEvent.TextDelta("Fatto"), StreamEvent.Completed("resp_2", 1, 1)),
            ),
        )
        val ev = agent(b).run(user).toList()
        assertEquals(2, b.requests.size)
        assertEquals("resp_1", b.requests[1].previousResponseId)
        val out = b.requests[1].input.single() as InputItem.ToolOutput
        assertEquals("call_1", out.callId)
        assertEquals("executed", JSONObject(out.output).getString("status"))
        assertTrue(ev.any { it is AgentEvent.ToolStarted && it.kind == ToolKind.OPEN_APP })
        assertTrue(ev.any { it is AgentEvent.ToolFinished && it.outcome is ToolOutcome.Executed })
        assertEquals("Fatto", (ev.last() as AgentEvent.Finished).fullText)
    }

    @Test fun `se l'utente nega, il modello riceve refused e nessuna azione parte`() = runBlocking {
        val b = FakeBackend(
            listOf(
                listOf(StreamEvent.ToolCall("call_9", "set_timer", """{"seconds":60}"""), StreamEvent.Completed("resp_1", null, null)),
                listOf(StreamEvent.TextDelta("Ok, non lo imposto."), StreamEvent.Completed("resp_2", null, null)),
            ),
        )
        agent(b, confirm = false).run(user).toList()
        val out = b.requests[1].input.single() as InputItem.ToolOutput
        assertEquals("refused", JSONObject(out.output).getString("status"))
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun `fonti della ricerca web vengono raccolte senza duplicati`() = runBlocking {
        val b = FakeBackend(
            listOf(listOf(
                StreamEvent.SearchState(true), StreamEvent.SearchState(false),
                StreamEvent.TextDelta("x"),
                StreamEvent.Citation("https://a.it", "A"), StreamEvent.Citation("https://a.it", "A"), StreamEvent.Citation("https://b.it", null),
                StreamEvent.Completed("resp_1", null, null),
            )),
        )
        val ev = agent(b).run(user).toList()
        val sources = ev.filterIsInstance<AgentEvent.Sources>().single().items
        assertEquals(listOf("https://a.it", "https://b.it"), sources.map { it.url })
        assertTrue(ev.contains(AgentEvent.Searching(true)))
    }

    @Test fun `ciclo di strumenti infinito si ferma con errore tipizzato`() = runBlocking {
        val loop = listOf(StreamEvent.ToolCall("c", "get_device_status", "{}"), StreamEvent.Completed("resp_x", null, null))
        val b = FakeBackend(listOf(loop))
        try {
            agent(b, rounds = 3).run(user).toList()
            fail("doveva lanciare")
        } catch (e: JarvisException) {
            assertEquals(FailureCode.TOOL_LOOP_LIMIT, e.failure.code)
        }
        assertEquals(3, b.requests.size)
    }

    @Test fun `evento Failed del flusso diventa eccezione`() = runBlocking {
        val b = FakeBackend(listOf(listOf(StreamEvent.Failed("server_error", "boom"))))
        try {
            agent(b).run(user).toList()
            fail("doveva lanciare")
        } catch (e: JarvisException) {
            assertFalse(e.failure.message.isEmpty())
        }
    }
}
