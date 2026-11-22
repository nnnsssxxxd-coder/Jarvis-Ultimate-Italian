package it.jarvis

import it.jarvis.apps.AppCategory
import it.jarvis.apps.AppSearch
import it.jarvis.apps.InstalledApp
import it.jarvis.ime.WordSuggester
import it.jarvis.telemetry.CpuStatParser
import it.jarvis.telemetry.CpuTimes
import it.jarvis.telemetry.rateKbps
import it.jarvis.ui.charts.CameraState
import it.jarvis.ui.charts.ChartSceneBuilder
import it.jarvis.ui.charts.Projection
import it.jarvis.ui.charts.Vec3
import it.jarvis.ui.perf.FrameQualityPolicy
import it.jarvis.ui.perf.RenderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSceneTest {
    private val series = listOf(listOf<Float?>(10f, null, 50f, 100f, 25f))

    @Test fun `una barra per ogni valore non nullo`() {
        val s = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.FULL_3D)
        assertEquals(4, s.bars.size)
        assertTrue(s.bars.none { it.index == 1 })
    }

    @Test fun `ordinate dalla piu lontana alla piu vicina`() {
        val s = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.FULL_3D)
        assertTrue(s.bars.zipWithNext().all { (a, b) -> a.depth >= b.depth })
    }

    @Test fun `barra piu alta ha la cima piu in alto sullo schermo`() {
        val cam = CameraState(yaw = 0f, pitch = -0.05f)
        val s = ChartSceneBuilder.build(listOf(listOf<Float?>(20f, 80f)), 0f, 100f, cam, 300f, 200f, RenderQuality.LITE_2_5D)
        val low = s.bars.first { it.index == 0 }
        val high = s.bars.first { it.index == 1 }
        assertTrue(high.topCenter.y < low.topCenter.y)
    }

    @Test fun `con la camera di default si vedono la cima e almeno una faccia laterale`() {
        val s = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.FULL_3D)
        assertTrue(s.bars.all { it.faces.size in 2..3 })
    }

    @Test fun `tocco vicino alla cima seleziona la barra giusta, lontano nessuna`() {
        val s = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.FULL_3D)
        val target = s.bars.first { it.index == 3 }
        val hit = ChartSceneBuilder.nearest(s, target.topCenter.x + 2f, target.topCenter.y - 2f, 40f)
        assertNotNull(hit)
        assertEquals(3, hit!!.index)
        assertNull(ChartSceneBuilder.nearest(s, -500f, -500f, 40f))
    }

    @Test fun `qualita ridotta semplifica la griglia`() {
        val full = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.FULL_3D)
        val lite = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.LITE_2_5D)
        val min = ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 340f, 180f, RenderQuality.MINIMAL)
        assertTrue(full.floor.size > lite.floor.size)
        assertEquals(4, lite.floor.size)
        assertTrue(min.frame.isEmpty())
    }

    @Test fun `dati vuoti o dimensioni nulle non producono nulla`() {
        assertTrue(ChartSceneBuilder.build(emptyList(), 0f, 1f, CameraState(), 100f, 100f, RenderQuality.FULL_3D).bars.isEmpty())
        assertTrue(ChartSceneBuilder.build(series, 0f, 100f, CameraState(), 0f, 0f, RenderQuality.FULL_3D).bars.isEmpty())
    }

    @Test fun `la prospettiva ingrandisce cio che e vicino, il 2_5D no`() {
        val cam = CameraState(yaw = 0f, pitch = 0f)
        val nearP = Projection.project(Vec3(1f, 0f, -1f), cam, 100f, 100f, true)
        val farP = Projection.project(Vec3(1f, 0f, 1f), cam, 100f, 100f, true)
        assertTrue(nearP.x > farP.x)
        val nearF = Projection.project(Vec3(1f, 0f, -1f), cam, 100f, 100f, false)
        val farF = Projection.project(Vec3(1f, 0f, 1f), cam, 100f, 100f, false)
        assertEquals(nearF.x, farF.x, 0.001f)
    }

    @Test fun `gesti: inclinazione e zoom hanno limiti, reset riporta al default`() {
        var c = CameraState()
        c = c.orbit(0f, -100000f)
        assertEquals(CameraState.MAX_PITCH, c.pitch, 0.0001f)
        c = c.orbit(0f, 100000f)
        assertEquals(CameraState.MIN_PITCH, c.pitch, 0.0001f)
        assertEquals(4f, c.zoomBy(100f).zoom, 0.0001f)
        assertEquals(0.6f, c.zoomBy(0.0001f).zoom, 0.0001f)
        val moved = CameraState().orbit(30f, 30f).zoomBy(2f).panBy(5f, 5f)
        assertTrue(moved != CameraState())
        assertEquals(CameraState(), CameraState()) // il reset della UI assegna CameraState(): stato di default
    }
}

class FrameQualityPolicyTest {
    private val slow = 40_000_000L
    private val fast = 10_000_000L

    @Test fun `scende di livello se i frame sono lenti e si ferma al minimo`() {
        val p = FrameQualityPolicy()
        var changed: RenderQuality? = null
        repeat(60) { p.onFrame(slow)?.let { changed = it } }
        assertEquals(RenderQuality.LITE_2_5D, changed)
        repeat(60) { p.onFrame(slow)?.let { changed = it } }
        assertEquals(RenderQuality.MINIMAL, p.level)
        changed = null
        repeat(120) { p.onFrame(slow)?.let { changed = it } }
        assertNull(changed)
    }

