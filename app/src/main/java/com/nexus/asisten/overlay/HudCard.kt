package com.nexus.asisten.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.asisten.R
import com.nexus.asisten.ui.*

/**
 * Kartu HUD beranimasi di atas layar game (tidak menghalangi sentuhan):
 *  - [toast]  : notifikasi singkat, meluncur masuk dengan pegas + garis waktu yang menyusut.
 *  - [begin] / [step] / [finish] : proses berlangkah (Boost, Kosongkan RAM, Cek jaringan) dengan
 *    bilah cahaya berjalan, langkah yang berputar lalu berubah jadi centang, dan hasil berupa
 *    lencana + meter yang terisi beranimasi.
 */
class HudCard(private val ctx: Context, private val wm: WindowManager, private val handler: Handler) {

    enum class Kind { OK, WARN, INFO }
    class Metric(val label: String, val value: String, val fraction: Float, val color: Int)

    private class StepRow(
        val root: LinearLayout, val mark: FrameLayout, val icon: ImageView, val label: TextView, val detail: TextView
    ) {
        var done = false
        var spin: ObjectAnimator? = null
    }

    private var card: LinearLayout? = null
    private var closeTask: Runnable? = null
    private var shimmer: ValueAnimator? = null
    private var barFill: View? = null
    private var headIcon: View? = null
    private var headSub: TextView? = null
    private val rows = ArrayList<StepRow>()

    /** true selama proses (begin..finish) berjalan; notifikasi biasa tidak menimpa kartu proses. */
    @Volatile var processActive = false
        private set

    private fun colorOf(k: Kind) = when (k) { Kind.OK -> NX.GOOD; Kind.WARN -> NX.WARN; Kind.INFO -> NX.CYAN }

    // ---------- Notifikasi singkat ----------
    fun toast(title: String, sub: String?, kind: Kind, icon: Int) {
        if (processActive) return
        removeNow()
        val color = colorOf(kind)
        val c = newCard(color)
        val row = ctx.hbox()
        val disc = disc(icon, color, 36)
        row.add(disc, ctx.dp(36), ctx.dp(36), 0f, 0, 0, 12, 0)
        val tx = ctx.vbox()
        tx.addView(ctx.tv(title, 14f, NX.TEXT, 700).also { it.maxLines = 2 })
        if (!sub.isNullOrBlank()) tx.add(ctx.tv(sub, 12f, NX.TEXT2).also { it.maxLines = 3 }, WRAP, WRAP, 0f, 0, 2, 0, 0)
        row.add(tx, WRAP, WRAP)
        c.addView(row)
        val bar = View(ctx)
        bar.background = rounded(color, ctx.dpf(2f))
        bar.pivotX = 0f
        c.add(bar, MATCH, ctx.dp(2), 0f, 0, 10, 0, 0)
        present(c)
        popIn(disc)
        val dur = if (sub.isNullOrBlank()) 2200L else 3200L
        bar.animate().scaleX(0f).setDuration(dur).setInterpolator(LinearInterpolator()).start()
        scheduleClose(dur)
    }

    // ---------- Proses berlangkah ----------
    fun begin(title: String, icon: Int, steps: List<String>) {
        removeNow()
        val c = newCard(NX.CYAN)
        val head = ctx.hbox()
        val disc = disc(icon, NX.CYAN, 40)
        headIcon = disc
        head.add(disc, ctx.dp(40), ctx.dp(40), 0f, 0, 0, 12, 0)
        val tx = ctx.vbox()
        tx.addView(ctx.tv(title, 15f, NX.TEXT, 700))
        val sub = ctx.tv("Sedang berjalan…", 12f, NX.CYAN, 500)
        headSub = sub
        tx.add(sub, WRAP, WRAP, 0f, 0, 2, 0, 0)
        head.add(tx, WRAP, WRAP)
        c.addView(head)

        val track = FrameLayout(ctx)
        track.background = rounded(withAlpha(Color.WHITE, 0x18), ctx.dpf(2f))
        val fill = View(ctx)
        fill.background = gradientRounded(NX.ACCENT, NX.CYAN, ctx.dpf(2f))
        fill.pivotX = 0f
        fill.scaleX = 0.35f
        barFill = fill
        track.addView(fill, FrameLayout.LayoutParams(MATCH, MATCH))
        c.add(track, MATCH, ctx.dp(3), 0f, 0, 12, 0, 6)
        shimmer = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { fill.translationX = (-0.35f + 1.35f * (it.animatedValue as Float)) * track.width }
            start()
        }

