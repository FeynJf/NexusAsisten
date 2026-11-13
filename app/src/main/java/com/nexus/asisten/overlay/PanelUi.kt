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
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
}

/**
 * Overlay Nexus Gamer: geser dari bawah-tengah. SATU panel kubah 180°: tombol aksi cepat berjajar di
 * sepanjang busur, HUD (FPS, ping, suhu, baterai) di dalam busur, lalu tab dan halaman geser di bawahnya.
 */
class PanelUi(private val ctx: Context, private val host: PanelHost) {
    val root = FrameLayout(ctx)
    private val scrim = View(ctx)
    private var card: FrameLayout? = null
    private var dome: DomeDrawable? = null
    private var origin: View? = null
    private lateinit var pager: SnapPager
    private lateinit var tabIndicator: View
    private val tabLabels = ArrayList<TextView>()
    private val pageFrames = ArrayList<FrameLayout>()
    private val arcBtns = ArrayList<View>()
    private val arcOffsets = ArrayList<Pair<Float, Float>>()
    private var laidOut = false
    private var lastNear = -1
    private var closing = false
    private var introRun: MutableList<Motion.Handle>? = null
    private var tabW = 0

    private var tvFps: TextView? = null
    private var tvPing: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBat: TextView? = null
    private var tvSession: TextView? = null
    private var tvSessionHero: TextView? = null

    private val tabNames = listOf("Performa", "Alat", "Aplikasi", "Saya")
    private val selfAnnounced = setOf("Cegah salah sentuh", "Mode Fokus")

    private class Tile(val icon: Int, val label: String, val state: (() -> Boolean)?, val onClick: () -> Unit)

