package com.nexus.asisten.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.nexus.asisten.core.Perms
import com.nexus.asisten.data.Prefs

/**
 * Satu pintu untuk semua izin. Menangani kasus yang biasanya gagal:
 *  - izin yang hanya bisa lewat ADB (ditampilkan perintahnya + tombol salin),
 *  - "setelan terbatas" Android 13+ untuk aplikasi di luar Play Store,
 *  - izin runtime yang sudah ditolak permanen (diarahkan ke Info Aplikasi),
 *  - layar pengaturan yang tidak ada di ROM tertentu (ada jalur cadangan di Perms).
 */
data class PermItem(
    val id: String,
    val name: String,
    val desc: String,
    val ok: Boolean,
    val required: Boolean = false,
    val adb: String? = null,
    val restricted: Boolean = false,
    val runtime: String? = null,
    val open: (Context) -> Unit = {}
)

const val ADB_PKG = "com.nexus.asisten"

fun Activity.permItems(): List<PermItem> = listOf(
    PermItem("overlay", "Tampil di atas aplikasi lain", "Wajib untuk panel & pegangan Asisten Game.",
        Perms.overlay(this), required = true, open = { Perms.openOverlay(it) }),
    PermItem("usage", "Akses penggunaan", "Mendeteksi game yang sedang dibuka & waktu bermain.",
        Perms.usage(this), open = { Perms.openUsage(it) }),
    PermItem("write", "Ubah pengaturan sistem", "Kecerahan, kunci rotasi, dan refresh rate.",
        Perms.writeSettings(this), open = { Perms.openWrite(it) }),
    PermItem("dnd", "Jangan Ganggu", "Blokir panggilan & notifikasi saat bermain.",
        Perms.dnd(this), open = { Perms.openDnd(it) }),
    PermItem("notif_listener", "Akses notifikasi", "Titik indikator notifikasi di overlay.",
        Perms.notifListener(this), restricted = true, open = { Perms.openNotifListener(it) }),
    PermItem("post_notif", "Tampilkan notifikasi", "Notifikasi Asisten aktif & pembaruan.",
        Perms.postNotif(this), runtime = Manifest.permission.POST_NOTIFICATIONS, open = { Perms.openAppNotifications(it) }),
    PermItem("mic", "Mikrofon", "Hanya untuk rekam layar dengan suara mikrofon.",
        Perms.mic(this), runtime = Manifest.permission.RECORD_AUDIO, open = { Perms.openAppInfo(it) }),
    PermItem("install", "Pasang aplikasi tidak dikenal", "Agar pembaruan bisa dipasang dari dalam aplikasi.",
        Perms.installApps(this), open = { Perms.openInstallApps(it) }),
    PermItem("battery", "Tanpa pembatasan baterai", "Mencegah sistem mematikan overlay saat bermain lama.",
        Perms.battery(this), open = { Perms.openBattery(it) }),
    PermItem("dump", "FPS akurat (ADB)", "Satu kali lewat ADB. Tanpa ini FPS berupa estimasi (~).",
        Perms.dump(this), adb = "adb shell pm grant $ADB_PKG android.permission.DUMP"),
    PermItem("secure", "DNS cepat (ADB)", "Satu kali lewat ADB. Untuk mengatur DNS pribadi saat bermain.",
        Perms.secure(this), adb = "adb shell pm grant $ADB_PKG android.permission.WRITE_SECURE_SETTINGS")
)

fun Context.copyText(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Fx.say(this, "Disalin ke papan klip", Fx.OK)
}

fun Activity.handlePerm(p: PermItem) {
    if (p.ok) {
        Fx.say(this, "${p.name} sudah aktif", Fx.OK)
        return
    }
    when {
        p.adb != null -> AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage("Izin ini tidak bisa diberikan dari pengaturan biasa. Sambungkan HP ke komputer (USB debugging), lalu jalankan:\n\n${p.adb}")
            .setPositiveButton("Salin perintah") { _, _ -> copyText("adb", p.adb) }
            .setNegativeButton("Tutup", null)
            .show()

        p.runtime != null -> {
            if (!Prefs.asked(p.id)) {
                Prefs.setAsked(p.id)
                ActivityCompat.requestPermissions(this, arrayOf(p.runtime), 31)
            } else {
                // Sudah pernah diminta: Android tidak menampilkan dialog lagi, buka pengaturan langsung.
                Fx.say(this, "Aktifkan ${p.name} lalu kembali", Fx.INFO)
                p.open(this)
            }
        }

        p.restricted && Perms.restrictedLikely(this) -> AlertDialog.Builder(this)
            .setTitle("Izinkan setelan terbatas")
            .setMessage(
                "Android 13+ memblokir izin ini untuk aplikasi yang dipasang di luar Play Store.\n\n" +
                    "1. Buka Info Aplikasi Nexus Asisten\n" +
                    "2. Ketuk menu ⋮ di pojok kanan atas\n" +
                    "3. Pilih \"Izinkan setelan terbatas\"\n" +
                    "4. Kembali ke sini lalu aktifkan izinnya"
            )
            .setPositiveButton("Buka Info Aplikasi") { _, _ -> Perms.openAppInfo(this) }
            .setNeutralButton("Langsung ke izin") { _, _ -> p.open(this) }
            .setNegativeButton("Nanti", null)
            .show()

        else -> {
            Fx.say(this, "Aktifkan ${p.name} lalu kembali", Fx.INFO)
            p.open(this)
        }
    }
}
