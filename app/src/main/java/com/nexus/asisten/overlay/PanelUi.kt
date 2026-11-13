package com.nexus.asisten.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nexus.asisten.R
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Yang dibutuhkan panel dari service overlay. */
interface PanelHost {
    val pkg: String
    val gameName: String
    fun profile(): GameProfile
    fun updateProfile(p: GameProfile)
    fun close()
    fun toast(msg: String)
    fun cleanMemory()
    fun boost()
    fun checkNetwork()
    fun addToAssistant()
    fun setCrosshair(on: Boolean)
    fun setEdgeGuard(on: Boolean)
    fun setKeepAwake(on: Boolean)
    fun setFocus(on: Boolean)
    fun setDnd(on: Boolean)
    fun screenshot()
    fun toggleRecord()
    fun isRecording(): Boolean
    fun openWifi()
    fun openSettings()
    fun openGameSettings()
    fun openAlbum()
    fun pickDockApps()
    fun launchDock(pkg: String)
    fun pinShortcut()
    fun dockApps(): List<Pair<String, Drawable>>
    fun sessionMs(): Long
    fun requestWrite()
    fun relayout()
    fun floatSupported(): Boolean
    fun openFloatHelp()
    fun gameIcon(): Drawable?
    fun openMiniWindow(index: Int)
}

/**
 * Overlay Nexus 1.9 "Obsidian Aurora": kartu kaca melayang yang naik dari bawah.
 * Susunan (potret): pegangan seret, header game + LIVE, HUD (kartu FPS dengan grafik + 4 chip), baris aksi cepat,
 * tab kapsul geser, lalu halaman. Landscape: dua kolom (HUD + aksi di kiri, tab + halaman di kanan).
 * Tarik header/pegangan ke bawah untuk menutup.
 */
class PanelUi(private val ctx: Context, private val host: PanelHost) {
    val root = FrameLayout(ctx)
    private val scrim = View(ctx)
    private var card: FrameLayout? = null
    private var sheet: SheetDrawable? = null
    private lateinit var pager: SnapPager
    private lateinit var tabPill: View
    private val tabLabels = ArrayList<TextView>()
    private val pageFrames = ArrayList<FrameLayout>()
    private val sections = ArrayList<View>()
    private val quickBtns = ArrayList<View>()
    private var laidOut = false
    private var played = false
    private var lastNear = -1
    private var closing = false
    private var introRun: MutableList<Motion.Handle>? = null
    private var pulse: Motion.Loop? = null
    private var liveDot: View? = null
    private var tabW = 0
    private var cardH = 0

    private var tvFps: TextView? = null
    private var tvPing: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBat: TextView? = null
    private var tvSession: TextView? = null
    private var tvSessionHero: TextView? = null
    private var spark: SparkView? = null

    private val tabNames = listOf("Performa", "Alat", "Aplikasi", "Saya")
    private val selfAnnounced = setOf("Cegah salah sentuh", "Mode Fokus")

    private class Tile(val icon: Int, val label: String, val state: (() -> Boolean)?, val onClick: () -> Unit)

