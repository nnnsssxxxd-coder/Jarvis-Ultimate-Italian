package it.jarvis.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import it.jarvis.ui.charts.CameraState
import it.jarvis.ui.charts.Projection
import it.jarvis.ui.charts.Vec3
import it.jarvis.ui.perf.LocalQualityController
import it.jarvis.ui.perf.RenderQuality
import it.jarvis.ui.theme.StarkHud
import it.jarvis.ui.util.rememberProcessStarted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class CoreState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

private class CoreParticle(val rho: Float, val a0: Float, val omega: Float, val size: Float, val tilt: Float)

private val TWO_PI = (2.0 * PI).toFloat()

/**
 * Nucleo JARVIS: sfera a fili rotante (proiezione 3D reale), anelli orbitali inclinati, particelle e onde.
 * Stati: IDLE, LISTENING, THINKING, SPEAKING, ERROR. Il tocco genera un impulso. `level` (0..1) = volume del microfono.
 * Si ferma quando l'app non è visibile e riduce fps/dettaglio se il telefono non regge.
 */
@Composable
fun JarvisCore(
    state: CoreState,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    onTap: () -> Unit = {},
) {
    val controller = LocalQualityController.current
    val quality = controller.quality
    val started by rememberProcessStarted()
    val time = remember { mutableStateOf(0f) }
    val pulse = remember { mutableStateOf(0f) }
    val particles = remember {
        val r = Random(7)
        List(72) { CoreParticle(0.5f + r.nextFloat() * 0.55f, r.nextFloat() * TWO_PI, (0.15f + r.nextFloat() * 0.5f) * (if (r.nextBoolean()) 1f else -1f), 1f + r.nextFloat() * 1.6f, r.nextFloat() * 0.9f) }
    }
    val tap = rememberUpdatedState(onTap)
    val path = remember { Path() }

    LaunchedEffect(started, quality) {
        if (!started) return@LaunchedEffect
        val minFrameNs = when (quality) { RenderQuality.FULL_3D -> 0L; RenderQuality.LITE_2_5D -> 32_000_000L; RenderQuality.MINIMAL -> 64_000_000L }
        var last = 0L
        var lastDraw = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) controller.onFrame(now - last) // misura i vsync reali, non i frame saltati
            last = now
            if (now - lastDraw >= minFrameNs) {
                val dt = ((now - lastDraw) / 1e9f).coerceAtMost(0.1f)
                lastDraw = now
                time.value += dt
                if (pulse.value > 0f) pulse.value = max(0f, pulse.value - dt * 2.2f)
            }
        }
    }

    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) { detectTapGestures { pulse.value = 1f; tap.value() } },
    ) {
        drawCore(time.value, state, pulse.value, level.coerceIn(0f, 1f), quality, particles, path)
    }
}

