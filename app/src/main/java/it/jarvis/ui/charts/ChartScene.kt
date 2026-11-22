package it.jarvis.ui.charts

import it.jarvis.ui.perf.RenderQuality
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class Vec3(val x: Float, val y: Float, val z: Float)
data class Pt(val x: Float, val y: Float, val depth: Float)

/** Posizione della "camera" attorno al grafico. Immutabile: ogni gesto produce un nuovo stato. */
data class CameraState(
    val yaw: Float = DEFAULT_YAW,
    val pitch: Float = DEFAULT_PITCH,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun orbit(dx: Float, dy: Float, sensitivity: Float = 0.008f) =
        copy(yaw = yaw + dx * sensitivity, pitch = (pitch - dy * sensitivity).coerceIn(MIN_PITCH, MAX_PITCH))

    fun zoomBy(factor: Float) = copy(zoom = (zoom * factor).coerceIn(0.6f, 4f))
    fun panBy(dx: Float, dy: Float) = copy(panX = panX + dx, panY = panY + dy)

    companion object {
        const val DEFAULT_YAW = -0.55f
        const val DEFAULT_PITCH = -0.62f
        const val MIN_PITCH = -1.5f   // quasi dall'alto
        const val MAX_PITCH = -0.05f  // quasi di lato: mai sotto il piano
    }
}

object Projection {
    const val CAMERA_DISTANCE = 3.6f

    /** Prima rotazione attorno a Y (yaw), poi attorno a X (pitch). z positivo = lontano dall'osservatore. */
    fun rotate(p: Vec3, yaw: Float, pitch: Float): Vec3 {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val x1 = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        val cp = cos(pitch)
        val sp = sin(pitch)
        return Vec3(x1, p.y * cp - z1 * sp, p.y * sp + z1 * cp)
    }

    fun project(p: Vec3, cam: CameraState, w: Float, h: Float, perspective: Boolean): Pt {
        val r = rotate(p, cam.yaw, cam.pitch)
        val f = if (perspective) CAMERA_DISTANCE / max(0.4f, CAMERA_DISTANCE + r.z) else 1f
        val unit = h * 0.45f * cam.zoom
        return Pt(w / 2f + r.x * f * unit + cam.panX, h / 2f - r.y * f * unit + cam.panY, r.z)
    }
}

enum class Shade { TOP, FRONT, SIDE }
class Face(val xs: FloatArray, val ys: FloatArray, val shade: Shade)
class Seg(val a: Pt, val b: Pt)
class SceneBar(
    val seriesIndex: Int,
    val index: Int,
    val value: Float,
    val topCenter: Pt,
    val faces: List<Face>,
    val depth: Float,
)

class ChartScene(val floor: List<Seg>, val frame: List<Seg>, val bars: List<SceneBar>)

/** Costruisce la scena 3D (o 2.5D senza prospettiva) in coordinate schermo. Pura: nessuna dipendenza Android. */
object ChartSceneBuilder {
    private const val FLOOR_Y = -0.5f

