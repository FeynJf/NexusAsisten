package com.nexus.asisten.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Process

data class GameApp(val pkg: String, val name: String, val icon: Drawable, val isGame: Boolean = false)

object GameRepo {

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    /** Game yang sudah ditambahkan, dalam urutan tersimpan. Aplikasi yang sudah dihapus dilewati. */
    fun savedGames(ctx: Context): List<GameApp> {
        val out = mutableListOf<GameApp>()
        for (pkg in Prefs.games) {
            val ic = icon(ctx, pkg) ?: continue
            out.add(GameApp(pkg, label(ctx, pkg), ic, true))
        }
        return out
    }

    fun add(pkg: String) {
        val l = Prefs.games.toMutableList()
        if (!l.contains(pkg)) { l.add(pkg); Prefs.games = l }
    }

    fun remove(pkg: String) {
        val l = Prefs.games.toMutableList()
        if (l.remove(pkg)) Prefs.games = l
    }

    /** Pindahkan game ke urutan paling depan (dipakai terakhir). */
    fun bump(pkg: String) {
        val l = Prefs.games.toMutableList()
        if (l.remove(pkg)) { l.add(0, pkg); Prefs.games = l }
    }

    @Suppress("DEPRECATION")
    fun isGameInfo(ai: ApplicationInfo): Boolean =
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    /** Semua aplikasi ber-launcher, game terdeteksi di urutan atas. */
    fun launchableApps(ctx: Context): List<GameApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val list = mutableListOf<GameApp>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                list.add(GameApp(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), isGameInfo(ai)))
            } catch (_: Exception) {
            }
        }
        return list.sortedWith(compareByDescending<GameApp> { it.isGame }.thenBy { it.name.lowercase() })
    }
}

/** Waktu bermain: data sistem (Usage Access) jika diizinkan, kalau tidak catatan internal. */
object PlayStats {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun aggregate(ctx: Context, days: Int): Map<String, Long> {
        if (!hasUsageAccess(ctx)) return emptyMap()
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryAndAggregateUsageStats(now - days * 86_400_000L, now)
                .mapValues { it.value.totalTimeInForeground }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun last30(ctx: Context, pkgs: List<String>): Map<String, Long> {
        val sys = aggregate(ctx, 30)
        return pkgs.associateWith { maxOf(sys[it] ?: 0L, Prefs.play30Internal(it)) }
    }

    fun total(ctx: Context, pkgs: List<String>): Map<String, Long> {
        val sys = aggregate(ctx, 365)
        return pkgs.associateWith { maxOf(sys[it] ?: 0L, Prefs.playTotalInternal(it)) }
    }
}
