package it.jarvis.overlay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cornice HUD "a casco": angoli, tacche sui bordi, archi da visiera, ora e batteria in alto.
 * Solo disegno: non gestisce tocchi (la finestra è anche FLAG_NOT_TOUCHABLE).
 */
class HudFrameView(context: Context) : View(context) {

    private val cyan = Color.argb(200, 0, 229, 255)
    private val cyanSoft = Color.argb(90, 0, 229, 255)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = cyan
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = cyanSoft
        strokeWidth = dp(1f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        textSize = dp(13f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
    private var label = ""
    private val ticker = object : Runnable {
        override fun run() {
            label = "${timeFormat.format(Date())}  ·  BAT ${batteryPercent()}%"
            invalidate()
            postDelayed(this, 1_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun batteryPercent(): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else 0
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = dp(10f)       // margine
        val len = dp(36f)     // lunghezza angoli

        // Angoli
        c.drawLine(m, m, m + len, m, stroke);         c.drawLine(m, m, m, m + len, stroke)
        c.drawLine(w - m, m, w - m - len, m, stroke); c.drawLine(w - m, m, w - m, m + len, stroke)
        c.drawLine(m, h - m, m + len, h - m, stroke); c.drawLine(m, h - m, m, h - m - len, stroke)
        c.drawLine(w - m, h - m, w - m - len, h - m, stroke); c.drawLine(w - m, h - m, w - m, h - m - len, stroke)

        // Tacche sui bordi laterali
        val step = dp(24f)
        var y = m + len + step
        while (y < h - m - len) {
            c.drawLine(m, y, m + dp(8f), y, soft)
            c.drawLine(w - m, y, w - m - dp(8f), y, soft)
            y += step
        }

        // Archi da visiera (alto e basso)
        val r = dp(60f)
        val top = RectF(w / 2 - r * 2, m, w / 2 + r * 2, m + r * 2)
        val bottom = RectF(w / 2 - r * 2, h - m - r * 2, w / 2 + r * 2, h - m)
        c.drawArc(top, 215f, 110f, false, stroke)
        c.drawArc(bottom, 35f, 110f, false, stroke)

        // Ora e batteria
        c.drawText(label, w / 2, m + dp(28f), text)
    }
}