    init {
        val shade = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#1A000000"), Color.parseColor("#A604050E"))
        )
        val rise = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = 480f * ctx.resources.displayMetrics.density
            colors = intArrayOf(withAlpha(NX.ACCENT, 0x4A), withAlpha(NX.CYAN, 0x14), 0)
        }
        scrim.background = android.graphics.drawable.LayerDrawable(arrayOf(shade, rise))
        scrim.setOnClickListener { host.close() }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { sheet?.stop(); pulse?.cancel(); pulse = null }
        })
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    /**
     * Bangun seluruh tampilan SEBELUM jendela ditambahkan ke layar. Dengan begitu pekerjaan berat (membuat view,
     * mengukur) selesai lebih dulu dan animasi masuk tidak ikut tersendat/terpotong di frame pertama.
     */
    fun build() {
        if (laidOut) return
        val (w, h) = screenSize()
        try {
            layoutUi(w, h)
            laidOut = true
        } catch (e: Throwable) {
            showError(e)
        }
    }

    /** Dipanggil setelah window ditambahkan: animasi baru mulai tepat setelah frame pertama tergambar. */
    fun start() {
        if (!laidOut) build()
        if (card != null) {
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
                    root.post { playIn() }
                    return true
                }
            })
        }
        // Jaring pengaman: kalau animasi masuk tidak jalan, paksa tampil.
        root.postDelayed({ ensureVisible() }, 1900)
    }

    private fun showError(e: Throwable) {
        root.removeAllViews()
        sections.clear(); quickBtns.clear(); pageFrames.clear(); tabLabels.clear()
        card = null
        scrim.alpha = 1f
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        val where = e.stackTrace.take(3).joinToString("\n") { "${it.fileName}:${it.lineNumber}" }
        val c = ctx.vbox(18, 16)
        c.background = rounded(Color.parseColor("#F00F1426"), ctx.dpf(20f), NX.LINE, 1)
        c.addView(ctx.tv("Panel gagal dimuat", 15f, NX.TEXT, 700))
        c.add(ctx.tv("${e.javaClass.simpleName}: ${e.message ?: "-"}\n$where", 11.5f, NX.TEXT2), t = 6)
        c.add(ctx.tv("Ketuk di mana saja untuk menutup. Kirim screenshot ini ke pengembang.", 11.5f, NX.MUTED), t = 8)
        root.addView(c, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).also {
            it.leftMargin = ctx.dp(24); it.rightMargin = ctx.dp(24)
        })
    }

    private fun ensureVisible() {
        if (closing) return
        fun fix(v: View) {
            if (v.alpha < 0.99f || v.scaleX < 0.99f || v.translationY != 0f || v.translationX != 0f) {
                v.animate().cancel()
                v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; v.translationY = 0f
            }
        }
        introRun?.forEach { it.cancel() }
        introRun = null
        sheet?.intro = 1f
        card?.let { fix(it) }
        sections.forEach { fix(it) }
        quickBtns.forEach { fix(it) }
        pageFrames.getOrNull(lastNear.coerceAtLeast(0))?.let { f -> ((f.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout)?.let { col ->
            for (i in 0 until col.childCount) fix(col.getChildAt(i))
        } }
        if (scrim.alpha < 0.99f) { scrim.animate().cancel(); scrim.alpha = 1f }
        sheet?.start()
        startPulse()
    }

    // ---------- Tata letak ----------
    private fun layoutUi(w: Int, h: Int) {
        val land = w > h
        val margin = ctx.dp(8)
        val navB = if (land) 0 else ctx.navInset()
        val status = if (land) 0 else ctx.statusInset()
        val sheetW: Int
        val sheetH: Int
        if (land) {
            sheetW = min(w - 2 * margin, ctx.dp(780))
            sheetH = min(h - 2 * margin, ctx.dp(336))
        } else {
            sheetW = min(w - 2 * margin, ctx.dp(520))
            sheetH = min(((h - status - navB - ctx.dp(24)) * 0.9f).toInt(), ctx.dp(640))
        }
        val padH = ctx.dp(16)
        val frame = FrameLayout(ctx)
        frame.background = SheetDrawable(ctx.dpf(28f), ctx.dpf(1f)).also { sheet = it }
        frame.isClickable = true

        val body = LinearLayout(ctx)
        body.setPadding(padH, ctx.dp(20), padH, ctx.dp(12))
        val innerW = sheetW - 2 * padH
        if (land) {
            body.orientation = LinearLayout.HORIZONTAL
            val gap = ctx.dp(14)
            val leftW = ((innerW - gap) * 0.44f).toInt()
            val rightW = innerW - gap - leftW
            val left = ctx.vbox()
            val header = buildHeader()
            val hud = buildHud(true)
            val actions = buildActions()
            left.add(header, MATCH, ctx.dp(44))
            left.add(hud, MATCH, ctx.dp(88), 0f, 0, 10, 0, 0)
            left.add(actions, MATCH, WRAP, 0f, 0, 12, 0, 0)
            sections += listOf(header, hud, actions)
            val right = ctx.vbox()
            val tabs = buildTabs(rightW)
            right.add(tabs, MATCH, ctx.dp(40))
            sections += tabs
            right.add(buildPager(rightW), MATCH, 0, 1f, 0, 6, 0, 0)
            body.add(left, leftW, MATCH, 0f, 0, 0, 14, 0)
            body.add(right, 0, MATCH, 1f)
            attachDrag(header)
        } else {
            body.orientation = LinearLayout.VERTICAL
            val header = buildHeader()
            val hud = buildHud(false)
            val actions = buildActions()
            val tabs = buildTabs(innerW)
            body.add(header, MATCH, ctx.dp(44))
            body.add(hud, MATCH, ctx.dp(96), 0f, 0, 12, 0, 0)
            body.add(actions, MATCH, WRAP, 0f, 0, 14, 0, 0)
            body.add(tabs, MATCH, ctx.dp(40), 0f, 0, 14, 0, 0)
            body.add(buildPager(innerW), MATCH, 0, 1f, 0, 6, 0, 0)
            sections += listOf(header, hud, actions, tabs)
            attachDrag(header)
        }
        frame.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))

        // Zona seret di puncak (pegangan).
        val grab = View(ctx)
        frame.addView(grab, FrameLayout.LayoutParams(MATCH, ctx.dp(22), Gravity.TOP))
        attachDrag(grab)

        val bm = margin + navB
        root.addView(frame, FrameLayout.LayoutParams(sheetW, sheetH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.bottomMargin = bm
        })
        frame.pivotX = sheetW / 2f
        frame.pivotY = sheetH.toFloat()
        cardH = sheetH + bm
        card = frame
        onPagePos(0f)

        // Keadaan awal animasi: sudah tersembunyi sebelum jendela pertama kali digambar (tanpa kedipan).
        frame.alpha = 0f
        frame.translationY = (cardH + ctx.dp(24)).toFloat()
        scrim.alpha = 0f
        sections.forEach { it.alpha = 0f; it.translationY = ctx.dpf(18f) }
        quickBtns.forEach { it.alpha = 0f; it.scaleX = 0.6f; it.scaleY = 0.6f }
    }

    private fun buildHeader(): LinearLayout {
        val row = ctx.hbox()
        val iconBox = FrameLayout(ctx)
        iconBox.setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        iconBox.background = rounded(0, ctx.dpf(15f), withAlpha(NX.CYAN, 0x99), ctx.dp(1))
        val iv = ImageView(ctx)
        val gi = host.gameIcon()
        if (gi != null) iv.setImageDrawable(gi) else { iv.setImageResource(R.drawable.ic_gamepad); iv.setColorFilter(NX.CYAN); iv.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8)) }
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = rounded(NX.SURFACE2, ctx.dpf(13f))
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        iconBox.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        row.add(iconBox, ctx.dp(44), ctx.dp(44), 0f, 0, 0, 12, 0)

        val names = ctx.vbox()
        val name = ctx.tv(host.gameName, 16.5f, NX.TEXT, 700)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        names.addView(name)
        names.add(ctx.tv("Asisten Game aktif", 11f, NX.MUTED, 500), t = 1)
        row.add(names, 0, WRAP, 1f)

        val live = ctx.hbox()
        live.setPadding(ctx.dp(9), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        live.background = rounded(withAlpha(NX.GOOD, 0x22), ctx.dpf(11f), withAlpha(NX.GOOD, 0x77), 1)
        val dot = View(ctx)
        dot.background = circle(NX.GOOD)
        liveDot = dot
        live.add(dot, ctx.dp(6), ctx.dp(6), 0f, 0, 0, 6, 0)
        live.addView(ctx.tv("LIVE", 10f, NX.GOOD, 700).also { it.letterSpacing = 0.14f })
        row.add(live, WRAP, WRAP, 0f, 0, 0, 10, 0)

        val close = FrameLayout(ctx)
        close.background = NeonDrawable(Color.parseColor("#F0141A2E"), Color.parseColor("#F00A0D18"), ctx.dpf(17f))
        close.addView(ctx.icon(R.drawable.ic_close, NX.TEXT, 16), FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        close.setOnClickListener { host.close() }
        close.pressFx(0.9f)
        row.add(close, ctx.dp(34), ctx.dp(34))
        return row
    }

    private fun buildHud(land: Boolean): LinearLayout {
        val row = ctx.hbox(false)
        val fpsCard = ctx.vbox(12, 8)
        fpsCard.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(NX.CYAN, 0x2A), withAlpha(NX.ACCENT, 0x16))
        ).also { it.cornerRadius = ctx.dpf(18f); it.setStroke(ctx.dp(1), withAlpha(NX.CYAN, 0x88)) }
        fpsCard.addView(ctx.tv("FPS", 9.5f, NX.CYAN, 700).also { it.letterSpacing = 0.16f })
        val fps = ctx.tv("--", if (land) 30f else 38f, NX.TEXT, 700)
        fps.setShadowLayer(ctx.dpf(10f), 0f, 0f, NX.CYAN)
        tvFps = fps
        fpsCard.add(fps, WRAP, WRAP)
        val sp = SparkView(ctx)
        spark = sp
        fpsCard.add(sp, MATCH, 0, 1f, 0, 2, 0, 0)
        row.add(fpsCard, 0, MATCH, 1.15f, 0, 0, 8, 0)

        val grid = ctx.vbox()
        val ping = statChip("Ping", NX.TEXT)
        val temp = statChip("Suhu", NX.WARN)
        val bat = statChip("Baterai", NX.GOOD)
        val ses = statChip("Sesi", NX.TEXT2)
        tvPing = ping.second; tvTemp = temp.second; tvBat = bat.second; tvSessionHero = ses.second
        val r1 = ctx.hbox(false)
        r1.add(ping.first, 0, MATCH, 1f, 0, 0, 8, 0)
        r1.add(temp.first, 0, MATCH, 1f)
        val r2 = ctx.hbox(false)
        r2.add(bat.first, 0, MATCH, 1f, 0, 0, 8, 0)
        r2.add(ses.first, 0, MATCH, 1f)
        grid.add(r1, MATCH, 0, 1f, 0, 0, 0, 8)
        grid.add(r2, MATCH, 0, 1f)
        row.add(grid, 0, MATCH, 1.85f)
        return row
    }

    private fun buildActions(): LinearLayout {
        val items = listOf(
            ArcItem(R.drawable.ic_bolt, "Boost", null) { host.boost() },
            ArcItem(R.drawable.ic_scissors, "Cuplikan", null) { host.screenshot() },
            ArcItem(R.drawable.ic_video, "Rekam", { host.isRecording() }) { host.toggleRecord() },
            ArcItem(R.drawable.ic_bell, "Senyap", { Booster.dndOn(ctx) }) { host.setDnd(!Booster.dndOn(ctx)) },
            ArcItem(R.drawable.ic_crosshair, "Bidik", { Prefs.crosshair }) { host.setCrosshair(!Prefs.crosshair) },
            ArcItem(R.drawable.ic_hand, "Kunci tepi", { Prefs.edgeGuard }) { host.setEdgeGuard(!Prefs.edgeGuard) }
        )
        val row = ctx.hbox(false)
        items.forEach { item ->
            val v = arcButton(item)
            quickBtns.add(v)
            row.add(v, 0, WRAP, 1f)
        }
        return row
    }

    private fun buildTabs(innerW: Int): FrameLayout {
        val pill = FrameLayout(ctx)
        pill.background = rounded(withAlpha(Color.WHITE, 0x0E), ctx.dpf(20f), NX.LINE, 1)
        tabW = (innerW - ctx.dp(8)) / tabNames.size
        tabPill = View(ctx)
        tabPill.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(withAlpha(NX.ACCENT_DEEP, 0xE6), withAlpha(NX.BLUE, 0xCC))
        ).also { it.cornerRadius = ctx.dpf(16f); it.setStroke(ctx.dp(1), withAlpha(Color.WHITE, 0x44)) }
        tabPill.glow(NX.ACCENT, 6f)
        pill.addView(tabPill, FrameLayout.LayoutParams(tabW, ctx.dp(32), Gravity.CENTER_VERTICAL or Gravity.START).also { it.leftMargin = ctx.dp(4) })
        val labels = ctx.hbox()
        labels.setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        tabNames.forEachIndexed { i, nm ->
            val t = ctx.tv(nm, 11f, NX.MUTED, 700)
            t.isAllCaps = true
            t.letterSpacing = 0.08f
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setOnClickListener { if (::pager.isInitialized) pager.snapTo(i) }
            t.pressFx(0.94f)
            tabLabels.add(t)
            labels.add(t, tabW, ctx.dp(32))
        }
        pill.addView(labels, FrameLayout.LayoutParams(MATCH, MATCH))
        return pill
    }

    private fun buildPager(cw: Int): SnapPager {
        pager = SnapPager(ctx) { pos -> onPagePos(pos) }
        pager.pageW = cw
        pager.count = tabNames.size
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        tabNames.indices.forEach { i ->
            val f = FrameLayout(ctx)
            pageFrames.add(f)
            row.addView(f, LinearLayout.LayoutParams(cw, MATCH))
            rebuildPage(i)
        }
        pager.addView(row, FrameLayout.LayoutParams(cw * tabNames.size, MATCH))
        return pager
    }

    private fun onPagePos(pos: Float) {
        if (!::tabPill.isInitialized) return
        tabPill.translationX = pos * tabW
        val near = (pos + 0.5f).toInt().coerceIn(0, tabLabels.size - 1)
        if (near != lastNear) {
            if (lastNear >= 0 && laidOut) staggerPage(near, 60L)
            lastNear = near
        }
        tabLabels.forEachIndexed { i, t -> t.setTextColor(if (i == near) Color.WHITE else NX.MUTED) }
        pageFrames.forEachIndexed { i, f ->
            val d = min(1f, kotlin.math.abs(pos - i))
            f.alpha = 1f - 0.55f * d
            f.scaleX = 1f - 0.05f * d
            f.scaleY = 1f - 0.05f * d
        }
    }

    // ---------- Tombol aksi cepat ----------
    private class ArcItem(val icon: Int, val label: String, val state: (() -> Boolean)?, val onClick: () -> Unit)

    private fun arcButton(item: ArcItem): View {
        val col = ctx.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val disc = FrameLayout(ctx)
        disc.addView(ctx.icon(item.icon, NX.TEXT, 20), FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
        col.add(disc, ctx.dp(46), ctx.dp(46))
        val label = ctx.tv(item.label, 9.5f, NX.TEXT2, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        col.add(label, MATCH, WRAP, 0f, 0, 4, 0, 0)
        styleArc(col, item.state?.invoke() ?: false)
        col.setOnClickListener {
            item.onClick()
            col.postDelayed({
                val on = item.state?.invoke() ?: false
                styleArc(col, on)
                if (item.state != null && item.label !in selfAnnounced) host.toast(item.label + ": " + if (on) "aktif" else "mati")
            }, 450)
        }
        col.pressFx(0.9f)
        return col
    }

    private fun styleArc(v: LinearLayout, active: Boolean) {
        val disc = v.getChildAt(0) as FrameLayout
        val r = ctx.dpf(23f)
        disc.background = if (active)
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.ACCENT_DEEP, NX.BLUE, NX.CYAN)).also {
                it.shape = GradientDrawable.OVAL
                it.setStroke(ctx.dp(1), withAlpha(Color.WHITE, 0x88))
            }
        else NeonDrawable(Color.parseColor("#F2171E38"), Color.parseColor("#F20A0D1A"), r)
        disc.glow(if (active) NX.CYAN else Color.TRANSPARENT, if (active) 12f else 4f)
        (disc.getChildAt(0) as ImageView).setColorFilter(if (active) Color.WHITE else NX.CYAN)
        (v.getChildAt(1) as TextView).setTextColor(if (active) NX.TEXT else NX.TEXT2)
    }

    // ---------- Tarik ke bawah untuk menutup ----------
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View) {
        var startY = 0f
        var dragging = false
        var vt: VelocityTracker? = null
        v.setOnTouchListener { _, e ->
            val c = card
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; dragging = false
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - startY
                    if (!dragging && dy > ctx.dp(6)) dragging = true
                    if (dragging && c != null && !closing) {
                        introRun?.forEach { it.cancel() }
                        introRun = null
                        c.alpha = 1f
                        c.translationY = if (dy > 0) dy else dy * 0.15f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && c != null && !closing) {
                        vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f
                        if (e.actionMasked == MotionEvent.ACTION_UP && (c.translationY > cardH * 0.25f || vy > 1400f)) host.close()
                        else {
                            val y0 = c.translationY
                            Motion.run(420) { t -> c.translationY = y0 * (1f - Motion.spring(t)) }
                        }
                    }
                    vt?.recycle(); vt = null
                    dragging = false
                }
            }
            true
        }
    }

    // ---------- Animasi (Motion: tidak tergantung skala animator sistem) ----------
    private fun startPulse() {
        if (pulse != null) return
        val d = liveDot ?: return
        pulse = Motion.loop(50) { ms -> d.alpha = 0.55f + 0.45f * sin(ms / 330f) }
    }

    private fun playIn() {
        if (played || closing) return
        played = true
        val c = card ?: return
        val runs = ArrayList<Motion.Handle>()
        introRun = runs
        sheet?.intro = 0f

        // Latar gelap + cahaya naik dari bawah.
        runs += Motion.run(420) { t -> scrim.alpha = Motion.smooth(t) }

        // Kartu naik dari bawah: meluncur cepat lalu mendarat halus dengan sedikit pantulan.
        val dy = (cardH + ctx.dp(24)).toFloat()
        runs += Motion.run(640, 0, {
            // Latar bergerak (aurora, kilat) baru dinyalakan setelah kartu mendarat supaya tidak membebani animasi masuk.
            sheet?.start()
            startPulse()
        }) { t ->
            val e = Motion.outBack(t, 0.9f)
            c.alpha = Motion.outCubic((t * 3.2f).coerceAtMost(1f))
            c.translationY = dy * (1f - e)
            val sc = 0.94f + 0.06f * Motion.smooth(t)
            c.scaleX = sc; c.scaleY = sc
        }
        runs += Motion.run(900, 360) { t -> sheet?.intro = Motion.outExpo(t) }

        // Blok isi masuk berurutan dari bawah.
        sections.forEachIndexed { i, v ->
            runs += Motion.run(560, 140L + i * 70L) { t ->
                v.alpha = Motion.outCubic(t)
                v.translationY = ctx.dpf(18f) * (1f - Motion.smooth(t))
            }
        }
        // Tombol aksi cepat "pop" satu per satu.
        quickBtns.forEachIndexed { i, b ->
            runs += Motion.run(620, 260L + i * 55L) { t ->
                val e = Motion.spring(t)
                val sc = 0.6f + 0.4f * e
                b.scaleX = sc; b.scaleY = sc
                b.alpha = Motion.outCubic((t * 3f).coerceAtMost(1f))
            }
        }
        // Isi halaman pertama.
        staggerPage(0, 420L, runs)
    }

    /** Anak-anak halaman [page] naik berurutan (saat panel dibuka dan saat pindah tab). */
    private fun staggerPage(page: Int, base: Long, runs: MutableList<Motion.Handle>? = null) {
        val col = (pageFrames.getOrNull(page)?.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val dy = ctx.dpf(20f)
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dy
            val h = Motion.run(520, base + i * 50L) { t ->
                v.alpha = Motion.outCubic(t)
                v.translationY = dy * (1f - Motion.smooth(t))
            }
            runs?.add(h)
        }
    }

    /** Animasi keluar: kartu turun dan memudar, latar menghilang, lalu panggil [end]. */
    fun animateOut(end: () -> Unit) {
        closing = true
        introRun?.forEach { it.cancel() }
        introRun = null
        pulse?.cancel(); pulse = null
        card?.let { c ->
            val dy = (cardH + ctx.dp(24)).toFloat()
            val a0 = c.alpha; val y0 = c.translationY
            Motion.run(320) { t ->
                val e = Motion.inCubic(t)
                c.alpha = a0 * (1f - Motion.inCubic((t - 0.35f).coerceAtLeast(0f) / 0.65f))
                c.translationY = y0 + (dy - y0) * e
                val sc = 1f - 0.05f * e
                c.scaleX = sc; c.scaleY = sc
            }
        }
        val s0 = scrim.alpha
        Motion.run(320) { t -> scrim.alpha = s0 * (1f - Motion.outCubic(t)) }
        // Hapus window lewat timer supaya pasti terhapus walau animasi dibatalkan.
        root.postDelayed({ end() }, 360)
    }

    fun showTab(i: Int) {
        if (laidOut) pager.snapTo(i)
    }

    // ---------- Halaman ----------
    private fun rebuildPage(i: Int) {
        val f = pageFrames[i]
        f.removeAllViews()
        if (i == 3) tvSession = null
        f.addView(when (i) { 0 -> pagePerforma(); 1 -> pageAlat(); 2 -> pageAplikasi(); else -> pageSaya() }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.isVerticalFadingEdgeEnabled = true
        sv.setFadingEdgeLength(ctx.dp(22))
        val col = ctx.vbox(12, 8)
        sv.addView(col)
        return Pair(sv, col)
    }

    private fun glass(radius: Float = 18f): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(Color.WHITE, 0x16), withAlpha(Color.WHITE, 0x08)))
            .also { it.cornerRadius = ctx.dpf(radius); it.setStroke(ctx.dp(1), NX.LINE) }

    private fun statChip(label: String, color: Int): Pair<LinearLayout, TextView> {
        val b = ctx.vbox(11, 6)
        b.gravity = Gravity.CENTER_VERTICAL
        b.background = glass(14f)
        val l = ctx.tv(label, 9.5f, NX.MUTED, 700).also { it.isAllCaps = true; it.letterSpacing = 0.14f }
        val v = ctx.tv("--", 16f, color, 700)
        b.add(l, WRAP, WRAP)
        b.add(v, WRAP, WRAP, 0f, 0, 1, 0, 0)
        return Pair(b, v)
    }

    fun update(fps: Int, exact: Boolean, ping: Int, temp: Float, bat: Int) {
        tvFps?.text = (if (exact) "" else "~") + fps
        if (fps > 0) spark?.push(fps.toFloat())
        tvPing?.text = if (ping < 0) "--" else "$ping ms"
        tvTemp?.text = String.format("%.0f°", temp)
        tvTemp?.setTextColor(if (temp >= Prefs.tempWarn) NX.HOT else NX.WARN)
        tvBat?.text = "$bat%"
        tvSession?.text = fmtDuration(host.sessionMs())
        tvSessionHero?.text = fmtDuration(host.sessionMs())
    }

    private fun pagePerforma(): View {
        val (sv, col) = scrollPage()
        val p = host.profile()
        col.add(ctx.gradientButton("Boost sekarang") { host.boost() }, t = 4)
        col.add(ctx.tv("Kosongkan RAM, kunci Wi-Fi & refresh rate, lalu uji jaringan.", 11.5f, NX.MUTED), t = 6)
        col.add(ctx.sectionTitle("Mode performa"), t = 16, b = 8)
        val modeNames = listOf("Hemat daya", "Seimbang", "Gamer Pro")
        col.add(ctx.chipRow(modeNames, p.mode, listOf(NX.GOOD, NX.CYAN, NX.PRO)) {
            host.updateProfile(host.profile().copy(mode = it))
            host.toast("Mode ${modeNames[it]} diterapkan")
        })
        col.add(ctx.tv("Gamer Pro menaikkan konsumsi daya dan suhu.", 11.5f, NX.MUTED), t = 6)
        col.add(ctx.sectionTitle("Sampel sentuhan"), t = 16, b = 8)
        col.add(ctx.chipRow(listOf("Cerdas", "Sentuhan ultra"), if (p.ultraTouch) 1 else 0) {
            host.updateProfile(host.profile().copy(ultraTouch = it == 1))
            host.toast(
                if (it == 1) {
                    if (com.nexus.asisten.core.Perms.secure(ctx)) "Sentuhan ultra aktif (respons tekan-tahan dipercepat)"
                    else "Sentuhan ultra disimpan. Efek penuh butuh izin ADB WRITE_SECURE_SETTINGS (lihat Pengaturan > Izin)"
                } else "Sentuhan cerdas dipilih"
            )
        })
        col.add(ctx.sectionTitle("Refresh layar"), t = 16, b = 8)
        val opts = Booster.supportedRefresh(ctx)
        val sel = if (p.refreshHz == 0) opts.size - 1 else opts.indexOfFirst { it == p.refreshHz }.coerceAtLeast(0)
        col.add(ctx.chipRow(opts.map { "$it Hz" }, sel) {
            host.updateProfile(host.profile().copy(refreshHz = opts[it]))
            host.toast("Refresh rate ${opts[it]} Hz diminta")
        })
        val link = ctx.linkRow("Performa game ini", "Layar, kontrol, fokus", R.drawable.ic_settings) { host.openGameSettings() }
        link.background = glass(16f)
        col.add(link, t = 18, b = 10)
        return sv
    }

    private fun pageAlat(): View {
        val (sv, col) = scrollPage()
        col.add(sliderRow(R.drawable.ic_brightness, NX.WARN, 255, brightnessNow()) { p ->
            if (!Settings.System.canWrite(ctx)) { host.requestWrite(); return@sliderRow }
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, max(p, 8))
        })
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        col.add(sliderRow(R.drawable.ic_volume, NX.CYAN, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.getStreamVolume(AudioManager.STREAM_MUSIC)) { p ->
            am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
        }, t = 8)
        val tiles = listOf(
            Tile(R.drawable.ic_globe, "Cek jaringan", null) { host.checkNetwork() },
            Tile(R.drawable.ic_wifi, "WLAN", null) { host.openWifi() },
            Tile(R.drawable.ic_nuclear, "Kosongkan RAM", null) { host.cleanMemory() },
            Tile(R.drawable.ic_focus, "Mode Fokus", { Prefs.focusMode }) { host.setFocus(!Prefs.focusMode) },
            Tile(R.drawable.ic_brightness, "Layar menyala", { Prefs.keepAwake }) { host.setKeepAwake(!Prefs.keepAwake) },
            Tile(R.drawable.ic_bolt, "Mulai cepat", { Prefs.quickStart }) { Prefs.quickStart = !Prefs.quickStart },
            Tile(R.drawable.ic_rotate, "Kunci orientasi", { rotationLocked() }) { toggleRotation() },
            Tile(R.drawable.ic_folder_star, "Tambah ke Asisten", null) { host.addToAssistant() },
            Tile(R.drawable.ic_monitor, "Status sistem", null) { showStatus() },
            Tile(R.drawable.ic_gamepad, "Pintasan game", null) { showShortcut() },
            Tile(R.drawable.ic_photo, "Album", null) { host.openAlbum() },
            Tile(R.drawable.ic_settings, "Sesuaikan", null) { host.openSettings() }
        )
        var i = 0
        while (i < tiles.size) {
            val row = ctx.hbox()
            for (k in 0 until 3) {
                val t = tiles.getOrNull(i + k)
                if (t != null) row.add(tileView(t), 0, ctx.dp(78), 1f, 0, 0, if (k < 2) 8 else 0, 0)
                else row.add(View(ctx), 0, ctx.dp(78), 1f)
            }
            col.add(row, t = 8)
            i += 3
        }
        col.add(View(ctx), MATCH, ctx.dp(8))
        return sv
    }

    private fun styleTile(v: LinearLayout, active: Boolean) {
        val r = ctx.dpf(16f)
        val base: Drawable = if (active)
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(NX.ACCENT, 0x77), withAlpha(NX.BLUE, 0x44), withAlpha(NX.CYAN, 0x30)))
                .also { it.cornerRadius = r; it.setStroke(ctx.dp(1), withAlpha(NX.CYAN, 0xAA)) }
        else glass(16f)
        v.rippleBg(base, r)
        (v.getChildAt(0) as ImageView).setColorFilter(if (active) NX.CYAN else NX.TEXT2)
        (v.getChildAt(1) as TextView).setTextColor(if (active) NX.TEXT else NX.TEXT2)
    }

    private fun tileView(t: Tile): View {
        val v = ctx.vbox(6, 8)
        v.gravity = Gravity.CENTER
        v.addView(ctx.icon(t.icon, NX.TEXT2, 24))
        val label = ctx.tv(t.label, 11f, NX.TEXT2, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        v.add(label, MATCH, WRAP, 0f, 0, 6, 0, 0)
        styleTile(v, t.state?.invoke() ?: false)
        v.setOnClickListener {
            t.onClick()
            v.postDelayed({
                val on = t.state?.invoke() ?: false
                styleTile(v, on)
                if (t.state != null && t.label !in selfAnnounced) host.toast(t.label + ": " + if (on) "aktif" else "mati")
            }, 450)
        }
        return v
    }

    private fun brightnessNow() =
        try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }

    private fun sliderRow(iconRes: Int, tint: Int, max: Int, cur: Int, onChange: (Int) -> Unit): View {
        val row = ctx.hbox()
        row.background = glass(18f)
        row.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        row.addView(ctx.icon(iconRes, tint, 22))
        val sb = SeekBar(ctx)
        sb.max = max
        sb.progress = cur
        sb.progressTintList = ColorStateList.valueOf(NX.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        row.add(sb, 0, WRAP, 1f, 10, 0, 0, 0)
        return row
    }

    private fun rotationLocked(): Boolean =
        try { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 0 } catch (_: Exception) { false }

    private fun toggleRotation() {
        if (!Settings.System.canWrite(ctx)) { host.requestWrite(); return }
        val cr = ctx.contentResolver
        if (rotationLocked()) {
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
        } else {
            // Kunci pada orientasi yang sedang dipakai (tanpa ini game landscape bisa terbanting ke potret).
            val rot = try {
                (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).rotation
            } catch (_: Exception) { 0 }
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, rot)
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
        }
    }

    // ---------- Sub-halaman (di dalam halaman Alat) ----------
    private fun subPage(title: String, build: (LinearLayout) -> Unit) {
        val f = pageFrames[1]
        f.removeAllViews()
        val (sv, col) = scrollPage()
        val head = ctx.hbox()
        val back = ctx.icon(R.drawable.ic_back, NX.TEXT, 24)
        back.setOnClickListener { rebuildPage(1) }
        back.pressFx(0.85f)
        head.add(back, ctx.dp(24), ctx.dp(24), 0f, 0, 0, 12, 0)
        head.addView(ctx.tv(title, 17f, NX.TEXT, 700))
        col.add(head, b = 14)
        build(col)
        f.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun infoRow(k: String, v: String): View {
        val r = ctx.hbox()
        r.setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        r.add(ctx.tv(k, 14f, NX.TEXT2), 0, WRAP, 1f)
        r.addView(ctx.tv(v, 14f, NX.TEXT, 500))
        return r
    }

    private fun showStatus() = subPage("Status sistem") { col ->
        val b = SystemStats.battery(ctx)
        val (free, total) = SystemStats.ramMb(ctx)
        col.addView(infoRow("Baterai", "${b.pct}%" + if (b.charging) " (mengisi)" else ""))
        col.addView(infoRow("Suhu baterai", String.format("%.1f °C", b.tempC)))
        col.addView(infoRow("Status termal", SystemStats.thermalLabel(ctx)))
        col.addView(infoRow("RAM tersedia", "$free dari $total MB"))
        col.addView(infoRow("Jaringan", SystemStats.networkType(ctx)))
        val hz = try { (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).refreshRate.toInt() } catch (_: Exception) { 0 }
        if (hz > 0) col.addView(infoRow("Refresh layar", "$hz Hz"))
    }

    private fun showShortcut() = subPage("Pintasan game") { col ->
        val c = ctx.card(16, NX.SURFACE2)
        c.addView(ctx.linkRow("Ikon layar Awal", "Pintasan Mulai cepat untuk ${host.gameName}", R.drawable.ic_play) { host.pinShortcut() })
        col.addView(c)
    }

    // ---------- Halaman Aplikasi: jendela mini bawaan + aplikasi saya ----------
    private fun pageAplikasi(): View {
        val (sv, col) = scrollPage()
        col.add(ctx.sectionTitle("Jendela mini"), b = 6)
        col.add(ctx.tv("Mengambang di atas game, tanpa izin atau pengaturan tambahan. Seret judulnya untuk memindah, tarik sudut kanan-bawah untuk mengubah ukuran.", 11.5f, NX.MUTED), b = 10)
        MiniWindow.SITES.chunked(2).forEachIndexed { r, pair ->
            val row = ctx.hbox(false)
            pair.forEachIndexed { k, s ->
                row.add(siteCard(s.name) { host.openMiniWindow(r * 2 + k) }, 0, ctx.dp(52), 1f, 0, 0, if (k == 0) 8 else 0, 0)
            }
            if (pair.size == 1) row.add(View(ctx), 0, ctx.dp(52), 1f)
            col.add(row, t = if (r == 0) 0 else 8)
        }

        col.add(ctx.sectionTitle("Aplikasi saya"), t = 18, b = 10)
        val apps = host.dockApps()
        val cells = ArrayList<View>()
        apps.forEach { (pkg, d) -> cells.add(appCell(d, GameRepo.label(ctx, pkg)) { host.launchDock(pkg) }) }
        val plus = FrameLayout(ctx)
        plus.background = rounded(0, ctx.dpf(16f), withAlpha(NX.ACCENT, 0x88), ctx.dp(1))
        plus.addView(ctx.icon(R.drawable.ic_add, NX.ACCENT, 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        plus.setOnClickListener { host.pickDockApps() }
        plus.pressFx(0.9f)
        val addCell = ctx.vbox()
        addCell.gravity = Gravity.CENTER_HORIZONTAL
        addCell.add(plus, ctx.dp(54), ctx.dp(54))
        addCell.add(ctx.tv("Tambah", 10.5f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER }, WRAP, WRAP, 0f, 0, 6, 0, 0)
        cells.add(addCell)
        var i = 0
        while (i < cells.size) {
            val row = ctx.hbox(false)
            for (k in 0 until 4) {
                val c = cells.getOrNull(i + k)
                row.add(c ?: View(ctx), 0, WRAP, 1f)
            }
            col.add(row, t = if (i == 0) 0 else 12)
            i += 4
        }

        if (host.floatSupported()) {
            // Hanya muncul bila HP memang mendukung jendela aplikasi asli (freeform): tanpa langkah tambahan.
            col.add(ctx.sectionTitle("Cara membuka aplikasi"), t = 18, b = 8)
            col.add(ctx.chipRow(listOf("Jendela mengambang", "Layar penuh"), if (Prefs.floatingWindow) 0 else 1) {
                Prefs.floatingWindow = it == 0
            })
            if (Prefs.floatingWindow) {
                col.add(ctx.chipRow(listOf("Kecil", "Sedang", "Besar"), Prefs.floatSize) { Prefs.floatSize = it }, t = 8)
            }
        } else {
            col.add(ctx.tv("Aplikasi asli dibuka cepat di atas game. Ketuk gelembung Nexus yang muncul untuk kembali ke game.", 11.5f, NX.MUTED), t = 14)
        }
        col.add(View(ctx), MATCH, ctx.dp(10))
        return sv
    }

    private fun siteCard(name: String, onClick: () -> Unit): View {
        val v = ctx.hbox()
        v.setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        v.addView(ctx.icon(R.drawable.ic_globe, NX.CYAN, 20))
        v.add(ctx.tv(name, 13.5f, NX.TEXT, 700), 0, WRAP, 1f, 10, 0, 0, 0)
        v.add(ctx.icon(R.drawable.ic_arrow_right, NX.MUTED, 18), ctx.dp(18), ctx.dp(18))
        v.rippleBg(glass(16f), ctx.dpf(16f))
        v.setOnClickListener { onClick() }
        return v
    }

    private fun appCell(icon: Drawable, name: String, onClick: () -> Unit): View {
        val v = ctx.vbox()
        v.gravity = Gravity.CENTER_HORIZONTAL
        val iv = ImageView(ctx)
        iv.setImageDrawable(icon)
        iv.background = rounded(NX.SURFACE2, ctx.dpf(16f))
        iv.clipToOutline = true
        iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        v.add(iv, ctx.dp(54), ctx.dp(54))
        v.add(ctx.tv(name, 10.5f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER; it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }, MATCH, WRAP, 0f, 2, 6, 2, 0)
        v.setOnClickListener { onClick() }
        v.pressFx(0.9f)
        return v
    }

    // ---------- Halaman Saya ----------
    private fun pageSaya(): View {
        val (sv, col) = scrollPage()
        val head = ctx.hbox()
        val avatar = ctx.tv(Prefs.userName.take(1).uppercase(), 22f, Color.WHITE, 700)
        avatar.gravity = Gravity.CENTER
        avatar.background = gradientRounded(NX.ACCENT_DEEP, NX.CYAN, ctx.dpf(18f), GradientDrawable.Orientation.TL_BR)
        avatar.glow(NX.ACCENT, 8f)
        head.add(avatar, ctx.dp(52), ctx.dp(52), 0f, 0, 0, 14, 0)
        val names = ctx.vbox()
        names.addView(ctx.tv(Prefs.userName, 18f, NX.TEXT, 700))
        names.add(ctx.tv("Sedang bermain ${host.gameName}", 12.5f, NX.TEXT2), t = 2)
        head.add(names, 0, WRAP, 1f)
        col.add(head, b = 14)

        val c = ctx.vbox(16, 6)
        c.background = glass(18f)
        val sess = ctx.tv(fmtDuration(host.sessionMs()), 14f, NX.TEXT, 500)
        tvSession = sess
        val r = ctx.hbox()
        r.setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        r.add(ctx.tv("Sesi ini", 14f, NX.TEXT2), 0, WRAP, 1f)
        r.addView(sess)
        c.addView(r)
        val total = com.nexus.asisten.data.PlayStats.total(ctx, listOf(host.pkg))[host.pkg] ?: 0L
        val m30 = com.nexus.asisten.data.PlayStats.last30(ctx, listOf(host.pkg))[host.pkg] ?: 0L
        c.addView(infoRow("30 hari terakhir", fmtDuration(m30)))
        c.addView(infoRow("Total tercatat", fmtDuration(total)))
        col.addView(c)

        col.add(ctx.ghostButton("Album game") { host.openAlbum() }, t = 14)
        col.add(ctx.ghostButton("Pengaturan lainnya") { host.openSettings() }, t = 8, b = 10)
        return sv
    }
}
