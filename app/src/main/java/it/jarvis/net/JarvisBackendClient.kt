package it.jarvis.net

import android.os.SystemClock
import it.jarvis.core.ErrorLog
import it.jarvis.core.FailureCode
import it.jarvis.core.FailureMapper
import it.jarvis.core.GlobalErrorLog
import it.jarvis.core.JarvisException
import it.jarvis.core.JarvisFailure
import it.jarvis.core.Verdict
import it.jarvis.core.verdictOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fornisce il token di accesso Supabase. forceRefresh=true: rinnova la sessione (refresh token). */
interface SessionProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

data class HealthReport(
    val verdict: Verdict,
    val message: String,
    val authValid: Boolean?,
    val secretPresent: Boolean?,
    val openAiReachable: Boolean?,
    val secretSource: String?,   // solo il NOME del secret (es. "jarvis"), mai il valore
    val model: String?,
    val roundTripMs: Long,
    val openAiMs: Long?,
    val functionVersion: String?,
    val failure: JarvisFailure?,
    val atMs: Long,
) {
    val ok: Boolean get() = verdict == Verdict.CONNECTED

    companion object {
        fun failed(failure: JarvisFailure, roundTripMs: Long, now: Long = System.currentTimeMillis()) = HealthReport(
            verdict = verdictOf(failure.code), message = failure.message,
            authValid = if (failure.code == FailureCode.AUTH_INVALID) false else null,
            secretPresent = null, openAiReachable = null, secretSource = null, model = null,
            roundTripMs = roundTripMs, openAiMs = null, functionVersion = null, failure = failure, atMs = now,
        )

        fun fromJson(text: String, roundTripMs: Long, now: Long = System.currentTimeMillis()): HealthReport {
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: return failed(JarvisFailure(FailureCode.MALFORMED_RESPONSE, FailureCode.MALFORMED_RESPONSE.userMessage), roundTripMs, now)
            val checks = o.optJSONObject("checks")
            val ok = o.optBoolean("ok", false)
            val code = if (ok) null else FailureCode.fromWire(o.optString("code"))
            val message = o.optString("message").ifBlank { code?.userMessage ?: "Connessione riuscita." }
            return HealthReport(
                verdict = verdictOf(code),
                message = message,
                authValid = checks?.optString("auth")?.let { it == "valid" },
                secretPresent = checks?.optString("secret")?.let { it == "present" },
                openAiReachable = checks?.optString("openai")?.let { if (it == "reachable") true else if (it == "failed") false else null },
                secretSource = o.optString("secret_source").ifBlank { null },
                model = o.optString("model").ifBlank { null },
                roundTripMs = roundTripMs,
                openAiMs = if (o.isNull("openai_latency_ms")) null else o.optLong("openai_latency_ms"),
                functionVersion = o.optString("function_version").ifBlank { null },
                failure = code?.let { JarvisFailure(it, message) },
                atMs = now,
            )
        }
    }
}

/**
 * Client della Edge Function "jarvis-ai". Nessuna chiave OpenAI qui dentro: si invia solo il JWT dell'utente
 * e la chiave PUBBLICA di Supabase (publishable/anon), che per progettazione non è un segreto.
 */
