package com.nexus.asisten.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import com.nexus.asisten.data.GameRepo

/**
 * Supaya Nexus tidak bertabrakan dengan asisten game bawaan HP / aplikasi lain
 * (Game Turbo, Game Space, Game Booster, Game Genie, dst.).
 */
object GameConflict {
    private val known = setOf(
        "com.samsung.android.game.gametools", "com.samsung.android.game.gos", "com.samsung.android.game.gamehome",
        "com.miui.securitycenter", "com.miui.gameassistant",
        "com.oplus.games", "com.coloros.gamespaceui", "com.oneplus.gamespace",
        "com.vivo.gamewatch", "com.vivo.game",
        "com.asus.gamewidget", "com.asus.armourycrate",
        "com.huawei.gameassistant", "com.hihonor.gameassistant",
        "com.google.android.play.games"
    )
    private val gameWords = listOf("assist", "space", "turbo", "boost", "gos", "tools", "mode", "genie", "watch", "dock", "optimiz")

    private fun looksLikeAssistant(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("game") && gameWords.any { p.contains(it) }
    }

    fun isAssistant(pkg: String) = pkg in known || looksLikeAssistant(pkg)

    /** Asisten game lain yang terpasang (nama tampilan). */
    fun installed(ctx: Context): List<String> {
        val pm = ctx.packageManager
        return try {
            pm.getInstalledApplications(0)
                .filter { it.packageName != ctx.packageName && isAssistant(it.packageName) }
                .map { GameRepo.label(ctx, it.packageName) }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun imePackage(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    } catch (_: Exception) { null }

    private fun dialerPackage(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    } catch (_: Exception) { null }

    private fun accessibilityPackages(ctx: Context): Set<String> = try {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.contains('/') }.map { it.substringBefore('/') }.toSet()
    } catch (_: Exception) { emptySet() }

    /** Paket yang TIDAK boleh dihentikan saat membersihkan RAM. */
    fun protectedPackages(ctx: Context): Set<String> {
        val out = HashSet<String>()
        imePackage(ctx)?.let { out.add(it) }
        dialerPackage(ctx)?.let { out.add(it) }
        out.addAll(accessibilityPackages(ctx))
        try { out.addAll(NotificationManagerCompat.getEnabledListenerPackages(ctx)) } catch (_: Exception) {}
        return out
    }

    fun isProtected(pkg: String, extra: Set<String>) = isAssistant(pkg) || pkg in extra

    /** Aktivitas milik asisten lain / sistem tidak dianggap "game ditutup". */
    fun ignorableForeground(ctx: Context, pkg: String): Boolean =
        pkg == ctx.packageName || pkg == "com.android.systemui" || isAssistant(pkg) || pkg == imePackage(ctx)

    fun isSystemApp(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
