package it.jarvis.ui.touch

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import it.jarvis.ui.perf.LocalQualityController
import it.jarvis.ui.perf.RenderQuality
import it.jarvis.ui.theme.StarkHud
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val TAP_LIFE_S = 0.75f
private const val LONG_LIFE_S = 1.1f
private const val TRAIL_LIFE_S = 0.5f
private const val LONG_PRESS_NS = 450_000_000L
private const val STALE_POINTER_NS = 20_000_000_000L

internal class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val size: Float, val hot: Boolean,
)

internal class Ripple(val x: Float, val y: Float, val bornNs: Long, val long: Boolean)
internal class TrailPoint(val x: Float, val y: Float, val bornNs: Long)

internal class PointerState(val id: Long, var x: Float, var y: Float, val downNs: Long) {
    val startX = x
    val startY = y
    var moved = 0f
    var longFired = false
    var down = true
    var lastEventNs = downNs
    var sinceEmit = 0f
    val trail = ArrayDeque<TrailPoint>()
}

/**
 * Motore dell'effetto olografico. Non tocca mai gli eventi: li osserva soltanto.
 * A riposo non consuma nulla: il ciclo dei frame dorme finché non c'è attività.
 */
internal class HologramEngine {
    val wake = Channel<Unit>(Channel.CONFLATED)
    val tick = mutableStateOf(0L)
    var dens = 1f
    var quality = RenderQuality.FULL_3D
    var intensity = 1f
    var lastNs = 0L
    var onTapHaptic: () -> Unit = {}
    var onLongHaptic: () -> Unit = {}

    private val ripples = ArrayList<Ripple>()
    private val particles = ArrayList<Particle>()
    private val pointers = HashMap<Long, PointerState>()
    private val rnd = Random(System.nanoTime())
    private var lastHapticNs = 0L

    private val maxParticles: Int
        get() = when (quality) { RenderQuality.FULL_3D -> 160; RenderQuality.LITE_2_5D -> 60; RenderQuality.MINIMAL -> 0 }
    private val maxTrail: Int
        get() = when (quality) { RenderQuality.FULL_3D -> 24; RenderQuality.LITE_2_5D -> 10; RenderQuality.MINIMAL -> 0 }
    private val tapBurst: Int
        get() = (when (quality) { RenderQuality.FULL_3D -> 14; RenderQuality.LITE_2_5D -> 6; RenderQuality.MINIMAL -> 0 } * intensity).roundToInt()

    fun hasActivity(): Boolean = pointers.isNotEmpty() || ripples.isNotEmpty() || particles.isNotEmpty()

    fun onDown(id: Long, x: Float, y: Float, nowNs: Long) {
        val p = PointerState(id, x, y, nowNs)
        p.trail.addLast(TrailPoint(x, y, nowNs))
        pointers[id] = p
        ripples += Ripple(x, y, nowNs, false)
        while (ripples.size > 24) ripples.removeAt(0)
        burst(x, y, tapBurst, 70f, 220f)
        if (nowNs - lastHapticNs > 60_000_000L) { lastHapticNs = nowNs; onTapHaptic() }
        wake.trySend(Unit)
    }

    fun onMove(id: Long, x: Float, y: Float, nowNs: Long) {
        val p = pointers[id] ?: return
        val d = hypot(x - p.x, y - p.y)
        p.x = x
        p.y = y
        p.moved += d
        p.lastEventNs = nowNs
        if (maxTrail > 0) {
            val last = p.trail.lastOrNull()
            if (last == null || hypot(x - last.x, y - last.y) > 4f * dens) {
                p.trail.addLast(TrailPoint(x, y, nowNs))
                while (p.trail.size > maxTrail) p.trail.removeFirst()
            }
        }
        p.sinceEmit += d
        if (p.sinceEmit > 16f * dens) {
            p.sinceEmit = 0f
            burst(x, y, if (quality == RenderQuality.FULL_3D) 2 else 1, 10f, 60f)
        }
        wake.trySend(Unit)
    }

    fun onUp(id: Long, nowNs: Long) {
        val p = pointers[id] ?: return
        p.down = false
        p.lastEventNs = nowNs
        wake.trySend(Unit)
    }

