package it.jarvis.net

import it.jarvis.core.FailureCode
import it.jarvis.core.JarvisFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Outcome { SUCCESS, ERROR, RATE_LIMITED, CANCELLED }

data class ApiEvent(val tsMs: Long, val outcome: Outcome, val ttfbMs: Long?, val durationMs: Long)

data class ApiSnapshot(
    val total: Int = 0,
    val success: Int = 0,
    val errors: Int = 0,
    val rateLimited: Int = 0,
    val inFlight: Int = 0,
    val lastTtfbMs: Long? = null,
    val lastError: JarvisFailure? = null,
    val events: List<ApiEvent> = emptyList(),
) {
    /** Ultime `limit` latenze (tempo al primo byte) in ms: solo richieste che hanno ricevuto risposta. */
    fun latencySeries(limit: Int = 60): List<Float?> =
        events.mapNotNull { it.ttfbMs?.toFloat() }.takeLast(limit)

    /** Secondi di lavoro del motore AI per bucket (durata attribuita al bucket in cui la richiesta termina). */
    fun busySeconds(nowMs: Long, bucketMs: Long = 10_000, count: Int = 30): List<Float> {
        val out = FloatArray(count)
        for (e in events) {
            val idx = bucketIndex(nowMs, e.tsMs, bucketMs, count) ?: continue
            out[idx] += (minOf(e.durationMs, bucketMs) / 1000f)
        }
        return out.map { minOf(it, bucketMs / 1000f) }
    }

    /** Conteggio richieste per esito e bucket. */
    fun outcomeBuckets(nowMs: Long, outcomes: Set<Outcome>, bucketMs: Long = 10_000, count: Int = 30): List<Float> {
        val out = FloatArray(count)
        for (e in events) {
            if (e.outcome !in outcomes) continue
            val idx = bucketIndex(nowMs, e.tsMs, bucketMs, count) ?: continue
            out[idx] += 1f
        }
        return out.toList()
    }

    private fun bucketIndex(nowMs: Long, tsMs: Long, bucketMs: Long, count: Int): Int? {
        val age = nowMs - tsMs
        if (age < 0 || age >= bucketMs * count) return null
        return count - 1 - (age / bucketMs).toInt()
    }
}

/** Statistiche reali delle richieste al backend: nessun valore inventato. */
class ApiMetricsStore(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(ApiSnapshot())
    val state: StateFlow<ApiSnapshot> = _state.asStateFlow()

    fun begin() = _state.update { it.copy(inFlight = it.inFlight + 1) }

    fun finish(outcome: Outcome, ttfbMs: Long?, durationMs: Long, failure: JarvisFailure? = null) {
        _state.update { s ->
            val ev = ApiEvent(clock(), outcome, ttfbMs, durationMs)
            s.copy(
                total = s.total + 1,
                success = s.success + if (outcome == Outcome.SUCCESS) 1 else 0,
                errors = s.errors + if (outcome == Outcome.ERROR) 1 else 0,
                rateLimited = s.rateLimited + if (outcome == Outcome.RATE_LIMITED) 1 else 0,
                inFlight = maxOf(0, s.inFlight - 1),
                lastTtfbMs = ttfbMs ?: s.lastTtfbMs,
                lastError = failure ?: s.lastError,
                events = (s.events + ev).takeLast(500),
            )
        }
    }

    fun outcomeFor(failure: JarvisFailure): Outcome = when (failure.code) {
        FailureCode.CANCELLED -> Outcome.CANCELLED
        FailureCode.RATE_LIMITED, FailureCode.QUOTA_EXCEEDED -> Outcome.RATE_LIMITED
        else -> Outcome.ERROR
    }
}

val GlobalApiMetrics = ApiMetricsStore()
