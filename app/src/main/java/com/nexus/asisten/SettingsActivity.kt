package com.nexus.asisten

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.nexus.asisten.core.Perms
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*

/** Pengaturan lainnya: Pengaturan & Tentang Asisten Game. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var body: FrameLayout
    private lateinit var tabHolder: FrameLayout
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(NX.BG)
        root.addView(topBar("Pengaturan lainnya", { finish() }))
        tabHolder = FrameLayout(this)
        root.addView(tabHolder)
        body = FrameLayout(this)
        root.add(body, MATCH, 0, 1f)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        tabHolder.removeAllViews()
        tabHolder.addView(tabBar(listOf("Pengaturan", "Tentang Asisten Game"), tab) { tab = it; render() })
        body.removeAllViews()
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox(16, 14)
        sv.addView(col)
        if (tab == 0) settings(col) else about(col)
        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun group(col: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        col.add(sectionTitle(title), t = 16, b = 8)
        val c = card(20)
        build(c)
        col.addView(c)
    }

    private fun LinearLayout.sep() = add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)

    private fun settings(col: LinearLayout) {
        group(col, "Asisten Game di layar Awal") { c ->
            c.addView(switchRow("Buka animasi", "Tampilkan animasi saat Anda meluncurkan game dari Nexus.", Prefs.openAnimation) { Prefs.openAnimation = it })
            c.sep()
            c.addView(switchRow("Album game", "Tampilkan Album game di beranda.", Prefs.albumEnabled) { Prefs.albumEnabled = it })
            c.sep()
            c.addView(switchRow("Mulai cepat", "Bersihkan RAM otomatis saat game dibuka dengan Gamer Pro.", Prefs.quickStart) { Prefs.quickStart = it })
        }
        group(col, "Suhu") { c ->
            c.addView(switchRow("Atur otomatis saat panas", "Turunkan Gamer Pro ke Seimbang sementara ketika perangkat terlalu panas.", Prefs.autoAdjust) { Prefs.autoAdjust = it })
            val temps = listOf(40, 43, 46)
            val row = vbox(16, 4)
            row.addView(sectionTitle("Batas suhu"))
            row.add(chipRow(temps.map { "$it°C" }, temps.indexOf(Prefs.tempWarn).coerceAtLeast(1)) { Prefs.tempWarn = temps[it] }, t = 8, b = 12)
            c.addView(row)
        }
        group(col, "Perekaman layar") { c ->
            c.addView(switchRow("Rekam suara mikrofon", "Butuh izin mikrofon. Suara game tidak ikut terekam.", Prefs.recordMic) {
                Prefs.recordMic = it
                if (it && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            })
            c.sep()
            c.addView(switchRow("Kualitas tinggi", "Hingga 1080p dengan bitrate lebih besar.", Prefs.recordHd) { Prefs.recordHd = it })
            c.sep()
            c.addView(switchRow("60 fps", "Lebih halus, file lebih besar.", Prefs.recordFps60) { Prefs.recordFps60 = it })
        }
        group(col, "Izin") { c ->
            val rows = listOf(
                Triple("Tampil di atas aplikasi lain", Perms.overlay(this), { Perms.openOverlay(this) }),
                Triple("Akses penggunaan", Perms.usage(this), { Perms.openUsage(this) }),
                Triple("Ubah pengaturan sistem", Perms.writeSettings(this), { Perms.openWrite(this) }),
                Triple("Jangan Ganggu", Perms.dnd(this), { Perms.openDnd(this) }),
                Triple("Akses notifikasi", Perms.notifListener(this), { Perms.openNotifListener(this) })
            )
            rows.forEachIndexed { i, (name, ok, act) ->
                if (i > 0) c.sep()
                val r = hbox()
                r.setPadding(dp(16), dp(14), dp(16), dp(14))
                r.add(tv(name, 15f, NX.TEXT, 500), 0, WRAP, 1f)
                r.addView(tv(if (ok) "Aktif" else "Izinkan", 13f, if (ok) NX.GOOD else NX.ACCENT, 700))
                r.setOnClickListener { act() }
                c.addView(r)
            }
        }
    }

    private fun about(col: LinearLayout) {
        val c = card(20)
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.addView(tv("Nexus Asisten", 20f, NX.TEXT, 700))
        c.add(tv("Versi 2.0.0", 13f, NX.TEXT2), t = 2, b = 14)
        c.addView(tv("Asisten Game ringan untuk HP: panel overlay, profil performa per game, perekaman, dan statistik waktu bermain.", 14f, NX.TEXT2))
        col.addView(c)

        col.add(sectionTitle("Apa yang nyata dan apa yang terbatas"), t = 18, b = 8)
        val n = card(20)
        n.setPadding(dp(18), dp(16), dp(18), dp(16))
        n.addView(tv(
            "Nyata: suhu & baterai, latensi (ping TCP), pengosongan RAM, cuplikan & rekaman layar, Jangan Ganggu, " +
                "pencegahan salah sentuh, bantuan bidik, kecerahan, volume, waktu bermain.\n\n" +
                "FPS: akurat jika izin DUMP diberikan lewat ADB:\nadb shell pm grant com.nexus.asisten android.permission.DUMP\n" +
                "Tanpa itu angka FPS berupa estimasi (diberi tanda ~).\n\n" +
                "Terbatas: laju refresh dan sampling sentuhan bergantung pada ROM. Android tidak mengizinkan aplikasi biasa " +
                "mengubah sensor sentuhan atau CPU/GPU tanpa root.",
            13.5f, NX.TEXT2
        ))
        col.addView(n)
    }
}
