package com.nexus.asisten.core

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.nexus.asisten.data.PlayStats

/**
 * Status & pembuka izin. Setiap pembuka punya beberapa jalur cadangan, karena sebagian ROM
 * (MIUI, ColorOS, OneUI, dll.) tidak punya layar pengaturan yang kita minta.
 */
object Perms {
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun usage(ctx: Context) = PlayStats.hasUsageAccess(ctx)
    fun writeSettings(ctx: Context) = Settings.System.canWrite(ctx)
    fun dnd(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun notifListener(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun postNotif(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun mic(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun installApps(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun battery(ctx: Context) =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun secure(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun dump(ctx: Context) =
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /** Android 13+ memblokir "izin sensitif" untuk aplikasi yang dipasang di luar Play Store. */
    fun restrictedLikely(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val src = ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            src != "com.android.vending"
        } catch (_: Exception) {
            true
        }
    }

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    /** Coba intent satu per satu sampai ada yang berhasil dibuka. */
    private fun openFirst(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun openAppInfo(ctx: Context) =
        openFirst(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)))

    fun openOverlay(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(ctx)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openUsage(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri(ctx)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openWrite(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(ctx)),
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openDnd(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openNotifListener(ctx: Context): Boolean {
        val comp = ComponentName(ctx, NexusNotificationListener::class.java).flattenToString()
        return openFirst(
            ctx,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
        )
    }

    fun openInstallApps(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(ctx)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openBattery(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openAppNotifications(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
    )

    fun openWifiPanel(ctx: Context) = openFirst(
        ctx,
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
        Intent(Settings.ACTION_WIFI_SETTINGS)
    )

    fun openPrivateDns(ctx: Context) = openFirst(
        ctx,
        Intent("android.settings.PRIVATE_DNS_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )
}
