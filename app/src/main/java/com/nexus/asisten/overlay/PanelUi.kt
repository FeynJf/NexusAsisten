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
    private var sheet: DomeDrawable? = null
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

    // ---------- Tata letak: bingkai panel + kubah 180° (mengikuti desain Canva 1.9.1) ----------
    private var arcFromX = FloatArray(0)
    private var arcFromY = FloatArray(0)
    private var landMode = false

    private fun layoutUi(w: Int, h: Int) {
        val land = w > h
        landMode = land
        val status = if (land) 0 else ctx.statusInset()
        val bottomM = if (land) ctx.dp(6) else ctx.navInset() + ctx.dp(6)
        val hy = ctx.dp(56)          // baris header di atas kubah
        val am = ctx.dp(8)           // jarak kubah dari tepi bingkai
        val fw: Int
        val total: Int
        val rho: Int
        val domeW: Int
        if (land) {
            // Landscape: dua panel. Kiri = kubah desain (aksi, FPS, chip, Boost). Kanan = tab + halaman.
            fw = min(w - ctx.dp(12), ctx.dp(900))
            total = h - bottomM - ctx.dp(6)
            domeW = ctx.dp(360)
            rho = min(ctx.dp(168), total - hy - ctx.dp(150))
        } else {
            fw = w - ctx.dp(8)
            total = min(h - status - bottomM - ctx.dp(6), ctx.dp(800))
            domeW = fw
            rho = fw / 2 - am
        }
        val cy = hy + rho            // garis tengah busur
        val s = total - cy
        val inset = am + ctx.dp(if (land) 28 else 34)
        val padH = ctx.dp(14)
        val dcx = domeW / 2           // titik tengah kubah (sumbu x)

        val frame = FrameLayout(ctx)
        frame.background = DomeDrawable(
            (fw / 2).toFloat(), rho.toFloat(), s.toFloat(), inset.toFloat(), ctx.dpf(22f), ctx.dpf(1f), hy.toFloat(), am.toFloat(),
            0f, domeW.toFloat()
        ).also { sheet = it }
        frame.isClickable = true

        fun lp(wid: Int, hei: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0) =
            FrameLayout.LayoutParams(wid, hei, gravity).also { it.leftMargin = l; it.topMargin = t; it.rightMargin = r }

        // --- Tombol aksi cepat berjajar di sepanjang busur (cincin berwarna, label di bawah) ---
        val items = listOf(
            ArcItem(R.drawable.ic_bolt, "Boost", NX.CYAN, null) { host.boost() },
            ArcItem(R.drawable.ic_scissors, "Cuplikan", NX.ACCENT, null) { host.screenshot() },
            ArcItem(R.drawable.ic_video, "Rekam", Color.parseColor("#FF4D6D"), { host.isRecording() }) { host.toggleRecord() },
            ArcItem(R.drawable.ic_bell, "Senyap", NX.TEXT2, { Booster.dndOn(ctx) }) { host.setDnd(!Booster.dndOn(ctx)) },
            ArcItem(R.drawable.ic_crosshair, "Bidik", NX.CYAN, { Prefs.crosshair }) { host.setCrosshair(!Prefs.crosshair) },
            ArcItem(R.drawable.ic_hand, "Kunci tepi", NX.GOOD, { Prefs.edgeGuard }) { host.setEdgeGuard(!Prefs.edgeGuard) },
            ArcItem(R.drawable.ic_monitor, "Jendela", NX.ACCENT, null) { if (::pager.isInitialized) pager.snapTo(2) }
        )
        val ra = (domeW / 2 - inset).toFloat()
        val rb = (rho - inset).toFloat()
        val bw = ctx.dp(58)
        val bh = ctx.dp(62)
        arcFromX = FloatArray(items.size)
        arcFromY = FloatArray(items.size)
        items.forEachIndexed { i, item ->
            val th = Math.PI + Math.PI * (i + 0.5) / items.size
            val x = dcx + ra * Math.cos(th).toFloat()
            val y = cy + rb * Math.sin(th).toFloat()
            val v = arcButton(item)
            quickBtns.add(v)
            frame.addView(v, lp(bw, bh, Gravity.TOP or Gravity.START, (x - bw / 2f).toInt(), (y - bh / 2f).toInt()))
            arcFromX[i] = dcx - x
            arcFromY[i] = cy - y
        }

        // --- HUD di dalam busur: FPS besar + empat chip ---
        val hudViews = ArrayList<View>()
        val chips = buildChips()
        val fps = buildFps(land)
        val chipsW = min(domeW - ctx.dp(if (land) 36 else 56), ctx.dp(330))
        val chipsTop = cy - ctx.dp(48)
        val fpsTop = hy + inset + ctx.dp(30)
        val fpsH = max(chipsTop - ctx.dp(4) - fpsTop, ctx.dp(56))
        val fpsW = min(domeW - ctx.dp(if (land) 130 else 120), ctx.dp(260))
        frame.addView(chips, lp(chipsW, ctx.dp(40), Gravity.TOP or Gravity.START, dcx - chipsW / 2, chipsTop))
        frame.addView(fps, lp(fpsW, fpsH, Gravity.TOP or Gravity.START, dcx - fpsW / 2, chipsTop - ctx.dp(4) - fpsH))
        hudViews += listOf(chips, fps)

        // --- Header di atas kubah ---
        val header = buildHeader()
        frame.addView(header, lp(MATCH, ctx.dp(44), Gravity.TOP or Gravity.START, padH, ctx.dp(8), padH))
        sections += header
        sections += hudViews

        val tabs: View
        val pagerLeft: Int
        val pagerTop: Int
        val pagerW: Int
        val pagerH: Int
        if (land) {
            // Panel kanan: kartu kaca berisi tab dan halaman.
            val x0 = domeW + ctx.dp(8)
            val rw = fw - x0 - padH
            val paneH = total - hy - ctx.dp(14)
            val pane = View(ctx)
            pane.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(Color.WHITE, 0x14), withAlpha(Color.WHITE, 0x06))
            ).also { it.cornerRadius = ctx.dpf(20f); it.setStroke(ctx.dp(1), withAlpha(NX.CYAN, 0x44)) }
            frame.addView(pane, lp(rw, paneH, Gravity.TOP or Gravity.START, x0, hy))
            sections += pane
            pagerLeft = x0 + ctx.dp(6)
            pagerW = rw - ctx.dp(12)
            tabs = buildTabs(pagerW)
            frame.addView(tabs, lp(pagerW, ctx.dp(40), Gravity.TOP or Gravity.START, pagerLeft, hy + ctx.dp(8)))
            pagerTop = hy + ctx.dp(54)
            pagerH = (hy + paneH - ctx.dp(6) - pagerTop).coerceAtLeast(ctx.dp(110))

            // Tombol Boost besar di bawah kubah (sama dengan desain Canva), jadi halaman Performa tidak mengulangnya.
            val boost = ctx.gradientButton("Boost sekarang") { host.boost() }
            frame.addView(boost, lp(domeW - ctx.dp(36), ctx.dp(46), Gravity.TOP or Gravity.START, ctx.dp(18), cy + ctx.dp(14)))
            val hint = ctx.tv("Kosongkan RAM, kunci Wi-Fi & refresh rate, lalu uji jaringan.", 11f, NX.MUTED)
            hint.gravity = Gravity.CENTER
            frame.addView(hint, lp(domeW - ctx.dp(36), WRAP, Gravity.TOP or Gravity.START, ctx.dp(18), cy + ctx.dp(66)))
            sections += boost
            sections += hint
        } else {
            tabs = buildTabs(fw - 2 * padH)
            pagerLeft = padH
            pagerW = fw - 2 * padH
            frame.addView(tabs, lp(MATCH, ctx.dp(40), Gravity.TOP or Gravity.START, padH, cy + ctx.dp(10), padH))
            pagerTop = cy + ctx.dp(58)
            pagerH = (total - pagerTop - ctx.dp(14)).coerceAtLeast(ctx.dp(110))
        }
        sections += tabs
        frame.addView(buildPager(pagerW), lp(pagerW, pagerH, Gravity.TOP or Gravity.START, pagerLeft, pagerTop))
        attachDrag(header)

        // Zona seret di puncak panel.
        val grab = View(ctx)
        frame.addView(grab, lp(ctx.dp(180), ctx.dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        attachDrag(grab)

        root.addView(frame, FrameLayout.LayoutParams(fw, total, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.bottomMargin = bottomM
        })
        frame.pivotX = fw / 2f
        frame.pivotY = total.toFloat()
        cardH = total + bottomM
        card = frame
        onPagePos(0f)

        // Keadaan awal animasi: sudah tersembunyi sebelum jendela pertama kali digambar (tanpa kedipan).
        frame.alpha = 0f
        frame.translationY = (cardH + ctx.dp(24)).toFloat()
        scrim.alpha = 0f
        sections.forEach { it.alpha = 0f; it.translationY = ctx.dpf(18f) }
        quickBtns.forEachIndexed { i, b ->
            b.alpha = 0f; b.scaleX = 0.5f; b.scaleY = 0.5f
            b.translationX = arcFromX[i]; b.translationY = arcFromY[i]
        }
    }

    private fun buildHeader(): LinearLayout {
        val row = ctx.hbox()
        val iconBox = FrameLayout(ctx)
        iconBox.setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        iconBox.background = rounded(0, ctx.dpf(14f), withAlpha(NX.CYAN, 0x99), ctx.dp(1))
        val iv = ImageView(ctx)
        val gi = host.gameIcon()
        if (gi != null) iv.setImageDrawable(gi) else { iv.setImageResource(R.drawable.ic_gamepad); iv.setColorFilter(NX.CYAN); iv.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8)) }
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = rounded(NX.SURFACE2, ctx.dpf(12f))
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        iconBox.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        row.add(iconBox, ctx.dp(42), ctx.dp(42), 0f, 0, 0, 10, 0)

        val name = ctx.tv(host.gameName, 16f, NX.TEXT, 700)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        row.add(name, WRAP, WRAP, 0f, 0, 0, 10, 0)

        val live = ctx.hbox()
        live.setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(9), ctx.dp(3))
        live.background = rounded(withAlpha(Color.parseColor("#FF4D6D"), 0x26), ctx.dpf(11f), withAlpha(Color.parseColor("#FF4D6D"), 0x99), 1)
        val dot = View(ctx)
        dot.background = circle(Color.parseColor("#FF4D6D"))
        liveDot = dot
        live.add(dot, ctx.dp(6), ctx.dp(6), 0f, 0, 0, 5, 0)
        live.addView(ctx.tv("LIVE", 9.5f, Color.parseColor("#FF8FA3"), 700).also { it.letterSpacing = 0.14f })
        row.add(live, WRAP, WRAP, 0f)
        // Spasi pendorong: tombol tutup di ujung kanan.
        row.add(View(ctx), 0, 1, 1f)

        val close = FrameLayout(ctx)
        close.background = NeonDrawable(Color.parseColor("#F0141A2E"), Color.parseColor("#F00A0D18"), ctx.dpf(17f))
        close.addView(ctx.icon(R.drawable.ic_close, NX.TEXT, 16), FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        close.setOnClickListener { host.close() }
        close.pressFx(0.9f)
        row.add(close, ctx.dp(34), ctx.dp(34))
        return row
    }

    /** Blok FPS: "FPS" kecil, angka besar cyan bercahaya, gelombang grafik di belakang angka. */
    private fun buildFps(land: Boolean): FrameLayout {
        val box = FrameLayout(ctx)
        val sp = SparkView(ctx)
        spark = sp
        sp.alpha = 0.75f
        box.addView(sp, FrameLayout.LayoutParams(MATCH, ctx.dp(if (land) 24 else 30), Gravity.BOTTOM))
        val col = ctx.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(ctx.tv("FPS", 10f, NX.TEXT, 700).also { it.letterSpacing = 0.22f; it.gravity = Gravity.CENTER })
        val fps = ctx.tv("--", if (land) 34f else 42f, NX.CYAN, 700)
        fps.setShadowLayer(ctx.dpf(14f), 0f, 0f, NX.CYAN)
        fps.gravity = Gravity.CENTER
        tvFps = fps
        col.addView(fps)
        box.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        return box
    }

    private fun miniChip(label: String, iconRes: Int, color: Int): Pair<LinearLayout, TextView> {
        val b = ctx.hbox()
        b.gravity = Gravity.CENTER
        b.setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
        b.background = glass(12f)
        b.add(ctx.icon(iconRes, color, 14), ctx.dp(14), ctx.dp(14), 0f, 0, 0, 5, 0)
        val col = ctx.vbox()
        col.addView(ctx.tv(label, 8f, NX.MUTED, 700).also { it.isAllCaps = true; it.letterSpacing = 0.08f })
        val v = ctx.tv("--", 12f, color, 700)
        col.addView(v)
        b.add(col, WRAP, WRAP)
        return Pair(b, v)
    }

    private fun buildChips(): LinearLayout {
        val ping = miniChip("Ping", R.drawable.ic_wifi, NX.GOOD)
        val temp = miniChip("Suhu", R.drawable.ic_thermo, Color.parseColor("#FF5C8A"))
        val bat = miniChip("Baterai", R.drawable.ic_battery, NX.GOOD)
        val ses = miniChip("Sesi", R.drawable.ic_speed, NX.CYAN)
        tvPing = ping.second; tvTemp = temp.second; tvBat = bat.second; tvSessionHero = ses.second
        val row = ctx.hbox(false)
        listOf(ping, temp, bat, ses).forEachIndexed { i, c -> row.add(c.first, 0, MATCH, 1f, 0, 0, if (i < 3) 6 else 0, 0) }
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
    private class ArcItem(val icon: Int, val label: String, val color: Int, val state: (() -> Boolean)?, val onClick: () -> Unit)

    private fun arcButton(item: ArcItem): View {
        val col = ctx.vbox()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.tag = item.color
        val disc = FrameLayout(ctx)
        disc.addView(ctx.icon(item.icon, item.color, 20), FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
        col.add(disc, ctx.dp(44), ctx.dp(44))
        val label = ctx.tv(item.label, 10f, NX.TEXT2, 500)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        col.add(label, MATCH, WRAP, 0f, 0, 3, 0, 0)
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
        val color = (v.tag as? Int) ?: NX.CYAN
        val disc = v.getChildAt(0) as FrameLayout
        disc.background = if (active)
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.ACCENT_DEEP, NX.BLUE, color)).also {
                it.shape = GradientDrawable.OVAL
                it.setStroke(ctx.dp(2), withAlpha(Color.WHITE, 0xCC))
            }
        else GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            it.setColor(Color.parseColor("#D00A0D1A"))
            it.setStroke(ctx.dp(2), withAlpha(color, 0xE0))
        }
        disc.glow(color, if (active) 14f else 6f)
        (disc.getChildAt(0) as ImageView).setColorFilter(if (active) Color.WHITE else color)
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
        // Tombol aksi cepat terbang dari pusat kubah ke posisinya di busur, satu per satu.
        quickBtns.forEachIndexed { i, b ->
            runs += Motion.run(700, 230L + i * 55L) { t ->
                val e = Motion.spring(t)
                val k = 1f - e
                b.translationX = arcFromX[i] * k
                b.translationY = arcFromY[i] * k
                val sc = 0.5f + 0.5f * e
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
        if (!landMode) {
            col.add(ctx.gradientButton("Boost sekarang") { host.boost() }, t = 4)
            col.add(ctx.tv("Kosongkan RAM, kunci Wi-Fi & refresh rate, lalu uji jaringan.", 11.5f, NX.MUTED), t = 6)
        }
        col.add(ctx.sectionTitle("Mode performa"), t = if (landMode) 4 else 16, b = 8)
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
        // Rentang kecerahan berbeda tiap merek (255, 1023, 2047, 4095): pakai tebakan dari nilai sekarang.
        val bNow = brightnessNow()
        val bMax = when { bNow > 2047 -> 4095; bNow > 1023 -> 2047; bNow > 255 -> 1023; else -> 255 }
        col.add(sliderRow(R.drawable.ic_brightness, NX.WARN, bMax, bNow, { Settings.System.canWrite(ctx).also { ok -> if (!ok) host.requestWrite() } }) { p ->
            try {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, max(p, bMax / 32))
            } catch (_: Exception) { host.toast("Kecerahan tidak bisa diubah") }
        })
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        col.add(sliderRow(R.drawable.ic_volume, NX.CYAN, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.getStreamVolume(AudioManager.STREAM_MUSIC)) { p ->
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0) } catch (_: Exception) {}
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

    /** [gate]: dipanggil sekali saat slider disentuh; false = batalkan (mis. izin belum ada). */
    private fun sliderRow(iconRes: Int, tint: Int, max: Int, cur: Int, gate: (() -> Boolean)? = null, onChange: (Int) -> Unit): View {
        val row = ctx.hbox()
        row.background = glass(18f)
        row.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        row.addView(ctx.icon(iconRes, tint, 22))
        val sb = SeekBar(ctx)
        sb.max = max
        sb.progress = cur.coerceIn(0, max)
        sb.progressTintList = ColorStateList.valueOf(NX.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        var allowed = true
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser && allowed) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) { allowed = gate?.invoke() ?: true }
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        // Tanpa ini pager horizontal (SnapPager) merebut geseran, jadi slider nyaris tidak bisa digeser.
        sb.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        row.add(sb, 0, ctx.dp(34), 1f, 10, 0, 0, 0)
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
        // Kartu "Jendela mengambang" (bilah alamat + situs pilihan), gaya desain Canva 1.9.1.
        val bc = ctx.vbox(14, 12)
        bc.background = NeonDrawable(Color.parseColor("#E6141A33"), Color.parseColor("#EE090B18"), ctx.dpf(18f), strokePx = ctx.dpf(1.2f))
        val bh = ctx.hbox()
        bh.add(ctx.tv("Jendela mengambang", 13.5f, NX.ACCENT, 700), 0, WRAP, 1f)
        bh.addView(ctx.tv("tanpa izin", 10.5f, NX.GOOD, 700).also { it.isAllCaps = true; it.letterSpacing = 0.1f })
        bc.addView(bh)
        val addr = ctx.hbox()
        addr.setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        addr.background = rounded(withAlpha(Color.WHITE, 0x10), ctx.dpf(12f), NX.LINE, 1)
        addr.add(ctx.icon(R.drawable.ic_globe, NX.TEXT2, 16), ctx.dp(16), ctx.dp(16), 0f, 0, 0, 8, 0)
        addr.add(ctx.tv("Ketik alamat atau cari situs", 12.5f, NX.MUTED), 0, WRAP, 1f)
        addr.addView(ctx.icon(R.drawable.ic_search, NX.TEXT2, 16))
        addr.setOnClickListener { host.openMiniWindow(1) }
        addr.pressFx(0.98f)
        bc.add(addr, MATCH, ctx.dp(40), 0f, 0, 10, 0, 0)
        val tilesRow = ctx.hbox(false)
        val brand = listOf(Color.parseColor("#FF0033"), Color.parseColor("#4285F4"), Color.parseColor("#25D366"), Color.parseColor("#E1306C"))
        val glyph = listOf("▶", "G", "W", "I")
        MiniWindow.SITES.forEachIndexed { k, site ->
            val cell = ctx.vbox()
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val badge = ctx.tv(glyph.getOrElse(k) { "•" }, 20f, Color.WHITE, 700)
            badge.gravity = Gravity.CENTER
            badge.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brand.getOrElse(k) { NX.ACCENT }, withAlpha(brand.getOrElse(k) { NX.ACCENT }, 0xAA)))
                .also { it.cornerRadius = ctx.dpf(15f) }
            badge.glow(brand.getOrElse(k) { NX.ACCENT }, 6f)
            cell.add(badge, ctx.dp(48), ctx.dp(48))
            cell.add(ctx.tv(site.name, 10.5f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER; it.maxLines = 1 }, MATCH, WRAP, 0f, 0, 6, 0, 0)
            cell.setOnClickListener { host.openMiniWindow(k) }
            cell.pressFx(0.92f)
            tilesRow.add(cell, 0, WRAP, 1f)
        }
        bc.add(tilesRow, MATCH, WRAP, 0f, 0, 12, 0, 0)
        col.add(bc, MATCH, WRAP, 0f, 0, 0, 0, 0)

        col.add(ctx.sectionTitle("Aplikasi saya"), t = 16, b = 10)
        val apps = host.dockApps()
        val cells = ArrayList<View>()
        apps.forEach { (pkg, d) ->
            val label = GameRepo.label(ctx, pkg)
            cells.add(appCell(d, label, MiniWindow.siteFor(pkg, label) != null) { host.launchDock(pkg) })
        }
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
            col.add(ctx.tv("Aplikasi dengan lencana biru dibuka sebagai jendela mini (versi web) di atas game. Aplikasi lain dibuka penuh dengan gelembung Nexus untuk kembali ke game. Jendela aplikasi asli yang sungguhan hanya mungkin di HP yang mendukung freeform.", 11.5f, NX.MUTED), t = 14)
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

    private fun appCell(icon: Drawable, name: String, mini: Boolean = false, onClick: () -> Unit): View {
        val v = ctx.vbox()
        v.gravity = Gravity.CENTER_HORIZONTAL
        val iv = ImageView(ctx)
        iv.setImageDrawable(icon)
        iv.background = rounded(NX.SURFACE2, ctx.dpf(16f))
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val holder = FrameLayout(ctx)
        holder.addView(iv, FrameLayout.LayoutParams(ctx.dp(54), ctx.dp(54)))
        if (mini) {
            // Lencana: aplikasi ini bisa dibuka sebagai jendela mini (versi web) di HP mana pun.
            val badge = FrameLayout(ctx)
            badge.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.ACCENT_DEEP, NX.CYAN)).also {
                it.shape = GradientDrawable.OVAL; it.setStroke(ctx.dp(1), Color.parseColor("#FF0A0D18"))
            }
            badge.addView(ctx.icon(R.drawable.ic_monitor, Color.WHITE, 11), FrameLayout.LayoutParams(ctx.dp(11), ctx.dp(11), Gravity.CENTER))
            holder.addView(badge, FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.BOTTOM or Gravity.END))
        }
        v.add(holder, ctx.dp(54), ctx.dp(54))
        v.add(ctx.tv(name, 10.5f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER; it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }, MATCH, WRAP, 0f, 2, 6, 2, 0)
        v.setOnClickListener { onClick() }
        v.pressFx(0.9f)
        return v
    }

    // ---------- Halaman Saya ----------
    private fun pageSaya(): View {
        val (sv, col) = scrollPage()
        val head = ctx.hbox()
        val avatar = ImageView(ctx)
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.background = gradientRounded(NX.ACCENT_DEEP, NX.CYAN, ctx.dpf(26f), GradientDrawable.Orientation.TL_BR)
        avatar.clipToOutline = true
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        com.nexus.asisten.core.Avatar.into(ctx, avatar)
        head.add(avatar, ctx.dp(52), ctx.dp(52), 0f, 0, 0, 14, 0)
        val names = ctx.vbox()
        names.addView(ctx.tv(Prefs.userName, 18f, NX.TEXT, 700))
        names.add(ctx.tv(if (Prefs.googleEmail.isNotEmpty()) Prefs.googleEmail else "Sedang bermain ${host.gameName}", 12.5f, NX.TEXT2), t = 2)
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