private fun DrawScope.drawCore(
    t: Float,
    state: CoreState,
    pulse: Float,
    level: Float,
    quality: RenderQuality,
    particles: List<CoreParticle>,
    path: Path,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(size.width, size.height) * 0.46f
    val center = Offset(cx, cy)
    val dp = density
    val main = if (state == CoreState.ERROR) StarkHud.Alert else StarkHud.Cyan
    val accent = when (state) {
        CoreState.ERROR -> StarkHud.Alert
        CoreState.THINKING -> StarkHud.Amber
        else -> StarkHud.Electric
    }
    val flicker = if (state == CoreState.ERROR) 0.55f + 0.45f * sin(t * 28f) else 1f
    val energy = when (state) {
        CoreState.IDLE -> 0.35f
        CoreState.LISTENING -> 0.7f + level * 0.6f
        CoreState.THINKING -> 0.8f
        CoreState.SPEAKING -> 0.85f + 0.3f * sin(t * 9f)
        CoreState.ERROR -> 0.6f
    }

    fun pr(v: Vec3, cam: CameraState, scale: Float): Offset {
        val p = Projection.project(v, cam, 2f, 2f, true)
        // project() usa unit = h*0.45: riportiamo a pixel del core
        val ux = (p.x - 1f) / (2f * 0.45f)
        val uy = (p.y - 1f) / (2f * 0.45f)
        return Offset(cx + ux * scale, cy + uy * scale)
    }

    // alone
    drawCircle(
        brush = Brush.radialGradient(listOf(main.copy(alpha = 0.30f * flicker), accent.copy(alpha = 0.10f), Color.Transparent), center = center, radius = r * 1.2f),
        radius = r * 1.2f, center = center,
    )

    // tacche rotanti del bordo
    val ticks = if (quality == RenderQuality.FULL_3D) 72 else if (quality == RenderQuality.LITE_2_5D) 36 else 0
    for (i in 0 until ticks) {
        val a = i * TWO_PI / ticks + t * 0.12f
        val len = if (i % 6 == 0) 0.07f else 0.035f
        drawLine(
            main.copy(alpha = 0.55f * flicker),
            Offset(cx + cos(a) * r * 0.99f, cy + sin(a) * r * 0.99f),
            Offset(cx + cos(a) * r * (0.99f - len), cy + sin(a) * r * (0.99f - len)),
            strokeWidth = 1.2f * dp,
        )
    }

    // anelli orbitali inclinati (3D reale)
    val ringSteps = when (quality) { RenderQuality.FULL_3D -> 72; RenderQuality.LITE_2_5D -> 40; RenderQuality.MINIMAL -> 24 }
    val rings = if (quality == RenderQuality.MINIMAL) 2 else 3
    for (k in 0 until rings) {
        val cam = CameraState(yaw = t * (0.35f + 0.18f * k) * (if (k % 2 == 0) 1f else -1f) + k, pitch = -0.5f - 0.45f * k, zoom = 1f)
        path.reset()
        for (s in 0..ringSteps) {
            val a = s * TWO_PI / ringSteps
            val o = pr(Vec3(cos(a) * 0.82f, 0f, sin(a) * 0.82f), cam, r)
            if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, (if (k == 1) accent else main).copy(alpha = 0.55f * flicker), style = Stroke(width = (1.6f - 0.3f * k) * dp, cap = StrokeCap.Round))
        // satellite sull'anello
        val sa = t * (1.2f + 0.5f * k) + k * 2f
        val sp = pr(Vec3(cos(sa) * 0.82f, 0f, sin(sa) * 0.82f), cam, r)
        drawCircle(StarkHud.Ice.copy(alpha = 0.9f), radius = 2.6f * dp, center = sp)
    }

    // sfera a fili
    val globe = CameraState(yaw = t * 0.5f, pitch = -0.35f, zoom = 1f)
    val gr = 0.42f
    val meridians = if (quality == RenderQuality.FULL_3D) 6 else 4
    val steps = if (quality == RenderQuality.FULL_3D) 48 else 28
    val wire = main.copy(alpha = 0.5f * flicker)
    for (m in 0 until meridians) {
        val phi = m * PI.toFloat() / meridians
        path.reset()
        for (s in 0..steps) {
            val a = s * TWO_PI / steps
            val o = pr(Vec3(cos(a) * cos(phi) * gr, sin(a) * gr, cos(a) * sin(phi) * gr), globe, r)
            if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, wire, style = Stroke(width = 1f * dp))
    }
    if (quality != RenderQuality.MINIMAL) {
        for (lat in listOf(-0.55f, 0f, 0.55f)) {
            val ry = sin(lat * 1.2f) * gr
            val rr = cos(lat * 1.2f) * gr
            path.reset()
            for (s in 0..steps) {
                val a = s * TWO_PI / steps
                val o = pr(Vec3(cos(a) * rr, ry, sin(a) * rr), globe, r)
                if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, wire, style = Stroke(width = 1f * dp))
        }
    }

    // cuore luminoso
    val coreR = r * 0.2f * (1f + 0.05f * sin(t * 2.2f) + pulse * 0.3f + level * 0.25f * (if (state == CoreState.LISTENING) 1f else 0f))
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * flicker), main.copy(alpha = 0.75f * flicker), Color.Transparent), center = center, radius = coreR * 1.8f),
        radius = coreR * 1.8f, center = center,
    )

    // onde energetiche
    val waves = when (state) {
        CoreState.IDLE -> 1
        CoreState.LISTENING, CoreState.SPEAKING -> if (quality == RenderQuality.FULL_3D) 3 else 2
        CoreState.THINKING -> 0
        CoreState.ERROR -> 1
    }
    for (w in 0 until waves) {
        val ph = ((t * (0.35f + 0.5f * energy)) + w / max(1f, waves.toFloat())) % 1f
        drawCircle(main.copy(alpha = (1f - ph) * 0.45f * flicker), radius = r * (0.25f + 0.75f * ph), center = center, style = Stroke(width = 1.6f * dp))
    }
    if (state == CoreState.THINKING) {
        drawArc(StarkHud.Amber.copy(alpha = 0.9f), startAngle = t * 240f, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(cx - r * 0.92f, cy - r * 0.92f), size = Size(r * 1.84f, r * 1.84f), style = Stroke(width = 3f * dp, cap = StrokeCap.Round))
        drawArc(StarkHud.Ice.copy(alpha = 0.7f), startAngle = -t * 160f, sweepAngle = 40f, useCenter = false,
            topLeft = Offset(cx - r * 0.74f, cy - r * 0.74f), size = Size(r * 1.48f, r * 1.48f), style = Stroke(width = 2f * dp, cap = StrokeCap.Round))
    }

    // impulso al tocco
    if (pulse > 0f) {
        drawCircle(StarkHud.Ice.copy(alpha = pulse * 0.8f), radius = r * (0.2f + 0.9f * (1f - pulse)), center = center, style = Stroke(width = 2.4f * dp))
    }

    // particelle
    val count = when (quality) { RenderQuality.FULL_3D -> particles.size; RenderQuality.LITE_2_5D -> 28; RenderQuality.MINIMAL -> 0 }
    for (i in 0 until count) {
        val p = particles[i]
        val a = p.a0 + t * p.omega * (0.6f + energy)
        val px = cx + cos(a) * r * p.rho
        val py = cy + sin(a) * r * p.rho * (0.45f + 0.5f * p.tilt)
        drawCircle(StarkHud.Ice.copy(alpha = 0.35f + 0.4f * p.tilt), radius = p.size * dp, center = Offset(px, py))
    }
}
