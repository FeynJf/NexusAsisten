package com.nexus.asisten.core

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.provider.Settings
import com.nexus.asisten.data.GameProfile
import com.nexus.asisten.data.Prefs
import kotlin.math.roundToInt

/** Semua aksi yang benar-benar mengubah perilaku perangkat. Panggil dari thread latar. */
object Booster {
    data class CleanResult(val killed: Int, val freedMb: Long, val beforeMb: Long = 0, val afterMb: Long = 0, val totalMb: Long = 0)

    fun supportedRefresh(ctx: Context): List<Int> {
        val d = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = d.getDisplay(0) ?: return listOf(60)
        return display.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted().ifEmpty { listOf(60) }
    }

    /** Paket sistem yang tidak boleh disentuh sama sekali (SystemUI, telepon, keyboard, layanan Google, dll.). */
    private val criticalSystem = Regex(
        "systemui|\\.phone$|telecom|bluetooth|\\.nfc|settings|providers|permissioncontroller|packageinstaller|" +
            "launcher|inputmethod|keyboard|webview|\\.gms|\\.gsf|vending|wallpaper|dialer|contacts|mms|sms|" +
            "accessibility|print|cellbroadcast|emergency|stk|wifi|netspeed|android$"
    )

    /** Daftar paket yang aman dihentikan proses latarnya. */
    private fun cleanTargets(ctx: Context, keep: Set<String>): List<String> {
        val pm = ctx.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        // Jangan sentuh asisten game lain, keyboard, telepon, aksesibilitas, & pendengar notifikasi.
        val shield = if (Prefs.compatMode) GameConflict.protectedPackages(ctx) else emptySet()
        val out = ArrayList<String>()
        for (ai in pm.getInstalledApplications(0)) {
            val pkg = ai.packageName
            if (pkg == ctx.packageName || pkg == home || pkg == ime || pkg in keep) continue
            if (Prefs.compatMode && GameConflict.isProtected(pkg, shield)) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val ok = when {
                !isSystem -> true
                updatedSystem && !criticalSystem.containsMatchIn(pkg) -> true
                // Aplikasi sistem yang punya ikon peluncur (Kamera, Galeri, Chrome, dll.) aman dimulai ulang.
                pm.getLaunchIntentForPackage(pkg) != null && !criticalSystem.containsMatchIn(pkg) -> true
                else -> false
            }
            if (ok) out.add(pkg)
        }
        return out
    }

    /**
     * Hentikan proses latar aplikasi lain lalu ukur RAM bebas sebelum & sesudah (angka nyata).
     * Dua putaran, karena sebagian aplikasi punya beberapa proses/layanan yang baru berhenti di putaran kedua.
     */
    fun cleanMemory(ctx: Context, keep: Set<String>): CleanResult {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val (before, total) = SystemStats.ramMb(ctx)
        val targets = try { cleanTargets(ctx, keep) } catch (_: Exception) { emptyList() }
        var killed = 0
        repeat(2) { pass ->
            for (pkg in targets) {
                try {
                    am.killBackgroundProcesses(pkg)
                    if (pass == 0) killed++
                } catch (_: Exception) {
                }
            }
            try { Thread.sleep(450) } catch (_: InterruptedException) {}
        }
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        val after = SystemStats.ramMb(ctx).first
        return CleanResult(killed, maxOf(0L, after - before), before, after, total)
    }

    fun targetRefresh(ctx: Context, p: GameProfile, mode: Int): Int {
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
            Prefs.lastHz = hz.toFloat()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Nyalakan/matikan Jangan Ganggu. Bila DND sudah dinyalakan pengguna atau asisten lain,
     * Nexus tidak menimpanya dan tidak mematikannya sendiri (force = ketukan manual pengguna).
     */
    fun setDnd(ctx: Context, on: Boolean, force: Boolean = false): Boolean {
        if (!Perms.dnd(ctx)) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (on) {
            val cur = nm.currentInterruptionFilter
            if (cur == NotificationManager.INTERRUPTION_FILTER_ALL || cur == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                Prefs.origDnd = if (cur > 0) cur else NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                Prefs.dndOurs = true
            }
        } else if (Prefs.dndOurs || force) {
            val prev = Prefs.origDnd
            nm.setInterruptionFilter(if (prev > 0) prev else NotificationManager.INTERRUPTION_FILTER_ALL)
            Prefs.dndOurs = false
            Prefs.origDnd = -1
        }
        return true
    }

    fun dndOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private var wifiLock: WifiManager.WifiLock? = null

    /** Kunci Wi-Fi latensi rendah selama bermain (efek nyata pada ping/lag di Wi-Fi). */
    @Suppress("DEPRECATION")
    @Synchronized
    fun holdWifi(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "nexus:game")
                        .also { it.setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else {
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
            }
        } catch (_: Exception) {
        }
    }

