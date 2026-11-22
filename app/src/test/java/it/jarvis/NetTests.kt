package it.jarvis

import it.jarvis.core.FailureCode
import it.jarvis.core.FailureMapper
import it.jarvis.core.JarvisFailure
import it.jarvis.core.Verdict
import it.jarvis.core.verdictOf
import it.jarvis.net.ApiMetricsStore
import it.jarvis.net.Outcome
import it.jarvis.net.SseParser
import it.jarvis.net.StreamEvent
import it.jarvis.net.StreamEventMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test fun `un evento si chiude con la riga vuota`() {
        val p = SseParser()
        assertNull(p.feed("event: response.output_text.delta"))
        assertNull(p.feed("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", p.feed(""))
        assertNull(p.feed(""))
    }

    @Test fun `data su piu righe viene unito e i commenti ignorati`() {
        val p = SseParser()
        p.feed(": keep-alive")
        p.feed("data: riga1")
        p.feed("data: riga2")
        assertEquals("riga1\nriga2", p.feed(""))
    }

    @Test fun `flush recupera l'ultimo evento senza riga vuota`() {
        val p = SseParser()
        p.feed("data: x")
        assertEquals("x", p.flush())
        assertNull(p.flush())
    }
}

class StreamEventMapperTest {
    @Test fun `delta di testo`() {
        val e = StreamEventMapper.map("""{"type":"response.output_text.delta","delta":"Ciao"}""")
        assertEquals(StreamEvent.TextDelta("Ciao"), e)
    }

    @Test fun `chiamata a funzione completata`() {
        val e = StreamEventMapper.map(
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"WhatsApp\"}"}}""",
        )
        assertEquals(StreamEvent.ToolCall("call_1", "open_app", "{\"name\":\"WhatsApp\"}"), e)
    }

    @Test fun `item non function_call viene ignorato`() {
        assertNull(StreamEventMapper.map("""{"type":"response.output_item.done","item":{"type":"message"}}"""))
    }

    @Test fun `completed con usage`() {
        val e = StreamEventMapper.map("""{"type":"response.completed","response":{"id":"resp_9","usage":{"input_tokens":10,"output_tokens":4}}}""")
        assertEquals(StreamEvent.Completed("resp_9", 10, 4), e)
    }

    @Test fun `citazione url`() {
        val e = StreamEventMapper.map("""{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://a.it","title":"A"}}""")
        assertEquals(StreamEvent.Citation("https://a.it", "A"), e)
    }

    @Test fun `ricerca web in corso e terminata`() {
        assertEquals(StreamEvent.SearchState(true), StreamEventMapper.map("""{"type":"response.web_search_call.searching"}"""))
        assertEquals(StreamEvent.SearchState(false), StreamEventMapper.map("""{"type":"response.web_search_call.completed"}"""))
    }

    @Test fun `errori del flusso`() {
        val f = StreamEventMapper.map("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}""")
        assertEquals(StreamEvent.Failed("server_error", "boom"), f)
        assertTrue(StreamEventMapper.map("""{"type":"error","message":"x"}""") is StreamEvent.Failed)
    }

    @Test fun `eventi sconosciuti, DONE e JSON rotto danno null`() {
        assertNull(StreamEventMapper.map("""{"type":"response.created"}"""))
        assertNull(StreamEventMapper.map("[DONE]"))
        assertNull(StreamEventMapper.map("{non json"))
    }
}

class FailureMapperTest {
    @Test fun `errore della nostra funzione con secret mancante`() {
        val f = FailureMapper.fromHttp(500, """{"ok":false,"error":{"code":"secret_missing","message":"manca","retryable":false,"retry_after_ms":null,"request_id":"abc"}}""")
        assertEquals(FailureCode.SECRET_MISSING, f.code)
        assertEquals("abc", f.requestId)
        assertNull(f.retryAfterMs)
        assertEquals(Verdict.SECRET_MISSING, verdictOf(f.code))
    }