class JarvisBackendClient(
    private val baseUrl: String,
    private val publishableKey: String,
    private val session: SessionProvider,
    private val metrics: ApiMetricsStore = GlobalApiMetrics,
    private val errorLog: ErrorLog = GlobalErrorLog,
    private val functionName: String = "jarvis-ai",
) : ChatBackend {

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        val startedAt = SystemClock.elapsedRealtime()
        var ttfb: Long? = null
        metrics.begin()
        try {
            val bytes = request.toJson(stream = true).toString().toByteArray(Charsets.UTF_8)
            var forceRefresh = false
            var authRetried = false
            while (true) {
                val token = session.accessToken(forceRefresh)
                val conn = open(token, bytes.size, stream = true, readTimeoutMs = 120_000)
                // Se la coroutine viene annullata (interruzione risposta) chiudiamo il socket: sblocca readLine().
                val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { conn.disconnect() } }
                try {
                    conn.outputStream.use { it.write(bytes) }
                    val status = conn.responseCode
                    ttfb = SystemClock.elapsedRealtime() - startedAt
                    if (status in 200..299) {
                        pump(conn)
                        metrics.finish(Outcome.SUCCESS, ttfb, SystemClock.elapsedRealtime() - startedAt)
                        return@flow
                    }
                    val failure = readFailure(conn, status)
                    if (status == 401 && !authRetried) {
                        authRetried = true
                        forceRefresh = true
                        continue
                    }
                    throw JarvisException(failure)
                } finally {
                    handle?.dispose()
                    runCatching { conn.disconnect() }
                }
            }
        } catch (e: Throwable) {
            val active = currentCoroutineContext().isActive
            val failure = if (!active || e is CancellationException) {
                JarvisFailure(FailureCode.CANCELLED, FailureCode.CANCELLED.userMessage)
            } else e.toFailure()
            metrics.finish(metrics.outcomeFor(failure), ttfb, SystemClock.elapsedRealtime() - startedAt, failure.takeIf { it.code != FailureCode.CANCELLED })
            errorLog.add(failure)
            if (!active || e is CancellationException) throw CancellationException("Risposta interrotta")
            throw if (e is JarvisException) e else JarvisException(failure)
        }
    }.flowOn(Dispatchers.IO)

    /** Richiesta REALE al backend (e quindi a OpenAI): usata dal pulsante TESTA CONNESSIONE. */
    suspend fun health(deep: Boolean = true): HealthReport = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            healthOnce(deep, forceRefresh = false, t0)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val failure = e.toFailure()
            errorLog.add(failure)
            HealthReport.failed(failure, SystemClock.elapsedRealtime() - t0)
        }
    }

    private suspend fun healthOnce(deep: Boolean, forceRefresh: Boolean, t0: Long): HealthReport {
        val token = session.accessToken(forceRefresh)
        val bytes = JSONObject().put("action", "health").put("deep", deep).toString().toByteArray(Charsets.UTF_8)
        val conn = open(token, bytes.size, stream = false, readTimeoutMs = 30_000)
        try {
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().take(20_000)
            val rtt = SystemClock.elapsedRealtime() - t0
            if (status == 401 && !forceRefresh) return healthOnce(deep, forceRefresh = true, t0)
            if (status in 200..299) return HealthReport.fromJson(text, rtt)
            val failure = FailureMapper.fromHttp(status, text, conn.getHeaderField("Retry-After")?.toLongOrNull())
            errorLog.add(failure)
            return HealthReport.failed(failure, rtt)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(token: String, bodySize: Int, stream: Boolean, readTimeoutMs: Int): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}/functions/v1/$functionName")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setFixedLengthStreamingMode(bodySize)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            setRequestProperty("Accept-Encoding", "identity") // niente gzip: non ritarda i chunk dello streaming
        }
    }

    private fun readFailure(conn: HttpURLConnection, status: Int): JarvisFailure {
        val text = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
            .getOrNull().orEmpty().take(4000)
        return FailureMapper.fromHttp(status, text, conn.getHeaderField("Retry-After")?.toLongOrNull())
    }

    private suspend fun FlowCollector<StreamEvent>.pump(conn: HttpURLConnection) {
        val parser = SseParser()
        var finished = false
        suspend fun handle(payload: String) {
            when (val ev = StreamEventMapper.map(payload) ?: return) {
                is StreamEvent.Failed -> throw JarvisException(
                    JarvisFailure(FailureCode.fromWire(ev.code).takeIf { it != FailureCode.UNKNOWN } ?: FailureCode.OPENAI_UNAVAILABLE, ev.message),
                )
                is StreamEvent.Completed, is StreamEvent.Incomplete -> { finished = true; emit(ev) }
                else -> emit(ev)
            }
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                parser.feed(line)?.let { handle(it) }
            }
            parser.flush()?.let { handle(it) }
        }
        if (!finished) {
            throw JarvisException(JarvisFailure(FailureCode.MALFORMED_RESPONSE, "Flusso interrotto prima del completamento."))
        }
    }

    private fun Throwable.toFailure(): JarvisFailure = when (this) {
        is JarvisException -> failure
        is SocketTimeoutException -> JarvisFailure(FailureCode.NETWORK_TIMEOUT, FailureCode.NETWORK_TIMEOUT.userMessage)
        is UnknownHostException, is ConnectException, is SSLException ->
            JarvisFailure(FailureCode.NETWORK_OFFLINE, FailureCode.NETWORK_OFFLINE.userMessage)
        is IOException -> JarvisFailure(FailureCode.NETWORK_OFFLINE, "Connessione interrotta (${javaClass.simpleName}).")
        else -> JarvisFailure(FailureCode.UNKNOWN, "Errore inatteso (${javaClass.simpleName}).")
    }
}
