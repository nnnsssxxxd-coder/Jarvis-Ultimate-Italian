package it.jarvis.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ErrorEntry(val tsMs: Long, val code: FailureCode, val message: String, val requestId: String?)

/** Registro errori in memoria (ultimi 100). Non contiene mai token o chiavi. */
class ErrorLog(private val capacity: Int = 100, private val clock: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<List<ErrorEntry>>(emptyList())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    fun add(failure: JarvisFailure) {
        if (failure.code == FailureCode.CANCELLED) return
        _entries.update { (listOf(ErrorEntry(clock(), failure.code, failure.message, failure.requestId)) + it).take(capacity) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

val GlobalErrorLog = ErrorLog()
