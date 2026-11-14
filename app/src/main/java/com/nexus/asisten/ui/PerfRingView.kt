package com.nexus.asisten.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Gauge Nexus 1.9.1: dua cincin 270°. Cincin luar = baterai (angka besar di tengah),
 * cincin dalam = suhu (hijau, kuning, merah). Bersih dan mudah dibaca sekilas.
 */
class PerfRingView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var heat = 0.3f
    private var battery = 0.5f
    private var accent = NX.ACCENT

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = withAlpha(Color.WHITE, 0x14)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NX.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NX.MUTED; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.14f
    }
    private val rot = Matrix()

    fun set(heatFrac: Float, batFrac: Float, @Suppress("UNUSED_PARAMETER") prof: FloatArray, accentColor: Int) {
        heat = heatFrac.coerceIn(0f, 1f)
        battery = batFrac.coerceIn(0f, 1f)
        accent = accentColor
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val rOut = min(w, h) * 0.5f - 12f * d
        val rIn = rOut - 18f * d
        val sw = 9f * d
        val start = 135f
        val sweep = 270f
        track.strokeWidth = sw
        fill.strokeWidth = sw

        val outer = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        val inner = RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn)

        // Cincin luar: baterai (gradien hijau -> cyan).
        c.drawArc(outer, start, sweep, false, track)
        val low = battery < 0.2f
        val sg = SweepGradient(cx, cy, intArrayOf(if (low) NX.HOT else NX.GOOD, if (low) NX.WARN else NX.CYAN, if (low) NX.HOT else NX.GOOD), floatArrayOf(0f, 0.75f, 1f))
        rot.setRotate(start - 6f, cx, cy)
        sg.setLocalMatrix(rot)
        fill.shader = sg
        glowP.strokeWidth = sw + 7f * d
        glowP.shader = sg
        glowP.alpha = 46
        if (battery > 0.01f) {
            c.drawArc(outer, start, sweep * battery, false, glowP)
            c.drawArc(outer, start, sweep * battery, false, fill)
        }
        fill.shader = null
        glowP.shader = null

        // Cincin dalam: suhu.
        track.strokeWidth = sw * 0.7f
        fill.strokeWidth = sw * 0.7f
        c.drawArc(inner, start, sweep, false, track)
        fill.color = when {
            heat > 0.75f -> NX.HOT
            heat > 0.5f -> NX.WARN
            else -> accent
        }
        c.drawArc(inner, start, sweep * heat.coerceAtLeast(0.02f), false, fill)

        // Angka di tengah.
        big.textSize = 30f * d
        c.drawText("${(battery * 100f).toInt()}%", cx, cy + 6f * d, big)
        small.textSize = 9.5f * d
        c.drawText("BATERAI", cx, cy + 24f * d, small)
        small.color = fill.color
        c.drawText("SUHU", cx, cy - 26f * d, small)
        small.color = NX.MUTED
    }
}