        rows.clear()
        steps.forEach { label ->
            val r = stepRow(label)
            rows.add(r)
            c.add(r.root, MATCH, WRAP, 0f, 0, 6, 0, 0)
        }
        processActive = true
        present(c)
        popIn(disc)
        if (rows.isNotEmpty()) activate(0)
    }

    /** Tandai langkah [i] selesai (dengan keterangan opsional) lalu mulai langkah berikutnya. */
    fun step(i: Int, detail: String? = null) {
        if (!processActive || i !in rows.indices) return
        complete(i, detail)
        if (i + 1 in rows.indices) activate(i + 1)
    }

    fun finish(headline: String, kind: Kind, badge: String?, metrics: List<Metric>, tip: String?, closeMs: Long = 6000) {
        val c = card ?: return
        val color = colorOf(kind)
        stopAnimators()
        rows.forEachIndexed { i, r -> if (!r.done) complete(i, null) }
        barFill?.let {
            it.translationX = 0f
            it.background = gradientRounded(color, NX.ACCENT, ctx.dpf(2f))
            it.animate().scaleX(1f).setDuration(380).start()
        }
        (c.background as? GlowDrawable)?.edgeFrom = color
        headSub?.text = headline
        headSub?.setTextColor(color)
        headIcon?.let { popIn(it) }
        var delay = 150L
        if (badge != null) {
            val b = ctx.tv(badge, 22f, color, 800)
            b.gravity = Gravity.CENTER
            b.letterSpacing = 0.08f
            c.add(b, MATCH, WRAP, 0f, 0, 12, 0, 2)
            b.scaleX = 0f; b.scaleY = 0f
            b.animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(450).setInterpolator(OvershootInterpolator(2.2f)).start()
            delay += 150
        }
        metrics.forEach { m ->
            val v = metricView(m, delay)
            c.add(v, MATCH, WRAP, 0f, 0, 9, 0, 0)
            delay += 110
        }
        if (!tip.isNullOrBlank()) {
            val t = ctx.tv(tip, 11.5f, NX.MUTED)
            c.add(t, MATCH, WRAP, 0f, 0, 10, 0, 0)
            t.alpha = 0f
            t.animate().alpha(1f).setStartDelay(delay).setDuration(300).start()
        }
        processActive = false
        scheduleClose(closeMs)
    }

    fun dismiss() {
        closeTask?.let { handler.removeCallbacks(it) }
        closeTask = null
        val c = card ?: return
        card = null
        processActive = false
        stopAnimators()
        c.animate().alpha(0f).translationY(-ctx.dpf(30f)).scaleX(0.95f).scaleY(0.95f).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { try { wm.removeView(c) } catch (_: Exception) {} }.start()
    }

    /** Hapus seketika tanpa animasi (saat service berhenti). */
    fun release() = removeNow()

    // ---------- Pembentuk tampilan ----------
    private fun newCard(color: Int): LinearLayout {
        val c = ctx.vbox(16, 13)
        c.background = GlowDrawable(ctx.dpf(20f), ctx.dpf(1f), color, NX.ACCENT)
        c.elevation = ctx.dpf(10f)
        c.minimumWidth = ctx.dp(260)
        return c
    }

    private fun disc(icon: Int, color: Int, sizeDp: Int): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, NX.ACCENT_DEEP)).also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(1, withAlpha(Color.WHITE, 0x55))
        }
        val s = ctx.dp(sizeDp / 2)
        f.addView(ctx.icon(icon, Color.WHITE, sizeDp / 2), FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        return f
    }

    private fun stepRow(label: String): StepRow {
        val root = ctx.hbox()
        val mark = FrameLayout(ctx)
        mark.background = GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(ctx.dp(1), withAlpha(Color.WHITE, 0x44))
        }
        val icon = ctx.icon(R.drawable.ic_check, Color.WHITE, 12)
        icon.alpha = 0f
        mark.addView(icon, FrameLayout.LayoutParams(ctx.dp(12), ctx.dp(12), Gravity.CENTER))
        root.add(mark, ctx.dp(20), ctx.dp(20), 0f, 0, 0, 10, 0)
        val l = ctx.tv(label, 13f, NX.MUTED, 500)
        root.add(l, 0, WRAP, 1f)
        val d = ctx.tv("", 12f, NX.GOOD, 600)
        root.addView(d)
        return StepRow(root, mark, icon, l, d)
    }

    private fun activate(i: Int) {
        val r = rows[i]
        if (r.done || r.spin != null) return
        r.label.setTextColor(NX.TEXT)
        r.mark.background = GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(ctx.dp(1), NX.CYAN)
        }
        r.icon.setImageResource(R.drawable.ic_rotate)
        r.icon.setColorFilter(NX.CYAN)
        r.icon.alpha = 1f
        r.spin = ObjectAnimator.ofFloat(r.icon, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun complete(i: Int, detail: String?) {
        val r = rows[i]
        if (r.done) {
            if (!detail.isNullOrBlank()) r.detail.text = detail
            return
        }
        r.done = true
        r.spin?.cancel()
        r.spin = null
        r.icon.rotation = 0f
        r.icon.setImageResource(R.drawable.ic_check)
        r.icon.setColorFilter(Color.WHITE)
        r.icon.alpha = 1f
        r.mark.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(NX.GOOD, NX.BLUE)).also {
            it.shape = GradientDrawable.OVAL
        }
        r.label.setTextColor(NX.TEXT)
        if (!detail.isNullOrBlank()) r.detail.text = detail
        r.mark.scaleX = 0.4f; r.mark.scaleY = 0.4f
        r.mark.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun metricView(m: Metric, delay: Long): View {
        val box = ctx.vbox()
        val top = ctx.hbox()
        top.add(ctx.tv(m.label, 12f, NX.TEXT2), 0, WRAP, 1f)
        top.addView(ctx.tv(m.value, 13.5f, NX.TEXT, 700))
        box.addView(top)
        if (m.fraction >= 0f) {
            val track = FrameLayout(ctx)
            track.background = rounded(withAlpha(Color.WHITE, 0x18), ctx.dpf(3f))
            val fill = View(ctx)
            fill.background = gradientRounded(withAlpha(m.color, 0xB0), m.color, ctx.dpf(3f))
            fill.pivotX = 0f
            fill.scaleX = 0f
            track.addView(fill, FrameLayout.LayoutParams(MATCH, MATCH))
            box.add(track, MATCH, ctx.dp(5), 0f, 0, 5, 0, 0)
            fill.animate().scaleX(m.fraction.coerceIn(0.02f, 1f)).setStartDelay(delay + 80).setDuration(650)
                .setInterpolator(OvershootInterpolator(0.8f)).start()
        }
        box.alpha = 0f
        box.translationY = ctx.dpf(10f)
        box.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320).start()
        return box
    }

    private fun popIn(v: View) {
        v.scaleX = 0f; v.scaleY = 0f; v.rotation = -50f
        v.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(120).setDuration(430)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    // ---------- Jendela ----------
    private fun present(c: LinearLayout) {
        val p = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = ctx.dp(14)
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { wm.addView(c, p) } catch (_: Exception) { processActive = false; return }
        card = c
        c.alpha = 0f
        c.translationY = -ctx.dpf(50f)
        c.scaleX = 0.9f
        c.scaleY = 0.9f
        c.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(430)
            .setInterpolator(OvershootInterpolator(1.25f)).start()
    }

    private fun scheduleClose(ms: Long) {
        closeTask?.let { handler.removeCallbacks(it) }
        val t = Runnable { dismiss() }
        closeTask = t
        handler.postDelayed(t, ms)
    }

    private fun stopAnimators() {
        shimmer?.cancel()
        shimmer = null
        rows.forEach { it.spin?.cancel(); it.spin = null }
    }

    private fun removeNow() {
        closeTask?.let { handler.removeCallbacks(it) }
        closeTask = null
        stopAnimators()
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
        processActive = false
    }
}
