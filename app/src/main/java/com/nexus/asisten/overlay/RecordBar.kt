package com.nexus.asisten.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.asisten.R
import com.nexus.asisten.core.Perms
import com.nexus.asisten.core.ScreenCaptureService
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*

/** Yang dibutuhkan bar rekam dari service overlay. */
interface RecordHost {
    /** Mulai merekam (minta izin layar dulu bila sesi belum ada). */
    fun beginRecording()
    fun askMic()
    fun toast(msg: String)
    fun onBarClosed()
}

/**
 * Bar rekam layar bergaya "kapsul kaca" di atas game:
 *  - Siap      : status + ringkasan setelan, tombol Rekam (merah), Jeda, Setelan, Tutup.
 *  - Merekam   : titik REC berdenyut + timer, tombol Jeda & Berhenti; bingkai berubah merah.
 *  - Dijeda    : titik kuning, tombol Lanjut.
 *  - Tersimpan / Gagal : status ditampilkan beberapa detik (dengan alasan kegagalan), tidak langsung hilang.
 * Setelan (mic, resolusi 2K/1K/750p/540p, fps 30-200) terbuka sebagai lembar di bawah kapsul.
 * Seret bagian mana pun yang bukan tombol untuk memindahkan bar.
 */
class RecordBar(private val ctx: Context, private val wm: WindowManager, private val host: RecordHost) {

    private class Btn(val box: FrameLayout, val img: ImageView)

    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(ctx)
    private val card = ctx.vbox(10, 8)
    private val glow = GlowDrawable(ctx.dpf(26f), ctx.dpf(1f), NX.ACCENT, NX.CYAN)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private lateinit var dot: View
    private lateinit var tvState: TextView
    private lateinit var tvInfo: TextView
    private lateinit var bPause: Btn
    private lateinit var bMain: Btn
    private lateinit var bSet: Btn
    private lateinit var bClose: Btn
    private lateinit var sheet: LinearLayout
    private var pulse: ObjectAnimator? = null
    private var pulsing = false

    private var attached = false
    private var settingsOpen = false
    private var pending = false
    private var pendingAt = 0L
    private var pendingWall = 0L
    private var wasRecording = false
    private var savedUntil = 0L
    private var errorUntil = 0L
    private var errorText: String? = null

    private val red = Color.parseColor("#FF4D6A")

    private val loop = object : Runnable {
        override fun run() {
            if (!attached) return
            render()
            handler.postDelayed(this, 400)
        }
    }

    init {
        card.background = glow
        card.elevation = ctx.dpf(10f)

        val row = ctx.hbox()
        val status = ctx.hbox()
        dot = View(ctx)
        dot.background = circle(NX.CYAN)
        status.add(dot, ctx.dp(9), ctx.dp(9), 0f, 4, 0, 9, 0)
        val tx = ctx.vbox()
        tvState = ctx.tv("SIAP", 11f, NX.TEXT, 800)
        tvState.letterSpacing = 0.14f
        tvInfo = ctx.tv("", 11.5f, NX.TEXT2, 500)
        tvInfo.maxLines = 1
        tvInfo.ellipsize = TextUtils.TruncateAt.END
        tvInfo.maxWidth = ctx.dp(190)
        tx.addView(tvState)
        tx.add(tvInfo, WRAP, WRAP, 0f, 0, 1, 0, 0)
        status.add(tx, WRAP, WRAP)
        row.add(status, WRAP, WRAP, 0f, 2, 0, 10, 0)

        bPause = circleBtn(R.drawable.ic_pause, 34) { onPause() }
        bMain = circleBtn(R.drawable.ic_record, 44) { onMain() }
        bMain.box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF6B7A"), Color.parseColor("#E0204D"))).also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(ctx.dp(1), withAlpha(Color.WHITE, 0x66))
        }
        bSet = circleBtn(R.drawable.ic_settings, 34) { onSettings() }
        bClose = circleBtn(R.drawable.ic_close, 28) { hide(); host.onBarClosed() }
        row.add(bPause.box, ctx.dp(34), ctx.dp(34), 0f, 0, 0, 8, 0)
        row.add(bMain.box, ctx.dp(44), ctx.dp(44), 0f, 0, 0, 8, 0)
        row.add(bSet.box, ctx.dp(34), ctx.dp(34), 0f, 0, 0, 8, 0)
        row.add(bClose.box, ctx.dp(28), ctx.dp(28))
        card.addView(row)

