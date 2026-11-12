package com.nexus.asisten.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.animation.ValueAnimator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import android.view.animation.TranslateAnimation
import android.widget.TextView
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.nexus.asisten.data.Prefs
import java.lang.ref.WeakReference

/** Animasi & notifikasi kecil setiap kali sesuatu diketuk. */
object Fx {
    const val INFO = 0
    const val OK = 1
    const val WARN = 2

    private var live: WeakReference<View>? = null

    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Kapsul notifikasi bergaya kaca gelap. Dipakai di dalam aplikasi maupun di overlay. */
    fun bannerView(ctx: Context, msg: String, kind: Int): View {
        val dotColor = when (kind) { OK -> NX.GOOD; WARN -> NX.WARN; else -> NX.CYAN }
        val row = ctx.hbox()
        row.setPadding(ctx.dp(14), ctx.dp(11), ctx.dp(18), ctx.dp(11))
        row.background = rounded(Color.parseColor("#F0141C33"), ctx.dpf(26f), 0x40FFFFFF, 1)
        val dot = View(ctx)
        dot.background = circle(dotColor)
        row.add(dot, ctx.dp(8), ctx.dp(8), 0f, 0, 0, 10, 0)
        val t = ctx.tv(msg, 13.5f, NX.TEXT, 500)
        t.maxLines = 3
        t.maxWidth = ctx.resources.displayMetrics.widthPixels - ctx.dp(80)
        row.addView(t)
        row.elevation = ctx.dpf(10f)
        return row
    }

    fun animateIn(v: View) {
        v.alpha = 0f
        v.translationY = -v.context.dpf(24f)
        v.scaleX = 0.9f
        v.scaleY = 0.9f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(280).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    fun animateOut(v: View, end: () -> Unit) {
        v.animate().alpha(0f).translationY(-v.context.dpf(16f)).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).withEndAction(end).start()
    }

    /** Anak-anak [container] muncul berurutan dari bawah sambil memudar masuk. */
    fun stagger(container: ViewGroup, base: Long = 0L, step: Long = 55L) {
        val dy = container.context.dpf(26f)
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            v.alpha = 0f
            v.translationY = dy
            v.animate().alpha(1f).translationY(0f).setStartDelay(base + i * step).setDuration(380)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    /** Item daftar (RecyclerView/ListView) masuk berurutan saat pertama tampil. */
    fun listAnim(vg: ViewGroup) {
        val a = AnimationSet(true)
        a.addAnimation(AlphaAnimation(0f, 1f))
        a.addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0.3f, Animation.RELATIVE_TO_SELF, 0f))
        a.duration = 380
        a.interpolator = DecelerateInterpolator()
        vg.layoutAnimation = LayoutAnimationController(a, 0.07f)
    }

    /** Angka menghitung naik dari 0 ke [to]. */
    fun countUp(t: TextView, to: Int, suffix: String = "", duration: Long = 700L) {
        val a = ValueAnimator.ofInt(0, to)
        a.duration = duration
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { t.text = "${it.animatedValue}$suffix" }
        a.start()
    }

    /** Tampilkan notifikasi singkat di bagian atas layar Activity (tidak berpengaruh bila bukan Activity). */
    fun say(ctx: Context, msg: String, kind: Int = INFO) {
        val act = activityOf(ctx) ?: return
        act.runOnUiThread {
            if (act.isFinishing) return@runOnUiThread
            val host = act.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            live?.get()?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
            val v = bannerView(act, msg, kind)
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.topMargin = act.dp(12)
            host.addView(v, lp)
            live = WeakReference(v)
            animateIn(v)
            v.postDelayed({ animateOut(v) { (v.parent as? ViewGroup)?.removeView(v) } }, 2000)
        }
    }
}

/** Efek tekan: mengecil sedikit saat disentuh, membal saat dilepas, plus getar halus. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFx(scale: Float = 0.95f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Prefs.haptic) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                v.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false
    }
}