    @Test fun `retry_after_ms del server ha la precedenza sull'header`() {
        val f = FailureMapper.fromHttp(429, """{"error":{"code":"rate_limited","message":"x","retry_after_ms":2500}}""", 9)
        assertEquals(FailureCode.RATE_LIMITED, f.code)
        assertEquals(2500L, f.retryAfterMs)
    }

    @Test fun `404 non JSON significa funzione non distribuita`() {
        assertEquals(FailureCode.FUNCTION_NOT_FOUND, FailureMapper.fromHttp(404, "Not Found").code)
    }

    @Test fun `503 del gateway non e un generico errore di rete`() {
        val f = FailureMapper.fromHttp(503, """{"code":"BOOT_ERROR","message":"Function failed to start"}""")
        assertEquals(FailureCode.PLATFORM_UNAVAILABLE, f.code)
        assertTrue(f.message.contains("BOOT_ERROR"))
        assertEquals(Verdict.BACKEND_DOWN, verdictOf(f.code))
    }

    @Test fun `401 del gateway`() {
        assertEquals(FailureCode.AUTH_INVALID, FailureMapper.fromHttp(401, "").code)
    }

    @Test fun `verdetti richiesti dal pannello diagnostica`() {
        assertEquals(Verdict.CONNECTED, verdictOf(null))
        assertEquals(Verdict.AUTH_INVALID, verdictOf(FailureCode.AUTH_INVALID))
        assertEquals(Verdict.OPENAI_UNREACHABLE, verdictOf(FailureCode.OPENAI_UNREACHABLE))
        assertEquals(Verdict.RATE_LIMIT, verdictOf(FailureCode.QUOTA_EXCEEDED))
        assertEquals(Verdict.INTERNAL_ERROR, verdictOf(FailureCode.INTERNAL_ERROR))
        assertEquals(Verdict.OFFLINE, verdictOf(FailureCode.NETWORK_OFFLINE))
    }
}

class ApiMetricsTest {
    private var now = 1_000_000L
    private val store = ApiMetricsStore { now }

    @Test fun `conta richieste, esiti e latenze reali`() {
        store.begin()
        assertEquals(1, store.state.value.inFlight)
        store.finish(Outcome.SUCCESS, ttfbMs = 120, durationMs = 800)
        store.begin()
        val rl = JarvisFailure(FailureCode.RATE_LIMITED, "x")
        store.finish(store.outcomeFor(rl), null, 50, rl)
        val s = store.state.value
        assertEquals(2, s.total)
        assertEquals(1, s.success)
        assertEquals(1, s.rateLimited)
        assertEquals(0, s.inFlight)
        assertEquals(120L, s.lastTtfbMs)
        assertEquals(listOf<Float?>(120f), s.latencySeries())
        assertEquals(FailureCode.RATE_LIMITED, s.lastError?.code)
    }

    @Test fun `bucket da 10 secondi`() {
        store.begin(); store.finish(Outcome.SUCCESS, 100, 4_000)
        now += 25_000
        store.begin(); store.finish(Outcome.ERROR, 100, 2_000)
        val s = store.state.value
        val busy = s.busySeconds(now, 10_000, 5)
        assertEquals(5, busy.size)
        assertEquals(2f, busy[4], 0.001f)  // l'ultimo bucket: evento appena avvenuto
        assertEquals(4f, busy[2], 0.001f)  // 25 s prima = 2 bucket indietro
        assertEquals(listOf(0f, 0f, 1f, 0f, 0f), s.outcomeBuckets(now, setOf(Outcome.SUCCESS), 10_000, 5))
    }

    @Test fun `eventi troppo vecchi escono dalla finestra`() {
        store.begin(); store.finish(Outcome.SUCCESS, 1, 1)
        now += 10 * 60_000
        assertTrue(store.state.value.outcomeBuckets(now, setOf(Outcome.SUCCESS), 10_000, 30).all { it == 0f })
    }

    @Test fun `annullata non e un errore`() {
        val c = JarvisFailure(FailureCode.CANCELLED, "x")
        assertEquals(Outcome.CANCELLED, store.outcomeFor(c))
        assertNotNull(store)
    }
}