    init {
        val shade = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#26000000"), Color.parseColor("#8C04050E"))
        )
        val rise = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = 520f * ctx.resources.displayMetrics.density
            colors = intArrayOf(withAlpha(NX.ACCENT, 0x5A), withAlpha(NX.CYAN, 0x16), 0)
        }
        scrim.background = android.graphics.drawable.LayerDrawable(arrayOf(shade, rise))
        scrim.setOnClickListener { host.close() }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    init {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { dome?.stop() }
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
     * Dipanggil setelah window panel ditambahkan. Tampilan dibangun langsung dari ukuran layar (tidak menunggu
     * callback layout, yang membuat panel kadang hanya berisi lapisan gelap). Bila ada yang gagal, pesan
     * errornya ditampilkan di layar supaya bisa dilaporkan, bukan layar hitam kosong.
     */
    fun start() {
        val (w, h) = screenSize()
        try {
            layoutUi(w, h)
            laidOut = true
            playIn()
        } catch (e: Throwable) {
            showError(e)
        }
        // Jaring pengaman: kalau animasi masuk tidak berjalan (animasi dimatikan/dihemat sistem), paksa tampil.
        root.postDelayed({ ensureVisible() }, 1700)
    }

    private fun showError(e: Throwable) {
        root.removeAllViews()
        arcBtns.clear(); arcOffsets.clear(); pageFrames.clear(); tabLabels.clear()
        card = null; origin = null
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
        dome?.intro = 1f
        card?.let { fix(it) }
        arcBtns.forEach { fix(it) }
        origin?.let { fix(it); it.rotation = 0f }
        pageFrames.getOrNull(0)?.let { f -> ((f.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout)?.let { col ->
            for (i in 0 until col.childCount) fix(col.getChildAt(i))
        } }
        if (scrim.alpha < 0.99f) { scrim.animate().cancel(); scrim.alpha = 1f }
    }

    // ---------- Tata letak: SATU panel kubah 180° ----------
    private fun layoutUi(w: Int, h: Int) {
        val land = w > h
        val status = if (land) 0 else ctx.statusInset()
        val bottomM = if (land) ctx.dp(8) else ctx.navInset() + ctx.dp(8)
        val a: Int
        val rho: Int
        val s: Int
        if (land) {
            a = min(w / 2 - ctx.dp(16), ctx.dp(300))
            val total = min(h - bottomM - ctx.dp(20), ctx.dp(330))
            rho = min(ctx.dp(140), (total * 0.46f).toInt())
            s = total - rho
        } else {
            a = w / 2 - ctx.dp(6)
            rho = a
            val avail = h - status - bottomM - ctx.dp(16)
            val total = min((avail * 0.88f).toInt(), ctx.dp(660))
            s = max(total - rho, ctx.dp(300))
        }
        val total = rho + s
        val frame = FrameLayout(ctx)
        frame.background = DomeDrawable(a.toFloat(), rho.toFloat(), s.toFloat(), ctx.dpf(30f), ctx.dpf(24f), ctx.dpf(1f)).also { dome = it }
        frame.isClickable = true
        buildDome(frame, land, a, rho, s)
        root.addView(frame, FrameLayout.LayoutParams(2 * a, total, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.bottomMargin = bottomM
        })
        frame.pivotX = a.toFloat()
        frame.pivotY = total.toFloat()
        card = frame
    }

    private fun buildDome(frame: FrameLayout, land: Boolean, a: Int, rho: Int, s: Int) {
        val total = rho + s
        val fw = 2 * a
        val cx = a.toFloat()
        val cy = rho.toFloat()

        // --- Tombol aksi cepat berjajar rata sepanjang busur 180° ---
        val items = listOf(
            ArcItem(R.drawable.ic_bolt, "Boost", null) { host.boost() },
            ArcItem(R.drawable.ic_scissors, "Cuplikan", null) { host.screenshot() },
            ArcItem(R.drawable.ic_video, "Rekam", { host.isRecording() }) { host.toggleRecord() },
            ArcItem(R.drawable.ic_bell, "Senyap", { Booster.dndOn(ctx) }) { host.setDnd(!Booster.dndOn(ctx)) },
            ArcItem(R.drawable.ic_crosshair, "Bidik", { Prefs.crosshair }) { host.setCrosshair(!Prefs.crosshair) },
            ArcItem(R.drawable.ic_hand, "Kunci tepi", { Prefs.edgeGuard }) { host.setEdgeGuard(!Prefs.edgeGuard) },
            ArcItem(R.drawable.ic_monitor, "Jendela", null) { pager.snapTo(2) }
        )
        val n = items.size
        val ra = a - ctx.dpf(30f)
        val rb = rho - ctx.dpf(30f)
        val steps = 360
        val xs = FloatArray(steps + 1)
        val ys = FloatArray(steps + 1)
        val cum = FloatArray(steps + 1)
        for (k in 0..steps) {
            val th = PI - k * PI / steps
            xs[k] = (ra * cos(th)).toFloat()
            ys[k] = (rb * sin(th)).toFloat()
            if (k > 0) cum[k] = cum[k - 1] + hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
        }
        val len = cum[steps]
        val bw = ctx.dp(60)
        val bh = ctx.dp(64)
        val cy0 = ctx.dp(23)
        items.forEachIndexed { i, item ->
            val target = len * (i + 0.5f) / n
            var k = 1
            while (k < steps && cum[k] < target) k++
            val px = cx + xs[k]
            val py = cy - ys[k]
            val v = arcButton(item)
            frame.addView(v, FrameLayout.LayoutParams(bw, bh, Gravity.TOP or Gravity.START).also { lp ->
                lp.leftMargin = (px - bw / 2f).toInt()
                lp.topMargin = (py - cy0).toInt()
            })
            arcBtns.add(v)
            arcOffsets.add(Pair(cx - px, cy - py))
        }

        // --- HUD di dalam busur ---
        if (land) {
            val row = ctx.hbox()
            row.gravity = Gravity.CENTER
            val ping = statBlock("Ping", NX.TEXT, 14f)
            val temp = statBlock("Suhu", NX.WARN, 14f)
            val fps = statBlock("FPS", NX.CYAN, 28f)
            val bat = statBlock("Baterai", NX.GOOD, 14f)
            val ses = statBlock("Sesi", NX.TEXT2, 14f)
            tvPing = ping.second; tvTemp = temp.second; tvFps = fps.second; tvBat = bat.second; tvSessionHero = ses.second
            listOf(ping, temp, fps, bat, ses).forEachIndexed { idx, b ->
                row.add(b.first, ctx.dp(if (idx == 2) 84 else 58), WRAP)
            }
            frame.addView(row, FrameLayout.LayoutParams(ctx.dp(316), WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also {
                it.topMargin = ctx.dp(87)
            })
        } else {
            val fps = statBlock("FPS", NX.CYAN, 36f)
            tvFps = fps.second
            frame.addView(fps.first, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also {
                it.topMargin = rho - ctx.dp(84)
            })
            val row = ctx.hbox()
            val ping = statBlock("Ping", NX.TEXT, 15f)
            val temp = statBlock("Suhu", NX.WARN, 15f)
            val bat = statBlock("Baterai", NX.GOOD, 15f)
            val ses = statBlock("Sesi", NX.TEXT2, 15f)
            tvPing = ping.second; tvTemp = temp.second; tvBat = bat.second; tvSessionHero = ses.second
            listOf(ping, temp, bat, ses).forEach { row.add(it.first, 0, WRAP, 1f) }
            frame.addView(row, FrameLayout.LayoutParams(fw - ctx.dp(32), WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also {
                it.topMargin = rho + ctx.dp(2)
            })
        }

        // --- Tab + tombol tutup ---
        val cw = fw - ctx.dp(32)
        val tabsW = min(cw - ctx.dp(52), ctx.dp(400))
        tabW = tabsW / tabNames.size
        val tabsTop = rho + if (land) ctx.dp(4) else ctx.dp(52)
        val head = ctx.vbox()
        val tabsRow = ctx.hbox()
        tabsRow.gravity = Gravity.CENTER_VERTICAL
        tabNames.forEachIndexed { i, nm ->
            val t = ctx.tv(nm, 11.5f, NX.MUTED, 700)
            t.isAllCaps = true
            t.letterSpacing = 0.1f
            t.gravity = Gravity.CENTER
            t.setPadding(0, ctx.dp(7), 0, ctx.dp(6))
            t.setOnClickListener { pager.snapTo(i) }
            t.pressFx(0.94f)
            tabLabels.add(t)
            tabsRow.add(t, tabW, WRAP)
        }
        val close = FrameLayout(ctx)
        close.background = NeonDrawable(Color.parseColor("#F0141A2E"), Color.parseColor("#F00A0D18"), ctx.dpf(17f))
        close.addView(ctx.icon(R.drawable.ic_close, NX.TEXT, 16), FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        close.setOnClickListener { host.close() }
        close.pressFx(0.9f)
        tabsRow.add(close, ctx.dp(34), ctx.dp(34), 0f, 8, 0, 0, 0)
        head.addView(tabsRow)
        val indWrap = FrameLayout(ctx)
        tabIndicator = View(ctx)
        tabIndicator.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(NX.ACCENT, NX.CYAN, NX.MAGENTA)).also { it.cornerRadius = ctx.dpf(2f) }
        tabIndicator.glow(NX.CYAN, 8f)
        indWrap.addView(tabIndicator, FrameLayout.LayoutParams(tabW - ctx.dp(28), ctx.dp(3)).also { it.leftMargin = ctx.dp(14) })
        head.add(indWrap, tabsW, WRAP)
        frame.addView(head, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also {
            it.topMargin = tabsTop
        })

        // --- Halaman (geser kiri-kanan) ---
        val pagerTop = tabsTop + ctx.dp(40)
        val pageH = max(total - pagerTop - ctx.dp(10), ctx.dp(80))
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
        frame.addView(pager, FrameLayout.LayoutParams(cw, pageH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also {
            it.topMargin = pagerTop
        })
        onPagePos(0f)
    }

    private fun onPagePos(pos: Float) {
        if (!::tabIndicator.isInitialized) return
        tabIndicator.translationX = pos * tabW
        val near = (pos + 0.5f).toInt().coerceIn(0, tabLabels.size - 1)
        if (near != lastNear) {
            if (lastNear >= 0 && laidOut) staggerPage(near, 60L)
            lastNear = near
        }
        tabLabels.forEachIndexed { i, t -> t.setTextColor(if (i == near) NX.TEXT else NX.MUTED) }
        pageFrames.forEachIndexed { i, f ->
            val d = min(1f, kotlin.math.abs(pos - i))
            f.alpha = 1f - 0.55f * d
            f.scaleX = 1f - 0.05f * d
            f.scaleY = 1f - 0.05f * d
        }
    }

    // ---------- Busur 180° ----------
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

    // ---------- Animasi (Motion: tidak tergantung skala animator sistem) ----------
    private fun playIn() {
        val runs = ArrayList<Motion.Handle>()
        introRun = runs
        dome?.intro = 0f
        dome?.start()

        // Latar gelap + cahaya neon naik dari bawah.
        scrim.alpha = 0f
        runs += Motion.run(320) { t -> scrim.alpha = Motion.outCubic(t) }

        // Kubah: naik dari bawah dengan pegas, mengembang dari dasar-tengah, bingkai "menyala".
        card?.let { c ->
            val dy = ctx.dpf(150f)
            c.alpha = 0f
            c.translationY = dy
            c.scaleX = 0.86f
            c.scaleY = 0.86f
            runs += Motion.run(760, 40) { t ->
                val e = Motion.spring(t)
                c.alpha = Motion.outCubic((t * 2.4f).coerceAtMost(1f))
                c.translationY = dy * (1f - e)
                val sc = 0.86f + 0.14f * e
                c.scaleX = sc
                c.scaleY = sc
            }
            runs += Motion.run(900, 120) { t -> dome?.intro = Motion.outExpo(t) }
        }

        // Tombol busur terbuka seperti kipas: meluncur dari titik pusat sepanjang busur.
        arcBtns.forEachIndexed { i, b ->
            val (dx, dy) = arcOffsets[i]
            b.alpha = 0f
            b.scaleX = 0.2f; b.scaleY = 0.2f
            b.translationX = dx; b.translationY = dy
            runs += Motion.run(680, 170L + i * 50L) { t ->
                val e = Motion.spring(t)
                b.translationX = dx * (1f - e)
                b.translationY = dy * (1f - e)
                val sc = 0.2f + 0.8f * e
                b.scaleX = sc; b.scaleY = sc
                b.alpha = Motion.outCubic((t * 2.6f).coerceAtMost(1f))
            }
        }

        origin?.let { o ->
            o.scaleX = 0f; o.scaleY = 0f; o.rotation = -90f
            runs += Motion.run(480, 120) { t ->
                val e = Motion.outBack(t, 1.9f)
                o.scaleX = e; o.scaleY = e
                o.rotation = -90f * (1f - Motion.outCubic(t))
            }
        }

        // Angka FPS "pop" lalu isi halaman Performa masuk berurutan.
        tvFps?.let { f ->
            f.scaleX = 0.5f; f.scaleY = 0.5f; f.alpha = 0f
            runs += Motion.run(520, 330) { t ->
                val e = Motion.outBack(t, 2.2f)
                f.scaleX = 0.5f + 0.5f * e; f.scaleY = 0.5f + 0.5f * e
                f.alpha = Motion.outCubic(t)
            }
        }
        staggerPage(0, 380L, runs)
        tabLabels.forEachIndexed { i, tv ->
            tv.alpha = 0f
            runs += Motion.run(420, 260L + i * 45L) { t -> tv.alpha = Motion.outCubic(t) }
        }
    }

    /** Anak-anak halaman [page] naik berurutan (dipanggil saat panel dibuka dan saat pindah tab). */
    private fun staggerPage(page: Int, base: Long, runs: MutableList<Motion.Handle>? = null) {
        val col = (pageFrames.getOrNull(page)?.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val dy = ctx.dpf(22f)
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dy
            val h = Motion.run(520, base + i * 55L) { t ->
                v.alpha = Motion.outCubic(t)
                v.translationY = dy * (1f - Motion.outExpo(t))
            }
            runs?.add(h)
        }
    }

    /** Animasi keluar: busur menguncup ke tengah, kubah turun & mengecil, lalu panggil [end]. */
    fun animateOut(end: () -> Unit) {
        closing = true
        introRun?.forEach { it.cancel() }
        introRun = null
        val n = arcBtns.size
        arcBtns.forEachIndexed { i, b ->
            val (dx, dy) = arcOffsets[i]
            val fa = b.alpha; val fx = b.translationX; val fy = b.translationY; val fs = b.scaleX
            Motion.run(240, (n - 1 - i) * 18L) { t ->
                val e = Motion.inCubic(t)
                b.translationX = fx + (dx - fx) * e
                b.translationY = fy + (dy - fy) * e
                val sc = fs + (0.2f - fs) * e
                b.scaleX = sc; b.scaleY = sc
                b.alpha = fa * (1f - e)
            }
        }
        origin?.let { o -> Motion.run(240) { t -> val e = 1f - Motion.inCubic(t); o.scaleX = e; o.scaleY = e; o.rotation = 90f * t } }
        card?.let { c ->
            val dy = ctx.dpf(110f)
            val a0 = c.alpha; val y0 = c.translationY
            Motion.run(300, 40) { t ->
                val e = Motion.inCubic(t)
                c.alpha = a0 * (1f - e)
                c.translationY = y0 + (dy - y0) * e
                val sc = 1f - 0.08f * e
                c.scaleX = sc; c.scaleY = sc
                dome?.intro = 1f - e
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

    private fun statBlock(label: String, color: Int, sizeSp: Float): Pair<LinearLayout, TextView> {
        val b = ctx.vbox()
        b.gravity = Gravity.CENTER_HORIZONTAL
        val v = ctx.tv("--", sizeSp, color, 700)
        v.gravity = Gravity.CENTER
        val l = ctx.tv(label, 9.5f, NX.MUTED, 700).also { it.isAllCaps = true; it.letterSpacing = 0.12f }
        l.gravity = Gravity.CENTER
        b.add(l, MATCH, WRAP)
        b.add(v, MATCH, WRAP, 0f, 0, 1, 0, 0)
        if (sizeSp >= 28f) v.glow(color, 8f)
        return Pair(b, v)
    }

    fun update(fps: Int, exact: Boolean, ping: Int, temp: Float, bat: Int) {
        tvFps?.text = (if (exact) "" else "~") + fps
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

    // ---------- Halaman Aplikasi (jendela mengambang) ----------
    private fun pageAplikasi(): View {
        val (sv, col) = scrollPage()
        col.add(ctx.sectionTitle("Cara membuka aplikasi"), b = 8)
        col.add(ctx.chipRow(listOf("Jendela mengambang", "Layar penuh"), if (Prefs.floatingWindow) 0 else 1) {
            Prefs.floatingWindow = it == 0
        })
        if (host.floatSupported() && Prefs.floatingWindow) {
            col.add(ctx.sectionTitle("Ukuran jendela"), t = 14, b = 8)
            col.add(ctx.chipRow(listOf("Kecil", "Sedang", "Besar"), Prefs.floatSize) { Prefs.floatSize = it })
        } else {
            // Tanpa izin atau pengaturan tambahan: aplikasi dibuka penuh, lalu gelembung Nexus membawa pulang ke game.
            val tip = ctx.vbox(14, 12)
            tip.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(withAlpha(NX.CYAN, 0x26), withAlpha(NX.ACCENT, 0x1A))
            ).also { it.cornerRadius = ctx.dpf(16f); it.setStroke(ctx.dp(1), withAlpha(NX.CYAN, 0x77)) }
            tip.addView(ctx.tv("Tanpa izin tambahan", 12f, NX.CYAN, 700).also { it.isAllCaps = true; it.letterSpacing = 0.12f })
            tip.add(ctx.tv("Aplikasi dibuka di atas game. Ketuk gelembung Nexus yang muncul untuk langsung kembali ke game.", 12.5f, NX.TEXT), t = 6)
            val more = ctx.tv("Mau jendela mengambang sungguhan? Lihat caranya", 12f, NX.MUTED, 500)
            more.setOnClickListener { host.openFloatHelp() }
            more.pressFx(0.98f)
            tip.add(more, t = 8)
            col.add(tip, t = 14)
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
        col.add(ctx.tv("Ketuk aplikasi untuk membukanya tanpa menutup game.", 11.5f, NX.MUTED), t = 14, b = 10)
        return sv
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
