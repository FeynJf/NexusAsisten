package com.nexus.asisten.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.nexus.asisten.data.Prefs

/** Mendeteksi aplikasi yang sedang di depan lewat UsageEvents (butuh Usage Access). */
class ForegroundDetector(private val ctx: Context, initial: String?) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastQuery = System.currentTimeMillis() - 5000
    var current: String? = initial
        private set

    /**
     * Layar bantu yang muncul DI ATAS game tidak dianggap "keluar dari game": halaman Nexus selain beranda
     * (izin tangkap layar, Album, Pengaturan, dll.), dialog izin sistem, dan SystemUI. Tanpa ini pegangan
     * overlay hilang setelah cuplikan layar atau dialog izin.
     */
    private fun skip(e: UsageEvents.Event): Boolean {
        val pkg = e.packageName ?: return true
        if (pkg == "com.android.systemui" || pkg.endsWith(".permissioncontroller")) return true
        if (pkg == ctx.packageName) return e.className?.endsWith(".MainActivity") != true
        return false
    }

    fun poll(): String? {
        val now = System.currentTimeMillis()
        try {
            val ev = usm.queryEvents(lastQuery, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && !skip(e) &&
                    !(Prefs.compatMode && GameConflict.ignorableForeground(ctx, e.packageName))
                ) current = e.packageName
            }
        } catch (_: Exception) {
        }
        lastQuery = now - 500
        return current
    }
}
