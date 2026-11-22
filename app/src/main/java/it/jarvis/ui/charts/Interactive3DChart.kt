package it.jarvis.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.jarvis.ui.perf.LocalQualityController
import it.jarvis.ui.perf.RenderQuality
import it.jarvis.ui.theme.StarkHud
import kotlin.math.max
import kotlin.math.roundToInt

data class ChartSeries(val name: String, val color: Color, val values: List<Float?>)

private fun Color.scaleRgb(f: Float) = Color(red * f, green * f, blue * f, alpha)

private fun fmt(v: Float): String = when {
    v >= 100f -> "%.0f".format(v)
    v >= 10f -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

/**
 * Grafico 3D interattivo disegnato su Canvas (nessuna libreria 3D, nessun asset): leggero per il moto g05.
 *
 * - ESPLORA attivo: un dito ruota e inclina, due dita ingrandiscono e spostano.
 * - ESPLORA spento: lo scorrimento verticale della Home resta libero; tocco = legge il valore.
 * - Doppio tocco o RESET: ripristina la posizione.
 * - Con qualità LITE_2_5D/MINIMAL la prospettiva e la griglia vengono semplificate automaticamente.
 */
@Composable
fun Interactive3DChart(
    title: String,
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    yMin: Float = 0f,
    yMax: Float? = null,
    emptyMessage: String = "Dato non disponibile su questo dispositivo",
    quality: RenderQuality = LocalQualityController.current.quality,
    /** Etichetta del campione: indice contato dalla fine (0 = ultimo). */
    sampleLabel: (Int) -> String = { "-${it}s" },
) {
    var cam by remember { mutableStateOf(CameraState()) }
    var explore by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val path = remember { Path() }

    val hasData = series.any { s -> s.values.any { it != null } }
    val maxValue = yMax ?: max(1f, (series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 1f) * 1.15f)
    val scene = remember(series, cam, size, quality, maxValue) {
        ChartSceneBuilder.build(series.map { it.values }, yMin, maxValue, cam, size.width.toFloat(), size.height.toFloat(), quality)
    }
    val sceneState = rememberUpdatedState(scene)

    val latest = series.firstOrNull()?.values?.lastOrNull { it != null }
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(StarkHud.Panel)
            .border(1.dp, StarkHud.PanelStroke, shape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = StarkHud.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (latest != null) "${fmt(latest)} $unit" else "n/d",
                    color = StarkHud.Cyan, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                )
            }
            if (quality != RenderQuality.FULL_3D) {
                Text("2.5D", color = StarkHud.TextDim, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
            if (hasData) {
                ChipButton(if (explore) "BLOCCA" else "ESPLORA", active = explore) { explore = !explore }
                Spacer(Modifier.width(6.dp))
                ChipButton("RESET") { cam = CameraState(); selected = null }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .onSizeChanged { size = it }
                .semantics { contentDescription = "Grafico $title" },
        ) {
            if (!hasData) {
                Text(emptyMessage, color = StarkHud.TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { o ->
                                    selected = ChartSceneBuilder.nearest(sceneState.value, o.x, o.y, 44.dp.toPx())
                                        ?.let { it.seriesIndex to it.index }
                                },
                                onDoubleTap = { cam = CameraState(); selected = null },
                            )
                        }
                        .pointerInput(explore) {
                            if (!explore) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        cam = cam.zoomBy(zoom).panBy(pan.x, pan.y)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (pressed.size == 1) {
                                        val d = pressed[0].positionChange()
                                        if (d != Offset.Zero) {
                                            cam = cam.orbit(d.x, d.y)
                                            pressed[0].consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    drawScene(scene, series.map { it.color }, selected, quality, path)
                }
                val sel = selected?.let { (s, i) -> scene.bars.firstOrNull { it.seriesIndex == s && it.index == i } }
                if (sel != null) {
                    val n = series.maxOfOrNull { it.values.size } ?: 0
                    Column(
                        Modifier
                            .offset {
                                IntOffset(
                                    (sel.topCenter.x - 56.dp.toPx()).roundToInt().coerceIn(0, max(0, size.width - 112.dp.roundToPx())),
                                    (sel.topCenter.y - 46.dp.toPx()).roundToInt().coerceAtLeast(0),
                                )
                            }
                            .width(112.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StarkHud.Void.copy(alpha = 0.88f))
                            .border(1.dp, StarkHud.Cyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(6.dp),
                    ) {
                        Text(series[sel.seriesIndex].name, color = StarkHud.TextDim, fontSize = 10.sp)
                        Text("${fmt(sel.value)} $unit", color = StarkHud.Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(sampleLabel(n - 1 - sel.index), color = StarkHud.TextDim, fontSize = 10.sp)
                    }
                }
            }
        }
        if (series.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(s.color))
                        Spacer(Modifier.width(4.dp))
                        Text(s.name, color = StarkHud.TextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (active) StarkHud.Cyan.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, StarkHud.Cyan.copy(alpha = if (active) 0.9f else 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = StarkHud.Ice, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

private fun DrawScope.drawScene(
    scene: ChartScene,
    colors: List<Color>,
    selected: Pair<Int, Int>?,
    quality: RenderQuality,
    path: Path,
) {
    val grid = StarkHud.Cyan.copy(alpha = 0.16f)
    scene.floor.forEach { drawLine(grid, Offset(it.a.x, it.a.y), Offset(it.b.x, it.b.y), strokeWidth = 1f) }
    scene.frame.forEach { drawLine(grid.copy(alpha = 0.28f), Offset(it.a.x, it.a.y), Offset(it.b.x, it.b.y), strokeWidth = 1f) }

    for (b in scene.bars) {
        val base = colors[b.seriesIndex.coerceAtMost(colors.lastIndex)]
        val isSel = selected != null && selected.first == b.seriesIndex && selected.second == b.index
        for (f in b.faces) {
            path.reset()
            path.moveTo(f.xs[0], f.ys[0])
            for (k in 1..3) path.lineTo(f.xs[k], f.ys[k])
            path.close()
            var col = when (f.shade) {
                Shade.TOP -> lerp(base, Color.White, 0.35f)
                Shade.FRONT -> base.copy(alpha = 0.92f)
                Shade.SIDE -> base.scaleRgb(0.62f)
            }
            if (isSel) col = lerp(col, Color.White, 0.35f)
            drawPath(path, col)
            if (quality != RenderQuality.MINIMAL) {
                drawPath(path, StarkHud.Ice.copy(alpha = if (isSel) 0.9f else 0.22f), style = Stroke(width = if (isSel) 2f else 0.8f))
            }
        }
    }
}
