package com.nexus.asisten.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.nexus.asisten.ui.NX
import kotlin.math.min

/**
 * Latar kartu "kaca gamer": isi gradasi indigo agak tembus pandang, kilau tipis di atas,
 * dan bingkai neon dua warna yang berpendar. Dipakai bar rekam dan semua kartu notifikasi.
 */
class GlowDrawable(
    private val radius: Float,
    private val px: Float,
    edge1: Int = NX.ACCENT,
    edge2: Int = NX.CYAN,
    private val top: Int = 0xEB1D1B58.toInt(),
    private val bottom: Int = 0xEB080B22.toInt()
) : Drawable() {

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var edgeFrom: Int = edge1
        set(v) { field = v; rebuild(); invalidateSelf() }
    var edgeTo: Int = edge2
        set(v) { field = v; rebuild(); invalidateSelf() }

    override fun onBoundsChange(b: Rect) { rebuild() }

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val m = px * 3f
        rect.set(b.left + m, b.top + m, b.right - m, b.bottom - m)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(rect.left, 0f, rect.right, 0f, edgeFrom, edgeTo, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        if (rect.isEmpty) return
        val r = min(radius, rect.height() / 2f)
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, sheen)
        edge.strokeWidth = px * 6f; edge.alpha = 22; c.drawRoundRect(rect, r, r, edge)
        edge.strokeWidth = px * 3f; edge.alpha = 55; c.drawRoundRect(rect, r, r, edge)
        edge.strokeWidth = px * 1.3f; edge.alpha = 255; c.drawRoundRect(rect, r, r, edge)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
