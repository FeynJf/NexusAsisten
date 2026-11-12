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

    fun poll(): String? {
        val now = System.currentTimeMillis()
        try {
            val ev = usm.queryEvents(lastQuery, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    !(Prefs.compatMode && GameConflict.ignorableForeground(ctx, e.packageName))
                ) current = e.packageName
            }
        } catch (_: Exception) {
        }
        lastQuery = now - 500
        return current
    }
}
