package com.nexus.asisten.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Grafik garis kecil (sparkline) untuk FPS: menyimpan 30 nilai terakhir, digambar dengan area gradien. */
class SparkView(ctx: Context, private val color: Int = NX.CYAN) : View(ctx) {
    private val vals = FloatArray(30)
    private var n = 0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.8f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        this.color = this@SparkView.color
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Path()
    private val a = Path()

    fun push(v: Float) {
        if (n < vals.size) vals[n++] = v
        else { System.arraycopy(vals, 1, vals, 0, vals.size - 1); vals[vals.size - 1] = v }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        area.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), withAlpha(color, 0x55), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (n < 2) return
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (i in 0 until n) { lo = minOf(lo, vals[i]); hi = maxOf(hi, vals[i]) }
        if (hi - lo < 20f) { val mid = (hi + lo) / 2f; lo = mid - 10f; hi = mid + 10f }
        val w = width.toFloat()
        val h = height.toFloat() - line.strokeWidth * 2f
        val step = w / (vals.size - 1)
        val x0 = w - (n - 1) * step
        p.reset(); a.reset()
        for (i in 0 until n) {
            val x = x0 + i * step
            val y = line.strokeWidth + h * (1f - (vals[i] - lo) / (hi - lo))
            if (i == 0) { p.moveTo(x, y); a.moveTo(x, height.toFloat()); a.lineTo(x, y) } else { p.lineTo(x, y); a.lineTo(x, y) }
        }
        a.lineTo(w, height.toFloat()); a.close()
        c.drawPath(a, area)
        c.drawPath(p, line)
    }
}
