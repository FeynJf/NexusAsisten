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
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import com.nexus.asisten.core.GameUtils
import com.nexus.asisten.core.NotifCenter
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.PingMonitor
import com.nexus.asisten.core.ScreenCaptureService
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.NX
import com.nexus.asisten.ui.circle
import com.nexus.asisten.ui.dp
import com.nexus.asisten.ui.dpf
import com.nexus.asisten.ui.gradientRounded
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service(), PanelHost {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val ACTION_START = "nx.START"
        const val ACTION_STOP = "nx.STOP"
        const val ACTION_REAPPLY = "nx.REAPPLY"
        const val ACTION_RELOAD_DOCK = "nx.DOCK"
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
        fps.start(p)
        ping.start()
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
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle("Asisten Game aktif")
            .setContentText("$name · geser dari tepi layar untuk membuka panel")
            .setContentIntent(open)
            .addAction(0, "Berhenti", stop)
            .setOngoing(true)
            .build()
    }

    // ---------- Loop utama ----------
    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (_: Exception) {}
            if (started) handler.postDelayed(this, 1000)
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
        if (!inGame && awaySince > 0 && now - awaySince > 90_000) { stopSelf(); return }
        if (panel == null) handleView?.visibility = if (inGame || now - awaySince < 3000) View.VISIBLE else View.GONE
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
        notifDot?.visibility = if (Prefs.notifDots && NotifCenter.count > 0) View.VISIBLE else View.GONE
        panel?.update(fps.fps, fps.exact, ping.lastMs, b.tempC, b.pct)
    }

    private fun applyEffective(clean: Boolean) {
        val p = profile
        val mode = if (hotThrottled) GameProfile.MODE_BALANCED else p.mode
        bg.execute { try { Booster.apply(this, pkg, p, mode, clean) } catch (_: Exception) {} }
    }

    // ---------- Jendela: handle ----------
    private fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }

    private fun addHandle() {
        val left = Prefs.dockLeft
        val root = FrameLayout(this)
        val pill = View(this)
        pill.background = gradientRounded(NX.ACCENT, NX.CYAN, dpf(3f), android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
        root.addView(pill, FrameLayout.LayoutParams(dp(5), dp(60), (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL))
        val dot = View(this)
        dot.background = circle(NX.HOT)
        dot.visibility = View.GONE
        notifDot = dot
        root.addView(dot, FrameLayout.LayoutParams(dp(9), dp(9), (if (left) Gravity.START else Gravity.END) or Gravity.TOP).also {
            it.topMargin = dp(4)
        })
        root.alpha = 0.6f

        val params = overlayParams(dp(28), dp(88), WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (Prefs.keepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0))
        params.gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
        params.y = resources.displayMetrics.heightPixels / 2 - dp(44)
        handleParams = params

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startY = 0; var moved = false; var opened = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = params.y; moved = false; opened = false
                    root.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val inward = if (left) dx else -dx
                    if (!opened && inward > dp(16) && abs(dy) < dp(30)) { opened = true; moved = true; openPanel() }
                    else if (!opened && abs(dy) > slop) {
                        moved = true
                        val maxY = resources.displayMetrics.heightPixels - dp(88)
                        params.y = (startY + dy).toInt().coerceIn(0, maxY)
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) openPanel()
                    root.alpha = 0.6f
                }
            }
            true
        }
        handleView = root
        try { wm.addView(root, params) } catch (_: Exception) { stopSelf() }
    }

    private fun removeHandle() {
        handleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        handleView = null
    }

    override fun relayout() {
        closePanel()
        removeHandle()
        addGuards()
        addHandle()
    }

    // ---------- Panel ----------
    private fun openPanel() {
        if (panel != null) return
        val ui = PanelUi(this, this)
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try {
            wm.addView(ui.root, params)
            panel = ui
            handleView?.visibility = View.INVISIBLE
        } catch (_: Exception) {
        }
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it.root) } catch (_: Exception) {} }
        panel = null
        handleView?.visibility = View.VISIBLE
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
            val p = overlayParams(dp(22), WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun cleanMemory() {
        toast("Mengosongkan RAM...")
        bg.execute {
            val r = Booster.cleanMemory(this, setOf(pkg))
            toast("${r.killed} aplikasi dihentikan, RAM bebas bertambah ${r.freedMb} MB")
        }
    }

    override fun checkNetwork() {
        toast("Menguji jaringan...")
        bg.execute {
            val (avg, jitter, loss) = ping.burst()
            val type = SystemStats.networkType(this)
            if (avg < 0) toast("Tidak ada koneksi internet")
            else {
                val verdict = when {
                    avg < 50 && loss == 0 -> "Sangat baik"
                    avg < 100 && loss <= 20 -> "Baik"
                    else -> if (type == "Seluler") "Kurang stabil, coba pakai Wi-Fi" else "Kurang stabil"
                }
                toast("$type · $avg ms · jitter $jitter ms · loss $loss% · $verdict")
            }
        }
    }

    override fun addToAssistant() {
        GameRepo.add(pkg)
        toast("$gameName ada di daftar Asisten Game")
    }

    override fun setKeepAwake(on: Boolean) {
        Prefs.keepAwake = on
        val p = handleParams ?: return
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        handleView?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
    }

    override fun setFocus(on: Boolean) {
        Prefs.focusMode = on
        if (!Perms.dnd(this)) { Prefs.focusMode = false; toast("Beri izin Jangan Ganggu dulu"); Perms.openDnd(this); return }
        setDnd(on || profile.blockCalls || profile.blockNotif)
        toast(if (on) "Mode Fokus aktif" else "Mode Fokus mati")
    }

    override fun setDnd(on: Boolean) {
        if (!Perms.dnd(this)) { toast("Beri izin Jangan Ganggu dulu"); Perms.openDnd(this); return }
        bg.execute { Booster.setDnd(this, on) }
    }

    override fun screenshot() {
        closePanel()
        // Beri waktu panel hilang sebelum menangkap layar.
        handler.postDelayed({
            startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("action", ScreenCaptureService.ACTION_SHOT))
        }, 250)
    }

    override fun toggleRecord() {
        if (ScreenCaptureService.recording) {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        } else {
            closePanel()
            handler.postDelayed({
                startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("action", ScreenCaptureService.ACTION_REC))
            }, 250)
        }
    }

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
        try {
            if (Prefs.floatingWindow) GameUtils.launchFloating(this, pkg) else GameUtils.launch(this, pkg)
        } catch (_: Exception) {
            GameUtils.launch(this, pkg)
        }
    }

    override fun pinShortcut() {
        val icon = GameRepo.icon(this, pkg) ?: return
        val ok = GameUtils.pinShortcut(this, pkg, gameName, icon)
        toast(if (ok) "Konfirmasi pintasan di layar Awal" else "Peluncur Anda tidak mendukung pintasan")
    }

    override fun dockApps(): List<Pair<String, Drawable>> =
        Prefs.dockApps.mapNotNull { p -> GameRepo.icon(this, p)?.let { p to it } }.take(5)

    override fun sessionMs() = session

    override fun requestWrite() { toast("Izinkan 'Ubah pengaturan sistem'"); Perms.openWrite(this) }
    override fun requestNotifAccess() { toast("Aktifkan akses notifikasi untuk Nexus Asisten"); Perms.openNotifListener(this) }

    // ---------- Selesai ----------
    override fun onDestroy() {
        started = false
        running = false
        currentPkg = null
        handler.removeCallbacksAndMessages(null)
        if (saveAcc > 0) Prefs.addPlay(pkg, saveAcc)
        fps.stop()
        ping.stop()
        closePanel()
        removeHandle()
        removeGuards()
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        crosshairView = null
        val ctx = applicationContext
        bg.execute { try { Booster.restore(ctx) } catch (_: Exception) {} }
        bg.shutdow
