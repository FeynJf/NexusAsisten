package com.nexus.asisten.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nexus.asisten.core.Updater
import com.nexus.asisten.core.Updater.Stage

/** Kartu pembaruan: otomatis menyegarkan dirinya selama terlihat di layar. */
fun Activity.updateCard(): LinearLayout {
    val c = card(24)
    c.setPadding(dp(18), dp(16), dp(18), dp(16))
    val title = tv("", 16f, NX.TEXT, 700)
    val sub = tv("", 13f, NX.TEXT2)
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
    bar.progressTintList = ColorStateList.valueOf(NX.ACCENT)
    bar.indeterminateTintList = ColorStateList.valueOf(NX.ACCENT)
    val btn = gradientButton("") {}
    c.addView(title)
    c.add(sub, t = 4)
    c.add(bar, MATCH, dp(6), 0f, 0, 12, 0, 0)
    c.add(btn, t = 14)

    fun show(t: String, s: String, b: String?, showBar: Boolean, indeterminate: Boolean, pct: Int, act: () -> Unit) {
        title.text = t
        sub.text = s
        bar.visibility = if (showBar) View.VISIBLE else View.GONE
        bar.isIndeterminate = indeterminate
        if (!indeterminate) bar.progress = pct
        if (b == null) btn.visibility = View.GONE else {
            btn.visibility = View.VISIBLE
            btn.text = b
            btn.setOnClickListener { act() }
        }
    }

    fun refresh() {
        val i = Updater.info
        val size = if (i != null && i.size > 0) " · ${i.size / 1048576} MB" else ""
        when (Updater.stage) {
            Stage.IDLE -> show("Pembaruan", "Versi terpasang ${Updater.currentLabel()}", "Periksa pembaruan", false, false, 0) {
                Fx.say(this, "Memeriksa pembaruan...")
                Updater.check(this, true)
            }
            Stage.CHECKING -> show("Memeriksa pembaruan...", "Versi terpasang ${Updater.currentLabel()}", null, true, true, 0) {}
            Stage.UPTODATE -> show("Sudah versi terbaru", "Versi terpasang ${Updater.currentLabel()}", "Periksa lagi", false, false, 0) {
                Fx.say(this, "Memeriksa pembaruan...")
                Updater.check(this, true)
            }
            Stage.AVAILABLE -> show(
                "Pembaruan tersedia", (i?.title ?: "Versi baru") + size + "\n" + (i?.notes?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: ""),
                "Perbarui sekarang", false, false, 0
            ) {
                Fx.say(this, "Mengunduh pembaruan...", Fx.OK)
                Updater.download(this)
            }
            Stage.DOWNLOADING -> show("Mengunduh pembaruan", "${Updater.progress}%$size", null, true, false, Updater.progress) {}
            Stage.READY -> show("Siap dipasang", Updater.error ?: "Ketuk Pasang lalu konfirmasi di layar berikutnya.", "Pasang sekarang", false, false, 0) {
                Fx.say(this, "Membuka pemasang...")
                Updater.install(this)
            }
            Stage.INSTALLING -> show("Memasang...", "Konfirmasi pemasangan di dialog sistem.", null, true, true, 0) {}
            Stage.ERROR -> show("Gagal", Updater.error ?: "Terjadi kesalahan", "Coba lagi", false, false, 0) {
                if (Updater.info != null) Updater.download(this) else Updater.check(this, true)
            }
        }
    }

    val h = Handler(Looper.getMainLooper())
    val loop = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 500)
        }
    }
    c.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { h.post(loop) }
        override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(loop) }
    })
    refresh()
    return c
}
