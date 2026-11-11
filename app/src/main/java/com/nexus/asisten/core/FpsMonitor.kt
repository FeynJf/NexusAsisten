package com.nexus.asisten.core

import android.view.Choreographer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * FPS:
 *  - Akurat (exact = true) bila izin DUMP diberikan lewat ADB, dibaca dari SurfaceFlinger layer game.
 *  - Jika tidak, jatuh ke estimasi Choreographer (laju refresh layar), ditandai exact = false.
 */
class FpsMonitor {
    @Volatile var fps: Int = 0
    @Volatile var exact: Boolean = false

    private var running = false
    private var frames = 0
    private var lastT = 0L
    @Volatile private var sfOk = true
    private var layer: String? = null
    private var exec: ScheduledExecutorService? = null
    @Volatile private var pkg: String? = null

    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frames++
            if (lastT == 0L) lastT = frameTimeNanos
            val dt = frameTimeNanos - lastT
            if (dt >= 1_000_000_000L) {
                if (!sfOk || !exact) {
                    fps = Math.round(frames * 1e9 / dt).toInt()
                }
                frames = 0
                lastT = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(gamePkg: String) {
        pkg = gamePkg
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(cb)
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ pollSurfaceFlinger() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        running = false
        exec?.shutdownNow()
        exec = null
    }

    private fun sh(vararg args: String): String? = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (out.contains("Permission Denial") || out.contains("Permission denied")) null else out
    } catch (_: Exception) {
        null
    }

    private fun pollSurfaceFlinger() {
        if (!sfOk) return
        val p = pkg ?: return
        try {
            var l = layer
            if (l == null) {
                val list = sh("dumpsys", "SurfaceFlinger", "--list")
                if (list == null) { sfOk = false; exact = false; return }
                val cands = list.lines().map { it.trim() }.filter { it.contains(p) && !it.startsWith("Background for") }
                l = cands.firstOrNull { it.contains("SurfaceView") } ?: cands.firstOrNull()
                layer = l
                if (l == null) { exact = false; return }
            }
            val out = sh("dumpsys", "SurfaceFlinger", "--latency", l)
            if (out == null) { sfOk = false; exact = false; return }
            val ts = out.lines().drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) parts[1].toLongOrNull() else null
            }.filter { it > 0L && it != Long.MAX_VALUE }
            if (ts.size < 2) { layer = null; exact = false; return }
            val last = ts.maxOrNull() ?: return
            fps = ts.count { it > last - 1_000_000_000L }
            exact = true
        } catch (_: Exception) {
            exact = false
        }
    }
}
