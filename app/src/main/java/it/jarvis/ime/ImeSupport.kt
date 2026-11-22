package it.jarvis.ime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import it.jarvis.net.ChatBackend
import java.util.Locale

/** Punto di accesso condiviso: JarvisApp.onCreate() imposta il backend, la tastiera lo usa per i comandi AI. */
object JarvisServices {
    @Volatile var backend: ChatBackend? = null
}

/** Android NON permette di attivare una tastiera in automatico: l'utente deve farlo dalle impostazioni. */
object ImeSetup {
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == ctx.packageName }

    fun isDefault(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(ctx.packageName + "/") == true

    /** Passo 1: apre l'elenco delle tastiere dove l'utente abilita "Tastiera JARVIS". */
    fun openEnableScreen(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Passo 2: selettore di sistema per scegliere la tastiera attiva (richiede che sia già abilitata). */
    fun showPicker(ctx: Context) {
        ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }
}

/** Suggerimenti e correzione leggera: parole comuni + parole imparate localmente. Nessun dato lascia il telefono. */
class WordSuggester(initialLearned: Map<String, Int> = emptyMap(), private val base: List<String> = DEFAULT_WORDS) {
    private val learned = HashMap(initialLearned)

    fun learn(word: String) {
        val w = word.lowercase(Locale.ROOT)
        if (w.length < 3 || w.length > 24 || !w.all { it.isLetter() }) return
        learned[w] = (learned[w] ?: 0) + 1
    }

    fun suggest(prefixRaw: String, limit: Int = 3): List<String> {
        val prefix = prefixRaw.lowercase(Locale.ROOT)
        if (prefix.isEmpty()) return emptyList()
        val scored = HashMap<String, Int>()
        for ((w, c) in learned) if (w.startsWith(prefix) && w != prefix) scored[w] = c * 10
        base.forEachIndexed { i, w -> if (w.startsWith(prefix) && w != prefix) scored.merge(w, 5 + (base.size - i) / 20, Int::plus) }
        var result = scored.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length }).map { it.key }
        if (result.isEmpty() && prefix.length >= 3) {
            // nessun completamento: prova una correzione a distanza 1 (es. "cioa" -> "ciao")
            result = (learned.keys + base).filter { it != prefix && withinOneEdit(prefix, it) }.sortedByDescending { learned[it] ?: 0 }
        }
        val capitalise = prefixRaw.first().isUpperCase()
        return result.take(limit).map { if (capitalise) it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) } else it }
    }

    fun serialize(): String =
        learned.entries.sortedByDescending { it.value }.take(500).joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        fun deserialize(text: String?): Map<String, Int> =
            text.orEmpty().lines().mapNotNull { l ->
                val p = l.split('\t')
                if (p.size == 2) p[1].toIntOrNull()?.let { p[0] to it } else null
            }.toMap()

        /** true se a e b differiscono per una sola sostituzione, inserzione, cancellazione o scambio adiacente. */
        fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            if (a.length == b.length) {
                val diff = a.indices.filter { a[it] != b[it] }
                if (diff.size == 1) return true
                return diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
            }
            val (s, l) = if (a.length < b.length) a to b else b to a
            var i = 0
            while (i < s.length && s[i] == l[i]) i++
            return s.substring(i) == l.substring(i + 1)
        }

        val DEFAULT_WORDS = listOf(
            "ciao", "come", "sono", "della", "questo", "perché", "grazie", "prego", "buongiorno", "buonasera",
            "quando", "dove", "anche", "ancora", "sempre", "adesso", "domani", "oggi", "stasera", "messaggio",
            "telefono", "casa", "lavoro", "tempo", "giorno", "fatto", "essere", "avere", "fare", "potere",
            "volere", "dovere", "stare", "andare", "venire", "vedere", "sapere", "dire", "dare", "molto",
            "poco", "tutto", "niente", "qualcosa", "persona", "amico", "amica", "famiglia", "scuola", "scusa",
            "scusami", "okay", "certo", "forse", "dopo", "prima", "subito", "presto", "tardi", "weekend",
            "appuntamento", "riunione", "ufficio", "chiamami", "scrivimi", "mandami", "ricordami", "arrivo",
            "arrivato", "partito", "stasera", "mattina", "pomeriggio", "sera", "notte", "buonanotte", "bene",
            "male", "meglio", "peggio", "problema", "soluzione", "domanda", "risposta", "informazioni",
            "documento", "foto", "video", "musica", "viaggio", "treno", "autobus", "macchina", "ristorante",
            "pranzo", "cena", "colazione", "caffè", "weekend", "vacanza", "compleanno", "auguri", "felice",
            "contento", "triste", "stanco", "occupato", "libero", "disponibile", "possibile", "importante",
        )
    }
}
