package com.nexus.asisten.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * UI kecil perekam layar yang melayang di atas game: tiga menu Jeda, Mulai/Selesai, dan Setelan
 * (mikrofon nyala/mati, resolusi 2K / 1K / 750p / 540p). Saat merekam, bar mengecil jadi ikon saja
 * dan agak transparan supaya tidak menghalangi layar. Bar bisa digeser lewat gagang di atasnya.
 */
class RecordBar(private val ctx: Context, private val wm: WindowManager, private val host: RecordHost) {

    private class Act(val box: LinearLayout, val img: ImageView, val label: TextView)

    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(ctx)
    private val card = ctx.vbox(10, 8)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private lateinit var statusBox: LinearLayout
    private lateinit var dot: View
    private lateinit var tvTime: TextView
    private lateinit var aPause: Act
    private lateinit var aStart: Act
    private lateinit var aSet: Act
    private lateinit var closeBox: FrameLayout
    private lateinit var settingsBox: LinearLayout

    private var attached = false
    private var settingsOpen = false
    private var pending = false
    private var pendingAt = 0L
    private var wasRecording = false
    private val red = Color.parseColor("#FF5D5D")

    private val loop = object : Runnable {
        override fun run() {
            if (!attached) return
            render()
            handler.postDelayed(this, 500)
        }
    }

    init {
        card.background = NeonDrawable(Color.parseColor("#F00F1426"), Color.parseColor("#F2060810"), ctx.dpf(20f))
        card.elevation = ctx.dpf(8f)

        card.addView(buildGrip())

        val row = ctx.hbox()
        statusBox = ctx.hbox()
        dot = View(ctx)
        dot.background = circle(red)
        statusBox.add(dot, ctx.dp(8), ctx.dp(8), 0f, 0, 0, 6, 0)
        tvTime = ctx.tv("00:00", 13f, NX.TEXT, 700)
        statusBox.addView(tvTime)
        statusBox.visibility = View.GONE
        row.add(statusBox, WRAP, WRAP, 0f, 2, 0, 8, 0)

        aPause = action(R.drawable.ic_pause, "Jeda") { onPause() }
        aStart = action(R.drawable.ic_play, "Mulai") { onStart() }
        aSet = action(R.drawable.ic_settings, "Setelan") { onSettings() }
        row.add(aPause.box, ctx.dp(58), WRAP)
        row.add(aStart.box, ctx.dp(58), WRAP)
        row.add(aSet.box, ctx.dp(58), WRAP)

        closeBox = FrameLayout(ctx)
        closeBox.background = circle(withAlpha(Color.WHITE, 0x14))
        closeBox.addView(ctx.icon(R.drawable.ic_close, NX.TEXT2, 14), FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER))
        closeBox.setOnClickListener { hide(); host.onBarClosed() }
        closeBox.pressFx(0.9f)
        row.add(closeBox, ctx.dp(30), ctx.dp(30), 0f, 6, 0, 0, 0)
        card.addView(row)

