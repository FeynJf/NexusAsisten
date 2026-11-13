package com.nexus.asisten.overlay

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import com.nexus.asisten.ui.NX

/**
 * Latar panel overlay: SATU bentuk kubah 180° (busur setengah elips di atas, dinding lurus di bawah).
 * Kaca indigo yang tembus pandang (game tetap terlihat), kilau ungu di atas, bingkai neon berpendar,
 * satu cahaya yang berputar pelan mengitari tepi, dan jalur putus-putus tempat tombol aksi berjajar.
 */
class DomeDrawable(
    private val a: Float,       // setengah lebar
    private val rho: Float,     // jari-jari vertikal busur
    private val s: Float,       // tinggi bagian lurus di bawah busur
    private val inset: Float,   // jarak jalur tombol dari tepi
    private val corner: Float,  // radius sudut bawah
    private val px: Float       // 1dp dalam piksel
) : Drawable() {

    private val path = Path()
    private val ring = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val runner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val runnerShader: SweepGradient
    private val runnerMatrix = Matrix()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    init {
        val w = 2 * a
        val h = rho + s
        val m = px * 4f            // sisakan ruang untuk tebal bingkai & pendar
        path.moveTo(m, h - corner)
        path.lineTo(m, rho)
        path.arcTo(RectF(m, m, w - m, 2 * rho - m), 180f, 180f, false)
        path.lineTo(w - m, h - corner)
        path.arcTo(RectF(w - m - 2 * corner, h - 2 * corner - m, w - m, h - m), 0f, 90f, false)
        path.lineTo(m + corner, h - m)
        path.arcTo(RectF(m, h - 2 * corner - m, m + 2 * corner, h - m), 90f, 90f, false)
        path.close()

        ring.arcTo(RectF(inset, inset, w - inset, 2 * rho - inset), 180f, 180f, false)

        // Kaca tembus pandang: ~70% di atas, ~78% di bawah (bukan hitam pekat).
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xB52C2370.toInt(), 0xB9161D4A.toInt(), 0xC70A1030.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        sheen.shader = RadialGradient(a, rho * 0.45f, a * 0.95f, intArrayOf(0x4A8A6CFF, 0x00000000), null, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(NX.ACCENT, NX.CYAN, NX.ACCENT), null, Shader.TileMode.CLAMP)
        track.color = 0x34FFFFFF
        track.strokeWidth = px
        track.pathEffect = DashPathEffect(floatArrayOf(px * 3f, px * 7f), 0f)
        base.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00000000, NX.CYAN, 0x00000000), null, Shader.TileMode.CLAMP)
        base.strokeWidth = px * 2f
        runnerShader = SweepGradient(
            a, rho,
            intArrayOf(0x00000000, 0x00000000, NX.CYAN, 0xFFFFFFFF.toInt(), NX.ACCENT, 0x00000000, 0x00000000),
            floatArrayOf(0f, 0.58f, 0.66f, 0.70f, 0.74f, 0.84f, 1f)
        )
        runner.shader = runnerShader
        runner.strokeWidth = px * 2.4f
    }

    /** Mulai cahaya berputar di tepi (panggil saat panel tampil). */
    fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun draw(c: Canvas) {
        val w = 2 * a
        val h = rho + s
        c.drawPath(path, fill)
        c.save()
        c.clipPath(path)
        c.drawRect(0f, 0f, w, h, sheen)
        c.restore()
        // pendar bingkai neon (tiga lapis)
        edge.strokeWidth = px * 8f; edge.alpha = 22; c.drawPath(path, edge)
        edge.strokeWidth = px * 4f; edge.alpha = 55; c.drawPath(path, edge)
        edge.strokeWidth = px * 1.5f; edge.alpha = 255; c.drawPath(path, edge)
        // cahaya yang berputar
        if (anim != null) {
            runnerMatrix.setRotate(phase, a, rho)
            runnerShader.setLocalMatrix(runnerMatrix)
            c.drawPath(path, runner)
        }
        c.drawPath(ring, track)
        val y = h - px * 5f
        c.drawLine(w * 0.28f, y, w * 0.72f, y, base)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