    private fun burst(x: Float, y: Float, count: Int, vMin: Float, vMax: Float) {
        val n = min(count, maxParticles - particles.size)
        if (n <= 0) return
        repeat(n) {
            val a = rnd.nextFloat() * (2f * PI.toFloat())
            val v = (vMin + rnd.nextFloat() * (vMax - vMin)) * dens
            val life = 0.45f + rnd.nextFloat() * 0.5f
            particles += Particle(x, y, cos(a) * v, sin(a) * v, life, life, 1.2f + rnd.nextFloat() * 1.8f, rnd.nextFloat() < 0.25f)
        }
    }

    fun step(nowNs: Long) {
        val dt = if (lastNs == 0L) 0.016f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = nowNs

        for (p in pointers.values) {
            if (p.down && nowNs - p.lastEventNs > STALE_POINTER_NS) p.down = false // sicurezza: dito "perso"
            if (p.down && !p.longFired && p.moved < 24f * dens && nowNs - p.downNs >= LONG_PRESS_NS) {
                p.longFired = true
                ripples += Ripple(p.x, p.y, nowNs, true)
                burst(p.x, p.y, (28 * intensity).roundToInt(), 90f, 320f)
                onLongHaptic()
            }
        }

        val pit = particles.iterator()
        while (pit.hasNext()) {
            val q = pit.next()
            q.life -= dt
            if (q.life <= 0f) { pit.remove(); continue }
            q.x += q.vx * dt
            q.y += q.vy * dt
            val drag = (1f - 2.2f * dt).coerceAtLeast(0f)
            q.vx *= drag
            q.vy *= drag
        }

        ripples.removeAll { (nowNs - it.bornNs) / 1e9f > (if (it.long) LONG_LIFE_S else TAP_LIFE_S) }

        val iter = pointers.values.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            while (p.trail.isNotEmpty() && (nowNs - p.trail.first().bornNs) / 1e9f > TRAIL_LIFE_S) p.trail.removeFirst()
            if (!p.down && p.trail.isEmpty()) iter.remove()
        }
    }

    fun draw(scope: DrawScope, nowNs: Long) = with(scope) {
        val dp = density
        val cyan = StarkHud.Cyan
        val electric = StarkHud.Electric
        val ice = StarkHud.Ice
        val glowBlend = if (quality == RenderQuality.FULL_3D) BlendMode.Plus else BlendMode.SrcOver
        val boost = intensity.coerceIn(0.5f, 1.5f)

        // scia
        for (p in pointers.values) {
            val t = p.trail
            for (i in 1 until t.size) {
                val a = t[i - 1]
                val b = t[i]
                val age = ((nowNs - b.bornNs).coerceAtLeast(0L) / 1e9f / TRAIL_LIFE_S).coerceIn(0f, 1f)
                drawLine(
                    color = cyan.copy(alpha = (1f - age) * 0.8f),
                    start = Offset(a.x, a.y), end = Offset(b.x, b.y),
                    strokeWidth = ((1f - age) * 5f + 1f) * dp, cap = StrokeCap.Round, blendMode = glowBlend,
                )
            }
        }

        // onde
        for (r in ripples) {
            val life = if (r.long) LONG_LIFE_S else TAP_LIFE_S
            val t = ((nowNs - r.bornNs).coerceAtLeast(0L) / 1e9f / life)
            if (t >= 1f) continue
            val e = 1f - (1f - t) * (1f - t)
            val maxR = (if (r.long) 150f else 78f) * dp * boost
            val center = Offset(r.x, r.y)
            val rings = if (r.long) 4 else when (quality) { RenderQuality.FULL_3D -> 3; RenderQuality.LITE_2_5D -> 2; RenderQuality.MINIMAL -> 1 }
            for (k in 0 until rings) {
                val tk = (e - k * 0.14f).coerceIn(0f, 1f)
                if (tk <= 0f) continue
                val rad = 10f * dp + (maxR - 10f * dp) * tk
                drawCircle(
                    color = cyan.copy(alpha = (1f - tk) * 0.85f), radius = rad, center = center,
                    style = Stroke(width = (2.2f - k * 0.4f).coerceAtLeast(0.8f) * dp),
                )
            }
            if (quality != RenderQuality.MINIMAL) {
                val ha = (1f - t) * 0.55f
                val hr = 46f * dp * (0.6f + 0.8f * e) * boost
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(cyan.copy(alpha = ha), electric.copy(alpha = ha * 0.4f), Color.Transparent),
                        center = center, radius = hr,
                    ),
                    radius = hr, center = center,
                )
            }
            if (r.long) {
                val spokes = if (quality == RenderQuality.FULL_3D) 12 else 6
                val r0 = 10f * dp + (maxR - 10f * dp) * e
                for (i in 0 until spokes) {
                    val ang = i * (2f * PI.toFloat() / spokes) + t * 0.6f
                    drawLine(
                        color = ice.copy(alpha = (1f - t) * 0.7f),
                        start = Offset(r.x + cos(ang) * r0 * 0.55f, r.y + sin(ang) * r0 * 0.55f),
                        end = Offset(r.x + cos(ang) * r0 * 0.85f, r.y + sin(ang) * r0 * 0.85f),
                        strokeWidth = 1.5f * dp, cap = StrokeCap.Round,
                    )
                }
            }
        }

        // particelle
        for (q in particles) {
            val k = (q.life / q.maxLife).coerceIn(0f, 1f)
            drawCircle(
                color = (if (q.hot) ice else cyan).copy(alpha = k),
                radius = q.size * dp * (0.6f + 0.4f * k), center = Offset(q.x, q.y), blendMode = glowBlend,
            )
        }

        // dito premuto: alone pulsante + arco di carica del tocco prolungato
        for (p in pointers.values) {
            if (!p.down) continue
            val age = (nowNs - p.downNs).coerceAtLeast(0L) / 1e9f
            val center = Offset(p.x, p.y)
            val pulse = 0.5f + 0.5f * sin(age * 8f)
            val rr = (26f + 3f * pulse) * dp
            if (quality != RenderQuality.MINIMAL) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(cyan.copy(alpha = 0.35f), Color.Transparent), center = center, radius = 40f * dp,
                    ),
                    radius = 40f * dp, center = center,
                )
            }
            drawCircle(color = cyan.copy(alpha = 0.55f), radius = rr, center = center, style = Stroke(width = 1.6f * dp))
            val progress = ((nowNs - p.downNs).toFloat() / LONG_PRESS_NS).coerceIn(0f, 1f)
            if (!p.longFired && p.moved < 24f * dp && progress > 0.05f) {
                val ar = rr + 7f * dp
                drawArc(
                    color = ice.copy(alpha = 0.9f), startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(p.x - ar, p.y - ar), size = Size(2f * ar, 2f * ar),
                    style = Stroke(width = 2.5f * dp, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun animationsDisabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * Avvolge TUTTE le schermate JARVIS. Un cerchio olografico appare esattamente sotto ogni dito, anche su aree vuote.
 *
 * Come non blocca nulla: osserva gli eventi nella fase Initial e non li consuma mai; il livello di disegno
 * è un Canvas senza gestione input, quindi pulsanti, scroll, grafici e gesture continuano a funzionare.
 *
 * Costo a riposo: zero (nessun frame richiesto finché non c'è un tocco). Intensità adattiva: segue
 * QualityController (FULL / LITE / MINIMAL) e rispetta "animazioni disattivate" di Android.
 */
@Composable
fun HologramTouchHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    intensity: Float = 1f,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val controller = LocalQualityController.current
    val density = LocalDensity.current.density
    val engine = remember { HologramEngine() }
    val reduceMotion = remember { animationsDisabled(context) }
    val active = enabled && !reduceMotion

    engine.dens = density
    engine.quality = controller.quality
    engine.intensity = intensity.coerceIn(0.3f, 1.5f)
    engine.onTapHaptic = { if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    engine.onLongHaptic = { if (haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    LaunchedEffect(engine, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            if (!engine.hasActivity()) engine.wake.receive()
            val now = withFrameNanos { it }
            engine.step(now)
            engine.tick.value = now
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(engine, active) {
                if (!active) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val now = System.nanoTime()
                        for (c in event.changes) {
                            val id = c.id.value
                            when {
                                c.pressed && !c.previousPressed -> engine.onDown(id, c.position.x, c.position.y, now)
                                !c.pressed && c.previousPressed -> engine.onUp(id, now)
                                c.pressed && c.position != c.previousPosition -> engine.onMove(id, c.position.x, c.position.y, now)
                            }
                            // NON si consuma nulla: i figli ricevono gli eventi normalmente.
                        }
                    }
                }
            },
    ) {
        content()
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val t = engine.tick.value // lettura = invalidazione del solo disegno
                engine.draw(this, if (t != 0L) t else engine.lastNs)
            }
        }
    }
}