    fun build(
        series: List<List<Float?>>,
        yMin: Float,
        yMax: Float,
        cam: CameraState,
        w: Float,
        h: Float,
        quality: RenderQuality,
    ): ChartScene {
        val n = series.maxOfOrNull { it.size } ?: 0
        if (n == 0 || w <= 0f || h <= 0f) return ChartScene(emptyList(), emptyList(), emptyList())

        val persp = quality == RenderQuality.FULL_3D
        val s = series.size
        val xHalf = (w / h * 0.9f).coerceIn(1f, 2.3f)
        val laneStep = 0.55f
        val zHalf = max(0.3f, (s - 1) / 2f * laneStep + 0.3f)
        val slot = 2f * xHalf / n
        val hw = slot * 0.36f
        val hd = if (s == 1) 0.28f else 0.2f
        val range = max(1e-6f, yMax - yMin)

        fun p(x: Float, y: Float, z: Float) = Projection.project(Vec3(x, y, z), cam, w, h, persp)
        fun faceOf(shade: Shade, a: Vec3, b: Vec3, c: Vec3, d: Vec3): Face {
            val pa = p(a.x, a.y, a.z); val pb = p(b.x, b.y, b.z); val pc = p(c.x, c.y, c.z); val pd = p(d.x, d.y, d.z)
            return Face(floatArrayOf(pa.x, pb.x, pc.x, pd.x), floatArrayOf(pa.y, pb.y, pc.y, pd.y), shade)
        }

        // Una faccia è visibile se la sua normale ruotata punta verso l'osservatore (z < 0).
        fun visible(nx: Float, ny: Float, nz: Float) = Projection.rotate(Vec3(nx, ny, nz), cam.yaw, cam.pitch).z < 0f
        val vTop = visible(0f, 1f, 0f)
        val vFront = visible(0f, 0f, -1f)
        val vBack = visible(0f, 0f, 1f)
        val vLeft = visible(-1f, 0f, 0f)
        val vRight = visible(1f, 0f, 0f)

        val bars = ArrayList<SceneBar>()
        for (si in 0 until s) {
            val zc = (si - (s - 1) / 2f) * laneStep
            val values = series[si]
            for (i in 0 until n) {
                val v = values.getOrNull(i) ?: continue
                val frac = ((v - yMin) / range).coerceIn(0f, 1f)
                val y1 = FLOOR_Y + max(frac, 0.012f)
                val xc = -xHalf + slot * (i + 0.5f)
                val x0 = xc - hw; val x1 = xc + hw
                val z0 = zc - hd; val z1 = zc + hd
                val y0 = FLOOR_Y

                val faces = ArrayList<Face>(3)
                if (vLeft) faces += faceOf(Shade.SIDE, Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0))
                if (vRight) faces += faceOf(Shade.SIDE, Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0))
                if (vFront) faces += faceOf(Shade.FRONT, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z0))
                if (vBack) faces += faceOf(Shade.FRONT, Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1))
                if (vTop) faces += faceOf(Shade.TOP, Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x0, y1, z1))

                val top = p(xc, y1, zc)
                val depth = Projection.rotate(Vec3(xc, (y0 + y1) / 2f, zc), cam.yaw, cam.pitch).z
                bars += SceneBar(si, i, v, top, faces, depth)
            }
        }
        bars.sortByDescending { it.depth } // dal più lontano al più vicino (algoritmo del pittore)

        val floor = ArrayList<Seg>()
        val frame = ArrayList<Seg>()
        fun rect(y: Float, out: MutableList<Seg>) {
            val c = listOf(Vec3(-xHalf, y, -zHalf), Vec3(xHalf, y, -zHalf), Vec3(xHalf, y, zHalf), Vec3(-xHalf, y, zHalf))
            for (k in 0 until 4) {
                val a = c[k]; val b = c[(k + 1) % 4]
                out += Seg(p(a.x, a.y, a.z), p(b.x, b.y, b.z))
            }
        }
        when (quality) {
            RenderQuality.FULL_3D -> {
                rect(FLOOR_Y, floor)
                for (k in 1..4) {
                    val z = -zHalf + 2f * zHalf * k / 5f
                    floor += Seg(p(-xHalf, FLOOR_Y, z), p(xHalf, FLOOR_Y, z))
                }
                val lines = minOf(n, 6)
                for (k in 1 until lines) {
                    val x = -xHalf + 2f * xHalf * k / lines
                    floor += Seg(p(x, FLOOR_Y, -zHalf), p(x, FLOOR_Y, zHalf))
                }
                rect(FLOOR_Y + 0.5f, frame)
                rect(FLOOR_Y + 1f, frame)
            }
            RenderQuality.LITE_2_5D -> {
                rect(FLOOR_Y, floor)
                rect(FLOOR_Y + 1f, frame)
            }
            RenderQuality.MINIMAL -> rect(FLOOR_Y, floor)
        }
        return ChartScene(floor, frame, bars)
    }

    /** Barra più vicina al punto toccato, entro maxDist pixel (misurata sulla cima della barra). */
    fun nearest(scene: ChartScene, x: Float, y: Float, maxDist: Float): SceneBar? {
        var best: SceneBar? = null
        var bestD = maxDist
        for (b in scene.bars) {
            val d = hypot(b.topCenter.x - x, b.topCenter.y - y)
            if (d <= bestD) { best = b; bestD = d }
        }
        return best
    }
}
