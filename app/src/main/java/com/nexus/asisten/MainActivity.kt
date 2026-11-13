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
    private var ring: PerfRingView? = null
    private var tvBat: TextView? = null
    private var tvTemp: TextView? = null
    private var tvRam: TextView? = null
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
        val g = games.firstOrNull { it.pkg == selected }
        val mode = if (g != null) Prefs.profile(g.pkg).mode else GameProfile.MODE_BALANCED
        val heat = (b.tempC - 25f) / 25f
        val bat = b.pct / 100f
        if (ringIntro) {
            ringIntro = false
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    ring?.set(heat * t, bat * t, profileFor(mode), accentFor(mode))
                }
                start()
            }
        } else ring?.set(heat, bat, profileFor(mode), accentFor(mode))
        tvBat?.text = "${b.pct}%" + if (b.charging) " · mengisi" else ""
        tvTemp?.text = String.format("%.1f °C", b.tempC)
        tvRam?.text = "$free MB bebas dari $total MB"
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
            if (on) { cell.glow(NX.CYAN, 10f); cell.scaleX = 1.06f; cell.scaleY = 1.06f }
            cell.addView(appIcon(g.icon, 58, 18), FrameLayout.LayoutParams(dp(58), dp(58)))
            cell.setOnClickListener { selected = g.pkg; Prefs.lastGame = g.pkg; build(false); Fx.say(this, "${g.name} dipilih") }
            cell.pressFx(0.9f)
            cell.setOnLongClickListener { gameMenu(g); true }
            row.add(cell, WRAP, WRAP, 0f, 0, 0, 10, 0)
        }
        hs.addView(row)
        root.add(hs, b = 6)
    }

    private fun buildHero() {
        val g = games.first { it.pkg == selected }
        val prof = Prefs.profile(g.pkg)
        val hero = vbox(20, 20)
        hero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#5B34F5"), Color.parseColor("#2A1A7A"), Color.parseColor("#120F36"), Color.parseColor("#06070F"))
        ).also { it.cornerRadius = dpf(30f); it.setStroke(dp(1), withAlpha(NX.CYAN, 0x66)) }
        hero.glow(NX.ACCENT, 18f)
        val top = hbox()
        val ic = appIcon(g.icon, 76, 22)
        ic.glow(NX.CYAN, 12f)
        top.add(ic, dp(76), dp(76), 0f, 0, 0, 16, 0)
        val info = vbox()
        info.addView(tv(g.name, 22f, NX.TEXT, 700).also { it.maxLines = 2 })
        val m30 = PlayStats.last30(this, listOf(g.pkg))[g.pkg] ?: 0L
        info.add(tv("Dimainkan ${fmtDuration(m30)} dalam 30 hari terakhir", 12.5f, 0xCCEAF0FF.toInt()), t = 4)
        top.add(info, 0, WRAP, 1f)
        hero.addView(top)

        val modeName = listOf("Hemat daya", "Seimbang", "Gamer Pro")[prof.mode]
        val chips = hbox()
        val tag = tv(modeName, 11.5f, Color.WHITE, 700)
        tag.isAllCaps = true
        tag.setPadding(dp(12), dp(6), dp(12), dp(6))
        tag.background = rounded(withAlpha(Color.WHITE, 0x26), dpf(12f), withAlpha(Color.WHITE, 0x44), 1)
        chips.addView(tag)
        if (OverlayService.running && OverlayService.currentPkg == g.pkg) {
            val live = tv("● Asisten aktif", 11.5f, NX.GOOD, 700)
            live.setPadding(dp(12), dp(6), dp(12), dp(6))
            chips.add(live, WRAP, WRAP, 0f, 8, 0, 0, 0)
        }
        hero.add(chips, t = 16)

        val row = hbox()
        row.add(gradientButton("Mulai") { startGame(g.pkg) }, 0, WRAP, 1.4f, 0, 0, 10, 0)
        row.add(ghostButton("Performa") {
            startActivity(Intent(this, GameSettingsActivity::class.java).putExtra(GameSettingsActivity.EXTRA_PKG, g.pkg))
        }, 0, WRAP, 1f)
        hero.add(row, t = 18)
        root.add(hero, b = 18)
    }

    private fun buildDeviceCard() {
        val c = card(26)
        c.setPadding(dp(16), dp(16), dp(16), dp(16))
        c.addView(sectionTitle("Perangkat sekarang"))
        val row = hbox()
        val r = PerfRingView(this)
        ring = r
        row.add(r, dp(168), dp(160), 0f, 0, 8, 0, 0)
        val info = vbox()
        val b = tv("--", 22f, NX.TEXT, 700); tvBat = b
        info.add(tv("BATERAI", 10.5f, NX.MUTED, 700).also { it.letterSpacing = 0.12f }, t = 4)
        info.addView(b)
        val tp = tv("--", 22f, NX.TEXT, 700); tvTemp = tp
        info.add(tv("SUHU", 10.5f, NX.MUTED, 700).also { it.letterSpacing = 0.12f }, t = 10)
        info.addView(tp)
        val rm = tv("--", 13f, NX.TEXT, 500); tvRam = rm
        info.add(tv("MEMORI", 10.5f, NX.MUTED, 700).also { it.letterSpacing = 0.12f }, t = 10)
        info.addView(rm)
        row.add(info, 0, WRAP, 1f, 8, 0, 0, 0)
        c.addView(row)
        c.add(ghostButton("Kosongkan RAM") { cleanRam() }, t = 8)
        root.add(c, b = 18)
    }

    private fun quickTile(iconRes: Int, title: String, sub: String, onClick: () -> Unit): View {
        val v = vbox(14, 14)
        v.background = NeonDrawable(Color.parseColor("#F0111729"), Color.parseColor("#F00A0D17"), dpf(22f), strokePx = dpf(1.2f))
        val badge = FrameLayout(this)
        badge.background = gradientRounded(withAlpha(NX.ACCENT, 0x55), withAlpha(NX.BLUE, 0x33), dpf(14f), GradientDrawable.Orientation.TL_BR)
            .also { it.setStroke(1, withAlpha(NX.ACCENT, 0x88)) }
        badge.addView(icon(iconRes, NX.CYAN, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
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
        if (Prefs.albumEnabled) tiles.add(quickTile(R.drawable.ic_photo, "Album", "Cuplikan & rekaman") {
            startActivity(Intent(this, AlbumActivity::class.java))
        })
        tiles.add(quickTile(R.drawable.ic_gamepad, "Kelola game", "Tambah atau hapus") { openPicker() })
        tiles.add(quickTile(R.drawable.ic_monitor, "Aplikasi overlay", "Dibuka jendela mengambang") {
            startActivity(Intent(this, GamePickerActivity::class.java).putExtra(GamePickerActivity.EXTRA_MODE, GamePickerActivity.MODE_DOCK))
        })
        tiles.add(quickTile(R.drawable.ic_person, "Profil", Prefs.userName) { renameDialog() })
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
