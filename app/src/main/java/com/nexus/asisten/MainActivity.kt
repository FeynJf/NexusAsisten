package com.nexus.asisten

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.GameUtils
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.SystemStats
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(NX.BG)
        scroll.isVerticalScrollBarEnabled = false
        root = vbox(20, 12)
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
        build()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 2000)
        }
    }

    private fun updateLive() {
        val b = SystemStats.battery(this)
        val (free, total) = SystemStats.ramMb(this)
        val g = games.firstOrNull { it.pkg == selected }
        val mode = if (g != null) Prefs.profile(g.pkg).mode else GameProfile.MODE_BALANCED
        ring?.set(((b.tempC - 25f) / 25f), b.pct / 100f, profileFor(mode), accentFor(mode))
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
    private fun build() {
        root.removeAllViews()
        games = GameRepo.savedGames(this)
        if (selected == null || games.none { it.pkg == selected }) {
            selected = games.firstOrNull { it.pkg == Prefs.lastGame }?.pkg ?: games.firstOrNull()?.pkg
        }
        buildTopBar()
        if (games.isEmpty()) buildEmpty() else {
            buildHero()
            buildCarousel()
        }
        buildDeviceCard()
        buildPlaytime()
        buildShortcuts()
        buildPermissions()
        root.add(View(this), MATCH, dp(24))
        updateLive()
    }

    private fun buildTopBar() {
        val bar = hbox()
        val avatar = tv(Prefs.userName.take(1).uppercase(), 18f, android.graphics.Color.WHITE, 700)
        avatar.gravity = Gravity.CENTER
        avatar.background = gradientRounded(NX.ACCENT, NX.CYAN, dpf(22f))
        bar.add(avatar, dp(44), dp(44), 0f, 0, 0, 12, 0)
        val t = vbox()
        t.addView(tv("Halo, ${Prefs.userName}", 13f, NX.TEXT2))
        t.add(tv("Nexus Asisten", 22f, NX.TEXT, 700), t = 1)
        bar.add(t, 0, WRAP, 1f)
        val add = icon(R.drawable.ic_add, NX.TEXT, 24)
        add.setPadding(dp(8), dp(8), dp(8), dp(8))
        add.setOnClickListener { openPicker() }
        bar.add(add, dp(40), dp(40))
        val set = icon(R.drawable.ic_settings, NX.TEXT, 24)
        set.setPadding(dp(8), dp(8), dp(8), dp(8))
        set.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bar.add(set, dp(40), dp(40))
        root.add(bar, b = 16)
    }

    private fun buildEmpty() {
        val c = card(24)
        c.setPadding(dp(20), dp(24), dp(20), dp(20))
        c.addView(tv("Belum ada game", 20f, NX.TEXT, 700))
        c.add(tv("Tambahkan game agar Nexus bisa menyiapkan performa, overlay, dan statistik waktu bermain.", 14f, NX.TEXT2), t = 8, b = 18)
        c.addView(gradientButton("Deteksi game otomatis") { autoDetect() })
        c.add(ghostButton("Pilih manual") { openPicker() }, t = 10)
        root.add(c, b = 16)
    }

    private fun buildHero() {
        val g = games.first { it.pkg == selected }
        val prof = Prefs.profile(g.pkg)
        val hero = vbox(20, 20)
        hero.background = gradientRounded(
            android.graphics.Color.parseColor("#3B2FC9"), android.graphics.Color.parseColor("#141B3D"), dpf(28f),
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR
        )
        val top = hbox()
        top.add(appIcon(g.icon, 72, 20), dp(72), dp(72), 0f, 0, 0, 16, 0)
        val info = vbox()
        info.addView(tv(g.name, 21f, NX.TEXT, 700).also { it.maxLines = 2 })
        val m30 = PlayStats.last30(this, listOf(g.pkg))[g.pkg] ?: 0L
        info.add(tv("Dimainkan ${fmtDuration(m30)} dalam 30 hari terakhir", 13f, 0xCCEAF0FF.toInt()), t = 4)
        top.add(info, 0, WRAP, 1f)
        hero.addView(top)

        val modeName = listOf("Hemat daya", "Seimbang", "Gamer Pro")[prof.mode]
        val chips = hbox()
        val tag = tv(modeName, 12f, android.graphics.Color.WHITE, 500)
        tag.setPadding(dp(10), dp(5), dp(10), dp(5))
        tag.background = rounded(0x33FFFFFF, dpf(10f))
        chips.addView(tag)
        if (OverlayService.running && OverlayService.currentPkg == g.pkg) {
            val live = tv("Asisten aktif", 12f, NX.GOOD, 500)
            live.setPadding(dp(10), dp(5), dp(10), dp(5))
            chips.add(live, WRAP, WRAP, 0f, 8, 0, 0, 0)
        }
        hero.add(chips, t = 16)

        val row = hbox()
        row.add(gradientButton("Mulai") { startGame(g.pkg) }.also {
            it.background = rounded(android.graphics.Color.WHITE, dpf(16f))
            it.setTextColor(android.graphics.Color.parseColor("#2A1FA8"))
        }, 0, WRAP, 1.4f, 0, 0, 10, 0)
        row.add(ghostButton("Performa") {
            startActivity(Intent(this, GameSettingsActivity::class.java).putExtra(GameSettingsActivity.EXTRA_PKG, g.pkg))
        }, 0, WRAP, 1f)
        hero.add(row, t = 16)
        root.add(hero, b = 14)
    }

    private fun buildCarousel() {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        val row = hbox()
        games.forEach { g ->
            val on = g.pkg == selected
            val cell = FrameLayout(this)
            cell.setPadding(dp(4), dp(4), dp(4), dp(4))
            cell.background = if (on) rounded(0, dpf(22f), NX.ACCENT, dp(2)) else null
            cell.addView(appIcon(g.icon, 56, 16), FrameLayout.LayoutParams(dp(56), dp(56)))
            cell.setOnClickListener { selected = g.pkg; Prefs.lastGame = g.pkg; build() }
            cell.setOnLongClickListener { gameMenu(g); true }
            row.add(cell, WRAP, WRAP, 0f, 0, 0, 8, 0)
        }
        val plus = FrameLayout(this)
        plus.background = rounded(NX.SURFACE, dpf(16f), NX.LINE, 1)
        plus.addView(icon(R.drawable.ic_add, NX.TEXT2, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        plus.setOnClickListener { openPicker() }
        row.add(plus, dp(56), dp(56), 0f, 4, 4, 0, 4)
        hs.addView(row)
        root.add(hs, b = 18)
    }

    private fun buildDeviceCard() {
        val c = card(24)
        c.setPadding(dp(16), dp(16), dp(16), dp(16))
        val row = hbox()
        val r = PerfRingView(this)
        ring = r
        row.add(r, dp(180), dp(170))
        val info = vbox()
        info.addView(tv("Perangkat sekarang", 13f, NX.TEXT2, 500))
        val b = tv("--", 18f, NX.TEXT, 700); tvBat = b
        info.add(tv("Baterai", 12f, NX.MUTED), t = 12)
        info.addView(b)
        val tp = tv("--", 18f, NX.TEXT, 700); tvTemp = tp
        info.add(tv("Suhu baterai", 12f, NX.MUTED), t = 10)
        info.addView(tp)
        val rm = tv("--", 13f, NX.TEXT, 500); tvRam = rm
        info.add(tv("Memori", 12f, NX.MUTED), t = 10)
        info.addView(rm)
        row.add(info, 0, WRAP, 1f, 8, 0, 0, 0)
        c.addView(row)
        c.add(ghostButton("Kosongkan RAM") { cleanRam() }, t = 8)
        root.add(c, b = 14)
    }

    private fun buildPlaytime() {
        val c = card(24)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        val pkgs = games.map { it.pkg }
        val totals = PlayStats.total(this, pkgs)
        val sum = totals.values.sum()
        val head = hbox()
        head.add(tv("Waktu dimainkan", 15f, NX.TEXT, 700), 0, WRAP, 1f)
        head.addView(tv(fmtDuration(sum), 15f, NX.CYAN, 700))
        c.addView(head)
        val top = games.sortedByDescending { totals[it.pkg] ?: 0L }.take(3)
        val max = (top.firstOrNull()?.let { totals[it.pkg] } ?: 0L).coerceAtLeast(1L)
        top.forEach { g ->
            val ms = totals[g.pkg] ?: 0L
            val r = hbox()
            r.add(appIcon(g.icon, 34, 10), dp(34), dp(34), 0f, 0, 0, 12, 0)
            val m = vbox()
            val line = hbox()
            line.add(tv(g.name, 14f, NX.TEXT, 500).also { it.maxLines = 1 }, 0, WRAP, 1f)
            line.addView(tv(fmtDuration(ms), 13f, NX.TEXT2))
            m.addView(line)
            val track = FrameLayout(this)
            track.background = rounded(NX.SURFACE2, dpf(3f))
            val bar = View(this)
            bar.background = gradientRounded(NX.ACCENT, NX.CYAN, dpf(3f))
            track.addView(bar, FrameLayout.LayoutParams(0, MATCH))
            track.post { bar.layoutParams = FrameLayout.LayoutParams((track.width * ms / max).toInt(), MATCH) }
            m.add(track, MATCH, dp(5), 0f, 0, 8, 0, 0)
            r.add(m, 0, WRAP, 1f)
            c.add(r, t = 14)
        }
        if (games.isEmpty()) c.add(tv("Belum ada data.", 13f, NX.MUTED), t = 8)
        if (!Perms.usage(this)) {
            c.add(tv("Aktifkan Akses penggunaan agar waktu bermain akurat dan overlay tahu kapan game ditutup.", 12.5f, NX.MUTED), t = 14)
            c.add(ghostButton("Aktifkan akses penggunaan") { Perms.openUsage(this) }, t = 8)
        }
        root.add(c, b = 14)
    }

    private fun buildShortcuts() {
        val c = card(24)
        if (Prefs.albumEnabled) {
            c.addView(linkRow("Album game", "Cuplikan dan rekaman layar", R.drawable.ic_photo) {
                startActivity(Intent(this, AlbumActivity::class.java))
            })
            c.add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)
        }
        c.addView(linkRow("Kelola game", "Tambah atau hapus dari daftar", R.drawable.ic_gamepad) { openPicker() })
        c.add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)
        c.addView(linkRow("Ubah nama profil", Prefs.userName, R.drawable.ic_person) { renameDialog() })
        c.add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)
        c.addView(linkRow("Pengaturan lainnya", "Perekaman, izin, tentang", R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.add(c, b = 14)
    }

    private fun buildPermissions() {
        data class P(val name: String, val ok: Boolean, val act: () -> Unit)
        val list = listOf(
            P("Tampil di atas aplikasi lain (wajib)", Perms.overlay(this)) { Perms.openOverlay(this) },
            P("Akses penggunaan", Perms.usage(this)) { Perms.openUsage(this) },
            P("Ubah pengaturan sistem", Perms.writeSettings(this)) { Perms.openWrite(this) },
            P("Jangan Ganggu", Perms.dnd(this)) { Perms.openDnd(this) },
            P("Akses notifikasi", Perms.notifListener(this)) { Perms.openNotifListener(this) }
        ).filter { !it.ok }
        if (list.isEmpty()) return
        val c = card(24)
        c.setPadding(dp(18), dp(14), dp(18), dp(8))
        c.addView(tv("Izin yang belum aktif", 15f, NX.TEXT, 700))
        list.forEach { p ->
            val r = hbox()
            r.setPadding(0, dp(10), 0, dp(10))
            val dot = View(this)
            dot.background = circle(NX.WARN)
            r.add(dot, dp(8), dp(8), 0f, 0, 0, 12, 0)
            r.add(tv(p.name, 14f, NX.TEXT2), 0, WRAP, 1f)
            r.addView(tv("Izinkan", 13f, NX.ACCENT, 700))
            r.setOnClickListener { p.act() }
            c.addView(r)
        }
        root.add(c)
    }

    // ---------- Aksi ----------
    private fun openPicker() {
        startActivity(Intent(this, GamePickerActivity::class.java).putExtra(GamePickerActivity.EXTRA_MODE, GamePickerActivity.MODE_GAMES))
    }

    private fun autoDetect() {
        val found = GameRepo.launchableApps(this).filter { it.isGame }
        if (found.isEmpty()) {
            Toast.makeText(this, "Tidak ada game terdeteksi, pilih manual", Toast.LENGTH_SHORT).show()
            openPicker()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Tambahkan ${found.size} game?")
            .setMessage(found.joinToString("\n") { it.name })
            .setPositiveButton("Tambahkan") { _, _ -> found.forEach { GameRepo.add(it.pkg) }; build() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun gameMenu(g: GameApp) {
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf("Pintasan layar Awal", "Hapus dari daftar")) { _, which ->
                if (which == 0) {
                    val ok = GameUtils.pinShortcut(this, g.pkg, g.name, g.icon)
                    if (!ok) Toast.makeText(this, "Peluncur tidak mendukung pintasan", Toast.LENGTH_SHORT).show()
                } else { GameRepo.remove(g.pkg); build() }
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
                if (n.isNotEmpty()) { Prefs.userName = n; build() }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun cleanRam() {
        Toast.makeText(this, "Mengosongkan RAM...", Toast.LENGTH_SHORT).show()
        bg.execute {
            val r = Booster.cleanMemory(this, setOfNotNull(selected))
            handler.post {
                Toast.makeText(this, "${r.killed} aplikasi dihentikan, +${r.freedMb} MB", Toast.LENGTH_LONG).show()
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
            if (!GameUtils.launch(this, pkg)) Toast.makeText(this, "Game tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            else if (Prefs.openAnimation) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            Unit
        }
        if (OverlayService.running && OverlayService.currentPkg != pkg) {
            OverlayService.stop(this)
            handler.postDelayed({ begin() }, 500)
        } else begin()
    }
}
