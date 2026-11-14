package com.nexus.asisten.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nexus.asisten.AlbumActivity
import com.nexus.asisten.GamePickerActivity
import com.nexus.asisten.GameSettingsActivity
import com.nexus.asisten.MainActivity
import com.nexus.asisten.R
import com.nexus.asisten.SettingsActivity
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.CaptureActivity
import com.nexus.asisten.core.FpsMonitor
import com.nexus.asisten.core.ForegroundDetector
import com.nexus.asisten.core.GameConflict
import com.nexus.asisten.core.GameUtils
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.PingMonitor
import com.nexus.asisten.core.ScreenCaptureService
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.Fx
import com.nexus.asisten.ui.Motion
import com.nexus.asisten.ui.NX
import com.nexus.asisten.ui.circle
import com.nexus.asisten.ui.dp
import com.nexus.asisten.ui.dpf
import com.nexus.asisten.ui.navInset
import com.nexus.asisten.ui.glow
import com.nexus.asisten.ui.withAlpha
import com.nexus.asisten.ui.gradientRounded
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service(), PanelHost, RecordHost {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val ACTION_START = "nx.START"
        const val ACTION_STOP = "nx.STOP"
        const val ACTION_REAPPLY = "nx.REAPPLY"
        const val ACTION_RELOAD_DOCK = "nx.DOCK"
        const val ACTION_OPEN = "nx.OPEN"
        @Volatile var running = false
        @Volatile var currentPkg: String? = null
        private const val CH = "nexus_overlay"
        private const val NID = 1001

        fun start(ctx: Context, pkg: String) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_PKG, pkg))
        }

        fun reapply(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_REAPPLY))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }

    override var pkg: String = ""
    override var gameName: String = ""

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val fps = FpsMonitor()
    private val ping = PingMonitor()
    private var detector: ForegroundDetector? = null

    private var handleView: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var notifDot: View? = null
    private var panel: PanelUi? = null
    private var crosshairView: View? = null
    private var guardL: View? = null
    private var guardR: View? = null
    private var bannerView: View? = null
    private var recBar: RecordBar? = null
    private var backBubble: BackBubble? = null
    private var mini: MiniWindow? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var hideUntil = 0L
    private val othersPresent by lazy { Prefs.compatMode && GameConflict.installed(this).isNotEmpty() }

    private var profile = GameProfile()
    private var hotThrottled = false
    private var lastTick = 0L
    private var awaySince = 0L
    private var session = 0L
    private var saveAcc = 0L
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REAPPLY -> { profile = Prefs.profile(pkg); applyEffective(false); panel?.showTab(0); return START_STICKY }
            ACTION_RELOAD_DOCK -> { return START_STICKY }
            ACTION_OPEN -> { if (started) openPanel(); return START_STICKY }
        }
        val p = intent?.getStringExtra(EXTRA_PKG)
        if (p == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(NID, notification(GameRepo.label(this, p)))
        if (!Perms.overlay(this)) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_STICKY
        started = true
        running = true
        pkg = p
        currentPkg = p
        gameName = GameRepo.label(this, p)
        profile = Prefs.profile(p)
        detector = if (Perms.usage(this)) ForegroundDetector(this, p) else null
        addGuards()
        addHandle()
        if (Prefs.crosshair) setCrosshair(true)
        // FPS & ping hanya dijalankan saat panel terbuka supaya game tidak terbebani.
        Booster.holdWifi(this, Prefs.wifiLock)
        applyEffective(Prefs.quickStart)
        lastTick = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, 1000)
        return START_STICKY
    }

    private fun notification(name: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Asisten Game", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openPanel = PendingIntent.getService(
            this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_OPEN), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle("Asisten Game aktif")
            .setContentText("$name · geser ke atas dari bawah-tengah layar, atau ketuk Buka panel")
            .setContentIntent(open)
            .addAction(0, "Buka panel", openPanel)
            .addAction(0, "Berhenti", stop)
            .setOngoing(true)
            .build()
    }

    // ---------- Loop utama ----------
    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (_: Exception) {}
            if (started) handler.postDelayed(this, if (panel != null) 1000L else 2000L)
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTick
        lastTick = now

        var inGame = true
        detector?.let {
            inGame = it.poll() == pkg
            if (inGame) awaySince = 0L else if (awaySince == 0L) awaySince = now
        }
        val bubbleUp = backBubble?.shown == true
        if (inGame && bubbleUp && detector != null) backBubble?.hide()
        // Selama gelembung "Kembali ke game" tampil, pengguna sedang memakai aplikasi lain: beri waktu lebih lama.
        val awayLimit = if (bubbleUp) 20 * 60_000L else 90_000L
        if (!inGame && awaySince > 0 && now - awaySince > awayLimit) { stopSelf(); return }
        if (panel == null && now >= hideUntil) handleView?.visibility = if (inGame || now - awaySince < 3000) View.VISIBLE else View.GONE
        if (inGame) {
            session += dt
            saveAcc += dt
            if (saveAcc >= 15_000) { Prefs.addPlay(pkg, saveAcc); saveAcc = 0 }
        }

        val b = SystemStats.battery(this)
        if (Prefs.autoAdjust && profile.mode == GameProfile.MODE_PRO) {
            if (!hotThrottled && b.tempC >= Prefs.tempWarn) {
                hotThrottled = true
                applyEffective(false)
                toast("Perangkat panas, beralih ke mode Seimbang sementara")
            }
        }
        if (hotThrottled && b.tempC <= Prefs.tempWarn - 3) {
            hotThrottled = false
            applyEffective(false)
            toast("Suhu normal, Gamer Pro dipulihkan")
        }
        panel?.update(fps.fps, fps.exact, ping.lastMs, b.tempC, b.pct)
    }

    private fun applyEffective(clean: Boolean) {
        val p = profile
        val mode = if (hotThrottled) GameProfile.MODE_BALANCED else p.mode
        // Petunjuk refresh rate lewat jendela overlay: bekerja tanpa izin "Ubah pengaturan sistem" di banyak perangkat.
        handler.post { hintRefresh(try { Booster.targetRefresh(this, p, mode) } catch (_: Exception) { 0 }) }
        bg.execute { try { Booster.apply(this, pkg, p, mode, clean) } catch (_: Exception) {} }
    }

    private fun hintRefresh(hz: Int) {
        val params = handleParams ?: return
        params.preferredRefreshRate = hz.toFloat()
        handleView?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    // ---------- Jendela: handle ----------
    private fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }

    @Suppress("DEPRECATION")
    private fun handleY(ins: WindowInsets): Int {
        val gesture = try { ins.systemGestureInsets.bottom } catch (_: Throwable) { 0 }
        val base = maxOf(gesture, ins.systemWindowInsetBottom)
        return when {
            base > 0 -> base + dp(2)
            isGestureNav() -> dp(26)
            else -> dp(6)
        }
    }

    private fun isGestureNav(): Boolean {
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        return id > 0 && resources.getInteger(id) == 2
    }

    /**
     * Pegangan di bawah-tengah layar: geser ke ATAS (atau ketuk) untuk membuka overlay.
     * Area sentuhnya sengaja dibuat lebih tinggi & ditaruh DI ATAS zona gestur sistem (swipe-home),
     * kalau tidak, sistem yang menangkap gesekan dan overlay tidak pernah muncul. Pill kecilnya
     * hanya penanda visual; seluruh area transparan di sekitarnya ikut menangkap gesekan.
     */
    private fun addHandle() {
        val root = FrameLayout(this)
        val pill = View(this)
        pill.background = gradientRounded(NX.ACCENT, NX.CYAN, dpf(3f))
        pill.glow(NX.ACCENT, 6f)
        root.addView(pill, FrameLayout.LayoutParams(dp(64), dp(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(6) })
        root.alpha = 0.55f

        val params = overlayParams(
            dp(if (othersPresent) 160 else 220), dp(56),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (Prefs.keepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = dp(26)
        handleParams = params
        var lastY = params.y
        // Posisi dihitung dari insets nyata perangkat: tepat di atas zona gestur sistem (gestur) atau bilah
        // navigasi (3 tombol), jadi pill menempel di bawah-tengah layar apa pun jenis navigasinya.
        root.setOnApplyWindowInsetsListener { v, ins ->
            val y = handleY(ins)
            if (y != lastY) {
                lastY = y
                params.y = y
                v.post { try { wm.updateViewLayout(v, params) } catch (_: Exception) {} }
            }
            ins
        }

        var downY = 0f
        var opened = false
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; opened = false; moved = false
                    root.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val up = downY - e.rawY
                    if (up > slop) moved = true
                    if (!opened && up > dp(18)) { opened = true; openPanel() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!opened && !moved && e.actionMasked == MotionEvent.ACTION_UP) openPanel()
                    root.animate().alpha(0.55f).scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
            true
        }
        handleView = root
        try {
            wm.addView(root, params)
            // Minta sistem tidak merebut gesekan di area ini (Android 10+).
            root.post {
                try { root.systemGestureExclusionRects = listOf(Rect(0, 0, root.width, root.height)) } catch (_: Exception) {}
            }
            // Berdenyut dua kali saat muncul supaya pengguna tahu letaknya.
            root.animate().alpha(1f).setDuration(350).withEndAction {
                root.animate().alpha(0.55f).setDuration(350).withEndAction {
                    root.animate().alpha(1f).setDuration(350).withEndAction {
                        root.animate().alpha(0.55f).setDuration(350).start()
                    }.start()
                }.start()
            }.start()
        } catch (_: Exception) { stopSelf() }
    }

    private fun removeHandle() {
        handleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        handleView = null
    }

    override fun relayout() {
        closePanel(false)
        removeHandle()
        addGuards()
        addHandle()
    }

    // ---------- Panel ----------
    private fun openPanel() {
        if (panel != null) return
        val ui = try { PanelUi(this, this).also { it.build() } } catch (e: Exception) {
            Toast.makeText(applicationContext, "Gagal membuka panel Asisten", Toast.LENGTH_SHORT).show()
            return
        }
        // Blur latar (Android 12+, bila perangkat mendukung) memberi kesan kaca premium di atas game.
        val blur = Build.VERSION.SDK_INT >= 31 && Prefs.panelBlur && try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (blur) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
        )
        if (blur && Build.VERSION.SDK_INT >= 31) params.blurBehindRadius = 0
        try {
            wm.addView(ui.root, params)
            panelParams = params
            if (blur) animateBlur(ui.root, params, 0, dp(16), 520)
            panel = ui
            ui.start()
            handleView?.visibility = View.INVISIBLE
            fps.start(pkg)
            ping.start()
        } catch (_: Exception) {
            Toast.makeText(applicationContext, "Panel gagal tampil. Cek izin 'Tampil di atas aplikasi lain'", Toast.LENGTH_LONG).show()
        }
    }

    private fun closePanel(animate: Boolean = true) {
        val p = panel
        panel = null
        if (p != null) {
            fps.stop()
            ping.stop()
            if (animate) {
                panelParams?.let { pp -> if (Build.VERSION.SDK_INT >= 31 && pp.blurBehindRadius > 0) animateBlur(p.root, pp, pp.blurBehindRadius, 0, 300) }
                p.animateOut { try { wm.removeView(p.root) } catch (_: Exception) {} }
            }
            else try { wm.removeView(p.root) } catch (_: Exception) {}
        }
        handleView?.visibility = View.VISIBLE
    }

    private fun animateBlur(v: View, p: WindowManager.LayoutParams, from: Int, to: Int, dur: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        var last = from
        Motion.run(dur) { t ->
            val r = (from + (to - from) * Motion.outCubic(t)).toInt()
            // Perbarui jendela hanya bila radius berubah cukup besar, supaya tidak membebani game.
            if (abs(r - last) >= dp(4) || t >= 1f) {
                last = r
                p.blurBehindRadius = r
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
        }
    }

    override fun close() = closePanel()

    // ---------- Crosshair & pelindung tepi ----------
    override fun setCrosshair(on: Boolean) {
        Prefs.crosshair = on
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        crosshairView = null
        if (!on) return
        val size = dp(36)
        val v = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                p.color = Color.WHITE; p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, dpf(2.5f), p)
                p.color = Color.parseColor("#FF5D5D"); p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.5f)
                c.drawCircle(cx, cy, dpf(10f), p)
                c.drawLine(cx - dpf(16f), cy, cx - dpf(6f), cy, p)
                c.drawLine(cx + dpf(6f), cy, cx + dpf(16f), cy, p)
            }
        }
        val params = overlayParams(
            size, size,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        params.gravity = Gravity.CENTER
        try { wm.addView(v, params); crosshairView = v } catch (_: Exception) {}
    }

    private fun addGuards() {
        removeGuards()
        if (!Prefs.edgeGuard) return
        fun make(gravity: Int): View {
            val v = View(this)
            v.setOnTouchListener { _, _ -> true }
            val p = overlayParams(dp(if (othersPresent) 14 else 22), WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            p.gravity = gravity or Gravity.TOP
            try { wm.addView(v, p) } catch (_: Exception) {}
            return v
        }
        guardL = make(Gravity.START)
        guardR = make(Gravity.END)
    }

    private fun removeGuards() {
        guardL?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        guardR?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        guardL = null; guardR = null
    }

    override fun setEdgeGuard(on: Boolean) {
        Prefs.edgeGuard = on
        // Handle harus di atas pelindung: pasang ulang handle setelah pelindung.
        removeHandle()
        addGuards()
        addHandle()
        if (panel != null) handleView?.visibility = View.INVISIBLE
        toast(if (on) "Pencegahan salah sentuh aktif di kedua tepi" else "Pencegahan salah sentuh mati")
    }

    // ---------- PanelHost ----------
    override fun profile(): GameProfile = profile

    override fun updateProfile(p: GameProfile) {
        profile = p
        Prefs.saveProfile(pkg, p)
        hotThrottled = false
        applyEffective(p.mode == GameProfile.MODE_PRO)
    }

    override fun toast(msg: String) {
        handler.post { showBanner(msg) }
    }

    private var hudInst: HudCard? = null
    private val hud: HudCard get() = hudInst ?: HudCard(this, wm, handler).also { hudInst = it }

    /** Keterangan singkat untuk tombol yang bisa nyala/mati, supaya notifikasinya bermakna. */
    private fun describe(title: String, on: Boolean): String? = when (title) {
        "Mulai cepat" -> if (on) "Aktif · game dioptimalkan otomatis saat dibuka" else "Mati · optimasi manual lewat Boost"
        "Kunci tepi" -> if (on) "Aktif · sentuhan tak sengaja di tepi diabaikan" else "Mati"
        "Layar menyala" -> if (on) "Aktif · layar tidak padam saat bermain" else "Mati · layar mengikuti timeout biasa"
        "Bidik" -> if (on) "Aktif · titik bidik tampil di tengah layar" else "Mati"
        "Senyap" -> if (on) "Aktif · notifikasi & panggilan disenyapkan" else "Mati"
        "Kunci orientasi" -> if (on) "Aktif · layar tidak berputar sendiri" else "Mati"
        else -> null
    }

    /** Notifikasi kartu kaca beranimasi (meluncur dengan pegas + garis waktu), tidak menghalangi sentuhan. */
    private fun showBanner(msg: String) {
        val lower = msg.lowercase()
        val warn = lower.contains("gagal") || lower.contains("panas") || lower.contains("izin") || lower.contains("tidak ")
        var title = msg
        var sub: String? = null
        var on: Boolean? = null
        val dot = msg.indexOf(" · ")
        val colon = msg.indexOf(": ")
        when {
            dot > 0 -> { title = msg.substring(0, dot); sub = msg.substring(dot + 3) }
            colon in 1..24 -> {
                title = msg.substring(0, colon)
                val tail = msg.substring(colon + 2)
                if (tail == "aktif" || tail == "mati") {
                    on = tail == "aktif"
                    sub = describe(title, on) ?: tail.replaceFirstChar { it.uppercase() }
                } else sub = tail
            }
        }
        val kind = when {
            warn -> HudCard.Kind.WARN
            on == false -> HudCard.Kind.INFO
            else -> HudCard.Kind.OK
        }
        val t = title.lowercase()
        val icon = when {
            warn -> R.drawable.ic_warn
            t.contains("rekam") || t.contains("video") -> R.drawable.ic_video
            t.contains("cuplikan") -> R.drawable.ic_scissors
            t.contains("boost") || t.contains("cepat") || t.contains("ram") -> R.drawable.ic_bolt
            t.contains("jaringan") || t.contains("ping") || t.contains("wlan") || t.contains("wi-fi") -> R.drawable.ic_wifi
            t.contains("senyap") || t.contains("fokus") -> R.drawable.ic_bell
            t.contains("bidik") -> R.drawable.ic_crosshair
            t.contains("tepi") -> R.drawable.ic_hand
            t.contains("refresh") || t.contains("layar") -> R.drawable.ic_monitor
            t.contains("orientasi") -> R.drawable.ic_rotate
            t.contains("mode") -> R.drawable.ic_speed
            else -> R.drawable.ic_check
        }
        hud.toast(title, sub, kind, icon)
    }

    private fun gb(mb: Long) = String.format("%.1f GB", mb / 1024f)
    private fun quality(frac: Float) = when {
        frac > 0.66f -> NX.GOOD
        frac > 0.33f -> NX.WARN
        else -> NX.HOT
    }

    override fun boost() {
        hud.begin("Boost", R.drawable.ic_bolt, listOf("Kosongkan RAM", "Kunci Wi-Fi", "Atur performa", "Uji jaringan"))
        bg.execute {
            val r = try { Booster.cleanMemory(this, setOf(pkg)) } catch (_: Exception) { Booster.CleanResult(0, 0) }
            handler.post { hud.step(0, if (r.freedMb > 0) "+${r.freedMb} MB" else "${r.killed} app") }
            Thread.sleep(300)
            Booster.holdWifi(this, true)
            handler.post { hud.step(1, "Aktif") }
            Thread.sleep(300)
            handler.post { applyEffective(false); hud.step(2, "Siap") }
            val (avg, _, loss) = ping.burst()
            handler.post { hud.step(3, if (avg < 0) "Offline" else "$avg ms") }
            Thread.sleep(450)
            val (free, total) = SystemStats.ramMb(this)
            val ramFrac = if (total > 0) free.toFloat() / total else 0f
            val pingFrac = if (avg < 0) 0f else (1f - avg / 300f).coerceIn(0f, 1f)
            handler.post {
                hud.finish(
                    if (avg < 0) "Selesai · tanpa koneksi internet" else "Selesai · siap bermain",
                    if (avg < 0) HudCard.Kind.WARN else HudCard.Kind.OK,
                    if (avg < 0) null else "SIAP MAIN",
                    listOf(
                        HudCard.Metric("RAM bebas", "${gb(free)} / ${gb(total)}", ramFrac, quality(ramFrac * 2.2f)),
                        HudCard.Metric("RAM dibebaskan", "+${r.freedMb} MB", (r.freedMb / 1024f).coerceIn(0f, 1f), NX.CYAN),
                        HudCard.Metric("Ping", if (avg < 0) "--" else "$avg ms" + if (loss > 0) " · loss $loss%" else "", pingFrac, quality(pingFrac))
                    ),
                    if (r.freedMb == 0L) "Android menjaga aplikasi tetap di memori; RAM bebas tidak selalu bertambah banyak." else null
                )
            }
        }
    }

    override fun cleanMemory() {
        hud.begin("Kosongkan RAM", R.drawable.ic_nuclear, listOf("Memindai aplikasi latar", "Menghentikan proses", "Mengukur RAM"))
        handler.postDelayed({ hud.step(0) }, 500)
        handler.postDelayed({ hud.step(1) }, 1400)
        bg.execute {
            val r = try { Booster.cleanMemory(this, setOf(pkg)) } catch (_: Exception) { Booster.CleanResult(0, 0) }
            handler.post {
                hud.step(1, "${r.killed} app")
                hud.step(2, if (r.freedMb > 0) "+${r.freedMb} MB" else "Stabil")
                val total = if (r.totalMb > 0) r.totalMb else SystemStats.ramMb(this).second
                val beforeFrac = if (total > 0) r.beforeMb.toFloat() / total else 0f
                val afterFrac = if (total > 0) r.afterMb.toFloat() / total else 0f
                hud.finish(
                    if (r.freedMb > 0) "Selesai · +${r.freedMb} MB dibebaskan" else "Selesai · RAM sudah lega",
                    HudCard.Kind.OK,
                    if (r.freedMb > 0) "+${r.freedMb} MB" else null,
                    listOf(
                        HudCard.Metric("Sebelum", gb(r.beforeMb), beforeFrac, quality(beforeFrac * 2.2f)),
                        HudCard.Metric("Sesudah", gb(r.afterMb), afterFrac, quality(afterFrac * 2.2f)),
                        HudCard.Metric("Aplikasi dihentikan", "${r.killed}", -1f, NX.CYAN)
                    ),
                    if (r.freedMb == 0L) "Android menjaga aplikasi tetap di memori; RAM bebas tidak selalu bertambah banyak." else null
                )
            }
        }
    }

    override fun checkNetwork() {
        hud.begin("Cek jaringan", R.drawable.ic_globe, listOf("Menyambung ke server", "Mengukur latensi", "Menilai kestabilan"))
        handler.postDelayed({ hud.step(0) }, 500)
        handler.postDelayed({ hud.step(1) }, 1400)
        bg.execute {
            val (avg, jitter, loss) = ping.burst()
            val type = SystemStats.networkType(this)
            handler.post {
                if (avg < 0) {
                    hud.finish(
                        "Tidak ada koneksi internet", HudCard.Kind.WARN, "OFFLINE",
                        listOf(HudCard.Metric("Jaringan", type, -1f, NX.WARN)),
                        "Periksa Wi-Fi atau paket data, lalu coba lagi."
                    )
                    return@post
                }
                hud.step(2, "$avg ms")
                val (grade, kind) = when {
                    avg < 50 && loss == 0 -> "SANGAT BAIK" to HudCard.Kind.OK
                    avg < 100 && loss <= 20 -> "BAIK" to HudCard.Kind.OK
                    avg < 180 -> "CUKUP" to HudCard.Kind.WARN
                    else -> "KURANG STABIL" to HudCard.Kind.WARN
                }
                val pf = (1f - avg / 250f).coerceIn(0f, 1f)
                val jf = (1f - jitter / 100f).coerceIn(0f, 1f)
                val lf = (1f - loss / 100f).coerceIn(0f, 1f)
                hud.finish(
                    "Selesai · $type", kind, grade,
                    listOf(
                        HudCard.Metric("Ping", "$avg ms", pf, quality(pf)),
                        HudCard.Metric("Jitter", "$jitter ms", jf, quality(jf)),
                        HudCard.Metric("Paket hilang", "$loss%", lf, quality(lf))
                    ),
                    when {
                        kind == HudCard.Kind.OK -> "Koneksi optimal untuk game online."
                        type == "Seluler" -> "Kurang stabil. Coba pindah ke Wi-Fi atau dekatkan ke jendela."
                        else -> "Kurang stabil. Dekatkan ke router atau gunakan Wi-Fi 5 GHz."
                    }
                )
            }
        }
    }

    override fun addToAssistant() {
        GameRepo.add(pkg)
        toast("$gameName ada di daftar Asisten Game")
    }

    override fun setKeepAwake(on: Boolean) {
        Prefs.keepAwake = on
        handleView?.keepScreenOn = on
        val p = handleParams ?: return
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        handleView?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
    }

    override fun setFocus(on: Boolean) {
        Prefs.focusMode = on
        if (!Perms.dnd(this)) { Prefs.focusMode = false; askDndPermission(); return }
        setDnd(on || profile.blockCalls || profile.blockNotif)
        toast(if (on) "Mode Fokus aktif" else "Mode Fokus mati")
    }

    override fun setDnd(on: Boolean) {
        if (!Perms.dnd(this)) { askDndPermission(); return }
        bg.execute { Booster.setDnd(this, on, true) }
    }

    /**
     * Panel layar penuh menutupi layar izin. Tutup panel dulu, baru buka pengaturannya, supaya layar izin
     * benar-benar terlihat dan bisa disentuh.
     */
    private fun openSettingsAfterClose(msg: String, open: () -> Unit) {
        closePanel()
        toast(msg)
        handler.postDelayed({ try { open() } catch (_: Exception) {} }, 380)
    }

    private fun askDndPermission() =
        openSettingsAfterClose("Aktifkan Nexus di 'Akses Jangan Ganggu', lalu buka panel lagi") { Perms.openDnd(this) }

    /** Sembunyikan pegangan sebentar supaya tidak ikut terpotret. */
    private fun hideHandleBriefly(ms: Long) {
        hideUntil = SystemClock.elapsedRealtime() + ms
        handleView?.visibility = View.INVISIBLE
        handler.postDelayed({ if (panel == null) handleView?.visibility = View.VISIBLE }, ms)
    }

    /** Buka Activity izin di task terpisah (tidak menarik Nexus ke depan, lalu kembali ke game). */
    private fun launchCapture(then: String) {
        try {
            startActivity(
                Intent(this, CaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(CaptureActivity.EXTRA_THEN, then)
            )
        } catch (_: Exception) {
            toast("Gagal membuka izin tangkap layar")
        }
    }

    override fun screenshot() {
        closePanel()
        hideHandleBriefly(1800)
        // Tunggu panel selesai menutup sebelum menangkap layar game.
        handler.postDelayed({
            if (ScreenCaptureService.ready) ScreenCaptureService.send(this, ScreenCaptureService.ACTION_SHOT)
            else launchCapture(ScreenCaptureService.ACTION_SHOT)
        }, 450)
    }

    override fun toggleRecord() {
        closePanel()
        val bar = recBar ?: RecordBar(this, wm, this).also { recBar = it }
        bar.show()
    }

    override fun beginRecording() {
        if (ScreenCaptureService.ready) ScreenCaptureService.send(this, ScreenCaptureService.ACTION_REC)
        else launchCapture(ScreenCaptureService.ACTION_REC)
    }

    override fun askMic() = launchCapture(CaptureActivity.THEN_MIC)

    override fun onBarClosed() { recBar = null }

    override fun isRecording() = ScreenCaptureService.recording

    override fun openWifi() { closePanel(); Perms.openWifiPanel(this) }

    private fun launchUi(cls: Class<*>, vararg extra: Pair<String, String>) {
        closePanel()
        val i = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extra.forEach { i.putExtra(it.first, it.second) }
        startActivity(i)
    }

    override fun openSettings() = launchUi(SettingsActivity::class.java)
    override fun openGameSettings() = launchUi(GameSettingsActivity::class.java, GameSettingsActivity.EXTRA_PKG to pkg)
    override fun openAlbum() = launchUi(AlbumActivity::class.java)
    override fun pickDockApps() = launchUi(GamePickerActivity::class.java, GamePickerActivity.EXTRA_MODE to GamePickerActivity.MODE_DOCK)

    override fun launchDock(pkg: String) {
        closePanel()
        val name = GameRepo.label(this, pkg)
        // Tunggu animasi tutup panel selesai supaya jendela baru langsung terlihat.
        handler.postDelayed({
            try {
                val canFloat = Prefs.floatingWindow && GameUtils.freeformSupported(this)
                val web = MiniWindow.siteFor(pkg, name)
                if (canFloat && GameUtils.launchFloating(this, pkg, Prefs.floatSize)) {
                    toast("$name dibuka sebagai jendela mengambang")
                } else if (web != null) {
                    // HP tanpa jendela mengambang: pakai Jendela mini bawaan dengan versi web aplikasi ini.
                    val m = mini ?: MiniWindow(this, wm) { mini = null }.also { mini = it }
                    m.show(web)
                } else {
                    // Tanpa izin atau pengaturan tambahan: buka penuh, lalu gelembung Nexus untuk kembali ke game.
                    GameUtils.launch(this, pkg)
                    showBackBubble()
                }
            } catch (_: Exception) {
                try { GameUtils.launch(this, pkg); showBackBubble() } catch (_: Exception) { toast("Gagal membuka $name") }
            }
        }, 300)
    }

    private fun showBackBubble() {
        val b = backBubble ?: BackBubble(this, wm) { returnToGame() }.also { backBubble = it }
        // Jangan tampilkan seketika: tunggu aplikasi tujuan benar-benar di depan supaya tidak ikut terhapus oleh deteksi "kembali ke game".
        handler.postDelayed({ if (started) b.show(GameRepo.icon(this, pkg)) }, 1200)
    }

    private fun returnToGame() {
        backBubble?.hide()
        try { GameUtils.launch(this, pkg) } catch (_: Exception) { toast("Gagal kembali ke game") }
    }

    override fun floatSupported() = GameUtils.freeformSupported(this)

    override fun gameIcon(): Drawable? = GameRepo.icon(this, pkg)

    /** Jendela mini bawaan (browser mengambang). Hanya butuh izin overlay yang sudah ada. */
    override fun openMiniWindow(index: Int) {
        val site = MiniWindow.SITES.getOrNull(index) ?: return
        closePanel()
        // Tunggu animasi tutup panel selesai supaya jendela mini muncul bersih di atas game.
        handler.postDelayed({
            if (!started) return@postDelayed
            val m = mini ?: MiniWindow(this, wm) { mini = null }.also { mini = it }
            try { m.show(site) } catch (_: Exception) { toast("Gagal membuka jendela mini") }
        }, 330)
    }

    override fun openFloatHelp() = launchUi(SettingsActivity::class.java)

    override fun pinShortcut() {
        val icon = GameRepo.icon(this, pkg) ?: return
        val ok = GameUtils.pinShortcut(this, pkg, gameName, icon)
        toast(if (ok) "Konfirmasi pintasan di layar Awal" else "Peluncur Anda tidak mendukung pintasan")
    }

    override fun dockApps(): List<Pair<String, Drawable>> =
        Prefs.dockApps.mapNotNull { p -> GameRepo.icon(this, p)?.let { p to it } }.take(5)

    override fun sessionMs() = session

    override fun requestWrite() =
        openSettingsAfterClose("Izinkan 'Ubah pengaturan sistem' untuk Nexus, lalu buka panel lagi") { Perms.openWrite(this) }

    // ---------- Selesai ----------
    override fun onDestroy() {
        started = false
        running = false
        currentPkg = null
        handler.removeCallbacksAndMessages(null)
        if (saveAcc > 0) Prefs.addPlay(pkg, saveAcc)
        fps.stop()
        ping.stop()
        closePanel(false)
        recBar?.hide()
        recBar = null
        backBubble?.hide(true)
        backBubble = null
        mini?.hide(true)
        mini = null
        hudInst?.release()
        if (ScreenCaptureService.ready || ScreenCaptureService.recording) ScreenCaptureService.send(this, ScreenCaptureService.ACTION_END)
        bannerView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bannerView = null
        Booster.holdWifi(this, false)
        removeHandle()
        removeGuards()
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        crosshairView = null
        val ctx = applicationContext
        bg.execute { try { Booster.restore(ctx) } catch (_: Exception) {} }
        bg.shutdown()
    }
}
