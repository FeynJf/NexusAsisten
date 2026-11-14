package com.nexus.asisten.overlay

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import com.nexus.asisten.ui.Motion
import com.nexus.asisten.ui.NX
import kotlin.math.PI
import kotlin.math.sin

/**
 * Latar panel 1.9.1 "Obsidian Aurora": kubah 180° layar penuh. Kaca obsidian hampir hitam, aurora lembut,
 * cincin radar halus di balik HUD, jalur titik-titik tempat tombol aksi berjajar, bingkai gradien tipis dengan
 * pendar, dan komet cahaya yang mengitari tepi. Sengaja lebih bersih dari versi 1.8 (tanpa pola diagonal/berlian).
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
    private val brackets = Path()
    private val rings = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraC = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val runner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val edgeShader: SweepGradient
    private val runnerShader: SweepGradient
    private val rotM = Matrix()
    private val moveM = Matrix()

    private var timeMs = 0L
    private var loop: Motion.Loop? = null

    /** 0..1: "menyala" saat panel dibuka (kilau bingkai naik dari redup ke terang). */
    var intro = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }

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

        // Sudut siku HUD di kiri/kanan bawah.
        val bm = px * 14f
        val bl = px * 22f
        val by = h - px * 14f
        brackets.moveTo(bm, by - bl); brackets.lineTo(bm, by); brackets.lineTo(bm + bl, by)
        brackets.moveTo(w - bm, by - bl); brackets.lineTo(w - bm, by); brackets.lineTo(w - bm - bl, by)

        // Cincin radar halus (setengah elips) di balik HUD.
        val ra = a - inset
        val rb = rho - inset
        for (k in floatArrayOf(0.74f, 0.50f)) rings.addArc(RectF(a - ra * k, rho - rb * k, a + ra * k, rho + rb * k), 180f, 180f)

        // Kaca: gelap di bawah supaya teks mudah dibaca, sedikit tembus pandang di atas supaya game tetap terlihat.
        fill.shader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(0xE60E1130.toInt(), 0xEE090B20.toInt(), 0xF8040510.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        auroraA.shader = RadialGradient(w * 0.82f, rho * 0.55f, a * 1.05f, intArrayOf(0x4D8B5CFF, 0x00000000), null, Shader.TileMode.CLAMP)
        auroraB.shader = RadialGradient(w * 0.12f, h * 0.55f, a * 1.0f, intArrayOf(0x3022E4FF, 0x00000000), null, Shader.TileMode.CLAMP)
        auroraC.shader = RadialGradient(w * 0.55f, h * 0.98f, a * 0.9f, intArrayOf(0x28FF4FD8, 0x00000000), null, Shader.TileMode.CLAMP)

        ringPaint.color = 0x12FFFFFF
        ringPaint.strokeWidth = px

        sheen.shader = LinearGradient(0f, 0f, 0f, rho * 0.9f, intArrayOf(0x2EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)

        // Bingkai: tiga warna mengalir (diputar pelan).
        edgeShader = SweepGradient(
            a, rho,
            intArrayOf(NX.CYAN, NX.ACCENT, NX.MAGENTA, NX.ACCENT, NX.CYAN, NX.BLUE, NX.CYAN),
            floatArrayOf(0f, 0.18f, 0.36f, 0.54f, 0.72f, 0.88f, 1f)
        )
        edge.shader = edgeShader

        track.color = 0x55FFFFFF
        track.strokeWidth = px * 2f
        track.strokeCap = Paint.Cap.ROUND
        track.pathEffect = DashPathEffect(floatArrayOf(px * 1.6f, px * 8f), 0f)
        base.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00000000, NX.CYAN, NX.ACCENT, 0x00000000), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        base.strokeWidth = px * 2f
        runnerShader = SweepGradient(
            a, rho,
            intArrayOf(0x00000000, 0x00000000, NX.CYAN, 0xFFFFFFFF.toInt(), NX.MAGENTA, 0x00000000, 0x00000000),
            floatArrayOf(0f, 0.56f, 0.65f, 0.70f, 0.75f, 0.86f, 1f)
        )
        runner.shader = runnerShader
        runner.strokeWidth = px * 2.6f
        tick.strokeWidth = px * 2f
        tick.color = NX.CYAN
    }

    /** Mulai gerak latar (panggil saat panel tampil). Memakai Choreographer, bukan ValueAnimator. */
    fun start() {
        if (loop != null) return
        loop = Motion.loop(40) { ms -> timeMs = ms; invalidateSelf() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    override fun draw(c: Canvas) {
        val w = 2 * a
        val h = rho + s
        val t = timeMs / 1000f
        val breathe = 0.78f + 0.22f * sin(t * 2f * PI.toFloat() / 3.2f)
        val glowK = (0.25f + 0.75f * intro) * breathe

        c.drawPath(path, fill)
        c.save()
        c.clipPath(path)
        // aurora yang melayang pelan
        c.save(); c.translate(sin(t * 0.45f) * px * 14f, sin(t * 0.33f) * px * 8f); c.drawRect(-w * 0.2f, -h * 0.2f, w * 1.2f, h * 1.2f, auroraA); c.restore()
        c.save(); c.translate(sin(t * 0.38f + 2f) * px * 16f, sin(t * 0.5f + 1f) * px * 10f); c.drawRect(-w * 0.2f, -h * 0.2f, w * 1.2f, h * 1.2f, auroraB); c.restore()
        c.save(); c.translate(sin(t * 0.3f + 4f) * px * 12f, 0f); c.drawRect(-w * 0.2f, 0f, w * 1.2f, h * 1.3f, auroraC); c.restore()
        c.drawRect(0f, 0f, w, h, sheen)
        c.restore()

        // pendar bingkai neon (empat lapis) + warna yang mengalir
        rotM.setRotate(t * 28f, a, rho)
        edgeShader.setLocalMatrix(rotM)
        edge.strokeWidth = px * 6f; edge.alpha = (36 * glowK).toInt(); c.drawPath(path, edge)
        edge.strokeWidth = px * 1.5f; edge.alpha = (170 + 85 * intro).toInt().coerceAtMost(255); c.drawPath(path, edge)

        // komet cahaya yang mengitari tepi
        if (loop != null) {
            moveM.setRotate(t * 85f, a, rho)
            runnerShader.setLocalMatrix(moveM)
            runner.alpha = (255 * intro).toInt()
            c.drawPath(path, runner)
        }
        c.drawPath(rings, ringPaint)
        c.drawPath(ring, track)

        // sudut HUD, berlian puncak, garis dasar
        tick.alpha = (200 * intro).toInt()
        c.drawPath(brackets, tick)
        val y = h - px * 5f
        c.drawLine(w * 0.34f, y, w * 0.66f, y, base)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
