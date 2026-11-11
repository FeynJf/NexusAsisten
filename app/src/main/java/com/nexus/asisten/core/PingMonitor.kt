package com.nexus.asisten.core

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Latensi nyata: waktu handshake TCP ke DNS publik. */
class PingMonitor {
    @Volatile var lastMs: Int = -1
    private var exec: ScheduledExecutorService? = null
    private val targets = listOf("8.8.8.8" to 53, "1.1.1.1" to 53, "1.1.1.1" to 443)

    fun start() {
        if (exec != null) return
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ lastMs = measure() }, 0, 2, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        exec?.shutdownNow()
        exec = null
    }

    private fun measure(): Int {
        for ((host, port) in targets) {
            try {
                val sock = Socket()
                val t0 = System.nanoTime()
                sock.connect(InetSocketAddress(host, port), 1500)
                val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                sock.close()
                return ms
            } catch (_: Exception) {
            }
        }
        return -1
    }

    /** Uji sinkron 5 sampel (panggil dari thread latar). */
    fun burst(): Triple<Int, Int, Int> {
        val samples = mutableListOf<Int>()
        repeat(5) {
            val m = measure()
            if (m >= 0) samples.add(m)
            Thread.sleep(150)
        }
        if (samples.isEmpty()) return Triple(-1, -1, 100)
        return Triple(samples.average().toInt(), (samples.maxOrNull() ?: 0) - (samples.minOrNull() ?: 0), (5 - samples.size) * 20)
    }
}
