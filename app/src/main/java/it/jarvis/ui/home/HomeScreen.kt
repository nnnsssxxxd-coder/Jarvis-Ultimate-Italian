package it.jarvis.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.jarvis.net.ApiSnapshot
import it.jarvis.net.Outcome
import it.jarvis.telemetry.TelemetrySample
import it.jarvis.ui.charts.ChartSeries
import it.jarvis.ui.charts.Interactive3DChart
import it.jarvis.ui.theme.StarkHud

/** Sfondo comune a tutte le schermate: nero/blu notte con griglia sottile. Statico: nessun costo per frame. */
@Composable
fun HudBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(StarkHud.Void, StarkHud.NightBlue, StarkHud.Void)))) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 36.dp.toPx()
            val c = StarkHud.Cyan.copy(alpha = 0.045f)
            var x = 0f
            while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        content()
    }
}

private fun List<TelemetrySample>.col(sel: (TelemetrySample) -> Float?): List<Float?> = map(sel)

/**
 * Centrale operativa: nucleo + 10 grafici alimentati da dati reali (telemetria Android e metriche delle richieste).
 * Il grafico Temperatura compare solo se almeno un campione ha il dato. Nessuna lista di app qui.
 */
@Composable
fun HomeScreen(
    telemetry: State<List<TelemetrySample>>,
    api: State<ApiSnapshot>,
    coreState: CoreState,
    statusLine: String,
    micLevel: Float,
    onCoreTap: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val samples = telemetry.value
    val snap = api.value
    val now = System.currentTimeMillis()

    HudBackground(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable(onClick = onOpenDiagnostics)
                            .semantics { contentDescription = "Apri diagnostica" },
                    ) {
                        Text("J.A.R.V.I.S.", color = StarkHud.Ice, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                        Text(statusLine, color = StarkHud.TextDim, fontSize = 12.sp)
                    }
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.clip(shape).border(1.dp, StarkHud.Cyan.copy(alpha = 0.5f), shape)
                            .clickable(onClick = onOpenApps).padding(horizontal = 12.dp, vertical = 5.dp)
                            .semantics { contentDescription = "Apri applicazioni" },
                    ) { Text("APP", color = StarkHud.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                }
            }
            item(key = "core") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    JarvisCore(coreState, Modifier.widthIn(max = 320.dp).fillMaxWidth(), level = micLevel, onTap = onCoreTap)
                }
            }

            // 1. CPU
            item(key = "cpu") {
                val hasSys = samples.any { it.cpuSystemPercent != null }
                Interactive3DChart(
                    title = if (hasSys) "CPU" else "CPU · processo JARVIS",
                    series = buildList {
                        if (hasSys) add(ChartSeries("Sistema", StarkHud.Electric, samples.col { it.cpuSystemPercent }))
                        add(ChartSeries("Processo JARVIS", StarkHud.Cyan, samples.col { it.cpuAppPercent }))
                    },
                    unit = "%", yMax = 100f,
                    emptyMessage = "In attesa dei primi campioni",
                )
            }
            // 2. RAM
            item(key = "ram") {
                val last = samples.lastOrNull()
                Interactive3DChart(
                    title = if (last != null) "RAM · ${last.ramUsedMb} / ${last.ramTotalMb} MB" else "RAM",
                    series = listOf(ChartSeries("RAM usata", StarkHud.Cyan, samples.col { it.ramUsedPercent })),
                    unit = "%", yMax = 100f,
                )
            }
            // 3. Memoria interna
            item(key = "storage") {
                val last = samples.lastOrNull()
                Interactive3DChart(
                    title = if (last != null) "Memoria interna · liberi %.1f GB".format(last.storageFreeGb) else "Memoria interna",
                    series = listOf(ChartSeries("Spazio usato", StarkHud.Electric, samples.col { it.storageUsedPercent })),
                    unit = "%", yMax = 100f,
                )
            }
            // 4. Batteria
            item(key = "battery") {
                val charging = samples.lastOrNull()?.charging == true
                Interactive3DChart(
                    title = if (charging) "Batteria · in carica" else "Batteria",
                    series = listOf(ChartSeries("Carica", StarkHud.Ok, samples.col { it.batteryPercent })),
                    unit = "%", yMax = 100f,
                )
            }
            // 5. Temperatura: SOLO se il dato esiste davvero
            if (samples.any { it.batteryTempC != null }) {
                item(key = "temp") {
                    Interactive3DChart(
                        title = "Temperatura batteria",
                        series = listOf(ChartSeries("Batteria", StarkHud.Amber, samples.col { it.batteryTempC })),
                        unit = "°C", yMin = 0f, yMax = 60f,
                    )
                }
            }
            // 6-7. Rete
            item(key = "down") {
                Interactive3DChart(
                    title = "Rete · download",
                    series = listOf(ChartSeries("Download", StarkHud.Cyan, samples.col { it.netDownKbps })),
                    unit = "kb/s",
                    emptyMessage = "Contatore di rete non disponibile",
                )
            }
            item(key = "up") {
                Interactive3DChart(
                    title = "Rete · upload",
                    series = listOf(ChartSeries("Upload", StarkHud.Electric, samples.col { it.netUpKbps })),
                    unit = "kb/s",
                    emptyMessage = "Contatore di rete non disponibile",
                )
            }
            // 8. Attività del motore AI: secondi di lavoro ogni 10 s
            item(key = "ai") {
                Interactive3DChart(
                    title = "Motore AI · occupazione" + if (snap.inFlight > 0) " (in corso)" else "",
                    series = listOf(ChartSeries("Secondi attivi", StarkHud.Cyan, snap.busySeconds(now))),
                    unit = "s/10s", yMax = 10f,
                    emptyMessage = "Nessuna richiesta AI ancora",
                    sampleLabel = { "-${it * 10}s" },
                )
            }
            // 9. Tempi di risposta
            item(key = "latency") {
                val lat = snap.latencySeries(40)
                Interactive3DChart(
                    title = "Tempo di risposta API",
                    series = listOf(ChartSeries("Primo byte", StarkHud.Electric, lat)),
                    unit = "ms",
                    emptyMessage = "Nessuna richiesta completata ancora",
                    sampleLabel = { if (it == 0) "ultima richiesta" else "${it + 1} richieste fa" },
                )
            }
            // 10. Statistiche richieste
            item(key = "requests") {
                Interactive3DChart(
                    title = "Richieste · ${snap.total} totali",
                    series = listOf(
                        ChartSeries("Riuscite (${snap.success})", StarkHud.Ok, snap.outcomeBuckets(now, setOf(Outcome.SUCCESS))),
                        ChartSeries("Errori (${snap.errors})", StarkHud.Alert, snap.outcomeBuckets(now, setOf(Outcome.ERROR))),
                        ChartSeries("Limiti (${snap.rateLimited})", StarkHud.Amber, snap.outcomeBuckets(now, setOf(Outcome.RATE_LIMITED))),
                    ),
                    unit = "richieste", yMax = null,
                    emptyMessage = "Nessuna richiesta ancora",
                    sampleLabel = { "-${it * 10}s" },
                )
            }
        }
    }
}
