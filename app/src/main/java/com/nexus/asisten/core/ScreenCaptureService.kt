package com.nexus.asisten.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexus.asisten.R
import com.nexus.asisten.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cuplikan & rekam layar lewat MediaProjection.
 *
 * Izin layar hanya diminta SEKALI per sesi overlay: proyeksi dan VirtualDisplay dipertahankan selama
 * sesi (surface dilepas saat tidak dipakai, jadi tanpa beban). Cuplikan berikutnya langsung diambil
 * tanpa dialog izin dan tanpa memindahkan game ke belakang.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_INIT = "nx.CAP_INIT"
        const val ACTION_SHOT = "nx.SHOT"
        const val ACTION_REC = "nx.REC"
        const val ACTION_PAUSE = "nx.REC_PAUSE"
        const val ACTION_RESUME = "nx.REC_RESUME"
        const val ACTION_STOP = "nx.REC_STOP"
        const val ACTION_END = "nx.CAP_END"
        const val EXTRA_THEN = "then"
        const val DIR_IMAGES = "Pictures/NexusAsisten"
        const val DIR_VIDEOS = "Movies/NexusAsisten"

        /** Sisi pendek video untuk tiap pilihan resolusi (px). */
        val RES_SHORT = intArrayOf(1440, 1080, 750, 540)
        val RES_LABELS = listOf("2K", "1K", "750p", "540p")

        @Volatile var ready = false
        @Volatile var recording = false
        @Volatile var paused = false
        @Volatile private var startAt = 0L
        @Volatile private var pausedTotal = 0L
        @Volatile private var pauseAt = 0L

        private const val CH = "nexus_capture"
        private const val NID = 2003

        /** Lama rekaman berjalan (tidak menghitung saat dijeda), ms. */
        fun elapsedMs(): Long {
            if (!recording) return 0L
            val end = if (paused) pauseAt else SystemClock.elapsedRealtime()
            return (end - startAt - pausedTotal).coerceAtLeast(0L)
        }

        fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, ScreenCaptureService::class.java).setAction(action))
            } catch (_: Exception) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var videoUri: android.net.Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null
    private var shooting = false
    private var micTyped = false
    private var ending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> init(intent)
            ACTION_SHOT -> if (ready) takeShot() else stopSelf()
            ACTION_REC -> if (ready) startRecording() else stopSelf()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_END -> endSession()
        }
        return START_NOT_STICKY
    }

    // ---------- Sesi ----------
    private fun init(intent: Intent) {
        ending = false
        startFg(withMic = true)
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { endSession(); return }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() { main.post { onProjectionStopped() } }
            }
            p.registerCallback(cb, main)
            projection = p
            projCallback = cb
            val m = realMetrics()
            // Surface null = tidak ada beban saat tidak dipakai; surface dipasang saat cuplik/rekam.
            vd = p.createVirtualDisplay(
                "nx-capture", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main
            )
            ready = true
            refreshNotification()
        } catch (e: Exception) {
            toast("Gagal memulai tangkap layar")
            endSession()
            return
        }
        // Beri waktu dialog izin & animasi pindah-layar selesai sebelum menangkap layar game.
        val then = intent.getStringExtra(EXTRA_THEN)
        main.postDelayed({
            if (!ready) return@postDelayed
            when (then) {
                ACTION_SHOT -> takeShot()
                ACTION_REC -> startRecording()
            }
        }, 700)
    }

    private fun onProjectionStopped() {
        if (ending) return
        ready = false
        endSession()
    }

    private fun endSession() {
        if (ending && !ready && projection == null && vd == null) { stopSelf(); return }
        ending = true
        if (recording) stopRecording(keepSession = false)
        releaseAll()
        ready = false
        recording = false
        paused = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun releaseAll() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        shooting = false
        projCallback?.let { try { projection?.unregisterCallback(it) } catch (_: Exception) {} }
        projCallback = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    // ---------- Notifikasi ----------
    private fun startFg(withMic: Boolean) {
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val wantMic = withMic && Build.VERSION.SDK_INT >= 30 && ContextCompatMic.granted(this)
        try {
            if (wantMic) {
                startForeground(NID, buildNotif(), base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micTyped = true
            } else {
                startForeground(NID, buildNotif(), base)
            }
        } catch (_: Exception) {
            micTyped = false
            try { startForeground(NID, buildNotif(), base) } catch (_: Exception) {}
        }
    }

    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NID, buildNotif()) } catch (_: Exception) {}
    }

    private fun act(code: Int, action: String) = PendingIntent.getService(
        this, code, Intent(this, ScreenCaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Perekaman layar", NotificationManager.IMPORTANCE_LOW))
        val title = when {
            recording && paused -> "Rekaman dijeda"
            recording -> "Merekam layar"
            else -> "Tangkap layar siap"
        }
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_video)
            .setContentTitle(title)
            .setOngoing(true)
        if (recording) {
            if (paused) b.addAction(0, "Lanjut", act(11, ACTION_RESUME)) else b.addAction(0, "Jeda", act(12, ACTION_PAUSE))
            b.addAction(0, "Berhenti", act(13, ACTION_STOP))
        } else {
            b.addAction(0, "Akhiri", act(14, ACTION_END))
        }
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        val dm = getSystemService(DisplayManager::class.java)
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    // ---------- Cuplikan ----------
    private fun takeShot() {
        val v = vd
        if (!ready || v == null) return
        if (recording) { toast("Hentikan rekaman dulu untuk mengambil cuplikan"); return }
        if (shooting) return
        shooting = true
        val m = realMetrics()
        val w = m.widthPixels
        val h = m.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        var done = false
        r.setOnImageAvailableListener({ ir ->
            if (done) return@setOnImageAvailableListener
            val img = (try { ir.acquireLatestImage() } catch (_: Exception) { null }) ?: return@setOnImageAvailableListener
            done = true
            // Salin piksel & simpan di thread latar supaya game tidak tersendat.
            exec.execute {
                try {
                    val plane = img.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val out = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    saveImage(out)
                } catch (e: Exception) {
                    toast("Gagal mengambil cuplikan layar")
                } finally {
                    try { img.close() } catch (_: Exception) {}
                    main.post { detachReader() }
                }
            }
        }, main)
        try {
            v.resize(w, h, m.densityDpi)
            v.setSurface(r.surface)
        } catch (e: Exception) {
            toast("Gagal mengambil cuplikan layar")
            detachReader()
            return
        }
        main.postDelayed({
            if (!done) { done = true; toast("Gagal mengambil cuplikan layar"); detachReader() }
        }, 3000)
    }

    private fun detachReader() {
        if (!recording) { try { vd?.setSurface(null) } catch (_: Exception) {} }
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        shooting = false
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveImage(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Nexus_${stamp()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, DIR_IMAGES)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { toast("Gagal menyimpan cuplikan"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("Cuplikan layar disimpan di Album Nexus")
    }

    // ---------- Rekam ----------
    /** Ukuran video untuk pilihan resolusi [res] (berdasarkan sisi pendek), selalu genap & tidak lebih besar dari layar. */
    private fun sizeFor(m: DisplayMetrics, res: Int): Pair<Int, Int> {
        val short = minOf(m.widthPixels, m.heightPixels)
        val target = minOf(RES_SHORT[res.coerceIn(0, 3)], short)
        val k = target.toFloat() / short
        val w = (m.widthPixels * k).toInt() / 2 * 2
        val h = (m.heightPixels * k).toInt() / 2 * 2
        return Pair(maxOf(w, 2), maxOf(h, 2))
    }

    private fun startRecording() {
        val v = vd
        if (!ready || v == null || recording) return
        if (shooting) { toast("Tunggu cuplikan selesai"); return }
        val m = realMetrics()
        val fps = if (Prefs.recordFps60) 60 else 30
        var useMic = Prefs.recordMic && ContextCompatMic.granted(this)
        if (Prefs.recordMic && !useMic) toast("Izin mikrofon belum aktif, merekam tanpa suara mikrofon")
        if (useMic && !micTyped) {
            // Sesi dibuat sebelum izin mikrofon ada: coba naikkan tipe foreground service.
            startFg(withMic = true)
            if (!micTyped) { useMic = false; toast("Mikrofon tidak tersedia di sesi ini, mulai ulang rekam layar") }
        }
        // Coba resolusi pilihan; bila encoder tidak mendukung, turun satu tingkat sampai berhasil.
        var res = Prefs.recordRes.coerceIn(0, 3)
        while (res <= 3) {
            val (w, h) = sizeFor(m, res)
            if (tryStart(v, m, w, h, fps, useMic)) return
            res++
        }
        toast("Perekaman gagal dimulai")
        recording = false
        refreshNotification()
    }

    private fun tryStart(v: VirtualDisplay, m: DisplayMetrics, w: Int, h: Int, fps: Int, useMic: Boolean): Boolean {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Nexus_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, DIR_VIDEOS)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        var pfd: ParcelFileDescriptor? = null
        var rec: MediaRecorder? = null
        try {
            pfd = contentResolver.openFileDescriptor(uri, "w")!!
            rec = newRecorder()
            if (useMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioChannels(1)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128_000)
            }
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(fps)
            val bitrate = (w.toLong() * h * fps * 0.09).toLong().coerceIn(2_000_000L, 40_000_000L).toInt()
            rec.setVideoEncodingBitRate(bitrate)
            rec.prepare()
            v.resize(w, h, m.densityDpi)
            v.setSurface(rec.surface)
            rec.start()
        } catch (e: Exception) {
            try { rec?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            try { v.setSurface(null) } catch (_: Exception) {}
            return false
        }
        recorder = rec
        videoPfd = pfd
        videoUri = uri
        pausedTotal = 0L
        pauseAt = 0L
        startAt = SystemClock.elapsedRealtime()
        paused = false
        recording = true
        refreshNotification()
        toast("Perekaman dimulai (${w}x$h)")
        return true
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun pauseRecording() {
        if (!recording || paused) return
        try {
            recorder?.pause()
            pauseAt = SystemClock.elapsedRealtime()
            paused = true
            refreshNotification()
            toast("Rekaman dijeda")
        } catch (_: Exception) {
            toast("Perangkat tidak bisa menjeda rekaman")
        }
    }

    private fun resumeRecording() {
        if (!recording || !paused) return
        try {
            recorder?.resume()
            pausedTotal += SystemClock.elapsedRealtime() - pauseAt
            paused = false
            refreshNotification()
            toast("Rekaman dilanjutkan")
        } catch (_: Exception) {
            toast("Gagal melanjutkan rekaman")
        }
    }

    private fun stopRecording(keepSession: Boolean = true) {
        if (!recording) { if (!ready) stopSelf(); return }
        if (paused) { pausedTotal += SystemClock.elapsedRealtime() - pauseAt }
        recording = false
        paused = false
        var ok = true
        try { recorder?.stop() } catch (_: Exception) { ok = false }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { vd?.setSurface(null) } catch (_: Exception) {}
        try { videoPfd?.close() } catch (_: Exception) {}
        videoPfd = null
        val uri = videoUri
        if (uri != null) {
            if (ok) {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, cv, null, null)
                toast("Rekaman disimpan di Album Nexus")
            } else {
                contentResolver.delete(uri, null, null)
                toast("Rekaman terlalu pendek, tidak disimpan")
            }
        }
        videoUri = null
        if (keepSession && ready) refreshNotification()
    }

    private fun toast(s: String) = main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        if (recording) {
            recording = false
            paused = false
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { videoPfd?.close() } catch (_: Exception) {}
            videoUri?.let {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try { contentResolver.update(it, cv, null, null) } catch (_: Exception) {}
            }
        }
        releaseAll()
        ready = false
        exec.shutdown()
        super.onDestroy()
    }
}

object ContextCompatMic {
    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