        settingsBox = ctx.vbox(2, 0)
        settingsBox.visibility = View.GONE
        card.addView(settingsBox)

        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP))
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = ctx.statusInset() + ctx.dp(10)
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    // ---------- Pembentuk tampilan ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun buildGrip(): View {
        val grip = FrameLayout(ctx)
        val pill = View(ctx)
        pill.background = rounded(withAlpha(Color.WHITE, 0x55), ctx.dpf(2f))
        grip.addView(pill, FrameLayout.LayoutParams(ctx.dp(34), ctx.dp(4), Gravity.CENTER))
        var lx = 0f
        var ly = 0f
        grip.setOnTouchListener { _, e ->
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
        val lp = LinearLayout.LayoutParams(MATCH, ctx.dp(16))
        grip.layoutParams = lp
        return grip
    }

    private fun action(iconRes: Int, label: String, onClick: () -> Unit): Act {
        val box = ctx.vbox(4, 4)
        box.gravity = Gravity.CENTER_HORIZONTAL
        val img = ctx.icon(iconRes, NX.TEXT, 22)
        box.addView(img)
        val t = ctx.tv(label, 10f, NX.TEXT2, 500)
        t.gravity = Gravity.CENTER
        box.add(t, WRAP, WRAP, 0f, 0, 3, 0, 0)
        box.setOnClickListener { onClick() }
        box.pressFx(0.9f)
        return Act(box, img, t)
    }

    private fun buildSettings() {
        settingsBox.removeAllViews()
        fun title(s: String) = ctx.tv(s, 10.5f, NX.MUTED, 700).also { it.isAllCaps = true; it.letterSpacing = 0.1f }
        settingsBox.add(title("Mikrofon"), t = 8)
        settingsBox.add(ctx.chipRow(listOf("Mic nyala", "Mic mati"), if (Prefs.recordMic) 0 else 1) { i ->
            val on = i == 0
            Prefs.recordMic = on
            if (on && !Perms.mic(ctx)) host.askMic()
        }, t = 6)
        settingsBox.add(title("Resolusi video"), t = 12)
        settingsBox.add(ctx.chipRow(ScreenCaptureService.RES_LABELS, Prefs.recordRes.coerceIn(0, 3)) { Prefs.recordRes = it }, t = 6)
        settingsBox.add(title("Kehalusan"), t = 12)
        settingsBox.add(ctx.chipRow(listOf("30 fps", "60 fps"), if (Prefs.recordFps60) 1 else 0) { Prefs.recordFps60 = it == 1 }, t = 6, b = 4)
    }

    // ---------- Aksi ----------
    private fun onStart() {
        if (ScreenCaptureService.recording) {
            ScreenCaptureService.send(ctx, ScreenCaptureService.ACTION_STOP)
        } else if (!pending) {
            pending = true
            pendingAt = SystemClock.elapsedRealtime()
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
            buildSettings()
            settingsOpen = true
            settingsBox.visibility = View.VISIBLE
            card.minimumWidth = ctx.dp(264)
        }
    }

    private fun closeSettings() {
        settingsOpen = false
        settingsBox.visibility = View.GONE
        card.minimumWidth = 0
    }

    // ---------- Tampilan sesuai status ----------
    private fun render() {
        val rec = ScreenCaptureService.recording
        val paused = ScreenCaptureService.paused
        if (pending && (rec || SystemClock.elapsedRealtime() - pendingAt > 10_000)) pending = false

        when {
            rec -> { aStart.img.setImageResource(R.drawable.ic_stop); aStart.img.setColorFilter(red); aStart.label.text = "Selesai" }
            pending -> { aStart.img.setImageResource(R.drawable.ic_play); aStart.img.setColorFilter(NX.TEXT); aStart.label.text = "Siap…" }
            else -> { aStart.img.setImageResource(R.drawable.ic_play); aStart.img.setColorFilter(NX.TEXT); aStart.label.text = "Mulai" }
        }
        if (rec && paused) { aPause.img.setImageResource(R.drawable.ic_play); aPause.label.text = "Lanjut" }
        else { aPause.img.setImageResource(R.drawable.ic_pause); aPause.label.text = "Jeda" }
        aPause.box.alpha = if (rec) 1f else 0.35f
        aSet.box.alpha = if (rec) 0.35f else 1f

        statusBox.visibility = if (rec) View.VISIBLE else View.GONE
        if (rec) {
            val s = ScreenCaptureService.elapsedMs() / 1000
            tvTime.text = String.format("%02d:%02d", s / 60, s % 60)
            dot.background = circle(if (paused) NX.WARN else red)
        }
        if (rec && settingsOpen) closeSettings()

        // Mode ringkas saat merekam: hanya ikon, agak transparan.
        val vis = if (rec) View.GONE else View.VISIBLE
        aPause.label.visibility = vis
        aStart.label.visibility = vis
        aSet.label.visibility = vis
        closeBox.visibility = vis
        card.alpha = if (rec) 0.8f else 1f

        if (wasRecording && !rec) {
            // Rekaman selesai: tutup bar sebentar lagi.
            handler.postDelayed({ if (attached) { hide(); host.onBarClosed() } }, 1200)
        }
        wasRecording = rec
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
        Fx.animateIn(root)
        render()
        handler.postDelayed(loop, 500)
    }

    fun hide() {
        if (!attached) return
        attached = false
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(root) } catch (_: Exception) {}
    }

    val isShown get() = attached
}
