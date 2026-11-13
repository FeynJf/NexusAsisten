package com.nexus.asisten.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.nexus.asisten.R
import com.nexus.asisten.ui.Motion
import com.nexus.asisten.ui.NX
import com.nexus.asisten.ui.circle
import com.nexus.asisten.ui.dp
import com.nexus.asisten.ui.dpf
import com.nexus.asisten.ui.glow
import com.nexus.asisten.ui.icon
import kotlin.math.abs

/**
 * Gelembung "Kembali ke game". Pengganti jendela mengambang yang TIDAK butuh izin atau pengaturan tambahan:
 * setelah aplikasi (mis. WhatsApp) dibuka di atas game, gelembung ini melayang di tepi layar. Ketuk untuk
 * langsung pulang ke game, seret untuk memindahkan, otomatis menempel ke tepi dan hilang sendiri saat game kembali tampil.
 */
class BackBubble(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit
) {
    private var view: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var run: Motion.Handle? = null

    val shown: Boolean get() = view != null

    companion object {
        // Posisi terakhir diingat selama proses hidup, supaya gelembung muncul di tempat yang sama.
        private var savedX = -1
        private var savedY = -1
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(icon: Drawable?) {
        if (view != null) return
        val size = ctx.dp(58)
        val dm = ctx.resources.displayMetrics
        val margin = ctx.dp(4)

        val root = FrameLayout(ctx)
        root.background = GlowDrawable(size / 2f, ctx.dpf(1f))

        val iv = ImageView(ctx)
        iv.setImageDrawable(icon)
        iv.background = circle(NX.SURFACE2)
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(iv, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.CENTER))

        val badge = FrameLayout(ctx)
        badge.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.ACCENT_DEEP, NX.CYAN)).also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(ctx.dp(1), Color.parseColor("#99FFFFFF"))
        }
        badge.addView(ctx.icon(R.drawable.ic_back, Color.WHITE, 12), FrameLayout.LayoutParams(ctx.dp(12), ctx.dp(12), Gravity.CENTER))
        root.addView(badge, FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.BOTTOM or Gravity.END).also {
            it.rightMargin = ctx.dp(2); it.bottomMargin = ctx.dp(2)
        })
        root.glow(NX.ACCENT, 10f)

        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (savedX >= 0) savedX.coerceIn(0, dm.widthPixels - size) else dm.widthPixels - size - margin
        p.y = if (savedY >= 0) savedY.coerceIn(0, dm.heightPixels - size) else (dm.heightPixels * 0.35f).toInt()
        params = p

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    run?.cancel()
                    root.scaleX = 0.92f; root.scaleY = 0.92f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        p.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    run = Motion.run(300) { t -> val sc = 0.92f + 0.08f * Motion.spring(t); root.scaleX = sc; root.scaleY = sc }
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (!dragging) onTap() else snap(root, p, size, margin)
                    }
                }
            }
            true
        }

        view = root
        try {
            wm.addView(root, p)
        } catch (_: Exception) {
            view = null
            return
        }
        root.alpha = 0f
        root.scaleX = 0f; root.scaleY = 0f
        run = Motion.run(560) { t ->
            val e = Motion.spring(t)
            root.scaleX = e; root.scaleY = e
            root.alpha = Motion.outCubic((t * 3f).coerceAtMost(1f))
        }
    }

    /** Menempel ke tepi kiri/kanan terdekat. */
    private fun snap(v: FrameLayout, p: WindowManager.LayoutParams, size: Int, margin: Int) {
        val w = ctx.resources.displayMetrics.widthPixels
        val target = if (p.x + size / 2 < w / 2) margin else w - size - margin
        val from = p.x
        savedY = p.y
        run = Motion.run(320, 0, { savedX = p.x }) { t ->
            p.x = (from + (target - from) * Motion.outExpo(t)).toInt()
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    fun hide(immediate: Boolean = false) {
        val v = view ?: return
        view = null
        run?.cancel()
        val remove = { try { wm.removeView(v) } catch (_: Exception) {} }
        if (immediate) { remove(); return }
        run = Motion.run(200, 0, { remove() }) { t ->
            val e = 1f - Motion.inCubic(t)
            v.scaleX = e; v.scaleY = e; v.alpha = e
        }
    }
}
