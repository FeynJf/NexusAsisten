package com.nexus.asisten.core

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.nexus.asisten.data.PlayStats

object Perms {
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun usage(ctx: Context) = PlayStats.hasUsageAccess(ctx)
    fun writeSettings(ctx: Context) = Settings.System.canWrite(ctx)
    fun dnd(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun notifListener(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun open(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    fun openOverlay(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    fun openUsage(ctx: Context) = open(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openWrite(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    fun openDnd(ctx: Context) = open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    fun openNotifListener(ctx: Context) = open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    fun openWifiPanel(ctx: Context) = open(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
}
