package com.nexus.asisten.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gauge khas Nexus: busur kiri = suhu, busur kanan = baterai,
 * segitiga di tengah = profil (kontrol, gambar, kehalusan) sesuai mode.
 */
class PerfRingView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var heat = 0.3f
    private var battery = 0.5f
    private var profile = floatArrayOf(0.7f, 0.7f, 0.7f)
    private var accent = NX.ACCENT

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = NX.SURFACE2
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val tri = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NX.TEXT2; textAlign = Paint.Align.CENTER
    }

    fun set(heatFrac: Float, batFrac: Float, prof: FloatArray, accentColor: Int) {
        heat = heatFrac.coerceIn(0f, 1f)
        battery = batFrac.coerceIn(0f, 1f)
        profile = prof
        accent = accentColor
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h * 0.46f
        val r = min(w * 0.46f, h * 0.42f)
        val sw = 7f * d
        track.strokeWidth = sw
        fill.strokeWidth = sw
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        // Suhu (kiri): terisi dari bawah ke atas
        c.drawArc(oval, 140f, 80f, false, track)
        val heatColor = when {
            heat > 0.75f -> NX.HOT
            heat > 0.5f -> NX.WARN
            else -> NX.GOOD
        }
        fill.color = heatColor
        c.drawArc(oval, 140f, 80f * heat, false, fill)

        // Baterai (kanan): terisi dari bawah ke atas
        c.drawArc(oval, 320f, 80f, false, track)
        fill.color = if (battery < 0.2f) NX.HOT else NX.GOOD
        c.drawArc(oval, 40f - 80f * battery, 80f * battery, false, fill)

        // Segitiga profil
        val tr = r * 0.62f
        val angles = floatArrayOf(-90f, 30f, 150f)
        val back = Path()
        val front = Path()
        for (i in 0..2) {
            val a = Math.toRadians(angles[i].toDouble())
            val bx = cx + (tr * cos(a)).toFloat()
            val by = cy + (tr * sin(a)).toFloat()
            val k = profile[i].coerceIn(0.1f, 1f)
            val fx = cx + (tr * k * cos(a)).toFloat()
            val fy = cy + (tr * k * sin(a)).toFloat()
            if (i == 0) { back.moveTo(bx, by); front.moveTo(fx, fy) } else { back.lineTo(bx, by); front.lineTo(fx, fy) }
        }
        back.close(); front.close()
        tri.style = Paint.Style.FILL; tri.color = NX.SURFACE2
        c.drawPath(back, tri)
        tri.color = (accent and 0x00FFFFFF) or 0x66000000
        c.drawPath(front, tri)
        tri.style = Paint.Style.STROKE; tri.strokeWidth = 2f * d; tri.color = accent
        tri.strokeJoin = Paint.Join.ROUND
        c.drawPath(front, tri)

        label.textSize = 11f * d
        c.drawText("Kontrol", cx, cy - tr - 6f * d, label)
        c.drawText("Gambar", cx + tr * 0.95f, cy + tr * 0.62f, label)
        c.drawText("Halus", cx - tr * 0.95f, cy + tr * 0.62f, label)
        label.textSize = 12f * d
        c.drawText("Panas", cx - r * 0.78f, cy + r + 14f * d, label)
        c.drawText("Baterai", cx + r * 0.78f, cy + r + 14f * d, label)
    }
}
