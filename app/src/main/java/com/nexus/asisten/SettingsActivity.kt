package com.nexus.asisten

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.nexus.asisten.core.GameConflict
import com.nexus.asisten.core.GameUtils
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.Updater
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*

/** Pengaturan lainnya: Pengaturan, Izin & perbaikan, dan Tentang. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var body: FrameLayout
    private lateinit var tabHolder: FrameLayout
    private var tab = 0
    private var lastOk: Map<String, Boolean> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = screenBg()
        root.addView(topBar("Pengaturan lainnya", { finish() }))
        tabHolder = FrameLayout(this)
        root.addView(tabHolder)
        body = FrameLayout(this)
        root.add(body, MATCH, 0, 1f)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Beri tahu bila ada izin yang baru saja berhasil diaktifkan.
        val now = permItems().associate { it.id to it.ok }
        permItems().forEach {
            if (it.ok && lastOk[it.id] == false) Fx.say(this, "${it.name} aktif", Fx.OK)
        }
        lastOk = now
        render()
    }

    private fun render() {
        tabHolder.removeAllViews()
        tabHolder.addView(tabBar(listOf("Pengaturan", "Izin", "Tentang"), tab) { tab = it; render() })
        body.removeAllViews()
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox(16, 14)
        sv.addView(col)
        when (tab) {
            0 -> settings(col)
            1 -> permissions(col)
            else -> about(col)
        }
        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        Fx.stagger(col, 0L, 45L)
    }

    private fun group(col: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        col.add(sectionTitle(title), t = 18, b = 8)
        val c = card(22)
        build(c)
        col.addView(c)
    }

    private fun LinearLayout.sep() = add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)

    private fun settings(col: LinearLayout) {
        col.add(sectionTitle("Pembaruan"), t = 4, b = 8)
        col.addView(updateCard())
        val auto = card(22)
        auto.addView(switchRow("Cek pembaruan otomatis", "Memeriksa rilis baru di GitHub saat aplikasi dibuka.", Prefs.autoUpdate) { Prefs.autoUpdate = it })
        col.add(auto, t = 10)

        group(col, "Performa & jaringan") { c ->
            c.addView(switchRow("Kunci Wi-Fi latensi rendah", "Menahan Wi-Fi tetap responsif selama bermain agar ping lebih stabil.", Prefs.wifiLock) { Prefs.wifiLock = it })
            c.sep()
            val dns = vbox(16, 10)
            dns.addView(tv("DNS pribadi saat bermain", 15f, NX.TEXT, 500))
            dns.add(tv("Bisa mempercepat pencarian server game. Dipulihkan setelah game ditutup.", 12.5f, NX.TEXT2), t = 3)
            dns.add(chipRow(listOf("Bawaan", "Google", "Cloudflare"), Prefs.dnsChoice) { Prefs.dnsChoice = it }, t = 10, b = 4)
            if (!Perms.secure(this)) {
                dns.add(tv("Perlu izin sekali lewat ADB agar pilihan ini bisa diterapkan.", 12.5f, NX.WARN), t = 6)
                dns.add(ghostButton("Lihat caranya") { permItems().firstOrNull { it.id == "secure" }?.let { handlePerm(it) } }, t = 8)
            }
            c.addView(dns)
        }

        group(col, "Jendela mengambang") { c ->
            c.addView(switchRow("Buka aplikasi overlay sebagai jendela", "Aplikasi dari tab Aplikasi di overlay dibuka mengambang di atas game.", Prefs.floatingWindow) { Prefs.floatingWindow = it })
            val box = vbox(16, 10)
            box.addView(tv("Ukuran jendela", 15f, NX.TEXT, 500))
            box.add(chipRow(listOf("Kecil", "Sedang", "Besar"), Prefs.floatSize) { Prefs.floatSize = it }, t = 10, b = 6)
            val ok = GameUtils.freeformSupported(this)
            box.add(tv(
                if (ok) "Mode jendela aktif di HP ini."
                else "Mode jendela belum aktif di HP ini, jadi aplikasi dibuka layar penuh. Aktifkan salah satu cara di bawah, lalu restart HP.",
                12.5f, if (ok) NX.GOOD else NX.WARN
            ), t = 8)
            if (!ok) {
                box.add(tv("Opsi Pengembang → \"Aktifkan jendela freeform\" dan \"Paksa aktivitas agar dapat diubah ukurannya\".", 12f, NX.TEXT2), t = 6)
                box.add(ghostButton("Salin perintah ADB") {
                    copyText("adb", "adb shell settings put global enable_freeform_support 1\nadb shell settings put global force_resizable_activities 1")
                }, t = 10)
                if (Perms.secure(this)) box.add(gradientButton("Aktifkan sekarang") {
                    Fx.say(this, if (GameUtils.enableFreeform(this)) "Aktif. Restart HP untuk menerapkan." else "Gagal mengaktifkan", Fx.OK)
                }, t = 8)
            }
            c.add(divider(), MATCH, 1, 0f, 16, 0, 16, 0)
            c.addView(box)
        }

        group(col, "Kompatibilitas") { c ->
            c.addView(switchRow(
                "Mode kompatibel",
                "Tidak mematikan asisten game lain saat membersihkan RAM, tidak menimpa Jangan Ganggu/refresh rate milik aplikasi lain, dan mengabaikan panelnya.",
                Prefs.compatMode
            ) { Prefs.compatMode = it })
            val others = GameConflict.installed(this)
            val info = vbox(16, 10)
            info.addView(tv(
                if (others.isEmpty()) "Tidak ada asisten game lain terdeteksi."
                else "Terdeteksi: " + others.joinToString(", "),
                12.5f, NX.TEXT2
            ))
            if (others.isNotEmpty()) info.add(tv("Gunakan hanya satu panel overlay pada satu waktu supaya tidak saling menutupi.", 12f, NX.MUTED), t = 4)
            c.sep()
            c.addView(info)
        }

        group(col, "Tampilan & umpan balik") { c ->
            c.addView(switchRow("Getar halus saat mengetuk", "Memberi sensasi tekan pada tombol dan kartu.", Prefs.haptic) { Prefs.haptic = it })
        }

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
                if (it && !Perms.mic(this)) permItems().firstOrNull { p -> p.id == "mic" }?.let { p -> handlePerm(p) }
            })
            c.sep()
            c.addView(switchRow("Kualitas tinggi", "Hingga 1080p dengan bitrate lebih besar.", Prefs.recordHd) { Prefs.recordHd = it })
            c.sep()
            c.addView(switchRow("60 fps", "Lebih halus, file lebih besar.", Prefs.recordFps60) { Prefs.recordFps60 = it })
        }
    }

    private fun permissions(col: LinearLayout) {
        val items = permItems()
        val okCount = items.count { it.ok }
        val head = card(22)
        head.setPadding(dp(18), dp(16), dp(18), dp(16))
        head.addView(tv("$okCount dari ${items.size} izin aktif", 17f, NX.TEXT, 700))
        head.add(tv("Ketuk izin yang belum aktif. Izin yang butuh ADB atau \"setelan terbatas\" akan dipandu langkah demi langkah.", 13f, NX.TEXT2), t = 4)
        val next = items.firstOrNull { !it.ok && it.adb == null }
        if (next != null) head.add(gradientButton("Perbaiki: ${next.name}") { handlePerm(next) }, t = 14)
        col.addView(head)

        listOf("Wajib & utama" to items.filter { it.adb == null }, "Lewat ADB (opsional)" to items.filter { it.adb != null }).forEach { (title, list) ->
            group(col, title) { c ->
                list.forEachIndexed { i, p ->
                    if (i > 0) c.sep()
                    val r = hbox()
                    r.setPadding(dp(16), dp(14), dp(16), dp(14))
                    val t = vbox()
                    t.addView(tv(p.name + if (p.required) " *" else "", 15f, NX.TEXT, 500))
                    t.add(tv(p.desc, 12.5f, NX.TEXT2), t = 2)
                    r.add(t, 0, WRAP, 1f, 0, 0, 12, 0)
                    r.addView(tv(if (p.ok) "Aktif" else if (p.adb != null) "Via ADB" else "Izinkan", 13f, if (p.ok) NX.GOOD else NX.ACCENT, 700))
                    r.setOnClickListener { handlePerm(p) }
                    r.pressFx(0.98f)
                    c.addView(r)
                }
            }
        }
    }

    private fun about(col: LinearLayout) {
        val c = card(22)
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.addView(tv("Nexus Asisten", 20f, NX.TEXT, 700))
        c.add(tv("Versi ${Updater.currentLabel()}", 13f, NX.TEXT2), t = 2, b = 14)
        c.addView(tv("Asisten Game ringan untuk HP: panel overlay, profil performa per game, perekaman, dan statistik waktu bermain.", 14f, NX.TEXT2))
        col.addView(c)

        col.add(sectionTitle("Jika Play Protect memblokir pemasangan"), t = 18, b = 8)
        val pp = card(22)
        pp.setPadding(dp(18), dp(16), dp(18), dp(16))
        pp.addView(tv(
            "Play Protect di beberapa negara memblokir APK dari browser/file manager yang memakai izin sensitif " +
                "(akses notifikasi, aksesibilitas, SMS). Versi ini sudah tidak memakai akses notifikasi.\n\n" +
                "Bila masih diblokir karena aplikasi baru dan belum dikenal:\n" +
                "1. Ketuk \"Detail lainnya\" lalu \"Tetap instal\".\n" +
                "2. Kalau tombol itu tidak ada: Play Store → foto profil → Play Protect → ikon gerigi → matikan \"Pindai aplikasi dengan Play Protect\" sebentar, pasang, lalu nyalakan lagi.\n" +
                "3. Di OPPO/realme, pilih \"Lanjutkan\"/\"Instal tetap\" pada pemeriksaan keamanan ColorOS.",
            13f, NX.TEXT2
        ))
        col.addView(pp)

        col.add(sectionTitle("Apa yang nyata dan apa yang terbatas"), t = 18, b = 8)
        val n = card(22)
        n.setPadding(dp(18), dp(16), dp(18), dp(16))
        n.addView(tv(
            "Nyata: suhu & baterai, latensi (ping TCP), pengosongan RAM yang aman untuk asisten lain, kunci Wi-Fi latensi rendah, " +
                "cuplikan & rekaman layar, Jangan Ganggu, pencegahan salah sentuh, bantuan bidik, kecerahan, volume, jendela mengambang (bila HP mendukung), waktu bermain.\n\n" +
                "FPS: akurat jika izin DUMP diberikan lewat ADB:\nadb shell pm grant $ADB_PKG android.permission.DUMP\n" +
                "Tanpa itu angka FPS berupa estimasi (diberi tanda ~).\n\n" +
                "Terbatas: laju refresh dan sampling sentuhan bergantung pada ROM. Android tidak mengizinkan aplikasi biasa " +
                "mengubah sensor sentuhan atau CPU/GPU tanpa root, jadi aplikasi ini tidak bisa menjanjikan FPS naik drastis; " +
                "yang dilakukan adalah membebaskan RAM, menjaga refresh rate & Wi-Fi, dan menghindari bentrok dengan aplikasi lain.",
            13.5f, NX.TEXT2
        ))
        col.addView(n)
    }
}
