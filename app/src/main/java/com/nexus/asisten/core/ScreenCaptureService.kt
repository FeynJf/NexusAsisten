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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
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

/** Cuplikan layar & perekaman layar lewat MediaProjection. Hasil masuk ke Album Nexus. */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_SHOT = "nx.SHOT"
        const val ACTION_REC = "nx.REC"
        const val ACTION_STOP = "nx.REC_STOP"
        const val DIR_IMAGES = "Pictures/NexusAsisten"
        const val DIR_VIDEOS = "Movies/NexusAsisten"
        @Volatile var recording = false
        private const val CH = "nexus_capture"
        private const val NID = 2002
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var videoUri: android.net.Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        when (act) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_SHOT, ACTION_REC -> {
                intent!!
                startFg(act == ACTION_REC)
                val code = intent.getIntExtra("code", 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("data")
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { if (recording) stopRecording() } }
                }, main)
                if (act == ACTION_SHOT) takeShot() else startRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg(rec: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Perekaman layar", NotificationManager.IMPORTANCE_LOW))
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_video)
            .setContentTitle(if (rec) "Merekam layar" else "Mengambil cuplikan layar")
            .setOngoing(true)
        if (rec) {
            val pi = PendingIntent.getService(
                this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Berhenti", pi)
        }
        startForeground(NID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        val dm = getSystemService(DisplayManager::class.java)
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    private fun takeShot() {
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
                img.close()
                releaseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, main)
        vd = projection?.createVirtualDisplay(
            "nx-shot", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, main
        )
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

    private fun startRecording() {
        val m = realMetrics()
        val scale = if (Prefs.recordHd) 1f else 0.67f
        var w = (m.widthPixels * scale).toInt() / 2 * 2
        var h = (m.heightPixels * scale).toInt() / 2 * 2
        val maxSide = if (Prefs.recordHd) 1920 else 1280
        val big = maxOf(w, h)
        if (big > maxSide) {
            val k = maxSide.toFloat() / big
            w = (w * k).toInt() / 2 * 2
            h = (h * k).toInt() / 2 * 2
        }
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Nexus_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, DIR_VIDEOS)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { toast("Gagal membuat file rekaman"); releaseAll(); stopSelf(); return }
        videoUri = uri
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "w")!!
            videoPfd = pfd
            val useMic = Prefs.recordMic &&
                ContextCompatMic.granted(this)
            val rec = MediaRecorder()
            if (useMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128_000)
            }
            rec.setVideoSize(w, h)
            val fps = if (Prefs.recordFps60) 60 else 30
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(if (Prefs.recordHd) 12_000_000 else 6_000_000)
            rec.prepare()
            recorder = rec
            vd = projection?.createVirtualDisplay(
                "nx-rec", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, main
            )
            rec.start()
            recording = true
            toast("Perekaman dimulai")
        } catch (e: Exception) {
            toast("Perekaman gagal dimulai")
            recording = false
            discardVideo()
            releaseAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!recording) { stopSelf(); return }
        recording = false
        var ok = true
        try { recorder?.stop() } catch (_: Exception) { ok = false }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
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
        releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun discardVideo() {
        try { videoPfd?.close() } catch (_: Exception) {}
        videoPfd = null
        videoUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        videoUri = null
    }

    private fun releaseAll() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun toast(s: String) = main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        if (recording) { recording = false; try { recorder?.stop() } catch (_: Exception) {}; discardOrFinish() }
        releaseAll()
        super.onDestroy()
    }

    private fun discardOrFinish() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { videoPfd?.close() } catch (_: Exception) {}
        videoUri?.let {
            val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { contentResolver.update(it, cv, null, null) } catch (_: Exception) {}
        }
    }
}

object ContextCompatMic {
    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