    private val dnsHosts = listOf(null, "dns.google", "one.one.one.one")

    /** DNS pribadi (butuh WRITE_SECURE_SETTINGS lewat ADB). choice: 0 = jangan ubah, 1 = Google, 2 = Cloudflare. */
    fun applyDns(ctx: Context, choice: Int): Boolean {
        val host = dnsHosts.getOrNull(choice) ?: return false
        if (!Perms.secure(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            if (Prefs.origDnsMode == null) {
                Prefs.origDnsMode = Settings.Global.getString(cr, "private_dns_mode") ?: "opportunistic"
                Prefs.origDnsHost = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
            }
            Settings.Global.putString(cr, "private_dns_specifier", host)
            Settings.Global.putString(cr, "private_dns_mode", "hostname")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreDns(ctx: Context) {
        val mode = Prefs.origDnsMode ?: return
        try {
            if (Perms.secure(ctx)) {
                val cr = ctx.contentResolver
                Settings.Global.putString(cr, "private_dns_mode", mode)
                Settings.Global.putString(cr, "private_dns_specifier", Prefs.origDnsHost ?: "")
            }
        } catch (_: Exception) {
        }
        Prefs.origDnsMode = null
        Prefs.origDnsHost = null
    }

    /**
     * Sentuhan ultra: mempercepat jeda "tekan & tahan" sistem (long_press_timeout) agar sentuhan lebih responsif.
     * Butuh WRITE_SECURE_SETTINGS (sekali lewat ADB). Mengembalikan false bila izin belum ada.
     */
    fun applyTouch(ctx: Context, ultra: Boolean): Boolean {
        if (!Perms.secure(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            if (ultra) {
                if (Prefs.origLongPress == -1) Prefs.origLongPress = Settings.Secure.getInt(cr, "long_press_timeout", 400)
                Settings.Secure.putInt(cr, "long_press_timeout", 250)
            } else if (Prefs.origLongPress != -1) {
                Settings.Secure.putInt(cr, "long_press_timeout", Prefs.origLongPress)
                Prefs.origLongPress = -1
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Terapkan profil game dengan mode efektif (bisa lebih rendah saat perangkat panas). */
    fun apply(ctx: Context, pkg: String, p: GameProfile, effectiveMode: Int, clean: Boolean) {
        if (clean && effectiveMode == GameProfile.MODE_PRO) cleanMemory(ctx, setOf(pkg))
        applyRefresh(ctx, targetRefresh(ctx, p, effectiveMode))
        val wantDnd = p.blockCalls || p.blockNotif || Prefs.focusMode
        if (wantDnd) setDnd(ctx, true)
        if (Prefs.dnsChoice > 0) applyDns(ctx, Prefs.dnsChoice)
        applyTouch(ctx, p.ultraTouch)
    }

    /** Pulihkan pengaturan sistem yang kita ubah. */
    fun restore(ctx: Context) {
        try {
            if (Prefs.origPeak != -2f && Perms.writeSettings(ctx)) {
                val cr = ctx.contentResolver
                // Hanya pulihkan bila nilainya masih yang kita atur (kalau asisten lain mengubahnya, biarkan).
                val cur = Settings.System.getFloat(cr, "peak_refresh_rate", -1f)
                if (Prefs.lastHz < 0f || cur == Prefs.lastHz) {
                    Settings.System.putFloat(cr, "peak_refresh_rate", maxOf(Prefs.origPeak, 0f))
                    Settings.System.putFloat(cr, "min_refresh_rate", maxOf(Prefs.origMin, 0f))
                }
            }
        } catch (_: Exception) {
        }
        Prefs.origPeak = -2f
        Prefs.origMin = -2f
        Prefs.lastHz = -1f
        if (Prefs.dndOurs) setDnd(ctx, false)
        restoreDns(ctx)
        applyTouch(ctx, false)
    }
}
