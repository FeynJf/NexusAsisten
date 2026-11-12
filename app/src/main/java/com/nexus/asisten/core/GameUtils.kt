package com.nexus.asisten.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nexus.asisten.MainActivity

object GameUtils {
    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    /** True bila HP mengaktifkan mode jendela (freeform). Tanpa ini Android membuka aplikasi layar penuh. */
    fun freeformSupported(ctx: Context): Boolean {
        if (ctx.packageManager.hasSystemFeature("android.software.freeform_window_management")) return true
        return try {
            Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    /** Aktifkan mode jendela lewat izin WRITE_SECURE_SETTINGS (hasil grant ADB). Perlu restart HP. */
    fun enableFreeform(ctx: Context): Boolean {
        if (!Perms.secure(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(ctx.contentResolver, "force_resizable_activities", 1)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Buka aplikasi sebagai jendela mengambang berukuran [size] (0 kecil, 1 sedang, 2 besar).
     * Mengembalikan false bila HP belum mendukung; aplikasi tetap dibuka layar penuh.
     */
    fun launchFloating(ctx: Context, pkg: String, size: Int = 1): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (!freeformSupported(ctx)) {
            ctx.startActivity(i)
            return false
        }
        val dm = ctx.resources.displayMetrics
        val land = dm.widthPixels > dm.heightPixels
        val wf = (if (land) floatArrayOf(0.40f, 0.50f, 0.62f) else floatArrayOf(0.62f, 0.78f, 0.92f))[size.coerceIn(0, 2)]
        val hf = (if (land) floatArrayOf(0.62f, 0.75f, 0.88f) else floatArrayOf(0.34f, 0.46f, 0.60f))[size.coerceIn(0, 2)]
        val w = (dm.widthPixels * wf).toInt()
        val h = (dm.heightPixels * hf).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels * 0.10f).toInt()
        val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
        ctx.startActivity(i, opts.toBundle())
        return true
    }

    fun drawableToBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Pintasan layar utama: buka game lewat Nexus (profil + pembersihan otomatis). */
    fun pinShortcut(ctx: Context, pkg: String, name: String, icon: Drawable): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_LAUNCH)
            .putExtra(MainActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = ShortcutInfoCompat.Builder(ctx, "nx_$pkg")
            .setShortLabel(name)
            .setIcon(IconCompat.createWithBitmap(drawableToBitmap(icon)))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }
}
