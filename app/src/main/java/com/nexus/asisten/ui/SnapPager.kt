package com.nexus.asisten.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs

/** Pager horizontal yang bisa digeser dan selalu berhenti pas di satu halaman. */
class SnapPager(ctx: Context, private val onPos: (Float) -> Unit) : HorizontalScrollView(ctx) {
    var pageW = 0
    var count = 1
    private var downX = 0f
    private var startPage = 0

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (pageW > 0) onPos(l.toFloat() / pageW)
    }

    fun page(): Int = if (pageW > 0) ((scrollX + pageW / 2) / pageW).coerceIn(0, count - 1) else 0

    fun snapTo(i: Int, animate: Boolean = true) {
        val x = i.coerceIn(0, count - 1) * pageW
        if (animate) smoothScrollTo(x, 0) else scrollTo(x, 0)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; startPage = page() }
        }
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val dx = ev.x - downX
            val target = when {
                dx < -pageW * 0.14f -> startPage + 1
                dx > pageW * 0.14f -> startPage - 1
                else -> startPage
            }
            // Gesek cepat tetap berpindah satu halaman; gesek pendek kembali ke halaman semula.
            post { snapTo(if (abs(dx) < 4f) page() else target) }
        }
        return handled
    }
}
