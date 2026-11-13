package com.nexus.asisten.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.asisten.R
import com.nexus.asisten.ui.Motion
import com.nexus.asisten.ui.NX
import com.nexus.asisten.ui.add
import com.nexus.asisten.ui.dp
import com.nexus.asisten.ui.dpf
import com.nexus.asisten.ui.hbox
import com.nexus.asisten.ui.icon
import com.nexus.asisten.ui.pressFx
import com.nexus.asisten.ui.rounded
import com.nexus.asisten.ui.tv
import com.nexus.asisten.ui.vbox
import com.nexus.asisten.ui.withAlpha

/**
 * Jendela mini bawaan Nexus: jendela mengambang SUNGGUHAN yang tidak butuh izin tambahan apa pun dan tidak
 * bergantung pada mode freeform bawaan Android (yang tersembunyi di banyak HP). Isinya browser ringan dengan
 * pintasan YouTube, Google, WhatsApp Web, dll. Seret dari bilah judul, ubah ukuran dari sudut kanan-bawah.
 * Jendela ini tidak merebut fokus, jadi game di belakangnya tidak terjeda. Tombol "Aa" mengaktifkan
 * keyboard sementara (saat itu Android bisa menjeda sebagian game).
 */
class MiniWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onClosed: () -> Unit
) {
    class Site(val name: String, val url: String, val desktop: Boolean = false)

    companion object {
        val SITES = listOf(
            Site("YouTube", "https://m.youtube.com"),
            Site("Google", "https://www.google.com"),
            Site("WhatsApp", "https://web.whatsapp.com", true),
            Site("Instagram", "https://www.instagram.com")
        )
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private var sx = -1
        private var sy = -1
        private var sw = -1
        private var sh = -1
    }

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var titleView: TextView? = null
    private var bar: View? = null
    private var typing = false
    private var anim: Motion.Handle? = null

    val shown: Boolean get() = root != null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show(site: Site) {
        val existing = web
        if (existing != null && root != null) { open(site); return }
        val dm = ctx.resources.displayMetrics
        val w0 = if (sw > 0) sw else minOf((dm.widthPixels * 0.9f).toInt(), ctx.dp(380))
        val h0 = if (sh > 0) sh else minOf((dm.heightPixels * 0.55f).toInt(), ctx.dp(430))
        val x0 = if (sx >= 0) sx else (dm.widthPixels - w0) / 2
        val y0 = if (sy >= 0) sy else (dm.heightPixels * 0.12f).toInt()

        val frame = FrameLayout(ctx)
        frame.background = GlowDrawable(ctx.dpf(20f), ctx.dpf(1f))
        frame.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL

        // --- Bilah judul (area seret) ---
        val head = ctx.hbox()
        head.setPadding(ctx.dp(10), 0, ctx.dp(6), 0)
        val dot = View(ctx)
        dot.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.CYAN, NX.ACCENT)).also { it.shape = GradientDrawable.OVAL }
        head.add(dot, ctx.dp(8), ctx.dp(8), 0f, 0, 0, 8, 0)
        val title = ctx.tv(site.name, 13f, NX.TEXT, 700)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView = title
        head.add(title, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fun btn(res: Int, onClick: () -> Unit): View {
            val b = FrameLayout(ctx)
            b.addView(ctx.icon(res, NX.TEXT2, 18), FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
            b.setOnClickListener { onClick() }
            b.pressFx(0.85f)
            return b
        }
        head.add(btn(R.drawable.ic_back) { web?.let { if (it.canGoBack()) it.goBack() } }, ctx.dp(34), ctx.dp(34))
        head.add(btn(R.drawable.ic_rotate) { web?.reload() }, ctx.dp(34), ctx.dp(34))
        val aa = ctx.tv("Aa", 12.5f, NX.TEXT2, 700)
        aa.gravity = Gravity.CENTER
        aa.setOnClickListener { toggleTyping(aa) }
        aa.pressFx(0.85f)
        head.add(aa, ctx.dp(34), ctx.dp(34))
        head.add(btn(R.drawable.ic_close) { hide() }, ctx.dp(34), ctx.dp(34))
        col.add(head, ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(40))
        bar = head

        // --- Pintasan situs ---
        val chips = ctx.hbox()
        chips.setPadding(ctx.dp(8), 0, ctx.dp(8), ctx.dp(6))
        SITES.forEachIndexed { i, s ->
            val ch = ctx.tv(s.name, 11.5f, NX.TEXT2, 500)
            ch.gravity = Gravity.CENTER
            ch.setPadding(ctx.dp(8), ctx.dp(5), ctx.dp(8), ctx.dp(5))
            ch.background = rounded(withAlpha(Color.WHITE, 0x10), ctx.dpf(10f), withAlpha(NX.CYAN, 0x44), 1)
            ch.setOnClickListener { open(s) }
            ch.pressFx(0.92f)
            chips.add(ch, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, if (i == SITES.size - 1) 0 else 6, 0)
        }
        col.add(chips, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // --- Isi: WebView dibulatkan sudutnya ---
        val wv = WebView(ctx)
        web = wv
        wv.setBackgroundColor(Color.parseColor("#0A0C18"))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.webViewClient = WebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, t: String?) { if (!t.isNullOrBlank()) titleView?.text = t }
        }
        val holder = FrameLayout(ctx)
        holder.background = rounded(Color.parseColor("#0A0C18"), ctx.dpf(14f))
        holder.clipToOutline = true
        holder.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        holder.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.add(holder, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- Pegangan ubah ukuran (sudut kanan-bawah) ---
        val grip = object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(NX.CYAN, 0xDD); strokeWidth = ctx.dpf(2f); strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat(); val m = ctx.dpf(7f)
                c.drawLine(w - m, h - m - ctx.dpf(12f), w - m - ctx.dpf(12f), h - m, p)
                c.drawLine(w - m, h - m - ctx.dpf(6f), w - m - ctx.dpf(6f), h - m, p)
            }
        }
        frame.addView(grip, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.BOTTOM or Gravity.END))

        val lp = WindowManager.LayoutParams(
            w0, h0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x0; lp.y = y0
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        params = lp

        // Seret dari bilah judul.
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (ox + (e.rawX - dx)).toInt().coerceIn(-lp.width / 2, dm.widthPixels - lp.width / 2)
                    lp.y = (oy + (e.rawY - dy)).toInt().coerceIn(0, dm.heightPixels - ctx.dp(60))
                    sx = lp.x; sy = lp.y
                    try { wm.updateViewLayout(frame, lp) } catch (_: Exception) {}
                }
            }
            true
        }
        // Ubah ukuran dari sudut.
        var rx = 0f; var ry = 0f; var rw = 0; var rh = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rx = e.rawX; ry = e.rawY; rw = lp.width; rh = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (rw + (e.rawX - rx)).toInt().coerceIn(ctx.dp(240), dm.widthPixels)
                    lp.height = (rh + (e.rawY - ry)).toInt().coerceIn(ctx.dp(200), dm.heightPixels)
                    sw = lp.width; sh = lp.height
                    try { wm.updateViewLayout(frame, lp) } catch (_: Exception) {}
                }
            }
            true
        }

        root = frame
        try { wm.addView(frame, lp) } catch (_: Exception) { cleanup(); onClosed(); return }
        open(site)

        // Muncul: membesar dengan pegas dari 88% sambil memudar masuk.
        frame.alpha = 0f
        frame.scaleX = 0.88f; frame.scaleY = 0.88f
        frame.pivotX = w0 / 2f; frame.pivotY = 0f
        anim?.cancel()
        anim = Motion.run(460) { t ->
            val e = Motion.spring(t)
            frame.alpha = Motion.outCubic((t * 2.4f).coerceAtMost(1f))
            frame.scaleX = 0.88f + 0.12f * e
            frame.scaleY = 0.88f + 0.12f * e
        }
    }

    private fun open(site: Site) {
        val wv = web ?: return
        wv.settings.userAgentString = if (site.desktop) DESKTOP_UA else null
        titleView?.text = site.name
        wv.loadUrl(site.url)
    }

    private fun toggleTyping(label: TextView) {
        val lp = params ?: return
        val r = root ?: return
        typing = !typing
        lp.flags = if (typing) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(r, lp) } catch (_: Exception) {}
        label.setTextColor(if (typing) NX.CYAN else NX.TEXT2)
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (typing) {
            web?.requestFocus()
            r.postDelayed({ web?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }, 180)
        } else {
            imm.hideSoftInputFromWindow(r.windowToken, 0)
        }
    }

    private fun cleanup() {
        web?.let {
            try { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() } catch (_: Exception) {}
        }
        web = null
        root = null
        params = null
        titleView = null
        typing = false
    }

    fun hide(immediate: Boolean = false) {
        val r = root ?: return
        anim?.cancel()
        val remove = {
            try { wm.removeView(r) } catch (_: Exception) {}
            cleanup()
            onClosed()
        }
        if (immediate) { remove(); return }
        val a0 = r.alpha
        val s0 = r.scaleX
        anim = Motion.run(220, 0, { remove() }) { t ->
            val e = Motion.inCubic(t)
            r.alpha = a0 * (1f - e)
            val sc = s0 - 0.1f * e
            r.scaleX = sc; r.scaleY = sc
        }
    }
}
