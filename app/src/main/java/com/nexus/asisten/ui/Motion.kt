package com.nexus.asisten.ui

import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

/**
 * Mesin animasi sendiri berbasis Choreographer.
 *
 * Kenapa tidak pakai ViewPropertyAnimator/ValueAnimator? Keduanya tunduk pada "Skala durasi animator"
 * di Opsi Pengembang dan mode Hemat Baterai. Banyak gamer mematikannya demi performa, akibatnya semua
 * animasi langsung loncat ke akhir dan panel terlihat kaku. Motion membaca waktu frame langsung dari sistem,
 * jadi animasi Nexus selalu berjalan halus, apa pun pengaturan HP.
 */
object Motion {

    // ---------- Kurva gerak ----------
    fun linear(t: Float) = t
    fun outCubic(t: Float): Float { val x = 1f - t; return 1f - x * x * x }
    fun inCubic(t: Float) = t * t * t
    fun inOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).toDouble().pow(3.0).toFloat() / 2f
    fun outExpo(t: Float): Float = if (t >= 1f) 1f else 1f - 2.0.pow(-10.0 * t).toFloat()
    fun outBack(t: Float, s: Float = 1.7f): Float { val x = t - 1f; return x * x * ((s + 1f) * x + s) + 1f }

    /** Pegas teredam: melampaui sedikit (~9%) lalu mengendap. Terasa "hidup" tanpa memantul berlebihan. */
    fun spring(t: Float): Float = if (t >= 1f) 1f else (1.0 - exp(-7.0 * t) * cos(9.0 * t)).toFloat()

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---------- Animasi sekali jalan ----------
    class Handle internal constructor(
        private val dur: Long,
        private val delay: Long,
        private val frame: (Float) -> Unit,
        private val end: (() -> Unit)?
    ) : Choreographer.FrameCallback {
        private var t0 = -1L
        private var done = false

        internal fun begin(): Handle {
            Choreographer.getInstance().postFrameCallback(this)
            return this
        }

        fun cancel() {
            done = true
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (done) return
            if (t0 < 0) t0 = frameTimeNanos
            val el = (frameTimeNanos - t0) / 1_000_000L - delay
            if (el < 0) {
                frame(0f)
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val t = if (dur <= 0) 1f else (el.toFloat() / dur).coerceIn(0f, 1f)
            frame(t)
            if (t >= 1f) {
                done = true
                end?.invoke()
            } else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Jalankan [frame] dengan nilai 0..1 selama [durMs], setelah menunggu [delayMs]. Panggil dari thread utama. */
    fun run(durMs: Long, delayMs: Long = 0L, end: (() -> Unit)? = null, frame: (Float) -> Unit): Handle =
        Handle(durMs, delayMs, frame, end).begin()

    // ---------- Loop tanpa akhir ----------
    class Loop internal constructor(
        private val minStepMs: Long,
        private val tick: (Long) -> Unit
    ) : Choreographer.FrameCallback {
        private var t0 = -1L
        private var last = -1000L
        private var on = true

        internal fun begin(): Loop {
            Choreographer.getInstance().postFrameCallback(this)
            return this
        }

        fun cancel() {
            on = false
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!on) return
            if (t0 < 0) t0 = frameTimeNanos
            val ms = (frameTimeNanos - t0) / 1_000_000L
            if (ms - last >= minStepMs) {
                last = ms
                tick(ms)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** [tick] menerima milidetik sejak mulai. [minStepMs] membatasi laju (33 = ~30 fps, hemat saat game berjalan). */
    fun loop(minStepMs: Long = 0L, tick: (Long) -> Unit): Loop = Loop(minStepMs, tick).begin()
}
