package com.nexus.asisten.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Cincin tunggal 270° dengan angka besar di tengah (baterai, suhu) untuk kartu "Perangkat sekarang". */
class RingGauge(ctx: Context) : View(ctx) {
    private var frac = 0f
    private var big = "--"
    private var small = ""
    private var c1 = NX.GOOD
    private var c2 = NX.CYAN

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = withAlpha(Color.WHITE, 0x16) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NX.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NX.MUTED; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rot = Matrix()

    fun set(f: Float, bigText: String, smallText: String, colorA: Int, colorB: Int) {
        frac = f.coerceIn(0f, 1f); big = bigText; small = smallText; c1 = colorA; c2 = colorB
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val sw = 8f * d
        val r = min(w, h) / 2f - sw
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = sw
        c.drawArc(oval, 135f, 270f, false, track)
        if (frac > 0.01f) {
            val sg = SweepGradient(cx, cy, intArrayOf(c1, c2, c1), floatArrayOf(0f, 0.75f, 1f))
            rot.setRotate(129f, cx, cy)
            sg.setLocalMatrix(rot)
            fill.shader = sg; fill.strokeWidth = sw
            glow.shader = sg; glow.strokeWidth = sw + 6f * d; glow.alpha = 48
            c.drawArc(oval, 135f, 270f * frac, false, glow)
            c.drawArc(oval, 135f, 270f * frac, false, fill)
        }
        bigP.textSize = 26f * d
        c.drawText(big, cx, cy + 6f * d, bigP)
        smallP.textSize = 10.5f * d
        c.drawText(small, cx, cy + 22f * d, smallP)
    }
}
