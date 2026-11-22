package it.jarvis.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.jarvis.core.ErrorEntry
import it.jarvis.core.Verdict
import it.jarvis.net.ApiSnapshot
import it.jarvis.ui.home.HudBackground
import it.jarvis.ui.theme.StarkHud
import java.text.DateFormat
import java.util.Date

private enum class Tri { OK, BAD, UNKNOWN }

private fun Boolean?.tri() = when (this) { true -> Tri.OK; false -> Tri.BAD; null -> Tri.UNKNOWN }

private fun color(t: Tri) = when (t) { Tri.OK -> StarkHud.Ok; Tri.BAD -> StarkHud.Alert; Tri.UNKNOWN -> StarkHud.TextDim }

/**
 * Pannello tecnico. Non mostra mai chiavi o token: del secret si vede solo se esiste e con quale NOME.
 * `voiceRows`: coppie (etichetta, stato) fornite dai servizi vocali reali, es. "Hotword" -> "Attiva".
 */
@Composable
fun DiagnosticsScreen(
    state: State<DiagnosticsUiState>,
    api: State<ApiSnapshot>,
    errors: State<List<ErrorEntry>>,
    voiceRows: List<Pair<String, String>>,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = state.value
    val rep = ui.report
    val snap = api.value
    val df = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    HudBackground(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("DIAGNOSTICA", color = StarkHud.Ice, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            }
            item {
                val shape = RoundedCornerShape(12.dp)
                val verdictColor = when {
                    ui.running -> StarkHud.Amber
                    rep == null -> StarkHud.TextDim
                    rep.verdict == Verdict.CONNECTED -> StarkHud.Ok
                    else -> StarkHud.Alert
                }
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(StarkHud.Panel).border(1.dp, verdictColor.copy(alpha = 0.7f), shape).padding(12.dp),
                ) {
                    Text(
                        text = when {
                            ui.running -> "Test in corso: richiesta reale al backend…"
                            rep == null -> "Nessun test eseguito"
                            else -> rep.verdict.label
                        },
                        color = verdictColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (rep != null && !ui.running) {
                        Text(rep.message, color = StarkHud.TextDim, fontSize = 12.sp)
                        Text("Eseguito alle ${df.format(Date(rep.atMs))}", color = StarkHud.TextDim, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    val bs = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.clip(bs).background(StarkHud.Cyan.copy(alpha = if (ui.running) 0.08f else 0.22f))
                            .border(1.dp, StarkHud.Cyan.copy(alpha = 0.8f), bs)
                            .clickable(enabled = !ui.running, onClick = onTest)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) { Text("TESTA CONNESSIONE", color = StarkHud.Ice, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                }
            }

            val rows = buildList {
                add(Triple("Backend (Edge Function)", rep?.functionVersion ?: if (rep == null) "non verificato" else "raggiunto", rep?.let { it.authValid != null || it.openAiReachable != null }.tri()))
                add(Triple("Supabase / autenticazione", when (rep?.authValid) { true -> "sessione valida"; false -> "non valida"; null -> "non verificato" }, rep?.authValid.tri()))
                add(Triple("Secret OpenAI", when (rep?.secretPresent) { true -> "presente" + (rep?.secretSource?.let { " (nome: $it)" } ?: ""); false -> "mancante"; null -> "non verificato" }, rep?.secretPresent.tri()))
                add(Triple("API OpenAI", when (rep?.openAiReachable) { true -> "raggiungibile"; false -> "non raggiungibile / rifiutata"; null -> "non verificato" }, rep?.openAiReachable.tri()))
                add(Triple("Modello AI", rep?.model ?: "—", if (rep?.model != null) Tri.OK else Tri.UNKNOWN))
                add(Triple("Tempo di risposta", rep?.let { "totale ${it.roundTripMs} ms" + (it.openAiMs?.let { o -> " · OpenAI $o ms" } ?: "") } ?: "—", if (rep != null) Tri.OK else Tri.UNKNOWN))
                add(Triple("Richieste (questa sessione)", "${snap.total} · ok ${snap.success} · errori ${snap.errors} · limiti ${snap.rateLimited}", Tri.OK))
                add(Triple("Ultimo errore", snap.lastError?.let { "${it.code.wire}: ${it.message}" } ?: "nessuno", if (snap.lastError == null) Tri.OK else Tri.BAD))
            }
            items(rows) { (label, value, tri) -> Row3(label, value, color(tri)) }
            items(voiceRows) { (label, value) -> Row3(label, value, StarkHud.Electric) }

            item { Spacer(Modifier.height(6.dp)); Text("REGISTRO ERRORI", color = StarkHud.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp) }
            if (errors.value.isEmpty()) item { Text("Nessun errore registrato.", color = StarkHud.TextDim, fontSize = 12.sp) }
            items(errors.value.take(30)) { e ->
                Column {
                    Text("${df.format(Date(e.tsMs))} · ${e.code.wire}", color = StarkHud.Alert, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(e.message + (e.requestId?.let { " [id ${it.take(8)}]" } ?: ""), color = StarkHud.TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Row3(label: String, value: String, dot: Color) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(StarkHud.Panel).border(1.dp, StarkHud.PanelStroke, shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, color = StarkHud.TextDim, fontSize = 11.sp)
            Text(value, color = StarkHud.Ice, fontSize = 13.sp)
        }
    }
}
