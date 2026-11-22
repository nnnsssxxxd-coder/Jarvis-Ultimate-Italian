package it.jarvis.ui.perf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class RenderQuality { FULL_3D, LITE_2_5D, MINIMAL }

/**
 * Politica pura (testabile): guarda il tempo tra un frame e l'altro e abbassa la qualità se il telefono non regge,
 * la rialza solo dopo diversi secondi stabili (con backoff, per evitare oscillazioni).
 */
class FrameQualityPolicy(
    private val windowFrames: Int = 60,
    private val downMs: Float = 26f,
    private val upMs: Float = 18f,
    private var upWindowsNeeded: Int = 6,
) {
    var level: RenderQuality = RenderQuality.FULL_3D
        private set
    private var sumMs = 0f
    private var frames = 0
    private var goodWindows = 0

    /** Restituisce il nuovo livello solo se è cambiato. dt fuori scala (pause, schermo spento) viene ignorato. */
    fun onFrame(dtNanos: Long): RenderQuality? {
        val ms = dtNanos / 1_000_000f
        if (ms <= 0f || ms > 200f) return null
        sumMs += ms
        frames++
        if (frames < windowFrames) return null
        val avg = sumMs / frames
        sumMs = 0f
        frames = 0
        when {
            avg > downMs -> {
                goodWindows = 0
                if (level != RenderQuality.MINIMAL) {
                    level = RenderQuality.values()[level.ordinal + 1]
                    upWindowsNeeded = minOf(upWindowsNeeded * 2, 96)
                    return level
                }
            }
            avg < upMs -> {
                goodWindows++
                if (goodWindows >= upWindowsNeeded && level != RenderQuality.FULL_3D) {
                    goodWindows = 0
                    level = RenderQuality.values()[level.ordinal - 1]
                    return level
                }
            }
            else -> goodWindows = 0
        }
        return null
    }
}

class QualityController(private val isPowerSave: () -> Boolean = { false }) {
    private val policy = FrameQualityPolicy()
    private var auto by mutableStateOf(RenderQuality.FULL_3D)

    /** Se impostato (impostazioni utente) ignora la modalità automatica. */
    var forced: RenderQuality? by mutableStateOf(null)

    val quality: RenderQuality
        get() {
            val q = forced ?: auto
            return if (isPowerSave() && q == RenderQuality.FULL_3D) RenderQuality.LITE_2_5D else q
        }

    fun onFrame(dtNanos: Long) {
        policy.onFrame(dtNanos)?.let { auto = it }
    }
}

val LocalQualityController = staticCompositionLocalOf { QualityController() }
