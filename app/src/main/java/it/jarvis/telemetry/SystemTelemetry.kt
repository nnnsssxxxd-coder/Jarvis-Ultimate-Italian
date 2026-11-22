package it.jarvis.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Un campionamento di dati REALI. Ogni campo null = non disponibile su questo dispositivo/ora. */
data class TelemetrySample(
    val tsMs: Long,
    val cpuAppPercent: Float?,      // CPU usata dal processo JARVIS, % della capacità totale
    val cpuSystemPercent: Float?,   // CPU di sistema: Android 8+ di solito blocca /proc/stat alle app -> null
    val ramUsedPercent: Float,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val storageUsedPercent: Float,
    val storageFreeGb: Float,
    val batteryPercent: Float?,
    val charging: Boolean,
    val batteryTempC: Float?,       // dall'intent batteria; null se il dispositivo non lo espone
    val netDownKbps: Float?,
    val netUpKbps: Float?,
)

data class CpuTimes(val idle: Long, val total: Long)

object CpuStatParser {
    fun parse(line: String): CpuTimes? {
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 5 || p[0] != "cpu") return null
        val nums = p.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums[3] + (nums.getOrNull(4) ?: 0L) // idle + iowait
        return CpuTimes(idle = idle, total = nums.take(8).sum())
    }

    fun usage(prev: CpuTimes, cur: CpuTimes): Float? {
        val dt = cur.total - prev.total
        val di = cur.idle - prev.idle
        if (dt <= 0) return null
        return (100f * (dt - di) / dt).coerceIn(0f, 100f)
    }

    fun read(): CpuTimes? = runCatching {
        File("/proc/stat").bufferedReader().use { it.readLine() }?.let(::parse)
    }.getOrNull()
}

/** Velocità in kb/s da due contatori di byte. null se il contatore non è supportato o è tornato indietro. */
fun rateKbps(prevBytes: Long, curBytes: Long, dtMs: Long): Float? {
    if (prevBytes < 0 || curBytes < 0 || dtMs <= 0 || curBytes < prevBytes) return null
    return (curBytes - prevBytes) * 8f / 1000f / (dtMs / 1000f)
}

class SystemTelemetry(context: Context) {
    private val app = context.applicationContext
    private val am = app.getSystemService(ActivityManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var prevProcCpu = -1L
    private var prevWall = -1L
    private var prevSys: CpuTimes? = null
    private var prevRx = -1L
    private var prevTx = -1L
    private var prevNetWall = -1L

    private fun rx() = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: -1L
    private fun tx() = TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: -1L

    private fun sample(): TelemetrySample {
        val nowWall = SystemClock.elapsedRealtime()

        val procCpu = Process.getElapsedCpuTime()
        val cpuApp = if (prevProcCpu >= 0 && nowWall > prevWall) {
            (100f * (procCpu - prevProcCpu) / (nowWall - prevWall) / cores).coerceIn(0f, 100f)
        } else null
        prevProcCpu = procCpu
        prevWall = nowWall

        val sys = CpuStatParser.read()
        val cpuSys = if (sys != null && prevSys != null) CpuStatParser.usage(prevSys!!, sys) else null
        prevSys = sys

        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mi.totalMem / 1_048_576
        val usedMb = (mi.totalMem - mi.availMem) / 1_048_576

        val fs = StatFs(Environment.getDataDirectory().path)
        val storageTotal = fs.totalBytes.toFloat()
        val storageFree = fs.availableBytes.toFloat()

        val bat: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val rxNow = rx()
        val txNow = tx()
        val down = rateKbps(prevRx, rxNow, nowWall - prevNetWall)
        val up = rateKbps(prevTx, txNow, nowWall - prevNetWall)
        prevRx = rxNow
        prevTx = txNow
        prevNetWall = nowWall

        return TelemetrySample(
            tsMs = System.currentTimeMillis(),
            cpuAppPercent = cpuApp,
            cpuSystemPercent = cpuSys,
            ramUsedPercent = if (mi.totalMem > 0) 100f * (mi.totalMem - mi.availMem) / mi.totalMem else 0f,
            ramUsedMb = usedMb,
            ramTotalMb = totalMb,
            storageUsedPercent = if (storageTotal > 0) 100f * (storageTotal - storageFree) / storageTotal else 0f,
            storageFreeGb = storageFree / 1_000_000_000f,
            batteryPercent = if (level >= 0 && scale > 0) 100f * level / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempC = if (tempTenths != Int.MIN_VALUE && tempTenths > 0) tempTenths / 10f else null,
            netDownKbps = down,
            netUpKbps = up,
        )
    }

    fun stream(intervalMs: Long = 1000): Flow<TelemetrySample> = flow {
        while (true) {
            emit(sample())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Default)
}

/**
 * Storico degli ultimi `capacity` campioni. Il campionamento parte solo se qualcuno osserva (WhileSubscribed):
 * con l'app in background e UI che usa collectWhileStarted() si ferma da solo.
 */
class TelemetryHistory(
    scope: CoroutineScope,
    telemetry: SystemTelemetry,
    capacity: Int = 60,
    intervalMs: Long = 1000,
) {
    val samples: StateFlow<List<TelemetrySample>> =
        telemetry.stream(intervalMs)
            .scan(emptyList<TelemetrySample>()) { acc, s -> (acc + s).takeLast(capacity) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
