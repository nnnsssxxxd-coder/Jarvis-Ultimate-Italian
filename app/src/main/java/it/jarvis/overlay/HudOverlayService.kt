package it.jarvis.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/** Mostra l'HUD sopra le altre app. Richiede "Mostra sopra altre app". Non intercetta i tocchi. */
class HudOverlayService : Service() {

    private var hud: HudFrameView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (hud == null) addHud()
        running = true
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HUD JARVIS", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("HUD JARVIS attivo")
            .setContentText("Spegnilo dal pulsante HUD in JARVIS")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun addHud() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        val view = HudFrameView(this)
        wm.addView(view, params)
        hud = view
    }

    override fun onDestroy() {
        hud?.let {
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        hud = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_hud"
        @Volatile var running: Boolean = false
            private set

        fun toggle(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            val i = Intent(context, HudOverlayService::class.java)
            if (running) context.stopService(i) else context.startForegroundService(i)
            return true
        }
    }
}
