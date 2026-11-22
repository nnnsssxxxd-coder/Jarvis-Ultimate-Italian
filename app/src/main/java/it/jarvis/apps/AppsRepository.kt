package it.jarvis.apps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

enum class AppCategory(val label: String) {
    GAMES("Giochi"), MEDIA("Media"), SOCIAL("Social"), NEWS("Notizie"),
    MAPS("Mappe"), PRODUCTIVITY("Produttività"), SYSTEM("Sistema"), OTHER("Altro"),
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val category: AppCategory,
)

object AppSearch {
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

    /** Filtro istantaneo: prima i nomi che iniziano con la query, poi quelli che la contengono. */
    fun filter(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val q = normalize(query)
        if (q.isEmpty()) return apps
        val starts = ArrayList<InstalledApp>()
        val contains = ArrayList<InstalledApp>()
        for (a in apps) {
            val n = normalize(a.label)
            when {
                n.startsWith(q) -> starts += a
                n.contains(q) || a.packageName.lowercase(Locale.ROOT).contains(q) -> contains += a
            }
        }
        return starts + contains
    }

    /** Migliore corrispondenza per un comando vocale/testuale: uguale > inizia con > contiene. */
    fun bestMatch(apps: List<InstalledApp>, query: String): InstalledApp? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        apps.firstOrNull { it.packageName.equals(query.trim(), ignoreCase = true) }?.let { return it }
        apps.firstOrNull { normalize(it.label) == q }?.let { return it }
        apps.firstOrNull { normalize(it.label).startsWith(q) }?.let { return it }
        return apps.firstOrNull { normalize(it.label).contains(q) }
    }
}

/**
 * Elenco delle app realmente installate (solo quelle con icona nel launcher).
 * Richiede in AndroidManifest: <queries><intent><action MAIN/><category LAUNCHER/></intent></queries>
 */
class AppsRepository(
    context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_apps", Context.MODE_PRIVATE),
) {
    private val ctx = context.applicationContext

    suspend fun loadInstalled(): List<InstalledApp> = withContext(Dispatchers.IO) { loadInstalledSync() }

    fun loadInstalledSync(): List<InstalledApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return infos
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                InstalledApp(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), categoryOf(ai))
            }
            .distinctBy { it.packageName }
            .sortedBy { AppSearch.normalize(it.label) }
    }

    private fun categoryOf(ai: ApplicationInfo): AppCategory {
        if (Build.VERSION.SDK_INT >= 26) {
            when (ai.category) {
                ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.MEDIA
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_NEWS -> return AppCategory.NEWS
                ApplicationInfo.CATEGORY_MAPS -> return AppCategory.MAPS
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
            }
        }
        return if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) AppCategory.SYSTEM else AppCategory.OTHER
    }

    fun loadIcon(packageName: String, sizePx: Int = 96): ImageBitmap? = runCatching {
        ctx.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
    }.getOrNull()

    fun launch(packageName: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        markLaunched(packageName)
        return true
    }

    // ---- preferiti e recenti (solo nomi di package, in locale) ----
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun toggleFavorite(packageName: String): Boolean {
        val set = favorites().toMutableSet()
        val nowFav = if (set.remove(packageName)) false else { set.add(packageName); true }
        prefs.edit().putStringSet("favorites", set).apply()
        return nowFav
    }

    fun recents(): List<String> = prefs.getString("recents", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun markLaunched(packageName: String) {
        val list = (listOf(packageName) + recents().filter { it != packageName }).take(12)
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
    }
}
