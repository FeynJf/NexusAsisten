package com.nexus.asisten

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.GameUtils
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.core.Updater
import com.nexus.asisten.data.GameApp
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.PlayStats
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.overlay.OverlayService
import com.nexus.asisten.ui.*
import com.nexus.asisten.core.Avatar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_QUICK_LAUNCH = "nx.QUICK_LAUNCH"
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private var games: List<GameApp> = emptyList()
    private var selected: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var gBat: RingGauge? = null
    private var gTemp: RingGauge? = null
    private var tvRam: TextView? = null
    private var pbRam: android.widget.ProgressBar? = null
    private var tvDisk: TextView? = null
    private var pbDisk: android.widget.ProgressBar? = null
    private var tvNet: TextView? = null
    private var updateShown = false
    private var ringIntro = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        scroll = ScrollView(this)
        scroll.background = screenBg()
        scroll.isVerticalScrollBarEnabled = false
        root = vbox(18, 12)
        scroll.addView(root)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.action == ACTION_QUICK_LAUNCH) {
            i.getStringExtra(EXTRA_PKG)?.let { pkg ->
                GameRepo.add(pkg)
                selected = pkg
                handler.post { startGame(pkg) }
            }
            i.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.autoCheck(this)
        build(true)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // Kartu pembaruan muncul otomatis begitu pemeriksaan selesai.
            if (Updater.wantsCard() != updateShown) build(false) else updateLive()
            handler.postDelayed(this, 2000)
        }
    }

    private fun updateLive() {
        val b = SystemStats.battery(this)
        val (free, total) = SystemStats.ramMb(this)
        val heat = ((b.tempC - 25f) / 25f).coerceIn(0f, 1f)
        val bat = b.pct / 100f
        val tempColor = when { heat > 0.75f -> NX.HOT; heat > 0.5f -> NX.WARN; else -> NX.ACCENT }
        val apply = { k: Float ->
            gBat?.set(bat * k, "${(b.pct * k).toInt()}%", if (b.charging) "Mengisi" else "Baterai", if (bat < 0.2f) NX.HOT else NX.GOOD, NX.CYAN)
            gTemp?.set(heat * k, String.format("%.0f°C", b.tempC), "Suhu", tempColor, NX.MAGENTA)
        }
        if (ringIntro) {
            ringIntro = false
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { a -> apply(a.animatedValue as Float) }
                start()
            }
        } else apply(1f)
        val used = (total - free).coerceAtLeast(0)
        tvRam?.text = String.format("%.1f GB / %.1f GB", used / 1024f, total / 1024f)
        pbRam?.progress = if (total > 0) (used * 100L / total).toInt() else 0
        try {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val tot = st.blockCountLong * st.blockSizeLong
            val fr = st.availableBytes
            val u = (tot - fr).coerceAtLeast(0L)
            tvDisk?.text = String.format("%.1f GB / %.0f GB", u / 1073741824f, tot / 1073741824f)
            pbDisk?.progress = if (tot > 0) (u * 100L / tot).toInt() else 0
        } catch (_: Exception) {}
        tvNet?.text = SystemStats.networkType(this)
    }

    private fun profileFor(mode: Int) = when (mode) {
        GameProfile.MODE_SAVER -> floatArrayOf(0.5f, 0.4f, 0.55f)
        GameProfile.MODE_PRO -> floatArrayOf(1f, 1f, 0.9f)
        else -> floatArrayOf(0.7f, 0.7f, 0.75f)
    }

    private fun accentFor(mode: Int) = when (mode) {
        GameProfile.MODE_SAVER -> NX.GOOD
        GameProfile.MODE_PRO -> NX.PRO
        else -> NX.CYAN
    }

    // ---------- Tampilan ----------
    private fun build(anim: Boolean) {
        root.removeAllViews()
        games = GameRepo.savedGames(this)
        if (selected == null || games.none { it.pkg == selected }) {
            selected = games.firstOrNull { it.pkg == Prefs.lastGame }?.pkg ?: games.firstOrNull()?.pkg
        }
        buildTopBar()
        updateShown = Updater.wantsCard()
        if (updateShown) root.add(updateCard(), b = 16)
        if (games.isEmpty()) buildEmpty() else {
            buildCarousel()
            buildHero()
        }
        buildDeviceCard()
        buildQuickGrid()
        buildPlaytime()
        buildPermissions()
        root.add(View(this), MATCH, dp(28))
        ringIntro = anim
        updateLive()
        if (anim) Fx.stagger(root)
    }

    private fun buildTopBar() {
        val bar = hbox()
        val logo = FrameLayout(this)
        logo.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.ACCENT_DEEP, NX.BLUE, NX.CYAN))
            .also { it.cornerRadius = dpf(16f); it.setStroke(1, withAlpha(Color.WHITE, 0x66)) }
        logo.glow(NX.CYAN, 14f)
        logo.addView(icon(R.drawable.ic_gamepad, Color.WHITE, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        bar.add(logo, dp(46), dp(46), 0f, 0, 0, 14, 0)
        val t = vbox()
        t.addView(tv("NEXUS", 26f, NX.TEXT, 700).also { it.letterSpacing = 0.26f })
        t.add(tv("Halo, ${Prefs.userName}", 12.5f, NX.TEXT2), t = 0)
        bar.add(t, 0, WRAP, 1f)
        val av = FrameLayout(this)
        av.background = rounded(0, dpf(22f), withAlpha(NX.CYAN, 0xAA), dp(2))
        av.setPadding(dp(3), dp(3), dp(3), dp(3))
        val avIv = android.widget.ImageView(this)
        avIv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        avIv.background = circle(NX.SURFACE2)
        avIv.clipToOutline = true
        avIv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        Avatar.into(this, avIv)
        av.addView(avIv, FrameLayout.LayoutParams(MATCH, MATCH))
        av.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }
        av.pressFx(0.9f)
        bar.add(av, dp(44), dp(44), 0f, 0, 0, 10, 0)
        val set = FrameLayout(this)
        val r = dpf(15f)
        set.rippleBg(rounded(withAlpha(Color.WHITE, 0x10), r, NX.LINE, 1), r)
        set.addView(icon(R.drawable.ic_settings, NX.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        set.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bar.add(set, dp(44), dp(44))
        root.add(bar, b = 18)
    }

    private fun buildEmpty() {
        val c = card(26)
        c.setPadding(dp(22), dp(26), dp(22), dp(22))
        c.addView(tv("Belum ada game", 24f, NX.TEXT, 700))
        c.add(tv("Tambahkan game agar Nexus bisa menyiapkan performa, overlay, dan statistik waktu bermain.", 14f, NX.TEXT2), t = 8, b = 20)
        c.addView(gradientButton("Deteksi game otomatis") { autoDetect() })
        c.add(ghostButton("Pilih manual") { openPicker() }, t = 10)
        root.add(c, b = 16)
    }

    private fun buildCarousel() {
        val head = hbox()
        head.add(sectionTitle("Game saya"), 0, WRAP, 1f)
        val add = tv("+ Tambah", 12f, NX.CYAN, 700)
        add.setPadding(dp(10), dp(6), dp(10), dp(6))
        add.setOnClickListener { openPicker() }
        add.pressFx(0.92f)
        head.addView(add)
        root.add(head, b = 6)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val row = hbox()
        row.setPadding(dp(2), dp(8), dp(2), dp(12))
        games.forEach { g ->
            val on = g.pkg == selected
            val cell = FrameLayout(this)
            cell.setPadding(dp(4), dp(4), dp(4), dp(4))
            cell.background = if (on) rounded(0, dpf(24f), NX.CYAN, dp(2)) else null
            if (on) cell.glow(NX.CYAN, 10f)
            cell.addView(appIcon(g.icon, 58, 18), FrameLayout.LayoutParams(dp(58), dp(58)))
            cell.setOnClickListener { selected = g.pkg; Prefs.lastGame = g.pkg; build(false); Fx.say(this, "${g.name} dipilih") }
            cell.pressFx(0.9f)
            cell.setOnLongClickListener { gameMenu(g); true }
            val item = vbox()
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.add(cell, WRAP, WRAP)
            item.add(tv(g.name, 10f, if (on) NX.TEXT else NX.TEXT2, 500).also { it.maxLines = 1; it.gravity = Gravity.CENTER; it.ellipsize = android.text.TextUtils.TruncateAt.END }, dp(70), WRAP, 0f, 0, 2, 0, 0)
            row.add(item, WRAP, WRAP, 0f, 0, 0, 8, 0)
        }
        val more = vbox()
        more.gravity = Gravity.CENTER_HORIZONTAL
        val plus = FrameLayout(this)
        plus.background = rounded(withAlpha(Color.WHITE, 0x10), dpf(18f), NX.LINE, 1)
        plus.addView(icon(R.drawable.ic_add, NX.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        plus.setOnClickListener { openPicker() }
        plus.pressFx(0.9f)
        more.add(plus, dp(58), dp(58), 0f, 4, 4, 4, 0)
        more.add(tv("Lainnya", 10f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER }, dp(70), WRAP, 0f, 0, 2, 0, 0)
        row.addView(more)
        hs.addView(row)
        root.add(hs, b = 6)
    }

    private fun buildHero() {
        val g = games.first { it.pkg == selected }
        val prof = Prefs.profile(g.pkg)
        val hero = vbox(18, 18)
        hero.background = NeonDrawable(
            Color.parseColor("#FF3A22C4"), Color.parseColor("#FF080A1C"), dpf(28f),
            withAlpha(Color.WHITE, 0x66), withAlpha(NX.CYAN, 0x88), dpf(1.4f)
        )
        hero.glow(NX.ACCENT, 18f)
        val body = hbox()
        val ic = appIcon(g.icon, 112, 26)
        ic.glow(NX.CYAN, 14f)
        body.add(ic, dp(112), dp(112), 0f, 0, 0, 16, 0)
        val right = vbox()
        right.addView(tv(g.name, 21f, NX.TEXT, 700).also { it.maxLines = 2 })
        val modeName = listOf("Hemat daya", "Seimbang", "Gamer Pro")[prof.mode]
        val chips = hbox()
        val tag = tv(modeName, 11f, Color.WHITE, 700)
        tag.isAllCaps = true
        tag.setPadding(dp(10), dp(4), dp(10), dp(4))
        tag.background = rounded(withAlpha(NX.ACCENT, 0x88), dpf(10f), withAlpha(Color.WHITE, 0x55), 1)
        chips.addView(tag)
        if (OverlayService.running && OverlayService.currentPkg == g.pkg) {
            val live = tv("● Aktif", 11f, NX.GOOD, 700)
            live.setPadding(dp(10), dp(4), dp(4), dp(4))
            chips.addView(live)
        }
        right.add(chips, t = 6)
        right.add(gradientButton("Mulai") { startGame(g.pkg) }, t = 12)
        right.add(ghostButton("Performa") {
            startActivity(Intent(this, GameSettingsActivity::class.java).putExtra(GameSettingsActivity.EXTRA_PKG, g.pkg))
        }, t = 8)
        body.add(right, 0, WRAP, 1f)
        hero.addView(body)
        val m30 = PlayStats.last30(this, listOf(g.pkg))[g.pkg] ?: 0L
        hero.add(tv("Dimainkan ${fmtDuration(m30)} dalam 30 hari terakhir", 12f, 0xCCEAF0FF.toInt()), t = 12)
        root.add(hero, b = 18)
    }

    private fun statBar(label: String, color: Int): Triple<View, TextView, android.widget.ProgressBar> {
        val v = vbox()
        val head = hbox()
        head.add(tv(label, 11.5f, NX.TEXT2, 500), 0, WRAP, 1f)
        val value = tv("--", 11f, NX.TEXT, 700)
        head.addView(value)
        v.addView(head)
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100
        pb.progressTintList = android.content.res.ColorStateList.valueOf(color)
        pb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(withAlpha(Color.WHITE, 0x22))
        v.add(pb, MATCH, dp(6), 0f, 0, 6, 0, 0)
        return Triple(v, value, pb)
    }

    private fun buildDeviceCard() {
        val c = card(26)
        c.setPadding(dp(16), dp(16), dp(16), dp(16))
        c.addView(sectionTitle("Perangkat sekarang"))
        val row = hbox()
        val gb = RingGauge(this); gBat = gb
        val gt = RingGauge(this); gTemp = gt
        row.add(gb, dp(104), dp(104), 0f, 0, 10, 8, 0)
        row.add(gt, dp(104), dp(104), 0f, 0, 10, 12, 0)
        val info = vbox()
        val ram = statBar("Memori", NX.ACCENT); tvRam = ram.second; pbRam = ram.third
        val disk = statBar("Penyimpanan", NX.CYAN); tvDisk = disk.second; pbDisk = disk.third
        info.addView(ram.first)
        info.add(disk.first, t = 12)
        val net = hbox()
        net.add(tv("Jaringan", 11.5f, NX.TEXT2, 500), 0, WRAP, 1f)
        val nv = tv("--", 11f, NX.GOOD, 700); tvNet = nv
        net.addView(nv)
        info.add(net, t = 12)
        row.add(info, 0, WRAP, 1f)
        c.addView(row)
        c.add(ghostButton("Kosongkan RAM") { cleanRam() }, t = 12)
        root.add(c, b = 18)
    }

    private fun quickTile(iconRes: Int, title: String, sub: String, accent: Int = NX.ACCENT, onClick: () -> Unit): View {
        val v = vbox(14, 14)
        v.background = NeonDrawable(Color.parseColor("#E6141A33"), Color.parseColor("#EE090B18"), dpf(22f), strokePx = dpf(1.2f))
        val badge = FrameLayout(this)
        badge.background = gradientRounded(withAlpha(accent, 0x66), withAlpha(accent, 0x1E), dpf(14f), GradientDrawable.Orientation.TL_BR)
            .also { it.setStroke(1, withAlpha(accent, 0xAA)) }
        badge.addView(icon(iconRes, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        v.add(badge, dp(42), dp(42))
        v.add(tv(title, 15f, NX.TEXT, 700), t = 12)
        v.add(tv(sub, 11.5f, NX.TEXT2).also { it.maxLines = 2 }, t = 2)
        v.setOnClickListener { onClick() }
        v.pressFx(0.95f)
        return v
    }

    private fun buildQuickGrid() {
        root.add(sectionTitle("Akses cepat"), b = 10)
        val tiles = ArrayList<View>()
        if (Prefs.albumEnabled) tiles.add(quickTile(R.drawable.ic_photo, "Album", "Galeri & cuplikan", NX.MAGENTA) {
            startActivity(Intent(this, AlbumActivity::class.java))
        })
        tiles.add(quickTile(R.drawable.ic_gamepad, "Kelola game", "Atur game & data", NX.CYAN) { openPicker() })
        tiles.add(quickTile(R.drawable.ic_monitor, "Aplikasi saya", "Aplikasi terinstal", NX.BLUE) {
            startActivity(Intent(this, GamePickerActivity::class.java).putExtra(GamePickerActivity.EXTRA_MODE, GamePickerActivity.MODE_DOCK))
        })
        tiles.add(quickTile(R.drawable.ic_person, "Profil", if (Prefs.googleEmail.isNotEmpty()) Prefs.googleEmail else "Pengaturan akun", NX.GOOD) {
            startActivity(Intent(this, ProfileActivity::class.java))
        })
        var i = 0
        while (i < tiles.size) {
            val row = hbox(false)
            row.add(tiles[i], 0, WRAP, 1f, 0, 0, 5, 0)
            row.add(tiles.getOrNull(i + 1) ?: View(this), 0, WRAP, 1f, 5, 0, 0, 0)
            root.add(row, b = 10)
            i += 2
        }
        root.add(View(this), MATCH, dp(8))
    }

    private fun buildPlaytime() {
        val c = card(26)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        val pkgs = games.map { it.pkg }
        val totals = PlayStats.total(this, pkgs)
        val sum = totals.values.sum()
        val head = hbox()
        head.add(sectionTitle("Waktu dimainkan"), 0, WRAP, 1f)
        head.addView(tv(fmtDuration(sum), 18f, NX.CYAN, 700))
        c.addView(head)
        val top = games.sortedByDescending { totals[it.pkg] ?: 0L }.take(3)
        val max = (top.firstOrNull()?.let { totals[it.pkg] } ?: 0L).coerceAtLeast(1L)
        top.forEach { g ->
            val ms = totals[g.pkg] ?: 0L
            val r = hbox()
            r.add(appIcon(g.icon, 36, 11), dp(36), dp(36), 0f, 0, 0, 12, 0)
            val m = vbox()
            val line = hbox()
            line.add(tv(g.name, 14f, NX.TEXT, 500).also { it.maxLines = 1 }, 0, WRAP, 1f)
            line.addView(tv(fmtDuration(ms), 13f, NX.TEXT2))
            m.addView(line)
            val track = FrameLayout(this)
            track.background = rounded(withAlpha(Color.WHITE, 0x14), dpf(3f))
            val bar = View(this)
            bar.background = gradientRounded(NX.ACCENT, NX.CYAN, dpf(3f))
            track.addView(bar, FrameLayout.LayoutParams(0, MATCH))
            track.post {
                val target = (track.width * ms / max).toInt()
                ValueAnimator.ofInt(0, target).apply {
                    duration = 800
                    startDelay = 250
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener { a -> bar.layoutParams = FrameLayout.LayoutParams(a.animatedValue as Int, MATCH) }
                    start()
                }
            }
            m.add(track, MATCH, dp(5), 0f, 0, 8, 0, 0)
            r.add(m, 0, WRAP, 1f)
            c.add(r, t = 14)
        }
        if (games.isEmpty()) c.add(tv("Belum ada data.", 13f, NX.MUTED), t = 8)
        if (!Perms.usage(this)) {
            c.add(tv("Aktifkan Akses penggunaan agar waktu bermain akurat dan overlay tahu kapan game ditutup.", 12.5f, NX.MUTED), t = 14)
            c.add(ghostButton("Aktifkan akses penggunaan") { Perms.openUsage(this) }, t = 8)
        }
        root.add(c, b = 16)
    }

    private fun buildPermissions() {
        val keys = setOf("overlay", "usage", "write", "dnd", "post_notif")
        val list = permItems().filter { !it.ok && it.id in keys }
        if (list.isEmpty()) return
        val c = card(26)
        c.setPadding(dp(18), dp(16), dp(18), dp(10))
        c.addView(sectionTitle("Izin yang belum aktif"))
        list.forEach { p ->
            val r = hbox()
            r.setPadding(0, dp(10), 0, dp(10))
            val dot = View(this)
            dot.background = circle(NX.WARN)
            r.add(dot, dp(8), dp(8), 0f, 0, 0, 12, 0)
            r.add(tv(p.name + if (p.required) " (wajib)" else "", 14f, NX.TEXT2), 0, WRAP, 1f)
            r.addView(tv("IZINKAN", 12f, NX.ACCENT, 700))
            r.setOnClickListener { handlePerm(p) }
            r.pressFx(0.98f)
            c.addView(r)
        }
        c.add(ghostButton("Pusat izin & perbaikan") { startActivity(Intent(this, SettingsActivity::class.java)) }, t = 8, b = 8)
        root.add(c)
    }

    // ---------- Aksi ----------
    private fun openPicker() {
        startActivity(Intent(this, GamePickerActivity::class.java).putExtra(GamePickerActivity.EXTRA_MODE, GamePickerActivity.MODE_GAMES))
    }

    private fun autoDetect() {
        val found = GameRepo.launchableApps(this).filter { it.isGame }
        if (found.isEmpty()) {
            Fx.say(this, "Tidak ada game terdeteksi, pilih manual", Fx.WARN)
            openPicker()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Tambahkan ${found.size} game?")
            .setMessage(found.joinToString("\n") { it.name })
            .setPositiveButton("Tambahkan") { _, _ -> found.forEach { GameRepo.add(it.pkg) }; build(false) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun gameMenu(g: GameApp) {
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf("Pintasan layar Awal", "Hapus dari daftar")) { _, which ->
                if (which == 0) {
                    val ok = GameUtils.pinShortcut(this, g.pkg, g.name, g.icon)
                    if (!ok) Fx.say(this, "Peluncur tidak mendukung pintasan", Fx.WARN)
                } else { GameRepo.remove(g.pkg); build(false) }
            }.show()
    }

    private fun renameDialog() {
        val et = EditText(this)
        et.setText(Prefs.userName)
        et.setTextColor(NX.TEXT)
        et.setSingleLine()
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Nama profil")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { Prefs.userName = n; build(false) }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun cleanRam() {
        Fx.say(this, "Mengosongkan RAM...")
        bg.execute {
            val r = Booster.cleanMemory(this, setOfNotNull(selected))
            handler.post {
                Fx.say(this, "${r.killed} aplikasi dihentikan, +${r.freedMb} MB", Fx.OK)
                updateLive()
            }
        }
    }

    private fun startGame(pkg: String) {
        if (!Perms.overlay(this)) {
            AlertDialog.Builder(this)
                .setTitle("Izin overlay diperlukan")
                .setMessage("Nexus perlu tampil di atas game untuk menampilkan panel Asisten Game.")
                .setPositiveButton("Buka pengaturan") { _, _ -> Perms.openOverlay(this) }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        Prefs.lastGame = pkg
        GameRepo.bump(pkg)
        val begin = {
            OverlayService.start(this, pkg)
            if (!GameUtils.launch(this, pkg)) Fx.say(this, "Game tidak bisa dibuka", Fx.WARN)
            else if (Prefs.openAnimation) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            Unit
        }
        if (OverlayService.running && OverlayService.currentPkg != pkg) {
            OverlayService.stop(this)
            handler.postDelayed({ begin() }, 500)
        } else begin()
    }
}
