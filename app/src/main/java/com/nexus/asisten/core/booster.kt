package com.nexus.asisten.core

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.provider.Settings
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.Prefs
import kotlin.math.roundToInt

/** Semua aksi yang benar-benar mengubah perilaku perangkat. Panggil dari thread latar. */
object Booster {
    data class CleanResult(val killed: Int, val freedMb: Long)

    fun supportedRefresh(ctx: Context): List<Int> {
        val d = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = d.getDisplay(0) ?: return listOf(60)
        return display.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted().ifEmpty { listOf(60) }
    }

    fun cleanMemory(ctx: Context, keep: Set<String>): CleanResult {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val before = SystemStats.ramMb(ctx).first
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        var killed = 0
        for (ai in pm.getInstalledApplications(0)) {
            val pkg = ai.packageName
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem || pkg == ctx.packageName || pkg == home || pkg in keep) continue
            try {
                am.killBackgroundProcesses(pkg)
                killed++
            } catch (_: Exception) {
            }
        }
        try { Thread.sleep(600) } catch (_: InterruptedException) {}
        val after = SystemStats.ramMb(ctx).first
        return CleanResult(killed, maxOf(0L, after - before))
    }

    private fun targetRefresh(ctx: Context, p: GameProfile, mode: Int): Int {
        val opts = supportedRefresh(ctx)
        return when (mode) {
            GameProfile.MODE_SAVER -> opts.first()
            GameProfile.MODE_PRO -> opts.last()
            else -> if (p.refreshHz > 0) opts.minByOrNull { Math.abs(it - p.refreshHz) } ?: 0 else 0
        }
    }

    /** Terapkan laju refresh. Butuh izin "Ubah pengaturan sistem"; hasilnya tergantung ROM. */
    fun applyRefresh(ctx: Context, hz: Int): Boolean {
        if (hz <= 0 || !Perms.writeSettings(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            if (Prefs.origPeak == -2f) {
                Prefs.origPeak = Settings.System.getFloat(cr, "peak_refresh_rate", -1f)
                Prefs.origMin = Settings.System.getFloat(cr, "min_refresh_rate", -1f)
            }
            Settings.System.putFloat(cr, "peak_refresh_rate", hz.toFloat())
            Settings.System.putFloat(cr, "min_refresh_rate", hz.toFloat())
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setDnd(ctx: Context, on: Boolean): Boolean {
        if (!Perms.dnd(ctx)) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (on) {
            if (Prefs.origDnd == -1) Prefs.origDnd = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } else {
            val prev = Prefs.origDnd
            nm.setInterruptionFilter(if (prev > 0) prev else NotificationManager.INTERRUPTION_FILTER_ALL)
            Prefs.origDnd = -1
        }
        return true
    }

    fun dndOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Terapkan profil game dengan mode efektif (bisa lebih rendah saat perangkat panas). */
    fun apply(ctx: Context, pkg: String, p: GameProfile, effectiveMode: Int, clean: Boolean) {
        if (clean && effectiveMode == GameProfile.MODE_PRO) cleanMemory(ctx, setOf(pkg))
        applyRefresh(ctx, targetRefresh(ctx, p, effectiveMode))
        val wantDnd = p.blockCalls || p.blockNotif || Prefs.focusMode
        if (wantDnd) setDnd(ctx, true)
    }

    /** Pulihkan pengaturan sistem yang kita ubah. */
    fun restore(ctx: Context) {
        try {
            if (Prefs.origPeak != -2f && Perms.writeSettings(ctx)) {
                val cr = ctx.contentResolver
                Settings.System.putFloat(cr, "peak_refresh_rate", maxOf(Prefs.origPeak, 0f))
                Settings.System.putFloat(cr, "min_refresh_rate", maxOf(Prefs.origMin, 0f))
            }
        } catch (_: Exception) {
        }
        Prefs.origPeak = -2f
        Prefs.origMin = -2f
        if (Prefs.origDnd != -1) setDnd(ctx, false)
    }
}
