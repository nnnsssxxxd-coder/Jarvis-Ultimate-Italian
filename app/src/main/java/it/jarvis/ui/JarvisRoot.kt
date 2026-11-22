package it.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import it.jarvis.apps.AppsRepository
import it.jarvis.core.ErrorLog
import it.jarvis.diag.DiagnosticsController
import it.jarvis.diag.DiagnosticsScreen
import it.jarvis.net.ApiMetricsStore
import it.jarvis.telemetry.TelemetryHistory
import it.jarvis.ui.apps.AppsScreen
import it.jarvis.ui.home.CoreState
import it.jarvis.ui.home.HomeScreen
import it.jarvis.ui.touch.HologramTouchHost
import it.jarvis.ui.util.collectWhileStarted
import kotlinx.coroutines.flow.StateFlow

private enum class Screen { HOME, APPS, DIAGNOSTICS }

/**
 * Radice dell'interfaccia: tocco olografico globale + navigazione Home / App / Diagnostica con transizioni.
 * Esempio di uso in MainActivity:
 *   setContent { JarvisRoot(history, metrics, errors, apps, diagnostics, coreState, micLevel, voiceRows, ::onCoreTap) }
 */
@Composable
fun JarvisRoot(
    history: TelemetryHistory,
    metrics: ApiMetricsStore,
    errorLog: ErrorLog,
    apps: AppsRepository,
    diagnostics: DiagnosticsController,
    coreState: StateFlow<CoreState>,
    micLevel: StateFlow<Float>,
    voiceRows: List<Pair<String, String>>,
    onCoreTap: () -> Unit,
    statusLine: String = "Centrale operativa",
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val scope = rememberCoroutineScope()
    val telemetry = history.samples.collectWhileStarted()
    val api = metrics.state.collectWhileStarted()
    val errors = errorLog.entries.collectWhileStarted()
    val diag = diagnostics.state.collectWhileStarted()
    val core = coreState.collectWhileStarted()
    val level = micLevel.collectWhileStarted()

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Box(modifier.fillMaxSize()) {
    HologramTouchHost(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState == Screen.HOME) {
                    (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 1.06f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f))
                } else {
                    (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.06f))
                }
            },
            label = "jarvis-screens",
        ) { s ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    telemetry = telemetry, api = api, coreState = core.value, statusLine = statusLine, micLevel = level.value,
                    onCoreTap = onCoreTap, onOpenApps = { screen = Screen.APPS }, onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                )
                Screen.APPS -> AppsScreen(apps)
                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                    state = diag, api = api, errors = errors, voiceRows = voiceRows,
                    onTest = { diagnostics.runTest(scope) },
                )
            }
        }
    }
    if (screen == Screen.HOME) overlay()
    }
}
