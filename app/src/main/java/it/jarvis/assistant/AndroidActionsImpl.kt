package it.jarvis.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import it.jarvis.apps.AppSearch
import it.jarvis.apps.AppsRepository
import org.json.JSONObject

/**
 * Azioni Android reali. Non dichiarano successo se non possono saperlo:
 * Android non dice se una startActivity è andata a buon fine, quindi i messaggi parlano di "richiesta inviata".
 */
class AndroidActionsImpl(
    context: Context,
    private val apps: AppsRepository,
) : AndroidActions {
    private val ctx = context.applicationContext

    private fun appIsForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private val backgroundBlocked = ActionResult.NotAvailable(
        "JARVIS non è in primo piano: da Android 10 il sistema blocca l'avvio di altre app dal background. Riapri JARVIS e riprova.",
    )

    override fun openApp(query: String): ActionResult {
        if (!appIsForeground()) return backgroundBlocked
        val match = AppSearch.bestMatch(apps.loadInstalledSync(), query)
            ?: return ActionResult.NotAvailable("Nessuna app installata corrisponde a '$query'.")
        val intent = ctx.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ActionResult.NotAvailable("L'app ${match.label} non ha un'attività avviabile.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        apps.markLaunched(match.packageName)
        return ActionResult.Done("Richiesta di avvio inviata ad Android per ${match.label}.")
    }

    override fun deviceStatus(): JSONObject {
        val o = JSONObject()
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) o.put("battery_percent", 100 * level / scale)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        o.put("ram_total_mb", mi.totalMem / 1_048_576)
        o.put("ram_available_mb", mi.availMem / 1_048_576)
        val fs = StatFs(Environment.getDataDirectory().path)
        o.put("storage_total_gb", fs.totalBytes / 1_000_000_000)
        o.put("storage_free_gb", fs.availableBytes / 1_000_000_000)
        return o
    }

    override fun setTimer(seconds: Int, label: String?): ActionResult {
        if (!appIsForeground()) return backgroundBlocked
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return try {
            ctx.startActivity(intent)
            ActionResult.Done("Timer da $seconds secondi inviato all'app Orologio (può chiedere conferma).")
        } catch (e: android.content.ActivityNotFoundException) {
            ActionResult.NotAvailable("Nessuna app Orologio sul telefono gestisce i timer.")
        }
    }

    override fun openUrl(url: String): ActionResult {
        if (!appIsForeground()) return backgroundBlocked
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.Done("Indirizzo inviato al browser.")
        } catch (e: android.content.ActivityNotFoundException) {
            ActionResult.NotAvailable("Nessun browser disponibile.")
        }
    }
}
