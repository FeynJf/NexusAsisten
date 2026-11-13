package com.nexus.asisten.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.nexus.asisten.ui.Motion
import com.nexus.asisten.ui.NX
import kotlin.math.PI
import kotlin.math.sin

/**
 * Latar panel "Obsidian Aurora" (desain: papan Canva/Figma Nexus Overlay 1.9).
 * Kartu kaca obsidian melayang dengan sudut bundar, cahaya aurora violet/cyan yang bernapas pelan,
 * garis tepi atas bergradien dengan kilat cahaya yang menyapu, dan pegangan seret di puncak.
 * Sengaja ringan digambar: hanya satu lapis aurora bergerak dan satu garis tepi, supaya animasi buka
 * panel tetap mulus di atas game yang sedang berat.
 */
class SheetDrawable(
    private val radius: Float,
    private val px: Float
) : Drawable() {

    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aurora1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aurora2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val sweepM = Matrix()
    private var sweepShader: LinearGradient? = null

    private var timeMs = 0L
    private var loop: Motion.Loop? = null

    /** 0..1: bingkai "menyala" saat panel dibuka. */
    var intro = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val m = px * 1.5f
        rect.set(m, m, w - m, h - m)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xF0121430.toInt(), 0xF40A0C1E.toInt(), 0xF8060712.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        aurora1.shader = RadialGradient(w * 0.85f, h * 0.02f, w * 0.75f, intArrayOf(0x558B5CFF, 0x00000000), null, Shader.TileMode.CLAMP)
        aurora2.shader = RadialGradient(w * 0.08f, h * 0.30f, w * 0.65f, intArrayOf(0x3822E4FF, 0x00000000), null, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.22f, intArrayOf(0x24FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0x00000000, NX.CYAN, NX.ACCENT, NX.MAGENTA, 0x00000000), floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        edge.strokeWidth = px * 1.4f
        sweepShader = LinearGradient(
            0f, 0f, w * 0.35f, 0f,
            intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        sweep.shader = sweepShader
        sweep.strokeWidth = px * 2.2f
    }

    fun start() {
        if (loop != null) return
        loop = Motion.loop(40) { ms -> timeMs = ms; invalidateSelf() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f) return
        val t = timeMs / 1000f
        val breathe = 0.8f + 0.2f * sin(t * 2f * PI.toFloat() / 3.6f)

        c.drawPath(path, fill)
        c.save()
        c.clipPath(path)
        c.save(); c.translate(sin(t * 0.4f) * px * 12f, sin(t * 0.3f) * px * 6f)
        aurora1.alpha = (255 * (0.35f + 0.65f * intro) * breathe).toInt().coerceIn(0, 255)
        c.drawRect(-w * 0.2f, -h * 0.1f, w * 1.2f, h * 1.1f, aurora1); c.restore()
        c.save(); c.translate(sin(t * 0.33f + 2f) * px * 14f, sin(t * 0.45f) * px * 8f)
        c.drawRect(-w * 0.2f, -h * 0.1f, w * 1.2f, h * 1.1f, aurora2); c.restore()
        c.drawRect(0f, 0f, w, h * 0.25f, sheen)
        c.restore()

        // Garis tepi bergradien (memudar di kedua sisi, paling terang di tengah atas).
        edge.alpha = (90 + 165 * intro).toInt().coerceIn(0, 255)
        c.drawPath(path, edge)

        // Kilat cahaya yang menyapu garis tepi atas dari kiri ke kanan.
        if (loop != null) {
            val cycle = (timeMs % 4200L) / 4200f
            val x = -w * 0.35f + (w * 1.35f) * Motion.inOutCubic(cycle)
            sweepM.setTranslate(x, 0f)
            sweepShader?.setLocalMatrix(sweepM)
            sweep.alpha = (200 * intro).toInt()
            c.save()
            c.clipRect(0f, 0f, w, radius * 0.9f)
            c.drawPath(path, sweep)
            c.restore()
        }

        // Pegangan seret.
        val hw = px * 38f
        val hh = px * 4f
        val cy = px * 9f
        c.drawRoundRect(w / 2f - hw / 2f, cy - hh / 2f, w / 2f + hw / 2f, cy + hh / 2f, hh, hh, handle)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
