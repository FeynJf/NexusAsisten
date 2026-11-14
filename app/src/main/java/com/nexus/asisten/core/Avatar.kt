package com.nexus.asisten.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.nexus.asisten.R
import com.nexus.asisten.ui.NX
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

/** Foto profil Google: diunduh sekali saat masuk, disimpan di filesDir, lalu dipakai di seluruh aplikasi. */
object Avatar {
    private val ex = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun file(ctx: Context) = File(ctx.filesDir, "avatar.png")

    fun into(ctx: Context, iv: ImageView) {
        val f = file(ctx)
        val bmp = if (f.exists()) try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } else null
        if (bmp != null) {
            iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0)
            iv.setImageBitmap(bmp)
        } else {
            val p = (10 * ctx.resources.displayMetrics.density).toInt()
            iv.setPadding(p, p, p, p)
            iv.setImageResource(R.drawable.ic_person)
            iv.setColorFilter(NX.CYAN)
        }
    }

    fun fetch(ctx: Context, url: String, done: () -> Unit) {
        val app = ctx.applicationContext
        ex.execute {
            try {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bmp = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) FileOutputStream(file(app)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Throwable) {}
            main.post { done() }
        }
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}
