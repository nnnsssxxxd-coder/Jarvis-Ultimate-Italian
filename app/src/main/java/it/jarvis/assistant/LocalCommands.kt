package it.jarvis.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Comandi riconosciuti in locale: zero latenza e funzionano anche senza backend/Internet. */
sealed interface LocalIntent {
    data object Stop : LocalIntent
    data object Time : LocalIntent
    data object Today : LocalIntent
    data object Battery : LocalIntent
    data class Timer(val seconds: Int) : LocalIntent
    data class Alarm(val hour: Int, val minute: Int) : LocalIntent
    data class OpenApp(val name: String) : LocalIntent
}

/** Parser puro (testabile senza Android). Accetta solo richieste brevi: le domande lunghe vanno al modello. */
object LocalIntentParser {
    private val timerRegex = Regex("""(\d{1,4})\s*(second[oi]|minut[oi]|or[ae])""")
    private val alarmRegex = Regex("""(?:alle|per le|ore)\s+(\d{1,2})(?:\s*(?:[:.]|e|\s)\s*(\d{2}))?""")

    fun parse(raw: String): LocalIntent? {
        val t = raw.lowercase(Locale.ITALIAN).trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        val words = t.split(Regex("\\s+"))

        if (t in setOf("stop", "basta", "zitto", "silenzio", "fermati", "taci")) return LocalIntent.Stop

        if (t.startsWith("apri ") || t.startsWith("avvia ") || t.startsWith("lancia ")) {
            val name = t.substringAfter(' ').removePrefix("l'").removePrefix("la ").removePrefix("il ")
                .removePrefix("l'app ").removePrefix("app ").removePrefix("l'applicazione ").trim()
            if (name.isNotEmpty() && words.size <= 5 && !name.startsWith("http")) return LocalIntent.OpenApp(name)
        }

        if (words.size > 9) return null

        if ("timer" in t || "conto alla rovescia" in t) {
            val m = timerRegex.find(t)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: return null
                val secs = when {
                    m.groupValues[2].startsWith("second") -> n
                    m.groupValues[2].startsWith("minut") -> n * 60
                    else -> n * 3600
                }
                if (secs in 1..86_400) return LocalIntent.Timer(secs)
            }
            return null
        }

        if ("sveglia" in t) {
            val m = alarmRegex.find(t) ?: Regex("""(\d{1,2})[:.](\d{2})""").find(t)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: return null
                val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                    ?: when {
                        "e mezza" in t || "e mezzo" in t -> 30
                        "e un quarto" in t || "e quindici" in t -> 15
                        else -> 0
                    }
                if (h in 0..23 && min in 0..59) return LocalIntent.Alarm(h, min)
            }
            return null
        }

        if ("che ora" in t || "che ore" in t || "dimmi l'ora" in t) return LocalIntent.Time
        if ("che giorno" in t || "che data" in t || "data di oggi" in t) return LocalIntent.Today
        if ("batteria" in t && words.size <= 7) return LocalIntent.Battery
        return null
    }
}

class LocalCommands(
    context: Context,
    private val actions: AndroidActions,
) {
    private val ctx = context.applicationContext

    /** Esegue il comando. Restituisce la frase da dire, oppure null se non è un comando locale. */
    fun tryHandle(input: String): String? {
        val intent = LocalIntentParser.parse(input) ?: return null
        val now = Date()
        return when (intent) {
            LocalIntent.Stop -> ""
            LocalIntent.Time -> "Sono le ${SimpleDateFormat("HH:mm", Locale.ITALIAN).format(now)}."
            LocalIntent.Today -> "Oggi è ${SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALIAN).format(now)}."
            LocalIntent.Battery -> {
                val s = actions.deviceStatus()
                if (!s.has("battery_percent")) "Non riesco a leggere la batteria."
                else "La batteria è al ${s.getInt("battery_percent")} percento, " +
                    if (s.optBoolean("charging")) "in carica." else "non in carica."
            }
            is LocalIntent.Timer -> when (val r = actions.setTimer(intent.seconds, null)) {
                is ActionResult.Done -> "Timer da ${describe(intent.seconds)} inviato all'app Orologio."
                is ActionResult.NotAvailable -> r.reason
                is ActionResult.Failed -> r.reason
            }
            is LocalIntent.Alarm -> setAlarm(intent.hour, intent.minute)
            is LocalIntent.OpenApp -> when (val r = actions.openApp(intent.name)) {
                is ActionResult.Done -> "Apro ${intent.name}."
                is ActionResult.NotAvailable -> r.reason
                is ActionResult.Failed -> r.reason
            }
        }
    }

    private fun setAlarm(hour: Int, minute: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Sveglia JARVIS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            "Richiesta inviata all'app Orologio: sveglia alle ${"%02d:%02d".format(hour, minute)}."
        } catch (e: ActivityNotFoundException) {
            "Nessuna app Orologio compatibile trovata."
        } catch (e: SecurityException) {
            "Android non mi ha permesso di impostare la sveglia."
        }
    }

    private fun describe(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} ${if (seconds == 3600) "ora" else "ore"}"
        seconds % 60 == 0 -> "${seconds / 60} ${if (seconds == 60) "minuto" else "minuti"}"
        else -> "$seconds secondi"
    }
}
