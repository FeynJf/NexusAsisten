package com.nexus.asisten.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nexus.asisten.R
import com.nexus.asisten.core.Booster
import com.nexus.asisten.core.SystemStats
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*
import kotlin.math.min

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
    fun requestNotifAccess()
    fun relayout()
}

class PanelUi(private val ctx: Context, private val host: PanelHost) {
    val root = FrameLayout(ctx)
    private val cardView = LinearLayout(ctx)
    private val content = FrameLayout(ctx)
    private val railItems = ArrayList<LinearLayout>()
    private var tab = 0

    private var tvFps: TextView? = null
    private var tvPing: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBat: TextView? = null
    private var tvSession: TextView? = null

    private val selfAnnounced = setOf("Cegah salah sentuh", "Mode Fokus")

    private class Tile(val icon: Int, val label: String, val state: (() -> Boolean)?, val onClick: () -> Unit)

    init {
        val dm = ctx.resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val cardW = if (landscape) min(ctx.dp(640), (dm.widthPixels * 0.8f).toInt()) else dm.widthPixels - ctx.dp(20)
        val cardH = if (landscape) dm.heightPixels - ctx.dp(24) else min((dm.heightPixels * 0.78f).toInt(), ctx.dp(560))

        root.setBackgroundColor(NX.SCRIM)
        root.setOnClickListener { host.close() }

        val card = cardView
        card.orientation = LinearLayout.HORIZONTAL
        card.background = gradientRounded(
            Color.parseColor("#1B2348"), Color.parseColor("#0A0F1F"), ctx.dpf(30f), GradientDrawable.Orientation.TL_BR
        ).also { it.setStroke(1, 0x33FFFFFF) }
        card.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        card.isClickable = true

        card.add(buildRail(), ctx.dp(76), MATCH)
        card.add(content, 0, MATCH, 1f, 8, 0, 0, 0)

        val gravity = (if (Prefs.dockLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        val lp = FrameLayout.LayoutParams(cardW, cardH, gravity)
        lp.setMargins(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        root.addView(card, lp)
        showTab(0)

        // Animasi masuk: kartu meluncur dari sisi dock sambil memudar masuk.
        val dir = if (Prefs.dockLeft) -1f else 1f
        root.alpha = 0f
        card.translationX = ctx.dpf(48f) * dir
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        root.post {
            root.animate().alpha(1f).setDuration(200).start()
            card.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    /** Animasi keluar, lalu panggil [end] (biasanya melepas jendela). */
    fun animateOut(end: () -> Unit) {
        val dir = if (Prefs.dockLeft) -1f else 1f
        cardView.animate().translationX(ctx.dpf(40f) * dir).alpha(0f).setDuration(180).start()
        root.animate().alpha(0f).setDuration(200).withEndAction(end).start()
    }

    // ---------- Rail ----------
    private fun buildRail(): View {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.background = rounded(0x66050810, ctx.dpf(24f), 0x14FFFFFF, 1)
        val rail = ctx.vbox(4, 12)
        rail.gravity = Gravity.CENTER_HORIZONTAL
        val tabs = listOf("Performa" to R.drawable.ic_speed, "Alat" to R.drawable.ic_grid, "Saya" to R.drawable.ic_person)
        tabs.forEachIndexed { idx, (label, ic) ->
            val item = ctx.vbox(0, 8)
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.addView(ctx.icon(ic, NX.TEXT2, 24))
            item.add(ctx.tv(label, 11f, NX.TEXT2, 500).also { it.gravity = Gravity.CENTER }, WRAP, WRAP, 0f, 0, 3, 0, 0)
            item.setOnClickListener { showTab(idx) }
            item.pressFx(0.92f)
            railItems.add(item)
            rail.add(item, MATCH, WRAP, 0f, 0, 0, 0, 4)
        }
        rail.add(ctx.divider(), MATCH, 1, 0f, 8, 6, 8, 10)
        host.dockApps().forEach { (pkg, d) ->
            val iv = android.widget.ImageView(ctx)
            iv.setImageDrawable(d)
            iv.setOnClickListener { host.launchDock(pkg) }
            iv.pressFx(0.88f)
            rail.add(iv, ctx.dp(40), ctx.dp(40), 0f, 0, 0, 0, 10)
        }
        val plus = ctx.icon(R.drawable.ic_add, NX.MUTED, 22)
        plus.setOnClickListener { host.pickDockApps() }
        plus.pressFx(0.88f)
        rail.add(plus, ctx.dp(40), ctx.dp(34))
        sv.addView(rail)
        return sv
    }

    private fun styleRail(sel: Int) {
        railItems.forEachIndexed { i, item ->
            val on = i == sel
            val c = if (on) NX.ACCENT else NX.TEXT2
            item.background = if (on) rounded(0x338E7BFF, ctx.dpf(16f)) else null
            (item.getChildAt(0) as android.widget.ImageView).setColorFilter(c)
            (item.getChildAt(1) as TextView).setTextColor(c)
        }
    }

    fun showTab(i: Int) {
        tab = i
        styleRail(i)
        content.removeAllViews()
        clearRefs()
        content.addView(
            when (i) { 0 -> pagePerforma(); 1 -> pageAlat(); else -> pageSaya() },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        content.alpha = 0f
        content.translationY = ctx.dpf(10f)
        content.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    private fun clearRefs() { tvFps = null; tvPing = null; tvTemp = null; tvBat = null; tvSession = null }

    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        val col = ctx.vbox(10, 8)
        sv.addView(col)
        return Pair(sv, col)
    }

    // ---------- Statistik langsung ----------
    private fun statBlock(label: String, color: Int): Pair<LinearLayout, TextView> {
        val b = ctx.vbox()
        b.gravity = Gravity.CENTER_HORIZONTAL
        val v = ctx.tv("--", 22f, color, 700)
        b.addView(ctx.tv(label, 11.5f, NX.TEXT2))
        b.add(v, WRAP, WRAP, 0f, 0, 2, 0, 0)
        return Pair(b, v)
    }

    private fun statsRow(): View {
        val row = ctx.hbox()
        row.background = rounded(NX.SURFACE2, ctx.dpf(20f), NX.LINE, 1)
        row.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
        val a = statBlock("Laju bingkai", NX.CYAN)
        val b = statBlock("Jaringan", NX.TEXT)
        val c = statBlock("Suhu", NX.WARN)
        val d = statBlock("Baterai", NX.GOOD)
        tvFps = a.second; tvPing = b.second; tvTemp = c.second; tvBat = d.second
        listOf(a, b, c, d).forEach { row.add(it.first, 0, WRAP, 1f) }
        return row
    }

    fun update(fps: Int, exact: Boolean, ping: Int, temp: Float, bat: Int) {
        tvFps?.text = (if (exact) "" else "~") + fps
        tvPing?.text = if (ping < 0) "--" else "$ping ms"
        tvTemp?.text = String.format("%.0f°C", temp)
        tvTemp?.setTextColor(if (temp >= Prefs.tempWarn) NX.HOT else NX.WARN)
        tvBat?.text = "$bat%"
        tvSession?.text = fmtDuration(host.sessionMs())
    }

    // ---------- Halaman Performa ----------
    private fun pagePerforma(): View {
        val (sv, col) = scrollPage()
        val p = host.profile()
        col.add(statsRow())
        col.add(ctx.gradientButton("Boost sekarang") { host.boost() }, t = 12)
        col.add(ctx.tv("Kosongkan RAM, kunci Wi-Fi & refresh rate, lalu uji jaringan.", 11.5f, NX.MUTED), t = 6)
        col.add(ctx.sectionTitle("Mode performa"), t = 14, b = 8)
        val modeNames = listOf("Hemat daya", "Seimbang", "Gamer Pro")
        col.add(ctx.chipRow(modeNames, p.mode, listOf(NX.GOOD, NX.CYAN, NX.PRO)) {
            host.updateProfile(host.profile().copy(mode = it))
            host.toast("Mode ${modeNames[it]} diterapkan")
        })
        col.add(ctx.tv("Gamer Pro menaikkan konsumsi daya dan suhu.", 12f, NX.MUTED), t = 6)

        col.add(ctx.sectionTitle("Laju pengambilan sampel sentuhan"), t = 16, b = 8)
        col.add(ctx.chipRow(listOf("Cerdas", "Sentuhan ultra"), if (p.ultraTouch) 1 else 0) {
            host.updateProfile(host.profile().copy(ultraTouch = it == 1))
            host.toast(if (it == 1) "Sentuhan ultra dipilih" else "Sentuhan cerdas dipilih")
        })

        col.add(ctx.sectionTitle("Kecepatan refresh layar"), t = 16, b = 8)
        val opts = Booster.supportedRefresh(ctx)
        val sel = if (p.refreshHz == 0) opts.size - 1 else opts.indexOfFirst { it == p.refreshHz }.coerceAtLeast(0)
        col.add(ctx.chipRow(opts.map { "$it Hz" }, sel) {
            host.updateProfile(host.profile().copy(refreshHz = opts[it]))
            host.toast("Refresh rate ${opts[it]} Hz diminta")
        })

        val link = ctx.linkRow("Performa dalam bermain game saya", "Layar, kontrol, fokus", R.drawable.ic_settings) {
            host.openGameSettings()
        }
        link.background = rounded(NX.SURFACE2, ctx.dpf(16f))
        col.add(link, t = 18)
        return sv
    }

    // ---------- Halaman Alat ----------
    private fun pageAlat(): View {
        val (sv, col) = scrollPage()
        col.add(statsRow())
        col.add(brightnessRow(), t = 12)
        val tiles = listOf(
            Tile(R.drawable.ic_notification, "Notifikasi bertitik", { Prefs.notifDots }) {
                if (!com.nexus.asisten.core.Perms.notifListener(ctx)) host.requestNotifAccess()
                else Prefs.notifDots = !Prefs.notifDots
            },
            Tile(R.drawable.ic_folder_star, "Tambahkan ke Asisten", null) { host.addToAssistant() },
            Tile(R.drawable.ic_globe, "Cek jaringan", null) { host.checkNetwork() },
            Tile(R.drawable.ic_wifi, "WLAN", null) { host.openWifi() },
            Tile(R.drawable.ic_monitor, "Status sistem", null) { showStatus() },
            Tile(R.drawable.ic_nuclear, "Kosongkan RAM", null) { host.cleanMemory() },
            Tile(R.drawable.ic_crosshair, "Bantuan bidik", { Prefs.crosshair }) { host.setCrosshair(!Prefs.crosshair) },
            Tile(R.drawable.ic_scissors, "Cuplikan layar", null) { host.screenshot() },
            Tile(R.drawable.ic_video, "Perekaman layar", { host.isRecording() }) { host.toggleRecord() },
            Tile(R.drawable.ic_hand, "Cegah salah sentuh", { Prefs.edgeGuard }) { host.setEdgeGuard(!Prefs.edgeGuard) },
            Tile(R.drawable.ic_bell, "Jangan Ganggu", { Booster.dndOn(ctx) }) { host.setDnd(!Booster.dndOn(ctx)) },
            Tile(R.drawable.ic_focus, "Mode Fokus", { Prefs.focusMode }) { host.setFocus(!Prefs.focusMode) },
            Tile(R.drawable.ic_brightness, "Layar menyala", { Prefs.keepAwake }) { host.setKeepAwake(!Prefs.keepAwake) },
            Tile(R.drawable.ic_bolt, "Mulai cepat", { Prefs.quickStart }) { Prefs.quickStart = !Prefs.quickStart },
            Tile(R.drawable.ic_volume, "Volume", null) { showVolume() },
            Tile(R.drawable.ic_rotate, "Kunci orientasi", { rotationLocked() }) { toggleRotation() },
            Tile(R.drawable.ic_gamepad, "Asisten Game", null) { showAssistant() },
            Tile(R.drawable.ic_settings, "Sesuaikan", null) { host.openSettings() }
        )
        var i = 0
        while (i < tiles.size) {
            val row = ctx.hbox()
            for (k in 0 until 3) {
                val t = tiles.getOrNull(i + k)
                if (t != null) row.add(tileView(t), 0, ctx.dp(84), 1f, 0, 0, if (k < 2) 8 else 0, 0)
                else row.add(View(ctx), 0, ctx.dp(84), 1f)
            }
            col.add(row, t = 8)
            i += 3
        }
        return sv
    }

    private fun styleTile(v: LinearLayout, active: Boolean) {
        val r = ctx.dpf(16f)
        val base = if (active)
            gradientRounded(0x558E7BFF, 0x225B47F5, r, GradientDrawable.Orientation.TL_BR).also { it.setStroke(ctx.dp(1), NX.ACCENT) }
        else rounded(NX.SURFACE2, r, NX.LINE, 1)
        v.rippleBg(base, r)
        (v.getChildAt(0) as android.widget.ImageView).setColorFilter(if (active) NX.ACCENT else NX.TEXT2)
        (v.getChildAt(1) as TextView).setTextColor(if (active) NX.TEXT else NX.TEXT2)
    }

    private fun tileView(t: Tile): View {
        val v = ctx.vbox(6, 8)
        v.gravity = Gravity.CENTER
        v.addView(ctx.icon(t.icon, NX.TEXT2, 24))
        val label = ctx.tv(t.label, 11.5f, NX.TEXT2, 500)
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

    private fun brightnessRow(): View {
        val row = ctx.hbox()
        row.background = rounded(NX.SURFACE2, ctx.dpf(18f), NX.LINE, 1)
        row.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        row.addView(ctx.icon(R.drawable.ic_brightness, NX.WARN, 22))
        val sb = SeekBar(ctx)
        sb.max = 255
        sb.progress = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
        sb.progressTintList = android.content.res.ColorStateList.valueOf(NX.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (!Settings.System.canWrite(ctx)) { host.requestWrite(); return }
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxOf(p, 8))
            }
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
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rotationLocked()) 1 else 0)
    }

    // ---------- Sub-halaman ----------
    private fun subPage(title: String, build: (LinearLayout) -> Unit) {
        content.removeAllViews()
        clearRefs()
        val (sv, col) = scrollPage()
        val head = ctx.hbox()
        val back = ctx.icon(R.drawable.ic_back, NX.TEXT, 24)
        back.setOnClickListener { showTab(tab) }
        head.add(back, ctx.dp(24), ctx.dp(24), 0f, 0, 0, 12, 0)
        head.addView(ctx.tv(title, 17f, NX.TEXT, 700))
        col.add(head, b = 14)
        build(col)
        content.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
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

    private fun showVolume() = subPage("Volume") { col ->
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        col.addView(ctx.tv("Volume media", 13f, NX.TEXT2))
        val sb = SeekBar(ctx)
        sb.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sb.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        sb.progressTintList = android.content.res.ColorStateList.valueOf(NX.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.add(sb, t = 10)
    }

    private fun showAssistant() = subPage("Asisten Game") { col ->
        col.addView(ctx.sectionTitle("Posisi Asisten Game"))
        col.add(ctx.chipRow(listOf("Kiri", "Kanan"), if (Prefs.dockLeft) 0 else 1) {
            Prefs.dockLeft = it == 0
            host.relayout()
        }, t = 8)
        val c = ctx.card(16, NX.SURFACE2)
        c.addView(ctx.switchRow("Jendela mengambang", "Buka aplikasi dock sebagai jendela kecil", Prefs.floatingWindow) {
            Prefs.floatingWindow = it
        })
        c.add(ctx.divider(), MATCH, 1)
        c.addView(ctx.linkRow("Aplikasi di dock", "Pilih pintasan di sisi panel", R.drawable.ic_grid) { host.pickDockApps() })
        c.add(ctx.divider(), MATCH, 1)
        c.addView(ctx.linkRow("Ikon layar Awal", "Pintasan Mulai cepat untuk ${host.gameName}", R.drawable.ic_play) { host.pinShortcut() })
        col.add(c, t = 16)
    }

    // ---------- Halaman Saya ----------
    private fun pageSaya(): View {
        val (sv, col) = scrollPage()
        val head = ctx.hbox()
        val avatar = ctx.tv(Prefs.userName.take(1).uppercase(), 22f, Color.WHITE, 700)
        avatar.gravity = Gravity.CENTER
        avatar.background = gradientRounded(NX.ACCENT, NX.CYAN, ctx.dpf(28f))
        head.add(avatar, ctx.dp(52), ctx.dp(52), 0f, 0, 0, 14, 0)
        val names = ctx.vbox()
        names.addView(ctx.tv(Prefs.userName, 18f, NX.TEXT, 700))
        names.add(ctx.tv("Sedang bermain ${host.gameName}", 12.5f, NX.TEXT2), t = 2)
        head.add(names, 0, WRAP, 1f)
        col.add(head, b = 14)

        val c = ctx.card(16, NX.SURFACE2)
        c.setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
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
        col.add(ctx.ghostButton("Pengaturan lainnya") { host.openSettings() }, t = 8)
        return sv
    }
}
