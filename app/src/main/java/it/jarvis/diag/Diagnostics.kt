package it.jarvis.diag

import it.jarvis.net.HealthReport
import it.jarvis.net.JarvisBackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(val running: Boolean = false, val report: HealthReport? = null)

/** Esegue la richiesta REALE al backend (e a OpenAI). Nessuna simulazione. */
class DiagnosticsController(private val backend: JarvisBackendClient) {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun runTest(scope: CoroutineScope): Job? {
        if (_state.value.running) return null
        _state.update { it.copy(running = true) }
        return scope.launch {
            try {
                val report = backend.health(deep = true)
                _state.update { DiagnosticsUiState(running = false, report = report) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }
}
