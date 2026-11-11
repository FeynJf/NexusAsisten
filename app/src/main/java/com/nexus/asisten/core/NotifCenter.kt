package com.nexus.asisten.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object NotifCenter {
    @Volatile var count = 0
}

/** Menghitung notifikasi masuk supaya titik indikator di overlay bisa muncul. */
class NexusNotificationListener : NotificationListenerService() {
    private fun recount() {
        NotifCenter.count = try {
            activeNotifications.count { !it.isOngoing && it.packageName != packageName }
        } catch (_: Exception) {
            0
        }
    }

    override fun onListenerConnected() = recount()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()
}
