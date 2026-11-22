package it.jarvis

import android.Manifest
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import it.jarvis.apps.AppsRepository
import it.jarvis.assistant.AndroidActionsImpl
import it.jarvis.assistant.VoiceAssistant
import it.jarvis.core.GlobalErrorLog
import it.jarvis.diag.DiagnosticsController
import it.jarvis.ime.JarvisServices
import it.jarvis.net.GlobalApiMetrics
import it.jarvis.net.JarvisBackendClient
import it.jarvis.net.SessionProvider
import it.jarvis.overlay.HudOverlayService
import it.jarvis.telemetry.SystemTelemetry
import it.jarvis.telemetry.TelemetryHistory
import it.jarvis.ui.JarvisRoot
import it.jarvis.ui.assistant.AssistantPanel
import it.jarvis.voice.JarvisEars
import it.jarvis.voice.JarvisVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JarvisApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var backend: JarvisBackendClient
    lateinit var apps: AppsRepository
    lateinit var history: TelemetryHistory
    lateinit var diagnostics: DiagnosticsController

    override fun onCreate() {
        super.onCreate()
        apps = AppsRepository(this)
        history = TelemetryHistory(appScope, SystemTelemetry(this))
        backend = JarvisBackendClient(
            baseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            session = SupabaseAnonymousSession(
                baseUrl = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                preferences = createEncryptedPrefs(),
            ),
        )
        diagnostics = DiagnosticsController(backend)
        JarvisServices.backend = backend
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            "jarvis_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/**
 * Sessione anonima Supabase con mutex per evitare signup/refresh concorrenti.
 * I token restano nelle preferenze cifrate dell'app e non vengono mai loggati.
 */
private class SupabaseAnonymousSession(
    private val baseUrl: String,
    private val publishableKey: String,
    private val preferences: SharedPreferences,
) : SessionProvider {

    private val authMutex = Mutex()

    override suspend fun accessToken(forceRefresh: Boolean): String = authMutex.withLock {
        withContext(Dispatchers.IO) {
            require(baseUrl.startsWith("https://") && publishableKey.isNotBlank()) {
                "Configura SUPABASE_URL e SUPABASE_PUBLISHABLE_KEY nei GitHub Actions secrets."
            }

            val cachedAccess = preferences.getString(KEY_ACCESS_TOKEN, null)
            val cachedRefresh = preferences.getString(KEY_REFRESH_TOKEN, null)
            if (!forceRefresh && !cachedAccess.isNullOrBlank()) {
                return@withContext cachedAccess
            }

            val cleanBase = baseUrl.trimEnd('/')
            val hasRefresh = !cachedRefresh.isNullOrBlank()
            val endpoint = if (hasRefresh) {
                "$cleanBase/auth/v1/token?grant_type=refresh_token"
            } else {
                "$cleanBase/auth/v1/signup"
            }
            val payload = if (hasRefresh) {
                JSONObject().put("refresh_token", cachedRefresh)
            } else {
                JSONObject().put("data", JSONObject())
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $publishableKey")
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { out ->
                    out.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (status !in 200..299) {
                    // Se il refresh token è scaduto/non valido, pulisci e riprova con signup al giro successivo
                    if (hasRefresh && status in 400..401) {
                        preferences.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
                    }
                    throw IllegalStateException("Supabase Auth ha risposto HTTP $status.")
                }

                val json = JSONObject(body)
                val newAccess = json.optString("access_token")
                val newRefresh = json.optString("refresh_token", cachedRefresh.orEmpty())
                require(newAccess.isNotBlank()) {
                    "Supabase Auth non ha restituito un access token."
                }

                preferences.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccess)
                    .putString(KEY_REFRESH_TOKEN, newRefresh)
                    .apply()

                newAccess
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var assistant: VoiceAssistant
    private lateinit var ears: JarvisEars
    private lateinit var voice: JarvisVoice
    private val hudOn = mutableStateOf(false)
    private var afterMicPermission: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterMicPermission
        afterMicPermission = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(
                this,
                "Senza il permesso del microfono JARVIS non può ascoltarti. Puoi scrivergli dal campo di testo.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun withMic(action: () -> Unit) {
        if (hasMicPermission()) {
            action()
        } else {
            afterMicPermission = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleHud() {
        val wasOn = HudOverlayService.running
        val ok = HudOverlayService.toggle(this)
        if (!ok) {
            Toast.makeText(
                this,
                "Consenti \"Mostra sopra altre app\" a JARVIS, poi premi di nuovo HUD",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            hudOn.value = !wasOn
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JarvisApplication
        ears = JarvisEars(this)
        voice = JarvisVoice(this)
        assistant = VoiceAssistant(
            context = this,
            scope = lifecycleScope,
            backend = { if (BuildConfig.SUPABASE_URL.isBlank()) null else app.backend },
            actions = AndroidActionsImpl(this, app.apps),
            ears = ears,
            voice = voice,
        )

        setContent {
            val ttsReady by voice.isReady.collectAsStateWithLifecycle()
            val micGranted = hasMicPermission()
            val aiConfigured = BuildConfig.SUPABASE_URL.isNotBlank()

            JarvisRoot(
                history = app.history,
                metrics = GlobalApiMetrics,
                errorLog = GlobalErrorLog,
                apps = app.apps,
                diagnostics = app.diagnostics,
                coreState = assistant.coreState,
                micLevel = assistant.micLevel,
                voiceRows = listOf(
                    "Riconoscimento vocale" to if (ears.isAvailable) "Disponibile" else "Non disponibile",
                    "Sintesi vocale (italiano)" to if (ttsReady) "Pronta" else "Non pronta",
                    "Microfono" to if (micGranted) "Permesso concesso" else "Permesso mancante",
                ),
                onCoreTap = { withMic { assistant.onCoreTap() } },
                statusLine = if (aiConfigured) {
                    "Dashboard JARVIS · tocca il nucleo e parla"
                } else {
                    "Modalità locale · AI da configurare"
                },
                overlay = {
                    AssistantPanel(
                        assistant = assistant,
                        hudOn = hudOn.value,
                        onToggleHud = ::toggleHud,
                        onToggleContinuous = { withMic { assistant.setContinuous(!assistant.continuous.value) } },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hudOn.value = HudOverlayService.running
    }

    override fun onDestroy() {
        assistant.shutdown()
        super.onDestroy()
    }
}