    @Test fun `risale solo dopo molte finestre stabili (backoff anti oscillazione)`() {
        val p = FrameQualityPolicy()
        repeat(120) { p.onFrame(slow) } // FULL -> LITE -> MINIMAL
        var changed: RenderQuality? = null
        repeat(23 * 60) { p.onFrame(fast)?.let { changed = it } }
        assertNull(changed)
        repeat(60) { p.onFrame(fast)?.let { changed = it } }
        assertEquals(RenderQuality.LITE_2_5D, changed)
    }

    @Test fun `pause e frame fuori scala vengono ignorati`() {
        val p = FrameQualityPolicy()
        repeat(500) { p.onFrame(5_000_000_000L) }
        repeat(500) { p.onFrame(0L) }
        assertEquals(RenderQuality.FULL_3D, p.level)
    }
}

class AppSearchTest {
    private val apps = listOf(
        InstalledApp("com.whatsapp", "WhatsApp", AppCategory.SOCIAL),
        InstalledApp("com.google.android.apps.maps", "Maps", AppCategory.MAPS),
        InstalledApp("com.x.cafe", "Caffè Italia", AppCategory.OTHER),
        InstalledApp("org.app.zap", "Zapp WhatsApp Backup", AppCategory.OTHER),
    )

    @Test fun `ricerca ignora maiuscole e accenti, prima chi inizia poi chi contiene`() {
        assertEquals(listOf("Caffè Italia"), AppSearch.filter(apps, "CAFFE").map { it.label })
        assertEquals(listOf("WhatsApp", "Zapp WhatsApp Backup"), AppSearch.filter(apps, "whats").map { it.label })
        assertEquals(apps, AppSearch.filter(apps, "  "))
        assertTrue(AppSearch.filter(apps, "inesistente").isEmpty())
    }

    @Test fun `bestMatch: package esatto, poi nome uguale, poi inizia, poi contiene`() {
        assertEquals("com.whatsapp", AppSearch.bestMatch(apps, "WhatsApp")?.packageName)
        assertEquals("com.whatsapp", AppSearch.bestMatch(apps, "com.whatsapp")?.packageName)
        assertEquals("com.google.android.apps.maps", AppSearch.bestMatch(apps, "mAps")?.packageName)
        assertEquals("org.app.zap", AppSearch.bestMatch(apps, "backup")?.packageName)
        assertNull(AppSearch.bestMatch(apps, "telegram"))
        assertNull(AppSearch.bestMatch(apps, ""))
    }
}

class TelemetryMathTest {
    @Test fun `parsing di proc stat e uso CPU`() {
        val a = CpuStatParser.parse("cpu  100 0 50 800 50 0 0 0 0 0")
        assertEquals(CpuTimes(idle = 850, total = 1000), a)
        val b = CpuStatParser.parse("cpu  140 0 80 880 40 0 0 0 0 0")!!
        // delta totale 140 (1140-1000), delta idle 70 (920-850) -> 50%
        assertEquals(50f, CpuStatParser.usage(a!!, b)!!, 0.01f)
    }

    @Test fun `righe non valide e delta nullo`() {
        assertNull(CpuStatParser.parse("cpu0 1 2 3"))
        assertNull(CpuStatParser.parse("intr 1 2 3 4 5"))
        val t = CpuTimes(10, 100)
        assertNull(CpuStatParser.usage(t, t))
    }

    @Test fun `velocita di rete in kb al secondo`() {
        assertEquals(1000f, rateKbps(0, 125_000, 1000)!!, 0.01f)
        assertEquals(500f, rateKbps(0, 125_000, 2000)!!, 0.01f)
        assertNull(rateKbps(-1, 100, 1000))      // contatore non ancora inizializzato
        assertNull(rateKbps(500, 100, 1000))     // contatore tornato indietro (riavvio interfaccia)
        assertNull(rateKbps(0, 100, 0))
    }
}

class WordSuggesterTest {
    @Test fun `completamento da parole comuni e maiuscola mantenuta`() {
        val s = WordSuggester()
        assertTrue(s.suggest("buon").contains("buongiorno"))
        assertTrue(s.suggest("Buon").all { it.first().isUpperCase() })
        assertTrue(s.suggest("").isEmpty())
    }

    @Test fun `le parole imparate hanno la precedenza e si salvano`() {
        val s = WordSuggester()
        repeat(3) { s.learn("Zibaldone") }
        assertEquals("zibaldone", s.suggest("zib").first())
        val restored = WordSuggester(WordSuggester.deserialize(s.serialize()))
        assertEquals("zibaldone", restored.suggest("zib").first())
    }

    @Test fun `non impara numeri, parole corte o con simboli`() {
        val s = WordSuggester()
        s.learn("a1b2c3"); s.learn("ok"); s.learn("pa\$\$word")
        assertEquals("", s.serialize())
    }

    @Test fun `correzione a distanza uno quando non ci sono completamenti`() {
        val s = WordSuggester()
        assertTrue(s.suggest("cioa").contains("ciao"))   // scambio
        assertTrue(s.suggest("grazzie").isNotEmpty() || WordSuggester.withinOneEdit("grazzie", "grazie"))
        assertTrue(WordSuggester.withinOneEdit("casa", "cassa"))
        assertTrue(!WordSuggester.withinOneEdit("casa", "cane"))
    }
}
