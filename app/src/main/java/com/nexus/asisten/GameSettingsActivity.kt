package com.nexus.asisten

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSeekBar
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.overlay.OverlayService
import com.nexus.asisten.ui.*

/** "Performa dalam bermain game saya": Performa, Layar, Kontrol, Pengaturan fokus. */
class GameSettingsActivity : AppCompatActivity() {

    companion object { const val EXTRA_PKG = "pkg" }

    private lateinit var pkg: String
    private lateinit var profile: GameProfile
    private lateinit var body: FrameLayout
    private lateinit var tabHolder: FrameLayout
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: Prefs.lastGame ?: run { finish(); return }
        profile = Prefs.profile(pkg)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = screenBg()
        val head = hbox()
        head.setPadding(dp(8), dp(10), dp(16), dp(10))
        val back = icon(R.drawable.ic_back, NX.TEXT, 24)
        back.setPadding(dp(8), dp(8), dp(8), dp(8))
        back.setOnClickListener { finish() }
        head.add(back, dp(40), dp(40), 0f, 0, 0, 6, 0)
        val t = vbox()
        t.addView(tv("Performa dalam bermain game", 18f, NX.TEXT, 700))
        t.add(tv(GameRepo.label(this, pkg), 12.5f, NX.TEXT2), t = 2)
        head.add(t, 0, WRAP, 1f)
        GameRepo.icon(this, pkg)?.let { head.add(appIcon(it, 36, 10), dp(36), dp(36)) }
        root.addView(head)
        tabHolder = FrameLayout(this)
        root.addView(tabHolder)
        body = FrameLayout(this)
        root.add(body, MATCH, 0, 1f)
        setContentView(root)
        render()
    }

    private fun render() {
        tabHolder.removeAllViews()
        tabHolder.addView(tabBar(listOf("Performa", "Layar", "Kontrol", "Fokus"), tab) { tab = it; render() })
        body.removeAllViews()
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox(16, 14)
        sv.addView(col)
        when (tab) {
            0 -> tabPerforma(col)
            1 -> tabLayar(col)
            2 -> tabKontrol(col)
            else -> tabFokus(col)
        }
        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun save(p: GameProfile) {
        profile = p
        Prefs.saveProfile(pkg, p)
        if (OverlayService.running && OverlayService.currentPkg == pkg) OverlayService.reapply(this)
    }

    // ---------- Performa ----------
    private fun tabPerforma(col: LinearLayout) {
        val b = SystemStats.battery(this)
        val ring = PerfRingView(this)
        val prof = when (profile.mode) {
            GameProfile.MODE_SAVER -> floatArrayOf(0.5f, 0.4f, 0.55f)
            GameProfile.MODE_PRO -> floatArrayOf(1f, 1f, 0.9f)
            else -> floatArrayOf(0.7f, 0.7f, 0.75f)
        }
        val accent = listOf(NX.GOOD, NX.CYAN, NX.PRO)[profile.mode]
        ring.set((b.tempC - 25f) / 25f, b.pct / 100f, prof, accent)
        col.add(ring, MATCH, dp(190), 0f, 0, 0, 0, 12)

        val modes = listOf(
            Triple("Mode hemat daya", "Mengurangi konsumsi daya untuk memperpanjang masa pakai baterai.", NX.GOOD),
            Triple("Mode seimbang", "Konsumsi daya dan performa yang seimbang secara cerdas.", NX.CYAN),
            Triple("Mode Gamer Pro", "Meningkatkan performa untuk pengalaman bermain game profesional. Konsumsi daya dan suhu perangkat akan naik.", NX.PRO)
        )
        modes.forEachIndexed { i, (title, desc, color) ->
            val on = profile.mode == i
            val c = vbox(18, 16)
            c.background = rounded(if (on) NX.SURFACE2 else NX.SURFACE, dpf(20f), if (on) color else NX.LINE, if (on) dp(2) else 1)
            c.addView(tv(title, 16f, if (on) color else NX.TEXT, 700))
            c.add(tv(desc, 13.5f, NX.TEXT2), t = 4)
            c.setOnClickListener { save(profile.copy(mode = i)); render(); Fx.say(this, title + " diterapkan", Fx.OK) }
            c.pressFx(0.97f)
            col.add(c, t = 10)
        }
        col.add(tv("Jika suhu melewati ${Prefs.tempWarn}°C, Gamer Pro turun ke Seimbang sementara (bisa dimatikan di Pengaturan).", 12f, NX.MUTED), t = 12)
    }

    // ---------- Layar ----------
    private fun tabLayar(col: LinearLayout) {
        val c = card(20)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.addView(tv("Tingkat penyegaran layar", 16f, NX.TEXT, 700))
        c.add(tv("Dapat meningkatkan konsumsi daya dan suhu perangkat Anda.", 13f, NX.TEXT2), t = 4, b = 14)
        val opts = Booster.supportedRefresh(this)
        val labels = opts.mapIndexed { i, hz ->
            when {
                i == 0 && opts.size > 1 -> "$hz Hz\nHemat daya"
                i == opts.size - 1 && opts.size > 1 -> "$hz Hz\nLebih halus"
                else -> "$hz Hz"
            }
        }
        val sel = if (profile.refreshHz == 0) opts.size - 1 else opts.indexOfFirst { it == profile.refreshHz }.coerceAtLeast(0)
        c.addView(chipRow(labels, sel) { save(profile.copy(refreshHz = opts[it])) })
        if (!Perms.writeSettings(this)) {
            c.add(tv("Izin \"Ubah pengaturan sistem\" belum aktif, jadi pilihan ini belum bisa diterapkan.", 12.5f, NX.WARN), t = 14)
            c.add(ghostButton("Beri izin") { Perms.openWrite(this) }, t = 8)
        } else {
            c.add(tv("Diterapkan saat game dibuka lalu dipulihkan setelah selesai. Sebagian ROM mengabaikan pengaturan ini.", 12.5f, NX.MUTED), t = 14)
        }
        col.addView(c)
    }

    // ---------- Kontrol ----------
    private fun tabKontrol(col: LinearLayout) {
        val c = card(20)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.addView(tv("Laju pengambilan sampel sentuhan", 16f, NX.TEXT, 700))
        c.add(tv("Dapat meningkatkan konsumsi daya dan suhu perangkat Anda.", 13f, NX.TEXT2), t = 4, b = 14)
        c.addView(chipRow(listOf("Cerdas", "Sentuhan ultra"), if (profile.ultraTouch) 1 else 0) {
            save(profile.copy(ultraTouch = it == 1))
        })
        col.addView(c)

        val s = card(20)
        s.setPadding(dp(18), dp(16), dp(18), dp(16))
        s.addView(tv("Sensitivitas", 16f, NX.TEXT, 700))
        s.add(tv("Presisi sentuhan", 14f, NX.TEXT, 500), t = 14)
        s.addView(stepSlider(profile.touchPrecision) { save(profile.copy(touchPrecision = it)) })
        s.add(tv("Sensitivitas usapan", 14f, NX.TEXT, 500), t = 14)
        s.addView(stepSlider(profile.swipeSens) { save(profile.copy(swipeSens = it)) })
        col.add(s, t = 12)
        col.add(tv("Nilai ini disimpan di profil game. Pengaruhnya pada sentuhan bergantung pada dukungan perangkat/ROM, Android tidak mengizinkan aplikasi biasa mengubah sensor sentuhan langsung.", 12f, NX.MUTED), t = 12)
    }

    private fun stepSlider(value: Int, onChange: (Int) -> Unit): View {
        val box = vbox()
        val labels = hbox()
        listOf("-2", "-1", "Default", "1", "2").forEachIndexed { i, s ->
            val t = tv(s, 12f, NX.TEXT2)
            t.gravity = if (i == 0) android.view.Gravity.START else if (i == 4) android.view.Gravity.END else android.view.Gravity.CENTER
            labels.add(t, 0, WRAP, 1f)
        }
        box.add(labels, t = 8)
        val sb = AppCompatSeekBar(this)
        sb.max = 4
        sb.progress = value + 2
        sb.progressTintList = ColorStateList.valueOf(NX.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p - 2) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.add(sb, MATCH, WRAP, 0f, 0, 4, 0, 0)
        return box
    }

    // ---------- Fokus ----------
    private fun tabFokus(col: LinearLayout) {
        val c = card(20)
        c.addView(switchRow("Pemblokir panggilan", "Secara otomatis memblokir panggilan masuk dan notifikasi.", profile.blockCalls) {
            if (it && !Perms.dnd(this)) askDnd()
            save(profile.copy(blockCalls = it))
        })
        c.add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)
        c.addView(switchRow("Blokir notifikasi", "Notifikasi tidak akan muncul lagi saat Anda bermain game.", profile.blockNotif) {
            if (it && !Perms.dnd(this)) askDnd()
            save(profile.copy(blockNotif = it))
        })
        col.addView(c)
        col.add(tv("Memakai mode Jangan Ganggu (alarm tetap berbunyi). Pengaturan lama dipulihkan setelah game ditutup.", 12f, NX.MUTED), t = 12)
    }

    private fun askDnd() {
        AlertDialog.Builder(this)
            .setTitle("Izin Jangan Ganggu")
            .setMessage("Nexus perlu akses Jangan Ganggu untuk memblokir panggilan dan notifikasi saat bermain.")
            .setPositiveButton("Buka pengaturan") { _, _ -> Perms.openDnd(this) }
            .setNegativeButton("Nanti", null)
            .show()
    }
}
