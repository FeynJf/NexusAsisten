package com.nexus.asisten.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import com.nexus.asisten.BuildConfig
import com.nexus.asisten.MainActivity
import com.nexus.asisten.R
import com.nexus.asisten.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pembaruan lewat aplikasi: cek rilis terbaru di GitHub Releases (dibuat otomatis oleh GitHub Actions),
 * unduh APK, lalu pasang lewat PackageInstaller. Tidak perlu buka browser.
 */
object Updater {
    enum class Stage { IDLE, CHECKING, UPTODATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

    data class Info(val tag: String, val build: Int, val title: String, val notes: String, val url: String, val size: Long)

    @Volatile var stage: Stage = Stage.IDLE
        private set
    @Volatile var info: Info? = null
        private set
    @Volatile var progress: Int = 0
        private set
    @Volatile var error: String? = null
        private set
    /** Keterangan tambahan untuk status "sudah terbaru" (mis. belum ada rilis APK di GitHub). */
    @Volatile var note: String? = null
        private set
    @Volatile private var apk: File? = null

    const val ACTION_STATUS = "nx.UPDATE_STATUS"
    private const val CH = "nexus_update"
    private val exec = Executors.newSingleThreadExecutor()
    private val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10"
    const val NO_RELEASE = "Belum ada rilis APK di GitHub. Salin isi build.yml (di root repo) ke .github/workflows/build.yml di GitHub, lalu tunggu build selesai."
    /** Halaman edit workflow di GitHub, untuk memperbaiki penyebab "belum ada rilis". */
    val workflowEditUrl get() = "https://github.com/${BuildConfig.UPDATE_REPO}/edit/main/.github/workflows/build.yml"