        sheet = ctx.vbox(4, 0)
        sheet.visibility = View.GONE
        card.addView(sheet)

        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP))
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = ctx.statusInset() + ctx.dp(12)
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        installDrag()
    }

    // ---------- Pembentuk tampilan ----------
    private fun circleBtn(iconRes: Int, sizeDp: Int, onClick: () -> Unit): Btn {
        val box = FrameLayout(ctx)
        box.background = rounded(withAlpha(Color.WHITE, 0x16), ctx.dpf(sizeDp / 2f), withAlpha(Color.WHITE, 0x22), 1)
        val s = ctx.dp((sizeDp * 0.5f).toInt())
        val img = ctx.icon(iconRes, Color.WHITE, (sizeDp * 0.5f).toInt())
        box.addView(img, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        box.setOnClickListener { onClick() }
        box.pressFx(0.88f)
        return Btn(box, img)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        var lx = 0f
        var ly = 0f
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x += (e.rawX - lx).toInt()
                    params.y += (e.rawY - ly).toInt()
                    lx = e.rawX; ly = e.rawY
                    if (attached) try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                }
            }
            true
        }
    }

    private fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = ctx.hbox()
        box.background = rounded(withAlpha(Color.WHITE, 0x10), ctx.dpf(14f), withAlpha(Color.WHITE, 0x1A), 1)
        box.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, t ->
                if (i == sel) {
                    t.background = gradientRounded(NX.ACCENT_DEEP, NX.BLUE, ctx.dpf(11f))
                    t.setTextColor(Color.WHITE)
                } else {
                    t.background = null
                    t.setTextColor(NX.TEXT2)
                }
            }
        }
        labels.forEachIndexed { i, l ->
            val t = ctx.tv(l, 12f, NX.TEXT2, 700)
            t.gravity = Gravity.CENTER
            t.setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            t.setOnClickListener { paint(i); onSelect(i); renderInfo() }
            views.add(t)
            box.add(t, 0, WRAP, 1f)
        }
        paint(selected)
        return box
    }

    private fun toggleRow(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = ctx.hbox()
        val left = ctx.vbox()
        left.addView(ctx.tv(label, 13f, NX.TEXT, 700))
        left.add(ctx.tv(sub, 11f, NX.MUTED), WRAP, WRAP, 0f, 0, 1, 0, 0)
        row.add(left, 0, WRAP, 1f)
        val track = FrameLayout(ctx)
        val thumb = View(ctx)
        thumb.background = circle(Color.WHITE)
        track.addView(thumb, FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER_VERTICAL or Gravity.START))
        var state = on
        fun paint(animate: Boolean) {
            track.background = if (state) gradientRounded(NX.ACCENT_DEEP, NX.BLUE, ctx.dpf(13f))
            else rounded(withAlpha(Color.WHITE, 0x22), ctx.dpf(13f))
            val tx = if (state) ctx.dpf(22f) else ctx.dpf(3f)
            if (animate) thumb.animate().translationX(tx).setDuration(160).start() else thumb.translationX = tx
        }
        paint(false)
        track.setOnClickListener { state = !state; paint(true); onChange(state) }
        row.add(track, ctx.dp(46), ctx.dp(26))
        return row
    }

    private fun buildSheet() {
        sheet.removeAllViews()
        fun title(s: String) = ctx.tv(s, 10f, NX.MUTED, 800).also { it.isAllCaps = true; it.letterSpacing = 0.14f }
        sheet.add(View(ctx).also { it.setBackgroundColor(withAlpha(Color.WHITE, 0x18)) }, MATCH, 1, 0f, 0, 8, 0, 0)
        sheet.add(toggleRow("Suara mikrofon", "Matikan bila tidak ingin suara ikut terekam", Prefs.recordMic) { on ->
            Prefs.recordMic = on
            if (on && !Perms.mic(ctx)) host.askMic()
            renderInfo()
        }, t = 10)
        sheet.add(title("Resolusi video"), t = 12, b = 6)
        sheet.addView(segmented(ScreenCaptureService.RES_LABELS, Prefs.recordRes.coerceIn(0, 3)) { Prefs.recordRes = it })
        sheet.add(title("Frame rate (fps)"), t = 12, b = 6)
        val opts = ScreenCaptureService.FPS_OPTIONS
        sheet.addView(segmented(opts.map { it.toString() }, opts.indexOf(Prefs.recordFps).coerceAtLeast(0)) { Prefs.recordFps = opts[it] })
        sheet.add(ctx.tv("fps tinggi dibatasi refresh rate layar & encoder HP; otomatis turun bila tidak didukung.", 10.5f, NX.MUTED), t = 6, b = 4)
    }

    // ---------- Aksi ----------
    private fun onMain() {
        if (ScreenCaptureService.recording) {
            ScreenCaptureService.send(ctx, ScreenCaptureService.ACTION_STOP)
        } else if (!pending) {
            pending = true
            pendingAt = SystemClock.elapsedRealtime()
            pendingWall = System.currentTimeMillis()
            errorUntil = 0L
            closeSettings()
            host.beginRecording()
        }
        render()
    }

    private fun onPause() {
        if (!ScreenCaptureService.recording) { host.toast("Mulai merekam dulu"); return }
        ScreenCaptureService.send(
            ctx, if (ScreenCaptureService.paused) ScreenCaptureService.ACTION_RESUME else ScreenCaptureService.ACTION_PAUSE
        )
    }

    private fun onSettings() {
        if (ScreenCaptureService.recording) { host.toast("Ubah setelan sebelum mulai merekam"); return }
        if (settingsOpen) closeSettings() else {
            buildSheet()
            settingsOpen = true
            sheet.visibility = View.VISIBLE
            card.minimumWidth = ctx.dp(318)
        }
    }

    private fun closeSettings() {
        settingsOpen = false
        sheet.visibility = View.GONE
        card.minimumWidth = 0
    }

    // ---------- Tampilan sesuai status ----------
    private fun summary(): String {
        val res = ScreenCaptureService.RES_LABELS[Prefs.recordRes.coerceIn(0, 3)]
        return "$res · ${Prefs.recordFps} fps · " + if (Prefs.recordMic) "Mic" else "Tanpa mic"
    }

    private fun renderInfo() {
        if (!ScreenCaptureService.recording && !pending && errorUntil == 0L && savedUntil == 0L) tvInfo.text = summary()
    }

    private fun setPulse(on: Boolean) {
        if (on == pulsing) return
        pulsing = on
        pulse?.cancel()
        pulse = null
        dot.alpha = 1f
        if (on) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.2f).apply {
                duration = 650
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val rec = ScreenCaptureService.recording
        val paused = ScreenCaptureService.paused

        // Alasan gagal dari service ditampilkan di bar (tidak hilang diam-diam).
        if (pending && ScreenCaptureService.lastErrorAt >= pendingWall - 500 && ScreenCaptureService.lastError != null
        ) {
            errorText = ScreenCaptureService.lastError
            errorUntil = now + 6000
            pending = false
        }
        if (rec && pending) pending = false
        if (pending && now - pendingAt > 12_000) {
            pending = false
            errorText = "Tidak ada respons dari izin layar. Coba lagi."
            errorUntil = now + 5000
        }
        if (wasRecording && !rec) {
            val err = ScreenCaptureService.lastError
            if (err != null && System.currentTimeMillis() - ScreenCaptureService.lastErrorAt < 4000) {
                errorText = err
                errorUntil = now + 6000
            } else {
                savedUntil = now + 3200
            }
        }
        wasRecording = rec
        if (errorUntil != 0L && now > errorUntil) { errorUntil = 0L; errorText = null }
        val showSaved = !rec && savedUntil != 0L && now < savedUntil
        if (savedUntil != 0L && now >= savedUntil && !rec) {
            // selesai menampilkan "Tersimpan": tutup bar dengan halus
            savedUntil = 0L
            hide(); host.onBarClosed()
            return
        }

        when {
            rec -> {
                val sec = ScreenCaptureService.elapsedMs() / 1000
                tvState.text = if (paused) "JEDA" else "REC"
                tvState.setTextColor(if (paused) NX.WARN else red)
                tvInfo.text = String.format("%02d:%02d", sec / 60, sec % 60)
                tvInfo.setTextColor(NX.TEXT)
                dot.background = circle(if (paused) NX.WARN else red)
                glow.edgeFrom = if (paused) NX.WARN else red
                glow.edgeTo = NX.ACCENT
            }
            showSaved -> {
                tvState.text = "TERSIMPAN"
                tvState.setTextColor(NX.GOOD)
                tvInfo.text = "Lihat di Album Nexus"
                tvInfo.setTextColor(NX.TEXT2)
                dot.background = circle(NX.GOOD)
                glow.edgeFrom = NX.GOOD; glow.edgeTo = NX.CYAN
            }
            errorUntil != 0L -> {
                tvState.text = "GAGAL"
                tvState.setTextColor(NX.WARN)
                tvInfo.text = errorText ?: "Perekaman gagal"
                tvInfo.setTextColor(NX.TEXT2)
                dot.background = circle(NX.WARN)
                glow.edgeFrom = NX.WARN; glow.edgeTo = NX.HOT
            }
            pending -> {
                tvState.text = "MENYIAPKAN"
                tvState.setTextColor(NX.CYAN)
                tvInfo.text = "Izinkan perekaman layar…"
                tvInfo.setTextColor(NX.TEXT2)
                dot.background = circle(NX.CYAN)
                glow.edgeFrom = NX.ACCENT; glow.edgeTo = NX.CYAN
            }
            else -> {
                tvState.text = "SIAP"
                tvState.setTextColor(NX.TEXT)
                tvInfo.text = summary()
                tvInfo.setTextColor(NX.TEXT2)
                dot.background = circle(NX.CYAN)
                glow.edgeFrom = NX.ACCENT; glow.edgeTo = NX.CYAN
            }
        }
        setPulse(rec && !paused)

        bMain.img.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_record)
        if (rec && paused) bPause.img.setImageResource(R.drawable.ic_play) else bPause.img.setImageResource(R.drawable.ic_pause)
        bPause.box.alpha = if (rec) 1f else 0.35f
        bSet.box.alpha = if (rec) 0.35f else 1f
        bMain.box.alpha = if (pending || showSaved) 0.6f else 1f

        // Saat merekam: ringkas, tombol tutup disembunyikan, agak transparan.
        bClose.box.visibility = if (rec) View.GONE else View.VISIBLE
        bSet.box.visibility = if (rec) View.GONE else View.VISIBLE
        card.alpha = if (rec) 0.9f else 1f
        if (rec && settingsOpen) closeSettings()
    }

    fun show() {
        if (attached) return
        try {
            wm.addView(root, params)
        } catch (_: Exception) {
            host.toast("Gagal menampilkan bar rekam")
            host.onBarClosed()
            return
        }
        attached = true
        wasRecording = ScreenCaptureService.recording
        card.alpha = 0f
        card.translationY = -ctx.dpf(30f)
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
        render()
        handler.postDelayed(loop, 400)
    }

    fun hide() {
        if (!attached) return
        attached = false
        setPulse(false)
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(root) } catch (_: Exception) {}
    }

    val isShown get() = attached
}