    fun currentLabel() = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})"

    fun wantsCard() = when (stage) {
        Stage.AVAILABLE, Stage.DOWNLOADING, Stage.READY, Stage.INSTALLING -> true
        Stage.ERROR -> info != null
        else -> false
    }

    /** Cek otomatis paling sering tiap 6 jam. */
    fun autoCheck(ctx: Context) {
        if (!Prefs.autoUpdate) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < 6 * 3600_000L) return
        check(ctx, false)
    }

    private val SITE = "https://github.com/${BuildConfig.UPDATE_REPO}"
    private val APK_NAME = "NexusAsisten.apk"

    private fun buildOf(tag: String) = Regex("(\\d+)\\s*$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * Cara utama, TANPA API GitHub (jadi tidak kena batas permintaan yang sering menimpa jaringan seluler):
     * /releases/latest dialihkan ke /releases/tag/<tag>, dari situ nomor build dan URL APK diketahui.
     */
    private fun latestTagViaRedirect(): String? {
        val c = URL("$SITE/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "NexusAsisten")
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: return null
            val i = loc.indexOf("/releases/tag/")
            return if (i >= 0) java.net.URLDecoder.decode(loc.substring(i + 14).substringBefore('?'), "UTF-8") else null
        }
        if (code == 404) return null
        throw IOException("GitHub membalas kode $code")
    }

    /** Cadangan lewat API: rilis berAPK dengan nomor build tertinggi. */
    private fun latestViaApi(): Info? {
        val c = URL(api).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "NexusAsisten")
        val code = c.responseCode
        if (code == 404) return null
        if (code == 403 || code == 429) throw IOException("batas permintaan GitHub tercapai, coba lagi nanti")
        if (code != 200) throw IOException("GitHub membalas kode $code")
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        var best: Info? = null
        for (n in 0 until arr.length()) {
            val j = arr.getJSONObject(n)
            if (j.optBoolean("draft", false)) continue
            val tag = j.optString("tag_name")
            val build = buildOf(tag)
            var url = ""
            var size = 0L
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isEmpty()) continue
            if (best == null || build > best.build) best = Info(tag, build, j.optString("name", tag), j.optString("body", ""), url, size)
        }
        return best
    }

    /** Catatan rilis & ukuran APK (opsional; gagal pun tidak masalah). */
    private fun enrich(i: Info): Info = try {
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/${i.tag}").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "NexusAsisten")
        if (c.responseCode != 200) i else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            var size = i.size
            val assets = j.optJSONArray("assets")
            if (assets != null) for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.optString("name").endsWith(".apk")) { size = a.optLong("size"); break }
            }
            i.copy(title = j.optString("name", i.title), notes = j.optString("body", ""), size = size)
        }
    } catch (_: Exception) {
        i
    }

    fun check(ctx: Context, manual: Boolean = true) {
        if (stage == Stage.CHECKING || stage == Stage.DOWNLOADING || stage == Stage.INSTALLING) return
        stage = Stage.CHECKING
        error = null
        note = null
        exec.execute {
            try {
                var redirectError: Exception? = null
                val tag = try { latestTagViaRedirect() } catch (e: Exception) { redirectError = e; null }
                var best: Info? = null
                if (tag != null) {
                    val build = buildOf(tag)
                    best = Info(tag, build, "Nexus Asisten build $build", "", "$SITE/releases/download/$tag/$APK_NAME", 0L)
                    if (build > BuildConfig.BUILD_NUMBER) best = enrich(best)
                } else {
                    best = try { latestViaApi() } catch (e: Exception) { throw redirectError ?: e }
                    if (best == null && redirectError != null) throw redirectError
                }
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                val b = best
                if (b != null && b.build > BuildConfig.BUILD_NUMBER) {
                    info = b
                    stage = Stage.AVAILABLE
                } else {
                    info = null
                    if (b == null) note = NO_RELEASE
                    stage = Stage.UPTODATE
                }
            } catch (e: Exception) {
                if (manual) {
                    error = "Tidak bisa memeriksa pembaruan: ${e.message ?: "cek koneksi internet"}"
                    stage = Stage.ERROR
                } else {
                    stage = Stage.IDLE
                }
            }
        }
    }

    fun download(ctx: Context) {
        val i = info ?: return
        if (stage == Stage.DOWNLOADING || stage == Stage.INSTALLING) return
        stage = Stage.DOWNLOADING
        progress = 0
        error = null
        val app = ctx.applicationContext
        exec.execute {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                val out = File(dir, "NexusAsisten-${i.build}.apk")
                dir.listFiles()?.forEach { if (it != out) it.delete() }
                val c = URL(i.url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "NexusAsisten")
                if (c.responseCode != 200) throw IOException("Unduhan ditolak (kode ${c.responseCode})")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else i.size
                c.inputStream.use { input ->
                    FileOutputStream(out).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100)
                        }
                    }
                }
                if (i.size > 0 && out.length() != i.size) throw IOException("Berkas unduhan tidak lengkap")
                apk = out
                progress = 100
                stage = Stage.READY
                install(app)
            } catch (e: Exception) {
                error = "Unduhan gagal: ${e.message ?: "cek koneksi internet"}"
                stage = Stage.ERROR
            }
        }
    }

    fun install(ctx: Context) {
        val f = apk
        if (f == null || !f.exists()) {
            error = "Berkas pembaruan hilang, unduh ulang"
            stage = Stage.ERROR
            return
        }
        val app = ctx.applicationContext
        if (!Perms.installApps(app)) {
            error = "Izinkan \"Pasang aplikasi tidak dikenal\" untuk Nexus, lalu ketuk Pasang lagi"
            stage = Stage.READY
            Perms.openInstallApps(app)
            return
        }
        stage = Stage.INSTALLING
        error = null
        try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { input ->
                    s.openWrite("nexus.apk", 0, f.length()).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }
                val cb = PendingIntent.getBroadcast(
                    app, id, Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                s.commit(cb.intentSender)
            }
        } catch (e: Exception) {
            error = "Gagal memasang: ${e.message}"
            stage = Stage.ERROR
        }
    }

    internal fun onStatus(ctx: Context, status: Int, msg: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> { stage = Stage.IDLE; info = null }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { error = "Pemasangan dibatalkan"; stage = Stage.READY }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                error = "Tanda tangan APK berbeda dengan aplikasi terpasang. Uninstall sekali lalu pasang versi baru. (${msg ?: ""})"
                stage = Stage.ERROR
            }
            else -> { error = "Pemasangan gagal: ${msg ?: "kode $status"}"; stage = Stage.ERROR }
        }
    }

    internal fun notify(ctx: Context, title: String, text: String) {
        if (!Perms.postNotif(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Pembaruan", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            2002,
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Updater.notify(ctx, "Nexus Asisten diperbarui", "Ketuk untuk membuka versi ${Updater.currentLabel()}")
            return
        }
        if (intent.action != Updater.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(confirm)
                } catch (_: Exception) {
                    Updater.notify(ctx, "Pembaruan siap dipasang", "Ketuk untuk melanjutkan")
                }
            }
            return
        }
        Updater.onStatus(ctx, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
